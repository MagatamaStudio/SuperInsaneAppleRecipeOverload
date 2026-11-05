package com.siao.appleoverload.content;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 压缩单位的物品实现：附魔时改名字 + 出光效。
 *
 * <p>两个都走覆写而不是往堆叠里塞 {@code custom_name} /
 * {@code enchantment_glint_override} 组件：</p>
 * <ul>
 *   <li>{@link #getName} —— JEI/EMI/铁砧/物品栏全部自动跟着变，且名字仍是可翻译键</li>
 *   <li>{@link #isFoil} —— {@code ItemStack#hasFoil()} 在没有 glint 覆写组件时会回落到这里</li>
 * </ul>
 */
public class SiaoCompressedItem extends Item {

    public SiaoCompressedItem(Properties properties) {
        super(properties);
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
