package com.siao.appleoverload.recipe;

import com.siao.appleoverload.content.Material;
import com.siao.appleoverload.content.SiaoComponents;
import com.siao.appleoverload.content.SiaoItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * 混沌苹果 —— 原构想 L11：
 * <b>附魔一重压缩粗苹果金 + 附魔一重压缩苹果锭 = 带随机 NBT 的混沌苹果</b>。
 *
 * <p>为什么是自定义配方类而不是 shaped/shapeless：产物不是固定物品，
 * 每次合成都带一组随机组件。普通配方的结果是静态 ItemStack，表达不了随机。</p>
 *
 * <p>随机内容（我自己定的，记在自决清单里）：</p>
 * <ul>
 *   <li>{@code siao:enchant_tier} 随机 1–9 —— 混沌苹果的"强度"</li>
 *   <li>{@code siao:enchanted} 恒为 true —— 让它发光，一眼看出不是普通苹果</li>
 * </ul>
 *
 * <p>它仍然属于 {@link RecipeType#CRAFTING}，可以在工作台手搓 ——
 * 不违反 L12：「不能手搓」约束的是<b>压缩链</b>，混沌苹果本来就是把两个附魔件
 * 合到一起，属于合成行为。</p>
 */
public class ChaosAppleRecipe implements Recipe<CraftingInput> {

    public ChaosAppleRecipe() {
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        boolean sawRaw = false;
        boolean sawApple = false;
        int used = 0;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            used++;
            if (!SiaoComponents.isEnchanted(stack)) {
                return false;
            }
            if (stack.is(SiaoItems.rawUnit(Material.APPLE, 1).get())) {
                if (sawRaw) {
                    return false;
                }
                sawRaw = true;
            } else if (stack.is(SiaoItems.unit(Material.APPLE, 1).get())) {
                if (sawApple) {
                    return false;
                }
                sawApple = true;
            } else {
                return false;
            }
        }
        return used == 2 && sawRaw && sawApple;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        RandomSource random = RandomSource.create();
        ItemStack chaos = new ItemStack(SiaoItems.chaosApple().get());
        SiaoComponents.makeEnchanted(chaos, 1 + random.nextInt(9));
        return chaos;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SiaoRecipes.CHAOS_APPLE.get();
    }

    /** 仍然是合成配方类型，所以工作台能识别它。 */
    @Override
    public RecipeType<?> getType() {
        return RecipeType.CRAFTING;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return new ItemStack(SiaoItems.chaosApple().get());
    }

    /** 特殊配方：不参与配方书自动摆放，也不给 JEI 提供固定 ingredient。 */
    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        return NonNullList.withSize(2, Ingredient.EMPTY);
    }
}
