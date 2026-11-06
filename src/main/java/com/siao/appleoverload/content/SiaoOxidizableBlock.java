package com.siao.appleoverload.content;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 会自然氧化的压缩方块。
 *
 * <p>⚠️ 之前那 55 个铜变种是**普通 Block**，只挂了 {@code oxidizables} /
 * {@code waxables} 数据映射 —— 数据映射只让"斧头刮、蜜脾涂"生效，
 * <b>随机刻氧化根本不会发生</b>。所以"4 个氧化阶"当时只是四个贴图不同的方块。</p>
 *
 * <p>实现 {@link WeatheringCopper} 之后，{@code ChangeOverTimeBlock} 的默认逻辑
 * 会按随机刻调用 {@code WeatheringCopper.getNext()}，而它读的正是 NeoForge 的
 * {@code oxidizables} 数据映射 —— 也就是我们生成的那张表。</p>
 */
public class SiaoOxidizableBlock extends Block implements WeatheringCopper {

    private final WeatherState age;

    public SiaoOxidizableBlock(WeatherState age, Properties properties) {
        super(properties);
        this.age = age;
    }

    @Override
    public WeatherState getAge() {
        return age;
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        this.changeOverTime(state, level, pos, random);
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return WeatheringCopper.getNext(state.getBlock()).isPresent();
    }
}
