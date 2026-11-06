package com.siao.appleoverload.recipe;

import com.siao.appleoverload.SiaoMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 配方序列化器注册。 */
public final class SiaoRecipes {

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, SiaoMod.MOD_ID);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<SiaoCompressionRecipe>> COMPRESSION =
            SERIALIZERS.register("compression", SiaoCompressionRecipe.Serializer::new);

    /** 混沌苹果：产物带随机组件，走「特殊配方」，JSON 里没有可声明的 ingredient。 */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<ChaosAppleRecipe>> CHAOS_APPLE =
            SERIALIZERS.register("chaos_apple", ChaosAppleSerializer::new);

    private SiaoRecipes() {
    }
}
