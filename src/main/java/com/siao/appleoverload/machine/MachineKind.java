package com.siao.appleoverload.machine;

import net.minecraft.util.StringRepresentable;

/** 三台机器的静态参数。GUI 布局三台通用，所以这里只管数值和槽位语义。 */
public enum MachineKind implements StringRepresentable {

    COMPRESSOR("compressor", 9, 3, 40, 200),
    ENCHANTER("enchanter", 9, 3, 60, 500),
    CENTRIFUGE("centrifuge", 9, 3, 80, 800);

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

    /** 该机器是否把这个输入槽当料用（压缩机用满 9 格，另外两台只用第 0 格）。 */
    public boolean usesInputSlot(int slot) {
        return switch (this) {
            case COMPRESSOR -> true;
            case ENCHANTER, CENTRIFUGE -> slot == 0;
        };
    }

    public int baseTicks() {
        return baseTicks;
    }

    public int energyPerOperation() {
        return energyPerOperation;
    }
}
