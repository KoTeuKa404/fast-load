package com.koteuka404.fastload.resource;

import com.koteuka404.fastload.core.FastLoadEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Direct lookup for Thaumcraft research files.
 *
 * Thaumcraft uses Class.getResourceAsStream instead of Minecraft's resource
 * manager for research JSON. That makes the normal FastLoad resource hooks
 * invisible to it and causes the class loader to search every mod JAR for each
 * file. This small index keeps the same class-loader fallback for ambiguous or
 * unavailable entries, while making the common unchanged-mod case direct.
 */
final class ClasspathResearchCache {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final int MAGIC = 0x46524331; // FRC1
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 100_000;

    private static final Object LOCK = new Object();
    private static volatile Snapshot snapshot;
    private static volatile boolean initialized;

    private ClasspathResearchCache() {
    }

    static InputStream open(Object ownerObject, Object pathObject) {
        String path = pathObject instanceof String ? (String) pathObject : null;
        if (path == null || !path.startsWith("/assets/")) {
            return fallback(ownerObject, path);
        }

        Snapshot current = getSnapshot();
        String resource = path.substring(1);
        File source = current.owners.get(resource);
        if (source != null && !current.ambiguous.contains(resource)) {
            try {
                JarFile jar = new JarFile(source);
                ZipEntry entry = jar.getEntry(resource);
                if (entry != null) {
                    return new JarEntryInputStream(jar, jar.getInputStream(entry));
                }
                jar.close();
            } catch (IOException e) {
                LOGGER.debug("Direct research resource lookup failed for {}", resource, e);
            }
        }

        return fallback(ownerObject, path);
    }

    private static InputStream fallback(Object ownerObject, String path) {
        if (ownerObject instanceof Class && path != null) {
            return ((Class<?>) ownerObject).getResourceAsStream(path);
        }
        return null;
    }

    private static Snapshot getSnapshot() {
        Snapshot current = snapshot;
        if (current != null) {
            return current;
        }

        synchronized (LOCK) {
            current = snapshot;
            if (current == null) {
                current = loadOrBuild();
                snapshot = current;
                initialized = true;
            }
            return current;
        }
    }

    private static Snapshot loadOrBuild() {
        List<File> jars = findModJars(new File(FastLoadEnvironment.getMinecraftHome(), "mods"));
        String fingerprint = fingerprint(jars);
        File cacheFile = cacheFile(fingerprint);

        Snapshot loaded = load(cacheFile, fingerprint);
        if (loaded != null) {
            LOGGER.info("FastLoad research classpath index: {} entries restored", loaded.owners.size());
            return loaded;
        }

        Snapshot built = build(jars);
        write(cacheFile, fingerprint, built);
        LOGGER.info("FastLoad research classpath index: {} entries built from {} mod JARs",
                built.owners.size(), jars.size());
        return built;
    }

    private static Snapshot build(List<File> jars) {
        Map<String, File> owners = new HashMap<String, File>();
        Set<String> ambiguous = new HashSet<String>();

        for (File source : jars) {
            try (JarFile jar = new JarFile(source)) {
                java.util.Enumeration<? extends ZipEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!isResearchResource(name)) {
                        continue;
                    }

                    File previous = owners.putIfAbsent(name, source);
                    if (previous != null && !sameFile(previous, source)) {
                        ambiguous.add(name);
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("Could not index research resources in {}", source, e);
            }
        }

        return new Snapshot(owners, ambiguous);
    }

    private static boolean isResearchResource(String name) {
        if (!name.startsWith("assets/") || name.endsWith("/")) {
            return false;
        }
        int namespaceEnd = name.indexOf('/', "assets/".length());
        return namespaceEnd >= 0
                && namespaceEnd + 1 < name.length()
                && name.substring(namespaceEnd + 1).startsWith("research/");
    }

    private static List<File> findModJars(File root) {
        List<File> result = new ArrayList<File>();
        collectModJars(root, result);
        Collections.sort(result, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return canonical(left).compareTo(canonical(right));
            }
        });
        return result;
    }

    private static void collectModJars(File file, List<File> result) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isFile()) {
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".jar") || name.endsWith(".zip")) {
                result.add(file);
            }
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectModJars(child, result);
        }
    }

    private static Snapshot load(File file, String fingerprint) {
        if (!file.isFile() || file.length() > 16L * 1024L * 1024L) {
            return null;
        }
        Map<String, File> owners = new HashMap<String, File>();
        Set<String> ambiguous = new HashSet<String>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION
                    || !fingerprint.equals(in.readUTF())) {
                return null;
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                return null;
            }
            for (int i = 0; i < count; i++) {
                String resource = in.readUTF();
                File source = new File(in.readUTF());
                if (!isResearchResource(resource) || !source.isFile()) {
                    return null;
                }
                owners.put(resource, source);
            }
            int ambiguousCount = in.readInt();
            if (ambiguousCount < 0 || ambiguousCount > MAX_ENTRIES) {
                return null;
            }
            for (int i = 0; i < ambiguousCount; i++) {
                ambiguous.add(in.readUTF());
            }
            return new Snapshot(owners, ambiguous);
        } catch (IOException e) {
            LOGGER.debug("Ignoring unreadable research classpath index {}", file, e);
            return null;
        }
    }

    private static void write(File file, String fingerprint, Snapshot value) {
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            return;
        }

        File temp = null;
        boolean moved = false;
        try {
            temp = File.createTempFile(file.getName(), ".tmp", parent);
            try (FileOutputStream fos = new FileOutputStream(temp);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeUTF(fingerprint);
                out.writeInt(value.owners.size());
                for (Map.Entry<String, File> entry : value.owners.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(canonical(entry.getValue()));
                }
                out.writeInt(value.ambiguous.size());
                for (String resource : value.ambiguous) {
                    out.writeUTF(resource);
                }
                out.flush();
                fos.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } catch (IOException e) {
            LOGGER.debug("Could not write research classpath index {}", file, e);
        } finally {
            if (!moved && temp != null) {
                try {
                    Files.deleteIfExists(temp.toPath());
                } catch (IOException ignored) {
                    // Best-effort cleanup only.
                }
            }
        }
    }

    private static File cacheFile(String fingerprint) {
        String hash = hex(digest(fingerprint.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        return new File(new File(FastLoadEnvironment.getMinecraftHome(), "fastload-cache" + File.separator + "resources-v1"),
                "research-classpath-" + hash + ".frc");
    }

    private static String fingerprint(List<File> jars) {
        StringBuilder value = new StringBuilder();
        for (File jar : jars) {
            value.append(canonical(jar)).append('\n')
                    .append(jar.length()).append('\n')
                    .append(jar.lastModified()).append('\n');
        }
        return value.toString();
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static boolean sameFile(File left, File right) {
        return canonical(left).equals(canonical(right));
    }

    private static byte[] digest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }

    static boolean isInitialized() {
        return initialized;
    }

    static int indexedEntries() {
        Snapshot current = snapshot;
        return current == null ? 0 : current.owners.size();
    }

    private static final class Snapshot {
        private final Map<String, File> owners;
        private final Set<String> ambiguous;

        private Snapshot(Map<String, File> owners, Set<String> ambiguous) {
            this.owners = owners;
            this.ambiguous = ambiguous;
        }
    }

    private static final class JarEntryInputStream extends FilterInputStream {
        private final JarFile jar;

        private JarEntryInputStream(JarFile jar, InputStream input) {
            super(input);
            this.jar = jar;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                jar.close();
            }
        }
    }
}
