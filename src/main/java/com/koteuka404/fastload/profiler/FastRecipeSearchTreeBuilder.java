package com.koteuka404.fastload.profiler;

import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.util.RecipeBookClient;
import net.minecraft.client.util.SearchTree;
import net.minecraft.client.util.SearchTreeManager;
import net.minecraft.item.Item;
import net.minecraft.util.text.TextFormatting;

import java.util.List;
import java.util.stream.Collectors;

final class FastRecipeSearchTreeBuilder {
    private FastRecipeSearchTreeBuilder() {
    }

    static void rebuild(SearchTreeManager manager) {
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
}
