import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * 左上角统计 HUD：FPS 胶囊 + 键盘 / CPS 检测。
 *
 * <p>FPS 在上方，下面是一块按键布局：W、A / S / D、空格、LMB / RMB，
 * 最底部显示 CPS（左键 | 右键）。键盘和鼠标按下时对应按键会高亮。
 * 文字统一使用 Maver 自带的 Source Code Pro（{@link HudFont}）。
 *
 * <p>FPS 通过统计每秒 {@code RenderGameOverlayEvent.Post} 的调用次数得到，
 * 不依赖 Minecraft 的调试屏状态；CPS 每帧轮询鼠标左/右键边沿，
 * 只统计最近 1 秒内的按下次数，因此数值会随时间自然回落。
 *
 * <p>显示开关由 {@code render.fps} / {@code render.cps} 两个模块控制，
 * 默认都在 `config/default.jsonc` 中开启。
 */
public final class StatsHud {

    private static final int MARGIN = 12;
    private static final int GAP = 6;

    /** 顶部 FPS 面板（与按键面板共用同一套直角深色底）。 */
    private static final int PANEL_H = 22;
    private static final int FPS_PAD_X = 9;
    private static final int FPS_MIN_W = 60;
    private static final float FPS_TEXT_SIZE = 0.95F;

    /** 按键面板：W / A S D / 空格 / LMB RMB / CPS。 */
    private static final int KEY_SIZE = 22;
    private static final int KEY_GAP = 2;
    private static final int KEYBOARD_W = KEY_SIZE * 3 + KEY_GAP * 2;
    private static final int SPACE_H = 8;
    private static final int CPS_ROW_H = 20;
    private static final float KEY_TEXT_SIZE = 0.95F;
    private static final float KEY_LABEL_TEXT_SIZE = 0.85F;
    private static final float CPS_TEXT_SIZE = 0.95F;

    /** 整块按键面板的高度：W / ASD / 空格 / LMB RMB / CPS 五行 + 四个间隙。 */
    private static final int KEYS_BLOCK_H = KEY_SIZE * 3 + SPACE_H + CPS_ROW_H + KEY_GAP * 4;

    /** 统计 HUD 统一使用中性灰底，无外描边。 */
    private static final int PANEL_SHADOW_ALPHA = 0x38;

    /** 按键底板：半透明灰色，按下时反白。 */
    /**
     * 与右上角功能栏一致的底色：{@code OpenMaverHud} 的模块列表就是
     * {@code 0x000000} + alpha {@code 0x80} 的直角矩形。
     */
    private static final int KEY_BG = 0x80000000;
    private static final int KEY_BG_PRESSED = 0xDCE8EDF2;
    private static final int KEY_TEXT = 0xFFFFFFFF;
    private static final int KEY_TEXT_PRESSED = 0xFF20262D;
    private static final int KEY_SHADOW = 0x90000000;

    /** HudFont 用 ARGB，最高位必须是不透明的 0xFF，否则整段文字会被跳过。 */
    private static final int FPS_TEXT = 0xFFFFFFFF;

    /** FPS 统计窗口：每满 1 秒结算一次，避免数字逐帧跳动。 */
    private static final long FPS_WINDOW_NANOS = 1_000_000_000L;
    private static long fpsWindowStart;
    private static int fpsFrames;
    /** 尚未完成第一个统计窗口时为 -1，显示成「FPS --」。 */
    private static int fps = -1;

    /** 鼠标按键的上一次状态，用于检测按下边沿。 */
    private static boolean leftDown;
    private static boolean rightDown;
    /** 最近 1 秒内的点击时间戳（纳秒）。 */
    private static final List<Long> LEFT_CLICKS = new ArrayList<Long>();
    private static final List<Long> RIGHT_CLICKS = new ArrayList<Long>();

    /** 出错后停用，避免每帧抛异常。 */
    private static boolean failed;

    private StatsHud() {
    }

    /** 任一统计模块开启时返回 true。 */
    public static boolean isEnabled() {
        return isModuleEnabled("FPS") || isModuleEnabled("CPS");
    }

    /**
     * 绘制左上角统计 HUD。由 {@link OpenMaverHud} 在
     * {@code RenderGameOverlayEvent.Post} 中调用，此时背景快照已经抓取完成。
     */
    public static void render(FontRenderer font, ScaledResolution resolution, long now) {
        if (failed || font == null || resolution == null) {
            return;
        }
        try {
            boolean fpsEnabled = isModuleEnabled("FPS");
            boolean cpsEnabled = isModuleEnabled("CPS");
            if (!fpsEnabled && !cpsEnabled) {
                reset();
                return;
            }

            sampleFps(fpsEnabled, now);
            sampleClicks(cpsEnabled, now);
            draw(font, resolution, fpsEnabled, cpsEnabled);
        } catch (Throwable t) {
            failed = true;
            reset();
            System.err.println("[OpenMaver] Stats HUD disabled: " + t);
        }
    }

    // ==================================================================
    // 采样
    // ==================================================================

    private static void sampleFps(boolean enabled, long now) {
        if (!enabled) {
            fpsWindowStart = 0L;
            fpsFrames = 0;
            fps = -1;
            return;
        }
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now;
            fpsFrames = 0;
            return;
        }
        fpsFrames++;
        long elapsed = now - fpsWindowStart;
        if (elapsed >= FPS_WINDOW_NANOS) {
            fps = Math.round(fpsFrames * (float) FPS_WINDOW_NANOS / elapsed);
            fpsWindowStart = now;
            fpsFrames = 0;
        }
    }

    private static void sampleClicks(boolean enabled, long now) {
        if (!enabled) {
            resetClicks();
            return;
        }
        boolean left = Mouse.isButtonDown(0);
        boolean right = Mouse.isButtonDown(1);
        // 有 GUI（背包 / 聊天等）打开时只跟踪按键状态，不把界面内的点击算进 CPS。
        Minecraft mc = Minecraft.getMinecraft();
        boolean inGame = mc != null && mc.currentScreen == null;
        if (inGame && left && !leftDown) {
            LEFT_CLICKS.add(now);
        }
        if (inGame && right && !rightDown) {
            RIGHT_CLICKS.add(now);
        }
        leftDown = left;
        rightDown = right;
        prune(LEFT_CLICKS, now);
        prune(RIGHT_CLICKS, now);
    }

    /**
     * 记录一次由 AutoClicker 注入的点击。
     *
     * <p>CPS HUD 以前只轮询物理鼠标边沿，自动点击器工作时界面仍然是 L 0 / R 0，
     * 很容易被误判成“模块没触发”。这里让 HUD 同时统计注入点击。
     */
    public static void recordAutoClick(boolean left, long now) {
        if (failed || !isModuleEnabled("CPS")) {
            return;
        }
        if (left) {
            LEFT_CLICKS.add(now);
        } else {
            RIGHT_CLICKS.add(now);
        }
        prune(LEFT_CLICKS, now);
        prune(RIGHT_CLICKS, now);
    }

    private static void prune(List<Long> clicks, long now) {
        long cutoff = now - FPS_WINDOW_NANOS;
        while (!clicks.isEmpty() && clicks.get(0) < cutoff) {
            clicks.remove(0);
        }
    }

    private static void resetClicks() {
        leftDown = false;
        rightDown = false;
        LEFT_CLICKS.clear();
        RIGHT_CLICKS.clear();
    }

    private static void reset() {
        fpsWindowStart = 0L;
        fpsFrames = 0;
        fps = -1;
        resetClicks();
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    private static String fpsLabel() {
        return fps < 0 ? "FPS --" : "FPS " + fps;
    }

    private static void draw(FontRenderer font, ScaledResolution resolution,
            boolean fpsEnabled, boolean cpsEnabled) {
        int x = MARGIN;
        int y = MARGIN;
        int fpsW = 0;

        // ---- 先算布局，再统一画底板（避免玻璃绘制打断文字批次） ----
        if (fpsEnabled) {
            String label = fpsLabel();
            int textW = font.getStringWidth(label);
            fpsW = Math.max(FPS_MIN_W, textW + FPS_PAD_X * 2);
            fpsW = Math.min(fpsW, Math.max(FPS_MIN_W,
                    resolution.getScaledWidth() - x - MARGIN));
        }
        int keysY = fpsEnabled ? y + PANEL_H + GAP : y;

        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH);

        if (fpsEnabled) {
            panel(x, y, fpsW, PANEL_H);
        }
        if (cpsEnabled) {
            drawKeyBoxes(x, keysY);
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopAttrib();
        GlColor.restore();

        if (fpsEnabled) {
            String label = fpsLabel();
            int textX = x + (fpsW - font.getStringWidth(label)) / 2;
            int textY = y + (PANEL_H - 12) / 2;
            font.drawString(label, textX, textY, FPS_TEXT);
        }
        if (cpsEnabled) {
            drawKeyTexts(font, x, keysY);
        }
    }

    /**
     * 统计 HUD 底板：**正方形**深色半透明，与右上角功能栏（{@code OpenMaverHud}
     * 的模块列表）同一套底色 —— 那里就是 {@code 0x000000} + alpha {@code 0x80}、直角。
     * 之前这里是圆角灰块，和功能栏摆在一起像两套 UI。
     */
    private static void panel(int x, int y, int w, int h) {
        grayBox(x, y, w, h, KEY_BG);
    }

    private static void grayBox(int x, int y, int w, int h, int fill) {
        fillRect(x + 1, y + 2, w, h, withAlpha(0x000000, PANEL_SHADOW_ALPHA));
        fillRect(x, y, w, h, fill);
    }

    /** 直角实心矩形（GL 立即模式，配合 draw() 里的混合状态）。 */
    private static void fillRect(int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
            return;
        }
        GlColor.set(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }

    /** 键盘 / 鼠标 / CPS 的所有底板。 */
    private static void drawKeyBoxes(int x, int y) {
        int rowY = y;
        int wX = x + (KEYBOARD_W - KEY_SIZE) / 2;
        keyBox(wX, rowY, KEY_SIZE, KEY_SIZE, Keyboard.isKeyDown(Keyboard.KEY_W));
        rowY += KEY_SIZE + KEY_GAP;

        keyBox(x, rowY, KEY_SIZE, KEY_SIZE, Keyboard.isKeyDown(Keyboard.KEY_A));
        keyBox(x + KEY_SIZE + KEY_GAP, rowY, KEY_SIZE, KEY_SIZE,
                Keyboard.isKeyDown(Keyboard.KEY_S));
        keyBox(x + (KEY_SIZE + KEY_GAP) * 2, rowY, KEY_SIZE, KEY_SIZE,
                Keyboard.isKeyDown(Keyboard.KEY_D));
        rowY += KEY_SIZE + KEY_GAP;

        boolean space = Keyboard.isKeyDown(Keyboard.KEY_SPACE);
        keyBox(x, rowY, KEYBOARD_W, SPACE_H, space);
        int markW = Math.min(18, KEYBOARD_W - 12);
        fillRect(x + (KEYBOARD_W - markW) / 2, rowY + SPACE_H / 2 - 1,
                markW, 2, space ? KEY_TEXT_PRESSED : 0xB0FFFFFF);
        rowY += SPACE_H + KEY_GAP;

        int halfW = (KEYBOARD_W - KEY_GAP) / 2;
        keyBox(x, rowY, halfW, KEY_SIZE, Mouse.isButtonDown(0));
        keyBox(x + halfW + KEY_GAP, rowY, halfW, KEY_SIZE, Mouse.isButtonDown(1));
        rowY += KEY_SIZE + KEY_GAP;

        keyBox(x, rowY, KEYBOARD_W, CPS_ROW_H, false);
    }

    private static void keyBox(int x, int y, int w, int h, boolean pressed) {
        grayBox(x, y, w, h, pressed ? KEY_BG_PRESSED : KEY_BG);
    }

    /** 所有按键文字，统一使用 Maver 自带字体。 */
    private static void drawKeyTexts(FontRenderer font, int x, int y) {
        int rowY = y;
        int wX = x + (KEYBOARD_W - KEY_SIZE) / 2;
        drawKeyLabel(font, "W", wX, rowY, KEY_SIZE, KEY_SIZE,
                Keyboard.isKeyDown(Keyboard.KEY_W), KEY_TEXT_SIZE);
        rowY += KEY_SIZE + KEY_GAP;

        drawKeyLabel(font, "A", x, rowY, KEY_SIZE, KEY_SIZE,
                Keyboard.isKeyDown(Keyboard.KEY_A), KEY_TEXT_SIZE);
        drawKeyLabel(font, "S", x + KEY_SIZE + KEY_GAP, rowY, KEY_SIZE, KEY_SIZE,
                Keyboard.isKeyDown(Keyboard.KEY_S), KEY_TEXT_SIZE);
        drawKeyLabel(font, "D", x + (KEY_SIZE + KEY_GAP) * 2, rowY,
                KEY_SIZE, KEY_SIZE, Keyboard.isKeyDown(Keyboard.KEY_D),
                KEY_TEXT_SIZE);
        rowY += KEY_SIZE + KEY_GAP + SPACE_H + KEY_GAP;

        int halfW = (KEYBOARD_W - KEY_GAP) / 2;
        drawKeyLabel(font, "LMB", x, rowY, halfW, KEY_SIZE,
                Mouse.isButtonDown(0), KEY_LABEL_TEXT_SIZE);
        drawKeyLabel(font, "RMB", x + halfW + KEY_GAP, rowY, halfW, KEY_SIZE,
                Mouse.isButtonDown(1), KEY_LABEL_TEXT_SIZE);
        rowY += KEY_SIZE + KEY_GAP;

        drawCpsText(font, x, rowY, KEYBOARD_W, CPS_ROW_H);
    }

    private static void drawKeyLabel(FontRenderer font, String text,
            int boxX, int boxY, int boxW, int boxH, boolean pressed, float size) {
        int textW = font.getStringWidth(text);
        int textX = boxX + (boxW - textW) / 2;
        int textY = boxY + (boxH - 12) / 2;
        if (!pressed) {
            font.drawString(text, textX + 1, textY + 1, KEY_SHADOW);
        }
        font.drawString(text, textX, textY,
                pressed ? KEY_TEXT_PRESSED : KEY_TEXT);
    }

    private static void drawCpsText(FontRenderer font, int x, int y, int w, int h) {
        String text = "CPS " + LEFT_CLICKS.size() + " | " + RIGHT_CLICKS.size();
        int textW = font.getStringWidth(text);
        int textX = x + (w - textW) / 2;
        int textY = y + (h - 12) / 2;
        font.drawString(text, textX + 1, textY + 1, KEY_SHADOW);
        font.drawString(text, textX, textY, KEY_TEXT);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static boolean isModuleEnabled(String name) {
        Module module = ModuleManager.getModule(name);
        return module != null && module.isEnabled();
    }

    private static int withAlpha(int rgb, int alpha) {
        int a = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }
}
