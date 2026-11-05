package com.siao.appleoverload.machine;

import com.siao.appleoverload.content.Material;
import com.siao.appleoverload.content.SiaoComponents;
import com.siao.appleoverload.content.SiaoCompressedItem;
import com.siao.appleoverload.content.SiaoItems;
import com.siao.appleoverload.recipe.CompressionIndex;
import com.siao.appleoverload.recipe.SiaoCompressionRecipe;
import com.siao.appleoverload.upgrade.SiaoUpgradeItem;
import com.siao.appleoverload.upgrade.UpgradeKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 三台机器共用的方块实体：能量 + 进度 + 8 个升级槽 + 输入/输出槽。
 *
 * <p>三台机器的差别只有 {@link #tryOperation()} 里的一个 switch，
 * 其余（能量、升级、存档、GUI 数据）完全一致，所以不做三个类。</p>
 *
 * <p>处理模型：</p>
 * <ul>
 *   <li><b>速度</b>：每 tick 给 {@code progress} 加 {@code 1<<速度等级}，
 *       攒够 {@code baseTicks} 算一次加工 —— 升级直接缩短加工间隔</li>
 *   <li><b>并行</b>：一次结算最多做 {@code 1+并行等级} 次加工</li>
 *   <li><b>能量</b>：每次加工扣一次 FE，能量升级按 0.8^等级 打折</li>
 * </ul>
 */
public class AppleMachineBlockEntity extends BlockEntity implements MenuProvider {

    public static final int DATA_PROGRESS = 0;
    public static final int DATA_MAX_PROGRESS = 1;
    public static final int DATA_ENERGY = 2;
    public static final int DATA_ENERGY_MAX = 3;
    public static final int DATA_COUNT = 4;

    private final MachineKind machineKind;
    private final MachineItemHandler inventory;
    private final MachineEnergyStorage energy;
    private final ContainerData containerData;

    private int progress;
    private int maxProgress = 1;
    private boolean chunkForced;

    public AppleMachineBlockEntity(BlockPos pos, BlockState state) {
        super(SiaoMachineRegistry.MACHINE.get(), pos, state);
        this.machineKind = ((AppleMachineBlock) state.getBlock()).kind();
        this.inventory = new MachineItemHandler(machineKind.totalSlots());
        this.energy = new MachineEnergyStorage(MachineKind.ENERGY_CAPACITY, MachineKind.ENERGY_TRANSFER);
        this.containerData = new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_PROGRESS -> progress;
                    case DATA_MAX_PROGRESS -> maxProgress;
                    case DATA_ENERGY -> energy.getEnergyStored();
                    case DATA_ENERGY_MAX -> energy.getMaxEnergyStored();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch (index) {
                    case DATA_PROGRESS -> progress = value;
                    case DATA_MAX_PROGRESS -> maxProgress = value;
                    default -> {
                    }
                }
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    // ---- 对外接口 -----------------------------------------------------------

    public MachineKind machineKind() {
        return machineKind;
    }

    public IItemHandler getItemHandler() {
        return inventory;
    }

    public IEnergyStorage getEnergyStorage() {
        return energy;
    }

    public ContainerData containerData() {
        return containerData;
    }

    public int progress() {
        return progress;
    }

    public int maxProgress() {
        return maxProgress;
    }

    public int energyStored() {
        return energy.getEnergyStored();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(machineKind.langKey());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new AppleMachineMenu(containerId, playerInventory, this);
    }

    // ---- tick ---------------------------------------------------------------

    public void tick() {
        Level level = this.level;
        if (level == null || level.isClientSide) {
            return;
        }
        if (level.getGameTime() % 20L == 0L) {
            updateAnchor();
        }

        int speed = levelOf(UpgradeKind.SPEED);
        int needed = Math.max(1, machineKind.baseTicks() >> Math.min(speed, 16));
        this.maxProgress = needed;

        int budget = 1 + levelOf(UpgradeKind.PARALLEL);
        progress += 1 << Math.min(speed, 16);

        int operations = 0;
        while (progress >= needed && operations < budget) {
            if (!tryOperation()) {
                progress = 0;
                break;
            }
            progress -= needed;
            operations++;
        }
        // 有输入但并行吃不下时，别让 progress 无限堆积
        int cap = needed * budget;
        if (progress > cap) {
            progress = cap;
        }
        if (operations > 0) {
            setChanged();
        }
    }

    private boolean tryOperation() {
        int cost = energyPerOperation();
        if (energy.extractEnergy(cost, true) < cost) {
            return false;
        }
        boolean processed = switch (machineKind) {
            case COMPRESSOR -> runCompressor();
            case ENCHANTER -> runEnchanter();
            case CENTRIFUGE -> runCentrifuge();
        };
        if (processed) {
            energy.extractEnergy(cost, false);
        }
        return processed;
    }

    // ---- 三台机器的加工逻辑 -------------------------------------------------

    /** 9 个同种物品 → 1 个上一重。附魔穿透交给配方自己判，机器不重复实现。 */
    private boolean runCompressor() {
        ItemStack first = inventory.getStackInSlot(0);
        SiaoCompressionRecipe recipe = CompressionIndex.find(level.getRecipeManager(), first);
        if (recipe == null) {
            return false;
        }
        CraftingInput input = craftingInput();
        if (!recipe.matches(input, level)) {
            return false;
        }
        ItemStack output = recipe.assemble(input, level.registryAccess());
        if (!insertOutput(output)) {
            return false;
        }
        for (int slot = 0; slot < MachineKind.COMPRESSOR.inputSlots(); slot++) {
            inventory.extractItem(slot, 1, false);
        }
        return true;
    }

    /** 给压缩单位挂附魔；已经附魔的则提升强度。 */
    private boolean runEnchanter() {
        ItemStack input = inventory.getStackInSlot(0);
        if (input.isEmpty() || !(input.getItem() instanceof SiaoCompressedItem)) {
            return false;
        }
        int tier = SiaoComponents.enchantTier(input);
        if (tier >= Material.MAX_TIER) {
            return false;
        }
        ItemStack output = input.copyWithCount(1);
        SiaoComponents.makeEnchanted(output, tier + 1);
        if (!insertOutput(output)) {
            return false;
        }
        input.shrink(1);
        return true;
    }

    /** 附魔压缩单位 → 奇点碎片，数量 = 附魔强度。 */
    private boolean runCentrifuge() {
        ItemStack input = inventory.getStackInSlot(0);
        if (input.isEmpty() || !(input.getItem() instanceof SiaoCompressedItem)) {
            return false;
        }
        int tier = SiaoComponents.enchantTier(input);
        if (tier <= 0) {
            return false;
        }
        ItemStack output = new ItemStack(SiaoItems.singularityShard().get(), tier);
        if (!insertOutput(output)) {
            return false;
        }
        input.shrink(1);
        return true;
    }

    // ---- 辅助 ---------------------------------------------------------------

    private CraftingInput craftingInput() {
        List<ItemStack> slots = new ArrayList<>(MachineKind.COMPRESSOR.inputSlots());
        for (int slot = 0; slot < MachineKind.COMPRESSOR.inputSlots(); slot++) {
            slots.add(inventory.getStackInSlot(slot));
        }
        return CraftingInput.of(3, 3, slots);
    }

    private boolean insertOutput(ItemStack stack) {
        int start = machineKind.outputStart();
        for (int slot = start; slot < start + machineKind.outputSlots(); slot++) {
            if (inventory.insertItem(slot, stack.copy(), true).isEmpty()) {
                inventory.insertItem(slot, stack.copy(), false);
                return true;
            }
        }
        return false;
    }

    public int levelOf(UpgradeKind type) {
        int start = machineKind.upgradeStart();
        int sum = 0;
        for (int slot = start; slot < start + MachineKind.UPGRADE_SLOTS; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.getItem() instanceof SiaoUpgradeItem upgrade && upgrade.kind() == type) {
                sum += upgrade.contributedLevel(stack);
            }
        }
        return Math.min(sum, type.effectiveMaxLevel());
    }

    public int energyPerOperation() {
        int percent = energyPercent(levelOf(UpgradeKind.ENERGY));
        return Math.max(1, machineKind.energyPerOperation() * percent / 100);
    }

    /** 每级能耗 ×0.8，最低 5%。 */
    public static int energyPercent(int level) {
        double factor = Math.pow(0.8D, Math.max(0, level));
        return Math.max(5, (int) Math.round(factor * 100.0D));
    }

    private void updateAnchor() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean wanted = levelOf(UpgradeKind.ANCHOR) > 0;
        if (wanted == chunkForced) {
            return;
        }
        chunkForced = wanted;
        serverLevel.setChunkForced(worldPosition.getX() >> 4, worldPosition.getZ() >> 4, wanted);
    }

    @Override
    public void setRemoved() {
        if (chunkForced && level instanceof ServerLevel serverLevel) {
            serverLevel.setChunkForced(worldPosition.getX() >> 4, worldPosition.getZ() >> 4, false);
        }
        super.setRemoved();
    }

    // ---- 存档 ---------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", inventory.serializeNBT(registries));
        tag.put("energy", energy.serializeNBT(registries));
        tag.putInt("progress", progress);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        energy.deserializeNBT(registries, tag.get("energy"));
        progress = tag.getInt("progress");
    }

    // ---- 内部类型 -----------------------------------------------------------

    private final class MachineItemHandler extends ItemStackHandler {
        private MachineItemHandler(int size) {
            super(size);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot >= machineKind.upgradeStart()) {
                return stack.getItem() instanceof SiaoUpgradeItem;
            }
            if (slot >= machineKind.outputStart()) {
                return false;
            }
            return machineKind.usesInputSlot(slot);
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    }

    private final class MachineEnergyStorage extends EnergyStorage {
        private MachineEnergyStorage(int capacity, int transfer) {
            super(capacity, transfer, 0);
        }

        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int received = super.receiveEnergy(toReceive, simulate);
            if (received > 0 && !simulate) {
                setChanged();
            }
            return received;
        }
    }
}
