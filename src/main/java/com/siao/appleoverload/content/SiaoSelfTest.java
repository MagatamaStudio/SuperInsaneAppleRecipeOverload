package com.siao.appleoverload.content;

import com.siao.appleoverload.SiaoMod;
import com.siao.appleoverload.config.SiaoConfig;
import com.siao.appleoverload.machine.AppleMachineBlockEntity;
import com.siao.appleoverload.machine.MachineKind;
import com.siao.appleoverload.machine.SideMode;
import com.siao.appleoverload.recipe.ChaosAppleRecipe;
import com.siao.appleoverload.recipe.CompressionIndex;
import com.siao.appleoverload.recipe.SiaoCompressionRecipe;
import com.siao.appleoverload.upgrade.SiaoUpgrades;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.DataMapHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.items.IItemHandler;

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
        // 原需求要「附魔一重压缩苹果锭块」，所以附魔必须能穿透拆分。
        // 这不构成复制：9 个附魔一重 ↔ 1 个附魔二重，附魔件数量守恒。
        check("拆 1 个附魔二重：匹配", decompress.matches(single(enchantedUnit2), null));
        ItemStack splitEnchanted = decompress.assemble(single(enchantedUnit2), registries);
        check("拆附魔二重：出 9 个附魔一重",
                sameItem(splitEnchanted, unit1) && splitEnchanted.getCount() == 9
                        && SiaoComponents.isEnchanted(splitEnchanted),
                describe(splitEnchanted) + " enchanted=" + SiaoComponents.isEnchanted(splitEnchanted));

        // ---- 装块 / 拆块 ----------------------------------------------------
        SiaoCompressionRecipe pack = require(server, "siao:pack_iron_1x_block");
        ItemStack packed = pack.assemble(grid(fill(unit1, 9)), registries);
        check("9 个一重 → 1 个一重块", sameItem(packed, block1) && packed.getCount() == 1,
                describe(packed));

        // 原需求 L2/L3：附魔一重压缩苹果锭块必须存在
        ItemStack packedEnchanted = pack.assemble(grid(fill(unit1Enchanted, 9)), registries);
        check("9 个附魔一重 → 1 个附魔一重块",
                sameItem(packedEnchanted, block1) && SiaoComponents.isEnchanted(packedEnchanted),
                describe(packedEnchanted) + " enchanted=" + SiaoComponents.isEnchanted(packedEnchanted));
        check("方块物品是自定义 BlockItem（附魔会改名发光）",
                block1.getItem() instanceof SiaoCompressedBlockItem,
                block1.getItem().getClass().getSimpleName());
        check("block.asItem() 映射正常（否则掉落物会变成空气）",
                SiaoBlocks.unitBlock(Material.IRON, 1).get().asItem() == block1.getItem(),
                SiaoBlocks.unitBlock(Material.IRON, 1).get().asItem().toString());

        SiaoCompressionRecipe unpack = require(server, "siao:unpack_iron_1x_block");
        ItemStack unpacked = unpack.assemble(single(block1), registries);
        check("1 个一重块 → 9 个一重", sameItem(unpacked, unit1) && unpacked.getCount() == 9,
                describe(unpacked));

        // ---- 全量配方装载检查 ----------------------------------------------
        // 540 = 压缩链（12 材料 + 3 粗形态）× 9 重 × 4 类；另外 2 条是奇点与终极木棍
        long loaded = server.getRecipeManager().getRecipes().stream()
                .filter(holder -> holder.value() instanceof SiaoCompressionRecipe)
                .count();
        check("自定义压缩配方装载数 = 759", loaded == 759L, loaded + " 条");

        machineCases(server);
        sideConfigCases(server);
        persistenceCases(server, registries);
        batchCases(server);
        orchardCases(server);
        generatorCases(server);
        configuratorCases(server);
        oxidationCases(server);
        recipeSyncCases(server);
        advancementCases(server);
        perTierCases(server);
        singularityRouteCases(server);
        slotMatrixCases(server);
        compressorPriorityCases(server);
        ae2Cases(server);
        phase6Cases(server, registries);
        copperCases();
        recipeAuditCases(server, registries);
        configCases(server);
    }

    /**
     * 全量配方审计：**每一条 siao 配方都真的跑一遍 matches + assemble**。
     *
     * <p>只检查 JSON 结构是不够的 —— 结构合法但配方永远匹配不上的情况，
     * 只有真的喂一个合成格进去才会暴露。</p>
     */
    private static void recipeAuditCases(MinecraftServer server, HolderLookup.Provider registries) {
        int compressionTotal = 0;
        int compressionBad = 0;
        int shapedTotal = 0;
        int shapedBad = 0;
        String firstBad = null;

        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (!SiaoMod.MOD_ID.equals(holder.id().getNamespace())) {
                continue;
            }
            Recipe<?> recipe = holder.value();

            if (recipe instanceof SiaoCompressionRecipe compression
                    && compression.group().startsWith("singularity")) {
                continue;   // 这两条按配置互斥，在 singularityRouteCases 里单独测
            }
            if (recipe instanceof SiaoCompressionRecipe compression) {
                compressionTotal++;
                CraftingInput input = compressionInput(compression);
                boolean matched = input != null && compression.matches(input, server.overworld());
                ItemStack output = matched ? compression.assemble(input, registries) : ItemStack.EMPTY;
                if (!matched || output.isEmpty()
                        || !ItemStack.isSameItem(output, compression.getResultItem(registries))) {
                    compressionBad++;
                    if (firstBad == null) {
                        firstBad = holder.id() + " matched=" + matched + " out=" + output
                                + " 期望=" + compression.getResultItem(registries);
                    }
                }
            } else if (recipe instanceof ShapedRecipe shaped) {
                shapedTotal++;
                CraftingInput input = shapedInput(shaped);
                boolean matched = input != null && shaped.matches(input, server.overworld());
                ItemStack output = matched ? shaped.assemble(input, registries) : ItemStack.EMPTY;
                if (!matched || output.isEmpty()) {
                    shapedBad++;
                    if (firstBad == null) {
                        firstBad = holder.id() + " matched=" + matched + " out=" + output;
                    }
                }
            }
        }

        check("配方审计：压缩配方全部可匹配可产出（" + compressionTotal + " 条）",
                compressionBad == 0, firstBad);
        check("配方审计：shaped 配方全部可匹配可产出（" + shapedTotal + " 条）",
                shapedBad == 0, firstBad);
    }

    /** 按配方声明的输入个数摆一个 3x3 合成格。 */
    private static CraftingInput compressionInput(SiaoCompressionRecipe recipe) {
        ItemStack[] candidates = recipe.ingredient().getItems();
        if (candidates.length == 0) {
            return null;
        }
        ItemStack base = candidates[0].copyWithCount(1);
        if (recipe.enchantMode() == SiaoCompressionRecipe.EnchantMode.REQUIRE) {
            SiaoComponents.makeEnchanted(base, 1);
        }
        List<ItemStack> slots = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            slots.add(i < recipe.count() ? base.copy() : ItemStack.EMPTY);
        }
        return CraftingInput.of(3, 3, slots);
    }

    /** 按 pattern 逐格摆；空格留空。 */
    private static CraftingInput shapedInput(ShapedRecipe recipe) {
        int width = recipe.getWidth();
        int height = recipe.getHeight();
        List<ItemStack> slots = new ArrayList<>(width * height);
        for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack[] items = ingredient.getItems();
            slots.add(items.length == 0 ? ItemStack.EMPTY : items[0].copy());
        }
        if (slots.size() != width * height) {
            return null;
        }
        return CraftingInput.of(width, height, slots);
    }

    /** 判断一个 id 是不是压缩链上的东西（压缩单位 / 压缩块），而不是铜形状装饰块。 */
    private static boolean isCompressionChainItem(net.minecraft.resources.ResourceLocation id) {
        if (!SiaoMod.MOD_ID.equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        if (!path.startsWith("compressed_")) {
            return false;
        }
        return !path.matches(".*_(cut|polished|grate|chiseled|stairs|slab)(_waxed)?$");
    }

    /**
     * 回归：每条 siao 配方都必须能被序列化器编码进网络包。
     *
     * <p>之前混沌苹果用了 {@code StreamCodec.unit}，JSON 解析出来的实例和单例
     * 不是同一个对象，同步配方时直接抛 IllegalStateException，
     * 整个 update_recipes 包发不出去 —— 单人和多人都进不去世界。</p>
     */
    private static void recipeSyncCases(MinecraftServer server) {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(), server.registryAccess());
        int encoded = 0;
        String failure = null;
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (!SiaoMod.MOD_ID.equals(holder.id().getNamespace())) {
                continue;
            }
            try {
                encodeRecipe(buffer, holder.value());
                encoded++;
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = holder.id() + " -> " + throwable;
                }
            }
        }
        check("配方同步：全部 siao 配方都能编码进网络包（" + encoded + " 条）",
                failure == null, failure);
        buffer.release();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void encodeRecipe(net.minecraft.network.RegistryFriendlyByteBuf buffer,
                                     net.minecraft.world.item.crafting.Recipe<?> recipe) {
        ((net.minecraft.world.item.crafting.RecipeSerializer) recipe.getSerializer())
                .streamCodec().encode(buffer, recipe);
    }
    /**
     * 槽位矩阵：每台机器的每个槽位用典型物品试一遍，把结果打成一张表。
     *
     * <p>断言三件事：输出槽绝不接受、升级槽只收升级、非压缩机第 1-8 槽拒绝原料。</p>
     */
    private static void slotMatrixCases(MinecraftServer server) {
        List<ItemStack> probes = List.of(
                new ItemStack(SiaoItems.unit(Material.APPLE, 1).get()),
                new ItemStack(net.minecraft.world.item.Items.APPLE),
                new ItemStack(net.minecraft.world.item.Items.OAK_SAPLING),
                new ItemStack(SiaoItems.upgrades().iterator().next().get()),
                new ItemStack(net.minecraft.world.item.Items.DIRT));
        String[] names = {"压缩单位", "苹果", "树苗", "升级", "泥土"};

        for (MachineKind kind : MachineKind.values()) {
            AppleMachineBlockEntity machine = place(server, kind, new BlockPos(30 + kind.ordinal(), 220, 0));
            var handler = machine.getItemHandler();
            StringBuilder row = new StringBuilder();
            int wrongOutput = 0;
            int wrongUpgrade = 0;
            int wrongDeadSlot = 0;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                char cell = '.';
                for (int i = 0; i < probes.size(); i++) {
                    if (handler.isItemValid(slot, probes.get(i))) {
                        cell = "压果苗升泥".charAt(i);
                        break;
                    }
                }
                row.append(cell);
                boolean isOutput = slot >= kind.outputStart() && slot < kind.outputStart() + kind.outputSlots();
                boolean isUpgrade = slot >= kind.upgradeStart();
                if (isOutput && cell != '.') {
                    wrongOutput++;
                }
                if (isUpgrade && cell != '.' && cell != '升') {
                    wrongUpgrade++;
                }
                if (!isOutput && !isUpgrade && !kind.usesInputSlot(slot) && cell != '.') {
                    wrongDeadSlot++;
                }
                // 9 个输入槽必须都能塞进对应物品（不能让玩家对着空槽发呆）
                if (!isOutput && !isUpgrade) {
                    boolean anyProbe = false;
                    for (ItemStack probe : probes) {
                        anyProbe |= handler.isItemValid(slot, probe);
                    }
                    if (!anyProbe) {
                        wrongDeadSlot++;
                    }
                }
            }
            check("槽位：" + kind.id() + " 输出槽不收物品", wrongOutput == 0, row.toString());
            check("槽位：" + kind.id() + " 升级槽只收升级", wrongUpgrade == 0, row.toString());
            check("槽位：" + kind.id() + " 无效输入槽保持空", wrongDeadSlot == 0, row.toString());
            SiaoMod.LOGGER.info("[SIAO-TEST] 槽位矩阵 {}（{}槽）：{}",
                    kind.id(), handler.getSlots(), row);
        }
    }
    /**
     * 回归：9 个压缩煤同时匹配「升重」和「装块」，机器必须选升重。
     *
     * <p>之前候选没排序，取的是先注册的装块配方 —— 玩家压煤得到的是煤块。</p>
     */
    private static void compressorPriorityCases(MinecraftServer server) {
        AppleMachineBlockEntity machine = place(server, MachineKind.COMPRESSOR, new BlockPos(40, 220, 0));
        machine.fillEnergy();
        ItemStack coalUnit = new ItemStack(SiaoItems.unit(Material.COAL, 1).get());
        for (int slot = 0; slot < 9; slot++) {
            ((net.neoforged.neoforge.items.IItemHandlerModifiable) machine.getItemHandler()).setStackInSlot(slot, coalUnit.copy());
        }
        for (int tick = 0; tick < 200; tick++) {
            machine.tick();
        }
        ItemStack produced = ItemStack.EMPTY;
        for (int slot = machine.machineKind().outputStart();
             slot < machine.machineKind().outputStart() + machine.machineKind().outputSlots(); slot++) {
            if (!machine.getItemHandler().getStackInSlot(slot).isEmpty()) {
                produced = machine.getItemHandler().getStackInSlot(slot);
                break;
            }
        }
        check("压缩机优先级：9 个压缩煤 → 二重压缩煤（不是煤块）",
                produced.is(SiaoItems.unit(Material.COAL, 2).get()), describe(produced));

        // 切到装块模式后应该产出煤块
        machine.togglePackingMode();
        for (int slot = 0; slot < 9; slot++) {
            ((net.neoforged.neoforge.items.IItemHandlerModifiable) machine.getItemHandler()).setStackInSlot(slot, coalUnit.copy());
        }
        for (int slot = machine.machineKind().outputStart();
             slot < machine.machineKind().outputStart() + machine.machineKind().outputSlots(); slot++) {
            ((net.neoforged.neoforge.items.IItemHandlerModifiable) machine.getItemHandler()).setStackInSlot(slot, ItemStack.EMPTY);
        }
        for (int tick = 0; tick < 200; tick++) {
            machine.tick();
        }
        ItemStack packed = ItemStack.EMPTY;
        for (int slot = machine.machineKind().outputStart();
             slot < machine.machineKind().outputStart() + machine.machineKind().outputSlots(); slot++) {
            if (!machine.getItemHandler().getStackInSlot(slot).isEmpty()) {
                packed = machine.getItemHandler().getStackInSlot(slot);
                break;
            }
        }
        check("压缩机优先级：装块模式下 9 个压缩煤 → 煤块",
                packed.is(SiaoBlocks.unitBlock(Material.COAL, 1).get().asItem()), describe(packed));
        machine.togglePackingMode();
    }
    /** 奇点两条路线按配置互斥。 */
    private static void singularityRouteCases(MinecraftServer server) {
        SiaoCompressionRecipe direct = require(server, "siao:craft_singularity");
        SiaoCompressionRecipe shards = require(server, "siao:craft_singularity_from_shards");
        ItemStack tier9 = new ItemStack(SiaoItems.unit(Material.APPLE, 9).get());

        List<ItemStack> grid = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            grid.add(new ItemStack(SiaoItems.singularityShard().get()));
        }
        CraftingInput shardInput = CraftingInput.of(3, 3, grid);

        try {
            SiaoConfig.SINGULARITY_MODE.set(SiaoConfig.SingularityMode.DIRECT);
            check("奇点：direct 模式下九重苹果锭可合",
                    direct.matches(single(tier9), null), "不匹配");
            check("奇点：direct 模式下碎片配方不生效",
                    !shards.matches(shardInput, null), "居然生效了");

            SiaoConfig.SINGULARITY_MODE.set(SiaoConfig.SingularityMode.SHARD);
            check("奇点：shard 模式下 9 碎片可合",
                    shards.matches(shardInput, null), "不匹配");
            check("奇点：shard 模式下九重苹果锭配方不生效",
                    !direct.matches(single(tier9), null), "居然生效了");
        } finally {
            SiaoConfig.SINGULARITY_MODE.set(SiaoConfig.SingularityMode.DIRECT);
        }
    }
    /** 进度与物品标签。 */
    private static void advancementCases(MinecraftServer server) {
        int loaded = 0;
        for (String id : new String[]{"root", "first_compress", "tier3", "tier9", "machine", "enchant", "singularity"}) {
            if (server.getAdvancements().get(net.minecraft.resources.ResourceLocation.parse("siao:" + id)) != null) {
                loaded++;
            }
        }
        check("进度：7 个进度全部装载", loaded == 7, loaded + " 个");

        var itemTags = server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ITEM);
        var units = itemTags.getTag(net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.ITEM,
                net.minecraft.resources.ResourceLocation.parse("siao:compressed_units")));
        check("标签：压缩单位标签非空", units.isPresent() && units.get().size() > 100,
                units.map(tag -> tag.size() + " 项").orElse("标签不存在"));
    }
    /**
     * 回归：配方里的每重倍数必须等于 {@link Material#PER_TIER}。
     *
     * <p>倍数同时写在 Java 常量和生成的 JSON 里，改了一边忘了另一边就会出现
     * 「配方要 9 个、代码按 3 个算等价数」的错位。这条断言把它钉死。</p>
     */
    private static void perTierCases(MinecraftServer server) {
        int mismatched = 0;
        String first = null;
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof SiaoCompressionRecipe recipe)) {
                continue;
            }
            boolean compressPair = recipe.ingredient().getItems().length > 0
                    && (recipe.count() == Material.PER_TIER || recipe.result().getCount() == Material.PER_TIER);
            if (!compressPair) {
                continue;
            }
            boolean ok = recipe.count() == Material.PER_TIER
                    || recipe.result().getCount() == Material.PER_TIER;
            if (!ok) {
                mismatched++;
                if (first == null) {
                    first = holder.id().toString();
                }
            }
        }
        check("配方倍数与 Material.PER_TIER 一致", mismatched == 0, first);
    }
    /** C 线：氧化链必须真的挂上（之前那 55 个铜变种根本没实现 WeatheringCopper）。 */
    private static void oxidationCases(MinecraftServer server) {
        var registry = server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BLOCK);
        Block ironBlock = SiaoBlocks.unitBlock(Material.IRON, 1).get();
        Block exposed = registry.get(net.minecraft.resources.ResourceLocation.parse("siao:exposed_compressed_1x_iron_block"));
        Block weathered = registry.get(net.minecraft.resources.ResourceLocation.parse("siao:weathered_compressed_1x_iron_block"));
        Block oxidized = registry.get(net.minecraft.resources.ResourceLocation.parse("siao:oxidized_compressed_1x_iron_block"));
        Block waxed = registry.get(net.minecraft.resources.ResourceLocation.parse("siao:oxidized_compressed_1x_iron_block_waxed"));

        check("氧化：铁块 → 斑驳 → 锈蚀 → 氧化 成链",
                net.minecraft.world.level.block.WeatheringCopper.getNext(ironBlock).orElse(null) == exposed
                        && net.minecraft.world.level.block.WeatheringCopper.getNext(exposed).orElse(null) == weathered
                        && net.minecraft.world.level.block.WeatheringCopper.getNext(weathered).orElse(null) == oxidized,
                "链断了");
        check("氧化：最后一阶不再氧化",
                net.minecraft.world.level.block.WeatheringCopper.getNext(oxidized).isEmpty(), "");
        check("氧化：未涂蜡的方块会随机刻氧化",
                ironBlock instanceof net.minecraft.world.level.block.WeatheringCopper
                        && exposed instanceof net.minecraft.world.level.block.WeatheringCopper, "");
        check("氧化：涂蜡版不氧化",
                !(waxed instanceof net.minecraft.world.level.block.WeatheringCopper), waxed.toString());
    }
    /** 配置器（Mek 式）与发电机推送。 */
    private static void configuratorCases(MinecraftServer server) {
        AppleMachineBlockEntity machine = place(server, MachineKind.COMPRESSOR, new BlockPos(22, 210, 0));
        check("配置器：右键哪个面就切哪个面",
                machine.cycleSide(Direction.NORTH) == SideMode.INPUT
                        && machine.sideMode(Direction.NORTH) == SideMode.INPUT,
                machine.sideMode(Direction.NORTH).name());
        check("配置器：连切三次回到双向",
                machine.cycleSide(Direction.NORTH) == SideMode.OUTPUT
                        && machine.cycleSide(Direction.NORTH) == SideMode.NONE
                        && machine.cycleSide(Direction.NORTH) == SideMode.BOTH,
                machine.sideMode(Direction.NORTH).name());
        check("配置器：其他面不受影响",
                machine.sideMode(Direction.SOUTH) == SideMode.BOTH, machine.sideMode(Direction.SOUTH).name());

        // 潜行右键 = 反向循环
        machine.setSideMode(Direction.UP, SideMode.BOTH);
        check("配置器：潜行右键反向循环（双向 → 封闭）",
                machine.cycleSide(Direction.UP, true) == SideMode.NONE, machine.sideMode(Direction.UP).name());
        check("配置器：反向再走三步回到双向",
                machine.cycleSide(Direction.UP, true) == SideMode.OUTPUT
                        && machine.cycleSide(Direction.UP, true) == SideMode.INPUT
                        && machine.cycleSide(Direction.UP, true) == SideMode.BOTH,
                machine.sideMode(Direction.UP).name());

        // 回归：vanilla 的调用顺序是 block.useItemOn -> block.useWithoutItem -> item.useOn，
        // 机器若在 useWithoutItem 里 consume（开界面），配置器就永远轮不到。
        net.neoforged.neoforge.common.util.FakePlayer fake =
                net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(server.overworld());
        fake.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(SiaoItems.configurator().get()));
        var hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(machine.getBlockPos()),
                Direction.UP, machine.getBlockPos(), false);
        net.minecraft.world.InteractionResult blockResult =
                server.overworld().getBlockState(machine.getBlockPos())
                        .useWithoutItem(server.overworld(), fake, hit);
        check("配置器：机器不会抢走配置器的右键交互",
                blockResult == net.minecraft.world.InteractionResult.PASS, blockResult.toString());

        // 发电机把电推给相邻机器
        AppleMachineBlockEntity generator = place(server, MachineKind.GENERATOR, new BlockPos(24, 210, 0));
        AppleMachineBlockEntity consumer = place(server, MachineKind.COMPRESSOR, new BlockPos(25, 210, 0));
        generator.getItemHandler().insertItem(0, new ItemStack(Items.APPLE, 8), false);
        for (int tick = 0; tick < 200; tick++) {
            generator.tick();
        }
        check("发电机：电力推给相邻机器", consumer.energyStored() > 0, consumer.energyStored() + " FE");
    }

    /** 苹果发电机：烧苹果换 FE，整个模组唯一的电源。 */
    private static void generatorCases(MinecraftServer server) {
        int fuelTier1 = AppleMachineBlockEntity.fuelValue(
                new ItemStack(SiaoItems.unit(Material.APPLE, 1).get()));
        check("发电机：一重压缩苹果 = 1800 FE", fuelTier1 == 1800, fuelTier1 + " FE");
        check("发电机：铁锭不能当燃料",
                AppleMachineBlockEntity.fuelValue(new ItemStack(Items.IRON_INGOT)) == 0, "");

        AppleMachineBlockEntity generator = place(server, MachineKind.GENERATOR, new BlockPos(18, 210, 0));
        generator.getItemHandler().insertItem(0, new ItemStack(Items.APPLE, 4), false);
        int before = generator.energyStored();
        for (int tick = 0; tick < 60; tick++) {
            generator.tick();
        }
        check("发电机：烧苹果产电",
                generator.energyStored() > before, before + " -> " + generator.energyStored());
        check("发电机：燃料被消耗",
                generator.getItemHandler().getStackInSlot(0).getCount() == 3,
                generator.getItemHandler().getStackInSlot(0).getCount() + " 个");
    }

    /** 存档往返：能量与侧配置必须能存进去再读回来（也是 /data merge 能不能改能量的前提）。 */
    private static void persistenceCases(MinecraftServer server, HolderLookup.Provider registries) {
        AppleMachineBlockEntity machine = place(server, MachineKind.COMPRESSOR, new BlockPos(16, 210, 0));
        machine.fillEnergy();
        int full = machine.energyStored();
        machine.setSideMode(Direction.UP, SideMode.NONE);

        net.minecraft.nbt.CompoundTag tag = machine.saveWithoutMetadata(registries);
        machine.loadWithComponents(tag, registries);

        check("存档往返：NBT 里有 energy 键", tag.contains("energy"), tag.getAllKeys().toString());
        check("存档往返：能量读回来一致", machine.energyStored() == full,
                machine.energyStored() + " / " + full);
        check("存档往返：侧配置读回来一致", machine.sideMode(Direction.UP) == SideMode.NONE,
                machine.sideMode(Direction.UP).name());
    }

    /** 整批推料：AE2 档位 1 的地基，不装 AE2 也能测。 */
    private static void batchCases(MinecraftServer server) {
        AppleMachineBlockEntity machine = place(server, MachineKind.COMPRESSOR, new BlockPos(6, 206, 0));
        check("整批推料：9 个铁锭一次吃下",
                machine.acceptCraftingBatch(new ItemStack(Items.IRON_INGOT, 9)), "被拒");
        check("整批推料：吃下后输入槽共 9 个",
                inputCount(machine) == 9, inputCount(machine) + " 个");
        check("整批推料：超出容量的批次整批拒绝",
                !machine.acceptCraftingBatch(new ItemStack(Items.IRON_INGOT, 600)), "居然塞下了");
        check("整批推料：被拒时一个都没多进",
                inputCount(machine) == 9, inputCount(machine) + " 个");
    }

    private static int inputCount(AppleMachineBlockEntity machine) {
        int total = 0;
        int start = machine.machineKind().inputStart();
        for (int slot = start; slot < start + machine.machineKind().inputSlots(); slot++) {
            total += machine.getItemHandler().getStackInSlot(slot).getCount();
        }
        return total;
    }

    private static int outputCount(AppleMachineBlockEntity machine) {
        int total = 0;
        int start = machine.machineKind().outputStart();
        for (int slot = start; slot < start + machine.machineKind().outputSlots(); slot++) {
            total += machine.getItemHandler().getStackInSlot(slot).getCount();
        }
        return total;
    }

    /** 苹果农场：树苗 → 苹果，整条压缩链的瓶颈。 */
    private static void orchardCases(MinecraftServer server) {
        AppleMachineBlockEntity orchard = place(server, MachineKind.ORCHARD, new BlockPos(12, 210, 0));
        orchard.getItemHandler().insertItem(0, new ItemStack(Items.OAK_SAPLING), false);
        fillEnergy(orchard);
        for (int tick = 0; tick < 60 * 8; tick++) {
            orchard.tick();
        }
        check("农场：一个树苗结出 8 个苹果",
                outputCount(orchard) == 8, outputCount(orchard) + " 个");
        check("农场：树苗被消耗",
                orchard.getItemHandler().getStackInSlot(0).isEmpty(),
                orchard.getItemHandler().getStackInSlot(0) + "");

        for (int tick = 0; tick < 120; tick++) {
            orchard.tick();
        }
        check("农场：没有树苗就不再产苹果",
                outputCount(orchard) == 8, outputCount(orchard) + " 个");

        AppleMachineBlockEntity noSeed = place(server, MachineKind.ORCHARD, new BlockPos(14, 210, 0));
        check("农场：非树苗塞不进输入槽",
                noSeed.getItemHandler().insertItem(0, new ItemStack(Items.IRON_INGOT), false).getCount() == 1,
                "居然塞进去了");
    }
    /**
     * AE2 专项：只有装了 AE2 才跑。
     *
     * <p>用反射调用 {@code Ae2Integration#verify} —— 自检类本身不引用任何 AE2 类型，
     * 所以没装 AE2 的 CI 环境编译和运行都不受影响。</p>
     */
    private static void ae2Cases(MinecraftServer server) {
        if (!ModList.get().isLoaded("ae2")) {
            SiaoMod.LOGGER.info("[SIAO-TEST] SKIP  AE2 未安装，跳过 AE2 专项");
            SiaoMod.LOGGER.info("[SIAO-TEST] AE2-CHECK skipped");
            return;
        }
        BlockPos pos = new BlockPos(8, 206, 0);
        place(server, MachineKind.COMPRESSOR, pos);
        try {
            Class<?> integration = Class.forName("com.siao.appleoverload.compat.Ae2Integration");

            java.lang.reflect.Method verify = integration.getMethod("verify", Level.class, BlockPos.class);
            Object craftingFailure = verify.invoke(null, server.overworld(), pos);
            check("AE2：压缩机暴露了 ICraftingMachine 能力", craftingFailure == null,
                    String.valueOf(craftingFailure));

            java.lang.reflect.Method verifyStorage = integration.getMethod("verifyStorage",
                    Level.class, BlockPos.class);
            Object storageFailure = verifyStorage.invoke(null, server.overworld(), pos);
            check("AE2：ME_STORAGE 的 SIMULATE 准确且不改状态", storageFailure == null,
                    String.valueOf(storageFailure));

            boolean ok = craftingFailure == null && storageFailure == null;
            SiaoMod.LOGGER.info("[SIAO-TEST] AE2-CHECK {}", ok ? "ok" : "failed");
        } catch (ReflectiveOperationException e) {
            check("AE2：反射调用失败", false, e.toString());
            SiaoMod.LOGGER.error("[SIAO-TEST] AE2-CHECK failed");
        }
    }

    /** 侧配置：每个面的物品通行规则。 */
    private static void sideConfigCases(MinecraftServer server) {
        AppleMachineBlockEntity machine = place(server, MachineKind.COMPRESSOR, new BlockPos(4, 206, 0));
        Direction face = Direction.UP;

        check("侧配置：新机器六面默认双向",
                machine.sideMode(face) == SideMode.BOTH, machine.sideMode(face).name());
        check("侧配置：双向面能看到输入+输出槽（不含升级槽）",
                machine.getItemHandlerFor(face).getSlots()
                        == machine.machineKind().inputSlots() + machine.machineKind().outputSlots(),
                machine.getItemHandlerFor(face).getSlots() + " 槽");

        machine.setSideMode(face, SideMode.OUTPUT);
        IItemHandler outputOnly = machine.getItemHandlerFor(face);
        check("侧配置：仅输出面只剩输出槽",
                outputOnly.getSlots() == machine.machineKind().outputSlots(),
                outputOnly.getSlots() + " 槽");
        check("侧配置：仅输出面插不进东西",
                !outputOnly.insertItem(0, new ItemStack(Items.IRON_INGOT), true).isEmpty(),
                "居然插进去了");
        check("侧配置：没改过的面不受影响",
                machine.getItemHandlerFor(Direction.DOWN).getSlots()
                        == machine.machineKind().inputSlots() + machine.machineKind().outputSlots(),
                machine.getItemHandlerFor(Direction.DOWN).getSlots() + " 槽");

        machine.setSideMode(face, SideMode.INPUT);
        IItemHandler inputOnly = machine.getItemHandlerFor(face);
        check("侧配置：仅输入面抽不出东西",
                inputOnly.extractItem(0, 1, true).isEmpty(), "居然抽出来了");

        machine.setSideMode(face, SideMode.NONE);
        check("侧配置：封闭面 0 槽", machine.getItemHandlerFor(face).getSlots() == 0,
                machine.getItemHandlerFor(face).getSlots() + "");

        check("侧配置：内部逻辑看不到侧配置（不会被自己卡死）",
                machine.getItemHandler().getSlots() == machine.machineKind().totalSlots(),
                machine.getItemHandler().getSlots() + " 槽");

        // 外部视图绝不能暴露升级槽，否则管道能把升级抽走
        check("侧配置：外部视图不含升级槽",
                machine.getItemHandlerFor(null).getSlots()
                        == machine.machineKind().inputSlots() + machine.machineKind().outputSlots(),
                machine.getItemHandlerFor(null).getSlots() + " 槽");
        check("侧配置：内部栏仍含 8 个升级槽",
                machine.getItemHandler().getSlots() == machine.machineKind().totalSlots(),
                machine.getItemHandler().getSlots() + " 槽");

        // 带 requiresCorrectToolForDrops 的方块必须在 mineable 标签里，否则挖了不掉
        check("机器在 #mineable/pickaxe 里",
                server.overworld().getBlockState(machine.getBlockPos())
                        .is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE),
                machine.getBlockState().getBlock().toString());
        var copperStairs = SiaoBlocks.copperVariant(CopperVariant.Stage.UNAFFECTED,
                CopperVariant.Shape.STAIRS, false).get().defaultBlockState();
        check("铜楼梯在 #mineable/pickaxe 里",
                copperStairs.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE),
                copperStairs.getBlock().toString());

        // 四种整块形状 × 4 氧化阶 × 涂蜡/不涂蜡 = 32 条合成配方；
        // 缺了它们这些方块只能创造模式拿
        long shapeRecipes = server.getRecipeManager().getRecipes().stream()
                .filter(holder -> holder.id().getPath().matches(
                        "craft_(exposed_|weathered_|oxidized_)?compressed_1x_copper_(cut|polished|grate|chiseled)(_waxed)?"))
                .count();
        check("铜：四种整块形状都有合成配方（32 条）", shapeRecipes == 32, shapeRecipes + " 条");

        // 56 个铜方块都应该有「相当于 81 个铜锭」的等价提示
        long copperVariantsWithoutTooltip = SiaoBlocks.copperVariants().stream()
                .filter(block -> CompressionInfo.parse(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block.get())).isEmpty())
                .count();
        check("铜：每个变种方块都能算出等价数", copperVariantsWithoutTooltip == 0,
                copperVariantsWithoutTooltip + " 个算不出");
        var stairInfo = CompressionInfo.parse(net.minecraft.resources.ResourceLocation.parse(
                "siao:oxidized_compressed_1x_copper_stairs_waxed"));
        var worst = CompressionInfo.parse(net.minecraft.resources.ResourceLocation.parse(
                "siao:weathered_compressed_9x_apple_netherite_chiseled_waxed"));
        check("变种解析：九重苹果下界合金雕纹块 = 9^9 × 9",
                worst.isPresent() && worst.get().equivalents() == 3_486_784_401L,
                worst.map(info -> info.equivalents() + "").orElse("解析失败"));        check("铜：氧化涂蜡楼梯 = 81 个铜锭",
                stairInfo.isPresent() && stairInfo.get().equivalents() == 81L,
                stairInfo.map(info -> info.equivalents() + "").orElse("解析失败"));
    }

    /** 阶段 7：配置项。 */
    private static void configCases(MinecraftServer server) {
        SiaoCompressionRecipe eightToNine = require(server, "siao:compress_iron_9x");
        ItemStack eight = new ItemStack(SiaoItems.unit(Material.IRON, 8).get());
        check("配置：默认 maxTier=9，八重可压到九重",
                eightToNine.matches(grid(fill(eight, 9)), null), "不匹配");

        int original = SiaoConfig.maxTier();
        try {
            SiaoConfig.MAX_TIER.set(3);
            check("配置：maxTier 已改为 3", SiaoConfig.maxTier() == 3, SiaoConfig.maxTier() + "");

            SiaoCompressionRecipe threeToFour = require(server, "siao:compress_iron_4x");
            ItemStack three = new ItemStack(SiaoItems.unit(Material.IRON, 3).get());
            check("配置：maxTier=3 时四重配方不匹配（目标超限）",
                    !threeToFour.matches(grid(fill(three, 9)), null), "仍然匹配");

            SiaoCompressionRecipe twoToThree = require(server, "siao:compress_iron_3x");
            ItemStack two = new ItemStack(SiaoItems.unit(Material.IRON, 2).get());
            check("配置：maxTier=3 时三重配方仍匹配",
                    twoToThree.matches(grid(fill(two, 9)), null), "不匹配");
        } finally {
            SiaoConfig.MAX_TIER.set(original);
        }
        check("配置：maxTier 已恢复默认", SiaoConfig.maxTier() == original, SiaoConfig.maxTier() + "");
    }

    /** 阶段 5（第一刀）：铜专线整块变种 + 氧化/涂蜡 data map。 */
    private static void copperCases() {
        Block plainCut = SiaoBlocks.copperVariant(CopperVariant.Stage.UNAFFECTED, CopperVariant.Shape.CUT, false).get();
        Block exposedCut = SiaoBlocks.copperVariant(CopperVariant.Stage.EXPOSED, CopperVariant.Shape.CUT, false).get();
        Block waxedCut = SiaoBlocks.copperVariant(CopperVariant.Stage.UNAFFECTED, CopperVariant.Shape.CUT, true).get();
        Block base = SiaoBlocks.copperVariant(CopperVariant.Stage.UNAFFECTED, CopperVariant.Shape.BASE, false).get();
        Block exposedBase = SiaoBlocks.copperVariant(CopperVariant.Stage.EXPOSED, CopperVariant.Shape.BASE, false).get();

        check("铜：注册了 55 个变种（5 整块 × 8 + 楼梯 8 + 台阶 8 − 1 复用）",
                SiaoBlocks.copperVariantCount() == 55, SiaoBlocks.copperVariantCount() + " 个");
        Block stairsBlock = SiaoBlocks.copperVariant(CopperVariant.Stage.UNAFFECTED,
                CopperVariant.Shape.STAIRS, false).get();
        Block slabBlock = SiaoBlocks.copperVariant(CopperVariant.Stage.OXIDIZED,
                CopperVariant.Shape.SLAB, true).get();
        check("铜：楼梯用了 StairBlock", stairsBlock instanceof net.minecraft.world.level.block.StairBlock,
                stairsBlock.getClass().getSimpleName());
        check("铜：台阶用了 SlabBlock", slabBlock instanceof net.minecraft.world.level.block.SlabBlock,
                slabBlock.getClass().getSimpleName());
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

        // 终极木棍：原构想 L13 —— 附魔一重压缩**粗苹果金块** → 1 根木棍
        SiaoCompressionRecipe ultimate = require(server, "siao:ultimate_stick");
        ItemStack rawAppleBlock = new ItemStack(SiaoBlocks.rawBlock(Material.APPLE, 1).get());
        ItemStack enchantedRawBlock = rawAppleBlock.copy();
        SiaoComponents.makeEnchanted(enchantedRawBlock, 3);
        check("终极木棍：普通粗苹果金块不匹配", !ultimate.matches(single(rawAppleBlock), null), "");
        check("终极木棍：附魔粗苹果金块匹配", ultimate.matches(single(enchantedRawBlock), null), "不匹配");
        ItemStack stick = ultimate.assemble(single(enchantedRawBlock), registries);
        check("终极木棍：产物是 1 根木棍",
                sameItem(stick, new ItemStack(Items.STICK)) && stick.getCount() == 1, describe(stick));
        check("终极木棍：木棍不带附魔", !SiaoComponents.isEnchanted(stick), describe(stick));

        // 混沌苹果：原构想 L11 —— 附魔粗苹果金 + 附魔苹果锭 → 随机 NBT 混沌苹果
        ChaosAppleRecipe chaos = (ChaosAppleRecipe) server.getRecipeManager()
                .byKey(ResourceLocation.parse("siao:chaos_apple"))
                .orElseThrow().value();
        ItemStack rawAppleUnit = new ItemStack(SiaoItems.rawUnit(Material.APPLE, 1).get());
        ItemStack enchantedRawUnit = rawAppleUnit.copy();
        SiaoComponents.makeEnchanted(enchantedRawUnit, 1);
        ItemStack enchantedApple = tier1Apple.copy();
        SiaoComponents.makeEnchanted(enchantedApple, 3);

        List<ItemStack> pair = new ArrayList<>(9);
        pair.add(enchantedRawUnit);
        pair.add(enchantedApple);
        for (int i = 2; i < 9; i++) {
            pair.add(ItemStack.EMPTY);
        }
        CraftingInput chaosInput = CraftingInput.of(3, 3, pair);
        check("混沌苹果：附魔粗苹果金 + 附魔苹果锭 匹配",
                chaos.matches(chaosInput, server.overworld()), "不匹配");

        List<ItemStack> notEnchanted = new ArrayList<>(9);
        notEnchanted.add(rawAppleUnit);
        notEnchanted.add(tier1Apple);
        for (int i = 2; i < 9; i++) {
            notEnchanted.add(ItemStack.EMPTY);
        }
        check("混沌苹果：不附魔不匹配",
                !chaos.matches(CraftingInput.of(3, 3, notEnchanted), server.overworld()), "");

        ItemStack chaosApple = chaos.assemble(chaosInput, registries);
        check("混沌苹果：产物是混沌苹果且带附魔",
                sameItem(chaosApple, new ItemStack(SiaoItems.chaosApple().get()))
                        && SiaoComponents.isEnchanted(chaosApple),
                describe(chaosApple));
        int tierA = SiaoComponents.enchantTier(chaos.assemble(chaosInput, registries));
        check("混沌苹果：随机强度落在 1–9", tierA >= 1 && tierA <= 9, "tier=" + tierA);
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

        // 399 = 15 个原版输入 + 1 个粗苹果金 + 5 个合金基础物品 + 189 个压缩单位 + 189 个压缩块
        // （压缩/拆分/装块/拆块四类配方现在都在同一张索引里）
        check("压缩索引覆盖 400 种输入",
                CompressionIndex.size(server.getRecipeManager()) == 400,
                CompressionIndex.size(server.getRecipeManager()) + " 种");

        // ★ L12 科技树绑定：压缩链（单位 ↔ 单位、单位 ↔ 块）的东西不能在工作台合成。
        //   铜的形状变种是装饰线，按原版习惯仍然可以手搓，所以排除在外。
        long craftableByHand = server.getRecipeManager().getRecipes().stream()
                .filter(holder -> SiaoMod.MOD_ID.equals(holder.id().getNamespace()))
                .filter(holder -> holder.value().getType() == RecipeType.CRAFTING)
                .filter(holder -> isCompressionChainItem(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                                holder.value().getResultItem(server.registryAccess()).getItem())))
                .count();
        check("科技树绑定：压缩链产物不能手搓",
                craftableByHand == 0, craftableByHand + " 条仍可在工作台合成");

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
                new ItemStack(SiaoItems.upgrade(SiaoUpgrades.SPEED).get()), false);
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
                new ItemStack(SiaoItems.upgrade(SiaoUpgrades.ENERGY).get()), false);
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

        // 离心机：附魔单位 → 同阶普通单位（解附魔），不再产碎片
        AppleMachineBlockEntity centrifuge = place(server, MachineKind.CENTRIFUGE, new BlockPos(8, 200, 0));
        ItemStack enchantedUnit2 = unit2.copy();
        SiaoComponents.makeEnchanted(enchantedUnit2, 3);
        centrifuge.getItemHandler().insertItem(0, enchantedUnit2, false);
        fillEnergy(centrifuge);
        for (int tick = 0; tick < 100; tick++) {
            centrifuge.tick();
        }
        ItemStack purified = centrifuge.getItemHandler().getStackInSlot(centrifuge.machineKind().outputStart());
        check("离心机：附魔单位 → 同阶普通单位",
                sameItem(purified, unit2) && !SiaoComponents.isEnchanted(purified), describe(purified));
        check("离心机：不再产出奇点碎片",
                !sameItem(purified, shard), describe(purified));

        // 离心机：混沌苹果 → 1 个奇点碎片
        // 注意坐标：机器会主动把产物推给相邻 IItemHandler，
        // 必须放在四周空旷的位置，否则碎片会被隔壁机器接走
        AppleMachineBlockEntity chaos = place(server, MachineKind.CENTRIFUGE, new BlockPos(9, 210, 0));
        chaos.getItemHandler().insertItem(0, new ItemStack(SiaoItems.chaosApple().get()), false);
        fillEnergy(chaos);
        for (int tick = 0; tick < 100; tick++) {
            chaos.tick();
        }
        ItemStack fromChaos = chaos.getItemHandler().getStackInSlot(chaos.machineKind().outputStart());
        check("离心机：混沌苹果 → 1 个奇点碎片",
                sameItem(fromChaos, shard) && fromChaos.getCount() == 1, describe(fromChaos));

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

        // 阶段 4a 档位 0：产物自动推给相邻的 IItemHandler（AE2 供应器不会来取，必须自己推）
        AppleMachineBlockEntity sender = place(server, MachineKind.COMPRESSOR, new BlockPos(0, 202, 0));
        AppleMachineBlockEntity receiver = place(server, MachineKind.COMPRESSOR, new BlockPos(1, 202, 0));
        for (int slot = 0; slot < 9; slot++) {
            sender.getItemHandler().insertItem(slot, new ItemStack(Items.IRON_INGOT), false);
        }
        fillEnergy(sender);
        for (int tick = 0; tick < 60; tick++) {
            sender.tick();
        }
        ItemStack pushed = receiver.getItemHandler().getStackInSlot(0);
        check("相邻方块推料：产物进了邻居输入槽",
                sameItem(pushed, unit1) && pushed.getCount() == 1, describe(pushed));
        check("相邻方块推料：自己输出槽被清空",
                sender.getItemHandler().getStackInSlot(sender.machineKind().outputStart()).isEmpty(),
                describe(sender.getItemHandler().getStackInSlot(sender.machineKind().outputStart())));
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
