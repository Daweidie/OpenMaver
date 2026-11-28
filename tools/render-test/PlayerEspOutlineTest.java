import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderLivingEvent;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** Execute the production capture/composite path with independent driver/cache fixtures. */
public final class PlayerEspOutlineTest {
    private static int checks;
    private static final float[] WHITE = {1, 1, 1};

    public static void main(String[] args) throws Exception {
        Path config = Files.createTempDirectory("openmaver-outline-").resolve("features.properties");
        System.setProperty("openmaver.features", config.toString());
        GlStateManager.activeTexture(0);
        GlStateManager.bindTexture(41);
        GlStateManager.enableTexture2D();
        GlStateManager.activeTexture(1);
        GlStateManager.bindTexture(71);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
        GL11.state.put("viewport", "3:5:800:600");
        final Map<String, String> driver = new LinkedHashMap<String, String>(GL11.state);
        final String cache = GlStateManager.cache();
        final Manager manager = new Manager();
        manager.viewerPosX = 10; manager.viewerPosY = 20; manager.viewerPosZ = 30;
        final Target entity = new Target();
        entity.posX = 15; entity.posY = 27; entity.posZ = 39;
        entity.lastTickPosX = 11; entity.lastTickPosY = 23; entity.lastTickPosZ = 35;
        entity.prevRotationYaw = 10; entity.rotationYaw = 50;
        Runnable models = new Runnable() { public void run() {
            check(PlayerEspOutline.isRendering(), "Model capture has a recursion guard");
            check(!PlayerEspOutline.render(800, 600, 1, WHITE, 1, this), "Nested capture is rejected");
            check(EXTFramebufferObject.draw != 81, "Models target an isolated framebuffer");
            check(GL20.current == 0, "Vanilla model capture uses fixed-function rendering");
            OpenMaverFeatures.renderEspModel(manager, entity, .5F);
        }};
        check(!PlayerEspOutline.render(0, 600, 1, WHITE, 1, models), "Minimized windows skip capture");
        check(GL11.textures.isEmpty(), "Minimized windows allocate nothing");
        for (int i = 0; i < 3; i++) {
            check(PlayerEspOutline.render(800, 600, 1.5F, WHITE, .9F, models), "Outline frame succeeds");
            restored(driver, cache);
            check(GL11.textures.size() == 2 && EXTFramebufferObject.framebuffers.size() == 2
                    && EXTFramebufferObject.renderbuffers.size() == 1, "Same-size frames reuse all targets");
        }
        check(manager.renderer.calls == 3 && manager.renderer.labels == 3, "One model pass and no captured labels per frame");
        check(manager.renderer.x == 3 && manager.renderer.y == 5 && manager.renderer.z == 7,
                "Model coordinates interpolate at partialTicks and subtract camera position");
        check(manager.renderer.yaw == 30 && manager.renderer.partial == .5F, "Model animation uses frame partialTicks");
        check(GL11.draws.size() == 6 && GL11.draws.get(0).endsWith(":0:false")
                && "81:1:false".equals(GL11.draws.get(1)), "Two fullscreen passes; only the hollow composite reaches the world");
        check(!GL11.clears.contains(81), "World framebuffer color and depth are never cleared");
        java.util.Set<Integer> oldTextures = new java.util.HashSet<Integer>(GL11.textures);
        check(PlayerEspOutline.render(1024, 768, 4, WHITE, .2F, models), "Resize renders successfully");
        check(java.util.Collections.disjoint(oldTextures, GL11.textures), "Resize deletes old textures");
        restored(driver, cache);
        entity.ticksExisted = 0;
        check(PlayerEspOutline.render(1024, 768, .5F, WHITE, 1, models), "Newly spawned models render");
        check(manager.renderer.x == 5 && manager.renderer.y == 7 && manager.renderer.z == 9,
                "New entity uses current position without modifying its interpolation history");
        restored(driver, cache);
        check(entity.lastTickPosX == 11, "Extra render leaves entity history untouched");

        check(!PlayerEspOutline.render(1024, 768, 1, WHITE, 1, new Runnable() { public void run() {
            GlStateManager.bindTexture(999);
            throw new IllegalStateException("simulated model failure");
        }}), "Model exception returns the Box fallback signal");
        restored(driver, cache);
        emptyResources();
        check(!PlayerEspOutline.render(1024, 768, 1, WHITE, 1, models), "Failed renderer stays disabled without repeated allocations");
        reset();
        GL20.failCompile = true;
        check(!PlayerEspOutline.render(800, 600, 1, WHITE, 1, models), "Shader failure returns Box fallback");
        restored(driver, cache); emptyResources();
        reset(); GL20.failCompile = false; EXTFramebufferObject.complete = false;
        check(!PlayerEspOutline.render(800, 600, 1, WHITE, 1, models), "Incomplete framebuffer returns Box fallback");
        restored(driver, cache); emptyResources();
        EXTFramebufferObject.complete = true;

        selection();
        OpenMaverFeatures.setEspMode(1);
        OpenMaverFeatures.setEspColor(4);
        OpenMaverFeatures.flushSettings();
        Properties saved = new Properties();
        try (java.io.InputStream input = Files.newInputStream(config)) { saved.load(input); }
        check("1".equals(saved.getProperty("esp.mode")) && "4".equals(saved.getProperty("esp.color")),
                "Outline and White are saved through the production config writer");
        reload(config, "settings.version=24\nesp.mode=1\nesp.color=4\n");
        check(OpenMaverFeatures.getEspMode() == 1 && OpenMaverFeatures.getEspColor() == 4,
                "Outline and White survive the production config reader");
        reload(config, "settings.version=24\nesp.range=64\n");
        check(OpenMaverFeatures.getEspMode() == 0 && OpenMaverFeatures.getEspRange() == 64,
                "Existing configs retain Box mode and their previous range");
        reload(config, "settings.version=24\nesp.mode=999\nesp.color=999\n");
        check(OpenMaverFeatures.getEspMode() == 1 && OpenMaverFeatures.getEspColor() == 4,
                "Invalid high mode/color values are bounded");
        reload(config, "settings.version=24\nesp.mode=oops\nesp.color=-1\n");
        check(OpenMaverFeatures.getEspMode() == 0 && OpenMaverFeatures.getEspColor() == 0,
                "Malformed settings restore valid defaults");
        System.out.println("Player ESP Outline checks passed: " + checks);
    }

    private static void selection() {
        Self self = new Self(); Target near = new Target(), far = new Target();
        far.posX = 17;
        EntityLivingBase dead = new EntityLivingBase() { public boolean isEntityAlive() { return false; } };
        EntityPlayer player = new EntityPlayer();
        List<?> entities = Arrays.asList(self, near, far, dead, player, new Entity(), "ignored");
        OpenMaverFeatures.setEspRange(16); OpenMaverFeatures.setEspPlayersOnly(false);
        check(OpenMaverFeatures.espTargets(self, entities).equals(Arrays.asList(near, player)),
                "Selection excludes self, nonliving entities, dead models and out-of-range models");
        OpenMaverFeatures.setEspPlayersOnly(true);
        check(OpenMaverFeatures.espTargets(self, entities).equals(Arrays.asList(player)), "Players Only applies to model outlines");
        player.posX = 16;
        check(OpenMaverFeatures.espTargets(self, entities).size() == 1, "Range boundary is inclusive");
        player.posX = 16.001;
        check(OpenMaverFeatures.espTargets(self, entities).isEmpty(), "Beyond range boundary is excluded");
    }

    private static void restored(Map<String, String> driver, String cache) throws Exception {
        check(driver.equals(GL11.state), "Driver state restored including all texture units, viewport and clear values");
        check(cache.equals(GlStateManager.cache()), "Minecraft render caches restored");
        check(EXTFramebufferObject.draw == 81 && EXTFramebufferObject.read == 82
                && EXTFramebufferObject.renderbuffer == 83 && GL20.current == 73,
                "Separate draw/read bindings, renderbuffer and shader restored");
        check(GL11.stack.isEmpty() && GL11.matrices == 0 && !PlayerEspOutline.isRendering(), "GL stacks and render guard balanced");
        Field depth = NameTagFeature.class.getDeclaredField("skinPassDepth"); depth.setAccessible(true);
        check(depth.getInt(null) == 0, "NameTag guard released");
    }

    private static void emptyResources() {
        check(GL11.textures.isEmpty() && EXTFramebufferObject.framebuffers.isEmpty()
                && EXTFramebufferObject.renderbuffers.isEmpty() && GL20.programs.isEmpty()
                && GL20.shaders.isEmpty(), "Failed capture releases all owned GPU resources");
    }
    private static void reset() throws Exception {
        Field failed = PlayerEspOutline.class.getDeclaredField("failed"); failed.setAccessible(true); failed.setBoolean(null, false);
    }
    private static void reload(Path path, String text) throws Exception {
        Files.write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String name : new String[] {"settingsDirty", "settingsCheckTicks", "settingsModified"}) {
            Field field = OpenMaverFeatures.class.getDeclaredField(name); field.setAccessible(true);
            if (name.equals("settingsDirty")) field.setBoolean(null, false);
            else if (name.equals("settingsCheckTicks")) field.setInt(null, 20);
            else field.setLong(null, Long.MIN_VALUE);
        }
        Method load = OpenMaverFeatures.class.getDeclaredMethod("refreshSettings"); load.setAccessible(true); load.invoke(null);
    }
    public static final class Target extends EntityLivingBase {
        public double lastTickPosX, lastTickPosY, lastTickPosZ;
        public float prevRotationYaw;
        public int ticksExisted = 10;
    }
    public static final class Self extends EntityPlayer {
        public double getDistanceSqToEntity(Entity e) {
            double dx = posX - e.posX, dy = posY - e.posY, dz = posZ - e.posZ;
            return dx * dx + dy * dy + dz * dz;
        }
    }
    public static final class Manager extends RenderManager {
        final Renderer renderer = new Renderer();
        public Renderer getEntityRenderObject(Entity entity) { return renderer; }
    }
    public static final class Renderer {
        int calls, labels; double x, y, z; float yaw, partial;
        public void doRender(EntityLivingBase entity, double x, double y, double z, float yaw, float partial) {
            calls++; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.partial = partial;
            RenderLivingEvent.Specials.Pre<EntityLivingBase> event =
                    new RenderLivingEvent.Specials.Pre<EntityLivingBase>(entity, null, x, y, z);
            NameTagFeature.onRenderName(new Minecraft(), event);
            if (event.isCanceled()) labels++;
            GlStateManager.setActiveTexture(0x84C0); GlStateManager.bindTexture(100 + calls);
            GlStateManager.color(.2F, .3F, .4F, .5F);
            GlStateManager.setActiveTexture(0x84C1); GlStateManager.bindTexture(200 + calls);
        }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
