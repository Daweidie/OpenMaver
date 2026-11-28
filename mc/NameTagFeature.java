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
    private static int healthBarPosition;
    private static boolean hurtFill = true;
    private static boolean hurtFillFailed;
    private static boolean failed;
    private static int skinPassDepth;

    private NameTagFeature() {}

    public static void load(Properties values) {
        range = readFloat(values, "nametag.range", 64.0F, 8.0F, 128.0F);
        scale = readFloat(values, "nametag.scale", 1.0F, 0.5F, 2.0F);
        throughWalls = Boolean.parseBoolean(values.getProperty("nametag.throughWalls", "true"));
        healthBarPosition = "side".equalsIgnoreCase(values.getProperty("nametag.healthBarPosition")) ? 1 : 0;
        hurtFill = Boolean.parseBoolean(values.getProperty("nametag.hurtFill", "true"));
    }

    public static String save() {
        return "nametag.range=" + range + "\nnametag.scale=" + scale
                + "\nnametag.throughWalls=" + throughWalls
                + "\nnametag.healthBarPosition=" + (healthBarPosition == 1 ? "side" : "top")
                + "\nnametag.hurtFill=" + hurtFill + "\n";
    }

    public static float getRange() { return range; }
    public static int getHealthBarPosition() { return healthBarPosition; }
    public static void setHealthBarPosition(int value) {
        healthBarPosition = value == 1 ? 1 : 0;
        OpenMaverFeatures.markDirty();
    }
    public static boolean isHurtFill() { return hurtFill; }
    public static void setHurtFill(boolean value) {
        hurtFill = value;
        OpenMaverFeatures.markDirty();
    }
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
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            if (throughWalls) GL11.glDisable(GL11.GL_DEPTH_TEST);
            else GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (healthBarPosition == 1) {
                // Keep the bar upright beside the body. Only yaw faces the camera;
                // the label's pitch would tilt the long bar away from the player.
                GL11.glPushMatrix();
                try {
                    GL11.glTranslatef((float) event.x, (float) event.y, (float) event.z);
                    GL11.glRotatef(-manager.playerViewY, 0.0F, 1.0F, 0.0F);
                    GL11.glScalef(-tagScale, -tagScale, tagScale);
                    float barX = -(float) (Math.max(0.6D,
                            OpenMaverFeatures.number(player, "width", "field_70130_N")) / 2.0D) / tagScale - 7;
                    float top = -player.height / tagScale;
                    quad(barX - 1, top - 1, barX + 4, 1, 0xEE090C10);
                    quad(barX, top, barX + 3, 0, 0xB8323540);
                    gradient(barX, top * ratio, barX + 3, 0, healthColor, true);
                } finally {
                    GL11.glPopMatrix();
                }
            }
            GL11.glTranslatef((float) event.x,
                    (float) event.y + player.height + 0.45F - (player.isSneaking() ? 0.25F : 0.0F),
                    (float) event.z);
            GL11.glRotatef(-manager.playerViewY, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(pitch, 1.0F, 0.0F, 0.0F);
            GL11.glScalef(-tagScale, -tagScale, tagScale);
            quad(left - 4, -4, left + totalW + 4, 10, 0xB812151C);
            if (healthBarPosition == 0) {
                quad(left - 4, 10, left + totalW + 4, 12, 0x70474E5C);
                gradient(left - 4, 10, left - 4 + (totalW + 8) * ratio, 12, healthColor, false);
            }
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

    private static void gradient(float x1, float y1, float x2, float y2, int color, boolean vertical) {
        for (int i = 0; i < 48; i++) {
            float start = i / 48.0F, end = (i + 1) / 48.0F;
            float brightness = vertical ? 1.0F - start * 0.55F : 0.45F + start * 0.55F;
            int shade = 0xFF000000 | Math.round((color >> 16 & 255) * brightness) << 16
                    | Math.round((color >> 8 & 255) * brightness) << 8
                    | Math.round((color & 255) * brightness);
            quad(vertical ? x1 : x1 + (x2 - x1) * start,
                    vertical ? y1 + (y2 - y1) * start : y1,
                    vertical ? x2 : x1 + (x2 - x1) * end,
                    vertical ? y1 + (y2 - y1) * end : y2, shade);
        }
    }

    public static void onRenderWorld(Minecraft mc, float partialTicks) {
        Module module = ModuleManager.getModule("NameTag");
        if (!hurtFill || hurtFillFailed || module == null || !module.isEnabled() || mc == null) return;
        try {
            drawHurtFill(mc, partialTicks);
        } catch (Throwable error) {
            hurtFillFailed = true;
            System.err.println("[OpenMaver] NameTag hurt fill disabled: " + error);
        }
    }

    private static void drawHurtFill(Minecraft mc, float partialTicks) {
        // The real field is WorldClient, while the build stub declares World.
        // Read by mapped name to avoid embedding the incompatible stub descriptor.
        Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", net.minecraft.world.World.class);
        Object self = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", EntityPlayer.class);
        if (!(world instanceof net.minecraft.world.World) || !(self instanceof EntityPlayer)
                || mc.getRenderManager() == null
                || (mc.gameSettings != null && mc.gameSettings.hideGUI)) return;
        Object loaded = OpenMaverFeatures.read(world, "loadedEntityList", "field_72996_f", java.util.List.class);
        if (!(loaded instanceof java.util.List<?>)) return;
        EntityPlayer viewer = (EntityPlayer) self;
        RenderManager manager = mc.getRenderManager();
        for (Object candidate : (java.util.List<?>) loaded) {
            if (!(candidate instanceof EntityPlayer) || candidate == viewer) continue;
            EntityPlayer player = (EntityPlayer) candidate;
            if (!player.isEntityAlive() || player.getHealth() <= 0 || AntiBotFeature.shouldFilterRender(player)
                    || player.isInvisibleToPlayer(viewer)
                    || player.getDistanceSqToEntity(viewer) > range * range
                    || OpenMaverFeatures.number(player, "hurtTime", "field_70737_aN") <= 0) continue;
            net.minecraft.util.AxisAlignedBB box = player.getEntityBoundingBox();
            if (box == null) continue;
            double dx = (OpenMaverFeatures.number(player, "lastTickPosX", "field_70142_S") - player.posX) * (1 - partialTicks);
            double dy = (OpenMaverFeatures.number(player, "lastTickPosY", "field_70137_T") - player.posY) * (1 - partialTicks);
            double dz = (OpenMaverFeatures.number(player, "lastTickPosZ", "field_70136_U") - player.posZ) * (1 - partialTicks);
            FilledBox.draw(box.minX + dx - manager.viewerPosX, box.minY + dy - manager.viewerPosY,
                    box.minZ + dz - manager.viewerPosZ, box.maxX + dx - manager.viewerPosX,
                    box.maxY + dy - manager.viewerPosY, box.maxZ + dz - manager.viewerPosZ,
                    1.0F, 0.08F, 0.10F, 0.32F, throughWalls);
        }
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
