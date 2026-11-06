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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import com.siao.appleoverload.machine.SiaoMachineRegistry;

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
