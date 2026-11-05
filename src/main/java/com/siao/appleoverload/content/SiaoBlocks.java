package com.siao.appleoverload.content;

import com.siao.appleoverload.SiaoMod;
import com.siao.appleoverload.machine.AppleMachineBlock;
import com.siao.appleoverload.machine.MachineKind;
import net.minecraft.world.level.block.Block;
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
    private static final Map<MachineKind, DeferredBlock<Block>> MACHINES = new EnumMap<>(MachineKind.class);

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
    }

    private SiaoBlocks() {
    }

    private static DeferredBlock<Block> register(String id, Material material) {
        SoundType sound = material.form() == Material.Form.INGOT ? SoundType.METAL : SoundType.STONE;
        return BLOCKS.registerSimpleBlock(id, BlockBehaviour.Properties.of()
                .strength(2.0F, 6.0F)
                .sound(sound));
    }

    public static Collection<DeferredBlock<Block>> unitBlocks() {
        return UNIT_BLOCKS.values();
    }

    public static Collection<DeferredBlock<Block>> rawBlocks() {
        return RAW_BLOCKS.values();
    }

    public static DeferredBlock<Block> unitBlock(Material material, int tier) {
        return UNIT_BLOCKS.get(SiaoNames.unitBlockId(material, tier));
    }

    public static DeferredBlock<Block> machine(MachineKind kind) {
        return MACHINES.get(kind);
    }

    public static Collection<DeferredBlock<Block>> machineBlocks() {
        return MACHINES.values();
    }

    public static int blockCount() {
        return UNIT_BLOCKS.size() + RAW_BLOCKS.size() + MACHINES.size();
    }
}
