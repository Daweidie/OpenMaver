import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.opengl.GL11;

import java.lang.reflect.Method;

/**
 * Scaffold 方块计数 HUD。
 *
 * <p>参考 {@link TargetHud} 的信息卡风格：深色毛玻璃圆角卡片 + 方块图标 + 剩余数量。
 * 图标直接使用玩家当前手持（或快捷栏里第一组可放置）方块的真实物品模型，
 * 因此会跟随自动切方块实时变化；没有可用方块时退化为手绘的等距方块。
 *
 * <p>每次成功放置一个方块，{@link #onBlockPlaced()} 记录一次时间戳，卡片里的方块
 * 图标会左右抖动一下并快速衰减。数量每帧直接从快捷栏重新统计，因此在生存模式下
 * 会立刻减 1；创造模式不消耗方块，抖动仍然会播放。
 */
public final class ScaffoldHud {

    // ------------------------------------------------------------------
    // 卡片布局（与 TargetHUD 保持一致的比例）
    // ------------------------------------------------------------------

    private static final int CARD_W = 160;
    private static final int CARD_H = 44;
    private static final float CARD_R = 8.0F;

    private static final float ICON_X = 8.0F;
    private static final float ICON_Y = 10.0F;
    private static final float ICON_SIZE = 24.0F;
    /** 真实物品模型在图标槽里的显示尺寸（16px 原图放大到 22px）。 */
    private static final float ITEM_SIZE = 22.0F;

    private static final float CONTENT_X = 37.0F;
    private static final float CONTENT_W = 117.0F;
    private static final float NAME_Y = 6.0F;
    private static final float NAME_SIZE = 0.95F;
    private static final float COUNT_Y = 17.0F;
    private static final float COUNT_SIZE = 1.3F;
    private static final float SUFFIX_Y = 25.0F;
    private static final float SUFFIX_SIZE = 0.72F;

    private static final int TEXT = 0xF2F5FA;
    private static final int COUNT = 0xFFFFFF;
    private static final int COUNT_EMPTY = 0xFF5555;
    private static final int MUTED = 0x9AA4B2;
    /** 与 Target HUD 一致的细描边：低对比冷灰，不再使用白色高光。 */
    private static final int BORDER = 0x2E333B;
    private static final int SHADOW = 0x000000;
    private static final float SHADOW_ALPHA = 0.28F;
    /** 图标后面的极淡圆角底盘，让浅色方块也有稳定的边界。 */
    private static final int ICON_PLATE = 0xFFFFFF;
    private static final float ICON_PLATE_ALPHA = 0.055F;
    private static final int GLASS = 0x0D0F13;
    private static final float GLASS_TINT_STRENGTH = 0.62F;
    private static final float GLASS_ALPHA = 0.90F;

    /** 放置后的方块抖动：总时长与幅度（GUI 单位）。 */
    private static final long SHAKE_MS = 200L;
    private static final float SHAKE_AMPLITUDE = 3.0F;
    private static final float SHAKE_CYCLES = 2.5F;

    /** 卡片出现/消失动画：时长 + 从右侧滑入的距离。 */
    private static final long FADE_IN_MS = 170L;
    private static final long FADE_OUT_MS = 150L;
    private static final float SLIDE_DISTANCE = 18.0F;

    private static long lastPlaceNanos;
    private static float shakePhase;
    private static long lastFrameNanos;
    private static float visible;
    private static boolean failed;

    /** 淡出期间继续显示最后一次的内容。 */
    private static int cachedCount;
    private static Object cachedStack;
    private static String cachedName = "No blocks";

    /** 用于检测「数字减少」并触发抖动（兼容非 ScaffoldFeature 的放置路径）。 */
    private static int lastCount = -1;
    private static String lastName;

    /** RenderHelper 的 GUI 物品光照；未初始化时通过反射查找一次。 */
    private static Method enableItemLighting;
    private static Method disableItemLighting;
    private static boolean lightingLookupFailed;

    private ScaffoldHud() {
    }

    // ==================================================================
    // 入口
    // ==================================================================

    /** 由 {@link ScaffoldFeature} 在成功放置方块后调用。 */
    public static void onBlockPlaced() {
        lastPlaceNanos = System.nanoTime();
        // 随机初相位：连续快速放置时每次抖动方向不同，不会因为重置到 0 而互相抵消。
        shakePhase = (float) (Math.random() * Math.PI * 2.0D);
    }

    /** 模块开启且方块计数开启时返回 true；HUD 用它决定是否提前抓取毛玻璃快照。 */
    public static boolean isEnabled() {
        if (failed) {
            return false;
        }
        if (visible > 0.01F) {
            return true;
        }
        Module module = ModuleManager.getModule("Scaffold");
        return module != null && module.isEnabled() && ScaffoldFeature.isBlockCounter();
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
        Module module = mc == null ? null : ModuleManager.getModule("Scaffold");
        boolean active = mc != null && module != null && module.isEnabled()
                && ScaffoldFeature.isBlockCounter();
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
        float deltaMs = Math.max(1.0F, Math.min(50.0F, frameNanos / 1_000_000.0F));
        float duration = active ? FADE_IN_MS : FADE_OUT_MS;
        visible = approach(visible, active ? 1.0F : 0.0F, deltaMs / duration);
        if (visible <= 0.01F) {
            if (!active) {
                reset();
            }
            return;
        }

        if (active) {
            int count = ScaffoldFeature.hudBlockCount(player);
            Object stack = ScaffoldFeature.hudBlockStack(player);
            String name = stack == null
                    ? "No blocks"
                    : trimToWidth(font, displayName(stack), NAME_SIZE, (int) CONTENT_W);
            // 数量在同一组方块上减少时抖一下；即使放置不是走 ScaffoldFeature.place()
            // 也能得到反馈。换方块导致的数量变化不算。
            if (lastName != null && lastName.equals(name) && count < lastCount) {
                onBlockPlaced();
            }
            cachedCount = count;
            cachedStack = stack;
            cachedName = name;
            lastCount = count;
            lastName = name;
        }

        drawCard(font, resolution, cachedCount, cachedStack, cachedName, now, visible);
    }

    private static void reset() {
        visible = 0.0F;
        lastFrameNanos = 0L;
        lastPlaceNanos = 0L;
        cachedCount = 0;
        cachedStack = null;
        cachedName = "No blocks";
        lastCount = -1;
        lastName = null;
    }

    // ==================================================================
    // 卡片
    // ==================================================================

    private static void drawCard(FontRenderer font, ScaledResolution resolution,
            int count, Object stack, String name, long now, float alpha) {
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
        // Target HUD 也锚定在准星右侧；同时显示时把方块卡向下错开，避免叠在一起。
        float cardY = screenH / 2.0F - drawH / 2.0F;
        if (TargetHud.isShowing()) {
            cardY += TargetHud.cardHeight() + 6.0F * scale;
        }
        cardY = Math.max(4.0F, Math.min(cardY, screenH - drawH - 4.0F));

        // 出现时从右向左滑入，消失时反向滑出，同时淡入淡出。
        float slide = (1.0F - alpha) * SLIDE_DISTANCE * scale;
        float drawX = cardX + slide;
        float[] shake = shakeOffset(now);

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

        // 柔和投影 + 低对比细描边 + 深色毛玻璃，与 Target HUD 使用同一套卡片语言。
        roundRect(2.0F, 3.0F, CARD_W, CARD_H, CARD_R,
                color(SHADOW, SHADOW_ALPHA * alpha));
        roundRect(0.0F, 0.0F, CARD_W, CARD_H, CARD_R,
                color(BORDER, 0.85F * alpha));
        GuiBlur.draw(Math.round(drawX + scale), Math.round(cardY + scale),
                Math.round(drawW - scale * 2.0F), Math.round(drawH - scale * 2.0F),
                Math.max(0.0F, (CARD_R - 1.0F) * scale), GLASS,
                GLASS_TINT_STRENGTH, alpha * GLASS_ALPHA);

        // 图标圆角底盘：只加很淡的一层，白/浅色方块不会被纯黑方块衬底吃掉。
        roundRect(ICON_X - 1.0F, ICON_Y - 1.0F, ICON_SIZE + 2.0F,
                ICON_SIZE + 2.0F, 5.0F, color(ICON_PLATE, ICON_PLATE_ALPHA * alpha));

        // 物品模型无法直接设置透明度；用缩放代替淡入淡出，避免关闭时背景先消失、
        // 不透明的方块模型却还停留一段时间。
        float iconScale = alpha;
        float iconCenterX = ICON_X + ICON_SIZE / 2.0F;
        float iconCenterY = ICON_Y + ICON_SIZE / 2.0F;
        GL11.glPushMatrix();
        GL11.glTranslatef(iconCenterX, iconCenterY, 0.0F);
        GL11.glScalef(iconScale, iconScale, 1.0F);
        GL11.glTranslatef(-iconCenterX, -iconCenterY, 0.0F);
        drawIcon(stack, alpha, shake[0], shake[1]);
        GL11.glPopMatrix();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        text(font, name, (int) CONTENT_X, (int) NAME_Y, TEXT, NAME_SIZE, alpha);

        String countText = String.valueOf(count);
        String suffix = count == 1 ? " block left" : " blocks left";
        text(font, countText, (int) CONTENT_X, (int) COUNT_Y,
                count > 0 ? COUNT : COUNT_EMPTY, COUNT_SIZE, alpha);
        int suffixX = (int) CONTENT_X
                + HudFont.getStringWidth(font, countText, COUNT_SIZE) + 3;
        text(font, suffix, suffixX, (int) SUFFIX_Y, MUTED, SUFFIX_SIZE, alpha);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    /** 抖动的横向/纵向偏移：一次放置触发，随机初相位 + 衰减振荡。 */
    private static float[] shakeOffset(long now) {
        float[] offset = new float[2];
        if (lastPlaceNanos == 0L) {
            return offset;
        }
        long elapsed = now - lastPlaceNanos;
        if (elapsed < 0L || elapsed >= SHAKE_MS) {
            return offset;
        }
        float t = elapsed / (float) SHAKE_MS;
        float decay = 1.0F - t;
        float angle = shakePhase + (float) (t * SHAKE_CYCLES * Math.PI * 2.0D);
        offset[0] = (float) Math.sin(angle) * SHAKE_AMPLITUDE * decay;
        offset[1] = (float) Math.sin(angle * 1.7F + 0.9F)
                * SHAKE_AMPLITUDE * 0.35F * decay;
        return offset;
    }

    // ==================================================================
    // 方块图标
    // ==================================================================

    private static void drawIcon(Object stack, float alpha, float shakeX, float shakeY) {
        float x = ICON_X + shakeX;
        float y = ICON_Y + shakeY;
        // 不再画深色方块底板：白/浅色方块会在卡片背景上留下明显的黑色方块。
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
            // Lighting is best-effort; the item still renders with whatever state is active.
        }
    }

    /** 物品渲染不可用时的等距方块，保证 HUD 永远有「方块样式」。 */
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

        GlColor.set(color(0x6E7783, alpha));
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
        HudFont.drawString(font, value, x, y, color(rgb, alpha), size);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    private static void roundRect(float x, float y, float w, float h, float radius,
            int argb) {
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

    private static int color(int rgb, float alpha) {
        int a = clamp255(Math.round(alpha * 255.0F));
        return a << 24 | (rgb & 0x00FFFFFF);
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    /**
     * 线性推进：按毫秒进度直接走到目标值，保证淡入/淡出在设定时长内真正结束，
     * 而不是指数逼近导致方块图标在背景消失后还残留一段时间。
     */
    private static float approach(float value, float goal, float amount) {
        float step = amount < 0.0F ? 0.0F : (amount > 1.0F ? 1.0F : amount);
        if (value < goal) {
            return Math.min(goal, value + step);
        }
        return Math.max(goal, value - step);
    }

    private static String displayName(Object stack) {
        Object value = OpenMaverFeatures.call(stack, "getDisplayName", "func_82833_r", null);
        String name = value instanceof String ? stripFormatting((String) value) : "";
        if (name == null || name.trim().isEmpty()) {
            name = "Blocks";
        }
        return name;
    }

    private static String stripFormatting(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String trimToWidth(FontRenderer font, String text, float size,
            int maxWidth) {
        if (text == null) {
            return "";
        }
        if (HudFont.getStringWidth(font, text, size) <= maxWidth) {
            return text;
        }
        String suffix = "..";
        int end = text.length();
        while (end > 0
                && HudFont.getStringWidth(font, text.substring(0, end) + suffix, size)
                        > maxWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }
}
