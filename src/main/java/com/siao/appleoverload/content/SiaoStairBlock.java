package com.siao.appleoverload.content;

import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 楼梯方块。
 *
 * <p>{@link StairBlock} 的构造函数是 {@code protected}，跨包无法直接 new，
 * 所以套一层。没有别的逻辑 —— 楼梯的朝向/内外角全部由
 * {@code StairBlock} 的状态机自动处理。</p>
 */
public class SiaoStairBlock extends StairBlock {

    public SiaoStairBlock(BlockState baseState, BlockBehaviour.Properties properties) {
        super(baseState, properties);
    }
}
