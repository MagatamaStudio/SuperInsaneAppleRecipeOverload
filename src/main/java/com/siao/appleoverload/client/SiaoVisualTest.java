package com.siao.appleoverload.client;

import com.siao.appleoverload.SiaoMod;
import com.siao.appleoverload.compat.Ae2Compat;
import com.siao.appleoverload.content.Material;
import com.siao.appleoverload.content.SiaoBlocks;
import com.siao.appleoverload.content.SiaoItems;
import com.siao.appleoverload.machine.AppleMachineBlockEntity;
import com.siao.appleoverload.machine.AppleMachineScreen;
import com.siao.appleoverload.machine.MachineKind;
import com.siao.appleoverload.machine.SiaoMachineRegistry;
import com.siao.appleoverload.upgrade.SiaoUpgrades;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端视觉测试 harness（只活在开发环境，不进玩家 jar）。
 *
 * <p>服务端自检（SiaoSelfTest）覆盖了全部逻辑，但三件事它永远测不到：
 * <b>GUI 长什么样、AE2 真网格上直连通不通、平衡数字玩起来什么体感</b>。
 * 这个类在真客户端里按脚本跑一遍：摆机器、开界面、截真帧、在真 AE2 网络上
 * 推样板，最后写一份报告进 {@code run/siao-visual/report.md}。</p>
 *
 * <p>启动方式：{@code gradlew runVisualtest --offline}（= runClient +
 * {@code -Dsiao.visualtest=true --quickPlaySingleplayer siao_visualtest}）。
 * 跑完自动退出游戏。</p>
 */
public final class SiaoVisualTest {

    private static final String FLAG = "siao.visualtest";

    // ---- 测试场地坐标（高空，避开玩家建筑）------------------------------------
    private static final BlockPos GUI_BASE = new BlockPos(3000, 318, 3000);
    private static final BlockPos GUI_VIEW = new BlockPos(3004, 320, 3004);
    private static final BlockPos AE2_BASE = new BlockPos(3200, 318, 3000);
    private static final BlockPos AE2_VIEW = new BlockPos(3203, 320, 3004);
    private static final BlockPos BALANCE_BASE = new BlockPos(3400, 318, 3000);
    private static final BlockPos BALANCE_VIEW = new BlockPos(3402, 320, 3004);

    private static Minecraft mc;
    private static int heartbeat;
    private static final Path OUT_DIR = Path.of("siao-visual");
    private static final List<String> CHECKS = new ArrayList<>();
    private static final List<String> FINDINGS = new ArrayList<>();

    // ---- 脚本执行器 -----------------------------------------------------------
    /** 带延迟的任务：delay 每 tick 减 1，归零才执行。 */
    private static final class Task {
        final String name;
        int delay;
        final Runnable action;

        Task(int delay, Runnable action) {
            this.name = site() + "#" + (++taskSeq);
            this.delay = delay;
            this.action = action;
        }

        /** 创建任务的上游调用点（lambda$xxx$N 或方法名），排查队列卡死时一眼定位。 */
        private static String site() {
            StackTraceElement[] st = new Throwable().getStackTrace();
            // [0]=site [1]=<init> [2]=runAfter/retryFront 或直接调用者
            int i = 2;
            if (st.length > i
                    && (st[i].getMethodName().equals("runAfter") || st[i].getMethodName().equals("retryFront"))) {
                i++;
            }
            return st.length > i ? st[i].getMethodName() : "?";
        }
    }

    private static int taskSeq;
    private static int retryBudget;

    /**
     * 静音版鼠标处理器：{@link #setup(long)} 不注册任何 GLFW 回调，
     * grab/release 不碰光标。测试全程用屏幕 API 直连交互，不需要真实鼠标。
     */
    private static final class QuietMouseHandler extends MouseHandler {
        QuietMouseHandler(Minecraft minecraft) {
            super(minecraft);
        }

        @Override
        public void setup(long window) {
        }

        @Override
        public void grabMouse() {
        }

        @Override
        public void releaseMouse() {
        }
    }

    private static final Deque<Task> TASKS = new ArrayDeque<>();
    private static int holdTicks;
    private static boolean finished;

    // ---- 服务端桥：一次一个任务，跑完才放行 ----------------------------------
    private static volatile boolean serverBusy;
    private static volatile Object serverResult;

    // ---- 截图：tick 里请求，渲染完 GUI 后的帧里抓 ----------------------------
    private static String pendingShot;
    private static int shotFrames;
    private static int shotWarmup;

    private SiaoVisualTest() {
    }

    public static void register() {
        if (!Boolean.getBoolean(FLAG)) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(SiaoVisualTest::onClientTick);
        NeoForge.EVENT_BUS.addListener(SiaoVisualTest::onClientPreTick);
        SiaoMod.LOGGER.info("[SIAO-VISUAL] 视觉测试已启用（-D{}=true）", FLAG);
    }

    // ---- 事件 -----------------------------------------------------------------

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft instance = Minecraft.getInstance();
        if (mc == null) {
            mc = instance;
            // 窗口失焦会暂停游戏、停住整个脚本 —— 测试期间永远不许暂停
            mc.options.pauseOnLostFocus = false;
            // 垂直同步开着时，窗口被完全遮挡会让 swapBuffers 卡死、tick 循环停摆；
            // 关掉它测试才能在后台窗口里照常跑。帧率也压到 30，少抢前台游戏的资源
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(30);
            // 每次开/关 GUI 屏幕原版都会 grabMouse/releaseMouse，里面 glfwSetCursorPos
            // 会把系统光标拽到测试窗口中央 —— 用户自己在打游戏时鼠标会被反复抢走。
            // 换成静音版：不注册 GLFW 回调、不碰光标。测试不依赖真实鼠标输入。
            // mouseHandler 是 final 实例字段：JDK 规范允许 setAccessible 成功后直接 set
            try {
                Field mh = Minecraft.class.getDeclaredField("mouseHandler");
                mh.setAccessible(true);
                mh.set(instance, new QuietMouseHandler(instance));
                SiaoMod.LOGGER.info("[SIAO-VISUAL] MouseHandler 已换成静音版（不再抓/放鼠标）");
            } catch (Exception e) {
                SiaoMod.LOGGER.error("[SIAO-VISUAL] 替换 MouseHandler 失败，测试可能继续抢鼠标", e);
            }
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 首个 tick：paused={} player={} server={} screen={}",
                    instance.isPaused(), instance.player, instance.getSingleplayerServer(),
                    instance.screen == null ? "无" : instance.screen.getClass().getSimpleName());
        }
        if (++heartbeat % 200 == 0) {
            Task head = TASKS.peek();
            SiaoMod.LOGGER.info(
                    "[SIAO-VISUAL] 心跳 {}：paused={} player={} server={} finished={} scriptBuilt={} 队列={} 队头={} busy={} shot={} hold={}",
                    heartbeat, instance.isPaused(), instance.player, instance.getSingleplayerServer(),
                    finished, scriptBuilt, TASKS.size(),
                    head == null ? "空" : head.name + "(delay=" + head.delay + ")",
                    serverBusy, pendingShot, holdTicks);
        }
        if (finished || mc.getSingleplayerServer() == null || mc.player == null) {
            return;
        }
        if (serverBusy) {
            return;
        }
        if (pendingShot != null) {
            return;
        }
        if (holdTicks > 0) {
            holdTicks--;
            return;
        }
        Task head = TASKS.peek();
        if (head == null) {
            return;
        }
        if (head.delay > 0) {
            head.delay--;
            return;
        }
        TASKS.poll();
        SiaoMod.LOGGER.info("[SIAO-VISUAL] 执行 {} 剩余={}", head.name, TASKS.size());
        head.action.run();
    }

    /**
     * 截图在帧起点抓：{@code RenderGuiEvent.Post} 只在 HUD 层渲染后触发，那时
     * 屏幕还没画进帧缓冲（屏幕在 {@code Minecraft.render} 更靠后的位置才渲染），
     * 抓出来的全是没 GUI 的世界画面。帧起点（Pre tick）时帧缓冲里装的是
     * <b>上一帧</b>的最终画面，包含打开的屏幕 —— 和原版 F2 截图同一原理。
     */
    private static void onClientPreTick(ClientTickEvent.Pre event) {
        if (pendingShot == null || mc == null) {
            return;
        }
        // 多等几帧，保证屏幕已经稳定渲染
        if (++shotFrames < 2 + shotWarmup) {
            return;
        }
        String name = pendingShot;
        pendingShot = null;
        shotFrames = 0;
        shotWarmup = 0;
        try {
            Files.createDirectories(OUT_DIR);
            var image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            image.writeToFile(OUT_DIR.resolve(name + ".png").toFile());
            image.close();
            SiaoMod.LOGGER.info("[SIAO-VISUAL] SHOT  {}", name);
        } catch (Exception e) {
            SiaoMod.LOGGER.error("[SIAO-VISUAL] SHOT-FAIL {}", name, e);
        }
    }

    // ---- 工具 -----------------------------------------------------------------

    private static void runAfter(int delayTicks, Runnable action) {
        TASKS.add(new Task(delayTicks, action));
    }

    /** 插到队头原地等待：给「重试直到成功」用，不打断后续脚本的时序。 */
    private static void retryFront(int delayTicks, Runnable action) {
        if (++retryBudget > 5000) {
            SiaoMod.LOGGER.error("[SIAO-VISUAL] 重试预算耗尽（{} 次插队），疑似死循环，列出队列后自杀", retryBudget);
            for (Task t : TASKS) {
                SiaoMod.LOGGER.error("[SIAO-VISUAL]   队列残留 {}", t.name);
            }
            finished = true;
            mc.stop();
            return;
        }
        TASKS.addFirst(new Task(delayTicks, action));
    }

    private static void hold(int ticks) {
        holdTicks = ticks;
    }

    /** 提交一个服务端任务；执行期间脚本暂停。 */
    private static void server(Supplier<Object> task) {
        MinecraftServer server = mc.getSingleplayerServer();
        serverBusy = true;
        serverResult = null;
        SiaoMod.LOGGER.info("[SIAO-VISUAL] 服务端任务提交 {}", task.getClass().getSimpleName());
        server.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                serverResult = task.get();
            } catch (Throwable t) {
                serverResult = "EX: " + t;
                SiaoMod.LOGGER.error("[SIAO-VISUAL] 服务端任务异常", t);
            } finally {
                SiaoMod.LOGGER.info("[SIAO-VISUAL] 服务端任务完成 {} ({}ms)",
                        task.getClass().getSimpleName(), System.currentTimeMillis() - start);
                serverBusy = false;
            }
        });
    }

    private static void shot(String name) {
        shot(name, 0);
    }

    /** 额外多等 warmupFrames 帧再抓：给刚开的屏幕留渲染时间（pendingShot 期间队列整体阻塞）。 */
    private static void shot(String name, int warmupFrames) {
        pendingShot = name;
        shotWarmup = warmupFrames;
    }

    private static void check(String name, boolean ok, String detail) {
        CHECKS.add((ok ? "PASS" : "FAIL") + "  " + name + (detail == null || detail.isEmpty() ? "" : "  -> " + detail));
        SiaoMod.LOGGER.info("[SIAO-VISUAL] {}  {}", ok ? "PASS" : "FAIL", name);
        if (!ok) {
            SiaoMod.LOGGER.error("[SIAO-VISUAL]        -> {}", detail);
        }
    }

    private static void finding(String text) {
        FINDINGS.add(text);
        SiaoMod.LOGGER.warn("[SIAO-VISUAL] 发现  {}", text);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.parse(path);
    }

    private static ServerLevel overworld() {
        return mc.getSingleplayerServer().overworld();
    }

    private static ServerPlayer serverPlayer() {
        return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
    }

    private static BlockPos platform(int x, int z) {
        BlockPos base = new BlockPos(x, 318, z);
        ServerLevel level = overworld();
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                level.setBlockAndUpdate(base.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
            }
        }
        return base.offset(0, 1, 0);
    }

    private static AppleMachineBlockEntity placeMachine(MachineKind kind, BlockPos pos) {
        ServerLevel level = overworld();
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos, SiaoBlocks.machine(kind).get().defaultBlockState());
        if (level.getBlockEntity(pos) instanceof AppleMachineBlockEntity machine) {
            return machine;
        }
        throw new IllegalStateException("机器没放上：" + pos);
    }

    private static void setSlot(AppleMachineBlockEntity machine, int slot, ItemStack stack) {
        ((IItemHandlerModifiable) machine.getItemHandler()).setStackInSlot(slot, stack);
    }

    private static ItemStack slotOf(AppleMachineBlockEntity machine, int slot) {
        return machine.getItemHandler().getStackInSlot(slot);
    }

    private static int totalIn(AppleMachineBlockEntity machine) {
        int total = 0;
        for (int slot = machine.machineKind().inputStart();
             slot < machine.machineKind().inputStart() + machine.machineKind().inputSlots(); slot++) {
            total += slotOf(machine, slot).getCount();
        }
        return total;
    }

    private static int totalOut(AppleMachineBlockEntity machine) {
        int total = 0;
        for (int slot = machine.machineKind().outputStart();
             slot < machine.machineKind().outputStart() + machine.machineKind().outputSlots(); slot++) {
            total += slotOf(machine, slot).getCount();
        }
        return total;
    }

    /** 给机器塞满基础速度/并行/能量升级，返回实际生效等级。 */
    private static String maxOut(AppleMachineBlockEntity machine, boolean speed, boolean parallel, boolean energy) {
        int upgrade = machine.machineKind().upgradeStart();
        if (speed) {
            setSlot(machine, upgrade, new ItemStack(SiaoItems.upgrade(SiaoUpgrades.SPEED).get(), 8));
        }
        if (parallel) {
            setSlot(machine, upgrade + 1, new ItemStack(SiaoItems.upgrade(SiaoUpgrades.PARALLEL).get(), 4));
        }
        if (energy) {
            setSlot(machine, upgrade + 2, new ItemStack(SiaoItems.upgrade(SiaoUpgrades.ENERGY).get(), 8));
        }
        return "speed=" + machine.statLevel(com.siao.appleoverload.upgrade.MachineStat.SPEED)
                + " parallel=" + machine.statLevel(com.siao.appleoverload.upgrade.MachineStat.PARALLEL)
                + " energy=" + machine.statLevel(com.siao.appleoverload.upgrade.MachineStat.ENERGY);
    }

    // ---- 脚本入口：世界进来后调用一次 -----------------------------------------

    private static boolean scriptBuilt;

    public static void ensureScript() {
        if (scriptBuilt) {
            return;
        }
        scriptBuilt = true;
        try {
            Files.createDirectories(OUT_DIR);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SiaoMod.LOGGER.info("[SIAO-VISUAL] 场地坐标 GUI={} AE2={} BALANCE={}", GUI_BASE, AE2_BASE, BALANCE_BASE);
        buildScript();
    }

    private static void buildScript() {
        // ==== 初始化 ====
        runAfter(1, () -> server(() -> initWorld()));
        runAfter(1, () -> {
            long window = mc.getWindow().getWindow();
            GLFW.glfwSetWindowSize(window, 1280, 720);
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
        });

        // ==== 阶段一：GUI 视觉 ====
        guiCases();

        // ==== 阶段二：AE2 真网格 ====
        ae2Cases();

        // ==== 阶段三：平衡观测 ====
        balanceCases();

        // ==== 阶段四：交付物抽查（给大肥鱼的五个重点）====
        deliverableCases();

        // ==== 收尾：等所有任务（含嵌套的截图/关界面）都跑完再写报告退出 ====
        runAfter(1, SiaoVisualTest::finaleBarrier);
    }

    /** 收尾栅栏：队里还有别的任务就自己回队尾，只剩自己时写报告并退出。 */
    private static void finaleBarrier() {
        if (TASKS.size() > 1) {
            TASKS.addLast(new Task(1, SiaoVisualTest::finaleBarrier));
            return;
        }
        server(() -> writeReport());
        runAfter(2, () -> {
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 全部完成，退出游戏");
            finished = true;
            mc.stop();
        });
    }

    // ---- 阶段一：GUI -----------------------------------------------------------

    private static void guiCases() {
        BlockPos floor = platform(GUI_BASE.getX(), GUI_BASE.getZ());

        guiCase("compressor_empty", floor, MachineKind.COMPRESSOR, machine -> {
        });
        guiCase("compressor_working", floor.offset(2, 0, 0), MachineKind.COMPRESSOR, machine -> {
            machine.fillEnergy();
            for (int slot = 0; slot < 9; slot++) {
                setSlot(machine, slot, new ItemStack(Items.IRON_INGOT));
            }
            for (int tick = 0; tick < 20; tick++) {
                machine.tick();
            }
        });
        guiCase("compressor_upgrades", floor.offset(4, 0, 0), MachineKind.COMPRESSOR, machine -> {
            machine.fillEnergy();
            maxOut(machine, true, true, true);
        });
        guiCase("enchanter_working", floor.offset(6, 0, 0), MachineKind.ENCHANTER, machine -> {
            machine.fillEnergy();
            setSlot(machine, 0, new ItemStack(SiaoItems.unit(Material.IRON, 1).get()));
            for (int tick = 0; tick < 30; tick++) {
                machine.tick();
            }
        });
        guiCase("centrifuge_working", floor.offset(8, 0, 0), MachineKind.CENTRIFUGE, machine -> {
            machine.fillEnergy();
            ItemStack enchanted = new ItemStack(SiaoItems.unit(Material.IRON, 2).get());
            com.siao.appleoverload.content.SiaoComponents.makeEnchanted(enchanted, 3);
            setSlot(machine, 0, enchanted);
            for (int tick = 0; tick < 40; tick++) {
                machine.tick();
            }
        });
        guiCase("orchard_working", floor.offset(10, 0, 0), MachineKind.ORCHARD, machine -> {
            machine.fillEnergy();
            setSlot(machine, 0, new ItemStack(Items.OAK_SAPLING, 16));
            for (int tick = 0; tick < 240; tick++) {
                machine.tick();
            }
        });
        guiCase("generator_working", floor.offset(12, 0, 0), MachineKind.GENERATOR, machine -> {
            machine.fillEnergy();
            setSlot(machine, 0, new ItemStack(Items.APPLE, 16));
            for (int tick = 0; tick < 20; tick++) {
                machine.tick();
            }
        });
    }

    /** 摆机器 → 传送到机器面前 → 开界面 → 截图 → 关界面。 */
    private static void guiCase(String name, BlockPos pos, MachineKind kind,
                                java.util.function.Consumer<AppleMachineBlockEntity> stage) {
        runAfter(1, () -> server(() -> {
            placeMachine(kind, pos);
            stage.accept((AppleMachineBlockEntity) overworld().getBlockEntity(pos));
            return "staged " + name;
        }));
        runAfter(1, () -> server(() -> {
            ServerPlayer player = serverPlayer();
            // 站到机器南边两格，离得够近容器才不会被 stillValid 自动关掉
            BlockPos view = pos.offset(0, 1, 2);
            player.teleportTo(overworld(), view.getX() + 0.5, view.getY(), view.getZ() + 0.5, 180, 0);
            return "tp";
        }));
        openGuiAndShoot(name, pos);
    }

    /** 开界面 + 截图 + 关界面。原地重试（插队头），直到界面出现或放弃。 */
    private static void openGuiAndShoot(String name, BlockPos pos) {
        // 第一次尝试走队尾（保持脚本顺序），之后的原地重试才插队头
        TASKS.addLast(new Task(4, () -> openGuiAttempt(name, pos, 12)));
    }

    private static void openGuiAttempt(String name, BlockPos pos, int attempts) {
        retryFront(4, () -> {
            if (mc.screen instanceof AppleMachineScreen) {
                check("GUI：" + name + " 界面打开", true, "");
                shot(name);
                retryFront(4, () -> {
                    if (mc.screen != null) {
                        mc.setScreen(null);
                    }
                });
                return;
            }
            if (attempts <= 0) {
                SiaoMod.LOGGER.error("[SIAO-VISUAL] {} 界面始终没出现（放弃），客户端BE={} 客户端位置={}",
                        name, mc.level.getBlockEntity(pos), mc.player == null ? "无" : mc.player.blockPosition());
                check("GUI：" + name + " 界面打开", false, "界面始终没出现");
                shot(name);
                return;
            }
            if (mc.screen != null) {
                // 别人家的界面（EMI 开发模式开局自动打开浏览器）：关掉，下一轮重试
                mc.setScreen(null);
                retryFront(4, () -> openGuiAttempt(name, pos, attempts - 1));
                return;
            }
            if (!(mc.level.getBlockEntity(pos) instanceof AppleMachineBlockEntity)) {
                // 客户端还没同步到机器方块实体（tp 后区块加载有竞态），等几 tick 再试；
                // 现在开菜单的话 fromNetwork 会炸，NeoForge 开屏失败会导致客户端断线
                retryFront(8, () -> openGuiAttempt(name, pos, attempts - 1));
                return;
            }
            // 必须走服务端开菜单（等同右键机器）：客户端直开 openMenu(provider) 发的包
            // 只带菜单类型不带坐标，服务端没法重建方块实体菜单，屏幕永远出不来
            server(() -> {
                BlockEntity be = serverPlayer().level().getBlockEntity(pos);
                if (be instanceof AppleMachineBlockEntity machine) {
                    serverPlayer().openMenu(machine, pos);
                    return "openMenu:" + name;
                }
                return "NO-BE:" + name;
            });
            retryFront(4, () -> openGuiAttempt(name, pos, attempts - 1));
        });
    }

    // ---- 阶段二：AE2 真网格 ---------------------------------------------------

    private static void ae2Cases() {
        BlockPos floor = platform(AE2_BASE.getX(), AE2_BASE.getZ());
        BlockPos controller = floor;
        BlockPos cell = floor.east();
        BlockPos cableA = floor.east(2);
        BlockPos provider = floor.east(3);
        BlockPos machinePos = floor.east(4);
        BlockPos cableB = floor.east(5);
        BlockPos machine2Pos = floor.east(6);
        BlockPos cableC = floor.east(7);

        runAfter(1, () -> server(() -> {
            ServerPlayer player = serverPlayer();
            player.teleportTo(overworld(), AE2_VIEW.getX() + 0.5, AE2_VIEW.getY(), AE2_VIEW.getZ() + 0.5, 180, 0);
            return "tp";
        }));
        runAfter(1, () -> server(() -> {
            if (!Ae2Compat.PRESENT) {
                return "SKIP: AE2 未安装";
            }
            String result = Ae2Phase.build(controller, cell, cableA, provider, machinePos, cableB, machine2Pos, cableC);
            SiaoMod.LOGGER.info("[SIAO-VISUAL] AE2 搭建 -> {}", result);
            return result;
        }));
        runAfter(60, () -> server(() -> {
            if (!Ae2Compat.PRESENT) {
                return "SKIP: AE2 未安装";
            }
            return Ae2Phase.assertGrid(controller, provider, machinePos, cableB, machine2Pos, cableC);
        }));
        runAfter(1, () -> server(() -> {
            if (!Ae2Compat.PRESENT) {
                return "SKIP: AE2 未安装";
            }
            return Ae2Phase.runE2e(machinePos, provider);
        }));
        runAfter(30, () -> server(() -> {
            if (!Ae2Compat.PRESENT) {
                return "SKIP: AE2 未安装";
            }
            return Ae2Phase.verifyE2e(machinePos, provider);
        }));
        runAfter(20, () -> shot("ae2_network_scene"));
        openGuiAndShoot("ae2_machine_gui_on_grid", machinePos);
    }

    /**
     * 所有直接引用 AE2 类型的代码都在这个嵌套类里：AE2 没装时外层类照常加载，
     * 只有真的执行到 AE2 分支才会解析这些类型。
     */
    private static final class Ae2Phase {

        private Ae2Phase() {
        }

        static String build(BlockPos controller, BlockPos cell, BlockPos cableA, BlockPos provider,
                            BlockPos machinePos, BlockPos cableB, BlockPos machine2Pos, BlockPos cableC) {
            ServerLevel level = overworld();
            setBlock(level, controller, "ae2:controller");
            setBlock(level, cell, "ae2:creative_energy_cell");
            setBlock(level, cableA, "ae2:cable_bus");
            // ⚠️ 供应器默认 PUSH_DIRECTION=ALL = 六个面都不连网格！
            // 必须指一个真实方向：往东推料给机器，其余面连网格。
            setBlock(level, provider, "ae2:pattern_provider", state -> state.setValue(
                    appeng.block.crafting.PatternProviderBlock.PUSH_DIRECTION,
                    appeng.block.crafting.PushDirection.fromDirection(Direction.EAST)));
            placeMachine(MachineKind.COMPRESSOR, machinePos);
            setBlock(level, cableB, "ae2:cable_bus");
            placeMachine(MachineKind.ENCHANTER, machine2Pos);
            setBlock(level, cableC, "ae2:cable_bus");

            // 南侧回线：8 截线缆从东端绕回控制器脚下，形成闭环
            List<BlockPos> cableSpots = new ArrayList<>();
            cableSpots.add(cableA);
            cableSpots.add(cableB);
            cableSpots.add(cableC);
            for (int x = 0; x <= 7; x++) {
                cableSpots.add(controller.offset(x, 0, 1));
            }
            // 先铺空 cable_bus 方块（这样放部件时不会触发玩家距离检查），再放线缆部件
            for (BlockPos spot : cableSpots) {
                setBlock(level, spot, "ae2:cable_bus");
            }
            ServerPlayer player = serverPlayer();
            var cableItem = BuiltInRegistries.ITEM.get(id("ae2:white_glass_cable"));
            if (!(cableItem instanceof appeng.api.parts.IPartItem<?> partItem)) {
                return "FAIL: white_glass_cable 不是 IPartItem";
            }
            for (BlockPos spot : cableSpots) {
                var part = appeng.parts.PartPlacement.placePart(player, level, partItem,
                        net.minecraft.core.component.DataComponentMap.EMPTY, spot, Direction.UP);
                if (part == null) {
                    return "FAIL: 线缆部件没放上 " + spot;
                }
            }
            SiaoMod.LOGGER.info("[SIAO-VISUAL] AE2 闭环：{}→{}→{}→{}→机器→{}→机器2→{} + 南侧 8 截回线",
                    controller, cell, cableA, provider, cableB, cableC);
            SiaoMod.LOGGER.info("[SIAO-VISUAL] AE2 部件刚放完（总线的 onReady 可能还没跑）：");
            dumpAe2State(level, controller, cell, provider, machinePos, cableB, machine2Pos, cableC, cableSpots);
            ((AppleMachineBlockEntity) level.getBlockEntity(machinePos)).fillEnergy();
            return "built";
        }

        /**
         * 一次看全拓扑：总线（部件/中心节点/连接）、原生 AE2 方块（getMainNode）、
         * 我们的机器（IN_WORLD_GRID_NODE_HOST 能力）。
         *
         * <p>⚠️ 原生 AE2 方块<b>没有</b>注册 {@code IN_WORLD_GRID_NODE_HOST} 能力，
         * 用 {@code getExposedNode} 看它们永远是 null —— 那是诊断工具用错了，
         * 不代表它们没节点。正确入口是 {@code AENetworkedBlockEntity.getMainNode()}。</p>
         */
        private static void dumpAe2State(ServerLevel level, BlockPos controller, BlockPos cell, BlockPos provider,
                                         BlockPos machinePos, BlockPos cableB, BlockPos machine2Pos, BlockPos cableC,
                                         List<BlockPos> cableSpots) {
            for (BlockPos spot : cableSpots) {
                BlockEntity be = level.getBlockEntity(spot);
                if (!(be instanceof appeng.blockentity.networking.CableBusBlockEntity bus)) {
                    SiaoMod.LOGGER.info("[SIAO-VISUAL]   总线 {} BE={}", spot,
                            be == null ? "无" : be.getClass().getSimpleName());
                    continue;
                }
                var sidePart = bus.getPart(Direction.UP);
                var node = bus.getGridNode(Direction.UP);
                SiaoMod.LOGGER.info("[SIAO-VISUAL]   总线 {} 空={} 部件UP={} 节点={} 连接数={} 网格={}",
                        spot, bus.isEmpty(),
                        sidePart == null ? "无" : sidePart.getClass().getSimpleName(),
                        node == null ? "无" : node.getClass().getSimpleName(),
                        node == null ? 0 : node.getConnections().size(),
                        node != null && node.getGrid() != null ? node.getGrid().toString() : "无");
            }
            for (BlockPos pos : new BlockPos[]{controller, cell, provider}) {
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof appeng.blockentity.grid.AENetworkedBlockEntity ae)) {
                    SiaoMod.LOGGER.info("[SIAO-VISUAL]   {} 不是 AENetworkedBlockEntity：{}", pos,
                            be == null ? "无BE" : be.getClass().getSimpleName());
                    continue;
                }
                var node = ae.getMainNode().getNode();
                SiaoMod.LOGGER.info("[SIAO-VISUAL]   {} 节点={} 连接数={} 网格={}",
                        be.getClass().getSimpleName(),
                        node == null ? "无" : node.getClass().getSimpleName(),
                        node == null ? 0 : node.getConnections().size(),
                        node != null && node.getGrid() != null ? node.getGrid().toString() : "无");
            }
            for (BlockPos pos : new BlockPos[]{machinePos, machine2Pos}) {
                var node = appeng.api.networking.GridHelper.getExposedNode(level, pos, Direction.EAST);
                SiaoMod.LOGGER.info("[SIAO-VISUAL]   机器 {} 节点={} 连接数={} inWorld连接={} 网格={}",
                        pos, node == null ? "无" : node.getClass().getSimpleName(),
                        node == null ? 0 : node.getConnections().size(),
                        node == null ? "无" : node.getInWorldConnections().keySet(),
                        node != null && node.getGrid() != null ? node.getGrid().toString() : "无");
            }
        }

        private static void setBlock(ServerLevel level, BlockPos pos, String blockId) {
            setBlock(level, pos, blockId, state -> state);
        }

        private static void setBlock(ServerLevel level, BlockPos pos, String blockId,
                                     java.util.function.UnaryOperator<BlockState> adjust) {
            var block = BuiltInRegistries.BLOCK.get(id(blockId));
            level.setBlockAndUpdate(pos, adjust.apply(block.defaultBlockState()));
        }

        static String assertGrid(BlockPos controller, BlockPos providerPos, BlockPos machinePos,
                                 BlockPos cableB, BlockPos machine2Pos, BlockPos cableC) {
            ServerLevel level = overworld();
            // 机器东侧是线缆，节点应该暴露在 EAST
            var node = appeng.api.networking.GridHelper.getExposedNode(level, machinePos, Direction.EAST);
            check("AE2：机器节点在真网格上被找到", node != null,
                    node == null ? "getExposedNode 返回 null" : node.toString());
            if (node == null) {
                return "no node";
            }
            check("AE2：节点有电", node.isPowered(), "isPowered=false");
            check("AE2：节点在线", node.isOnline(), "isOnline=false");
            var grid = node.getGrid();
            check("AE2：节点属于一个网格", grid != null, grid == null ? "getGrid=null" : grid.toString());
            if (grid == null) {
                return "no grid";
            }
            check("AE2：网格供电正常", grid.getEnergyService().isNetworkPowered(),
                    "energyService 认为网络没电");
            double idle = grid.getEnergyService().getIdlePowerUsage();
            SiaoMod.LOGGER.info("[SIAO-VISUAL] AE2 网格空闲功耗 = {} AE/t（两台机器应贡献 1.0）", idle);
            check("AE2：空闲功耗包含两台机器的 1.0 AE/t", idle >= 1.0, idle + " AE/t");

            var node2 = appeng.api.networking.GridHelper.getExposedNode(level, machine2Pos, Direction.WEST);
            check("AE2：第二台机器也上了网", node2 != null && node2.getGrid() == grid,
                    node2 == null ? "无节点" : "不同网格");
            var providerNode = appeng.api.networking.GridHelper.getExposedNode(level, providerPos, Direction.WEST);
            check("AE2：样板供应器在同一网格", providerNode != null && providerNode.getGrid() == grid,
                    "供应器不在网格上");

            // 拓扑诊断：节点存在性 + 连接数 + 所属网格，一次看全（60 tick 后总线应已 onReady）
            SiaoMod.LOGGER.info("[SIAO-VISUAL] AE2 拓扑诊断（断言时点）：");
            List<BlockPos> allSpots = new ArrayList<>();
            for (BlockPos spot : new BlockPos[]{controller.east(2), cableB, controller.east(7)}) {
                allSpots.add(spot);
            }
            for (int x = 0; x <= 7; x++) {
                allSpots.add(controller.offset(x, 0, 1));
            }
            dumpAe2State(level, controller, controller.east(), providerPos,
                    machinePos, cableB, machine2Pos, cableC, allSpots);

            // ME_STORAGE 是否被网格存储服务直接挂载（观察项，不判对错）
            AppleMachineBlockEntity machine = (AppleMachineBlockEntity) level.getBlockEntity(machinePos);
            setSlot(machine, machine.machineKind().outputStart(), new ItemStack(Items.APPLE));
            var counter = new appeng.api.stacks.KeyCounter();
            grid.getStorageService().getInventory().getAvailableStacks(counter);
            boolean visible = !counter.isEmpty()
                    && counter.get(appeng.api.stacks.AEItemKey.of(new ItemStack(Items.APPLE))) > 0;
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 机器输出槽的苹果对网格存储{}", visible ? "可见（ME_STORAGE 被挂载）" : "不可见（仅能力层，符合预期）");
            setSlot(machine, machine.machineKind().outputStart(), ItemStack.EMPTY);
            return "grid ok";
        }

        static String runE2e(BlockPos machinePos, BlockPos providerPos) {
            ServerLevel level = overworld();
            MinecraftServer server = mc.getSingleplayerServer();
            // 用原版「9 铁 → 铁块」的合成配方编码一条真样板：供应器只看输入输出
            var holder = server.getRecipeManager().byKey(id("minecraft:iron_block")).orElse(null);
            if (holder == null || !(holder.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe)) {
                return "FAIL: 找不到 minecraft:iron_block 配方";
            }
            ItemStack patternStack = new ItemStack(BuiltInRegistries.ITEM.get(id("ae2:crafting_pattern")));
            ItemStack[] nineIron = new ItemStack[9];
            for (int i = 0; i < 9; i++) {
                nineIron[i] = new ItemStack(Items.IRON_INGOT);
            }
            @SuppressWarnings("unchecked")
            var craftingHolder = (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>) (Object) holder;
            appeng.crafting.pattern.AECraftingPattern.encode(patternStack, craftingHolder, nineIron,
                    new ItemStack(Items.IRON_BLOCK), false, false);

            var providerBe = level.getBlockEntity(providerPos);
            if (!(providerBe instanceof appeng.blockentity.crafting.PatternProviderBlockEntity providerBlock)) {
                return "FAIL: 供应器方块实体类型不对：" + providerBe;
            }
            var logic = providerBlock.getLogic();
            logic.getPatternInv().insertItem(0, patternStack, false);
            logic.updatePatterns();
            var patterns = logic.getAvailablePatterns();
            if (patterns.isEmpty()) {
                return "FAIL: 样板解码失败，getAvailablePatterns 为空";
            }
            var counter = new appeng.api.stacks.KeyCounter();
            counter.add(appeng.api.stacks.AEItemKey.of(new ItemStack(Items.IRON_INGOT)), 9);
            boolean pushed = logic.pushPattern(patterns.get(0), new appeng.api.stacks.KeyCounter[]{counter});
            SiaoMod.LOGGER.info("[SIAO-VISUAL] AE2 供应器 pushPattern -> {}", pushed);
            e2ePushed = pushed;
            check("AE2 E2E：pushPattern 推料成功", pushed,
                    pushed ? "9 个铁推给了机器" : "pushPattern 被拒（供应器不在有电的网格上就会这样）");
            if (!pushed) {
                return "FAIL: pushPattern 被拒";
            }
            // 机器整批吃下后开工
            AppleMachineBlockEntity machine = (AppleMachineBlockEntity) level.getBlockEntity(machinePos);
            machine.fillEnergy();
            for (int tick = 0; tick < 60; tick++) {
                machine.tick();
            }
            return "e2e pushed";
        }

        private static boolean e2ePushed;

        static String verifyE2e(BlockPos machinePos, BlockPos providerPos) {
            ServerLevel level = overworld();
            AppleMachineBlockEntity machine = (AppleMachineBlockEntity) level.getBlockEntity(machinePos);
            var providerBe = level.getBlockEntity(providerPos);
            var logic = ((appeng.blockentity.crafting.PatternProviderBlockEntity) providerBe).getLogic();

            if (!e2ePushed) {
                // 推料没成功，后面的「输入被吃完/产物产生」都会是空转 —— 跳过，只留手工基线
                SiaoMod.LOGGER.info("[SIAO-VISUAL] AE2 E2E 推料未成功，跳过空转断言（只跑手工基线）");
            } else {

            // 机器输入槽分布 —— 这里会暴露「整批推料只扣 1 个」的问题
            StringBuilder distribution = new StringBuilder();
            for (int slot = 0; slot < 9; slot++) {
                distribution.append(slotOf(machine, slot).getCount()).append(' ');
            }
            SiaoMod.LOGGER.info("[SIAO-VISUAL] AE2 推料后输入槽分布：[ {}]", distribution.toString().trim());
            int leftIn = totalIn(machine);
            check("AE2 E2E：输入被吃完（9 个铁都进了料）", leftIn == 0,
                    "还剩 " + leftIn + " 个（分布 " + distribution.toString().trim() + "）");

            // 产物去向：供应器回收入口 或 网格存储
            long unitInGrid = 0;
            var node = appeng.api.networking.GridHelper.getExposedNode(level, machinePos, Direction.EAST);
            if (node != null && node.getGrid() != null) {
                var gridCounter = new appeng.api.stacks.KeyCounter();
                node.getGrid().getStorageService().getInventory().getAvailableStacks(gridCounter);
                unitInGrid = gridCounter.get(appeng.api.stacks.AEItemKey.of(
                        new ItemStack(SiaoItems.unit(Material.IRON, 1).get())));
            }
            var returnCounter = new appeng.api.stacks.KeyCounter();
            logic.getReturnInv().getAvailableStacks(returnCounter);
            long unitInReturn = returnCounter.get(appeng.api.stacks.AEItemKey.of(
                    new ItemStack(SiaoItems.unit(Material.IRON, 1).get())));
            int unitInMachine = 0;
            for (int slot = machine.machineKind().outputStart();
                 slot < machine.machineKind().outputStart() + machine.machineKind().outputSlots(); slot++) {
                if (slotOf(machine, slot).is(SiaoItems.unit(Material.IRON, 1).get())) {
                    unitInMachine += slotOf(machine, slot).getCount();
                }
            }
            long total = unitInGrid + unitInReturn + unitInMachine;
            SiaoMod.LOGGER.info("[SIAO-VISUAL] AE2 产物去向：网格 {} / 供应器回收 {} / 机器输出 {}",
                    unitInGrid, unitInReturn, unitInMachine);
            check("AE2 E2E：产物（一重压缩铁锭）确实产生", total >= 1,
                    "三处都没有产物");
            check("AE2 E2E：产物回到了网格侧（供应器回收或网格存储）", unitInGrid + unitInReturn >= 1,
                    "产物只留在机器里");
            }

            // 对照基线：手工逐格摆放（1 格 1 个）应该干净吃完
            try {
                AppleMachineBlockEntity baseline = placeMachine(MachineKind.COMPRESSOR,
                        machinePos.south(3));
                baseline.fillEnergy();
                for (int slot = 0; slot < 9; slot++) {
                    setSlot(baseline, slot, new ItemStack(Items.IRON_INGOT));
                }
                for (int tick = 0; tick < 60; tick++) {
                    baseline.tick();
                }
                check("AE2 对照：手工逐格摆放能正常压完", totalIn(baseline) == 0 && totalOut(baseline) == 1,
                        "剩余输入 " + totalIn(baseline) + " / 输出 " + totalOut(baseline));
            } catch (Exception e) {
                check("AE2 对照：手工逐格摆放能正常压完", false, "基线机器搭建失败：" + e);
            }
            return "e2e verified";
        }
    }

    // ---- 阶段三：平衡观测 -----------------------------------------------------

    private static void balanceCases() {
        BlockPos floor = platform(BALANCE_BASE.getX(), BALANCE_BASE.getZ());
        BlockPos orchardBase = floor;
        BlockPos orchardMax = floor.offset(2, 0, 0);
        BlockPos orchardMaxChestE = orchardMax.east();
        BlockPos orchardMaxChestN = orchardMax.north();
        BlockPos loopPlain = floor.offset(4, 0, 0);
        BlockPos loopPlainGen = floor.offset(5, 0, 0);
        BlockPos loopEco = floor.offset(7, 0, 0);
        BlockPos loopEcoGen = floor.offset(8, 0, 0);
        BlockPos coalPress = floor.offset(10, 0, 0);

        runAfter(1, () -> server(() -> {
            // 基础农场：1 树苗 60 tick，8 苹果一轮
            AppleMachineBlockEntity orchard = placeMachine(MachineKind.ORCHARD, orchardBase);
            orchard.fillEnergy();
            setSlot(orchard, 0, new ItemStack(Items.OAK_SAPLING, 32));
            for (int tick = 0; tick < 1200; tick++) {
                orchard.tick();
            }
            int apples = totalOut(orchard);
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 基础农场：手动 1200 tick 产出 {} 苹果（60 tick/个）", apples);
            check("平衡：基础农场 1200 tick = 20 苹果（60 tick/个）", apples == 20, apples + " 苹果");

            // 满配农场 · 无抽出：会卡死在 3×64 的输出上限
            AppleMachineBlockEntity fast = placeMachine(MachineKind.ORCHARD, orchardMax);
            String levels = maxOut(fast, true, true, false);
            fast.fillEnergy();
            // 树苗只能合法堆叠到 64（>99 的堆在存档时会被 ItemStack 编码直接拒绝，
            // 方块实体整块丢档）。640 的需求靠循环里每 50 tick 补一次 64 满足
            setSlot(fast, 0, new ItemStack(Items.OAK_SAPLING, 64));
            for (int tick = 0; tick < 600; tick++) {
                if (tick % 50 == 0) {
                    setSlot(fast, 0, new ItemStack(Items.OAK_SAPLING, 64));
                }
                if (tick % 100 == 0) {
                    fast.fillEnergy();
                }
                fast.tick();
            }
            int stalled = totalOut(fast);
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 满配农场（{}）无抽出：600 tick 后输出 {} 苹果（卡输出上限）",
                    levels, stalled);
            check("平衡：满配农场无抽出时卡在 192（3×64 输出上限）", stalled == 192,
                    stalled + " 苹果");

            // 满配农场 · 旁边放两个箱子（自动输出）：速率应该 ≈5 苹果/tick。
            // 一个箱子只装得下 27×64=1728，加上机器 192 正好 1920 —— 上一轮就是这么卡住
            // 导致误报。两个箱子（3456）才装得下 600 tick × 5 的产出。
            overworld().setBlockAndUpdate(orchardMaxChestE, Blocks.CHEST.defaultBlockState());
            overworld().setBlockAndUpdate(orchardMaxChestN, Blocks.CHEST.defaultBlockState());
            for (int tick = 0; tick < 600; tick++) {
                if (tick % 50 == 0) {
                    setSlot(fast, 0, new ItemStack(Items.OAK_SAPLING, 64));
                }
                if (tick % 100 == 0) {
                    fast.fillEnergy();
                }
                fast.tick();
            }
            int chestApples = countApplesIn(overworld(), orchardMaxChestE)
                    + countApplesIn(overworld(), orchardMaxChestN);
            int fastApples = totalOut(fast) + chestApples;
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 满配农场+双箱子 600 tick：机器输出 {} + 箱子 {} = {} 苹果",
                    totalOut(fast), chestApples, fastApples);
            check("平衡：满配农场速率 ≥ 4 苹果/tick（有自动输出时）", fastApples >= 3100,
                    fastApples + " 苹果 / 600 tick");
            if (stalled == 192) {
                finding("满配农场没有抽出时会卡死在 192 苹果（3×64 输出槽满），生产完全停止 —— 需要管道/箱子持续抽走");
            }

            // 裸配闭环：农场 300 FE/苹果 vs 苹果 200 FE，净流出 100 FE/苹果
            AppleMachineBlockEntity plain = placeMachine(MachineKind.ORCHARD, loopPlain);
            AppleMachineBlockEntity plainGen = placeMachine(MachineKind.GENERATOR, loopPlainGen);
            plain.fillEnergy();
            setSlot(plain, 0, new ItemStack(Items.OAK_SAPLING, 64));
            int plainStart = plain.energyStored();
            for (int tick = 0; tick < 3000; tick++) {
                plain.tick();
                plainGen.tick();
            }
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 裸农场+发电机 3000 tick：农场能量 {}→{}（净 {} FE）",
                    plainStart, plain.energyStored(), plain.energyStored() - plainStart);
            check("平衡：裸配闭环能量净流出（300 产 1 苹果 vs 200 FE/苹果）",
                    plain.energyStored() < plainStart,
                    plainStart + "→" + plain.energyStored());

            // 节能闭环：8 能量升级 51 FE/次 + 满速满并行，净流入约 149 FE/苹果。
            // ⚠️ 抽能不能走 IEnergyStorage.extractEnergy：机器的 maxExtract=0（机器不是
            // 电源），那是个静默 no-op —— 上一轮抽了个寂寞，起点还是满的，被缓冲上限
            // 盖住什么都看不出来。这里反射调内部 consume 真正把电抽到 1000。
            AppleMachineBlockEntity eco = placeMachine(MachineKind.ORCHARD, loopEco);
            AppleMachineBlockEntity ecoGen = placeMachine(MachineKind.GENERATOR, loopEcoGen);
            maxOut(eco, true, true, true);
            maxOut(ecoGen, true, true, false);
            eco.fillEnergy();
            try {
                var consume = eco.getEnergyStorage().getClass().getDeclaredMethod("consume", int.class);
                consume.setAccessible(true);
                consume.invoke(eco.getEnergyStorage(), 199_000);
            } catch (Exception e) {
                check("平衡：8 能量升级后闭环能量净流入", false, "反射抽能失败：" + e);
                return null;
            }
            setSlot(eco, 0, new ItemStack(Items.OAK_SAPLING, 64));
            setSlot(ecoGen, 0, new ItemStack(Items.APPLE, 16));
            int ecoStart = eco.energyStored();
            int ecoCost = eco.energyPerOperation();
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 能量升级后单次加工 {} FE", ecoCost);
            for (int tick = 0; tick < 200; tick++) {
                if (tick % 50 == 0) {
                    setSlot(eco, 0, new ItemStack(Items.OAK_SAPLING, 64));
                }
                eco.tick();
                ecoGen.tick();
            }
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 节能农场+发电机 200 tick：农场能量 {}→{}（单次 {} FE，净 {}）",
                    ecoStart, eco.energyStored(), ecoCost, eco.energyStored() - ecoStart);
            check("平衡：8 能量升级后闭环能量净流入", eco.energyStored() > ecoStart + 50_000,
                    ecoStart + "→" + eco.energyStored());

            // 交付物抽查：9 个一重压缩煤 → 二重压缩煤，不是煤块
            AppleMachineBlockEntity press = placeMachine(MachineKind.COMPRESSOR, coalPress);
            press.fillEnergy();
            ItemStack coalUnit = new ItemStack(SiaoItems.unit(Material.COAL, 1).get());
            for (int slot = 0; slot < 9; slot++) {
                setSlot(press, slot, coalUnit.copy());
            }
            for (int tick = 0; tick < 60; tick++) {
                press.tick();
            }
            boolean rightOut = slotOf(press, press.machineKind().outputStart())
                    .is(SiaoItems.unit(Material.COAL, 2).get());
            check("交付物：压缩机 9 个一重煤 → 二重煤（不是煤块）", rightOut,
                    String.valueOf(slotOf(press, press.machineKind().outputStart()).getItem()));

            // 九重目标的时间量级
            long applesForTier9 = 1;
            for (int i = 0; i < 9; i++) {
                applesForTier9 *= 9;
            }
            long baseTicks = applesForTier9 * 60L;
            double baseYears = baseTicks / 20.0 / 3600.0 / 24.0 / 365.25;
            double maxedDays = applesForTier9 / 5.0 / 20.0 / 3600.0 / 24.0;
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 九重苹果单位需要 {} 个苹果", applesForTier9);
            SiaoMod.LOGGER.info("[SIAO-VISUAL]   单台基础农场：{} tick ≈ {} 年（真实时间）",
                    baseTicks, String.format("%.1f", baseYears));
            SiaoMod.LOGGER.info("[SIAO-VISUAL]   单台满配农场（5 苹果/tick）：约 {} 天",
                    String.format("%.1f", maxedDays));
            SiaoMod.LOGGER.info("[SIAO-VISUAL]   树苗消耗：{} 棵（每 8 苹果 1 棵）", applesForTier9 / 8);
            SiaoMod.LOGGER.info("[SIAO-VISUAL]   基础能耗：{} FE（300 FE/苹果，仅农场）", applesForTier9 * 300L);
            return "balance done";
        }));
        runAfter(1, () -> server(() -> {
            ServerPlayer player = serverPlayer();
            player.teleportTo(overworld(), BALANCE_VIEW.getX() + 0.5, BALANCE_VIEW.getY(),
                    BALANCE_VIEW.getZ() + 0.5, 180, 0);
            return "tp";
        }));
        runAfter(20, () -> shot("balance_farm_scene"));
    }

    /** 数一下箱子里有多少苹果（-1 = 位置没有物品能力）。 */
    private static int countApplesIn(ServerLevel level, BlockPos pos) {
        var handler = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                pos, Direction.WEST);
        if (handler == null) {
            return -1;
        }
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(Items.APPLE)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // ---- 阶段四：交付物抽查 ---------------------------------------------------

    private static void deliverableCases() {
        // 创造栏每页数量
        runAfter(1, () -> server(() -> {
            StringBuilder counts = new StringBuilder();
            int total = 0;
            for (String tabId : new String[]{"main", "blocks", "cut", "polished", "grate", "chiseled", "stairs"}) {
                CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.get(id("siao:" + tabId));
                if (tab == null) {
                    counts.append(tabId).append("=缺失 ");
                    continue;
                }
                int size = tab.getDisplayItems().size();
                total += size;
                counts.append(tabId).append('=').append(size).append(' ');
            }
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 创造栏分页：{}（合计 {}）", counts.toString().trim(), total);
            check("交付物：七个页签全部非空", counts.indexOf("=0 ") < 0 && counts.indexOf("缺失") < 0,
                    counts.toString());
            return counts.toString();
        }));

        // 创造栏翻页视觉：main / blocks / stairs 三页
        creativeShot("main", "siao:main");
        creativeShot("blocks", "siao:blocks");
        creativeShot("stairs", "siao:stairs");

        // 楼梯/台阶命名：zh_cn 里名字必须以「楼梯」「台阶」结尾
        runAfter(1, () -> {
            List<String> bad = new ArrayList<>();
            try {
                Path langPath = Path.of("../src/main/resources/assets/siao/lang/zh_cn.json");
                if (!Files.exists(langPath)) {
                    langPath = Path.of("src/main/resources/assets/siao/lang/zh_cn.json");
                }
                String json = Files.readString(langPath, StandardCharsets.UTF_8);
                var root = new com.google.gson.JsonParser().parse(json).getAsJsonObject();
                for (var entry : root.entrySet()) {
                    String key = entry.getKey();
                    if (!key.startsWith("block.siao.")) {
                        continue;
                    }
                    String stem = key.endsWith("_waxed")
                            ? key.substring(0, key.length() - "_waxed".length())
                            : key;
                    String value = entry.getValue().getAsString();
                    if (stem.endsWith("_stairs") && !value.endsWith("楼梯")) {
                        bad.add(key + "=" + value);
                    } else if (stem.endsWith("_slab") && !value.endsWith("台阶")) {
                        bad.add(key + "=" + value);
                    }
                }
            } catch (Exception e) {
                check("交付物：楼梯/台阶命名检查", false, "读 zh_cn.json 失败：" + e);
                return;
            }
            check("交付物：楼梯以「楼梯」结尾、台阶以「台阶」结尾（" + bad.size() + " 个问题）",
                    bad.isEmpty(), bad.isEmpty() ? "" : bad.subList(0, Math.min(3, bad.size())).toString());
        });

        // EMI：搜「压缩煤」+ 压缩（机器）分类。
        // 创造栏三连的关屏任务会因截图 hold 产生随机漂移，固定延迟躲不开它，
        // 所以每个截图任务自己重开目标屏再拍：pendingShot 挂起期间队列整体阻塞，
        // 开屏与抓帧之间不可能插入任何任务（含创造栏的关屏）。
        // displayAllRecipes/displayRecipeCategory 会自己开 RecipeScreen（先垫 InventoryScreen）
        runAfter(40, () -> {
            if (!net.neoforged.fml.ModList.get().isLoaded("emi")) {
                check("交付物：EMI 有「压缩（机器）」分类", false, "EMI 未安装");
                return;
            }
            if (mc.screen != null) {
                mc.setScreen(null);
            }
            runAfter(3, () -> {
                try {
                    runAfter(12, () -> {
                        try {
                            if (mc.screen != null) {
                                mc.setScreen(null);
                            }
                            dev.emi.emi.api.EmiApi.setSearchText("压缩煤");
                            dev.emi.emi.api.EmiApi.displayAllRecipes();
                            shot("emi_search_compressed_coal", 6);
                        } catch (Throwable t) {
                            check("交付物：EMI 有「压缩（机器）」分类", false, t.toString());
                        }
                    });
                    runAfter(14, () -> {
                        try {
                            var target = id("siao:compression");
                            var category = dev.emi.emi.api.EmiApi.getRecipeManager().getCategories().stream()
                                    .filter(c -> c.getId().equals(target))
                                    .findFirst().orElse(null);
                            check("交付物：EMI 有「压缩（机器）」分类", category != null,
                                    category == null ? "找不到 siao:compression 分类" : "siao:compression");
                        } catch (Throwable t) {
                            check("交付物：EMI 有「压缩（机器）」分类", false, t.toString());
                        }
                    });
                    runAfter(26, () -> {
                        try {
                            var category = dev.emi.emi.api.EmiApi.getRecipeManager().getCategories().stream()
                                    .filter(c -> c.getId().equals(id("siao:compression")))
                                    .findFirst().orElse(null);
                            if (category != null) {
                                if (mc.screen != null) {
                                    mc.setScreen(null);
                                }
                                dev.emi.emi.api.EmiApi.displayRecipeCategory(category);
                            }
                            shot("emi_compression_category", 6);
                        } catch (Throwable t) {
                            check("交付物：EMI 有「压缩（机器）」分类", false, t.toString());
                        }
                    });
                    runAfter(28, () -> {
                        if (mc.screen != null) {
                            mc.setScreen(null);
                        }
                    });
                } catch (Throwable t) {
                    check("交付物：EMI 有「压缩（机器）」分类", false, t.toString());
                }
            });
        });
    }

    /** 用反射选中创造栏页签再开屏幕（selectedTab 是私有静态字段）。 */
    private static void creativeShot(String name, String tabId) {
        runAfter(1, () -> {
            try {
                CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.get(id(tabId));
                if (tab == null) {
                    check("交付物：创造栏 " + tabId, false, "页签不存在");
                    return;
                }
                Field field = CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                field.setAccessible(true);
                field.set(null, tab);
                LocalPlayer player = mc.player;
                mc.setScreen(new CreativeModeInventoryScreen(player,
                        FeatureFlags.REGISTRY.allFlags(), player.hasPermissions(2)));
                runAfter(12, () -> {
                    shot("creative_" + name);
                    runAfter(4, () -> {
                        if (mc.screen != null) {
                            mc.setScreen(null);
                        }
                    });
                });
            } catch (Throwable t) {
                check("交付物：创造栏 " + name, false, t.toString());
            }
        });
    }

    // ---- 初始化与报告 ---------------------------------------------------------

    private static String initWorld() {
        MinecraftServer server = mc.getSingleplayerServer();
        ServerLevel level = server.overworld();
        ServerPlayer player = serverPlayer();
        if (player == null) {
            return "WAIT: 玩家还没进服务器";
        }
        player.setGameMode(GameType.CREATIVE);
        if (player.isDeadOrDying()) {
            player.respawn();
        }
        level.setWeatherParameters(6000, 0, false, false);
        level.setDayTime(6000);
        player.teleportTo(level, GUI_VIEW.getX() + 0.5, GUI_VIEW.getY(), GUI_VIEW.getZ() + 0.5, 180, 0);
        return "world ready";
    }

    private static String writeReport() {
        try {
            Files.createDirectories(OUT_DIR);
            StringBuilder report = new StringBuilder();
            report.append("# SIAO 视觉测试报告\n\n");
            report.append("生成时间：").append(java.time.LocalDateTime.now()).append("\n\n");
            report.append("## 断言结果\n\n");
            int pass = 0;
            int fail = 0;
            for (String line : CHECKS) {
                if (line.startsWith("PASS")) {
                    pass++;
                } else {
                    fail++;
                }
                report.append("- ").append(line).append('\n');
            }
            report.append("\n**通过 ").append(pass).append(" / 失败 ").append(fail).append("**\n\n");
            report.append("## 截图\n\n");
            try (var stream = Files.list(OUT_DIR)) {
                stream.filter(p -> p.toString().endsWith(".png"))
                        .sorted()
                        .forEach(p -> report.append("- `").append(p.getFileName()).append("`\n"));
            }
            report.append("\n## 值得注意的发现\n\n");
            if (FINDINGS.isEmpty()) {
                report.append("（无）\n");
            } else {
                for (String text : FINDINGS) {
                    report.append("- ").append(text).append('\n');
                }
            }
            report.append("\n## 平衡体感数字\n\n");
            report.append("见上方日志 [SIAO-VISUAL] 平衡 小节；核心数字：\n");
            report.append("- 单台基础农场：60 tick/苹果，九重苹果单位（9^9 = 387,420,489 个苹果）需 36.8 年\n");
            report.append("- 单台满配农场（速度8+并行4，5 苹果/tick）：约 44.8 天，另需 48,427,561 棵树苗\n");
            report.append("- 裸配农场+发电机闭环能量为负（300 FE 产 1 苹果 vs 苹果 200 FE）；8 能量升级后转正\n");
            Files.writeString(OUT_DIR.resolve("report.md"), report, StandardCharsets.UTF_8);
            SiaoMod.LOGGER.info("[SIAO-VISUAL] 报告已写入 {}", OUT_DIR.resolve("report.md").toAbsolutePath());
            return "report written";
        } catch (IOException e) {
            SiaoMod.LOGGER.error("[SIAO-VISUAL] 写报告失败", e);
            return "report failed: " + e;
        }
    }

    // ---- 启动钩子：玩家进世界后构建脚本 --------------------------------------

    static {
        NeoForge.EVENT_BUS.addListener(SiaoVisualTest::onFirstWorldTick);
    }

    private static void onFirstWorldTick(ClientTickEvent.Post event) {
        if (scriptBuilt || Minecraft.getInstance().player == null
                || Minecraft.getInstance().getSingleplayerServer() == null) {
            return;
        }
        ensureScript();
    }
}
