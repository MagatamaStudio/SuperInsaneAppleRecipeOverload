package com.siao.appleoverload.content;

/**
 * 铜专线变种：只作用于**一重压缩铜锭块**这一阶，向上不扩散（控制 ID 爆炸）。
 *
 * <pre>
 * [stage_]compressed_1x_copper[_shape][_waxed]
 * 例：oxidized_compressed_1x_copper_cut_waxed
 * </pre>
 *
 * <p>氧化与涂蜡**不写一行交互代码**：全部交给 NeoForge 的
 * {@code neoforge:oxidizables} / {@code neoforge:waxables} 两张 data map，
 * 蜜脾涂蜡、斧头擦蜡自动生效。</p>
 */
public final class CopperVariant {

    /** 氧化阶。 */
    public enum Stage {
        UNAFFECTED("", "", ""),
        EXPOSED("exposed_", "斑驳", "Exposed"),
        WEATHERED("weathered_", "锈蚀", "Weathered"),
        OXIDIZED("oxidized_", "氧化", "Oxidized");

        private final String idPrefix;
        private final String zh;
        private final String en;

        Stage(String idPrefix, String zh, String en) {
            this.idPrefix = idPrefix;
            this.zh = zh;
            this.en = en;
        }

        public String idPrefix() {
            return idPrefix;
        }

        public String zh() {
            return zh;
        }

        public String en() {
            return en;
        }

        /** 下一氧化阶；已是最末阶返回 null。 */
        public Stage next() {
            return switch (this) {
                case UNAFFECTED -> EXPOSED;
                case EXPOSED -> WEATHERED;
                case WEATHERED -> OXIDIZED;
                case OXIDIZED -> null;
            };
        }
    }

    /** 形状。最后两个是楼梯/台阶，方块类不同。 */
    public enum Shape {
        BASE("", "", "", ""),
        CUT("cut", "切制", "Cut", "cut"),
        POLISHED("polished", "磨制", "Polished", "polished"),
        GRATE("grate", "格栅", "Grate", "grate"),
        CHISELED("chiseled", "雕纹", "Chiseled", "chiseled"),
        STAIRS("stairs", "楼梯", "Stairs", "stairs"),
        SLAB("slab", "台阶", "Slab", "slab");

        private final String idSuffix;
        private final String zh;
        private final String en;
        private final String textureSuffix;

        Shape(String idSuffix, String zh, String en, String textureSuffix) {
            this.idSuffix = idSuffix;
            this.zh = zh;
            this.en = en;
            this.textureSuffix = textureSuffix;
        }

        public String idSuffix() {
            return idSuffix;
        }

        public String zh() {
            return zh;
        }

        public String en() {
            return en;
        }

        public String textureSuffix() {
            return textureSuffix;
        }
    }

    private CopperVariant() {
    }

    /** {@code [stage_]compressed_1x_copper[_shape][_waxed]} */
    public static String id(Stage stage, Shape shape, boolean waxed) {
        StringBuilder builder = new StringBuilder(stage.idPrefix()).append("compressed_1x_copper");
        if (!shape.idSuffix().isEmpty()) {
            builder.append('_').append(shape.idSuffix());
        }
        if (waxed) {
            builder.append("_waxed");
        }
        return builder.toString();
    }

    public static String zhName(Stage stage, Shape shape, boolean waxed) {
        return (waxed ? "涂蜡" : "") + stage.zh() + shape.zh() + "一重压缩铜锭块";
    }

    public static String enName(Stage stage, Shape shape, boolean waxed) {
        StringBuilder builder = new StringBuilder();
        if (waxed) {
            builder.append("Waxed ");
        }
        if (!stage.en().isEmpty()) {
            builder.append(stage.en()).append(' ');
        }
        if (!shape.en().isEmpty()) {
            builder.append(shape.en()).append(' ');
        }
        builder.append("1x Compressed Copper Ingot Block");
        return builder.toString();
    }

    /**
     * 这个变种是否已经被原来的压缩方块占用了 ID。
     *
     * <p>{@code compressed_1x_copper_block} 在阶段 1 就存在了，
     * 它正好就是「未氧化 + 基础形状 + 未涂蜡」这一格，不能再注册一次。</p>
     */
    public static boolean isOccupiedByCompressionBlock(Stage stage, Shape shape, boolean waxed) {
        return stage == Stage.UNAFFECTED && shape == Shape.BASE && !waxed;
    }
}
