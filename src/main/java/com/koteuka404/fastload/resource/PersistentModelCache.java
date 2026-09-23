package com.koteuka404.fastload.resource;

import com.koteuka404.fastload.core.FastLoadEnvironment;
import net.minecraft.client.resources.IResourcePack;
import net.minecraftforge.fml.common.FMLContainerHolder;
import net.minecraftforge.fml.common.ModContainer;
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
import java.util.HashMap;
import java.util.Map;

final class PersistentModelCache {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final int MAGIC = 0x464D4331; // FMC1
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_ENTRY_BYTES = 256 * 1024;
    private static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;

    private final File file;
    private final Map<String, byte[]> entries = new HashMap<String, byte[]>();
    private long totalBytes;
    private boolean dirty;

    private PersistentModelCache(File file) {
        this.file = file;
        load();
    }

    static PersistentModelCache forPack(IResourcePack resourcePack) {
        if (!(resourcePack instanceof FMLContainerHolder)) {
            return null;
        }

        try {
            ModContainer container = ((FMLContainerHolder) resourcePack).getFMLContainer();
            File source = container == null ? null : container.getSource();
            if (source == null || !source.isFile()) {
                return null;
            }
            return new PersistentModelCache(cacheFileFor(source));
        } catch (Throwable t) {
            LOGGER.debug("Could not identify resource-pack source for persistent model cache", t);
            return null;
        }
    }

    synchronized byte[] get(String key) {
        return entries.get(key);
    }

    synchronized void put(String key, byte[] content) {
        if (key == null || content == null || content.length == 0 || content.length > MAX_ENTRY_BYTES) {
            return;
        }
        if (entries.containsKey(key) || entries.size() >= MAX_ENTRIES || totalBytes + content.length > MAX_CACHE_BYTES) {
            return;
        }
        entries.put(key, content);
        totalBytes += content.length;
        dirty = true;
    }

    synchronized void flush() {
        if (!dirty) {
            return;
        }

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
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeInt(entry.getValue().length);
                    out.write(entry.getValue());
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
            dirty = false;
        } catch (IOException e) {
            LOGGER.debug("Could not write persistent model cache {}", file, e);
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

    private void load() {
        if (!file.isFile() || file.length() > MAX_CACHE_BYTES + 1024L) {
            return;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) {
                return;
            }

            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                return;
            }

            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                int length = in.readInt();
                if (length <= 0 || length > MAX_ENTRY_BYTES || totalBytes + length > MAX_CACHE_BYTES) {
                    return;
                }
                byte[] content = new byte[length];
                in.readFully(content);
                entries.put(key, content);
                totalBytes += length;
            }

            if (in.read() != -1) {
                return;
            }
        } catch (EOFException e) {
            entries.clear();
            totalBytes = 0L;
        } catch (IOException e) {
            entries.clear();
            totalBytes = 0L;
            LOGGER.debug("Ignoring unreadable persistent model cache {}", file, e);
        }
    }

    private static File cacheFileFor(File source) throws IOException {
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
        return new File(root, name + "-" + pathHash + ".fmc");
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
