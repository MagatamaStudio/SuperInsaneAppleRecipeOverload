package com.siao.appleoverload.content;

/**
 * 命名与 ID 规则集中地。**发布后不可更改**，改了就毁存档。
 *
 * <pre>
 * compressed_{tier}x_{material}               压缩单位
 * compressed_{tier}x_{material}_block         压缩块
 * compressed_{tier}x_raw_{material}           粗形态单位
 * compressed_{tier}x_raw_{material}_block     粗形态块
 * </pre>
 */
public final class SiaoNames {
    private static final String[] ZH_TIERS = {
            "", "一", "二", "三", "四", "五", "六", "七", "八", "九"
    };

    private SiaoNames() {
    }

    public static String zhTier(int tier) {
        if (tier < 1 || tier > Material.MAX_TIER) {
            throw new IllegalArgumentException("tier 越界: " + tier);
        }
        return ZH_TIERS[tier];
    }

    // ---- id -----------------------------------------------------------------

    public static String unitId(Material material, int tier) {
        return "compressed_" + tier + "x_" + material.id();
    }

    public static String unitBlockId(Material material, int tier) {
        return unitId(material, tier) + "_block";
    }

    public static String rawUnitId(Material material, int tier) {
        return "compressed_" + tier + "x_raw_" + material.id();
    }

    public static String rawBlockId(Material material, int tier) {
        return rawUnitId(material, tier) + "_block";
    }

    // ---- C 线：氧化变种 ------------------------------------------------------

    /** 氧化阶前缀：0 = 未氧化（无前缀）。 */
    public static String stagePrefix(int stage) {
        return switch (stage) {
            case 1 -> "exposed_";
            case 2 -> "weathered_";
            case 3 -> "oxidized_";
            default -> "";
        };
    }

    /**
     * 氧化变种 id：{@code [stage_]compressed_Nx_[raw_]mat_block[_waxed]}。
     *
     * <p>stage = 0 且不涂蜡时就是基础方块本身，调用方要跳过。</p>
     */
    public static String oxidizedBlockId(Material material, int tier, boolean raw, int stage, boolean waxed) {
        String base = raw ? rawBlockId(material, tier) : unitBlockId(material, tier);
        return stagePrefix(stage) + base + (waxed ? "_waxed" : "");
    }

    /** 中文名：斑驳/锈蚀/氧化 + 原名 + 涂蜡前缀。 */
    public static String zhOxidizedBlock(Material material, int tier, boolean raw, int stage, boolean waxed) {
        String stem = raw ? zhRawBlock(material, tier) : zhUnitBlock(material, tier);
        String staged = switch (stage) {
            case 1 -> "斑驳" + stem;
            case 2 -> "锈蚀" + stem;
            case 3 -> "氧化" + stem;
            default -> stem;
        };
        return waxed ? "涂蜡" + staged : staged;
    }

    public static String enOxidizedBlock(Material material, int tier, boolean raw, int stage, boolean waxed) {
        String stem = raw ? enRawBlock(material, tier) : enUnitBlock(material, tier);
        String staged = switch (stage) {
            case 1 -> "Exposed " + stem;
            case 2 -> "Weathered " + stem;
            case 3 -> "Oxidized " + stem;
            default -> stem;
        };
        return waxed ? "Waxed " + staged : staged;
    }
    // ---- 中文名 ---------------------------------------------------------------

    public static String zhUnit(Material material, int tier) {
        return zhTier(tier) + "重压缩" + material.zhUnit();
    }

    public static String zhUnitBlock(Material material, int tier) {
        return zhUnit(material, tier) + "块";
    }

    public static String zhRawUnit(Material material, int tier) {
        // 粗形态名现在由材料自己给（「粗铁」「粗苹果金」），不再靠「去锭加粗」猜
        return zhTier(tier) + "重压缩" + material.zhRaw();
    }

    public static String zhRawBlock(Material material, int tier) {
        return zhRawUnit(material, tier) + "块";
    }

    /** 第 0 重的粗形态名（没有「重压缩」前缀），如「粗苹果金」。 */
    public static String zhRawBase(Material material) {
        return material.zhRaw();
    }

    /** 第 0 重的粗形态英文名，如「Raw Apple Gold」。 */
    public static String enRawBase(Material material) {
        return material.enRaw();
    }

    // ---- 英文名 ---------------------------------------------------------------

    public static String enUnit(Material material, int tier) {
        return numPrefix(tier) + "Compressed " + material.enUnit();
    }

    public static String enUnitBlock(Material material, int tier) {
        return enUnit(material, tier) + " Block";
    }

    public static String enRawUnit(Material material, int tier) {
        return numPrefix(tier) + "Compressed " + material.enRaw();
    }

    public static String enRawBlock(Material material, int tier) {
        return enRawUnit(material, tier) + " Block";
    }

    private static String numPrefix(int tier) {
        return switch (tier) {
            case 1 -> "1x ";
            case 2 -> "2x ";
            case 3 -> "3x ";
            case 4 -> "4x ";
            case 5 -> "5x ";
            case 6 -> "6x ";
            case 7 -> "7x ";
            case 8 -> "8x ";
            default -> "9x ";
        };
    }

    /** 「铁锭」→「铁」，「下界合金锭」→「下界合金」；无「锭」后缀的原样返回。 */
    private static String stripSuffix(String zh) {
        return zh.endsWith("锭") ? zh.substring(0, zh.length() - 1) : zh;
    }
}
