package com.siao.appleoverload.recipe;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「输入物品 → 压缩配方」索引，压缩机用。
 *
 * <p>直接用 {@code RecipeManager#getRecipeFor} 每次要过 500 多条配方的
 * {@code matches()}。满配机器一 tick 跑十几次，再乘上几十台，服务端就废了。
 * 这里按 {@link RecipeManager} 身份缓存一张 {@code Item → 配方列表} 表，
 * 查表之后只用对少数几条跑 {@code matches()}。</p>
 *
 * <p>压缩 / 拆分 / 装块 / 拆块**四类都在同一张表里** —— 它们现在都是
 * {@code siao:compression} 类型，由压缩机统一处理。</p>
 */
public final class CompressionIndex {

    private static RecipeManager cachedManager;
    private static Map<Item, List<SiaoCompressionRecipe>> byItem = Map.of();

    private CompressionIndex() {
    }

    /** 能处理这个物品的候选配方（可能为空）。 */
    public static List<SiaoCompressionRecipe> candidates(RecipeManager manager, ItemStack stack) {
        if (stack.isEmpty()) {
            return List.of();
        }
        if (manager != cachedManager) {
            rebuild(manager);
        }
        return byItem.getOrDefault(stack.getItem(), List.of());
    }

    /** 供自检用：索引里有多少种可识别输入。 */
    public static int size(RecipeManager manager) {
        if (manager != cachedManager) {
            rebuild(manager);
        }
        return byItem.size();
    }

    private static void rebuild(RecipeManager manager) {
        Map<Item, List<SiaoCompressionRecipe>> index = new HashMap<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (!(holder.value() instanceof SiaoCompressionRecipe recipe)) {
                continue;
            }
            for (ItemStack candidate : recipe.ingredient().getItems()) {
                index.computeIfAbsent(candidate.getItem(), key -> new ArrayList<>()).add(recipe);
            }
        }
        byItem = index;
        cachedManager = manager;
    }
}
