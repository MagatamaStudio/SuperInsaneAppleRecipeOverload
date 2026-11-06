package com.siao.appleoverload.content;

import com.siao.appleoverload.machine.AppleMachineBlockEntity;
import com.siao.appleoverload.machine.SideMode;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 苹果配置器 —— Mek 式的接口配置方式。
 *
 * <p>对着机器的**某个面**右键，就切这个面的通行规则：
 * 双向 → 仅输入 → 仅输出 → 封闭 → 双向。</p>
 *
 * <p>潜行右键则走方块自己的逻辑（打开界面），和 Mek 的配置器一致。</p>
 */
public class SiaoConfiguratorItem extends Item {

    public SiaoConfiguratorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level.getBlockEntity(context.getClickedPos()) instanceof AppleMachineBlockEntity machine)) {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        // 潜行时打开界面（和 Mek 一致）。注意潜行时 vanilla 会跳过
        // block.useWithoutItem，所以这里必须自己处理
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) {
                return InteractionResult.SUCCESS;
            }
            player.openMenu(machine, context.getClickedPos());
            return InteractionResult.CONSUME;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        Direction face = context.getClickedFace();
        SideMode next = machine.cycleSide(face);
        player.displayClientMessage(Component.literal(faceName(face) + "面 → " + next.zh()), true);
        return InteractionResult.CONSUME;
    }

    private static String faceName(Direction face) {
        return switch (face) {
            case DOWN -> "下";
            case UP -> "上";
            case NORTH -> "北";
            case SOUTH -> "南";
            case WEST -> "西";
            case EAST -> "东";
        };
    }
}
