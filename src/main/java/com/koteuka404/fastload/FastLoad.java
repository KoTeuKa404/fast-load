package com.koteuka404.fastload;

import com.koteuka404.fastload.profiler.LoadStats;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(
        modid = FastLoad.MODID,
        name = FastLoad.NAME,
        version = FastLoad.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "*"
)
public final class FastLoad {
    public static final String MODID = "fastload";
    public static final String NAME = "FastLoad";
    public static final String VERSION = "0.7.5";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LoadStats.logSummary();
    }
}
