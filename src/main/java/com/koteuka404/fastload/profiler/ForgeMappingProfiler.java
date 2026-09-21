package com.koteuka404.fastload.profiler;

import com.koteuka404.fastload.resource.FastResourceIO;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.stats.StatList;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLModIdMappingEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.oredict.OreDictionary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ForgeMappingProfiler {
    private static final Logger LOGGER = LogManager.getLogger("FastLoad");

    private ForgeMappingProfiler() {
    }

    public static void mappingChanged(final FMLModIdMappingEvent event) {
        long totalStart = System.nanoTime();

        long oreDictionary = timed("OreDictionary.rebakeMap", new Step() {
            @Override
            public void run() {
                OreDictionary.rebakeMap();
            }
        });

        long statList = timed("StatList.reinit", new Step() {
            @Override
            public void run() {
                StatList.reinit();
            }
        });

        long ingredients = timed("Ingredient.invalidateAll", new Step() {
            @Override
            public void run() {
                Ingredient.invalidateAll();
            }
        });

        long recipeBook = timed("FMLCommonHandler.resetClientRecipeBook", new Step() {
            @Override
            public void run() {
                FMLCommonHandler.instance().resetClientRecipeBook();
            }
        });

        long searchTrees = timed("search-tree rebuild", new Step() {
            @Override
            public void run() {
                if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
                    FastSearchTreeOptimizer.reload(event);
                } else {
                    FMLCommonHandler.instance().reloadSearchTrees();
                }
            }
        });

        long creativeSettings = timed("FMLCommonHandler.reloadCreativeSettings", new Step() {
            @Override
            public void run() {
                FMLCommonHandler.instance().reloadCreativeSettings();
            }
        });

        long total = System.nanoTime() - totalStart;
        LOGGER.info(
                "FastLoad ModIdMapping profile: total={} ms, oreDictionary={} ms, statList={} ms, ingredients={} ms, recipeBook={} ms, searchTrees={} ms, creativeSettings={} ms, frozen={}, remapRegistries={}",
                nanosToMillis(total),
                nanosToMillis(oreDictionary),
                nanosToMillis(statList),
                nanosToMillis(ingredients),
                nanosToMillis(recipeBook),
                nanosToMillis(searchTrees),
                nanosToMillis(creativeSettings),
                event != null && event.isFrozen,
                event == null ? -1 : event.getRegistries().size()
        );
        FastResourceIO.logSummary();
    }

    private static long timed(String name, Step step) {
        long start = System.nanoTime();
        try {
            step.run();
        } finally {
            long elapsed = System.nanoTime() - start;
            LOGGER.debug("FastLoad ModIdMapping step {} took {} ms", name, nanosToMillis(elapsed));
        }
        return System.nanoTime() - start;
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private interface Step {
        void run();
    }
}
