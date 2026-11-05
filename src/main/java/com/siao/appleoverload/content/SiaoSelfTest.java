package com.siao.appleoverload.content;

import com.siao.appleoverload.SiaoMod;
import com.siao.appleoverload.machine.AppleMachineBlockEntity;
import com.siao.appleoverload.machine.MachineKind;
import com.siao.appleoverload.recipe.CompressionIndex;
import com.siao.appleoverload.recipe.SiaoCompressionRecipe;
import com.siao.appleoverload.upgrade.UpgradeKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.DataMapHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 服务端自检：不开游戏就能验证自定义配方的匹配 / 输出逻辑。
 *
 * <p>手工来测「9 个附魔能压成 1 个附魔、混合输入被拒绝」需要开客户端一格一格摆，
 * 而逻辑写错了在服务端日志里是<b>看不出来</b>的。所以把用例写进代码，
 * 用 {@code -Dsiao.selftest=true} 起服务端，日志里直接出 PASS/FAIL。</p>
 *
 * <p>默认关闭，不影响正常游玩。</p>
 */
public final class SiaoSelfTest {

    private static final String FLAG = "siao.selftest";

    private static int passed;
    private static int failed;

    private SiaoSelfTest() {
    }

    public static void register() {
        if (!Boolean.getBoolean(FLAG)) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(SiaoSelfTest::onServerStarted);
        SiaoMod.LOGGER.info("SIAO 自检已启用（-D{}=true）", FLAG);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        passed = 0;
        failed = 0;
        MinecraftServer server = event.getServer();
        HolderLookup.Provider registries = server.registryAccess();

        try {
            runCases(server, registries);
        } catch (RuntimeException e) {
            failed++;
            SiaoMod.LOGGER.error("[SIAO-TEST] FAIL  自检自身异常", e);
        }

        SiaoMod.LOGGER.info("[SIAO-TEST] ===== 通过 {} / 失败 {} =====", passed, failed);
        // 纯 ASCII 的机器可读结论：日志文件是平台默认编码写的，CI 里别拿中文去 grep
        SiaoMod.LOGGER.info("[SIAO-TEST] RESULT pass={} fail={}", passed, failed);
        if (failed > 0) {
            SiaoMod.LOGGER.error("[SIAO-TEST] 存在失败用例，构建不可用");
        }
        // 自检是给 CI / 命令行校验用的，跑完自动关服；加 -Dsiao.selftest.keepalive=true 可留服
        if (!Boolean.getBoolean("siao.selftest.keepalive")) {
            server.halt(false);
        }
    }

    private static void runCases(MinecraftServer server, HolderLookup.Provider registries) {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack gold = new ItemStack(Items.GOLD_INGOT);
        ItemStack unit1 = new ItemStack(SiaoItems.unit(Material.IRON, 1).get());
        ItemStack unit2 = new ItemStack(SiaoItems.unit(Material.IRON, 2).get());
        ItemStack block1 = new ItemStack(SiaoBlocks.unitBlock(Material.IRON, 1).get());

        ItemStack enchantedIron = new ItemStack(Items.IRON_INGOT);
        SiaoComponents.makeEnchanted(enchantedIron, 2);

        ItemStack enchantedUnit2 = unit2.copy();
        SiaoComponents.makeEnchanted(enchantedUnit2, 3);

        // ---- 压缩：9 → 1 -----------------------------------------------------
        SiaoCompressionRecipe compress = require(server, "siao:compress_iron_1x");

        CraftingInput nineIron = grid(fill(iron, 9));
        check("9 普通铁锭：匹配", compress.matches(nineIron, null));
        ItemStack pressed = compress.assemble(nineIron, registries);
        check("9 普通铁锭：产物 = 一重压缩铁锭", sameItem(pressed, unit1) && pressed.getCount() == 1,
                describe(pressed));
        check("9 普通铁锭：产物不附魔", !SiaoComponents.isEnchanted(pressed), describe(pressed));

        check("只放 8 个：不匹配", !compress.matches(grid(fill(iron, 8)), null));
        check("9 格塞 10 个不可能，改用 8 铁 + 1 金：不匹配",
                !compress.matches(grid(concat(fill(iron, 8), gold)), null));
        check("空合成功：不匹配", !compress.matches(grid(), null));

        // ---- 压缩：附魔穿透 ------------------------------------------------
        CraftingInput nineEnchanted = grid(fill(enchantedIron, 9));
        check("9 个附魔铁锭：匹配", compress.matches(nineEnchanted, null));
        ItemStack pressedEnchanted = compress.assemble(nineEnchanted, registries);
        check("9 个附魔铁锭：产物附魔",
                SiaoComponents.isEnchanted(pressedEnchanted), describe(pressedEnchanted));
        check("9 个附魔铁锭：附魔等级保留 2 级",
                SiaoComponents.enchantTier(pressedEnchanted) == 2, describe(pressedEnchanted));

        // ---- 附魔的表现层（名字 / 光效）------------------------------------
        ItemStack unit1Enchanted = unit1.copy();
        SiaoComponents.makeEnchanted(unit1Enchanted, 2);
        String plainName = unit1.getHoverName().getString();
        String enchantedName = unit1Enchanted.getHoverName().getString();
        // 服务端语言固定是 en_us，所以这里断言「结构」而不是某个具体词：
        // 附魔名 = 前缀 + 原名，且前缀非空
        check("附魔物品名字带「附魔」前缀",
                enchantedName.endsWith(plainName) && enchantedName.length() > plainName.length(),
                enchantedName);
        check("附魔物品出光效", unit1Enchanted.hasFoil() && !unit1.hasFoil(),
                "enchanted=" + unit1Enchanted.hasFoil() + " plain=" + unit1.hasFoil());

        // ---- 混合输入必须拒绝 ----------------------------------------------
        List<ItemStack> mixed = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = new ItemStack(Items.IRON_INGOT);
            if (i >= 5) {
                SiaoComponents.makeEnchanted(stack, 2);
            }
            mixed.add(stack);
        }
        check("5 普通 + 4 附魔：不匹配（关键用例）",
                !compress.matches(CraftingInput.of(3, 3, mixed), null));

        // ---- 拆分：附魔物品不许下泄 ----------------------------------------
        SiaoCompressionRecipe decompress = require(server, "siao:decompress_iron_2x");
        CraftingInput oneUnit2 = single(unit2);
        check("拆 1 个普通二重：匹配", decompress.matches(oneUnit2, null));
        ItemStack split = decompress.assemble(oneUnit2, registries);
        check("拆 1 个普通二重：出 9 个一重",
                sameItem(split, unit1) && split.getCount() == 9, describe(split));
        check("拆 1 个附魔二重：不匹配（堵复制漏洞）",
                !decompress.matches(single(enchantedUnit2), null));

        // ---- 装块 / 拆块 ----------------------------------------------------
        SiaoCompressionRecipe pack = require(server, "siao:pack_iron_1x_block");
        ItemStack packed = pack.assemble(grid(fill(unit1, 9)), registries);
        check("9 个一重 → 1 个一重块", sameItem(packed, block1) && packed.getCount() == 1,
                describe(packed));

        SiaoCompressionRecipe unpack = require(server, "siao:unpack_iron_1x_block");
        ItemStack unpacked = unpack.assemble(single(block1), registries);
        check("1 个一重块 → 9 个一重", sameItem(unpacked, unit1) && unpacked.getCount() == 9,
                describe(unpacked));

        // ---- 全量配方装载检查 ----------------------------------------------
        // 540 = 压缩链（12 材料 + 3 粗形态）× 9 重 × 4 类；另外 2 条是奇点与终极木棍
        long loaded = server.getRecipeManager().getRecipes().stream()
                .filter(holder -> holder.value() instanceof SiaoCompressionRecipe)
                .count();
        check("自定义压缩配方装载数 = 542", loaded == 542L, loaded + " 条");

        machineCases(server);
        phase6Cases(server, registries);
        copperCases();
    }

    /** 阶段 5（第一刀）：铜专线整块变种 + 氧化/涂蜡 data map。 */
    private static void copperCases() {
        Block plainCut = SiaoBlocks.copperVariant(CopperVariant.Stage.UNAFFECTED, CopperVariant.Shape.CUT, false).get();
        Block exposedCut = SiaoBlocks.copperVariant(CopperVariant.Stage.EXPOSED, CopperVariant.Shape.CUT, false).get();
        Block waxedCut = SiaoBlocks.copperVariant(CopperVariant.Stage.UNAFFECTED, CopperVariant.Shape.CUT, true).get();
        Block base = SiaoBlocks.copperVariant(CopperVariant.Stage.UNAFFECTED, CopperVariant.Shape.BASE, false).get();
        Block exposedBase = SiaoBlocks.copperVariant(CopperVariant.Stage.EXPOSED, CopperVariant.Shape.BASE, false).get();

        check("铜：注册了 39 个整块变种", SiaoBlocks.copperVariantCount() == 39,
                SiaoBlocks.copperVariantCount() + " 个");
        check("铜：未氧化基础格复用一重压缩铜块",
                base == SiaoBlocks.unitBlock(Material.COPPER, 1).get(), String.valueOf(base));
        check("铜：oxidizables 表生效（基础 -> 斑驳）",
                DataMapHooks.getNextOxidizedStage(base) == exposedBase,
                String.valueOf(DataMapHooks.getNextOxidizedStage(base)));
        check("铜：oxidizables 表生效（切制 -> 斑驳切制）",
                DataMapHooks.getNextOxidizedStage(plainCut) == exposedCut,
                String.valueOf(DataMapHooks.getNextOxidizedStage(plainCut)));
        check("铜：waxables 表生效（切制 -> 涂蜡切制）",
                DataMapHooks.getBlockWaxed(plainCut) == waxedCut,
                String.valueOf(DataMapHooks.getBlockWaxed(plainCut)));
    }

    /** 阶段 6：奇点 / 混沌苹果 / 终极木棍。 */
    private static void phase6Cases(MinecraftServer server, HolderLookup.Provider registries) {
        ItemStack tier9Apple = new ItemStack(SiaoItems.unit(Material.APPLE, 9).get());
        ItemStack singularity = new ItemStack(SiaoItems.singularity().get());
        ItemStack tier1Apple = new ItemStack(SiaoItems.unit(Material.APPLE, 1).get());

        // 奇点：九重压缩苹果锭 ×1
        SiaoCompressionRecipe singularityRecipe = require(server, "siao:craft_singularity");
        check("奇点：九重压缩苹果锭 → 绝对苹果奇点",
                singularityRecipe.matches(single(tier9Apple), null), "不匹配");
        ItemStack produced = singularityRecipe.assemble(single(tier9Apple), registries);
        check("奇点：产物 = siao:singularity", sameItem(produced, singularity), describe(produced));

        // 终极木棍：必须附魔，产物是木棍且不带附魔
        SiaoCompressionRecipe ultimate = require(server, "siao:ultimate_stick");
        ItemStack enchantedApple = tier1Apple.copy();
        SiaoComponents.makeEnchanted(enchantedApple, 3);
        check("终极木棍：普通苹果锭不匹配", !ultimate.matches(single(tier1Apple), null), "");
        check("终极木棍：附魔苹果锭匹配", ultimate.matches(single(enchantedApple), null), "不匹配");
        ItemStack stick = ultimate.assemble(single(enchantedApple), registries);
        check("终极木棍：产物是 1 根木棍",
                sameItem(stick, new ItemStack(Items.STICK)) && stick.getCount() == 1, describe(stick));
        check("终极木棍：木棍不带附魔", !SiaoComponents.isEnchanted(stick), describe(stick));

        // 混沌苹果：普通 shaped 配方，产物固定
        check("混沌苹果：配方已装载",
                server.getRecipeManager().byKey(ResourceLocation.parse("siao:craft_chaos_apple")).isPresent(),
                "找不到 craft_chaos_apple");
    }

    /**
     * 真世界里放机器、喂料、跑 tick、验产物。
     *
     * <p>这一步不是走 mock —— 方块实体、物品栏、能量、配方查询全部是真家伙，
     * 唯一省掉的是客户端渲染。</p>
     */
    private static void machineCases(MinecraftServer server) {
        ItemStack unit1 = new ItemStack(SiaoItems.unit(Material.IRON, 1).get());
        ItemStack unit2 = new ItemStack(SiaoItems.unit(Material.IRON, 2).get());
        ItemStack shard = new ItemStack(SiaoItems.singularityShard().get());

        check("压缩索引覆盖 135 种可压缩输入",
                CompressionIndex.size(server.getRecipeManager()) == 135,
                CompressionIndex.size(server.getRecipeManager()) + " 种");

        // 压缩机：40 tick 一次，200 FE
        AppleMachineBlockEntity compressor = place(server, MachineKind.COMPRESSOR, new BlockPos(0, 200, 0));
        for (int slot = 0; slot < 9; slot++) {
            compressor.getItemHandler().insertItem(slot, new ItemStack(Items.IRON_INGOT), false);
        }
        fillEnergy(compressor);
        int energyBefore = compressor.energyStored();
        for (int tick = 0; tick < 60; tick++) {
            compressor.tick();
        }
        ItemStack pressed = compressor.getItemHandler().getStackInSlot(compressor.machineKind().outputStart());
        check("压缩机：9 铁锭 → 1 个一重压缩铁锭",
                sameItem(pressed, unit1) && pressed.getCount() == 1, describe(pressed));
        check("压缩机：9 个输入格被吃干净",
                compressor.getItemHandler().getStackInSlot(0).isEmpty(),
                compressor.getItemHandler().getStackInSlot(0) + "");
        check("压缩机：扣了 200 FE",
                compressor.energyStored() == energyBefore - 200,
                "before=" + energyBefore + " after=" + compressor.energyStored());

        // 速度升级：等级 1 → 40 tick 变 20 tick
        AppleMachineBlockEntity fast = place(server, MachineKind.COMPRESSOR, new BlockPos(2, 200, 0));
        for (int slot = 0; slot < 9; slot++) {
            fast.getItemHandler().insertItem(slot, new ItemStack(Items.IRON_INGOT), false);
        }
        fast.getItemHandler().insertItem(fast.machineKind().upgradeStart(),
                new ItemStack(SiaoItems.upgrade(UpgradeKind.SPEED).get()), false);
        fillEnergy(fast);
        for (int tick = 0; tick < 25; tick++) {
            fast.tick();
        }
        check("速度升级：25 tick 内就出料",
                !fast.getItemHandler().getStackInSlot(fast.machineKind().outputStart()).isEmpty(),
                fast.progress() + "/" + fast.maxProgress());

        // 能量升级：等级 1 → 单次 200 → 160 FE
        AppleMachineBlockEntity eco = place(server, MachineKind.COMPRESSOR, new BlockPos(4, 200, 0));
        eco.getItemHandler().insertItem(eco.machineKind().upgradeStart(),
                new ItemStack(SiaoItems.upgrade(UpgradeKind.ENERGY).get()), false);
        check("能量升级：单次耗能 200 → 160", eco.energyPerOperation() == 160,
                eco.energyPerOperation() + " FE");

        // 附魔台
        AppleMachineBlockEntity enchanter = place(server, MachineKind.ENCHANTER, new BlockPos(6, 200, 0));
        enchanter.getItemHandler().insertItem(0, unit1.copy(), false);
        fillEnergy(enchanter);
        for (int tick = 0; tick < 80; tick++) {
            enchanter.tick();
        }
        ItemStack enchanted = enchanter.getItemHandler().getStackInSlot(enchanter.machineKind().outputStart());
        check("附魔台：产出附魔一重压缩铁锭", SiaoComponents.isEnchanted(enchanted), describe(enchanted));
        check("附魔台：附魔强度 = 1", SiaoComponents.enchantTier(enchanted) == 1, describe(enchanted));

        // 离心机：附魔 3 级 → 3 个奇点碎片
        AppleMachineBlockEntity centrifuge = place(server, MachineKind.CENTRIFUGE, new BlockPos(8, 200, 0));
        ItemStack enchantedUnit2 = unit2.copy();
        SiaoComponents.makeEnchanted(enchantedUnit2, 3);
        centrifuge.getItemHandler().insertItem(0, enchantedUnit2, false);
        fillEnergy(centrifuge);
        for (int tick = 0; tick < 100; tick++) {
            centrifuge.tick();
        }
        ItemStack shards = centrifuge.getItemHandler().getStackInSlot(centrifuge.machineKind().outputStart());
        check("离心机：附魔 3 级 → 3 个奇点碎片",
                sameItem(shards, shard) && shards.getCount() == 3, describe(shards));

        AppleMachineBlockEntity idle = place(server, MachineKind.CENTRIFUGE, new BlockPos(10, 200, 0));
        idle.getItemHandler().insertItem(0, unit1.copy(), false);
        fillEnergy(idle);
        for (int tick = 0; tick < 200; tick++) {
            idle.tick();
        }
        check("离心机：没附魔不加工",
                idle.getItemHandler().getStackInSlot(idle.machineKind().outputStart()).isEmpty(),
                describe(idle.getItemHandler().getStackInSlot(idle.machineKind().outputStart())));

        // 没电不动
        AppleMachineBlockEntity unpowered = place(server, MachineKind.COMPRESSOR, new BlockPos(12, 200, 0));
        for (int slot = 0; slot < 9; slot++) {
            unpowered.getItemHandler().insertItem(slot, new ItemStack(Items.IRON_INGOT), false);
        }
        for (int tick = 0; tick < 120; tick++) {
            unpowered.tick();
        }
        check("没电不加工",
                unpowered.getItemHandler().getStackInSlot(unpowered.machineKind().outputStart()).isEmpty(),
                unpowered.energyStored() + " FE");
    }

    private static AppleMachineBlockEntity place(MinecraftServer server, MachineKind kind, BlockPos pos) {
        ServerLevel level = server.overworld();
        // 先清空再放：老存档里同一位置可能留着上次自检的机器实体
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos, SiaoBlocks.machine(kind).get().defaultBlockState());
        if (level.getBlockEntity(pos) instanceof AppleMachineBlockEntity machine) {
            return machine;
        }
        throw new IllegalStateException("机器没放上：" + pos);
    }

    /** 充满能量。单次 receiveEnergy 受 maxReceive 限制，调用一次只能灌 4000，所以要循环。 */
    private static void fillEnergy(AppleMachineBlockEntity machine) {
        int capacity = machine.getEnergyStorage().getMaxEnergyStored();
        for (int guard = 0; guard < 1000 && machine.energyStored() < capacity; guard++) {
            machine.getEnergyStorage().receiveEnergy(capacity, false);
        }
    }

    // ---- 工具 ---------------------------------------------------------------

    private static SiaoCompressionRecipe require(MinecraftServer server, String id) {
        Optional<RecipeHolder<?>> holder = server.getRecipeManager().byKey(ResourceLocation.parse(id));
        if (holder.isEmpty()) {
            throw new IllegalStateException("找不到配方 " + id);
        }
        if (!(holder.get().value() instanceof SiaoCompressionRecipe recipe)) {
            throw new IllegalStateException("配方 " + id + " 类型不对："
                    + holder.get().value().getClass());
        }
        return recipe;
    }

    private static CraftingInput grid(ItemStack... stacks) {
        List<ItemStack> slots = new ArrayList<>(SiaoCompressionRecipe.MAX_COUNT);
        for (int i = 0; i < SiaoCompressionRecipe.MAX_COUNT; i++) {
            slots.add(i < stacks.length ? stacks[i] : ItemStack.EMPTY);
        }
        return CraftingInput.of(3, 3, slots);
    }

    private static CraftingInput single(ItemStack stack) {
        return CraftingInput.of(1, 1, List.of(stack));
    }

    private static ItemStack[] fill(ItemStack stack, int amount) {
        ItemStack[] array = new ItemStack[amount];
        for (int i = 0; i < amount; i++) {
            array[i] = stack.copy();
        }
        return array;
    }

    private static ItemStack[] concat(ItemStack[] head, ItemStack tail) {
        ItemStack[] array = new ItemStack[head.length + 1];
        System.arraycopy(head, 0, array, 0, head.length);
        array[head.length] = tail;
        return array;
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return ItemStack.isSameItem(a, b);
    }

    private static String describe(ItemStack stack) {
        return stack.getCount() + "× " + stack.getItem()
                + (SiaoComponents.isEnchanted(stack)
                        ? " [enchanted " + SiaoComponents.enchantTier(stack) + "]"
                        : "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            SiaoMod.LOGGER.info("[SIAO-TEST] PASS  {}", name);
        } else {
            failed++;
            SiaoMod.LOGGER.error("[SIAO-TEST] FAIL  {}   -> {}", name, detail);
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }
}
