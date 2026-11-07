package com.siao.appleoverload.content;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从注册名反解出「哪种材料、第几重、什么形态」。
 *
 * <p>不额外维护映射表，直接解析 ID —— ID 一旦发布就冻结，
 * 所以解析是稳定的，还能自动覆盖后续新增的形态。</p>
 */
public record CompressionInfo(Material material, int tier, Kind kind) {

    public enum Kind {
        /** 九重压缩的「单位」，9^tier 个基础材料。 */
        UNIT,
        /** 单位块，等价 9 倍单位。 */
        BLOCK,
        /** 粗形态单位。 */
        RAW_UNIT,
        /** 粗形态块。 */
        RAW_BLOCK
    }

    private static final Pattern RAW = Pattern.compile("compressed_(\\d)x_raw_([a-z]+)(_block)?");
    private static final Pattern PLAIN = Pattern.compile("compressed_(\\d)x_([a-z]+)(_block)?");
    /**
     * 铜专线变种：{@code [stage_]compressed_1x_copper[_shape][_waxed]}。
     *
     * <p>这些是**方块变种**而不是压缩单位，等价数按一重块算。之前正则匹配不到它们，
     * 结果 56 个铜方块全都没有等价提示 —— 压缩块有 tooltip、铜变种没有，看着就像坏了。</p>
     */
    private static final Pattern COPPER_VARIANT = Pattern.compile(
            "(?:(?:exposed|weathered|oxidized)_)?compressed_(\\d)x_copper"
                    + "(?:_(?:cut|polished|grate|chiseled|stairs|slab))?(?:_waxed)?");

    /**
     * C/D 线的变种：{@code [stage_]compressed_Nx_[raw_]material[_shape][_waxed]}。
     *
     * <p>material 里可能带下划线（{@code apple_netherite}），所以不能用一条大正则，
     * 改成「按已知后缀逐层剥」再解析。</p>
     */
    private static final Pattern VARIANT = Pattern.compile(
            "(?:(?:exposed|weathered|oxidized)_)?compressed_(\\d)x_(raw_)?(.+)");
    private static final String[] SHAPES = {"cut", "polished", "grate", "chiseled", "stairs", "slab"};

    public static Optional<CompressionInfo> parse(ResourceLocation id) {
        if (!"siao".equals(id.getNamespace())) {
            return Optional.empty();
        }
        String path = id.getPath();

        Matcher raw = RAW.matcher(path);
        if (raw.matches()) {
            return build(raw, true);
        }
        Matcher plain = PLAIN.matcher(path);
        if (plain.matches()) {
            return build(plain, false);
        }
        // 铜专线兜底（保留原行为）
        Matcher variant = COPPER_VARIANT.matcher(path);
        if (variant.matches() && !path.equals("compressed_1x_copper")) {
            int tier = Integer.parseInt(variant.group(1));
            return Optional.of(new CompressionInfo(Material.COPPER, Math.max(1, tier), Kind.BLOCK));
        }
        return parseVariant(path);
    }

    /**
     * C/D 线变种的解析：剥掉 {@code _waxed} 和形状后缀，剩下的当材料 id 查表。
     *
     * <p>这样 10,548 个方块全都有「相当于 N 个单位」的提示，不再只有铜专线有。</p>
     */
    private static Optional<CompressionInfo> parseVariant(String path) {
        Matcher matcher = VARIANT.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int tier;
        try {
            tier = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
        if (tier < 1 || tier > Material.MAX_TIER) {
            return Optional.empty();
        }
        boolean rawForm = matcher.group(2) != null;
        String rest = matcher.group(3);

        boolean block = false;
        if (rest.endsWith("_waxed")) {
            rest = rest.substring(0, rest.length() - "_waxed".length());
        }
        rest = rest.endsWith("_block") ? rest.substring(0, rest.length() - "_block".length()) : rest;
        for (String shape : SHAPES) {
            if (rest.endsWith("_" + shape)) {
                rest = rest.substring(0, rest.length() - shape.length() - 1);
                block = true;
                break;
            }
        }
        Material material = Material.byId(rest).orElse(null);
        if (material == null) {
            return Optional.empty();
        }
        Kind kind;
        if (rawForm) {
            kind = block ? Kind.RAW_BLOCK : Kind.RAW_UNIT;
        } else {
            kind = block ? Kind.BLOCK : Kind.UNIT;
        }
        return Optional.of(new CompressionInfo(material, tier, kind));
    }

    private static Optional<CompressionInfo> build(Matcher matcher, boolean isRaw) {
        int tier;
        try {
            tier = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (tier < 1 || tier > Material.MAX_TIER) {
            return Optional.empty();
        }
        Material material = Material.byId(matcher.group(2)).orElse(null);
        if (material == null) {
            return Optional.empty();
        }
        boolean block = matcher.group(3) != null;
        Kind kind;
        if (isRaw) {
            kind = block ? Kind.RAW_BLOCK : Kind.RAW_UNIT;
        } else {
            kind = block ? Kind.BLOCK : Kind.UNIT;
        }
        return Optional.of(new CompressionInfo(material, tier, kind));
    }

    /** 折算成该材料的基础单位数量（块额外 ×9）。 */
    public long equivalents() {
        long base = 1L;
        for (int i = 0; i < tier; i++) {
            base *= Material.PER_TIER;
        }
        return switch (kind) {
            case UNIT, RAW_UNIT -> base;
            case BLOCK, RAW_BLOCK -> base * Material.PER_TIER;
        };
    }

    /** 材料名对应的翻译键。 */
    public String materialKey() {
        return "material.siao." + material.id();
    }
}
