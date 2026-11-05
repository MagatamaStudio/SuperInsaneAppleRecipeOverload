package com.siao.appleoverload.machine;

import com.siao.appleoverload.upgrade.SiaoUpgradeItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/** 三台机器共用的菜单：9 输入 + 3 输出 + 8 升级 + 玩家背包。 */
public class AppleMachineMenu extends AbstractContainerMenu {

    private final AppleMachineBlockEntity machine;
    private final MachineKind kind;
    private final ContainerLevelAccess access;

    public AppleMachineMenu(int containerId, Inventory playerInventory, AppleMachineBlockEntity machine) {
        super(SiaoMachineRegistry.menuFor(machine.machineKind()), containerId);
        this.machine = machine;
        this.kind = machine.machineKind();
        this.access = ContainerLevelAccess.create(machine.getLevel(), machine.getBlockPos());

        IItemHandler handler = machine.getItemHandler();

        for (int i = 0; i < kind.inputSlots(); i++) {
            addSlot(new SlotItemHandler(handler, i, MachineLayout.inputX(i), MachineLayout.inputY(i)));
        }
        for (int i = 0; i < kind.outputSlots(); i++) {
            addSlot(new SlotItemHandler(handler, kind.outputStart() + i,
                    MachineLayout.outputX(), MachineLayout.outputY(i)) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
        for (int i = 0; i < MachineKind.UPGRADE_SLOTS; i++) {
            addSlot(new SlotItemHandler(handler, kind.upgradeStart() + i,
                    MachineLayout.upgradeX(i), MachineLayout.upgradeY(i)));
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9,
                        MachineLayout.PLAYER_X + column * MachineLayout.SLOT,
                        MachineLayout.PLAYER_Y + row * MachineLayout.SLOT));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column,
                    MachineLayout.PLAYER_X + column * MachineLayout.SLOT, MachineLayout.HOTBAR_Y));
        }

        addDataSlots(machine.containerData());
    }

    /** 客户端菜单工厂：额外数据里带着机器坐标，靠它找客户端方块实体。 */
    public static AppleMachineMenu fromNetwork(int containerId, Inventory playerInventory,
                                               RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        BlockEntity blockEntity = playerInventory.player.level().getBlockEntity(pos);
        if (blockEntity instanceof AppleMachineBlockEntity machine) {
            return new AppleMachineMenu(containerId, playerInventory, machine);
        }
        throw new IllegalStateException("客户端找不到机器方块实体：" + pos);
    }

    public MachineKind machineKind() {
        return kind;
    }

    public int energyStored() {
        return machine.containerData().get(AppleMachineBlockEntity.DATA_ENERGY);
    }

    public int energyCapacity() {
        return machine.containerData().get(AppleMachineBlockEntity.DATA_ENERGY_MAX);
    }

    public int progress() {
        return machine.containerData().get(AppleMachineBlockEntity.DATA_PROGRESS);
    }

    public int maxProgress() {
        return machine.containerData().get(AppleMachineBlockEntity.DATA_MAX_PROGRESS);
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, machine.getBlockState().getBlock());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int machineSlots = kind.totalSlots();

        if (index < machineSlots) {
            if (!moveItemStackTo(stack, machineSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.getItem() instanceof SiaoUpgradeItem) {
            if (!moveItemStackTo(stack, kind.upgradeStart(), kind.upgradeStart() + MachineKind.UPGRADE_SLOTS, false)
                    && !moveItemStackTo(stack, kind.inputStart(), kind.inputStart() + kind.inputSlots(), false)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, kind.inputStart(), kind.inputStart() + kind.inputSlots(), false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }
}
