package com.siao.appleoverload.upgrade;

/**
 * 升级类型。等级累加值决定效果强度。
 *
 * <p><b>为什么是 enum 而不是真注册表</b>：物品 / NBT / 数据组件里存的是
 * {@link #id()} 字符串，不是序数，所以将来换成一个
 * {@code Registry<UpgradeType>} 不会破坏存档格式。
 * 先把三台机器跑通，开放注册留到需要第三方接入时再换。</p>
 */
public enum UpgradeKind {
    /** 基础层：每级处理速度 ×2。 */
    SPEED("speed", 8, "速度升级", "Speed Upgrade"),
    /** 基础层：每级能耗 ×0.8。 */
    ENERGY("energy", 8, "能量升级", "Energy Upgrade"),
    /** 基础层：每级每 tick 多处理 1 次。 */
    PARALLEL("parallel", 4, "并行升级", "Parallel Upgrade"),
    /** 基础层：区块卸载后继续运行。 */
    ANCHOR("anchor", 1, "锚定升级", "Anchor Upgrade"),
    /** 进阶层：速度上限 +4。 */
    OVERCLOCK("overclock", 4, "超频核心", "Overclock Core"),
    /** 进阶层：能耗上限 +4。 */
    QUANTUM("quantum", 4, "量子核心", "Quantum Core"),
    /** 进阶层：并行上限 +4。 */
    FISSION("fission", 4, "裂变核心", "Fission Core");

    private final String id;
    private final int maxLevel;
    private final String zhName;
    private final String enName;

    UpgradeKind(String id, int maxLevel, String zhName, String enName) {
        this.id = id;
        this.maxLevel = maxLevel;
        this.zhName = zhName;
        this.enName = enName;
    }

    public String id() {
        return id;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public String zhName() {
        return zhName;
    }

    public String enName() {
        return enName;
    }

    public String langKey() {
        return "item.siao." + id + "_upgrade";
    }

    public boolean isAdvanced() {
        return this == OVERCLOCK || this == QUANTUM || this == FISSION;
    }

    /** 该类型对应的有效等级上限（基础层上限 + 进阶层带来的额外上限）。 */
    public int effectiveMaxLevel() {
        return switch (this) {
            case SPEED -> SPEED.maxLevel + OVERCLOCK.maxLevel;
            case ENERGY -> ENERGY.maxLevel + QUANTUM.maxLevel;
            case PARALLEL -> PARALLEL.maxLevel + FISSION.maxLevel;
            default -> maxLevel;
        };
    }
}
