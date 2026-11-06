package com.siao.appleoverload.content;

import com.siao.appleoverload.SiaoMod;
import com.siao.appleoverload.machine.AppleMachineBlock;
import com.siao.appleoverload.machine.MachineKind;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全部压缩方块。每个方块同时注册同名 BlockItem。
 *
 * <p>铜氧化/涂蜡变种不在这里，属于后续阶段。</p>
 */
public final class SiaoBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SiaoMod.MOD_ID);

    private static final Map<String, DeferredBlock<Block>> UNIT_BLOCKS = new LinkedHashMap<>();
    private static final Map<String, DeferredBlock<Block>> RAW_BLOCKS = new LinkedHashMap<>();
    private static final Map<String, DeferredBlock<Block>> COPPER_VARIANTS = new LinkedHashMap<>();
    private static final Map<MachineKind, DeferredBlock<Block>> MACHINES = new EnumMap<>(MachineKind.class);
    /** C 线：氧化/涂蜡变种。 */
    private static final Map<String, DeferredBlock<Block>> OXIDIZED_BLOCKS = new LinkedHashMap<>();

    static {
        for (Material material : Material.values()) {
            for (int tier = 1; tier <= Material.MAX_TIER; tier++) {
                String id = SiaoNames.unitBlockId(material, tier);
                DeferredBlock<Block> block = register(id, material);
                UNIT_BLOCKS.put(id, block);
                SiaoItems.registerBlockItem(id, block);

                if (material.hasRawForm()) {
                    String rawId = SiaoNames.rawBlockId(material, tier);
                    DeferredBlock<Block> rawBlock = register(rawId, material);
                    RAW_BLOCKS.put(rawId, rawBlock);
                    SiaoItems.registerBlockItem(rawId, rawBlock);
                }
            }
        }

        for (MachineKind kind : MachineKind.values()) {
            String id = kind.blockId();
            DeferredBlock<Block> block = BLOCKS.register(id, () -> new AppleMachineBlock(kind,
                    BlockBehaviour.Properties.of()
                            .strength(3.5F, 8.0F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.METAL)));
            MACHINES.put(kind, block);
            SiaoItems.registerBlockItem(id, block);
        }

        // ---- C 线：所有压缩方块都有 4 个氧化阶 × 涂蜡 ----------------------
        // 未氧化+未涂蜡那格就是基础方块本身，跳过；其余 7 格在这里注册
        for (Material material : Material.values()) {
            for (int tier = 1; tier <= Material.MAX_TIER; tier++) {
                registerOxidation(material, tier, false);
                if (material.hasRawForm()) {
                    registerOxidation(material, tier, true);
                }
            }
        }

        // 铜专线：4 氧化阶 × 5 整块形状 × 2 涂蜡 = 40，其中 1 格被阶段 1 的压缩块占用
        for (CopperVariant.Stage stage : CopperVariant.Stage.values()) {
            for (CopperVariant.Shape shape : CopperVariant.Shape.values()) {
                for (boolean waxed : new boolean[]{false, true}) {
                    if (CopperVariant.isOccupiedByCompressionBlock(stage, shape, waxed)) {
                        continue;
                    }
                    String id = CopperVariant.id(stage, shape, waxed);
                    BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                            .strength(3.0F, 6.0F)
                            .requiresCorrectToolForDrops()
                            .sound(SoundType.COPPER);
                    DeferredBlock<Block> block = switch (shape) {
                        case STAIRS -> BLOCKS.register(id, () -> new SiaoStairBlock(
                                Blocks.CUT_COPPER.defaultBlockState(), properties));
                        case SLAB -> BLOCKS.register(id, () -> new SlabBlock(properties));
                        default -> BLOCKS.registerSimpleBlock(id, properties);
                    };
                    COPPER_VARIANTS.put(id, block);
                    SiaoItems.registerBlockItem(id, block);
                }
            }
        }
    }

    private SiaoBlocks() {
    }

    /**
     * 给一个压缩方块注册它的 7 个氧化/涂蜡变种。
     *
     * <p>未涂蜡的那几阶用 {@link SiaoOxidizableBlock}（随机刻会往下氧化），
     * 涂蜡的用普通 Block（锁死）。氧化链本身不写一行逻辑 —— 全部由
     * {@code neoforge:oxidizables} / {@code neoforge:waxables} 数据映射驱动。</p>
     */
    private static void registerOxidation(Material material, int tier, boolean raw) {
        for (int stage = 0; stage <= 3; stage++) {
            for (boolean waxed : new boolean[]{false, true}) {
                if (stage == 0 && !waxed) {
                    continue;   // 这一格就是基础方块本身
                }
                String id = SiaoNames.oxidizedBlockId(material, tier, raw, stage, waxed);
                BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                        .strength(2.0F, 6.0F)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.COPPER);
                DeferredBlock<Block> block;
                if (waxed) {
                    block = BLOCKS.registerSimpleBlock(id, properties);
                } else {
                    WeatheringCopper.WeatherState state = switch (stage) {
                        case 1 -> WeatheringCopper.WeatherState.EXPOSED;
                        case 2 -> WeatheringCopper.WeatherState.WEATHERED;
                        default -> WeatheringCopper.WeatherState.OXIDIZED;
                    };
                    block = BLOCKS.register(id, () -> new SiaoOxidizableBlock(state, properties.randomTicks()));
                }
                OXIDIZED_BLOCKS.put(id, block);
                SiaoItems.registerBlockItem(id, block);

                // ---- D 线：四种整块形状跟着每个氧化态一起生成 ----------
                // 铜的第 1 重已经有专门的 CopperVariant 线，跳过避免 ID 撞车
                if (material == Material.COPPER && tier == 1) {
                    continue;
                }
                for (String shape : SiaoNames.ALL_SHAPES) {
                    // 形状变种是装饰线，不参与氧化
                    String shapeId = SiaoNames.shapeBlockId(material, tier, raw, stage, shape, waxed);
                    DeferredBlock<Block> shapeBlock = registerShapeBlock(shapeId, shape, properties);
                    OXIDIZED_BLOCKS.put(shapeId, shapeBlock);
                    SiaoItems.registerBlockItem(shapeId, shapeBlock);
                }
            }
        }

        // stage 0 的未涂蜡形状：上面那个循环在 (stage==0, waxed=false) 时直接跳过了
        // 基础方块本身，但它的形状变种是独立 id，必须补注册
        if (!(material == Material.COPPER && tier == 1)) {
            for (String shape : SiaoNames.ALL_SHAPES) {
                String shapeId = SiaoNames.shapeBlockId(material, tier, raw, 0, shape, false);
                BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
                        .strength(2.0F, 6.0F)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.COPPER);
                DeferredBlock<Block> shapeBlock = registerShapeBlock(shapeId, shape, props);
                OXIDIZED_BLOCKS.put(shapeId, shapeBlock);
                SiaoItems.registerBlockItem(shapeId, shapeBlock);
            }
        }
    }
    /** 按形状注册：楼梯 / 台阶 / 整块。 */
    private static DeferredBlock<Block> registerShapeBlock(String id, String shape,
                                                           BlockBehaviour.Properties properties) {
        return switch (shape) {
            case "stairs" -> BLOCKS.register(id, () -> new SiaoStairBlock(
                    Blocks.CUT_COPPER.defaultBlockState(), properties));
            case "slab" -> BLOCKS.register(id, () -> new SlabBlock(properties));
            default -> BLOCKS.registerSimpleBlock(id, properties);
        };
    }
    private static DeferredBlock<Block> register(String id, Material material) {
        SoundType sound = material.form() == Material.Form.INGOT ? SoundType.METAL : SoundType.STONE;
        // 基础方块本身就是氧化链的起点，必须也实现 WeatheringCopper + randomTicks
        return BLOCKS.register(id, () -> new SiaoOxidizableBlock(
                WeatheringCopper.WeatherState.UNAFFECTED,
                BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(sound).randomTicks()));
    }

    public static Collection<DeferredBlock<Block>> unitBlocks() {
        return UNIT_BLOCKS.values();
    }

    public static Collection<DeferredBlock<Block>> rawBlocks() {
        return RAW_BLOCKS.values();
    }

    /** 粗形态块（如粗苹果金块），自检和配方要用。 */
    public static DeferredBlock<Block> rawBlock(Material material, int tier) {
        return RAW_BLOCKS.get(SiaoNames.rawBlockId(material, tier));
    }

    public static DeferredBlock<Block> unitBlock(Material material, int tier) {
        return UNIT_BLOCKS.get(SiaoNames.unitBlockId(material, tier));
    }

    public static DeferredBlock<Block> machine(MachineKind kind) {
        return MACHINES.get(kind);
    }

    public static DeferredBlock<Block> copperVariant(CopperVariant.Stage stage, CopperVariant.Shape shape,
                                                     boolean waxed) {
        if (CopperVariant.isOccupiedByCompressionBlock(stage, shape, waxed)) {
            return unitBlock(Material.COPPER, 1);
        }
        return COPPER_VARIANTS.get(CopperVariant.id(stage, shape, waxed));
    }

    public static Collection<DeferredBlock<Block>> copperVariants() {
        return COPPER_VARIANTS.values();
    }

    public static int copperVariantCount() {
        return COPPER_VARIANTS.size();
    }

    public static Collection<DeferredBlock<Block>> machineBlocks() {
        return MACHINES.values();
    }

    public static int blockCount() {
        return UNIT_BLOCKS.size() + RAW_BLOCKS.size() + MACHINES.size() + COPPER_VARIANTS.size();
    }
}
