package com.siao.appleoverload.upgrade;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 升级物品。等级存在数据组件 {@code siao:upgrade_level} 里，
 * 所以「速度升级 III」和「速度升级 I」是同一个 ID，不额外占物品栏位。
 */
public class SiaoUpgradeItem extends Item {

    private final UpgradeKind kind;

    public SiaoUpgradeItem(UpgradeKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public UpgradeKind kind() {
        return kind;
    }

    /** 单个物品的等级（默认 1）。 */
    public static int levelOf(ItemStack stack) {
        return Math.max(1, stack.getOrDefault(
                com.siao.appleoverload.content.SiaoComponents.UPGRADE_LEVEL.get(), 1));
    }

    /** 这堆叠贡献的等级 = 数量 × 单件等级。 */
    public int contributedLevel(ItemStack stack) {
        return stack.getCount() * levelOf(stack);
    }
}
