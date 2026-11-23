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
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderLivingEvent;

/** Runs the real label callback without a Minecraft window or native OpenGL. */
public final class NameTagTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        NameTag module = new NameTag();
        ModuleManager.register(module);
        Minecraft mc = new Minecraft();
        mc.thePlayer = new EntityPlayerSP();
        mc.theWorld = new World();
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
        Properties saved = new Properties();
        saved.load(new ByteArrayInputStream(NameTagFeature.save().getBytes(StandardCharsets.US_ASCII)));
        NameTagFeature.load(new Properties());
        NameTagFeature.load(saved);
        check(NameTagFeature.getRange() == 96 && NameTagFeature.getScale() == 1.25F
                && !NameTagFeature.isThroughWalls(), "Range, size and depth setting survive save/load");
        check(render(mc, player).isCanceled(), "Depth-tested labels still replace vanilla names");
        Properties invalid = new Properties();
        invalid.setProperty("nametag.range", "NaN");
        invalid.setProperty("nametag.scale", "Infinity");
        NameTagFeature.load(invalid);
        check(NameTagFeature.getRange() == 64 && NameTagFeature.getScale() == 1,
                "Invalid numeric settings restore safe defaults");

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
