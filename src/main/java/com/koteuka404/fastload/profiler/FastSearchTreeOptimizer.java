package com.koteuka404.fastload.profiler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.util.SearchTreeManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLModIdMappingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FastSearchTreeOptimizer {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("fastload.optimizeFinalSearchTrees", "true"));
    private static final boolean LAZY_RECIPES =
            Boolean.parseBoolean(System.getProperty("fastload.lazyRecipeSearchTree", "true"));

    /*
     * Only optimize the first empty, frozen remap event. Forge uses that event
     * at the end of initial registry freezing even though no IDs actually
     * changed. Later frozen events can happen when registry state is restored,
     * so those keep vanilla behavior.
     */
    private static boolean initialFreezeOptimized;

    private FastSearchTreeOptimizer() {
    }

    public static synchronized void reload(FMLModIdMappingEvent event) {
        if (!ENABLED) {
            fullReload("optimization disabled");
            return;
        }

        if (event == null || !event.isFrozen || !event.getRegistries().isEmpty()) {
            fullReload("event contains real remaps or is not the initial freeze");
            return;
        }

        if (initialFreezeOptimized) {
            fullReload("subsequent frozen mapping event");
            return;
        }

        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            SearchTreeManager manager = minecraft.getSearchTreeManager();

            if (manager == null || manager.get(SearchTreeManager.ITEMS) == null) {
                fullReload("existing item search tree is unavailable");
                return;
            }

            long start = System.nanoTime();

            if (LAZY_RECIPES) {
                manager.register(SearchTreeManager.RECIPES, new LazyRecipeSearchTree());
            } else {
                FastRecipeSearchTreeBuilder.rebuild(manager);
            }

            initialFreezeOptimized = true;

            LOGGER.info(
                    "FastLoad search-tree optimization: reused existing item tree, recipeTreeMode={}, setup={} ms",
                    LAZY_RECIPES ? "lazy" : "eager",
                    nanosToMillis(System.nanoTime() - start)
            );
        } catch (Throwable t) {
            LOGGER.warn("FastLoad optimized search-tree setup failed; falling back to vanilla full rebuild.", t);
            fullReload("optimized setup failed");
        }
    }

    private static void fullReload(String reason) {
        LOGGER.debug("FastLoad search-tree optimization using vanilla rebuild: {}", reason);
        FMLCommonHandler.instance().reloadSearchTrees();
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
