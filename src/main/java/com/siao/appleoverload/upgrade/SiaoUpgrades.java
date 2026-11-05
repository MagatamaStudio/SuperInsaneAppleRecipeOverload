package com.siao.appleoverload.upgrade;

import com.siao.appleoverload.SiaoMod;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

/**
 * 升级类型注册表 —— 对第三方模组**开放**。
 *
 * <p>物品 / 存档里认的是注册名（{@code siao:speed} 之类），不是序数，
 * 所以别的模组注册自己的升级类型时，不需要动我们的代码，也不会破坏存档。</p>
 *
 * <p>注册表本身要用 {@link NewRegistryEvent} 建出来，见 {@link #onNewRegistry}。</p>
 */
public final class SiaoUpgrades {

    /** 自定义注册表：{@code siao:upgrade_type}。 */
    public static final ResourceKey<Registry<UpgradeType>> KEY = ResourceKey.createRegistryKey(
            ResourceLocation.fromNamespaceAndPath(SiaoMod.MOD_ID, "upgrade_type"));

    public static final DeferredRegister<UpgradeType> TYPES =
            DeferredRegister.create(KEY, SiaoMod.MOD_ID);

    // ---- 基础层：直接生效 ---------------------------------------------------
    public static final DeferredHolder<UpgradeType, UpgradeType> SPEED =
            TYPES.register("speed", () -> new UpgradeType(MachineStat.SPEED, 8, false));
    public static final DeferredHolder<UpgradeType, UpgradeType> ENERGY =
            TYPES.register("energy", () -> new UpgradeType(MachineStat.ENERGY, 8, false));
    public static final DeferredHolder<UpgradeType, UpgradeType> PARALLEL =
            TYPES.register("parallel", () -> new UpgradeType(MachineStat.PARALLEL, 4, false));
    public static final DeferredHolder<UpgradeType, UpgradeType> ANCHOR =
            TYPES.register("anchor", () -> new UpgradeType(MachineStat.ANCHOR, 1, false));

    // ---- 进阶层：只抬上限 ---------------------------------------------------
    public static final DeferredHolder<UpgradeType, UpgradeType> OVERCLOCK =
            TYPES.register("overclock", () -> new UpgradeType(MachineStat.SPEED, 4, true));
    public static final DeferredHolder<UpgradeType, UpgradeType> QUANTUM =
            TYPES.register("quantum", () -> new UpgradeType(MachineStat.ENERGY, 4, true));
    public static final DeferredHolder<UpgradeType, UpgradeType> FISSION =
            TYPES.register("fission", () -> new UpgradeType(MachineStat.PARALLEL, 4, true));

    private SiaoUpgrades() {
    }

    /** 由 {@code SiaoMod} 挂到 mod 事件总线。必须在 {@code RegisterEvent} 之前跑。 */
    public static void onNewRegistry(NewRegistryEvent event) {
        event.create(new RegistryBuilder<>(KEY));
    }

    /** 进阶层给某个属性额外抬的上限总和。 */
    public static int bonusCap(MachineStat stat) {
        int bonus = 0;
        for (DeferredHolder<UpgradeType, ? extends UpgradeType> holder : TYPES.getEntries()) {
            UpgradeType type = holder.get();
            if (type.boostsCap() && type.stat() == stat) {
                bonus += type.maxLevel();
            }
        }
        return bonus;
    }

    /** 某属性的实际上限 = 基础上限 + 进阶层加成。 */
    public static int effectiveCap(MachineStat stat) {
        return stat.baseCap() + bonusCap(stat);
    }
}
