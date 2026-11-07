package com.siao.appleoverload.content;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * 给所有压缩物品挂上「等价多少个基础材料」的提示。
 *
 * <p>走 {@link ItemTooltipEvent} 而不是给每个物品写子类：
 * 一次覆盖 270 个物品 + 方块，后续新增形态自动生效。</p>
 */
public final class CompressionTooltips {

    private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.ROOT);

    private CompressionTooltips() {
    }

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(CompressionTooltips.class);
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        CompressionInfo.parse(BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem()))
                .ifPresent(info -> {
                    long amount = info.equivalents();
                    event.getToolTip().add(Component.translatable(
                                    "tooltip.siao.equivalent",
                                    NUMBERS.format(amount),
                                    Component.translatable(info.materialKey()))
                            .withStyle(ChatFormatting.GRAY));
                    // 装块路线在 EMI 里和升重配方被合并，看不出来 ——
                    // 至少让玩家知道「9 个能装成块」这件事存在
                    if (info.kind() == CompressionInfo.Kind.UNIT
                            || info.kind() == CompressionInfo.Kind.RAW_UNIT) {
                        event.getToolTip().add(Component.translatable("tooltip.siao.packing")
                                .withStyle(ChatFormatting.DARK_GRAY));
                    }
                });
    }
}
