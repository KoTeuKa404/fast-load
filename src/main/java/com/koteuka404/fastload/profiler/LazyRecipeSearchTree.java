package com.koteuka404.fastload.profiler;

import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.util.RecipeBookClient;
import net.minecraft.client.util.SearchTree;
import net.minecraft.item.Item;
import net.minecraft.util.text.TextFormatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

final class LazyRecipeSearchTree extends SearchTree<RecipeList> {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");

    private boolean built;

    LazyRecipeSearchTree() {
        super(
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
    }

    @Override
    public synchronized List<RecipeList> search(String searchText) {
        ensureBuilt();
        return super.search(searchText);
    }

    @Override
    public synchronized void recalculate() {
        if (built) {
            super.recalculate();
        }
    }

    private void ensureBuilt() {
        if (built) {
            return;
        }

        long start = System.nanoTime();
        for (RecipeList recipeList : RecipeBookClient.ALL_RECIPES) {
            super.add(recipeList);
        }

        // SearchTree#add already indexed every entry; only finalize the suffix arrays.
        this.byName.generate();
        this.byId.generate();
        built = true;

        LOGGER.info(
                "FastLoad lazy recipe search tree built on first use: {} recipe groups in {} ms",
                RecipeBookClient.ALL_RECIPES.size(),
                (System.nanoTime() - start) / 1_000_000L
        );
    }
}
