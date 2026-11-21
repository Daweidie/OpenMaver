import java.util.Properties;

/**
 * OpenZen Telly 的独立参数。运行时由 ScaffoldFeature 选择本组参数，
 * 共用目标搜索、MovementInput 静默转向及 C03 → C08 放置时序。
 */
public final class TellyFeature {
    public static final int MODE_TELLY_BRIDGE = 0;
    public static final int MODE_OLD_TELLY = 1;
    public static final String[] MODE_NAMES = {"TELLY BRIDGE", "OLD TELLY"};

    static final ScaffoldFeature.Settings SETTINGS = new ScaffoldFeature.Settings();
    static {
        SETTINGS.mode = ScaffoldFeature.MODE_TELLY_BRIDGE;
        SETTINGS.eagle = false;
        SETTINGS.snap = false;
    }

    private TellyFeature() { }

    public static void load(Properties values) {
        ScaffoldFeature.reset();
        SETTINGS.mode = readInt(values, "telly.mode", MODE_TELLY_BRIDGE, 0, 1)
                + ScaffoldFeature.MODE_TELLY_BRIDGE;
        SETTINGS.sneak = readBoolean(values, "telly.sneak", true);
        SETTINGS.clutch = readBoolean(values, "telly.clutch", true);
        SETTINGS.rotationTick = readInt(values, "telly.rotationTick", 3, 1, 6);
        SETTINGS.slotMode = readInt(values, "telly.slotMode", ScaffoldFeature.SLOT_HOTBAR,
                ScaffoldFeature.SLOT_SILENT, ScaffoldFeature.SLOT_HOTBAR);
        SETTINGS.itemSpoof = readBoolean(values, "telly.itemSpoof", true);
        SETTINGS.hud = readBoolean(values, "telly.hud", true);
    }

    public static String save() {
        return "telly.mode=" + getMode()
                + "\ntelly.sneak=" + SETTINGS.sneak
                + "\ntelly.clutch=" + SETTINGS.clutch
                + "\ntelly.rotationTick=" + SETTINGS.rotationTick
                + "\ntelly.slotMode=" + SETTINGS.slotMode
                + "\ntelly.itemSpoof=" + SETTINGS.itemSpoof
                + "\ntelly.hud=" + SETTINGS.hud + "\n";
    }

    private static int readInt(Properties values, String key, int fallback, int min, int max) {
        try {
            return clamp(Integer.parseInt(values.getProperty(key, String.valueOf(fallback)).trim()), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(Properties values, String key, boolean fallback) {
        return Boolean.parseBoolean(values.getProperty(key, String.valueOf(fallback)).trim());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int getMode() { return SETTINGS.mode - ScaffoldFeature.MODE_TELLY_BRIDGE; }
    public static void setMode(int value) {
        int next = clamp(value, 0, 1) + ScaffoldFeature.MODE_TELLY_BRIDGE;
        if (SETTINGS.mode == next) return;
        ScaffoldFeature.reset();
        SETTINGS.mode = next;
        OpenMaverFeatures.markDirty();
    }
    public static boolean isSneak() { return SETTINGS.sneak; }
    public static void setSneak(boolean value) { SETTINGS.sneak = value; OpenMaverFeatures.markDirty(); }
    public static boolean isClutch() { return SETTINGS.clutch; }
    public static void setClutch(boolean value) { SETTINGS.clutch = value; OpenMaverFeatures.markDirty(); }
    public static int getRotationTick() { return SETTINGS.rotationTick; }
    public static void setRotationTick(int value) {
        SETTINGS.rotationTick = clamp(value, 1, 6);
        OpenMaverFeatures.markDirty();
    }
    public static int getSlotMode() { return SETTINGS.slotMode; }
    public static void setSlotMode(int value) { ScaffoldFeature.setSlotMode(SETTINGS, value); }
    public static boolean isItemSpoof() { return SETTINGS.itemSpoof; }
    public static void setItemSpoof(boolean value) { SETTINGS.itemSpoof = value; OpenMaverFeatures.markDirty(); }
    public static boolean isHud() { return SETTINGS.hud; }
    public static void setHud(boolean value) { SETTINGS.hud = value; OpenMaverFeatures.markDirty(); }
}
