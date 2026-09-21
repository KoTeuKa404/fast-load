package com.koteuka404.fastload.profiler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.util.RecipeBookClient;
import net.minecraft.client.util.SearchTree;
import net.minecraft.client.util.SearchTreeManager;
import net.minecraft.item.Item;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLModIdMappingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

public final class FastSearchTreeOptimizer {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("fastload.optimizeFinalSearchTrees", "true"));

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
            rebuildRecipeTree(manager);
            initialFreezeOptimized = true;

            LOGGER.info(
                    "FastLoad search-tree optimization: reused existing item tree, rebuilt recipe tree only ({} recipe groups, {} ms)",
                    RecipeBookClient.ALL_RECIPES.size(),
                    nanosToMillis(System.nanoTime() - start)
            );
        } catch (Throwable t) {
            LOGGER.warn("FastLoad optimized search-tree rebuild failed; falling back to vanilla full rebuild.", t);
            fullReload("optimized rebuild failed");
        }
    }

    private static void rebuildRecipeTree(SearchTreeManager manager) {
        SearchTree<RecipeList> recipeTree = new SearchTree<RecipeList>(
                recipeList -> (List<String>) recipeList.getRecipes().stream()
                        .flatMap(recipe -> recipe.getRecipeOutput()
                                .getTooltip(null, ITooltipFlag.TooltipFlags.NORMAL)
                                .stream())
                        .map(TextFormatting::getTextWithoutFormattingCodes)
                        .map(String::trim)
                        .filter(text -> !text.isEmpty())
                        .collect(Collectors.toList()),
                recipeList -> recipeList.getRecipes().stream()
                        .map(recipe -> Item.REGISTRY.getNameForObject(recipe.getRecipeOutput().getItem()))
                        .collect(Collectors.toList())
        );

        RecipeBookClient.ALL_RECIPES.forEach(recipeTree::add);
        recipeTree.recalculate();
        manager.register(SearchTreeManager.RECIPES, recipeTree);
    }

    private static void fullReload(String reason) {
        LOGGER.debug("FastLoad search-tree optimization using vanilla rebuild: {}", reason);
        FMLCommonHandler.instance().reloadSearchTrees();
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }
}
