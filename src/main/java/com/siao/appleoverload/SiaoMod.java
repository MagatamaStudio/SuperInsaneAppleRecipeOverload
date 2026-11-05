package com.siao.appleoverload;

import com.siao.appleoverload.content.CompressionTooltips;
import com.siao.appleoverload.content.SiaoBlocks;
import com.siao.appleoverload.content.SiaoComponents;
import com.siao.appleoverload.content.SiaoCreativeTabs;
import com.siao.appleoverload.content.SiaoItems;
import com.siao.appleoverload.content.SiaoSelfTest;
import com.siao.appleoverload.machine.MachineKind;
import com.siao.appleoverload.machine.SiaoMachineRegistry;
import com.siao.appleoverload.client.SiaoClientEvents;
import com.siao.appleoverload.recipe.SiaoRecipes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SuperInsaneAppleRecipeOverload
 *
 * <p>核心玩法：一切皆可压缩。九个苹果压成一个，一直压到九重，
 * 再拿去换一根木棍。</p>
 */
@Mod(SiaoMod.MOD_ID)
public final class SiaoMod {
    public static final String MOD_ID = "siao";
    public static final Logger LOGGER = LoggerFactory.getLogger("SIAO");

    public SiaoMod(IEventBus modBus, ModContainer container) {
        // 先触发方块注册（它会把同名 BlockItem 注入 SiaoItems）
        SiaoBlocks.BLOCKS.register(modBus);
        SiaoItems.ITEMS.register(modBus);
        SiaoCreativeTabs.TABS.register(modBus);

        SiaoComponents.DATA_COMPONENTS.register(modBus);
        SiaoRecipes.SERIALIZERS.register(modBus);
        SiaoMachineRegistry.BLOCK_ENTITIES.register(modBus);
        SiaoMachineRegistry.MENUS.register(modBus);

        modBus.addListener(SiaoCommonEvents::registerCapabilities);

        // 客户端类只在客户端分支里加载，专用服务器不会碰到
        if (FMLEnvironment.dist == Dist.CLIENT) {
            SiaoClientEvents.register(modBus);
        }

        CompressionTooltips.register();
        SiaoSelfTest.register();

        LOGGER.info("SIAO 已装填：{} 压缩单位 / {} 粗形态 / {} 方块 / {} 数据组件 / {} 配方类型 / {} 机器",
                SiaoItems.unitCount(), SiaoItems.rawUnitCount(), SiaoBlocks.blockCount(),
                SiaoComponents.DATA_COMPONENTS.getEntries().size(),
                SiaoRecipes.SERIALIZERS.getEntries().size(),
                MachineKind.values().length);
    }
}
