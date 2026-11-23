import com.mojang.authlib.GameProfile;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import module.ModuleManager;
import module.combat.AntiBot;
import module.combat.Criticals;
import module.render.NameTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderLivingEvent;

/** Exercises real tick classification and combat/render consumers with simulated Tab updates. */
public final class AntiBotTest {
    private static int checks;
    private static final AntiBot module = new AntiBot();

    public static void main(String[] args) throws Exception {
        System.setProperty("openmaver.features", Files.createTempFile("openmaver-antibot-", ".properties").toString());
        ModuleManager.register(module);
        detection();
        lifecycle();
        settings();
        consumers();
        System.out.println("AntiBot checks passed: " + checks);
    }

    private static void detection() {
        Client mc = fixture();
        Player bot = (Player) mc.theWorld.loadedEntityList.get(1);
        Player real = (Player) mc.theWorld.loadedEntityList.get(2);
        AntiBotFeature.onTick(mc);
        check(!AntiBotFeature.isBot(bot), "Module is disabled by default");
        module.setEnabled(true);
        ticks(mc, 39);
        check(!AntiBotFeature.isBot(bot), "Packet synchronization has a 40 tick grace period");
        ticks(mc, 1);
        check(AntiBotFeature.isBot(bot) && AntiBotFeature.getBotCount() == 1,
                "Persistent absence from Tab identifies a bot");
        check(!AntiBotFeature.isBot(real) && !AntiBotFeature.isBot(mc.thePlayer), "Tab players and self are retained");
        check(!AntiBotFeature.isBot(new EntityLivingBase()), "Mob entities are unaffected");
        mc.connection.entries.add(new Info(bot.profile));
        ticks(mc, 1);
        check(!AntiBotFeature.isBot(bot), "A delayed Tab entry immediately clears the verdict");
        mc.connection.entries.remove(mc.connection.entries.size() - 1);
        ticks(mc, 39);
        check(!AntiBotFeature.isBot(bot), "A later Tab removal starts a fresh grace period");
        ticks(mc, 1);
        check(AntiBotFeature.isBot(bot), "Repeated absence is detected again");
        mc.connection.entries.clear();
        ticks(mc, 100);
        check(AntiBotFeature.getBotCount() == 0, "An empty Tab list is treated as unavailable");
        mc.connection.entries.add(new Object());
        ticks(mc, 100);
        check(AntiBotFeature.getBotCount() == 0, "Unreadable Tab entries never exclude every player");
        mc.connection = null;
        ticks(mc, 100);
        check(!AntiBotFeature.isBot(real), "Connection loss leaves players available");
        mc = fixture();
        Player offline = new Player(new Profile(UUID.nameUUIDFromBytes("OfflinePlayer:Local".getBytes(StandardCharsets.UTF_8)), "Local"), 3);
        mc.theWorld.loadedEntityList.add(offline);
        mc.connection.entries.add(new Info(offline.profile));
        ticks(mc, 50);
        check(!AntiBotFeature.isBot(offline), "Offline UUIDs are accepted when present in Tab");
        Player custom = new Player(new Profile(UUID.randomUUID(), "玩家.One"), 4);
        mc.theWorld.loadedEntityList.add(custom);
        mc.connection.entries.add(new Info(custom.profile));
        ticks(mc, 50);
        check(!AntiBotFeature.isBot(custom), "Custom server names are accepted with name check off");
        AntiBotFeature.setNameCheck(true);
        ticks(mc, 40);
        check(AntiBotFeature.isBot(custom), "Optional strict name check also applies to listed NPCs");
        AntiBotFeature.setTabCheck(false);
        ticks(mc, 40);
        check(AntiBotFeature.isBot(custom) && !AntiBotFeature.isBot(mc.theWorld.loadedEntityList.get(1)),
                "Name check operates independently of Tab check");
        AntiBotFeature.setNameCheck(false);
        ticks(mc, 100);
        check(AntiBotFeature.getBotCount() == 0, "Turning both checks off keeps all players");
        AntiBotFeature.load(new Properties());
    }

    private static void lifecycle() {
        Client mc = fixture();
        Player bot = (Player) mc.theWorld.loadedEntityList.get(1);
        ticks(mc, 40);
        module.setEnabled(false);
        check(!AntiBotFeature.isBot(bot) && AntiBotFeature.getBotCount() == 0, "Disabling immediately removes filtering");
        ticks(mc, 1);
        module.setEnabled(true);
        ticks(mc, 1);
        check(!AntiBotFeature.isBot(bot), "Re-enabling starts a new observation period");
        ticks(mc, 39);
        Player respawn = new Player(bot.profile, 2);
        respawn.setEntityId(bot.getEntityId());
        mc.theWorld.loadedEntityList.set(1, respawn);
        ticks(mc, 1);
        check(!AntiBotFeature.isBot(respawn) && !AntiBotFeature.isBot(bot), "Respawn does not inherit a verdict or retain the old entity");
        ticks(mc, 39);
        mc.theWorld = new World();
        mc.theWorld.loadedEntityList.add(respawn);
        ticks(mc, 1);
        check(!AntiBotFeature.isBot(respawn), "Dimension changes clear observations");
        ticks(mc, 39);
        mc.thePlayer = new Local();
        ticks(mc, 1);
        check(!AntiBotFeature.isBot(respawn), "Local player replacement clears observations");
        ticks(mc, 39);
        mc.connection = new Connection();
        mc.connection.entries.add(new Info(new Profile(UUID.randomUUID(), "Other")));
        ticks(mc, 1);
        check(!AntiBotFeature.isBot(respawn), "Connection replacement clears observations");
        ticks(mc, 39);
        mc.theWorld = null;
        ticks(mc, 1);
        check(AntiBotFeature.getBotCount() == 0, "Leaving a world clears all references and verdicts");
    }

    private static void settings() throws Exception {
        AntiBotFeature.setTabCheck(false);
        AntiBotFeature.setNameCheck(true);
        AntiBotFeature.setCheckDelay(15);
        AntiBotFeature.setFilterRender(false);
        Properties saved = new Properties();
        saved.load(new ByteArrayInputStream(AntiBotFeature.save().getBytes(StandardCharsets.US_ASCII)));
        AntiBotFeature.load(new Properties());
        AntiBotFeature.load(saved);
        check(!AntiBotFeature.isTabCheck() && AntiBotFeature.isNameCheck() && !AntiBotFeature.isFilterRender()
                && AntiBotFeature.getCheckDelay() == 15, "All detection and render settings survive save/load");
        saved.setProperty("antibot.checkDelay", "-100");
        AntiBotFeature.load(saved);
        check(AntiBotFeature.getCheckDelay() == 0, "Negative delays are clamped");
        saved.setProperty("antibot.checkDelay", "1000");
        AntiBotFeature.load(saved);
        check(AntiBotFeature.getCheckDelay() == 100, "Oversized delays are clamped");
        saved.setProperty("antibot.checkDelay", "NaN");
        AntiBotFeature.load(saved);
        check(AntiBotFeature.getCheckDelay() == 40, "Malformed delays restore the default");
        AntiBotFeature.load(new Properties());
    }

    private static void consumers() throws Exception {
        Client mc = fixture();
        Local self = (Local) mc.thePlayer;
        Player bot = (Player) mc.theWorld.loadedEntityList.get(1);
        Player real = (Player) mc.theWorld.loadedEntityList.get(2);
        ticks(mc, 40);
        OpenMaverFeatures.setBypassEnabled(false);
        check(!(Boolean) invoke(OpenMaverFeatures.class, "isValidKillAuraTarget",
                new Class<?>[] {Object.class, EntityLivingBase.class}, self, bot), "KillAura rejects a classified bot");
        check((Boolean) invoke(OpenMaverFeatures.class, "isValidKillAuraTarget",
                new Class<?>[] {Object.class, EntityLivingBase.class}, self, real), "KillAura retains legitimate players");
        setFeature("aimEnabled", true);
        OpenMaverFeatures.setAimRequireClick(false);
        invoke(OpenMaverFeatures.class, "updateAimTarget", new Class<?>[] {Minecraft.class}, mc);
        check(feature("aimLockedTarget") == real, "Aim Assist skips a nearer bot");
        setFeature("aimLockedTarget", bot);
        invoke(OpenMaverFeatures.class, "updateAimTarget", new Class<?>[] {Minecraft.class}, mc);
        check(feature("aimLockedTarget") == real, "Aim Assist drops a target that becomes a bot");

        mc.objectMouseOver = new MovingObjectPosition();
        mc.objectMouseOver.typeOfHit = MovingObjectPosition.MovingObjectType.ENTITY;
        mc.objectMouseOver.entityHit = bot;
        check(!(Boolean) invoke(OpenMaverFeatures.class, "tryAutoLeftClick",
                new Class<?>[] {Minecraft.class, long.class}, mc, System.nanoTime()), "AutoClicker ignores a bot under the crosshair");
        OpenMaverFeatures.beginAttackTick();
        check(!OpenMaverFeatures.attackEntity18(mc, self, bot) && mc.playerController.target == null,
                "The shared attack path prevents bot attacks");
        Criticals criticals = new Criticals();
        ModuleManager.register(criticals);
        criticals.setEnabled(true);
        setFeature("criticalsJumpTarget", bot);
        setFeature("criticalsJumpDeadlineNanos", System.nanoTime() + 5_000_000_000L);
        invoke(OpenMaverFeatures.class, "processPendingCriticalJump", new Class<?>[] {Minecraft.class}, mc);
        check(feature("criticalsJumpTarget") == null, "Deferred attacks release a bot target");
        criticals.setEnabled(false);
        mc.objectMouseOver = null;
        OpenMaverFeatures.setReachDistance(5);
        invoke(OpenMaverFeatures.class, "attackNearestEntityInReach",
                new Class<?>[] {Minecraft.class, Object.class, Object.class}, mc, self, mc.theWorld);
        check(mc.playerController.target == real, "Reach attacks the real player rather than a nearer bot");

        NameTag nameTag = new NameTag();
        ModuleManager.register(nameTag);
        nameTag.setEnabled(true);
        RenderLivingEvent.Specials.Pre<EntityLivingBase> event = new RenderLivingEvent.Specials.Pre<EntityLivingBase>(bot, null, 0, 0, 2);
        NameTagFeature.onRenderName(mc, event);
        check(!event.isCanceled(), "NameTag leaves excluded bots to vanilla rendering");
        check(!(Boolean) invoke(TargetHud.class, "validTarget", new Class<?>[] {EntityLivingBase.class, EntityPlayer.class}, bot, self),
                "Target HUD does not label a bot");
        check(AntiBotFeature.shouldFilterRender(bot), "Rendering filters are enabled by default");
        AntiBotFeature.setFilterRender(false);
        event = new RenderLivingEvent.Specials.Pre<EntityLivingBase>(bot, null, 0, 0, 2);
        NameTagFeature.onRenderName(mc, event);
        check(event.isCanceled() && AntiBotFeature.isBot(bot), "Render filtering can be disabled while keeping combat filtering");
        module.setEnabled(false);
        OpenMaverFeatures.beginAttackTick();
        mc.playerController.target = null;
        invoke(OpenMaverFeatures.class, "attackNearestEntityInReach",
                new Class<?>[] {Minecraft.class, Object.class, Object.class}, mc, self, mc.theWorld);
        check(mc.playerController.target == bot, "Disabling AntiBot restores the nearest player as a target");
    }

    private static Client fixture() {
        Client mc = new Client();
        mc.theWorld = new World();
        mc.thePlayer = new Local();
        mc.gameSettings = new GameSettings();
        mc.renderManager = new RenderManager();
        mc.fontRendererObj = new FontRenderer();
        Player bot = new Player(new Profile(UUID.randomUUID(), "Bot"), 2);
        Player real = new Player(new Profile(UUID.randomUUID(), "Real"), 2.8);
        mc.theWorld.loadedEntityList.add(mc.thePlayer);
        mc.theWorld.loadedEntityList.add(bot);
        mc.theWorld.loadedEntityList.add(real);
        mc.connection.entries.add(new Info(((Local) mc.thePlayer).profile));
        mc.connection.entries.add(new Info(real.profile));
        return mc;
    }

    private static void ticks(Client mc, int count) { for (int i = 0; i < count; i++) AntiBotFeature.onTick(mc); }
    private static Object feature(String name) throws Exception {
        Field field = OpenMaverFeatures.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
    private static void setFeature(String name, Object value) throws Exception {
        Field field = OpenMaverFeatures.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
    private static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static final class Profile extends GameProfile {
        final UUID id;
        final String name;
        Profile(UUID id, String name) { super(id, name); this.id = id; this.name = name; }
        @Override public UUID getId() { return id; }
        @Override public String getName() { return name; }
    }
    public static final class Info {
        final GameProfile profile;
        Info(GameProfile profile) { this.profile = profile; }
        public GameProfile getGameProfile() { return profile; }
    }
    public static final class Connection {
        final List<Object> entries = new ArrayList<Object>();
        public Collection<?> getPlayerInfoMap() { return entries; }
    }
    public static final class Client extends Minecraft {
        Connection connection = new Connection();
        public final Controller playerController = new Controller();
        public int leftClickCounter;
        public Connection getNetHandler() { return connection; }
    }
    public static final class Controller {
        Entity target;
        public void attackEntity(EntityPlayer player, Entity entity) { target = entity; }
    }
    public static final class Local extends EntityPlayerSP {
        final Profile profile = new Profile(UUID.randomUUID(), "Self");
        public boolean serverSprintState;
        public GameProfile getGameProfile() { return profile; }
        @Override public float getEyeHeight() { return 1.62F; }
        @Override public double getDistanceSqToEntity(Entity entity) { return entity.posZ * entity.posZ; }
        public boolean isSprinting() { return false; }
        public void swingItem() {}
    }
    public static final class Player extends EntityPlayer {
        final Profile profile;
        Player(Profile profile, double z) { this.profile = profile; posZ = z; height = 1.8F; }
        public GameProfile getGameProfile() { return profile; }
        @Override public String getName() { return profile.getName(); }
        public boolean canBeCollidedWith() { return true; }
        @Override public AxisAlignedBB getEntityBoundingBox() { return new AxisAlignedBB(-0.3, 0, posZ - 0.3, 0.3, 1.8, posZ + 0.3); }
    }
}
