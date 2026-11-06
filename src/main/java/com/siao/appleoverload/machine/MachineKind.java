package com.siao.appleoverload.machine;

import net.minecraft.util.StringRepresentable;

/** 四台机器的静态参数。GUI 布局四台通用，所以这里只管数值和槽位语义。 */
public enum MachineKind implements StringRepresentable {

    COMPRESSOR("compressor", 9, 3, 40, 200),
    ENCHANTER("enchanter", 9, 3, 60, 500),
    CENTRIFUGE("centrifuge", 9, 3, 80, 800),
    /** 苹果农场：吃树苗产苹果，是整个压缩链真正的瓶颈。 */
    ORCHARD("orchard", 9, 3, 60, 300),
    /** 苹果发电机：烧苹果换 FE。注意它是**产**电，单次耗能是 0。 */
    GENERATOR("generator", 9, 3, 40, 0);

    /** 基础层升级槽数量。 */
    public static final int UPGRADE_SLOTS = 8;
    /** 能量缓冲，三台一致。 */
    public static final int ENERGY_CAPACITY = 200_000;
    /** 每 tick 最多接收的能量。 */
    public static final int ENERGY_TRANSFER = 4_000;

    private final String id;
    private final int inputSlots;
    private final int outputSlots;
    private final int baseTicks;
    private final int energyPerOperation;

    MachineKind(String id, int inputSlots, int outputSlots, int baseTicks, int energyPerOperation) {
        this.id = id;
        this.inputSlots = inputSlots;
        this.outputSlots = outputSlots;
        this.baseTicks = baseTicks;
        this.energyPerOperation = energyPerOperation;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String id() {
        return id;
    }

    public String blockId() {
        return "apple_" + id;
    }

    public String langKey() {
        return "block.siao." + blockId();
    }

    public int inputSlots() {
        return inputSlots;
    }

    public int outputSlots() {
        return outputSlots;
    }

    public int inputStart() {
        return 0;
    }

    public int outputStart() {
        return inputSlots;
    }

    public int upgradeStart() {
        return inputSlots + outputSlots;
    }

    public int totalSlots() {
        return inputSlots + outputSlots + UPGRADE_SLOTS;
    }

    /** 该机器是否把这个输入槽当料用（压缩机用满 9 格，其余只用第 0 格）。 */
    /**
     * 输入槽是否可用。
     *
     * <p>五种机器的 9 个输入槽**全部可用** —— 单输入的机器会自己找第一个非空槽，
     * 玩家/Alchemist 往哪格里塞都行。之前只有第 0 格可用，界面上却有 9 个槽，
     * 看起来像坏了。</p>
     */
    public boolean usesInputSlot(int slot) {
        return slot >= inputStart() && slot < inputStart() + inputSlots();
    }

    public int baseTicks() {
        return baseTicks;
    }

    public int energyPerOperation() {
        return energyPerOperation;
    }
}
