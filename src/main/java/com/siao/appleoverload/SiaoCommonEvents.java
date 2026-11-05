package com.siao.appleoverload;

import com.siao.appleoverload.machine.SiaoMachineRegistry;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** 通用（双端）注册：能力。由 {@code SiaoMod} 直接挂到 mod 事件总线上。 */
public final class SiaoCommonEvents {

    private SiaoCommonEvents() {
    }

    /**
     * 只挂标准 NeoForge 能力，不碰 AE2。
     * 这样漏斗 / 管道 / AE2 的普通「物品」推入路径全都能用，且不需要编译期依赖 AE2。
     */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, SiaoMachineRegistry.MACHINE.get(),
                (machine, side) -> machine.getItemHandlerFor(side));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, SiaoMachineRegistry.MACHINE.get(),
                (machine, side) -> machine.getEnergyStorage());
    }
}
