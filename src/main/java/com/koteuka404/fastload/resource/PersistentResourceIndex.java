package com.koteuka404.fastload.resource;

import com.koteuka404.fastload.core.FastLoadEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Persistent asset-name index for one unchanged mod JAR. It lets the resource
 * existence hook answer both positive and negative queries without reopening
 * the ZIP for every lookup.
 */
final class PersistentResourceIndex {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final int MAGIC = 0x46524931; // FRI1
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 500_000;
    private static final long MAX_INDEX_BYTES = 32L * 1024L * 1024L;

    private final File file;
    private final Set<String> resources = new HashSet<String>();
    private boolean available;

    private PersistentResourceIndex(File source) {
        file = cacheFileFor(source);
        if (!load()) {
            build(source);
        }
    }

    static PersistentResourceIndex forSource(File source) {
        if (source == null || !source.isFile()) {
            return null;
        }
        try {
            return new PersistentResourceIndex(source);
        } catch (Throwable t) {
            LOGGER.debug("Could not initialize persistent mod-resource index", t);
            return null;
        }
    }

    Boolean contains(String resourceName) {
        return available ? Boolean.valueOf(resources.contains(resourceName)) : null;
    }

    int size() {
        return resources.size();
    }

    private boolean load() {
        if (!file.isFile() || file.length() > MAX_INDEX_BYTES + 1024L) {
            return false;
        }

        Set<String> loaded = new HashSet<String>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) {
                return false;
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                if (!loaded.add(in.readUTF())) {
                    return false;
                }
            }
            if (in.read() != -1) {
                return false;
            }
            resources.addAll(loaded);
            available = true;
            return true;
        } catch (EOFException e) {
            return false;
        } catch (IOException e) {
            LOGGER.debug("Ignoring unreadable persistent mod-resource index {}", file, e);
            return false;
        }
    }

    private void build(File source) {
        Set<String> discovered = new HashSet<String>();
        try (JarFile jar = new JarFile(source)) {
            java.util.Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("assets/") || name.endsWith("/")) {
                    continue;
                }

                int namespaceEnd = name.indexOf('/', "assets/".length());
                if (namespaceEnd < 0 || namespaceEnd + 1 >= name.length()) {
                    continue;
                }

                discovered.add(name.substring("assets/".length(), namespaceEnd)
                        + ":" + name.substring(namespaceEnd + 1));
                if (discovered.size() > MAX_ENTRIES) {
                    LOGGER.debug("Skipping persistent resource index for {}: too many entries", source.getName());
                    return;
                }
            }

            resources.addAll(discovered);
            available = true;
            writeAtomic(discovered);
        } catch (IOException e) {
            LOGGER.debug("Could not build persistent mod-resource index for {}", source.getName(), e);
        }
    }

    private void writeAtomic(Set<String> entries) {
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
                out.writeInt(entries.size());
                for (String entry : entries) {
                    out.writeUTF(entry);
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
            LOGGER.debug("Could not write persistent mod-resource index {}", file, e);
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

    private static File cacheFileFor(File source) {
        String path;
        try {
            path = source.getCanonicalPath();
        } catch (IOException e) {
            path = source.getAbsolutePath();
        }

        String identity = path + "\n" + source.length() + "\n" + source.lastModified();
        String pathHash = hex(digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        String name = source.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 80) {
            name = name.substring(0, 80);
        }

        File root = new File(FastLoadEnvironment.getMinecraftHome(), "fastload-cache" + File.separator + "resources-v1");
        return new File(root, name + "-" + pathHash + ".fmi");
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
}
