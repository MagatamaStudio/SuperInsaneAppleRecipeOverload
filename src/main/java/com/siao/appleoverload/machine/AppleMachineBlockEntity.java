package com.siao.appleoverload.machine;

import com.siao.appleoverload.config.SiaoConfig;
import com.siao.appleoverload.content.Material;
import com.siao.appleoverload.content.SiaoComponents;
import com.siao.appleoverload.content.SiaoCompressedItem;
import com.siao.appleoverload.content.SiaoItems;
import com.siao.appleoverload.recipe.CompressionIndex;
import com.siao.appleoverload.recipe.SiaoCompressionRecipe;
import com.siao.appleoverload.upgrade.MachineStat;
import com.siao.appleoverload.upgrade.SiaoUpgradeItem;
import com.siao.appleoverload.upgrade.SiaoUpgrades;
import com.siao.appleoverload.upgrade.UpgradeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

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
    /** 六个面的通行规则，2 bit 一个面，打包进一个 int 走 ContainerData 同步。 */
    public static final int DATA_SIDES = 4;
    public static final int DATA_COUNT = 5;

    private final MachineKind machineKind;
    private final MachineItemHandler inventory;
    private final MachineEnergyStorage energy;
    private final ContainerData containerData;

    /** 下标 = {@link Direction#ordinal()}。 */
    private final SideMode[] sides = defaultSides();

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
                    case DATA_SIDES -> packedSides();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch (index) {
                    case DATA_PROGRESS -> progress = value;
                    case DATA_MAX_PROGRESS -> maxProgress = value;
                    case DATA_SIDES -> unpackSides(value);
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

    /**
     * 某个面能看到的物品栏视图。
     *
     * <p>{@code side == null}（内部/无面）返回完整栏位，机器自己的加工逻辑走这里，
     * 所以侧配置怎么调都不会把机器自己卡死。</p>
     */
    public IItemHandler getItemHandlerFor(@Nullable Direction side) {
        if (side == null) {
            return inventory;
        }
        SideMode mode = sides[side.ordinal()];
        if (mode == SideMode.NONE) {
            return MachineSideHandler.empty();
        }
        boolean input = mode.allowsInsert();
        boolean output = mode.allowsExtract();
        if (input && output) {
            return inventory;
        }
        if (input) {
            return new MachineSideHandler(inventory, machineKind.inputStart(),
                    machineKind.inputSlots(), true, false);
        }
        return new MachineSideHandler(inventory, machineKind.outputStart(),
                machineKind.outputSlots(), false, true);
    }

    /**
     * 只含**输出槽**的视图，给 AE2 的 {@code ME_STORAGE} 用。
     *
     * <p>为什么不让 AE2 从完整物品栏里取：那样它能把排队待加工的原料抽走。
     * 机器对外的「可取内容」只有产物。</p>
     */
    public IItemHandler getOutputHandlerFor(@Nullable Direction side) {
        if (side != null && !sides[side.ordinal()].allowsExtract()) {
            return MachineSideHandler.empty();
        }
        return new MachineSideHandler(inventory, machineKind.outputStart(),
                machineKind.outputSlots(), false, true);
    }

    /**
     * AE2 整批推料：**全部放得下才放**，否则一点不动。
     *
     * <p>{@code ICraftingMachine#pushPattern} 的语义是「全有或全无」，
     * 所以先模拟插一遍，确认能吃完再真插。</p>
     */
    public boolean acceptCraftingBatch(ItemStack batch) {
        if (batch.isEmpty()) {
            return false;
        }
        int start = machineKind.inputStart();

        ItemStack remaining = batch.copy();
        for (int slot = start; slot < start + machineKind.inputSlots() && !remaining.isEmpty(); slot++) {
            remaining = inventory.insertItem(slot, remaining, true);
        }
        if (!remaining.isEmpty()) {
            return false;
        }

        remaining = batch.copy();
        for (int slot = start; slot < start + machineKind.inputSlots() && !remaining.isEmpty(); slot++) {
            remaining = inventory.insertItem(slot, remaining, false);
        }
        setChanged();
        return true;
    }

    public SideMode sideMode(Direction side) {
        return sides[side.ordinal()];
    }

    public void setSideMode(Direction side, SideMode mode) {
        sides[side.ordinal()] = mode;
        setChanged();
    }

    /**
     * 玩家点一下配置按钮：切掉「玩家所站的那个面」的模式。
     *
     * <p>没做 AE2 那种六面小方块 UI —— 那个要画立方体、要处理鼠标拾取，
     * 而界面我无法验证。用「你站在哪边就改哪边」的规则，行为可预测且不用画图。</p>
     */
    public SideMode cycleSideNear(Player player) {
        Direction face = Direction.getNearest(
                player.getX() - (worldPosition.getX() + 0.5D),
                player.getEyeY() - (worldPosition.getY() + 0.5D),
                player.getZ() - (worldPosition.getZ() + 0.5D));
        SideMode next = sides[face.ordinal()].next();
        sides[face.ordinal()] = next;
        setChanged();
        return next;
    }

    /** 玩家所站的那个面（给界面显示用）。 */
    public Direction faceNear(Player player) {
        return Direction.getNearest(
                player.getX() - (worldPosition.getX() + 0.5D),
                player.getEyeY() - (worldPosition.getY() + 0.5D),
                player.getZ() - (worldPosition.getZ() + 0.5D));
    }

    private static SideMode[] defaultSides() {
        SideMode[] array = new SideMode[6];
        java.util.Arrays.fill(array, SideMode.BOTH);
        return array;
    }

    private int packedSides() {
        int packed = 0;
        for (int i = 0; i < sides.length; i++) {
            packed |= sides[i].ordinal() << (2 * i);
        }
        return packed;
    }

    private void unpackSides(int packed) {
        for (int i = 0; i < sides.length; i++) {
            sides[i] = SideMode.byOrdinal((packed >> (2 * i)) & 0b11);
        }
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

        int speed = statLevel(MachineStat.SPEED);
        int needed = Math.max(1, machineKind.baseTicks() >> Math.min(speed, 16));
        this.maxProgress = needed;

        int budget = 1 + statLevel(MachineStat.PARALLEL);
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
        autoOutput();
    }

    /**
     * 主动把产物推给相邻的 {@code IItemHandler}。
     *
     * <p>这是 AE2 档位 0 的关键一步：样板供应器**不会来机器里取产物**，
     * 它每 tick 只把自己那 9 格返回槽灌回网络。所以机器必须自己推回去。
     * AE2 用 {@code registerGenericAdapters} 把供应器的返回槽适配成普通
     * {@code IItemHandler}，所以这里只依赖 NeoForge 标准能力，**不需要编译期依赖 AE2**。</p>
     */
    private void autoOutput() {
        int start = machineKind.outputStart();
        boolean hasOutput = false;
        for (int slot = start; slot < start + machineKind.outputSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                hasOutput = true;
                break;
            }
        }
        if (!hasOutput) {
            return;
        }

        for (Direction direction : Direction.values()) {
            BlockPos targetPos = worldPosition.relative(direction);
            IItemHandler target = level.getCapability(Capabilities.ItemHandler.BLOCK,
                    targetPos, direction.getOpposite());
            if (target == null) {
                continue;
            }
            for (int slot = start; slot < start + machineKind.outputSlots(); slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                ItemStack remaining = ItemHandlerHelper.insertItemStacked(target, stack, false);
                if (remaining.getCount() != stack.getCount()) {
                    inventory.setStackInSlot(slot, remaining);
                }
            }
        }
    }

    private boolean tryOperation() {
        int cost = energyPerOperation();
        if (!energy.hasEnergy(cost)) {
            return false;
        }
        boolean processed = switch (machineKind) {
            case COMPRESSOR -> runCompressor();
            case ENCHANTER -> runEnchanter();
            case CENTRIFUGE -> runCentrifuge();
        };
        if (processed) {
            energy.consume(cost);
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

    /**
     * 离心机。
     *
     * <p>⚠️ 这里**不再**把附魔压缩单位变成奇点碎片。那条是从阶段 3 的临时设计，
     * 有两个问题：奇点碎片是阶段 6 的物品，不该由阶段 3 的机器产出；
     * 而且「附魔台灌 500 FE → 离心机 → 碎片 → 换量子核心」是条明显的换钱路。</p>
     *
     * <p>现在两件事：</p>
     * <ol>
     *   <li><b>解附魔</b>：附魔压缩单位 → 同阶普通单位。补上「附魔件不能拆块/拆分」的死路</li>
     *   <li><b>处理混沌苹果</b>：混沌苹果 → 1 个奇点碎片（奇点碎片只从阶段 6 的产物里来）</li>
     * </ol>
     */
    private boolean runCentrifuge() {
        ItemStack input = inventory.getStackInSlot(0);
        if (input.isEmpty()) {
            return false;
        }

        if (input.is(SiaoItems.chaosApple().get())) {
            ItemStack shard = new ItemStack(SiaoItems.singularityShard().get(), 1);
            if (!insertOutput(shard)) {
                return false;
            }
            input.shrink(1);
            return true;
        }

        if (input.getItem() instanceof SiaoCompressedItem && SiaoComponents.isEnchanted(input)) {
            ItemStack purified = input.copyWithCount(1);
            SiaoComponents.clearEnchantment(purified);
            if (!insertOutput(purified)) {
                return false;
            }
            input.shrink(1);
            return true;
        }

        return false;
    }

    // ---- 辅助 ---------------------------------------------------------------

    private CraftingInput craftingInput() {
        List<ItemStack> slots = new ArrayList<>(MachineKind.COMPRESSOR.inputSlots());
        for (int slot = 0; slot < MachineKind.COMPRESSOR.inputSlots(); slot++) {
            slots.add(inventory.getStackInSlot(slot));
        }
        return CraftingInput.of(3, 3, slots);
    }

    /**
     * 往输出槽里塞产物。
     *
     * <p>⚠️ 不能走 {@code inventory.insertItem}：输出槽在 {@link MachineItemHandler#isItemValid}
     * 里是 false（挡住漏斗和玩家往里塞），而 {@code insertItem} 会先做这个校验，
     * 于是机器自己也被挡在外面 —— 表现就是「机器一直转，但什么都不出」。
     * 内部产出必须绕开校验直接写槽位。</p>
     */
    private boolean insertOutput(ItemStack stack) {
        int start = machineKind.outputStart();
        for (int slot = start; slot < start + machineKind.outputSlots(); slot++) {
            ItemStack current = inventory.getStackInSlot(slot);
            if (current.isEmpty()) {
                inventory.setStackInSlot(slot, stack.copy());
                return true;
            }
            if (ItemStack.isSameItemSameComponents(current, stack)
                    && current.getCount() + stack.getCount() <= current.getMaxStackSize()) {
                ItemStack merged = current.copy();
                merged.grow(stack.getCount());
                inventory.setStackInSlot(slot, merged);
                return true;
            }
        }
        return false;
    }

    /**
     * 某个属性当前生效的等级：所有**直接生效**类升级的贡献之和，按实际上限截断。
     *
     * <p>进阶层（超频/量子/裂变）不直接贡献等级，只通过
     * {@link SiaoUpgrades#effectiveCap} 把上限抬上去。</p>
     */
    public int statLevel(MachineStat stat) {
        int start = machineKind.upgradeStart();
        int sum = 0;
        for (int slot = start; slot < start + MachineKind.UPGRADE_SLOTS; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.getItem() instanceof SiaoUpgradeItem upgrade) {
                UpgradeType type = upgrade.upgradeType();
                if (!type.boostsCap() && type.stat() == stat) {
                    sum += upgrade.contributedLevel(stack);
                }
            }
        }
        return Math.min(sum, SiaoUpgrades.effectiveCap(stat));
    }

    public int energyPerOperation() {
        int percent = energyPercent(statLevel(MachineStat.ENERGY));
        double scaled = machineKind.energyPerOperation() * percent / 100.0D
                * SiaoConfig.machineEnergyScale();
        return Math.max(1, (int) Math.round(scaled));
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
        boolean wanted = statLevel(MachineStat.ANCHOR) > 0;
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
        tag.putInt("sides", packedSides());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        energy.deserializeNBT(registries, tag.get("energy"));
        progress = tag.getInt("progress");
        if (tag.contains("sides")) {
            unpackSides(tag.getInt("sides"));
        }
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
        /**
         * @param capacity 缓冲上限
         * @param transfer 每 tick 最多接受的 FE
         *
         * <p>⚠️ maxExtract 传 0 —— 机器不是电源，管道和玩家不该能从中抽电。
         * 但 {@code IEnergyStorage#extractEnergy} 在 {@code canExtract()==false} 时
         * <b>永远返回 0</b>，所以机器自己也抽不出电来加工。
         * 内部耗能必须走 {@link #consume(int)} 直接改缓冲，不能走 IEnergyStorage 语义。</p>
         */
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

        private boolean hasEnergy(int amount) {
            return this.energy >= amount;
        }

        private void consume(int amount) {
            this.energy = Math.max(0, this.energy - amount);
            setChanged();
        }
    }
}
