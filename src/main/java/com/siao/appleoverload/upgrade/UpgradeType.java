package com.siao.appleoverload.upgrade;

/**
 * 升级类型。自己的 ID 由注册表给出，这里只描述行为。
 *
 * @param stat      作用在哪个机器属性上
 * @param maxLevel  该类型自身的最大等级
 * @param boostsCap true = 进阶层，不直接生效，只把对应属性的上限抬 {@code maxLevel}
 */
public record UpgradeType(MachineStat stat, int maxLevel, boolean boostsCap) {
}
