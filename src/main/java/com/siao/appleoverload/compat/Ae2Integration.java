package com.siao.appleoverload.compat;

import appeng.api.AECapabilities;
import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.siao.appleoverload.machine.AppleMachineBlockEntity;
import com.siao.appleoverload.machine.MachineKind;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import com.siao.appleoverload.machine.SiaoMachineRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * AE2 对接：把苹果压缩机暴露成 {@link ICraftingMachine}。
 *
 * <p><b>整个文件只在装了 AE2 时才会被类加载</b>（见 {@code SiaoCommonEvents} 的
 * {@code ModList.get().isLoaded("ae2")} 判断）。所以 AE2 是编译期依赖、不是运行期依赖，
 * 没装 AE2 的整合包照样能跑。</p>
 *
 * <p>为什么值得做：样板供应器推料是**三级优先**，{@code CRAFTING_MACHINE} 排第一。
 * 有它的话 AE2 会把整批料一次推过来（全有或全无），比逐格塞 64 个高效得多，
 * 也不会出现「推了一半被拒」的尴尬。</p>
 */
public final class Ae2Integration {

    private Ae2Integration() {
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.CRAFTING_MACHINE, SiaoMachineRegistry.MACHINE.get(),
                (machine, side) -> machine.machineKind() == MachineKind.COMPRESSOR
                        ? new Ae2CraftingMachine(machine)
                        : null);

        // 档位 2：long 语义的存储门面。
        // ⚠️ 注册它之后 AE2 就不再走 IItemHandler 了，所以这一层必须完全正确：
        //    SIMULATE 必须准确且不改状态，否则会吞料。
        event.registerBlockEntity(AECapabilities.ME_STORAGE, SiaoMachineRegistry.MACHINE.get(),
                Ae2Storage::new);
        // 档位 3：ME 直连。机器自己就是网格节点，用线缆连上就有电有网，
        // 不需要额外的 ME 接口方块。
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST,
                SiaoMachineRegistry.MACHINE.get(),
                (machine, context) -> Ae2GridHost.of(machine));
    }

    /**
     * 给自检用的验证入口。
     *
     * <p>由 {@code SiaoSelfTest} 通过**反射**调用 —— 这样自检本身不硬链接 AE2 的类，
     * 没装 AE2 的 CI 环境照样能编译和运行。</p>
     *
     * @return null 表示通过，否则返回失败原因
     */
    public static String verify(Level level, BlockPos pos) {
        ICraftingMachine capability = level.getCapability(AECapabilities.CRAFTING_MACHINE, pos, Direction.UP);
        if (capability == null) {
            return "该位置没有 ICraftingMachine 能力";
        }
        if (!capability.acceptsPlans()) {
            return "acceptsPlans() 返回 false";
        }
        PatternContainerGroup info = capability.getCraftingMachineInfo();
        if (info == null || info.name() == null) {
            return "getCraftingMachineInfo() 为空";
        }
        return null;
    }

    /**
     * 档位 2 的验证入口：ME_STORAGE 的 SIMULATE 必须准确且不改状态。
     *
     * @return null 表示通过
     */
    public static String verifyStorage(Level level, BlockPos pos) {
        MEStorage storage = level.getCapability(AECapabilities.ME_STORAGE, pos, Direction.UP);
        if (storage == null) {
            return "该位置没有 ME_STORAGE 能力";
        }
        if (storage.getDescription() == null) {
            return "getDescription() 为空";
        }

        IActionSource source = IActionSource.empty();
        AEItemKey iron = AEItemKey.of(new ItemStack(Items.IRON_INGOT));
        if (iron == null) {
            return "AEItemKey.of 返回 null";
        }

        long simulated = storage.insert(iron, 9, Actionable.SIMULATE, source);
        if (simulated != 9) {
            return "SIMULATE 应能插入 9，实际 " + simulated;
        }
        if (!storage.getAvailableStacks().isEmpty()) {
            return "SIMULATE 之后内容非空 —— 模拟改动了状态，这就是吞料的根源";
        }

        long inserted = storage.insert(iron, 9, Actionable.MODULATE, source);
        if (inserted != 9) {
            return "MODULATE 只插入了 " + inserted;
        }

        long overflow = storage.insert(iron, 100_000, Actionable.MODULATE, source);
        long remainingSpace = 9L * 64L - 9L;
        if (overflow > remainingSpace) {
            return "超容量插入返回 " + overflow + "，超过实际剩余空间 " + remainingSpace + " —— 会谎报容量";
        }

        long extractedWhenEmpty = storage.extract(iron, 1, Actionable.MODULATE, source);
        if (extractedWhenEmpty != 0) {
            return "输出槽为空却能抽出 " + extractedWhenEmpty;
        }
        return null;
    }

    /**
     * long 语义的存储门面。
     *
     * <p>插入走 {@code getItemHandlerFor(side)}（受侧配置约束、且输出槽 {@code isItemValid=false}
     * 天然拒绝）；取出只走**输出槽**，免得 AE2 把排队待加工的原料抽走。</p>
     */
    private static final class Ae2Storage implements MEStorage {

        private final AppleMachineBlockEntity machine;
        @Nullable
        private final Direction side;

        private Ae2Storage(AppleMachineBlockEntity machine, @Nullable Direction side) {
            this.machine = machine;
            this.side = side;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            if (!(what instanceof AEItemKey itemKey) || amount <= 0) {
                return 0;
            }
            IItemHandler handler = machine.getItemHandlerFor(side);
            int requested = (int) Math.min(amount, Integer.MAX_VALUE);
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(
                    handler, itemKey.toStack(requested), mode.isSimulate());
            return requested - leftover.getCount();
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            if (amount <= 0) {
                return 0;
            }
            IItemHandler handler = machine.getOutputHandlerFor(side);
            long extracted = 0;
            for (int slot = 0; slot < handler.getSlots() && extracted < amount; slot++) {
                ItemStack current = handler.getStackInSlot(slot);
                if (current.isEmpty() || !AEItemKey.of(current).equals(what)) {
                    continue;
                }
                int want = (int) Math.min(amount - extracted, Integer.MAX_VALUE);
                extracted += handler.extractItem(slot, want, mode.isSimulate()).getCount();
            }
            return extracted;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            IItemHandler handler = machine.getOutputHandlerFor(side);
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    out.add(AEItemKey.of(stack), stack.getCount());
                }
            }
        }

        @Override
        public Component getDescription() {
            return machine.getDisplayName();
        }
    }

    /** 真正的实现。 */
    private static final class Ae2CraftingMachine implements ICraftingMachine {

        private final AppleMachineBlockEntity machine;

        private Ae2CraftingMachine(AppleMachineBlockEntity machine) {
            this.machine = machine;
        }

        @Override
        public PatternContainerGroup getCraftingMachineInfo() {
            return new PatternContainerGroup(
                    AEItemKey.of(new ItemStack(machine.getBlockState().getBlock())),
                    machine.getDisplayName(),
                    List.of());
        }

        @Override
        public boolean acceptsPlans() {
            return machine.machineKind() == MachineKind.COMPRESSOR;
        }

        /**
         * 把整批料一次塞进输入槽，全有或全无。
         *
         * <p>只接受「单一物品」的样板 —— 压缩链本来就是单一材料，
         * 多输入的样板我们这台机器也处理不了，直接拒绝让 AE2 走别的目标。</p>
         */
        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputs,
                                   Direction ejectionDirection) {
            if (inputs == null || inputs.length != 1) {
                return false;
            }
            ItemStack batch = null;
            for (Object2LongMap.Entry<AEKey> entry : inputs[0]) {
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return false;
                }
                if (batch != null) {
                    // 出现了第二种物品：不是压缩链的样板
                    return false;
                }
                if (amount > Integer.MAX_VALUE) {
                    return false;
                }
                batch = itemKey.toStack((int) amount);
            }
            return batch != null && machine.acceptCraftingBatch(batch);
        }
    }
}
