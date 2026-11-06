package com.siao.appleoverload.machine;

import com.mojang.serialization.MapCodec;
import com.siao.appleoverload.content.SiaoConfiguratorItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.items.IItemHandler;

/** 机器方块。三台共用一个类，行为差异全在方块实体里。 */
public class AppleMachineBlock extends BaseEntityBlock {

    private final MachineKind kind;

    public AppleMachineBlock(MachineKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public MachineKind kind() {
        return kind;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(properties -> new AppleMachineBlock(kind, properties));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AppleMachineBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof AppleMachineBlockEntity machine) {
                machine.tick();
            }
        };
    }

    /** BaseEntityBlock 默认 INVISIBLE，用普通方块模型必须改回 MODEL。 */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        // ⚠️ vanilla 的调用顺序是：
        //    block.useItemOn -> block.useWithoutItem -> itemStack.useOn
        // 我们这里一旦 consume，物品的 useOn 就永远轮不到了。
        // 所以手上拿着配置器时必须让位，否则「右键配置面」会被开界面截胡。
        if (player.getMainHandItem().getItem() instanceof SiaoConfiguratorItem
                || player.getOffhandItem().getItem() instanceof SiaoConfiguratorItem) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AppleMachineBlockEntity machine) {
            player.openMenu(machine, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof AppleMachineBlockEntity machine) {
            IItemHandler handler = machine.getItemHandler();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    Block.popResource(level, pos, stack.copy());
                    handler.extractItem(slot, stack.getCount(), false);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
