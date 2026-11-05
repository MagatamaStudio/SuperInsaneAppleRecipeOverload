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

    private SiaoRecipes() {
    }
}
