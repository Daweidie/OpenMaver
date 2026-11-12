import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenMaver 游戏内 HUD：右侧垂直堆叠的模块列表（ModuleList）。
 *
 * <p>视觉稿：
 * <ul>
 *   <li>每项是半透明深色圆角胶囊，右对齐堆叠，整体贴屏幕右侧</li>
 *   <li>左侧 3px 彩色竖条：颜色由模块分类决定，亮度表示启用状态</li>
 *   <li>模块名使用 Open Sans 无衬线字体（视觉稿 14px 规格），右侧是圆角小键帽形式的绑定键位</li>
 *   <li>启用项为高亮文字 + 青/紫/粉分类点缀 + 轻微外发光；禁用项整体降低不透明度</li>
 *   <li>启用时从右侧轻微滑入并淡入，同时伴随一次短暂的外发光脉冲</li>
 * </ul>
 *
 * <p>动画全部基于 {@link System#nanoTime()}，与帧率无关。列表行按注册顺序错峰出现，
 * 因此首次进入世界时是一段很轻的瀑布式滑入。
 *
 * <p>右下角仍保留注入成功的胶囊提示（由 {@link #success(String)} 触发），
 * 新提示会从屏幕右侧滑入，停留后淡出。
 */
@SideOnly(Side.CLIENT)
public final class OpenMaverHud {

    // ------------------------------------------------------------------
    // 模块列表布局
    // ------------------------------------------------------------------

    private static final int MARGIN = 12;
    private static final int ROW_H = 24;
    private static final int ROW_GAP = 5;
    private static final int ACCENT_W = 3;
    private static final int LEFT_PAD = 16;
    private static final int RIGHT_PAD = 9;
    private static final int KEY_GAP = 10;
    private static final int KEY_H = 14;
    private static final int PILL_MIN_W = 124;
    private static final int PILL_MAX_W = 212;

    // ------------------------------------------------------------------
    // 动效时长
    // ------------------------------------------------------------------

    private static final long APPEAR_MS = 260L;
    private static final long ENABLE_MS = 240L;
    private static final long FADE_MS = 200L;
    private static final long GLOW_MS = 420L;
    private static final long STAGGER_MS = 45L;

    private static final long TOAST_IN_MS = 320L;
    private static final long TOAST_HOLD_MS = 2400L;
    private static final long TOAST_OUT_MS = 300L;
    private static final long TOAST_TOTAL_MS = TOAST_IN_MS + TOAST_HOLD_MS + TOAST_OUT_MS;
    private static final int TOAST_H = 28;

    // ------------------------------------------------------------------
    // 配色：青 / 紫 / 粉三色点缀，其余为中性深色
    // ------------------------------------------------------------------

    private static final int ACCENT_CYAN = 0x5CE1FF;
    private static final int ACCENT_PURPLE = 0xB18CFF;
    private static final int ACCENT_PINK = 0xFF79C6;
    private static final int[] ACCENTS = {ACCENT_CYAN, ACCENT_PURPLE, ACCENT_PINK};

    private static final int TEXT_ON = 0xF4F8FF;
    private static final int TEXT_OFF = 0xA7B3C6;

    /** 启用项底板：半透明中性灰；禁用项与提示保留原来的深色玻璃。 */
    private static final int ENABLED_PILL_BG = 0x808080;
    private static final int ENABLED_PILL_BG_ALPHA = 96;
    private static final int DISABLED_PILL_BG = 0x0D131B;
    private static final int DISABLED_PILL_BG_ALPHA = 174;
    private static final int TOAST_PILL_BG = 0x0F1621;
    private static final int TOAST_PILL_BG_ALPHA = 204;

    /** 启用/禁用行的整体不透明度。 */
    private static final float ALPHA_ON = 1.0f;
    private static final float ALPHA_OFF = 0.44f;

    /** 每个模块独立保存的动画状态，以「配置路径 + 名称」为 key。 */
    private static final Map<String, RowAnim> ROW_ANIMS = new HashMap<String, RowAnim>();

    /** 当前提示文案；为 null 表示无提示。 */
    private static String message = null;

    /** 提示动画起点（System.nanoTime）。 */
    private static long messageStartNanos = 0L;

    /** 出错后停用，避免每帧抛异常。 */
    private static boolean failed = false;

    public OpenMaverHud() {
        // 由 Forge 事件总线实例化/注册
    }

    /** 触发一次右下角成功提示。 */
    public static void success(String text) {
        message = text;
        messageStartNanos = System.nanoTime();
    }

    /** 是否正在显示提示。 */
    public static boolean isShowing() {
        return message != null;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (failed || event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        try {
            FontRenderer font = (FontRenderer) readField(Minecraft.getMinecraft(),
                    "fontRendererObj", "field_71466_p");
            if (font == null) {
                return;
            }
            long now = System.nanoTime();
            List<Module> modules = ModuleManager.getModules();
            List<HudEntry> entries = new ArrayList<HudEntry>(modules.size() + 2);
            for (int i = 0; i < modules.size(); i++) {
                Module module = modules.get(i);
                entries.add(new HudEntry(module.getConfigKey() + "#" + module.getName(),
                        module.getName(), keyLabel(module), accentFor(module), module.isEnabled()));
            }
            entries.add(new HudEntry("feature#aim", "Aim Assist", "--", ACCENT_PINK,
                    OpenMaverFeatures.isAimEnabled()));
            entries.add(new HudEntry("feature#esp", "Player ESP", "--", ACCENT_PURPLE,
                    OpenMaverFeatures.isEspEnabled()));
            int listBottom = renderModuleList(font, event.resolution, entries, now);
            renderToast(font, event.resolution, now, listBottom);
        } catch (Throwable t) {
            failed = true;
            message = null;
            System.out.println("[OpenMaver] HUD 绘制出错，已停用: " + t);
        }
    }

    // ==================================================================
    // 模块列表
    // ==================================================================

    private static int renderModuleList(FontRenderer font, ScaledResolution resolution,
            List<HudEntry> entries, long now) {
        if (entries.isEmpty()) {
            return MARGIN;
        }

        final int count = entries.size();
        final String[] labels = new String[count];
        final String[] keys = new String[count];
        final int[] accents = new int[count];

        int nameWidth = 0;
        int keyWidth = 0;
        for (int i = 0; i < count; i++) {
            HudEntry entry = entries.get(i);
            labels[i] = entry.name;
            keys[i] = entry.key;
            accents[i] = entry.accent;
            nameWidth = Math.max(nameWidth, OpenSansFont.getStringWidth(font, labels[i]));
            keyWidth = Math.max(keyWidth, OpenSansFont.getStringWidth(font, keys[i]));
        }

        int keyCapWidth = Math.max(keyWidth + 9, 18);
        int pillWidth = LEFT_PAD + ACCENT_W + 7 + nameWidth + KEY_GAP + keyCapWidth + RIGHT_PAD;
        pillWidth = clamp(pillWidth, PILL_MIN_W, PILL_MAX_W);

        int totalHeight = count * ROW_H + (count - 1) * ROW_GAP;
        int screenW = resolution.getScaledWidth();
        int screenH = resolution.getScaledHeight();
        int right = screenW - MARGIN;
        int startY = MARGIN;
        if (startY + totalHeight > screenH - MARGIN) {
            startY = Math.max(MARGIN, screenH - MARGIN - totalHeight);
        }

        List<TextDraw> texts = new ArrayList<TextDraw>(count * 2);

        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH);

        for (int i = 0; i < count; i++) {
            HudEntry entry = entries.get(i);
            boolean enabled = entry.enabled;
            RowAnim anim = rowAnim(entry, now);

            long appearElapsed = now - anim.appearStartNanos;
            float appear = easeOutCubic(clamp01(appearElapsed / nanos(APPEAR_MS)));
            int slide = (int) ((1.0f - appear) * 18.0f);

            float fadeAlpha = stateAlpha(anim, enabled, now);
            float alpha = appear * fadeAlpha;
            if (enabled && anim.enableStartNanos > 0L) {
                float enable = easeOutCubic(
                        clamp01((now - anim.enableStartNanos) / nanos(ENABLE_MS)));
                alpha *= 0.25f + 0.75f * enable;
                slide += (int) ((1.0f - enable) * 10.0f);
            }

            float pulse = 0.0f;
            if (enabled && anim.enableStartNanos > 0L) {
                float t = clamp01((now - anim.enableStartNanos) / nanos(GLOW_MS));
                pulse = 1.0f - easeOutCubic(t);
            }

            int x = right - pillWidth + slide;
            int y = startY + i * (ROW_H + ROW_GAP);
            int keyCapW = keyCapWidth;
            int keyCapX = x + pillWidth - RIGHT_PAD - keyCapW;
            int keyCapY = y + (ROW_H - KEY_H) / 2;

            int pillBackground = enabled ? ENABLED_PILL_BG : DISABLED_PILL_BG;
            int pillBackgroundAlpha = enabled ? ENABLED_PILL_BG_ALPHA
                    : DISABLED_PILL_BG_ALPHA;
            drawPillBackground(x, y, pillWidth, ROW_H, accents[i], enabled, alpha, pulse,
                    keyCapX, keyCapY, keyCapW, KEY_H, pillBackground, pillBackgroundAlpha);

            int nameColor = withAlpha(enabled ? TEXT_ON : TEXT_OFF,
                    (int) (alpha * 255.0f));
            texts.add(new TextDraw(labels[i], x + LEFT_PAD + ACCENT_W + 7, y + 8, nameColor));

            int labelW = OpenSansFont.getStringWidth(font, keys[i]);
            int keyColor = withAlpha(enabled ? 0xEAF8FF : 0xA7B3C6,
                    (int) (alpha * (enabled ? 235.0f : 175.0f)));
            texts.add(new TextDraw(keys[i], keyCapX + (keyCapW - labelW) / 2,
                    keyCapY + 3, keyColor));
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopAttrib();
        GlColor.restore();

        drawTexts(font, texts);
        return startY + totalHeight;
    }

    /**
     * 胶囊底板：阴影 -> 外发光 -> 1px 边框 -> 半透明深色填充 -> 左侧分类竖条 -> 右侧键帽。
     */
    private static void drawPillBackground(int x, int y, int w, int h, int accent,
            boolean enabled, float alpha, float pulse,
            int keyCapX, int keyCapY, int keyCapW, int keyCapH,
            int backgroundRgb, int backgroundAlpha) {
        int radius = h / 2;

        // 柔和投影，让半透明胶囊在明亮场景里也能与背景分离。
        roundRect(x + 2, y + 3, w, h, radius, withAlpha(0x000000, (int) (alpha * 46.0f)));

        // 启用项外发光；刚启用时叠加一次更亮的脉冲。
        if (enabled || pulse > 0.01f) {
            int outer = (int) (alpha * (13.0f + 22.0f * pulse));
            int inner = (int) (alpha * (18.0f + 40.0f * pulse));
            roundRect(x - 2, y - 2, w + 4, h + 4, radius + 2, withAlpha(accent, outer));
            roundRect(x - 1, y - 1, w + 2, h + 2, radius + 1, withAlpha(accent, inner));
        }

        int borderAlpha = enabled ? (int) (alpha * 46.0f) : (int) (alpha * 22.0f);
        roundRect(x, y, w, h, radius, withAlpha(0xFFFFFF, borderAlpha));

        roundRect(x + 1, y + 1, w - 2, h - 2, Math.max(1, radius - 1),
                withAlpha(backgroundRgb, (int) (alpha * backgroundAlpha)));

        // 左侧细长分类色条。
        int barX = x + 7;
        int barY = y + 6;
        int barH = h - 12;
        if (enabled) {
            roundRect(barX - 1, barY - 1, ACCENT_W + 2, barH + 2, 3,
                    withAlpha(accent, (int) (alpha * 38.0f)));
        }
        roundRect(barX, barY, ACCENT_W, barH, 2,
                withAlpha(accent, enabled ? (int) (alpha * 255.0f) : (int) (alpha * 112.0f)));

        // 右侧键位小键帽。
        if (keyCapW > 0 && keyCapH > 0) {
            int capBorder = enabled ? (int) (alpha * 30.0f) : (int) (alpha * 16.0f);
            int capFill = enabled ? (int) (alpha * 20.0f) : (int) (alpha * 10.0f);
            roundRect(keyCapX, keyCapY, keyCapW, keyCapH, 4,
                    withAlpha(0xFFFFFF, capBorder));
            roundRect(keyCapX + 1, keyCapY + 1, keyCapW - 2, keyCapH - 2, 3,
                    withAlpha(0xFFFFFF, capFill));
        }
    }

    private static RowAnim rowAnim(HudEntry entry, long now) {
        RowAnim anim = ROW_ANIMS.get(entry.id);
        if (anim == null) {
            anim = new RowAnim(now + ROW_ANIMS.size() * nanos(STAGGER_MS), entry.enabled);
            ROW_ANIMS.put(entry.id, anim);
            return anim;
        }
        if (anim.lastEnabled != entry.enabled) {
            anim.wasEnabled = anim.lastEnabled;
            anim.lastEnabled = entry.enabled;
            anim.lastChangeNanos = now;
            if (entry.enabled) {
                anim.enableStartNanos = now;
            }
        }
        return anim;
    }

    private static float stateAlpha(RowAnim anim, boolean enabled, long now) {
        float target = enabled ? ALPHA_ON : ALPHA_OFF;
        if (anim.lastChangeNanos <= 0L) {
            return target;
        }
        float t = easeOutCubic(clamp01((now - anim.lastChangeNanos) / nanos(FADE_MS)));
        float from = anim.wasEnabled ? ALPHA_ON : ALPHA_OFF;
        return from + (target - from) * t;
    }

    private static String keyLabel(Module module) {
        String key = module.getKeybind();
        return (key == null || key.isEmpty()) ? "--" : key;
    }

    private static int accentFor(Module module) {
        String configKey = module.getConfigKey();
        String category = "";
        if (configKey != null) {
            int dot = configKey.indexOf('.');
            category = (dot > 0 ? configKey.substring(0, dot) : configKey)
                    .toLowerCase(Locale.ROOT);
        }
        if ("movement".equals(category)) {
            return ACCENT_CYAN;
        }
        if ("combat".equals(category)) {
            return ACCENT_PINK;
        }
        if ("render".equals(category)) {
            return ACCENT_PURPLE;
        }
        int hash = category.hashCode() ^ module.getName().hashCode();
        return ACCENTS[(hash & 0x7FFFFFFF) % ACCENTS.length];
    }

    // ==================================================================
    // 右下角提示（保留注入成功反馈）
    // ==================================================================

    private static void renderToast(FontRenderer font, ScaledResolution resolution,
            long now, int listBottom) {
        if (message == null) {
            return;
        }
        long elapsedMs = (now - messageStartNanos) / 1_000_000L;
        if (elapsedMs >= TOAST_TOTAL_MS) {
            message = null;
            return;
        }

        float progress;
        float alpha;
        if (elapsedMs < TOAST_IN_MS) {
            float t = elapsedMs / (float) TOAST_IN_MS;
            progress = easeOutCubic(t);
            alpha = t;
        } else if (elapsedMs < TOAST_IN_MS + TOAST_HOLD_MS) {
            progress = 1.0f;
            alpha = 1.0f;
        } else {
            float t = (elapsedMs - TOAST_IN_MS - TOAST_HOLD_MS) / (float) TOAST_OUT_MS;
            progress = 1.0f - easeInCubic(t);
            alpha = 1.0f - t;
        }

        int screenW = resolution.getScaledWidth();
        int screenH = resolution.getScaledHeight();
        String key = "v" + OpenMaver.VERSION;
        int labelW = OpenSansFont.getStringWidth(font, message);
        int keyW = OpenSansFont.getStringWidth(font, key);
        int keyCapW = keyW + 9;
        int width = LEFT_PAD + ACCENT_W + 7 + labelW + KEY_GAP + keyCapW + RIGHT_PAD;
        width = Math.max(158, width);

        int targetX = screenW - MARGIN - width;
        int targetY = screenH - MARGIN - TOAST_H;
        // 模块列表较长时优先保证不重叠。
        if (targetY < listBottom + 8) {
            return;
        }
        int x = (int) (screenW + (targetX - screenW) * progress);
        int y = targetY;

        int keyCapX = x + width - RIGHT_PAD - keyCapW;
        int keyCapY = y + (TOAST_H - KEY_H) / 2;
        float pulse = 1.0f - progress;

        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH);
        drawPillBackground(x, y, width, TOAST_H, ACCENT_CYAN, true, alpha, pulse,
                keyCapX, keyCapY, keyCapW, KEY_H, TOAST_PILL_BG, TOAST_PILL_BG_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopAttrib();
        GlColor.restore();

        int textColor = withAlpha(0xF2FBFF, (int) (alpha * 245.0f));
        int keyColor = withAlpha(0xEAF8FF, (int) (alpha * 235.0f));
        OpenSansFont.drawString(font, message, x + LEFT_PAD + ACCENT_W + 7, y + 9,
                textColor);
        OpenSansFont.drawString(font, key, keyCapX + (keyCapW - keyW) / 2,
                keyCapY + 3, keyColor);
    }

    // ==================================================================
    // GL 绘制辅助
    // ==================================================================

    private static void drawTexts(FontRenderer font, List<TextDraw> texts) {
        for (int i = 0; i < texts.size(); i++) {
            TextDraw text = texts.get(i);
            OpenSansFont.drawString(font, text.text, text.x, text.y, text.color);
        }
    }

    private static void roundRect(int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
            return;
        }
        float r = Math.min(radius, Math.min(w / 2.0F, h / 2.0F));
        setColor(argb);
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

    private static void setColor(int argb) {
        GlColor.set(argb);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (clamp255(alpha) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    // ==================================================================
    // 动画缓动
    // ==================================================================

    private static float clamp01(float value) {
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }

    private static float easeOutCubic(float t) {
        float u = 1.0f - t;
        return 1.0f - u * u * u;
    }

    private static float easeInCubic(float t) {
        return t * t * t;
    }

    private static long nanos(long millis) {
        return millis * 1_000_000L;
    }

    private static Object readField(Object target, String... names) {
        Class<?> type = target.getClass();
        for (String name : names) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Try the type based fallback below.
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.getType().getName().equals("net.minecraft.client.gui.FontRenderer")) {
                field.setAccessible(true);
                try {
                    return field.get(target);
                } catch (IllegalAccessException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 一行中的一个文本绘制请求，等所有底板画完后再统一交给字体渲染器。 */
    private static final class TextDraw {
        final String text;
        final int x;
        final int y;
        final int color;

        TextDraw(String text, int x, int y, int color) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.color = color;
        }
    }

    private static final class HudEntry {
        final String id;
        final String name;
        final String key;
        final int accent;
        final boolean enabled;

        HudEntry(String id, String name, String key, int accent, boolean enabled) {
            this.id = id;
            this.name = name;
            this.key = key;
            this.accent = accent;
            this.enabled = enabled;
        }
    }

    /** 单个模块行的动画状态。 */
    private static final class RowAnim {
        final long appearStartNanos;
        boolean lastEnabled;
        boolean wasEnabled;
        long lastChangeNanos;
        long enableStartNanos;

        RowAnim(long appearStartNanos, boolean enabled) {
            this.appearStartNanos = appearStartNanos;
            this.lastEnabled = enabled;
            this.wasEnabled = enabled;
        }
    }
}
