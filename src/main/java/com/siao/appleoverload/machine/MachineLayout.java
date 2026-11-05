package com.siao.appleoverload.machine;

/** GUI 布局常量：菜单里的槽位坐标和贴图上的方框必须用同一套数字。 */
public final class MachineLayout {

    public static final int WIDTH = 176;
    public static final int HEIGHT = 200;
    public static final int SLOT = 18;

    public static final int INPUT_X = 34;
    public static final int INPUT_Y = 17;

    public static final int OUTPUT_X = 118;
    public static final int OUTPUT_Y = 17;

    public static final int ENERGY_X = 8;
    public static final int ENERGY_Y = 17;
    public static final int ENERGY_WIDTH = 14;
    public static final int ENERGY_HEIGHT = 52;

    public static final int ARROW_X = 94;
    public static final int ARROW_Y = 40;
    public static final int ARROW_WIDTH = 20;
    public static final int ARROW_HEIGHT = 8;

    public static final int UPGRADE_X = 34;
    public static final int UPGRADE_Y = 76;
    public static final int UPGRADE_COLUMNS = 4;

    public static final int PLAYER_X = 8;
    public static final int PLAYER_Y = 116;
    public static final int HOTBAR_Y = 174;

    private MachineLayout() {
    }

    public static int inputX(int index) {
        return INPUT_X + (index % 3) * SLOT;
    }

    public static int inputY(int index) {
        return INPUT_Y + (index / 3) * SLOT;
    }

    public static int outputX() {
        return OUTPUT_X;
    }

    public static int outputY(int index) {
        return OUTPUT_Y + index * SLOT;
    }

    public static int upgradeX(int index) {
        return UPGRADE_X + (index % UPGRADE_COLUMNS) * SLOT;
    }

    public static int upgradeY(int index) {
        return UPGRADE_Y + (index / UPGRADE_COLUMNS) * SLOT;
    }
}
