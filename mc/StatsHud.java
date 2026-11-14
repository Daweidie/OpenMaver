import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * 左上角统计 HUD：FPS 胶囊 + CPS 小方块。
 *
 * <p>布局默认锚定屏幕左上角：CPS 小方块在最左，FPS 胶囊紧挨在它右侧。
 * 两块底板都是灰色调圆角毛玻璃（模糊自身覆盖的画面，再叠一层中性灰），
 * 与右侧 ModuleList / 右下角提示保持同一套视觉语言。
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

    /** 两块底板统一高度，保证顶边对齐。 */
    private static final int PANEL_H = 22;
    private static final int FPS_RADIUS = 6;
    private static final int CPS_SIZE = 22;
    private static final int CPS_RADIUS = 6;

    private static final int FPS_PAD_X = 9;
    private static final int FPS_MIN_W = 60;
    private static final float FPS_TEXT_SIZE = 0.8F;

    private static final float CPS_TEXT_SIZE = 0.6F;
    /** CPS 方块内两行文字（左键 / 右键）的纵向位置。 */
    private static final int CPS_ROW_LEFT_Y = 3;
    private static final int CPS_ROW_RIGHT_Y = 11;

    /** 灰色毛玻璃：与右下角提示相同的色调与占比。 */
    private static final int PANEL_TINT = 0x8E8E8E;
    private static final float PANEL_TINT_STRENGTH = 0.46F;
    private static final int PANEL_BORDER_ALPHA = 0x30;
    private static final int PANEL_SHADOW_ALPHA = 0x38;
    private static final int PANEL_HIGHLIGHT_ALPHA = 0x14;

    /** HudFont 用 ARGB，最高位必须是不透明的 0xFF，否则整段文字会被跳过。 */
    private static final int FPS_TEXT = 0xFFFFFFFF;
    private static final int CPS_LABEL = 0xFFD8D8D8;
    private static final int CPS_VALUE = 0xFFFFFFFF;

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

    /** 任一统计模块开启时返回 true；HUD 用它决定是否提前抓取毛玻璃快照。 */
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
        int cpsX = -1;
        int fpsX = -1;
        int fpsW = 0;

        // ---- 先算布局，再统一画底板（避免玻璃绘制打断文字批次） ----
        if (cpsEnabled) {
            cpsX = x;
            x += CPS_SIZE + GAP;
        }
        if (fpsEnabled) {
            String label = fpsLabel();
            int textW = HudFont.getStringWidth(font, label, FPS_TEXT_SIZE);
            fpsW = Math.max(FPS_MIN_W, textW + FPS_PAD_X * 2);
            fpsX = x;
            fpsW = Math.min(fpsW, Math.max(FPS_MIN_W, resolution.getScaledWidth() - fpsX - MARGIN));
        }

        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH);

        if (cpsX >= 0) {
            panel(cpsX, y, CPS_SIZE, PANEL_H, CPS_RADIUS);
        }
        if (fpsX >= 0) {
            panel(fpsX, y, fpsW, PANEL_H, FPS_RADIUS);
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopAttrib();
        GlColor.restore();

        if (cpsX >= 0) {
            drawCpsText(font, cpsX, y);
        }
        if (fpsX >= 0) {
            String label = fpsLabel();
            int textX = fpsX + (fpsW - HudFont.getStringWidth(font, label, FPS_TEXT_SIZE)) / 2;
            int textY = y + (PANEL_H - 10) / 2;
            HudFont.drawString(font, label, textX, textY, FPS_TEXT, FPS_TEXT_SIZE);
        }
    }

    /** 灰色毛玻璃圆角底板：柔和投影 + 1px 白色描边 + 局部模糊。 */
    private static void panel(int x, int y, int w, int h, int radius) {
        roundRect(x + 1, y + 2, w, h, radius, withAlpha(0x000000, PANEL_SHADOW_ALPHA));
        roundRect(x, y, w, h, radius + 1, withAlpha(0xFFFFFF, PANEL_BORDER_ALPHA));
        GuiBlur.draw(x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1),
                PANEL_TINT, PANEL_TINT_STRENGTH, 1.0F);
        if (w > radius * 2) {
            roundRect(x + radius, y + 1, w - radius * 2, 1, 0,
                    withAlpha(0xFFFFFF, PANEL_HIGHLIGHT_ALPHA));
        }
    }

    private static void drawCpsText(FontRenderer font, int x, int y) {
        String left = "L " + LEFT_CLICKS.size();
        String right = "R " + RIGHT_CLICKS.size();
        drawCpsRow(font, left, x, y + CPS_ROW_LEFT_Y);
        drawCpsRow(font, right, x, y + CPS_ROW_RIGHT_Y);
    }

    private static void drawCpsRow(FontRenderer font, String text, int x, int y) {
        int textW = HudFont.getStringWidth(font, text, CPS_TEXT_SIZE);
        int textX = x + (CPS_SIZE - textW) / 2;
        // 字母标签稍暗、数值更亮，两行仍然保持紧凑。
        int split = text.indexOf(' ');
        if (split > 0) {
            String label = text.substring(0, split);
            String value = text.substring(split);
            int labelW = HudFont.getStringWidth(font, label, CPS_TEXT_SIZE);
            HudFont.drawString(font, label, textX, y, CPS_LABEL, CPS_TEXT_SIZE);
            HudFont.drawString(font, value, textX + labelW, y, CPS_VALUE, CPS_TEXT_SIZE);
        } else {
            HudFont.drawString(font, text, textX, y, CPS_VALUE, CPS_TEXT_SIZE);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static boolean isModuleEnabled(String name) {
        Module module = ModuleManager.getModule(name);
        return module != null && module.isEnabled();
    }

    private static void roundRect(int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
            return;
        }
        float r = Math.min(radius, Math.min(w / 2.0F, h / 2.0F));
        GlColor.set(argb);
        GL11.glBegin(GL11.GL_POLYGON);
        for (int corner = 0; corner < 4; corner++) {
            float centerX = x + ((corner == 0 || corner == 3) ? r : w - r);
            float centerY = y + ((corner < 2) ? r : h - r);
            float startAngle = 180.0F + corner * 90.0F;
            for (int segment = 0; segment <= 6; segment++) {
                double angle = Math.toRadians(startAngle + segment * 15.0F);
                GL11.glVertex2f(centerX + (float) Math.cos(angle) * r,
                        centerY + (float) Math.sin(angle) * r);
            }
        }
        GL11.glEnd();
    }

    private static int withAlpha(int rgb, int alpha) {
        int a = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }
}
