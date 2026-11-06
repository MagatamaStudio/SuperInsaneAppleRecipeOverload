package com.siao.appleoverload.content;

import com.siao.appleoverload.SiaoMod;
import com.siao.appleoverload.upgrade.SiaoUpgradeItem;
import com.siao.appleoverload.upgrade.SiaoUpgrades;
import com.siao.appleoverload.upgrade.UpgradeType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全部物品注册（含方块对应的 BlockItem）。
 *
 * <p>注册顺序即创造栏顺序：压缩单位 → 粗形态单位 → 压缩块。</p>
 */
public final class SiaoItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SiaoMod.MOD_ID);

    private static final Map<String, DeferredItem<Item>> UNITS = new LinkedHashMap<>();
    private static final Map<String, DeferredItem<Item>> RAW_UNITS = new LinkedHashMap<>();
    private static final Map<String, DeferredItem<BlockItem>> BLOCK_ITEMS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, DeferredItem<SiaoUpgradeItem>> UPGRADES = new LinkedHashMap<>();

    /** 奇点碎片：离心机处理混沌苹果得到（不再从附魔单位产出）。 */
    public static final DeferredItem<Item> SINGULARITY_SHARD =
            ITEMS.registerSimpleItem("singularity_shard");

    /** 绝对苹果奇点：九重压缩苹果锭 ×1 的等价物，图腾级目标。 */
    public static final DeferredItem<Item> SINGULARITY =
            ITEMS.registerSimpleItem("singularity");

    /** 混沌苹果：手动合成的展示物，不进 AE2 合成链（随机组件会卡死合成 CPU）。 */
    public static final DeferredItem<Item> CHAOS_APPLE =
            ITEMS.registerSimpleItem("chaos_apple");

    /**
     * 粗苹果金：苹果线的粗形态第 0 重（原构想 L4）。
     *
     * <p>用 {@link SiaoCompressedItem} 而不是普通 Item —— 原需求要「附魔粗苹果金」，
     * 它得和压缩单位一样会改名发光。</p>
     */
    public static final DeferredItem<Item> RAW_APPLE_GOLD =
            ITEMS.registerItem("raw_apple_gold", SiaoCompressedItem::new);
    /** 苹果配置器：右键机器的某个面来切该面的通行规则（Mek 式）。 */
    public static final DeferredItem<Item> CONFIGURATOR =
            ITEMS.registerItem("configurator", SiaoConfiguratorItem::new);
    static {
        for (Material material : Material.values()) {
            for (int tier = 1; tier <= Material.MAX_TIER; tier++) {
                String unitId = SiaoNames.unitId(material, tier);
                UNITS.put(unitId, ITEMS.registerItem(unitId, SiaoCompressedItem::new));

                if (material.hasRawForm()) {
                    String rawId = SiaoNames.rawUnitId(material, tier);
                    RAW_UNITS.put(rawId, ITEMS.registerItem(rawId, SiaoCompressedItem::new));
                }
            }
        }

        registerUpgrade(SiaoUpgrades.SPEED);
        registerUpgrade(SiaoUpgrades.ENERGY);
        registerUpgrade(SiaoUpgrades.PARALLEL);
        registerUpgrade(SiaoUpgrades.ANCHOR);
        registerUpgrade(SiaoUpgrades.OVERCLOCK);
        registerUpgrade(SiaoUpgrades.QUANTUM);
        registerUpgrade(SiaoUpgrades.FISSION);
    }

    private static DeferredItem<SiaoUpgradeItem> registerUpgrade(DeferredHolder<UpgradeType, UpgradeType> type) {
        DeferredItem<SiaoUpgradeItem> item = ITEMS.registerItem(
                type.getId().getPath() + "_upgrade",
                properties -> new SiaoUpgradeItem(type, properties));
        UPGRADES.put(type.getId(), item);
        return item;
    }

    private SiaoItems() {
    }

    /** 由 {@link SiaoBlocks} 在注册方块后回调，保证 BlockItem 与方块同名。 */
    static DeferredItem<BlockItem> registerBlockItem(String id, DeferredBlock<Block> block) {
        // 用自定义 BlockItem：原需求要「附魔压缩锭块」，而附魔状态是数据组件，
        // 方块物品也得会改名和发光（普通 BlockItem 覆写不了）
        DeferredItem<BlockItem> item = ITEMS.registerItem(id, properties -> {
            SiaoCompressedBlockItem blockItem = new SiaoCompressedBlockItem(block.get(), properties);
            // 手动建立 block -> item 映射。走 registerItem 不会替我们做这件事，
            // 漏了的话 block.asItem() 会返回 AIR，掉落物和创造栏都会坏
            blockItem.registerBlocks(net.minecraft.world.item.Item.BY_BLOCK, blockItem);
            return blockItem;
        });
        BLOCK_ITEMS.put(id, item);
        return item;
    }

    public static Collection<DeferredItem<Item>> units() {
        return UNITS.values();
    }

    public static Collection<DeferredItem<Item>> rawUnits() {
        return RAW_UNITS.values();
    }

    public static Collection<DeferredItem<BlockItem>> blockItems() {
        return BLOCK_ITEMS.values();
    }

    public static DeferredItem<Item> unit(Material material, int tier) {
        return UNITS.get(SiaoNames.unitId(material, tier));
    }

    public static DeferredItem<Item> rawUnit(Material material, int tier) {
        return RAW_UNITS.get(SiaoNames.rawUnitId(material, tier));
    }

    public static DeferredItem<BlockItem> blockItem(Material material, int tier) {
        return BLOCK_ITEMS.get(SiaoNames.unitBlockId(material, tier));
    }

    public static int unitCount() {
        return UNITS.size();
    }

    public static int rawUnitCount() {
        return RAW_UNITS.size();
    }

    public static DeferredItem<SiaoUpgradeItem> upgrade(DeferredHolder<UpgradeType, UpgradeType> type) {
        return UPGRADES.get(type.getId());
    }

    public static Collection<DeferredItem<SiaoUpgradeItem>> upgrades() {
        return UPGRADES.values();
    }

    public static DeferredItem<Item> rawAppleGold() {
        return RAW_APPLE_GOLD;
    }

    public static DeferredItem<Item> configurator() {
        return CONFIGURATOR;
    }

    public static DeferredItem<Item> singularityShard() {
        return SINGULARITY_SHARD;
    }

    public static DeferredItem<Item> singularity() {
        return SINGULARITY;
    }

    public static DeferredItem<Item> chaosApple() {
        return CHAOS_APPLE;
    }

    public static int blockItemCount() {
        return BLOCK_ITEMS.size();
    }
}

