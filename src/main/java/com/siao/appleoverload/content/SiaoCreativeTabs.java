package com.siao.appleoverload.content;

import com.siao.appleoverload.SiaoMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式物品栏。
 *
 * <p>一万多个方块全塞一个标签页里没法用，按「物品 / 方块 / 整块形状 / 楼梯台阶」
 * 拆成四页。分类规则写在 {@link #tabOf(String)} 里，靠 id 后缀判断 ——
 * 不额外维护一张分类表，新增材料或形状会自动落到正确的页。</p>
 */
public final class SiaoCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SiaoMod.MOD_ID);

    /** 物品与机器。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.siao.main"))
                    .icon(() -> new ItemStack(SiaoItems.unit(Material.APPLE, 9).get()))
                    .displayItems((parameters, output) -> {
                        SiaoItems.units().forEach(item -> output.accept(item.get()));
                        SiaoItems.rawUnits().forEach(item -> output.accept(item.get()));
                        SiaoItems.ALLOYS.values().forEach(item -> output.accept(item.get()));
                        output.accept(SiaoItems.rawAppleGold().get());
                        SiaoItems.upgrades().forEach(item -> output.accept(item.get()));
                        output.accept(SiaoItems.configurator().get());
                        output.accept(SiaoItems.singularityShard().get());
                        output.accept(SiaoItems.singularity().get());
                        output.accept(SiaoItems.chaosApple().get());
                        SiaoBlocks.machineBlocks().forEach(block -> output.accept(block.get()));
                    })
                    .build());

    /** 压缩方块：基础块 + 氧化/涂蜡。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BLOCKS = TABS.register("blocks",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.siao.blocks"))
                    .icon(() -> new ItemStack(SiaoBlocks.unitBlock(Material.IRON, 1).get()))
                    .displayItems((parameters, output) -> addBlocks(output, 1))
                    .build());

    /** 整块形状：切制 / 磨制 / 格栅 / 雕纹。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SHAPES = TABS.register("shapes",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.siao.shapes"))
                    .icon(() -> new ItemStack(itemOf("compressed_1x_iron_cut")))
                    .displayItems((parameters, output) -> addBlocks(output, 2))
                    .build());

    /** 楼梯与台阶。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> STAIRS = TABS.register("stairs",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.siao.stairs"))
                    .icon(() -> new ItemStack(itemOf("compressed_1x_iron_stairs")))
                    .displayItems((parameters, output) -> addBlocks(output, 3))
                    .build());

    private SiaoCreativeTabs() {
    }

    private static void addBlocks(CreativeModeTab.Output output, int wantedTab) {
        SiaoItems.blockItems().forEach(item -> {
            String path = BuiltInRegistries.ITEM.getKey(item.get()).getPath();
            if (tabOf(path) == wantedTab) {
                output.accept(item.get());
            }
        });
    }

    private static Item itemOf(String path) {
        return BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath(SiaoMod.MOD_ID, path));
    }

    /**
     * 按 id 后缀分类：1 = 方块/氧化，2 = 整块形状，3 = 楼梯台阶。
     *
     * <p>机器的 BlockItem 在主栏里单独发，不会走到这里。</p>
     */
    static int tabOf(String path) {
        String stem = path.endsWith("_waxed") ? path.substring(0, path.length() - "_waxed".length()) : path;
        if (stem.endsWith("_stairs") || stem.endsWith("_slab")) {
            return 3;
        }
        for (String shape : SiaoNames.CUBE_SHAPES) {
            if (stem.endsWith("_" + shape)) {
                return 2;
            }
        }
        return 1;
    }
}
