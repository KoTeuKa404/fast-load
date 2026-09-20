package com.koteuka404.fastload.cache;

import com.koteuka404.fastload.profiler.LoadStats;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.FMLModContainer;
import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.ModContainerFactory;
import net.minecraftforge.fml.common.discovery.ASMDataTable;
import net.minecraftforge.fml.common.discovery.ITypeDiscoverer;
import net.minecraftforge.fml.common.discovery.ModCandidate;
import net.minecraftforge.fml.common.discovery.asm.ASMModParser;
import net.minecraftforge.fml.common.discovery.asm.ModAnnotation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;

public final class FastJarScanner {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final Field ASM_INTERFACES_FIELD = findInterfacesField();

    private FastJarScanner() {
    }

    public static List<ModContainer> discover(ModCandidate candidate, ASMDataTable table) {
        File source = candidate.getModContainer();

        if (CacheManager.ENABLED && ASM_INTERFACES_FIELD != null && source != null && source.isFile()) {
            CachedJar cached = CacheManager.loadIfValid(source);
            if (cached != null) {
                try {
                    long started = System.nanoTime();
                    List<ModContainer> restored = restore(candidate, table, source, cached);
                    long elapsed = System.nanoTime() - started;
                    LoadStats.cacheHit(cached.classEntries.size(), elapsed, cached.scanNanos);
                    LOGGER.debug("Cache HIT: {} ({} classes, {} ms)", source.getName(), cached.classEntries.size(), elapsed / 1_000_000L);
                    return restored;
                } catch (Exception e) {
                    LOGGER.warn("Could not restore FastLoad cache for {}; rebuilding", source.getName(), e);
                    CacheManager.invalidate(source);
                    LoadStats.cacheFailure();
                }
            }
        }

        return scanNormally(candidate, table, source);
    }

    private static List<ModContainer> restore(ModCandidate candidate, ASMDataTable table, File source, CachedJar cache) throws Exception {
        MetadataCollection metadata = readMetadata(source);
        List<ModContainer> preparedContainers = prepareContainers(candidate, metadata, cache.mods);

        for (String classEntry : cache.classEntries) {
            candidate.addClassEntry(classEntry);
        }
        for (CachedJar.CachedAsmData data : cache.asmData) {
            table.addASMData(candidate, data.annotationName, data.className, data.objectName, data.annotationInfo);
        }
        for (ModContainer container : preparedContainers) {
            table.addContainer(container);
        }
        return preparedContainers;
    }

    private static List<ModContainer> prepareContainers(ModCandidate candidate, MetadataCollection metadata, List<CachedJar.CachedMod> cachedMods) throws Exception {
        List<ModContainer> containers = new ArrayList<ModContainer>();
        for (CachedJar.CachedMod cachedMod : cachedMods) {
            Constructor<? extends ModContainer> constructor = findContainerConstructor(cachedMod.annotationName);
            if (constructor == null) {
                throw new IOException("Container type is no longer registered: " + cachedMod.annotationName);
            }

            ModContainer container = constructor.newInstance(cachedMod.className, candidate, cachedMod.descriptor);
            if (!container.shouldLoadInEnvironment()) {
                continue;
            }
            container.bindMetadata(metadata);
            container.setClassVersion(cachedMod.classVersion);
            containers.add(container);
        }
        return containers;
    }

    private static List<ModContainer> scanNormally(ModCandidate candidate, ASMDataTable table, File source) {
        List<ModContainer> foundMods = new ArrayList<ModContainer>();
        CachedJar cache = new CachedJar();
        long started = System.nanoTime();
        long scannedClasses = 0L;
        boolean cacheable = CacheManager.ENABLED && ASM_INTERFACES_FIELD != null && source != null && source.isFile();
        boolean scanCompleted = false;

        FMLLog.log.debug("Examining file {} for potential mods (FastLoad)", source == null ? "<null>" : source.getName());

        if (source == null || !source.isFile()) {
            return foundMods;
        }

        try (JarFile jar = new JarFile(source)) {
            MetadataCollection metadata = readMetadata(jar, source);

            for (ZipEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (name != null && name.startsWith("__MACOSX")) {
                    continue;
                }
                Matcher match = ITypeDiscoverer.classFile.matcher(name == null ? "" : name);
                if (!match.matches()) {
                    continue;
                }

                ASMModParser parser;
                try (InputStream input = jar.getInputStream(entry)) {
                    parser = new ASMModParser(input);
                } catch (LoaderException e) {
                    FMLLog.log.error("There was a problem reading the entry {} in the jar {} - probably a corrupt zip", name, source.getPath(), e);
                    throw e;
                }

                scannedClasses++;
                candidate.addClassEntry(name);
                parser.validate();
                parser.sendToTable(table, candidate);

                if (cacheable) {
                    try {
                        recordClass(cache, parser, name);
                    } catch (Exception e) {
                        cacheable = false;
                        LOGGER.debug("{} uses annotation data FastLoad cannot safely capture; this jar will not be cached", source.getName(), e);
                    }
                }

                ModContainer container = ModContainerFactory.instance().build(parser, source, candidate);
                if (container != null) {
                    table.addContainer(container);
                    foundMods.add(container);
                    container.bindMetadata(metadata);
                    container.setClassVersion(parser.getClassVersion());
                }
            }
            scanCompleted = true;
        } catch (Exception e) {
            FMLLog.log.warn("Zip file {} failed to read properly, it will be ignored", source.getName(), e);
        }

        long scanNanos = System.nanoTime() - started;
        cache.scanNanos = scanNanos;
        LoadStats.cacheMiss(scannedClasses, scanNanos);

        if (cacheable && scanCompleted) {
            try {
                CacheManager.save(source, cache);
                LOGGER.debug("Cache MISS: {} -> stored {} classes / {} ASM entries ({} ms)", source.getName(), cache.classEntries.size(), cache.asmData.size(), scanNanos / 1_000_000L);
            } catch (Exception e) {
                LoadStats.cacheFailure();
                LOGGER.warn("Could not write FastLoad cache for {}; Forge will continue normally", source.getName(), e);
            }
        }

        return foundMods;
    }

    private static void recordClass(CachedJar cache, ASMModParser parser, String classEntry) throws IllegalAccessException {
        cache.classEntries.add(classEntry);

        for (ModAnnotation annotation : parser.getAnnotations()) {
            CachedJar.CachedAsmData asm = new CachedJar.CachedAsmData();
            asm.annotationName = annotation.getASMType().getClassName();
            asm.className = parser.getASMType().getClassName();
            asm.objectName = annotation.getMember();
            asm.annotationInfo = copyMap(annotation.getValues());
            cache.asmData.add(asm);
        }

        @SuppressWarnings("unchecked")
        Set<String> interfaces = (Set<String>) ASM_INTERFACES_FIELD.get(parser);
        for (String intf : interfaces) {
            CachedJar.CachedAsmData asm = new CachedJar.CachedAsmData();
            asm.annotationName = intf;
            asm.className = parser.getASMType().getInternalName();
            asm.objectName = null;
            asm.annotationInfo = null;
            cache.asmData.add(asm);
        }

        for (ModAnnotation annotation : parser.getAnnotations()) {
            if (ModContainerFactory.modTypes.containsKey(annotation.getASMType())) {
                Constructor<? extends ModContainer> constructor = ModContainerFactory.modTypes.get(annotation.getASMType());
                if (!FMLModContainer.class.equals(constructor.getDeclaringClass())) {
                    throw new IllegalStateException("Custom ModContainer types are not cached in v0.1: " + constructor.getDeclaringClass().getName());
                }
                CachedJar.CachedMod mod = new CachedJar.CachedMod();
                mod.annotationName = annotation.getASMType().getClassName();
                mod.className = parser.getASMType().getClassName();
                mod.descriptor = copyMap(annotation.getValues());
                mod.classVersion = parser.getClassVersion();
                cache.mods.add(mod);
                break;
            }
        }
    }

    private static MetadataCollection readMetadata(File source) throws IOException {
        try (JarFile jar = new JarFile(source)) {
            return readMetadata(jar, source);
        }
    }

    private static MetadataCollection readMetadata(JarFile jar, File source) throws IOException {
        ZipEntry modInfo = jar.getEntry("mcmod.info");
        if (modInfo == null) {
            return MetadataCollection.from(null, "");
        }
        try (InputStream input = jar.getInputStream(modInfo)) {
            return MetadataCollection.from(input, source.getName());
        }
    }

    private static Constructor<? extends ModContainer> findContainerConstructor(String annotationName) {
        for (Map.Entry<Type, Constructor<? extends ModContainer>> entry : ModContainerFactory.modTypes.entrySet()) {
            if (entry.getKey().getClassName().equals(annotationName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, Object> copyMap(Map<String, Object> input) {
        return input == null ? null : new HashMap<String, Object>(input);
    }

    private static Field findInterfacesField() {
        try {
            Field field = ASMModParser.class.getDeclaredField("interfaces");
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            LOGGER.warn("FastLoad cannot access ASMModParser interfaces; caching is disabled to preserve Forge semantics", t);
            return null;
        }
    }
}
