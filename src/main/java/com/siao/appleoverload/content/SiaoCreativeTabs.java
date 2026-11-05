package com.siao.appleoverload.content;

import com.siao.appleoverload.SiaoMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 创造模式物品栏。物品多，先按「单位 → 粗形态 → 方块」分组，后续再拆子页。 */
public final class SiaoCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SiaoMod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.siao.main"))
                    .icon(() -> new ItemStack(SiaoItems.unit(Material.APPLE, 9).get()))
                    .displayItems((parameters, output) -> {
                        SiaoItems.units().forEach(item -> output.accept(item.get()));
                        SiaoItems.rawUnits().forEach(item -> output.accept(item.get()));
                        SiaoItems.blockItems().forEach(item -> output.accept(item.get()));
                        SiaoBlocks.machineBlocks().forEach(block -> output.accept(block.get()));
                        SiaoItems.upgrades().forEach(item -> output.accept(item.get()));
                        output.accept(SiaoItems.singularityShard().get());
                        output.accept(SiaoItems.singularity().get());
                        output.accept(SiaoItems.chaosApple().get());
                    })
                    .build());

    private SiaoCreativeTabs() {
    }
}
