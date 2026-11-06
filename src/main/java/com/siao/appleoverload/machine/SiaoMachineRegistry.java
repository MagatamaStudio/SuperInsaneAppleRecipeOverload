package com.siao.appleoverload.machine;

import com.siao.appleoverload.SiaoMod;
import com.siao.appleoverload.content.SiaoBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** 机器的方块实体类型与菜单类型注册。 */
public final class SiaoMachineRegistry {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SiaoMod.MOD_ID);

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, SiaoMod.MOD_ID);

    /**
     * 所有机器共用一个方块实体类型，合法方块列表**直接从 {@link MachineKind} 生成**。
     *
     * <p>手写列表的代价我踩过了：加了苹果农场却忘了加进这里，
     * 表现是「方块放得下、方块实体创建不了」，自检里报的是
     * {@code Invalid block entity ... got Block{siao:apple_orchard}}。</p>
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AppleMachineBlockEntity>> MACHINE =
            BLOCK_ENTITIES.register("apple_machine", () -> {
                Block[] blocks = Arrays.stream(MachineKind.values())
                        .map(kind -> SiaoBlocks.machine(kind).get())
                        .toArray(Block[]::new);
                return BlockEntityType.Builder.of(AppleMachineBlockEntity::new, blocks).build(null);
            });

    private static final Map<MachineKind, DeferredHolder<MenuType<?>, MenuType<AppleMachineMenu>>> MENU_TYPES =
            new EnumMap<>(MachineKind.class);

    static {
        for (MachineKind kind : MachineKind.values()) {
            MENU_TYPES.put(kind, MENUS.register(kind.blockId(),
                    () -> IMenuTypeExtension.create(AppleMachineMenu::fromNetwork)));
        }
    }

    private SiaoMachineRegistry() {
    }

    public static MenuType<AppleMachineMenu> menuFor(MachineKind kind) {
        return MENU_TYPES.get(kind).get();
    }
}
