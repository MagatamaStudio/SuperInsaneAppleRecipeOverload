package com.siao.appleoverload.machine;

import net.minecraft.util.StringRepresentable;

/**
 * 单个面的物品通行规则。
 *
 * <p>默认 {@link #BOTH}，也就是机器刚放下来时六面都能进出。
 * 想搭流水线就调成单向，避免管道把产物又灌回输入槽。</p>
 */
public enum SideMode implements StringRepresentable {
    BOTH("both", "双向"),
    INPUT("input", "仅输入"),
    OUTPUT("output", "仅输出"),
    NONE("none", "封闭");

    private final String id;
    private final String zh;

    SideMode(String id, String zh) {
        this.id = id;
        this.zh = zh;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String zh() {
        return zh;
    }

    public String langKey() {
        return "side.siao." + id;
    }

    /** 点击按钮时循环：双向 → 仅输入 → 仅输出 → 封闭 → 双向。 */
    public SideMode next() {
        return switch (this) {
            case BOTH -> INPUT;
            case INPUT -> OUTPUT;
            case OUTPUT -> NONE;
            case NONE -> BOTH;
        };
    }

    /** 反着循环：封闭 → 仅输出 → 仅输入 → 双向 → 封闭（配置器潜行右键用）。 */
    public SideMode previous() {
        return switch (this) {
            case BOTH -> NONE;
            case INPUT -> BOTH;
            case OUTPUT -> INPUT;
            case NONE -> OUTPUT;
        };
    }

    public boolean allowsInsert() {
        return this == BOTH || this == INPUT;
    }

    public boolean allowsExtract() {
        return this == BOTH || this == OUTPUT;
    }

    public static SideMode byOrdinal(int ordinal) {
        SideMode[] values = values();
        return values[Math.floorMod(ordinal, values.length)];
    }
}
