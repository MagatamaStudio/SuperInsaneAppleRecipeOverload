package com.siao.appleoverload.compat;

import appeng.api.AECapabilities;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import com.siao.appleoverload.content.SiaoBlocks;
import com.siao.appleoverload.machine.AppleMachineBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 机器的 ME 直连：让机器自己成为 AE2 网格上的一个节点。
 *
 * <p><b>为什么需要这个</b>：之前机器只挂了 {@code ME_STORAGE} 能力，必须靠
 * 外部 ME 接口或总线接到网络里。挂上 {@code IN_WORLD_GRID_NODE_HOST} 之后，
 * 机器用线缆直接连就行，和 AE2 自己的机器一样。</p>
 *
 * <p><b>生命周期</b>：节点在机器第一次 tick 时创建（那时 level/pos 一定可用），
 * 在方块实体移除时销毁。用 {@link WeakHashMap} 挂状态，方块实体被回收时
 * 不会泄漏。</p>
 */
public final class Ae2GridHost implements IInWorldGridNodeHost {

    /** 空载功耗：每台机器 0.5 AE/t，比接口便宜、比线缆贵。 */
    private static final double IDLE_POWER = 0.5D;

    private static final Map<AppleMachineBlockEntity, Ae2GridHost> HOSTS = new WeakHashMap<>();

    private static final IGridNodeListener<AppleMachineBlockEntity> LISTENER =
            new IGridNodeListener<>() {
                @Override
                public void onSaveChanges(AppleMachineBlockEntity machine, IGridNode node) {
                    machine.setChanged();
                }

                @Override
                public void onStateChanged(AppleMachineBlockEntity machine, IGridNode node,
                                           State state) {
                    machine.setChanged();
                }
            };

    private final AppleMachineBlockEntity machine;
    private IManagedGridNode managedNode;

    private Ae2GridHost(AppleMachineBlockEntity machine) {
        this.machine = machine;
    }

    // ---- 由 Ae2Compat 调用的生命周期 -----------------------------------------

    public static void tick(AppleMachineBlockEntity machine) {
        HOSTS.computeIfAbsent(machine, Ae2GridHost::new).ensureNode();
    }

    public static void removed(AppleMachineBlockEntity machine) {
        Ae2GridHost host = HOSTS.remove(machine);
        if (host != null) {
            host.destroy();
        }
    }

    /** 给能力注册用：取（或建立）这台机器的宿主。 */
    public static Ae2GridHost of(AppleMachineBlockEntity machine) {
        return HOSTS.computeIfAbsent(machine, Ae2GridHost::new);
    }

    private void ensureNode() {
        if (managedNode != null || machine.getLevel() == null) {
            return;
        }
        managedNode = GridHelper.createManagedNode(machine, LISTENER)
                .setInWorldNode(true)
                .setTagName("siao_machine")
                .setVisualRepresentation(new ItemStack(SiaoBlocks.machine(machine.machineKind()).get()))
                .setExposedOnSides(EnumSet.allOf(Direction.class))
                .setIdlePowerUsage(IDLE_POWER);
        managedNode.create(machine.getLevel(), machine.getBlockPos());
    }

    private void destroy() {
        if (managedNode != null) {
            managedNode.destroy();
            managedNode = null;
        }
    }

    @Override
    public IGridNode getGridNode(Direction direction) {
        ensureNode();
        return managedNode == null ? null : managedNode.getNode();
    }

    /** 能力注册里用的空判断：AE2 没装时根本不会走到这里。 */
    public static boolean available() {
        return true;
    }

    static {
        // 保证 AECapabilities 已被 AE2 初始化
        if (AECapabilities.IN_WORLD_GRID_NODE_HOST == null) {
            throw new IllegalStateException("AE2 capability 未初始化");
        }
    }
}
