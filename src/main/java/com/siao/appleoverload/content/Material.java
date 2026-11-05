package com.siao.appleoverload.content;

import java.util.Locale;

/**
 * 可被压缩的材料。
 *
 * <p>ID 前缀用于生成物品/方块注册名，中文名用于 lang 文件，
 * 两者由 {@link SiaoNames} 统一拼接。</p>
 */
public enum Material {
    IRON("iron", "铁锭", "Iron Ingot", Form.INGOT, true),
    GOLD("gold", "金锭", "Gold Ingot", Form.INGOT, true),
    COPPER("copper", "铜锭", "Copper Ingot", Form.INGOT, true),
    NETHERITE("netherite", "下界合金锭", "Netherite Ingot", Form.INGOT, false),
    DIAMOND("diamond", "钻石", "Diamond", Form.GEM, false),
    EMERALD("emerald", "绿宝石", "Emerald", Form.GEM, false),
    LAPIS("lapis", "青金石", "Lapis Lazuli", Form.GEM, false),
    AMETHYST("amethyst", "紫水晶", "Amethyst Shard", Form.GEM, false),
    QUARTZ("quartz", "下界石英", "Nether Quartz", Form.GEM, false),
    COAL("coal", "煤", "Coal", Form.DUST, false),
    REDSTONE("redstone", "红石", "Redstone", Form.DUST, false),
    APPLE("apple", "苹果锭", "Apple Ingot", Form.INGOT, false);

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
    private final boolean hasRawForm;

    Material(String id, String zhUnit, String enUnit, Form form, boolean hasRawForm) {
        this.id = id;
        this.zhUnit = zhUnit;
        this.enUnit = enUnit;
        this.form = form;
        this.hasRawForm = hasRawForm;
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

    /** 铁/金/铜有「粗 X」原矿形态，可以再压一条线。 */
    public boolean hasRawForm() {
        return hasRawForm;
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
