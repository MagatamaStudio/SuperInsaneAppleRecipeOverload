package com.siao.appleoverload.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 公共配置。
 *
 * <p><b>只放真正有运行时效果的项。</b>设计文档里还有 `applePerTier`，
 * 但那个会改变「一重 = 9 个」这个基础等式，牵动物品注册、配方 JSON、lang、
 * 等价数换算 —— 改它必须重新生成资源并重新构建，属于**构建期参数**
 * 而不是运行期配置。做成游戏内配置只会变成「改了没用」的假开关。
 * 详见 {@code docs/自决清单.md}。</p>
 */
public final class SiaoConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 压缩链允许的最大重数。默认 9，降下来可以让服务器跳过九重这个图腾级目标。 */
    public static final ModConfigSpec.IntValue MAX_TIER = BUILDER
            .comment("压缩链允许的最大重数（1-9）。低于 9 时，超过这个重数的压缩配方将不再匹配。")
            .defineInRange("maxTier", 9, 1, 9);

    /** 机器单次加工耗能的倍率。 */
    public static final ModConfigSpec.DoubleValue MACHINE_ENERGY_SCALE = BUILDER
            .comment("机器每次加工耗能的倍率。1.0 = 原始数值，0.5 = 省一半，2.0 = 翻倍。")
            .defineInRange("machineEnergyScale", 1.0D, 0.0D, 100.0D);

    /**
     * 奇点获取路线。
     *
     * <p>{@code direct}：1 个九重压缩苹果锭（387,420,489 个苹果）直接合奇点。</p>
     * <p>{@code shard}：离心机把**附魔六重及以上**的压缩单位拆成奇点碎片，
     * 9 个碎片合 1 个奇点。代价约 9 × 9^6 = 4,782,969 个苹果，
     * 比 direct（9^9 = 387,420,489）便宜约 81 倍 —— 这是我自己定的数值，见自决清单 §13.8。</p>
     */
    public static final ModConfigSpec.EnumValue<SingularityMode> SINGULARITY_MODE = BUILDER
            .comment("奇点获取路线：direct = 九重压缩苹果锭直接合；shard = 离心机拆碎片，30 碎片合一个。")
            .defineEnum("singularityMode", SingularityMode.DIRECT);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private static final int DEFAULT_MAX_TIER = 9;
    private static final double DEFAULT_ENERGY_SCALE = 1.0D;

    private SiaoConfig() {
    }

    /** 配置尚未加载时（例如数据生成、注册阶段）返回默认值，避免抛 IllegalStateException。 */
    public static int maxTier() {
        try {
            return MAX_TIER.get();
        } catch (IllegalStateException notLoadedYet) {
            return DEFAULT_MAX_TIER;
        }
    }

    public static SingularityMode singularityMode() {
        try {
            return SINGULARITY_MODE.get();
        } catch (IllegalStateException notLoadedYet) {
            return SingularityMode.DIRECT;
        }
    }

    /** 奇点路线。 */
    public enum SingularityMode {
        DIRECT, SHARD
    }

    /** 合 1 个奇点需要多少碎片（shard 路线）。受合成格 9 格限制，不能超过 9。 */
    public static final int SINGULARITY_SHARDS = 9;

    /** 离心机能拆碎片的最低重数。 */
    public static final int SHARD_MIN_TIER = 6;

    public static double machineEnergyScale() {
        try {
            return MACHINE_ENERGY_SCALE.get();
        } catch (IllegalStateException notLoadedYet) {
            return DEFAULT_ENERGY_SCALE;
        }
    }
}
