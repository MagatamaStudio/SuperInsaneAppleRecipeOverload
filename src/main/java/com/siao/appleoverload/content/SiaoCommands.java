package com.siao.appleoverload.content;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.siao.appleoverload.machine.AppleMachineBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 调试命令（需要 OP，权限等级 2）。
 *
 * <p>这个模组的机器吃 FE，但模组本身还不产电，原版和 AE2 也不产 FE ——
 * 所以测试期没有手段给机器供电。这个命令先把这个洞堵上，
 * 真正的解法是后面加一台苹果发电机。</p>
 *
 * <pre>/siao energy   —— 把准星对准的机器能量充满</pre>
 */
public final class SiaoCommands {

    private SiaoCommands() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SiaoCommands::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("siao")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("energy")
                        .executes(context -> fillEnergy(context.getSource()))));
    }

    private static int fillEnergy(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        HitResult hit = player.pick(8.0D, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)) {
            source.sendFailure(Component.literal("准星没有对准任何方块"));
            return 0;
        }
        var blockEntity = source.getLevel().getBlockEntity(blockHit.getBlockPos());
        if (!(blockEntity instanceof AppleMachineBlockEntity machine)) {
            source.sendFailure(Component.literal("对准的不是 SIAO 的机器"));
            return 0;
        }
        machine.fillEnergy();
        source.sendSuccess(() -> Component.literal("已充满能量：" + blockHit.getBlockPos().toShortString()), false);
        return 1;
    }
}
