package com.siao.appleoverload.machine;

import com.siao.appleoverload.SiaoMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 四台机器共用的界面。
 *
 * <p>能量条和进度条用 {@link GuiGraphics#fill} 直接画色块，
 * 不走贴图精灵 —— 少一处 UV 对不齐的坑，也少两张图。</p>
 *
 * <p>接口配置**不在界面里**：用苹果配置器右键机器的某个面（Mek 式）。
 * 界面上原本那个「切你站的这面」的按钮已经去掉了 —— 反馈太弱、
 * 而且改哪个面取决于站位，容易把人绕晕。</p>
 */
public class AppleMachineScreen extends AbstractContainerScreen<AppleMachineMenu> {

    private static final ResourceLocation BACKGROUND =
            ResourceLocation.fromNamespaceAndPath(SiaoMod.MOD_ID, "textures/gui/apple_machine.png");

    private static final int ENERGY_COLOR = 0xFF3FBF5F;
    private static final int PROGRESS_COLOR = 0xFFFFC64B;
    private static final int BAR_TRACK = 0xFF2A2A2A;

    public AppleMachineScreen(AppleMachineMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = MachineLayout.WIDTH;
        this.imageHeight = MachineLayout.HEIGHT;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = MachineLayout.PLAYER_X;
        this.inventoryLabelY = MachineLayout.PLAYER_Y - 11;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.blit(BACKGROUND, x, y, 0, 0, imageWidth, imageHeight);

        // 能量条（自下而上）
        int barX = x + MachineLayout.ENERGY_X + 1;
        int barY = y + MachineLayout.ENERGY_Y + 1;
        int barWidth = MachineLayout.ENERGY_WIDTH - 2;
        int barHeight = MachineLayout.ENERGY_HEIGHT - 2;
        graphics.fill(barX, barY, barX + barWidth, barY + barHeight, BAR_TRACK);

        int capacity = Math.max(1, menu.energyCapacity());
        // 用 long 算：能量 × 条高 在 energy 超过约 4200 万时会 int 溢出成负数，
        // 结果就是「明明有电，条却是空的」
        int filled = (int) Math.max(0L, Math.min((long) barHeight,
                (long) menu.energyStored() * barHeight / capacity));
        if (filled > 0) {
            graphics.fill(barX, barY + barHeight - filled, barX + barWidth, barY + barHeight, ENERGY_COLOR);
        }

        // 进度条（从左到右）
        int maxProgress = Math.max(1, menu.maxProgress());
        int progressWidth = (int) Math.max(0L, Math.min((long) MachineLayout.ARROW_WIDTH,
                (long) menu.progress() * MachineLayout.ARROW_WIDTH / maxProgress));
        int progressX = x + MachineLayout.ARROW_X;
        int progressY = y + MachineLayout.ARROW_Y;
        graphics.fill(progressX, progressY, progressX + MachineLayout.ARROW_WIDTH,
                progressY + MachineLayout.ARROW_HEIGHT, BAR_TRACK);
        if (progressWidth > 0) {
            graphics.fill(progressX, progressY, progressX + progressWidth,
                    progressY + MachineLayout.ARROW_HEIGHT, PROGRESS_COLOR);
        }
    }
}
