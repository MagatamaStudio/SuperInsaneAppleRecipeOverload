package com.siao.appleoverload.content;

import java.util.Locale;

/**
 * 可被压缩的材料。
 *
 * <p>ID 前缀用于生成物品/方块注册名，中文名用于 lang 文件，
 * 两者由 {@link SiaoNames} 统一拼接。</p>
 */
public enum Material {
    IRON("iron", "铁锭", "Iron Ingot", Form.INGOT, "粗铁", "Raw Iron"),
    GOLD("gold", "金锭", "Gold Ingot", Form.INGOT, "粗金", "Raw Gold"),
    COPPER("copper", "铜锭", "Copper Ingot", Form.INGOT, "粗铜", "Raw Copper"),
    /**
     * 苹果金线。
     *
     * <p>原构想 L4 整段都是「粗苹果金 / 一重压缩粗苹果金 / 附魔一重压缩粗苹果金块」，
     * 所以苹果这条线必须跟铁金铜一样有粗形态。它的第 0 重不是原版物品，
     * 而是自制物品 {@code siao:raw_apple_gold}（苹果 + 粗金），见 {@link SiaoItems}。</p>
     */
    APPLE("apple", "苹果锭", "Apple Ingot", Form.INGOT, "粗苹果金", "Raw Apple Gold"),
    NETHERITE("netherite", "下界合金锭", "Netherite Ingot", Form.INGOT, null, null),
    // ---- 苹果合金线（原构想 L6「苹果和其他锭的合成」）-------------------------
    APPLE_IRON("apple_iron", "苹果铁合金", "Apple Iron Alloy", Form.INGOT, null, null),
    APPLE_GOLD("apple_gold", "苹果金合金", "Apple Gold Alloy", Form.INGOT, null, null),
    APPLE_COPPER("apple_copper", "苹果铜合金", "Apple Copper Alloy", Form.INGOT, null, null),
    APPLE_DIAMOND("apple_diamond", "苹果钻石合金", "Apple Diamond Alloy", Form.GEM, null, null),
    APPLE_NETHERITE("apple_netherite", "苹果下界合金", "Apple Netherite Alloy", Form.INGOT, null, null),
    DIAMOND("diamond", "钻石", "Diamond", Form.GEM, null, null),
    EMERALD("emerald", "绿宝石", "Emerald", Form.GEM, null, null),
    LAPIS("lapis", "青金石", "Lapis Lazuli", Form.GEM, null, null),
    AMETHYST("amethyst", "紫水晶", "Amethyst Shard", Form.GEM, null, null),
    QUARTZ("quartz", "下界石英", "Nether Quartz", Form.GEM, null, null),
    COAL("coal", "煤", "Coal", Form.DUST, null, null),
    REDSTONE("redstone", "红石", "Redstone", Form.DUST, null, null);

    /** 材料形态，决定贴图基底与命名手感。 */
    public enum Form {
        INGOT, GEM, DUST
    }

    public static final int MAX_TIER = 9;
    public static final int PER_TIER = 9;

    private static final java.util.Map<String, Material> BY_ID =
            java.util.Arrays.stream(values())
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Material::id, m -> m));

    private final String id;
    private final String zhUnit;
    private final String enUnit;
    private final Form form;
    /** 粗形态中文名；{@code null} 表示这条线没有粗形态。 */
    private final String zhRaw;
    private final String enRaw;

    Material(String id, String zhUnit, String enUnit, Form form, String zhRaw, String enRaw) {
        this.id = id;
        this.zhUnit = zhUnit;
        this.enUnit = enUnit;
        this.form = form;
        this.zhRaw = zhRaw;
        this.enRaw = enRaw;
    }

    public String id() {
        return id;
    }

    public String zhUnit() {
        return zhUnit;
    }

    public String enUnit() {
        return enUnit;
    }

    public Form form() {
        return form;
    }

    /** 铁/金/铜/苹果有「粗 X」形态，可以再压一条线。 */
    public boolean hasRawForm() {
        return zhRaw != null;
    }

    /** 粗形态中文名，如「粗苹果金」。无粗形态时抛错，调用方必须先问 {@link #hasRawForm()}。 */
    public String zhRaw() {
        return zhRaw;
    }

    /** 粗形态英文名，如「Raw Apple Gold」。 */
    public String enRaw() {
        return enRaw;
    }

    /** 该材料在 {@code tier} 重的等价基础材料数量：9^tier。 */
    public long equivalents(int tier) {
        long result = 1L;
        for (int i = 0; i < tier; i++) {
            result *= PER_TIER;
        }
        return result;
    }

    public String langKeyPrefix() {
        return id.toLowerCase(Locale.ROOT);
    }

    public static java.util.Optional<Material> byId(String id) {
        return java.util.Optional.ofNullable(BY_ID.get(id));
    }
}
