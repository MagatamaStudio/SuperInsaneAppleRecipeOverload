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

    /**
     * ⚠️ 不能用 {@code StreamCodec.unit(new ChaosAppleRecipe())}。
     *
     * <p>{@code StreamCodec.unit} 在 encode 时会校验「传进来的就是那个单例」，
     * 而 JSON 解析每次都会 new 一个新实例，于是同步配方时抛
     * {@code IllegalStateException: Can't encode ... expected ...}，
     * 整个 {@code clientbound/minecraft:update_recipes} 包发不出去 ——
     * 表现就是**单人和多人都进不去世界**。</p>
     *
     * <p>这里手写一个「不写任何字节、读的时候 new 一个」的 codec。</p>
     */
    private static final StreamCodec<RegistryFriendlyByteBuf, ChaosAppleRecipe> STREAM_CODEC =
            StreamCodec.of((buffer, value) -> {
            }, buffer -> new ChaosAppleRecipe());

    @Override
    public MapCodec<ChaosAppleRecipe> codec() {
        return CODEC;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, ChaosAppleRecipe> streamCodec() {
        return STREAM_CODEC;
    }
}
