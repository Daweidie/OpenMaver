import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;

/**
 * OpenMaver 游戏内 HUD 提示：右下角一个长方形卡片，显示「注入成功！！」。
 *
 * <p>动画（全部按系统时间计算，与帧率无关）：
 * <ol>
 *   <li>滑入 350ms：从屏幕右侧滑入目标位置，透明度 0→1，ease-out</li>
 *   <li>停留 2500ms</li>
 *   <li>滑出 350ms：向右滑出并淡出，ease-in</li>
 * </ol>
 *
 * <p>通过 {@link #success(String)} 触发；实际绘制在
 * {@link RenderGameOverlayEvent.Post}（type=ALL）里完成，此时 GL 矩阵已是缩放坐标。
 *
 * <p>本类位于默认包，与其它 mc/ 集成类一致。
 */
@SideOnly(Side.CLIENT)
public final class OpenMaverHud {

    private static final long SLIDE_IN_MS = 350L;
    private static final long HOLD_MS = 2500L;
    private static final long SLIDE_OUT_MS = 350L;
    private static final long TOTAL_MS = SLIDE_IN_MS + HOLD_MS + SLIDE_OUT_MS;

    private static final int WIDTH = 160;
    private static final int HEIGHT = 28;
    private static final int MARGIN = 8;
    private static final int ACCENT_W = 3;

    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_BG = 0x101010;   // 深色卡片
    private static final int COLOR_ACCENT = 0x55FF55; // 绿色高亮

    /** 当前提示文案；为 null 表示无提示。 */
    private static String message = null;

    /** 动画起点（System.nanoTime）。 */
    private static long startNanos = 0L;

    /** 出错后停用，避免每帧抛异常。 */
    private static boolean failed = false;

    public OpenMaverHud() {
        // 由 Forge 事件总线实例化/注册
    }

    /** 触发一次右下角成功提示。 */
    public static void success(String text) {
        message = text;
        startNanos = System.nanoTime();
    }

    /** 是否正在显示。 */
    public static boolean isShowing() {
        return message != null;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (failed || message == null) {
            return;
        }
        try {
            if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
                return;
            }
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            if (elapsed >= TOTAL_MS) {
                message = null;
                return;
            }
            render(event.resolution, elapsed);
        } catch (Throwable t) {
            failed = true;
            message = null;
            System.out.println("[OpenMaver] HUD 绘制出错，已停用: " + t);
        }
    }

    private static void render(ScaledResolution resolution, long elapsed) {
        // 计算动画进度：progress 0→1（卡片位置），alpha 0→1（透明度）
        float progress;
        float alpha;
        if (elapsed < SLIDE_IN_MS) {
            float t = elapsed / (float) SLIDE_IN_MS;
            progress = easeOutCubic(t);
            alpha = t;
        } else if (elapsed < SLIDE_IN_MS + HOLD_MS) {
            progress = 1.0f;
            alpha = 1.0f;
        } else {
            float t = (elapsed - SLIDE_IN_MS - HOLD_MS) / (float) SLIDE_OUT_MS;
            progress = 1.0f - easeInCubic(t);
            alpha = 1.0f - t;
        }

        int screenW = resolution.getScaledWidth();
        int screenH = resolution.getScaledHeight();

        int targetX = screenW - WIDTH - MARGIN;
        int targetY = screenH - HEIGHT - MARGIN;

        // 从右侧屏外滑入：起点 x = 屏宽
        int x = (int) (screenW + (targetX - screenW) * progress);
        int y = targetY;

        int a = clamp255((int) (alpha * 255.0f));
        int bgColor = (a << 24) | COLOR_BG;
        int accentColor = (a << 24) | COLOR_ACCENT;

        // 卡片 + 左侧绿色竖条
        drawRect(x, y, x + WIDTH, y + HEIGHT, bgColor);
        drawRect(x, y, x + ACCENT_W, y + HEIGHT, accentColor);

        // 文本（带阴影）
        FontRenderer font = (FontRenderer) readField(Minecraft.getMinecraft(),
                "fontRendererObj", "field_71466_p");
        int textX = x + ACCENT_W + (WIDTH - ACCENT_W - font.getStringWidth(message)) / 2;
        int textY = y + (HEIGHT - 8) / 2;
        int textColor = (a << 24) | COLOR_TEXT;
        font.drawStringWithShadow(message, (float) textX, (float) textY, textColor);

        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------
    // 动画缓动
    // ------------------------------------------------------------------

    private static float easeOutCubic(float t) {
        float u = 1.0f - t;
        return 1.0f - u * u * u;
    }

    private static float easeInCubic(float t) {
        return t * t * t;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 用 GL 直接画一个带 alpha 的实心矩形。 */
    private static void drawRect(int left, int top, int right, int bottom, int argb) {
        float a = (argb >> 24 & 0xFF) / 255.0f;
        float r = (argb >> 16 & 0xFF) / 255.0f;
        float g = (argb >> 8 & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(left, top);
        GL11.glVertex2f(right, top);
        GL11.glVertex2f(right, bottom);
        GL11.glVertex2f(left, bottom);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
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
}
