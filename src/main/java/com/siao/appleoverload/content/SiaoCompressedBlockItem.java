package com.siao.appleoverload.content;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 压缩方块（锭块 / 粗形态块）的 BlockItem。
 *
 * <p>原需求 L2 明确要「附魔一重压缩苹果锭块」，而附魔状态挂在数据组件上，
 * 方块本身没法显示。所以方块物品要和压缩单位走同一套：附魔时改名字 + 出光效。</p>
 */
public class SiaoCompressedBlockItem extends BlockItem {

    public SiaoCompressedBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        Component base = super.getName(stack);
        return SiaoComponents.isEnchanted(stack)
                ? Component.translatable("prefix.siao.enchanted").append(base)
                : base;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return SiaoComponents.isEnchanted(stack) || super.isFoil(stack);
    }
}
