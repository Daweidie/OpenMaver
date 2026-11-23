import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Target HUD — 准星右侧的极简目标信息卡。
 *
 * <p>只保留四样信息：正方形头像（玩家真实皮肤 / 无皮肤时使用 Steve 头像）、名称、
 * 血量读数（当前血量/总血量）、细血条。血条颜色随血量从绿（满血）过渡到橙、再过渡到
 * 深红（低血），无外圈、无发光、无装饰线。
 *
 * <p>目标来源：仅当准星真正命中 {@link EntityLivingBase} 时显示，移开准星立即隐藏；
 * 不做视野锥自动索敌，也不保留历史目标。任何生物（玩家、怪物、动物）都可显示。
 */
public final class TargetHud {

    private static final int CARD_W = 160;
    private static final int CARD_H = 44;
    private static final float CARD_R = 8.0F;

    private static final float AVATAR_X = 8.0F;
    private static final float AVATAR_Y = 10.0F;
    private static final float AVATAR_SIZE = 24.0F;
    private static final float AVATAR_RADIUS = 6.0F;

    private static final float CONTENT_X = 37.0F;
    private static final float CONTENT_W = 117.0F;
    /** 血条上方的血量读数：当前血量/总血量。 */
    private static final float HEALTH_TEXT_Y = 18.0F;
    private static final float HEALTH_TEXT_SIZE = 0.78F;
    private static final float BAR_Y = 30.0F;
    private static final float BAR_H = 4.0F;

    private static final int HEALTH_FULL = 0x4ADE80; // green
    private static final int HEALTH_MID = 0xFF9F43;  // orange
    private static final int HEALTH_LOW = 0xD7263D;  // deep red
    private static final int TEXT = 0xF2F5FA;
    private static final int HEALTH_TEXT_MAX = 0x9AA4B2;
    private static final int MUTED_BORDER = 0x2E333B;
    private static final int SHADOW = 0x000000;
    private static final float SHADOW_ALPHA = 0.28F;
    private static final int TRACK = 0x22262D;
    private static final int GLASS = 0x0D0F13;
    private static final float GLASS_TINT_STRENGTH = 0.62F;
    private static final float GLASS_ALPHA = 0.90F;

    private static final ResourceLocation STEVE_SKIN =
            new ResourceLocation("textures/entity/steve.png");

    /** 与 Scaffold HUD 相同的出现/消失动画：时长 + 从右侧滑入的距离。 */
    private static final long FADE_IN_MS = 170L;
    private static final long FADE_OUT_MS = 150L;
    private static final float SLIDE_DISTANCE = 18.0F;

    /** 血量读数 / 血条的平滑时间常数：掉血比回血稍慢，确保掉血过程看得见。 */
    private static final float HEALTH_DROP_SMOOTH_MS = 140.0F;
    private static final float HEALTH_GAIN_SMOOTH_MS = 80.0F;
    /** 与目标值差小于该值时直接吸附，避免数字长期停在 19.9 之类的中间值。 */
    private static final float HEALTH_SNAP = 0.02F;

    private static EntityLivingBase target;
    private static long lastFrameNanos;
    private static float visible;
    private static boolean failed;

    /** 淡出期间继续显示最后一次锁定的目标信息。 */
    private static String cachedName = "";
    private static String cachedHealthNow = "0";
    private static String cachedHealthMax = "/20";
    /** 当前「显示中」的血量/血量上限；每帧向真实值平滑逼近。 */
    private static float shownHealth;
    private static float shownMaxHealth = 20.0F;
    private static float cachedRatio = 1.0F;

    private TargetHud() {
    }

    // ==================================================================
    // 入口
    // ==================================================================

    public static void render(FontRenderer font, ScaledResolution resolution, long now) {
        if (failed || font == null || resolution == null) {
            return;
        }
        try {
            draw(font, resolution, now);
        } catch (Throwable error) {
            failed = true;
            reset();
            System.err.println("[OpenMaver] Target HUD disabled: " + error);
        }
    }

    /** 模块是否开启；HUD 用它决定是否提前抓取毛玻璃背景快照。 */
    public static boolean isEnabled() {
        if (failed) {
            return false;
        }
        // 关闭后仍需在淡出期间继续抓屏，否则卡片背景会先消失、头像却还在。
        if (visible > 0.01F) {
            return true;
        }
        Module module = ModuleManager.getModule("Target HUD");
        return module != null && module.isEnabled();
    }

    /** 卡片当前是否真的可见（目标锁定且淡入完成）。 */
    public static boolean isShowing() {
        return !failed && target != null && visible > 0.01F;
    }

    /** 缩放后的卡片高度，供其它锚定在准星右侧的 HUD 错开布局。 */
    public static float cardHeight() {
        return CARD_H * OpenMaverFeatures.getTargetHudScale();
    }

    private static void draw(FontRenderer font, ScaledResolution resolution, long now) {
        Minecraft mc = Minecraft.getMinecraft();
        Module module = ModuleManager.getModule("Target HUD");
        boolean enabled = mc != null && module != null && module.isEnabled();
        Object world = mc == null ? null
                : read(mc, "theWorld", "field_71441_e", "f", World.class);
        Object self = mc == null ? null
                : read(mc, "thePlayer", "field_71439_g", "i", EntityPlayer.class);
        Object screen = mc == null ? null
                : read(mc, "currentScreen", "field_71462_r", "h", Object.class);
        EntityPlayer player = self instanceof EntityPlayer ? (EntityPlayer) self : null;
        EntityLivingBase aimed = !enabled || world == null || player == null
                || screen != null || !alive(player)
                ? null
                : crosshairTarget(mc, player);
        if (AntiBotFeature.shouldFilterRender(target)) reset();
        boolean active = aimed != null;

        long frameNanos = lastFrameNanos == 0L ? 16_000_000L : now - lastFrameNanos;
        lastFrameNanos = now;
        float deltaMs = Math.max(1.0F, Math.min(50.0F, frameNanos / 1_000_000.0F));

        if (active) {
            // 只在准星真正命中生物时更新缓存；移开准星后缓存用于淡出。
            float actualHealth = health(aimed);
            float actualMax = Math.max(1.0F, maxHealth(aimed));
            if (target != aimed) {
                // 换目标时直接吸附到真实值，避免从上一个目标的血量滑过来。
                target = aimed;
                shownHealth = actualHealth;
                shownMaxHealth = actualMax;
            } else {
                // 血条和数字一起平滑：掉血慢一点、回血快一点。
                shownHealth = approachHealth(shownHealth, actualHealth, deltaMs);
                shownMaxHealth = approachHealth(shownMaxHealth, actualMax, deltaMs);
            }
            cachedName = trimToWidth(font, displayName(target), 0.95F, (int) CONTENT_W);
            cachedHealthNow = formatHealth(shownHealth);
            cachedHealthMax = "/" + formatHealth(shownMaxHealth);
            cachedRatio = clamp01(shownHealth / shownMaxHealth);
        }

        float duration = active ? FADE_IN_MS : FADE_OUT_MS;
        visible = approach(visible, active ? 1.0F : 0.0F, deltaMs / duration);
        if (visible <= 0.01F) {
            if (!active) {
                reset();
            }
            return;
        }
        if (target == null) {
            reset();
            return;
        }

        drawCard(font, resolution, visible);
    }

    private static void reset() {
        target = null;
        visible = 0.0F;
        lastFrameNanos = 0L;
        cachedName = "";
        cachedHealthNow = "0";
        cachedHealthMax = "/20";
        shownHealth = 0.0F;
        shownMaxHealth = 20.0F;
        cachedRatio = 1.0F;
    }

    // ==================================================================
    // 目标选择
    // ==================================================================

    private static EntityLivingBase crosshairTarget(Minecraft mc, EntityPlayer self) {
        Object hitObject = read(mc, "objectMouseOver", "field_71476_x", "s",
                MovingObjectPosition.class);
        if (!(hitObject instanceof MovingObjectPosition)) {
            return null;
        }
        MovingObjectPosition hit = (MovingObjectPosition) hitObject;
        Object type = read(hit, "typeOfHit", "field_72313_a", "a", null);
        if (type != MovingObjectPosition.MovingObjectType.ENTITY) {
            return null;
        }
        Object entity = read(hit, "entityHit", "field_72308_g", null, EntityLivingBase.class);
        if (!(entity instanceof EntityLivingBase)) {
            return null;
        }
        EntityLivingBase living = (EntityLivingBase) entity;
        return validTarget(living, self) ? living : null;
    }

    private static boolean validTarget(EntityLivingBase entity, EntityPlayer self) {
        if (entity == null || entity == self || !alive(entity)
                || AntiBotFeature.shouldFilterRender(entity)) {
            return false;
        }
        if (OpenMaverFeatures.isTargetHudPlayersOnly() && !(entity instanceof EntityPlayer)) {
            return false;
        }
        float range = OpenMaverFeatures.getTargetHudRange();
        return distance(self, entity) <= range;
    }

    /** 整数血量去掉小数点，半颗心等小数保留一位。 */
    private static String formatHealth(float value) {
        float rounded = Math.round(value);
        if (Math.abs(value - rounded) < 0.05F) {
            return String.valueOf((int) rounded);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static float health(Object entity) {
        Object value = call(entity, "getHealth", "func_110143_aJ", Number.class);
        return value instanceof Number ? ((Number) value).floatValue() : 0.0F;
    }

    private static float maxHealth(Object entity) {
        Object value = call(entity, "getMaxHealth", "func_110138_aP", Number.class);
        return value instanceof Number ? ((Number) value).floatValue() : 20.0F;
    }

    private static boolean alive(Object entity) {
        Object value = call(entity, "isEntityAlive", "func_70089_S", Boolean.class);
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static double distance(Object entity, Object other) {
        Object value = call(entity, "getDistanceToEntity", "func_70032_d", Number.class, other);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.MAX_VALUE;
    }

    // ==================================================================
    // 卡片
    // ==================================================================

    private static void drawCard(FontRenderer font, ScaledResolution resolution, float alpha) {
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
        cardY = Math.max(4.0F, Math.min(cardY, screenH - drawH - 4.0F));

        float ratio = cachedRatio;
        int barColor = healthColor(ratio);
        String name = cachedName;
        String healthNow = cachedHealthNow;
        String healthMax = cachedHealthMax;

        // 出现时从右向左滑入，消失时反向滑出，同时淡入淡出（与 Scaffold HUD 一致）。
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

        // 柔和投影 + 低对比描边 + 深色毛玻璃，与 Scaffold HUD 保持同一套卡片语言。
        roundRect(2.0F, 3.0F, CARD_W, CARD_H, CARD_R,
                color(SHADOW, SHADOW_ALPHA * alpha));
        roundRect(0.0F, 0.0F, CARD_W, CARD_H, CARD_R,
                color(MUTED_BORDER, 0.85F * alpha));
        GuiBlur.draw(Math.round(drawX + scale), Math.round(cardY + scale),
                Math.round(drawW - scale * 2.0F), Math.round(drawH - scale * 2.0F),
                Math.max(0.0F, (CARD_R - 1.0F) * scale), GLASS,
                GLASS_TINT_STRENGTH, alpha * GLASS_ALPHA);

        drawAvatar(alpha);

        // 名称
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        HudFont.useHudFont();
        text(font, name, (int) CONTENT_X, 6, TEXT, 0.95F, alpha);

        // 血量读数：位于血条正上方，格式为「当前血量/总血量」。
        HudFont.useMenuFont();
        int healthRight = (int) (CONTENT_X + CONTENT_W);
        int maxWidth = HudFont.getStringWidth(font, healthMax, HEALTH_TEXT_SIZE);
        int nowWidth = HudFont.getStringWidth(font, healthNow, HEALTH_TEXT_SIZE);
        sourceText(font, healthNow, healthRight - maxWidth - nowWidth, (int) HEALTH_TEXT_Y,
                barColor, HEALTH_TEXT_SIZE, alpha);
        sourceText(font, healthMax, healthRight - maxWidth, (int) HEALTH_TEXT_Y,
                HEALTH_TEXT_MAX, HEALTH_TEXT_SIZE, alpha);

        // 细血条：颜色随血量从绿 -> 橙 -> 深红渐变
        float barY = BAR_Y;
        roundRect(CONTENT_X, barY, CONTENT_W, BAR_H, BAR_H / 2.0F,
                color(TRACK, alpha));
        float fillW = CONTENT_W * clamp01(ratio);
        if (fillW > 0.5F) {
            roundRect(CONTENT_X, barY, fillW, BAR_H, BAR_H / 2.0F,
                    color(barColor, alpha));
        }

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    private static void drawAvatar(float alpha) {
        float hurt = hurtAmount();
        // 深色圆角底盘
        roundRect(AVATAR_X, AVATAR_Y, AVATAR_SIZE, AVATAR_SIZE, AVATAR_RADIUS,
                color(0x15181D, 0.98F * alpha));

        if (target instanceof AbstractClientPlayer) {
            try {
                if (drawSkin((AbstractClientPlayer) target, AVATAR_X, AVATAR_Y,
                        AVATAR_SIZE, alpha, hurt)) {
                    return;
                }
            } catch (Throwable ignored) {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GlColor.restore();
            }
        }

        // 无头像（非玩家生物 / 皮肤缺失）统一使用 Steve 头像
        drawDefaultSkin(alpha, hurt);
    }

    /** 受击时头像整体偏红，随时间自然恢复。 */
    private static float hurtAmount() {
        if (target == null) {
            return 0.0F;
        }
        Object value = read(target, "hurtTime", "field_70737_aN", null, Number.class);
        if (!(value instanceof Number)) {
            return 0.0F;
        }
        return clamp01(((Number) value).floatValue() / 10.0F);
    }

    private static void drawDefaultSkin(float alpha, float hurt) {
        Minecraft mc = Minecraft.getMinecraft();
        Object textures = mc == null ? null
                : call(mc, "getTextureManager", "func_110434_K", null);
        if (textures != null) {
            bindAndDrawFace(textures, STEVE_SKIN, alpha, hurt);
        }
    }

    private static boolean drawSkin(AbstractClientPlayer player, float x, float y,
            float size, float alpha, float hurt) {
        Minecraft mc = Minecraft.getMinecraft();
        Object textures = mc == null ? null
                : call(mc, "getTextureManager", "func_110434_K", null);
        Object skin = call(player, "getLocationSkin", "func_110311_f", null);
        if (textures == null || skin == null) {
            return false;
        }

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        setSkinTint(alpha, hurt);
        call(textures, "bindTexture", "func_110577_a", null, skin);
        drawFaceRounded(x, y, size, AVATAR_RADIUS, 8, 8);
        call(textures, "bindTexture", "func_110577_a", null, skin);
        drawFaceRounded(x, y, size, AVATAR_RADIUS, 40, 8);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GlColor.restore();
        return true;
    }

    private static void bindAndDrawFace(Object textures, Object skin, float alpha,
            float hurt) {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        setSkinTint(alpha, hurt);
        call(textures, "bindTexture", "func_110577_a", null, skin);
        drawFaceRounded(AVATAR_X, AVATAR_Y, AVATAR_SIZE, AVATAR_RADIUS, 8, 8);
        call(textures, "bindTexture", "func_110577_a", null, skin);
        drawFaceRounded(AVATAR_X, AVATAR_Y, AVATAR_SIZE, AVATAR_RADIUS, 40, 8);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GlColor.restore();
    }

    private static void setSkinTint(float alpha, float hurt) {
        float gb = 1.0F - 0.74F * clamp01(hurt);
        GlColor.set(1.0F, gb, gb, alpha);
    }

    /**
     * 把头像纹理绘制成圆角矩形：用三角形扇沿圆角轮廓采样同一张脸，
     * 四个角会被真正裁掉，而不是在方形头像上盖一块色块。
     */
    private static void drawFaceRounded(float x, float y, float size,
            float radius, int texX, int texY) {
        float inset = 0.18F;
        float u0 = (texX + inset) / 64.0F;
        float v0 = (texY + inset) / 64.0F;
        float u1 = (texX + 8.0F - inset) / 64.0F;
        float v1 = (texY + 8.0F - inset) / 64.0F;
        float uMid = (u0 + u1) * 0.5F;
        float vMid = (v0 + v1) * 0.5F;

        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glTexCoord2f(uMid, vMid);
        GL11.glVertex2f(x + size * 0.5F, y + size * 0.5F);
        for (int corner = 0; corner < 4; corner++) {
            float centerX = x + ((corner == 0 || corner == 3)
                    ? radius : size - radius);
            float centerY = y + ((corner < 2) ? radius : size - radius);
            float startAngle = 180.0F + corner * 90.0F;
            for (int segment = 0; segment <= 8; segment++) {
                double angle = Math.toRadians(startAngle + segment * 11.25D);
                float px = centerX + (float) Math.cos(angle) * radius;
                float py = centerY + (float) Math.sin(angle) * radius;
                GL11.glTexCoord2f(u0 + (px - x) / size * (u1 - u0),
                        v0 + (py - y) / size * (v1 - v0));
                GL11.glVertex2f(px, py);
            }
        }
        // 闭合圆角轮廓，补上左侧直边对应的最后一个三角形。
        GL11.glTexCoord2f(u0, v0 + radius / size * (v1 - v0));
        GL11.glVertex2f(x, y + radius);
        GL11.glEnd();
    }

    // ==================================================================
    // GL 基础图形
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

    private static float clamp01(float value) {
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }

    /** 线性推进，保证淡入/淡出在设定时长内真正到达 0/1。 */
    private static float approach(float value, float goal, float amount) {
        float step = clamp01(amount);
        if (value < goal) {
            return Math.min(goal, value + step);
        }
        return Math.max(goal, value - step);
    }

    /**
     * 血量 / 血量上限的平滑逼近。
     *
     * <p>用与帧率无关的指数平滑：掉血用 {@link #HEALTH_DROP_SMOOTH_MS}，
     * 回血用更快的 {@link #HEALTH_GAIN_SMOOTH_MS}；差很小时直接吸附到目标值。
     */
    private static float approachHealth(float value, float goal, float deltaMs) {
        float diff = goal - value;
        if (Math.abs(diff) <= HEALTH_SNAP) {
            return goal;
        }
        float tau = diff < 0.0F ? HEALTH_DROP_SMOOTH_MS : HEALTH_GAIN_SMOOTH_MS;
        float factor = 1.0F - (float) Math.exp(-deltaMs / Math.max(1.0F, tau));
        return value + diff * factor;
    }

    /** 满血绿色 → 半血橙色 → 空血深红。 */
    private static int healthColor(float ratio) {
        ratio = clamp01(ratio);
        if (ratio >= 0.5F) {
            return lerpColor(HEALTH_MID, HEALTH_FULL, (ratio - 0.5F) / 0.5F);
        }
        return lerpColor(HEALTH_LOW, HEALTH_MID, ratio / 0.5F);
    }

    private static int lerpColor(int from, int to, float t) {
        t = clamp01(t);
        int r = (int) Math.round(((from >> 16) & 0xFF) * (1.0F - t) + ((to >> 16) & 0xFF) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) * (1.0F - t) + ((to >> 8) & 0xFF) * t);
        int b = (int) Math.round((from & 0xFF) * (1.0F - t) + (to & 0xFF) * t);
        return r << 16 | g << 8 | b;
    }

    private static String displayName(EntityLivingBase entity) {
        String name = "";
        Object value = call(entity, "getName", "func_70005_c_", String.class);
        if (value instanceof String) {
            name = stripFormatting((String) value);
        }
        if (name == null || name.trim().isEmpty()) {
            name = entity.getClass().getSimpleName().replace("Entity", "");
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
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String suffix = "..";
        int end = text.length();
        while (end > 0
                && font.getStringWidth(text.substring(0, end) + suffix)
                        > maxWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }

    // ==================================================================
    // 反射访问（运行期可能是 MCP / SRG / 混淆名）
    // ==================================================================

    private static Object read(Object target, String mcp, String srg, String obf,
            Class<?> expected) {
        Field field = findField(target, mcp, srg, obf);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            Object value = field.get(target);
            if (expected == null || value == null || expected.isInstance(value)) {
                return value;
            }
        } catch (Throwable ignored) {
            // Treat an inaccessible field as missing; the HUD is optional.
        }
        return null;
    }

    private static Field findField(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            if (name == null) {
                continue;
            }
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    return type.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    // Continue with the superclass / next runtime name.
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object call(Object target, String mcp, String srg, Class<?> expected,
            Object... args) {
        if (target == null) {
            return null;
        }
        for (String name : new String[] {mcp, srg}) {
            if (name == null) {
                continue;
            }
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                Method[] methods;
                try {
                    methods = type.getDeclaredMethods();
                } catch (Throwable ignored) {
                    continue;
                }
                for (int i = 0; i < methods.length; i++) {
                    Method method = methods[i];
                    if (!method.getName().equals(name)
                            || method.getParameterTypes().length != args.length
                            || !parametersMatch(method.getParameterTypes(), args)) {
                        continue;
                    }
                    try {
                        method.setAccessible(true);
                        Object value = method.invoke(target, args);
                        if (expected == null || value == null || expected.isInstance(value)) {
                            return value;
                        }
                    } catch (Throwable ignored) {
                        // Try the next overload / mapping.
                    }
                }
            }
        }
        return null;
    }

    private static boolean parametersMatch(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object arg = args[i];
            if (arg == null) {
                if (type.isPrimitive()) {
                    return false;
                }
            } else if (type.isPrimitive()) {
                if (type == Boolean.TYPE) {
                    if (!(arg instanceof Boolean)) return false;
                } else if (type == Byte.TYPE) {
                    if (!(arg instanceof Byte)) return false;
                } else if (type == Character.TYPE) {
                    if (!(arg instanceof Character)) return false;
                } else if (type == Short.TYPE) {
                    if (!(arg instanceof Short)) return false;
                } else if (type == Integer.TYPE) {
                    if (!(arg instanceof Integer)) return false;
                } else if (type == Long.TYPE) {
                    if (!(arg instanceof Long)) return false;
                } else if (type == Float.TYPE) {
                    if (!(arg instanceof Float)) return false;
                } else if (type == Double.TYPE) {
                    if (!(arg instanceof Double)) return false;
                }
            } else if (!type.isInstance(arg)) {
                return false;
            }
        }
        return true;
    }
}
