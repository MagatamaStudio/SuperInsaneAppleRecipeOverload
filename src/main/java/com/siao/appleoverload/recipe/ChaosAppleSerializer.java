package com.siao.appleoverload.recipe;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * 混沌苹果配方的序列化器。
 *
 * <p>JSON 里只有一个 {@code "type"}，没有任何字段 —— 匹配逻辑全在
 * {@link ChaosAppleRecipe#matches} 里，产物带随机组件。</p>
 */
public class ChaosAppleSerializer implements RecipeSerializer<ChaosAppleRecipe> {

    private static final MapCodec<ChaosAppleRecipe> CODEC = MapCodec.unit(ChaosAppleRecipe::new);
    private static final StreamCodec<RegistryFriendlyByteBuf, ChaosAppleRecipe> STREAM_CODEC =
            StreamCodec.unit(new ChaosAppleRecipe());

    @Override
    public MapCodec<ChaosAppleRecipe> codec() {
        return CODEC;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, ChaosAppleRecipe> streamCodec() {
        return STREAM_CODEC;
    }
}
