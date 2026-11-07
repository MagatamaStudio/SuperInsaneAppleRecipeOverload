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
 * <p>蹲不蹲都一样，永远是正向循环；想打开界面就空手右键。</p>
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
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // 蹲不蹲都做同一件事：正向循环。
        // 之前蹲下是反向循环，结果同一个面同一个物品有两种结果，玩家没法预期。
        Direction face = context.getClickedFace();
        SideMode before = machine.sideMode(face);
        SideMode after = machine.cycleSide(face);
        // 提示带上「从什么变成什么」，比只报新状态好确认
        player.displayClientMessage(Component.literal(
                faceName(face) + "面：" + before.zh() + " → " + after.zh()), true);
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
