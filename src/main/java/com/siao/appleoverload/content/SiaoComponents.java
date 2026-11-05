package com.siao.appleoverload.content;

import com.mojang.serialization.Codec;
import com.siao.appleoverload.SiaoMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 自定义数据组件。
 *
 * <p>附魔是**组件**而不是独立物品：337 个 ID 里一个都不占，
 * 靠 {@code siao:enchanted} 打标记，靠 {@link SiaoCompressedItem} 改名字和光效。</p>
 *
 * <pre>
 * siao:enchanted      boolean  是否附魔
 * siao:enchant_tier   int      附魔强度 1..9（压缩时原样传递，供离心机换算碎片用）
 * </pre>
 */
public final class SiaoComponents {

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, SiaoMod.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> ENCHANTED =
            DATA_COMPONENTS.registerComponentType("enchanted",
                    builder -> builder.persistent(Codec.BOOL).cacheEncoding());

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ENCHANT_TIER =
            DATA_COMPONENTS.registerComponentType("enchant_tier",
                    builder -> builder.persistent(Codec.intRange(0, Material.MAX_TIER)));

    /** 升级物品的等级，1..16。这样「速度升级 III」不需要额外 ID。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> UPGRADE_LEVEL =
            DATA_COMPONENTS.registerComponentType("upgrade_level",
                    builder -> builder.persistent(Codec.intRange(1, 16)));

    private static final int DEFAULT_ENCHANT_TIER = 1;

    private SiaoComponents() {
    }

    /** 该堆叠是否带附魔组件。空堆叠一律 false。 */
    public static boolean isEnchanted(ItemStack stack) {
        return !stack.isEmpty() && Boolean.TRUE.equals(stack.get(ENCHANTED.get()));
    }

    /** 附魔强度；没附魔返回 0。 */
    public static int enchantTier(ItemStack stack) {
        if (!isEnchanted(stack)) {
            return 0;
        }
        return Mth.clamp(stack.getOrDefault(ENCHANT_TIER.get(), DEFAULT_ENCHANT_TIER),
                DEFAULT_ENCHANT_TIER, Material.MAX_TIER);
    }

    /** 给堆叠挂上附魔。{@code tier} 会被夹到 1..9。 */
    public static void makeEnchanted(ItemStack stack, int tier) {
        stack.set(ENCHANTED.get(), Boolean.TRUE);
        stack.set(ENCHANT_TIER.get(), Mth.clamp(tier, DEFAULT_ENCHANT_TIER, Material.MAX_TIER));
    }

    /** 把 {@code from} 的附魔状态原样搬到 {@code to}（没附魔则什么都不做）。 */
    public static void copyEnchantment(ItemStack from, ItemStack to) {
        if (isEnchanted(from)) {
            makeEnchanted(to, enchantTier(from));
        }
    }

    /** 剥掉附魔组件。 */
    public static void clearEnchantment(ItemStack stack) {
        stack.remove(ENCHANTED.get());
        stack.remove(ENCHANT_TIER.get());
    }
}
