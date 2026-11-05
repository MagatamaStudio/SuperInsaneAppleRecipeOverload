package com.siao.appleoverload.upgrade;

/** 机器属性。升级分「直接生效」和「抬上限」两类，都作用在这四个量上。 */
public enum MachineStat {
    SPEED(8),
    ENERGY(8),
    PARALLEL(4),
    ANCHOR(1);

    private final int baseCap;

    MachineStat(int baseCap) {
        this.baseCap = baseCap;
    }

    /** 基础层能堆到的上限（不含进阶层加成）。 */
    public int baseCap() {
        return baseCap;
    }
}
