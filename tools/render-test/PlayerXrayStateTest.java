import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderLivingEvent;
import org.lwjgl.opengl.GL11;

/** Runs the actual X-Ray skin pass with distinct driver/cache fixtures. */
public final class PlayerXrayStateTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        GlStateManager.activeTexture(1);
        GlStateManager.bindTexture(71);
        GlStateManager.enableTexture2D();
        GlStateManager.activeTexture(2);
        GlStateManager.bindTexture(91);
        GlStateManager.activeTexture(0);
        GlStateManager.bindTexture(41);
        GlStateManager.enableTexture2D();
        GlStateManager.lighting(true);
        GlStateManager.color(1, 1, 1, 1);
        OpenGlHelper.lastBrightnessX = 17;
        OpenGlHelper.lastBrightnessY = 23;
        GL11.state.put("lightmap", "17:23");
        Map<String, String> driver = new LinkedHashMap<String, String>(GL11.state);
        String cache = GlStateManager.cache();
        Method skin = OpenMaverFeatures.class.getDeclaredMethod("renderPlayerSkin",
                RenderManager.class, EntityPlayer.class, float.class);
        skin.setAccessible(true);
        Renderer renderer = new Renderer();
        for (int i = 0; i < 3; i++) {
            skin.invoke(null, renderer, new EntityPlayer(), 0.5F);
            check(driver.equals(GL11.state), "All driver state restores after player " + i);
            check(cache.equals(GlStateManager.cache()), "All Minecraft caches restore after player " + i);
            check(OpenGlHelper.lastBrightnessX == 17 && OpenGlHelper.lastBrightnessY == 23,
                    "Lightmap mirrors restore after player " + i);
            check(GL11.stack.isEmpty() && GL11.matrices == 0, "GL stacks balance");
            // The inventory's next bind can legitimately be skipped by the cache.
            GlStateManager.bindTexture(41);
            check("41".equals(GL11.state.get("texture0")), "Cached inventory bind still selects its own texture");
        }
        check(renderer.calls == 6, "Two model passes per player");
        check(renderer.hiddenLabels == 6, "Extra labels suppressed even with NameTag disabled");

        GL11.failNextDepth = true;
        try {
            skin.invoke(null, renderer, new EntityPlayer(), 0.5F);
            throw new AssertionError("Expected a simulated state failure");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException, "Real skin pass exercises an exception");
            check(driver.equals(GL11.state) && cache.equals(GlStateManager.cache()),
                    "Failed skin pass restores driver and cache");
        }

        try {
            try (ModelRenderState ignored = new ModelRenderState()) {
                renderer.renderEntitySimple(new EntityPlayer(), 1);
                throw new IllegalStateException("simulated renderer failure");
            }
        } catch (IllegalStateException expected) {
            check(driver.equals(GL11.state) && cache.equals(GlStateManager.cache()),
                    "Exception path restores driver and cache");
        }
        Field depth = NameTagFeature.class.getDeclaredField("skinPassDepth");
        depth.setAccessible(true);
        check(depth.getInt(null) == 0, "Name label guard is released");
        check(GL11.stack.isEmpty() && GL11.matrices == 0, "Exception path balances GL stacks");
        System.out.println("Player X-Ray state checks passed: " + checks);
    }

    public static final class Renderer extends RenderManager {
        int calls, hiddenLabels;
        public boolean renderEntitySimple(Entity entity, float partialTicks) {
            calls++;
            RenderLivingEvent.Specials.Pre<EntityLivingBase> event =
                    new RenderLivingEvent.Specials.Pre<EntityLivingBase>((EntityLivingBase) entity,
                            null, 0, 0, 0);
            NameTagFeature.onRenderName(new Minecraft(), event);
            if (event.isCanceled()) hiddenLabels++;
            GlStateManager.activeTexture(1);
            GlStateManager.bindTexture(200 + calls);
            GlStateManager.enableTexture2D();
            GlStateManager.activeTexture(2);
            GlStateManager.bindTexture(300 + calls);
            GlStateManager.activeTexture(0);
            GlStateManager.bindTexture(100 + calls);
            GlStateManager.color(0.2F, 0.3F, 0.4F, 0.5F);
            GlStateManager.lighting(false);
            OpenGlHelper.lastBrightnessX = 240;
            OpenGlHelper.lastBrightnessY = calls;
            GL11.state.put("lightmap", "240:" + calls);
            return true;
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
