package com.siao.appleoverload.recipe;

import com.siao.appleoverload.SiaoMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 自定义配方类型。
 *
 * <p>关键点：压缩配方**不是** {@link RecipeType#CRAFTING}，所以工作台根本不会去看它。
 * 这是原需求「这些物品不能手搓，必须用特定机器做出来」的落地方式 ——
 * 不是把配方删掉，而是把它们从合成台的配方类型里摘出去。</p>
 */
public final class SiaoRecipeTypes {

    public static final DeferredRegister<RecipeType<?>> TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, SiaoMod.MOD_ID);

    public static final DeferredHolder<RecipeType<?>, RecipeType<SiaoCompressionRecipe>> COMPRESSION =
            TYPES.register("compression", () -> RecipeType.simple(
                    ResourceLocation.fromNamespaceAndPath(SiaoMod.MOD_ID, "compression")));

    private SiaoRecipeTypes() {
    }
}
