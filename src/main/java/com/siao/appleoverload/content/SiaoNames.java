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

    // ---- 中文名 ---------------------------------------------------------------

    public static String zhUnit(Material material, int tier) {
        return zhTier(tier) + "重压缩" + material.zhUnit();
    }

    public static String zhUnitBlock(Material material, int tier) {
        return zhUnit(material, tier) + "块";
    }

    public static String zhRawUnit(Material material, int tier) {
        return zhTier(tier) + "重压缩粗" + stripSuffix(material.zhUnit());
    }

    public static String zhRawBlock(Material material, int tier) {
        return zhRawUnit(material, tier) + "块";
    }

    // ---- 英文名 ---------------------------------------------------------------

    public static String enUnit(Material material, int tier) {
        return numPrefix(tier) + "Compressed " + material.enUnit();
    }

    public static String enUnitBlock(Material material, int tier) {
        return enUnit(material, tier) + " Block";
    }

    public static String enRawUnit(Material material, int tier) {
        return numPrefix(tier) + "Compressed Raw " + stripSuffix(material.enUnit());
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
