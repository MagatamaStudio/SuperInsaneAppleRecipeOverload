package com.siao.appleoverload.client;

import com.siao.appleoverload.machine.AppleMachineScreen;
import com.siao.appleoverload.machine.MachineKind;
import com.siao.appleoverload.machine.SiaoMachineRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 客户端专用注册。
 *
 * <p>**只在 {@code FMLEnvironment.dist == CLIENT} 时才会被加载**（见 {@code SiaoMod}），
 * 所以这个类里可以直接引用客户端专属类型，专用服务器上不会 NoClassDefFoundError。</p>
 */
public final class SiaoClientEvents {

    private SiaoClientEvents() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(SiaoClientEvents::onRegisterMenuScreens);
    }

    private static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        for (MachineKind kind : MachineKind.values()) {
            event.register(SiaoMachineRegistry.menuFor(kind), AppleMachineScreen::new);
        }
    }
}
