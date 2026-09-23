package com.koteuka404.fastload.cache;

import com.koteuka404.fastload.core.FastLoadEnvironment;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.ModContainerFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Type;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class CacheManager {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final String CACHE_DIR_NAME = "fastload-cache";
    private static final String CACHE_ABI = "forge-1.12.2-discovery-v1";

    static final boolean ENABLED = getBoolean("fastload.cache", true);
    private static final boolean STRICT_HASHES = getBoolean("fastload.strictHashes", false);
    private static final boolean HASH_ON_METADATA_CHANGE = getBoolean("fastload.hashOnMetadataChange", true);
    private static final boolean HASH_ON_SAVE = getBoolean("fastload.hashOnSave", false);

    private CacheManager() {
    }

    static CachedJar loadIfValid(File source) {
        if (!ENABLED || source == null || !source.isFile()) {
            return null;
        }

        File cacheFile = cacheFileFor(source);
        if (!cacheFile.isFile()) {
            return null;
        }

        try {
            CachedJar cache = CacheCodec.read(cacheFile);
            if (cache == null) {
                return null;
            }
            if (!forgeVersion().equals(cache.forgeVersion)) {
                return null;
            }
            if (!containerSignature().equals(cache.containerSignature)) {
                return null;
            }
            if (source.length() != cache.sourceSize) {
                return null;
            }

            long currentModified = source.lastModified();
            boolean metadataMatches = currentModified == cache.sourceModified;
            if (metadataMatches && !STRICT_HASHES) {
                return cache;
            }

            if (!metadataMatches && !HASH_ON_METADATA_CHANGE) {
                return null;
            }

            if (cache.sourceSha256 == null) {
                return null;
            }

            byte[] currentHash = sha256(source);
            if (!constantTimeEquals(currentHash, cache.sourceSha256)) {
                return null;
            }

            if (!metadataMatches) {
                cache.sourceModified = currentModified;
                try {
                    CacheCodec.writeAtomic(cacheFile, cache);
                } catch (IOException e) {
                    LOGGER.debug("Could not refresh cache metadata for {}", source.getName(), e);
                }
            }
            return cache;
        } catch (Exception e) {
            LOGGER.warn("Ignoring invalid FastLoad cache for {} and rebuilding it", source.getName(), e);
            safeDelete(cacheFile);
            return null;
        }
    }

    static void save(File source, CachedJar cache) throws IOException {
        if (!ENABLED || source == null || cache == null || !source.isFile()) {
            return;
        }
        cache.sourceSize = source.length();
        cache.sourceModified = source.lastModified();
        cache.sourceSha256 = HASH_ON_SAVE || STRICT_HASHES ? sha256(source) : null;
        cache.forgeVersion = forgeVersion();
        cache.containerSignature = containerSignature();
        CacheCodec.writeAtomic(cacheFileFor(source), cache);
    }

    static void invalidate(File source) {
        if (source != null) {
            safeDelete(cacheFileFor(source));
        }
    }

    static String forgeVersion() {
        String forge;
        try {
            forge = ForgeVersion.getVersion();
        } catch (Throwable ignored) {
            forge = "unknown";
        }
        return CACHE_ABI + ":" + forge;
    }

    static String containerSignature() {
        List<String> types = new ArrayList<String>();
        for (Map.Entry<Type, Constructor<? extends ModContainer>> entry : ModContainerFactory.modTypes.entrySet()) {
            String declaring = entry.getValue().getDeclaringClass().getName();
            types.add(entry.getKey().getDescriptor() + "->" + declaring);
        }
        Collections.sort(types);
        StringBuilder builder = new StringBuilder();
        for (String type : types) {
            builder.append(type).append('\n');
        }
        return hex(digest(builder.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static File cacheFileFor(File source) {
        File root = new File(FastLoadEnvironment.getMinecraftHome(), CACHE_DIR_NAME + File.separator + "v1");
        String fileName = sanitize(source.getName());
        String pathKey;
        try {
            pathKey = source.getCanonicalPath();
        } catch (IOException e) {
            pathKey = source.getAbsolutePath();
        }
        String pathHash = hex(digest(pathKey.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        return new File(root, fileName + "-" + pathHash + ".flc");
    }

    private static String sanitize(String fileName) {
        String sanitized = fileName == null ? "unknown" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.length() > 80) {
            sanitized = sanitized.substring(0, 80);
        }
        return sanitized;
    }

    private static byte[] sha256(File file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[1024 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file), buffer.length)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return digest.digest();
    }

    private static byte[] digest(byte[] input) {
        MessageDigest digest = newDigest();
        return digest.digest(input);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
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

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length; i++) {
            diff |= left[i] ^ right[i];
        }
        return diff == 0;
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static void safeDelete(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            LOGGER.debug("Could not delete cache file {}", file, e);
        }
    }
}
