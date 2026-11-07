package com.siao.appleoverload.compat;

import com.siao.appleoverload.machine.AppleMachineBlockEntity;
import net.neoforged.fml.ModList;

/**
 * AE2 可选接触点的**唯一静态入口**。
 *
 * <p>这个类不引用任何 AE2 类型，所以没装 AE2 时它能安全加载。
 * 真正的实现放在 {@link Ae2GridHost}，只有在装 AE2 时才会被解析到 ——
 * JVM 的类解析是惰性的，分支不进就永远不会加载那个类。</p>
 */
public final class Ae2Compat {

    public static final boolean PRESENT = ModList.get().isLoaded("ae2");

    private Ae2Compat() {
    }

    public static void tick(AppleMachineBlockEntity machine) {
        if (PRESENT) {
            Ae2GridHost.tick(machine);
        }
    }

    public static void removed(AppleMachineBlockEntity machine) {
        if (PRESENT) {
            Ae2GridHost.removed(machine);
        }
    }
}
