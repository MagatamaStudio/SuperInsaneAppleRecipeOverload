package com.siao.appleoverload.recipe;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 「输入物品 → 压缩配方」索引，压缩机用。
 *
 * <p>直接用 {@code RecipeManager#getRecipeFor} 每次要过 540 条配方的
 * {@code matches()}（≈4900 次物品比较）。满配机器一 tick 跑十几次，
 * 再乘上几十台，服务端就废了。这里按 {@link RecipeManager} 身份缓存一张
 * {@code Item → 配方} 表，查表 O(1)，再用单条 {@code matches()} 做最终校验
 * （附魔状态、数量这些仍然走配方自己的逻辑，不重复实现）。</p>
 */
public final class CompressionIndex {

    private static RecipeManager cachedManager;
    private static Map<Item, SiaoCompressionRecipe> byItem = Map.of();

    private CompressionIndex() {
    }

    /** 找出能把 {@code stack} 压上去的配方；没有则 null。 */
    public static SiaoCompressionRecipe find(RecipeManager manager, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (manager != cachedManager) {
            rebuild(manager);
        }
        return byItem.get(stack.getItem());
    }

    private static void rebuild(RecipeManager manager) {
        Map<Item, SiaoCompressionRecipe> index = new HashMap<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (holder.value() instanceof SiaoCompressionRecipe recipe
                    && recipe.count() == 9
                    && recipe.enchantMode() == SiaoCompressionRecipe.EnchantMode.PRESERVE
                    && recipe.result().getCount() == 1) {
                for (ItemStack candidate : recipe.ingredient().getItems()) {
                    index.putIfAbsent(candidate.getItem(), recipe);
                }
            }
        }
        byItem = index;
        cachedManager = manager;
    }

    /** 供自检用：索引里有多少条可压缩输入。 */
    public static int size(RecipeManager manager) {
        if (manager != cachedManager) {
            rebuild(manager);
        }
        return byItem.size();
    }
}
