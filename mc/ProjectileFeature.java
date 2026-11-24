import java.util.List;

import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;

/** Lightweight projectile markers and predicted flight paths for 1.8.9. */
public final class ProjectileFeature {
    private static float range = 64.0F;
    private static boolean failed;

    private ProjectileFeature() { }

    public static float getRange() { return range; }
    public static void setRange(float value) { range = Math.max(8.0F, Math.min(128.0F, value)); }

    public static void onRenderWorld(RenderWorldLastEvent event) {
        Module module = ModuleManager.getModule("Projectiles");
        if (failed || module == null || !module.isEnabled()) return;
        boolean pushed = false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) return;
            Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
            Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
            Object rm = OpenMaverFeatures.read(mc, "renderManager", "field_175616_W", null);
            List<?> entities = (List<?>) OpenMaverFeatures.read(world,
                    "loadedEntityList", "field_72996_f", List.class);
            if (world == null || entities == null || rm == null || player == null) return;
            double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
            double py = OpenMaverFeatures.number(player, "posY", "field_70163_u");
            double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
            double cx = OpenMaverFeatures.number(rm, "viewerPosX", "field_78725_b");
            double cy = OpenMaverFeatures.number(rm, "viewerPosY", "field_78726_c");
            double cz = OpenMaverFeatures.number(rm, "viewerPosZ", "field_78723_d");
            double rangeSq = range * range;

            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT
                    | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            pushed = true;
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(1.6F);

            for (int i = 0; i < entities.size(); i++) {
                Object value = entities.get(i);
                if (!(value instanceof Entity)) continue;
                Entity entity = (Entity) value;
                boolean arrow = isType(entity, "EntityArrow", "yz");
                boolean throwable = isType(entity, "EntityThrowable", "xl");
                boolean fireball = isType(entity, "EntityFireball", "yk");
                if (!(arrow || throwable || fireball)
                        || Boolean.TRUE.equals(OpenMaverFeatures.read(entity,
                                "isDead", "field_70128_L", Boolean.class))) continue;
                double ex = OpenMaverFeatures.number(entity, "posX", "field_70165_t");
                double ey = OpenMaverFeatures.number(entity, "posY", "field_70163_u");
                double ez = OpenMaverFeatures.number(entity, "posZ", "field_70161_v");
                double dx = ex - px, dy = ey - py, dz = ez - pz;
                if (dx * dx + dy * dy + dz * dz > rangeSq) continue;

                float red = arrow ? 1.0F : 0.35F;
                float green = fireball ? 0.35F : 0.85F;
                float blue = throwable ? 1.0F : 0.25F;
                GL11.glColor4f(red, green, blue, 0.95F);
                double x = ex - cx, y = ey - cy, z = ez - cz;
                double radius = fireball ? 0.24D : 0.13D;
                GL11.glBegin(GL11.GL_LINES);
                box(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
                path(entity, x, y, z, arrow, fireball);
                GL11.glEnd();

                // Direction cue from the player's eye to the projectile.
                GL11.glColor4f(red, green, blue, 0.35F);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex3d(px - cx, py + 1.62D - cy, pz - cz);
                GL11.glVertex3d(x, y, z);
                GL11.glEnd();
            }
            GL11.glDepthMask(true);
            GL11.glPopAttrib();
            pushed = false;
        } catch (Throwable error) {
            failed = true;
            System.err.println("[OpenMaver] Projectile hints disabled: " + error);
        } finally {
            if (pushed) {
                try { GL11.glPopAttrib(); } catch (Throwable ignored) { }
            }
        }
    }

    private static void path(Entity entity, double x, double y, double z,
            boolean arrow, boolean fireball) {
        double vx = OpenMaverFeatures.number(entity, "motionX", "field_70159_w");
        double vy = OpenMaverFeatures.number(entity, "motionY", "field_70181_x");
        double vz = OpenMaverFeatures.number(entity, "motionZ", "field_70179_y");
        double gravity = arrow ? 0.05D : fireball ? 0.0D : 0.03D;
        GL11.glColor4f(1.0F, 0.83F, 0.25F, 0.85F);
        double lastX = x, lastY = y, lastZ = z;
        for (int tick = 0; tick < 20; tick++) {
            vx *= 0.99D;
            vy = vy * 0.99D - gravity;
            vz *= 0.99D;
            double nextX = lastX + vx, nextY = lastY + vy, nextZ = lastZ + vz;
            GL11.glVertex3d(lastX, lastY, lastZ);
            GL11.glVertex3d(nextX, nextY, nextZ);
            lastX = nextX; lastY = nextY; lastZ = nextZ;
        }
    }

    private static boolean isType(Object entity, String... simpleNames) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            for (String name : simpleNames) {
                if (name.equals(type.getSimpleName())) return true;
            }
        }
        return false;
    }

    private static void box(double x1, double y1, double z1,
            double x2, double y2, double z2) {
        line(x1,y1,z1,x2,y1,z1); line(x2,y1,z1,x2,y1,z2);
        line(x2,y1,z2,x1,y1,z2); line(x1,y1,z2,x1,y1,z1);
        line(x1,y2,z1,x2,y2,z1); line(x2,y2,z1,x2,y2,z2);
        line(x2,y2,z2,x1,y2,z2); line(x1,y2,z2,x1,y2,z1);
        line(x1,y1,z1,x1,y2,z1); line(x2,y1,z1,x2,y2,z1);
        line(x2,y1,z2,x2,y2,z2); line(x1,y1,z2,x1,y2,z2);
    }

    private static void line(double x1, double y1, double z1,
            double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1); GL11.glVertex3d(x2, y2, z2);
    }
}
