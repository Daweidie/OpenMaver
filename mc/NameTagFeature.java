import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderLivingEvent;
import org.lwjgl.opengl.GL11;

import java.util.Locale;
import java.util.Properties;

/** Replaces vanilla player labels with a name, live health and a small health bar. */
public final class NameTagFeature {
    private static float range = 64.0F;
    private static float scale = 1.0F;
    private static boolean throughWalls = true;
    private static boolean failed;
    private static int skinPassDepth;

    private NameTagFeature() {}

    public static void load(Properties values) {
        range = readFloat(values, "nametag.range", 64.0F, 8.0F, 128.0F);
        scale = readFloat(values, "nametag.scale", 1.0F, 0.5F, 2.0F);
        throughWalls = Boolean.parseBoolean(values.getProperty("nametag.throughWalls", "true"));
    }

    public static String save() {
        return "nametag.range=" + range + "\nnametag.scale=" + scale
                + "\nnametag.throughWalls=" + throughWalls + "\n";
    }

    public static float getRange() { return range; }
    public static void setRange(float value) {
        range = finiteClamp(value, 8.0F, 128.0F, 64.0F);
        OpenMaverFeatures.markDirty();
    }
    public static float getScale() { return scale; }
    public static void setScale(float value) {
        scale = finiteClamp(value, 0.5F, 2.0F, 1.0F);
        OpenMaverFeatures.markDirty();
    }
    public static boolean isThroughWalls() { return throughWalls; }
    public static void setThroughWalls(boolean value) {
        throughWalls = value;
        OpenMaverFeatures.markDirty();
    }

    /** Extra X-Ray/ESP model passes must not duplicate labels or capture them in a mask. */
    public static void beginSkinPass() { skinPassDepth++; }
    public static void endSkinPass() { skinPassDepth = Math.max(0, skinPassDepth - 1); }

    public static void onRenderName(Minecraft mc, RenderLivingEvent.Specials.Pre<?> event) {
        if (mc == null || event == null || event.isCanceled()) return;
        if (skinPassDepth > 0) {
            event.setCanceled(true);
            return;
        }
        if (failed) return;
        Module module = ModuleManager.getModule("NameTag");
        if (module == null || !module.isEnabled()) return;
        try {
            if (!(event.entity instanceof EntityPlayer) || mc.thePlayer == null
                    || event.entity == mc.thePlayer
                    || mc.fontRendererObj == null || mc.getRenderManager() == null
                    || (mc.gameSettings != null && mc.gameSettings.hideGUI)) return;
            EntityPlayer player = (EntityPlayer) event.entity;
            if (!player.isEntityAlive() || player.getHealth() <= 0.0F
                    || AntiBotFeature.shouldFilterRender(player)
                    || player.isInvisibleToPlayer(mc.thePlayer)
                    || player.getDistanceSqToEntity(mc.thePlayer) > range * range) return;
            if (skinPassDepth == 0) draw(mc, event, player);
            // Only suppress the original label once the replacement has drawn successfully.
            event.setCanceled(true);
        } catch (Throwable error) {
            failed = true;
            System.err.println("[OpenMaver] NameTag disabled; using vanilla labels: " + error);
        }
    }

    private static void draw(Minecraft mc, RenderLivingEvent.Specials.Pre<?> event, EntityPlayer player) {
        FontRenderer font = mc.fontRendererObj;
        RenderManager manager = mc.getRenderManager();
        String name = player.getName();
        float health = finiteClamp(player.getHealth(), 0.0F, Float.MAX_VALUE, 0.0F);
        float maxHealth = finiteClamp(player.getMaxHealth(), 1.0F, Float.MAX_VALUE, 20.0F);
        String healthText = formatHealth(health) + "/" + formatHealth(maxHealth) + " HP";
        float ratio = Math.max(0.0F, Math.min(1.0F, health / maxHealth));
        int healthColor = ratio > 0.6F ? 0xFF79DE9B : ratio > 0.3F ? 0xFFFFC36A : 0xFFF2777D;
        int nameW = font.getStringWidth(name);
        int totalW = nameW + 8 + font.getStringWidth(healthText);
        float left = -totalW / 2.0F;
        float tagScale = 0.02666667F * scale;
        float pitch = manager.playerViewX;
        if (mc.gameSettings != null && mc.gameSettings.thirdPersonView == 2) pitch = -pitch;
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        // Forge supplies interpolated camera-relative coordinates, so walking labels stay steady.
        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_LIGHTING_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef((float) event.x,
                    (float) event.y + player.height + 0.45F - (player.isSneaking() ? 0.25F : 0.0F),
                    (float) event.z);
            GL11.glRotatef(-manager.playerViewY, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(pitch, 1.0F, 0.0F, 0.0F);
            GL11.glScalef(-tagScale, -tagScale, tagScale);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            if (throughWalls) GL11.glDisable(GL11.GL_DEPTH_TEST);
            else GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            quad(left - 4, -4, left + totalW + 4, 10, 0xB812151C);
            quad(left - 4, 10, left + totalW + 4, 11, 0x70474E5C);
            quad(left - 4, 10, left - 4 + (totalW + 8) * ratio, 11, healthColor);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GlColor.restore();
            font.drawStringWithShadow(name, left, -1, 0xFFF2F4F8);
            font.drawStringWithShadow(healthText, left + nameW + 8, -1, healthColor);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            // FontRenderer changes textures through Minecraft's cache; restore through it too.
            GlStateManager.bindTexture(previousTexture);
            GlColor.restore();
        }
    }

    private static void quad(float x1, float y1, float x2, float y2, int argb) {
        GlColor.set(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x1, y2);
        GL11.glVertex2f(x2, y2);
        GL11.glVertex2f(x2, y1);
        GL11.glVertex2f(x1, y1);
        GL11.glEnd();
    }

    private static String formatHealth(float value) {
        if (Math.abs(value - Math.round(value)) < 0.05F) return String.valueOf(Math.round(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static float readFloat(Properties values, String key, float fallback, float min, float max) {
        try {
            return finiteClamp(Float.parseFloat(values.getProperty(key, Float.toString(fallback))), min, max, fallback);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float finiteClamp(float value, float min, float max, float fallback) {
        return Float.isNaN(value) || Float.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value));
    }
}
