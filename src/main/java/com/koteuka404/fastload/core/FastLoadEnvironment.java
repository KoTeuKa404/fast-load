package com.koteuka404.fastload.core;

import java.io.File;

public final class FastLoadEnvironment {
    private static volatile File minecraftHome;
    private static volatile File coremodLocation;
    private static volatile boolean runtimeDeobfuscationEnabled;

    private FastLoadEnvironment() {
    }

    public static void initialize(File mcHome, File coremod, boolean runtimeDeobfuscation) {
        if (mcHome != null) {
            minecraftHome = mcHome;
        }
        coremodLocation = coremod;
        runtimeDeobfuscationEnabled = runtimeDeobfuscation;
    }

    public static File getMinecraftHome() {
        File home = minecraftHome;
        if (home != null) {
            return home;
        }
        return new File(".").getAbsoluteFile();
    }

    public static File getCoremodLocation() {
        return coremodLocation;
    }

    public static boolean isRuntimeDeobfuscationEnabled() {
        return runtimeDeobfuscationEnabled;
    }
}
