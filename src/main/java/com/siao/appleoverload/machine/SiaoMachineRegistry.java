package com.siao.appleoverload.machine;

import com.siao.appleoverload.SiaoMod;
import com.siao.appleoverload.content.SiaoBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

/** 机器的方块实体类型与菜单类型注册。 */
public final class SiaoMachineRegistry {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SiaoMod.MOD_ID);

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, SiaoMod.MOD_ID);

    /** 三台机器共用一个方块实体类型，合法方块列表里挂三个块。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AppleMachineBlockEntity>> MACHINE =
            BLOCK_ENTITIES.register("apple_machine", () -> BlockEntityType.Builder.of(
                    AppleMachineBlockEntity::new,
                    SiaoBlocks.machine(MachineKind.COMPRESSOR).get(),
                    SiaoBlocks.machine(MachineKind.ENCHANTER).get(),
                    SiaoBlocks.machine(MachineKind.CENTRIFUGE).get()).build(null));

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
