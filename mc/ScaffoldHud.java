import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.opengl.GL11;

import java.lang.reflect.Method;

/**
 * Scaffold 方块计数 HUD —— 按 {@code ../OpenZen} 的
 * {@code shit.zen.hud.ScaffoldHud} 重写。
 *
 * <p>OpenZen 的版面是“图标 + 计数条 + 计数/速度”：
 *
 * <pre>
 *   ┌──────────────────────────────────────────┐
 *   │ [icon]  ███████████░░░░  64 blocks       │
 *   │                          0.00b/s         │
 *   └──────────────────────────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li>图标用当前手持（没有则快捷栏第一组）方块的真实物品模型；</li>
 *   <li>计数条进度 = {@code count / 64}，紫色 {@code #9900FF}，带弹簧动画
 *       （OpenZen 的 {@code SpringAnimation}），换方块时平滑过渡到新值；</li>
 *   <li>速度是 {@code %.2fb/s}，用 {@link ScaffoldFeature#hudSpeedBps} 按上一 tick
 *       的位移换算；</li>
 *   <li>没有方块时整块卡片淡出，不做“0 blocks”的空卡片。</li>
 * </ul>
 *
 * <p>与 Target HUD 共用同一套卡片语言：深色毛玻璃 + 低对比描边 + 圆角，
 * 位置在准星右侧（Target HUD 显示时自动下移避让）。
 */
public final class ScaffoldHud {

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    private static final int CARD_W = 160;
    private static final int CARD_H = 32;
    private static final float CARD_R = 7.0F;

    private static final float PAD = 8.0F;
    private static final float ICON_SIZE = 22.0F;
    private static final float ITEM_SIZE = 20.0F;
    private static final float BAR_H = 5.0F;
    /** 计数/速度文本区预留（右对齐时按实际宽度收窄）。 */
    private static final float TEXT_MIN_W = 40.0F;
    private static final float COUNT_SIZE = 0.95F;
    private static final float SPEED_SIZE = 0.72F;

    private static final int TEXT = 0xF2F5FA;
    private static final int COUNT = 0xFFFFFF;
    private static final int MUTED = 0x9AA4B2;
    private static final int BORDER = 0x2E333B;
    private static final int SHADOW = 0x000000;
    private static final float SHADOW_ALPHA = 0.28F;
    private static final int GLASS = 0x0D0F13;
    private static final float GLASS_TINT_STRENGTH = 0.62F;
    private static final float GLASS_ALPHA = 0.90F;
    /** OpenZen 的进度条底色与填充色。 */
    private static final int BAR_BG = 0x1E1E1E;
    private static final int BAR_FILL = 0xFFFFFF;

    /** 卡片出现/消失动画。 */
    private static final long FADE_IN_MS = 150L;
    private static final long FADE_OUT_MS = 140L;
    private static final float SLIDE_DISTANCE = 16.0F;
    /** 弹簧动画的时间常数（毫秒）；越大越慢。 */
    private static final float SPRING_MS = 90.0F;

    private static float visible;
    private static float progress;
    private static float barProgress;
    private static long lastFrameNanos;
    private static boolean failed;

    /** 淡出期间继续显示最后一次的内容。 */
    private static int cachedCount;
    private static Object cachedStack;

    /** RenderHelper 的 GUI 物品光照；未初始化时通过反射查找一次。 */
    private static Method enableItemLighting;
    private static Method disableItemLighting;
    private static boolean lightingLookupFailed;

    private ScaffoldHud() {
    }

    // ==================================================================
    // 入口
    // ==================================================================

    /** 模块开启且 HUD 打开时返回 true；HUD 用它决定是否提前抓取毛玻璃快照。 */
    public static boolean isEnabled() {
        if (failed) {
            return false;
        }
        if (visible > 0.01F) {
            return true;
        }
        return moduleEnabled();
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
            System.err.println("[OpenMaver] Scaffold HUD disabled: " + error);
        }
    }

    private static void draw(FontRenderer font, ScaledResolution resolution, long now) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean active = mc != null && moduleEnabled();
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

        int count = 0;
        Object stack = null;
        double speedBps = 0.0D;
        if (active) {
            count = ScaffoldFeature.hudBlockCount(player);
            stack = ScaffoldFeature.hudBlockStack(player);
            speedBps = ScaffoldFeature.hudSpeedBps(player);
            cachedCount = count;
            cachedStack = stack;
        } else {
            count = cachedCount;
            stack = cachedStack;
        }
        if (stack == null) {
            // OpenZen 在没有方块时整块不画。
            visible = approach(visible, 0.0F, deltaMs / FADE_OUT_MS);
            if (visible <= 0.01F) {
                reset();
                return;
            }
        }

        progress = approach(progress, Math.min(1.0F, count / 64.0F), deltaMs / SPRING_MS);
        barProgress = progress;

        drawCard(font, resolution, count, stack, speedBps, visible);
    }

    private static void reset() {
        visible = 0.0F;
        progress = 0.0F;
        barProgress = 0.0F;
        lastFrameNanos = 0L;
        cachedCount = 0;
        cachedStack = null;
    }

    // ==================================================================
    // 卡片
    // ==================================================================

    private static void drawCard(FontRenderer font, ScaledResolution resolution,
            int count, Object stack, double speedBps, float alpha) {
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

        // 图标：只按 alpha 缩放，物品模型没法单独设透明度。
        float iconX = PAD;
        float iconY = (CARD_H - ICON_SIZE) / 2.0F;
        GL11.glPushMatrix();
        GL11.glTranslatef(iconX + ICON_SIZE / 2.0F, iconY + ICON_SIZE / 2.0F, 0.0F);
        GL11.glScalef(alpha, alpha, 1.0F);
        GL11.glTranslatef(-(iconX + ICON_SIZE / 2.0F), -(iconY + ICON_SIZE / 2.0F), 0.0F);
        drawIcon(stack, alpha, iconX, iconY);
        GL11.glPopMatrix();

        // 文本块：计数在上、速度在下，右对齐。
        String countText = count + (count == 1 ? " block" : " blocks");
        String speedText = String.format(java.util.Locale.ROOT, "%.2fb/s", speedBps);
        HudFont.useMenuFont();
        float countWidth = HudFont.getStringWidth(font, countText, COUNT_SIZE);
        float speedWidth = HudFont.getStringWidth(font, speedText, SPEED_SIZE);
        float textWidth = Math.max(TEXT_MIN_W, Math.max(countWidth, speedWidth));
        float textRight = CARD_W - PAD;
        float textLeft = textRight - textWidth;

        // 进度条：图标右侧 → 文本左侧。
        float barX = iconX + ICON_SIZE + PAD;
        float barW = textLeft - PAD - barX;
        if (barW > 8.0F) {
            float barY = CARD_H / 2.0F - BAR_H / 2.0F;
            roundRect(barX, barY, barW, BAR_H, BAR_H / 2.0F, color(BAR_BG, alpha * 0.95F));
            float fill = Math.max(0.0F, Math.min(1.0F, barProgress));
            if (fill > 0.0F) {
                roundRect(barX, barY, Math.max(BAR_H, barW * fill), BAR_H,
                        BAR_H / 2.0F, color(BAR_FILL, alpha));
            }
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        int countX = Math.round(textRight - countWidth);
        int speedX = Math.round(textRight - speedWidth);
        sourceText(font, countText, countX, 7, COUNT, COUNT_SIZE, alpha);
        sourceText(font, speedText, speedX, 19, MUTED, SPEED_SIZE, alpha);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    // ==================================================================
    // 方块图标
    // ==================================================================

    private static void drawIcon(Object stack, float alpha, float x, float y) {
        float itemX = x + (ICON_SIZE - ITEM_SIZE) / 2.0F;
        float itemY = y + (ICON_SIZE - ITEM_SIZE) / 2.0F;
        if (stack != null && drawItemIcon(stack, itemX, itemY)) {
            return;
        }
        drawFallbackCube(x + ICON_SIZE / 2.0F, y + ICON_SIZE / 2.0F, alpha);
    }

    /** 用 MC 自己的 RenderItem 绘制真实方块模型；失败时返回 false 交给手绘兜底。 */
    private static boolean drawItemIcon(Object stack, float x, float y) {
        Minecraft mc = Minecraft.getMinecraft();
        Object renderItem = mc == null ? null
                : OpenMaverFeatures.call(mc, "getRenderItem", "func_175599_af", null);
        if (renderItem == null || !ensureItemLighting()) {
            return false;
        }

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GlColor.set(1.0F, 1.0F, 1.0F, 1.0F);

        invokeStatic(enableItemLighting);
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0.0F);
        float scale = ITEM_SIZE / 16.0F;
        GL11.glScalef(scale, scale, 1.0F);
        boolean drawn = OpenMaverFeatures.callVoid(renderItem, "renderItemIntoGUI",
                "func_175042_a", null, stack, Integer.valueOf(0), Integer.valueOf(0));
        GL11.glPopMatrix();
        invokeStatic(disableItemLighting);

        GL11.glPopAttrib();
        GlColor.restore();
        return drawn;
    }

    private static boolean ensureItemLighting() {
        if (enableItemLighting != null && disableItemLighting != null) {
            return true;
        }
        if (lightingLookupFailed) {
            return false;
        }
        try {
            Class<?> helper = Class.forName("net.minecraft.client.renderer.RenderHelper");
            enableItemLighting = findStatic(helper,
                    "enableGUIStandardItemLighting", "func_74520_c");
            disableItemLighting = findStatic(helper,
                    "disableStandardItemLighting", "func_74518_a");
        } catch (Throwable ignored) {
            // Fall through to the hand-drawn cube fallback.
        }
        if (enableItemLighting == null || disableItemLighting == null) {
            enableItemLighting = null;
            disableItemLighting = null;
            lightingLookupFailed = true;
            return false;
        }
        return true;
    }

    private static Method findStatic(Class<?> type, String... names) {
        for (int i = 0; i < names.length; i++) {
            try {
                Method method = type.getDeclaredMethod(names[i]);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Try the next runtime name.
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static void invokeStatic(Method method) {
        if (method == null) {
            return;
        }
        try {
            method.invoke(null);
        } catch (Throwable ignored) {
            // Lighting is best-effort; whatever state is active still renders.
        }
    }

    /** 物品渲染不可用时的等距方块，保证 HUD 永远有“方块样式”。 */
    private static void drawFallbackCube(float cx, float cy, float alpha) {
        float s = 7.0F;
        float half = 0.866F * s;
        float top = cy - s;
        float middle = cy - s / 2.0F;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GlColor.set(color(0xD7DDE6, alpha));
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(cx, top);
        GL11.glVertex2f(cx + half, middle);
        GL11.glVertex2f(cx, cy);
        GL11.glVertex2f(cx - half, middle);
        GL11.glEnd();

        GlColor.set(color(0x9AA3AE, alpha));
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(cx - half, middle);
        GL11.glVertex2f(cx, cy);
        GL11.glVertex2f(cx, cy + s);
        GL11.glVertex2f(cx - half, cy + s / 2.0F);
        GL11.glEnd();

        GlColor.set(color(0x6F7783, alpha));
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(cx + half, middle);
        GL11.glVertex2f(cx, cy);
        GL11.glVertex2f(cx, cy + s);
        GL11.glVertex2f(cx + half, cy + s / 2.0F);
        GL11.glEnd();
    }

    // ==================================================================
    // GL 基础图形 / 文本
    // ==================================================================

    private static void text(FontRenderer font, String value, int x, int y, int rgb,
            float size, float alpha) {
        if (value == null || value.isEmpty()) {
            return;
        }
        font.drawString(value, x, y, color(rgb, alpha));
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    private static void sourceText(FontRenderer font, String value, int x, int y, int rgb,
            float size, float alpha) {
        if (value == null || value.isEmpty()) {
            return;
        }
        HudFont.drawString(font, value, x, y, color(rgb, alpha), size);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

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

    // ==================================================================
    // 工具
    // ==================================================================

    private static boolean moduleEnabled() {
        Module telly = ModuleManager.getModule("Telly");
        if (telly != null && telly.isEnabled()) return TellyFeature.isHud();
        Module module = ModuleManager.getModule("Scaffold");
        return module != null && module.isEnabled() && ScaffoldFeature.isHud();
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
}
