package com.koteuka404.fastload.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.io.File;
import java.util.Map;

@IFMLLoadingPlugin.Name("FastLoad")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions({"com.koteuka404.fastload"})
public final class FastLoadCorePlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{
                "com.koteuka404.fastload.core.JarDiscovererTransformer",
                "com.koteuka404.fastload.core.ForgeMappingTransformer",
                "com.koteuka404.fastload.core.ResourceManagerTransformer"
        };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        File mcLocation = valueAsFile(data.get("mcLocation"));
        File coremodLocation = valueAsFile(data.get("coremodLocation"));
        boolean runtimeDeobfuscation = Boolean.TRUE.equals(data.get("runtimeDeobfuscationEnabled"));
        FastLoadEnvironment.initialize(mcLocation, coremodLocation, runtimeDeobfuscation);
    }

    private static File valueAsFile(Object value) {
        return value instanceof File ? (File) value : null;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
