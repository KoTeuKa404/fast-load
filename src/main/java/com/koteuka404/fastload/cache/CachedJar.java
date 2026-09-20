package com.koteuka404.fastload.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CachedJar {
    long sourceSize;
    long sourceModified;
    byte[] sourceSha256;
    String forgeVersion;
    String containerSignature;
    long scanNanos;
    final List<String> classEntries = new ArrayList<String>();
    final List<CachedAsmData> asmData = new ArrayList<CachedAsmData>();
    final List<CachedMod> mods = new ArrayList<CachedMod>();

    static final class CachedAsmData {
        String annotationName;
        String className;
        String objectName;
        Map<String, Object> annotationInfo;
    }

    static final class CachedMod {
        String annotationName;
        String className;
        Map<String, Object> descriptor;
        int classVersion;
    }
}
