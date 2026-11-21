import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.util.Properties;

/**
 * 教材式搭路辅助。准星和行进方向由玩家控制，本模块按右键放置方块并管理潜行节奏。
 */
public final class Scaffold2Feature {

    public static final int MODE_STRAIGHT = 0;
    public static final int MODE_DIAGONAL = 1;
    public static final int MODE_SNEAK_TOGGLE = 2;
    public static final String[] MODE_NAMES = {"STRAIGHT", "DIAGONAL 45", "SNEAK TOGGLE"};

    private static int mode = MODE_STRAIGHT;
    private static int releaseTicks = 2;
    private static int phaseTicks;
    private static int releasePhase;
    private static int clickCooldown;
    private static boolean active;
    private static boolean oldSneakPressed;
    private static Object sneakBinding;
    private static boolean failed;

    private Scaffold2Feature() {
    }

    public static void onTickStart(Minecraft mc) {
        Module module = ModuleManager.getModule("Scaffold2");
        if (module == null || !module.isEnabled() || failed) {
            if (active) {
                restore();
            }
            return;
        }
        try {
            Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
            Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
            Object screen = OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null);
            if (player == null || world == null || screen != null || !OpenMaverFeatures.alive(player)) {
                restore();
                return;
            }

            Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
            sneakBinding = OpenMaverFeatures.read(settings, "keyBindSneak", "field_74311_E", null);
            if (!active) {
                active = true;
                oldSneakPressed = isPressed(sneakBinding);
                phaseTicks = 0;
                releasePhase = 0;
                clickCooldown = 0;
            }

            if (clickCooldown > 0) clickCooldown--;
            boolean back = keyDown(settings, "keyBindBack", "field_74314_A");
            boolean diagonal = keyDown(settings, "keyBindRight", "field_74366_z");
            boolean running = back && (mode != MODE_DIAGONAL || diagonal);
            if (!running) {
                phaseTicks = 0;
                releasePhase = 0;
                writePressed(sneakBinding, physicalSneak());
                return;
            }

            boolean placed = false;
            if (Mouse.isButtonDown(1) && clickCooldown == 0) {
                placed = tryPlace(mc, player, world);
                if (placed) {
                    clickCooldown = 4;
                    if (mode == MODE_SNEAK_TOGGLE) {
                        releasePhase = 1;
                        phaseTicks = 0;
                    }
                }
            }

            boolean sneak = true;
            if (mode == MODE_SNEAK_TOGGLE && releasePhase == 1) {
                phaseTicks++;
                if (phaseTicks <= releaseTicks) {
                    sneak = false;
                } else {
                    releasePhase = 0;
                    phaseTicks = 0;
                }
            }
            writePressed(sneakBinding, sneak);
        } catch (Throwable error) {
            failed = true;
            restore();
            System.err.println("[OpenMaver] Scaffold2 disabled after error: " + error);
        }
    }

    public static void reset() {
        restore();
        failed = false;
    }

    public static void load(Properties values) {
        if (values == null) return;
        mode = clamp(readInt(values, "scaffold2.mode", MODE_STRAIGHT), 0, MODE_NAMES.length - 1);
        releaseTicks = clamp(readInt(values, "scaffold2.releaseTicks", 2), 1, 4);
        reset();
    }

    public static String save() {
        // ★ 结尾必须有换行：saveSettings 是直接字符串拼接的，少了这一个 "\n"
        // 下一段（AttackReduce 的 save()，以 "attackreduce.mode=" 开头）会被粘成
        // `scaffold2.releaseTicks=2attackreduce.mode=9` —— 整行报废，
        // attackreduce.mode 永远读不到、每次启动都掉回默认值。
        return "scaffold2.mode=" + mode + "\nscaffold2.releaseTicks=" + releaseTicks + "\n";
    }

    private static int readInt(Properties values, String key, int fallback) {
        String raw = values.getProperty(key);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean tryPlace(Minecraft mc, Object player, Object world) {
        Object hit = OpenMaverFeatures.read(mc, "objectMouseOver", "field_71476_x", null);
        Object pos = OpenMaverFeatures.read(hit, "blockPos", "field_178783_e", null);
        Object face = OpenMaverFeatures.read(hit, "sideHit", "field_178784_b", null);
        Object hitVec = OpenMaverFeatures.read(hit, "hitVec", "field_72307_f", null);
        Object controller = OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        Object stack = OpenMaverFeatures.call(player, "getHeldItem", "func_70694_bm", null);
        if (pos == null || face == null || hitVec == null || controller == null || stack == null) {
            return false;
        }
        Object item = OpenMaverFeatures.call(stack, "getItem", "func_77973_b", null);
        try {
            Class<?> itemBlock = Class.forName("net.minecraft.item.ItemBlock");
            if (!itemBlock.isInstance(item)) return false;
        } catch (Throwable unavailable) {
            return false;
        }
        Object result = OpenMaverFeatures.call(controller, "onPlayerRightClick",
                "func_178890_a", null, player, world, stack, pos, face, hitVec);
        if (!Boolean.TRUE.equals(result)) {
            return false;
        }
        OpenMaverFeatures.callVoid(player, "swingItem", "func_71038_i", null);
        return true;
    }

    private static boolean keyDown(Object settings, String mcp, String srg) {
        Object binding = OpenMaverFeatures.read(settings, mcp, srg, null);
        Object code = OpenMaverFeatures.call(binding, "getKeyCode", "func_151463_i", null);
        return code instanceof Number && Keyboard.isKeyDown(((Number) code).intValue());
    }

    private static boolean isPressed(Object binding) {
        Object pressed = OpenMaverFeatures.read(binding, "pressed", "field_74513_e", null);
        return Boolean.TRUE.equals(pressed);
    }

    private static boolean physicalSneak() {
        Object code = OpenMaverFeatures.call(sneakBinding, "getKeyCode", "func_151463_i", null);
        return code instanceof Number
                ? Keyboard.isKeyDown(((Number) code).intValue()) : oldSneakPressed;
    }

    private static void writePressed(Object binding, boolean pressed) {
        if (binding == null) return;
        Field field = OpenMaverFeatures.findField(binding,
                new String[] {"pressed", "field_74513_e"});
        if (field != null) {
            try {
                field.setBoolean(binding, pressed);
            } catch (Throwable ignored) {
                // Keep the player's physical input if the runtime mapping is unavailable.
            }
        }
    }

    private static void restore() {
        if (active) writePressed(sneakBinding, physicalSneak());
        active = false;
        phaseTicks = 0;
        releasePhase = 0;
        clickCooldown = 0;
        sneakBinding = null;
    }

    public static int getMode() { return mode; }
    public static void setMode(int value) { mode = clamp(value, 0, MODE_NAMES.length - 1); }
    public static int getReleaseTicks() { return releaseTicks; }
    public static void setReleaseTicks(int value) { releaseTicks = clamp(value, 1, 4); }
}
