package com.siao.appleoverload;

import com.siao.appleoverload.content.CompressionTooltips;
import com.siao.appleoverload.content.SiaoBlocks;
import com.siao.appleoverload.content.SiaoComponents;
import com.siao.appleoverload.content.SiaoCommands;
import com.siao.appleoverload.content.SiaoCreativeTabs;
import com.siao.appleoverload.content.SiaoItems;
import com.siao.appleoverload.machine.MachineKind;
import com.siao.appleoverload.machine.SiaoMachineRegistry;
import com.siao.appleoverload.client.SiaoClientEvents;
import com.siao.appleoverload.config.SiaoConfig;
import com.siao.appleoverload.recipe.SiaoRecipeTypes;
import com.siao.appleoverload.recipe.SiaoRecipes;
import com.siao.appleoverload.upgrade.SiaoUpgrades;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
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
    /** 日志前缀用模组名（仓库名），别和 modid 混在一起。 */
    public static final Logger LOGGER = LoggerFactory.getLogger("SuperInsaneAppleRecipeOverload");

    public SiaoMod(IEventBus modBus, ModContainer container) {
        // 先触发方块注册（它会把同名 BlockItem 注入 SiaoItems）
        SiaoBlocks.BLOCKS.register(modBus);
        SiaoItems.ITEMS.register(modBus);
        SiaoCreativeTabs.TABS.register(modBus);

        SiaoComponents.DATA_COMPONENTS.register(modBus);
        SiaoRecipes.SERIALIZERS.register(modBus);
        SiaoRecipeTypes.TYPES.register(modBus);
        SiaoMachineRegistry.BLOCK_ENTITIES.register(modBus);
        SiaoMachineRegistry.MENUS.register(modBus);

        // 升级类型是自建注册表：先建表（NewRegistryEvent），再注册条目
        modBus.addListener(SiaoUpgrades::onNewRegistry);
        SiaoUpgrades.TYPES.register(modBus);

        container.registerConfig(ModConfig.Type.COMMON, SiaoConfig.SPEC);

        modBus.addListener(SiaoCommonEvents::registerCapabilities);

        // 客户端类只在客户端分支里加载，专用服务器不会碰到
        if (FMLEnvironment.dist == Dist.CLIENT) {
            SiaoClientEvents.register(modBus);
        }

        CompressionTooltips.register();
        SiaoCommands.register();
        // 用反射触发自检：生产 jar 里会把 SiaoSelfTest 整个排掉（见 build.gradle 的 jar 配置），
        // 直接静态引用会导致 NoClassDefFoundError
        try {
            Class.forName("com.siao.appleoverload.content.SiaoSelfTest")
                    .getMethod("register").invoke(null);
        } catch (ClassNotFoundException notInProductionJar) {
            LOGGER.debug("自检类未打包，跳过");
        } catch (ReflectiveOperationException reflectiveFailure) {
            LOGGER.warn("自检注册失败", reflectiveFailure);
        }

        LOGGER.info("SIAO 已装填：{} 压缩单位 / {} 粗形态 / {} 方块 / {} 数据组件 / {} 配方类型 / {} 机器",
                SiaoItems.unitCount(), SiaoItems.rawUnitCount(), SiaoBlocks.blockCount(),
                SiaoComponents.DATA_COMPONENTS.getEntries().size(),
                SiaoRecipes.SERIALIZERS.getEntries().size(),
                MachineKind.values().length);
    }
}
