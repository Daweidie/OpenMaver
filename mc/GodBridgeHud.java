import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.opengl.GL11;

/**
 * GodBridge（神桥）速度卡。
 *
 * <p>教材里用按键显示和方块计数判断自己到底有没有变快：普通搭路 1.67 方块/秒、
 * 45° 斜桥 2 方块/秒、蹲起搭路平均 3 方块/秒。这张卡片就是同一个读数：
 *
 * <pre>
 *   ┌──────────────────────────────────┐
 *   │ SNEAK TOGGLE            EDGE     │
 *   │ ████████████████░░░   2.86b/s    │
 *   └──────────────────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li>左下进度条 = 实测 b/s / 该模式的目标 b/s（12 / 10 / 7 tick 一方块）；</li>
 *   <li>右上角是状态机阶段（IDLE / EDGE / RELEASE / RESNEAK），松手那一下能看到
 *       RELEASE 闪一下；</li>
 *   <li>速度来自 {@link GodBridgeFeature#hudBlocksPerSecond()}，窗口 2 秒。</li>
 * </ul>
 *
 * <p>与 Target HUD / Scaffold HUD 共用同一套卡片语言，位置固定在准星右侧。
 */
public final class GodBridgeHud {

    private static final int CARD_W = 184;
    private static final int CARD_H = 32;
    private static final float CARD_R = 7.0F;
    private static final float PAD = 8.0F;
    private static final float BAR_H = 4.0F;

    private static final int TEXT = 0xF2F5FA;
    private static final int MUTED = 0x9AA4B2;
    private static final int BORDER = 0x2E333B;
    private static final int SHADOW = 0x000000;
    private static final float SHADOW_ALPHA = 0.28F;
    private static final int GLASS = 0x0D0F13;
    private static final float GLASS_TINT_STRENGTH = 0.62F;
    private static final float GLASS_ALPHA = 0.90F;
    private static final int BAR_BG = 0x1E1E1E;
    /** 神桥的主题色（与 Scaffold 的紫色区分开）。 */
    private static final int BAR_FILL = 0x37C6A8;

    private static final long FADE_IN_MS = 150L;
    private static final long FADE_OUT_MS = 140L;
    private static final float SLIDE_DISTANCE = 16.0F;
    private static final float SPRING_MS = 90.0F;

    private static float visible;
    private static float barProgress;
    private static long lastFrameNanos;
    private static boolean failed;

    private GodBridgeHud() {
    }

    /** 模块开启且 HUD 打开时返回 true（HUD 用它决定是否提前抓毛玻璃快照）。 */
    public static boolean isEnabled() {
        if (failed) {
            return false;
        }
        if (visible > 0.01F) {
            return true;
        }
        return moduleEnabled() && GodBridgeFeature.isHud();
    }

    public static void render(FontRenderer font, ScaledResolution resolution, long now) {
        if (failed || font == null || resolution == null) {
            return;
        }
        try {
            draw(font, resolution, now);
        } catch (Throwable error) {
            failed = true;
            reset();
            System.err.println("[OpenMaver] GodBridge HUD disabled: " + error);
        }
    }

    private static void draw(FontRenderer font, ScaledResolution resolution, long now) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean active = mc != null && moduleEnabled() && GodBridgeFeature.isHud();
        Object world = mc == null ? null
                : OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
        Object player = mc == null ? null
                : OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        Object screen = mc == null ? null
                : OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null);
        if (world == null || player == null || screen != null) {
            active = false;
        }

        long frameNanos = lastFrameNanos == 0L ? 16_000_000L : now - lastFrameNanos;
        lastFrameNanos = now;
        float deltaMs = Math.max(1.0F, Math.min(60.0F, frameNanos / 1_000_000.0F));
        float duration = active ? FADE_IN_MS : FADE_OUT_MS;
        visible = approach(visible, active ? 1.0F : 0.0F, deltaMs / duration);
        if (visible <= 0.01F) {
            if (!active) {
                reset();
            }
            return;
        }

        double bps = GodBridgeFeature.hudBlocksPerSecond();
        double target = GodBridgeFeature.targetBps(GodBridgeFeature.getMode());
        float fill = target <= 0.0D ? 0.0F : (float) Math.min(1.0D, bps / target);
        barProgress = approach(barProgress, fill, deltaMs / SPRING_MS);

        drawCard(font, resolution,
                GodBridgeFeature.MODE_NAMES[GodBridgeFeature.getMode()],
                GodBridgeFeature.getPhaseName(),
                bps, GodBridgeFeature.getPlacedCount(), visible);
    }

    private static void drawCard(FontRenderer font, ScaledResolution resolution,
            String mode, String phase, double bps, int placed, float alpha) {
        float scale = OpenMaverFeatures.getTargetHudScale();
        int screenW = resolution.getScaledWidth();
        int screenH = resolution.getScaledHeight();
        scale = Math.min(scale, Math.max(0.5F, (screenW - 20.0F) / CARD_W));

        float drawW = CARD_W * scale;
        float drawH = CARD_H * scale;
        float gap = 12.0F * scale;
        float cardX = screenW / 2.0F + gap;
        if (cardX + drawW > screenW - 6.0F) {
            cardX = screenW - drawW - 6.0F;
        }
        float cardY = screenH / 2.0F - drawH / 2.0F;
        if (TargetHud.isShowing()) {
            cardY += TargetHud.cardHeight() + 6.0F * scale;
        }
        cardY = Math.max(4.0F, Math.min(cardY, screenH - drawH - 4.0F));

        float slide = (1.0F - alpha) * SLIDE_DISTANCE * scale;
        float drawX = cardX + slide;

        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_LIGHTING);

        GL11.glPushMatrix();
        GL11.glTranslatef(drawX, cardY, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);

        roundRect(2.0F, 3.0F, CARD_W, CARD_H, CARD_R, color(SHADOW, SHADOW_ALPHA * alpha));
        roundRect(0.0F, 0.0F, CARD_W, CARD_H, CARD_R, color(BORDER, 0.85F * alpha));
        GuiBlur.draw(Math.round(drawX + scale), Math.round(cardY + scale),
                Math.round(drawW - scale * 2.0F), Math.round(drawH - scale * 2.0F),
                Math.max(0.0F, (CARD_R - 1.0F) * scale), GLASS,
                GLASS_TINT_STRENGTH, alpha * GLASS_ALPHA);

        float textX = PAD;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        font.drawString(mode, Math.round(textX), 7, color(TEXT, alpha));
        float phaseWidth = font.getStringWidth(phase);
        font.drawString(phase, Math.round(CARD_W - PAD - phaseWidth), 7,
                color(MUTED, alpha * 0.95F));
        String speedText = String.format(java.util.Locale.ROOT, "%.2fb/s", bps);
        font.drawString(speedText, Math.round(textX), 18, color(TEXT, alpha));
        String countText = placed + " blocks";
        float countWidth = font.getStringWidth(countText);
        font.drawString(countText, Math.round(CARD_W - PAD - countWidth), 20,
                color(MUTED, alpha * 0.95F));
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        float barX = textX;
        float barY = CARD_H - PAD - BAR_H;
        float barW = CARD_W - PAD * 2.0F;
        roundRect(barX, barY, barW, BAR_H, BAR_H / 2.0F, color(BAR_BG, alpha * 0.95F));
        float fillW = Math.max(0.0F, Math.min(1.0F, barProgress)) * barW;
        if (fillW > 0.5F) {
            roundRect(barX, barY, fillW, BAR_H, BAR_H / 2.0F, color(BAR_FILL, alpha));
        }

        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    // ==================================================================
    // GL / 工具
    // ==================================================================

    private static void roundRect(float x, float y, float w, float h, float radius, int argb) {
        if (w <= 0.0F || h <= 0.0F || (argb >>> 24) == 0) {
            return;
        }
        float r = Math.min(radius, Math.min(w / 2.0F, h / 2.0F));
        GlColor.set(argb);
        GL11.glBegin(GL11.GL_POLYGON);
        for (int corner = 0; corner < 4; corner++) {
            float centerX = x + ((corner == 0 || corner == 3) ? r : w - r);
            float centerY = y + ((corner < 2) ? r : h - r);
            float startAngle = 180.0F + corner * 90.0F;
            for (int segment = 0; segment <= 5; segment++) {
                double angle = Math.toRadians(startAngle + segment * 18.0D);
                GL11.glVertex2f(centerX + (float) Math.cos(angle) * r,
                        centerY + (float) Math.sin(angle) * r);
            }
        }
        GL11.glEnd();
    }

    private static boolean moduleEnabled() {
        Module module = ModuleManager.getModule("GodBridge");
        return module != null && module.isEnabled();
    }

    private static int color(int rgb, float alpha) {
        int a = clamp255(Math.round(alpha * 255.0F));
        return a << 24 | (rgb & 0x00FFFFFF);
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private static float approach(float value, float goal, float amount) {
        float step = amount < 0.0F ? 0.0F : (amount > 1.0F ? 1.0F : amount);
        if (value < goal) {
            return Math.min(goal, value + step);
        }
        return Math.max(goal, value - step);
    }

    private static void reset() {
        visible = 0.0F;
        barProgress = 0.0F;
        lastFrameNanos = 0L;
    }
}
