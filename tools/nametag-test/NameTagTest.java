import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import module.ModuleManager;
import module.render.NameTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraftforge.client.event.RenderLivingEvent;

/** Runs the real label callback without a Minecraft window or native OpenGL. */
public final class NameTagTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        NameTag module = new NameTag();
        ModuleManager.register(module);
        Minecraft mc = new Minecraft();
        mc.thePlayer = new EntityPlayerSP();
        mc.field_71441_e = new WorldClient();
        mc.renderManager = new RenderManager();
        mc.gameSettings = new GameSettings();
        RecordingFont font = new RecordingFont();
        mc.fontRendererObj = font;
        Player player = new Player();
        NameTagFeature.load(new Properties());

        check(!render(mc, player).isCanceled() && font.text.isEmpty(), "Disabled module leaves vanilla names alone");
        module.setEnabled(true);
        check(render(mc, player).isCanceled(), "Custom label replaces vanilla name");
        check(font.text.contains("TestPlayer") && font.text.contains("18/20 HP"), "Name and live health are drawn");
        int fullColor = font.colors.get(1);
        font.clear();
        player.health = 5.5F;
        render(mc, player);
        check(font.text.contains("5.5/20 HP"), "Damage updates health on the next render");
        check(font.colors.get(1) != fullColor, "Low health changes the label color");
        player.maxHealth = 40.0F;
        font.clear();
        render(mc, player);
        check(font.text.contains("5.5/40 HP"), "Custom maximum health is displayed");

        font.clear();
        check(!render(mc, mc.thePlayer).isCanceled(), "Local player is excluded");
        check(!render(mc, new EntityLivingBase()).isCanceled(), "Mob labels are unchanged");
        player.distanceSquared = 65 * 65;
        check(!render(mc, player).isCanceled(), "Out-of-range players keep vanilla labels");
        player.distanceSquared = 10 * 10;
        player.invisible = true;
        check(!render(mc, player).isCanceled(), "Players invisible to the viewer are excluded");
        player.invisible = false;
        player.health = 0;
        check(!render(mc, player).isCanceled(), "Dead players are excluded");
        player.health = 20;
        mc.gameSettings.hideGUI = true;
        check(!render(mc, player).isCanceled(), "F1 hides the custom label");
        mc.gameSettings.hideGUI = false;
        check(font.text.isEmpty(), "Excluded entities never draw custom text");

        NameTagFeature.beginSkinPass();
        try {
            check(render(mc, player).isCanceled() && font.text.isEmpty(), "X-Ray model passes suppress duplicate labels");
        } finally {
            NameTagFeature.endSkinPass();
        }
        render(mc, player);
        check(!font.text.isEmpty(), "Normal labels resume after the X-Ray pass");
        font.clear();
        RenderLivingEvent.Specials.Pre<EntityLivingBase> canceled = event(player);
        canceled.setCanceled(true);
        NameTagFeature.onRenderName(mc, canceled);
        check(font.text.isEmpty(), "Cancellation by another listener is respected");

        NameTagFeature.setRange(96);
        NameTagFeature.setScale(1.25F);
        NameTagFeature.setThroughWalls(false);
        NameTagFeature.setHealthBarPosition(1);
        NameTagFeature.setHurtFill(false);
        Properties saved = new Properties();
        saved.load(new ByteArrayInputStream(NameTagFeature.save().getBytes(StandardCharsets.US_ASCII)));
        NameTagFeature.load(new Properties());
        NameTagFeature.load(saved);
        check(NameTagFeature.getRange() == 96 && NameTagFeature.getScale() == 1.25F
                && !NameTagFeature.isThroughWalls() && NameTagFeature.getHealthBarPosition() == 1
                && !NameTagFeature.isHurtFill(), "NameTag range, scale, depth, bar direction and hurt fill survive save/load");
        check(render(mc, player).isCanceled(), "Depth-tested labels still replace vanilla names");
        org.lwjgl.opengl.GL11.reset();
        render(mc, player);
        check(org.lwjgl.opengl.GL11.maxY - org.lwjgl.opengl.GL11.minY > 60,
                "Side health bar spans the player body instead of the name label");
        for (int view : new int[] {0, 2}) {
            mc.gameSettings.thirdPersonView = view;
            for (float pitch : new float[] {-65, 0, 50}) {
                mc.renderManager.playerViewX = pitch;
                mc.renderManager.playerViewY = 63;
                org.lwjgl.opengl.GL11.reset();
                render(mc, player);
                java.util.List<double[]> points = org.lwjgl.opengl.GL11.points;
                double[] bottom = points.get(0), top = points.get(3);
                check(Math.abs(top[0] - bottom[0]) < 0.00001 && Math.abs(top[2] - bottom[2]) < 0.00001,
                        "Side bar stays parallel to the body at pitch " + pitch + " in camera view " + view);
                check(Math.abs(points.get(4)[1] - 1.25) < 0.00001
                        && Math.abs(points.get(7)[1] - (1.25 + player.height)) < 0.00001,
                        "Side bar endpoints align with the feet and head regardless of camera pitch");
            }
        }
        mc.gameSettings.thirdPersonView = 0;
        mc.renderManager.playerViewX = mc.renderManager.playerViewY = 0;
        mc.field_71441_e.loadedEntityList.add(player);
        player.hurtTime = 8;
        NameTagFeature.setHurtFill(true);
        org.lwjgl.opengl.GL11.reset();
        NameTagFeature.onRenderWorld(mc, 0.5F);
        check(org.lwjgl.opengl.GL11.vertices3d == 24,
                "Hurt fill reads the SRG WorldClient field and draws all six faces");
        player.hurtTime = 0;
        org.lwjgl.opengl.GL11.reset();
        NameTagFeature.onRenderWorld(mc, 0.5F);
        check(org.lwjgl.opengl.GL11.vertices3d == 0, "Red fill disappears when hurt animation ends");
        player.hurtTime = 8;
        NameTagFeature.setHurtFill(false);
        NameTagFeature.onRenderWorld(mc, 0.5F);
        check(org.lwjgl.opengl.GL11.vertices3d == 0, "Hurt fill toggle disables geometry");
        Properties invalid = new Properties();
        invalid.setProperty("nametag.range", "NaN");
        invalid.setProperty("nametag.scale", "Infinity");
        NameTagFeature.load(invalid);
        check(NameTagFeature.getRange() == 64 && NameTagFeature.getScale() == 1,
                "Invalid numeric settings restore safe defaults");
        check(NameTagFeature.getHealthBarPosition() == 0, "Old profiles default to the top health bar");

        player.hurtTime = 8;
        org.lwjgl.opengl.GL11.failNextBegin = true;
        NameTagFeature.onRenderWorld(mc, 0.5F);
        check(!org.lwjgl.opengl.GL11.failNextBegin, "Injected hurt fill rendering failure was exercised");
        org.lwjgl.opengl.GL11.reset();
        NameTagFeature.onRenderWorld(mc, 0.5F);
        check(org.lwjgl.opengl.GL11.vertices3d == 0, "Failed hurt fill stops retrying without throwing into the world bridge");
        check(render(mc, player).isCanceled(), "Name labels still render after a hurt fill failure");

        font.fail = true;
        check(!render(mc, player).isCanceled(), "Rendering failure falls back to vanilla labels");
        font.fail = false;
        font.clear();
        check(!render(mc, player).isCanceled() && font.text.isEmpty(), "Failed renderer does not keep throwing each frame");
        System.out.println("NameTag checks passed: " + checks);
    }

    private static RenderLivingEvent.Specials.Pre<EntityLivingBase> event(EntityLivingBase entity) {
        return new RenderLivingEvent.Specials.Pre<EntityLivingBase>(entity, null, 2.5, 1.25, -8.5);
    }
    private static RenderLivingEvent.Specials.Pre<?> render(Minecraft mc, EntityLivingBase entity) {
        RenderLivingEvent.Specials.Pre<?> event = event(entity);
        NameTagFeature.onRenderName(mc, event);
        return event;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static final class Player extends EntityPlayer {
        public int hurtTime;
        float health = 18;
        float maxHealth = 20;
        double distanceSquared = 100;
        boolean invisible;
        Player() { height = 1.8F; }
        @Override public String getName() { return "TestPlayer"; }
        @Override public float getHealth() { return health; }
        @Override public float getMaxHealth() { return maxHealth; }
        @Override public double getDistanceSqToEntity(Entity entity) { return distanceSquared; }
        @Override public boolean isInvisibleToPlayer(EntityPlayer player) { return invisible; }
        @Override public net.minecraft.util.AxisAlignedBB getEntityBoundingBox() {
            return new net.minecraft.util.AxisAlignedBB(-0.3, 0, -0.3, 0.3, height, 0.3);
        }
    }
    private static final class RecordingFont extends FontRenderer {
        final List<String> text = new ArrayList<String>();
        final List<Integer> colors = new ArrayList<Integer>();
        boolean fail;
        @Override public int drawStringWithShadow(String value, float x, float y, int color) {
            if (fail) throw new IllegalStateException("simulated font failure");
            text.add(value);
            colors.add(color);
            return 0;
        }
        void clear() { text.clear(); colors.clear(); }
    }
}
