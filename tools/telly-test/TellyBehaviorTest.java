import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;

import module.Module;
import module.ModuleManager;
import module.movement.GodBridge;
import module.movement.Scaffold;
import module.movement.Scaffold2;
import module.movement.Telly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovementInput;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Exercises the real tick lifecycle with physical input separate from KeyBinding.pressed. */
public final class TellyBehaviorTest {
    private static int checks;
    private static long tick;
    private static Minecraft mc;
    private static Settings keys;
    private static Player player;

    public static void main(String[] args) throws Exception {
        mc = new Minecraft();
        ModuleManager.register(new Scaffold());
        ModuleManager.register(new GodBridge());
        ModuleManager.register(new Scaffold2());
        ModuleManager.register(new Telly());
        settingsRoundTrip();
        mutualExclusion();
        competingFeatures();
        heightAndInput();
        airbornePlacement();
        lifecycle();
        srgInput();
        ModuleManager.disableAll();
        ScaffoldFeature.reset();
        System.out.println("[telly-test] " + checks + " checks passed");
    }

    private static void fresh() {
        ModuleManager.disableAll();
        ScaffoldFeature.reset();
        TellyFeature.load(new Properties());
        TellyFeature.setSneak(false);
        Keyboard.reset();
        Mouse.left = Mouse.right = false;
        keys = new Settings();
        player = new Player();
        mc.gameSettings = keys;
        mc.thePlayer = player;
        mc.theWorld = new World();
        mc.playerController = new Controller();
        mc.currentScreen = null;
        module("Telly").setEnabled(true);
    }

    private static void start() throws Exception {
        field(OpenMaverFeatures.class, "placementsThisTick").setInt(null, 0);
        ScaffoldFeature.tickCounter(++tick);
        ScaffoldFeature.onTickStart(mc);
        check(!flag("failed"), "runtime reflection succeeds");
    }

    private static void end() { ScaffoldFeature.onTickEnd(mc); }

    private static void settingsRoundTrip() throws Exception {
        ScaffoldFeature.load(new Properties());
        ScaffoldFeature.setMode(ScaffoldFeature.MODE_KEEP_Y);
        ScaffoldFeature.setRotationTick(2);
        TellyFeature.load(new Properties());
        check(TellyFeature.getMode() == 0 && TellyFeature.getSlotMode() == ScaffoldFeature.SLOT_HOTBAR,
                "Telly defaults to Telly Bridge with real hotbar switching");
        TellyFeature.setMode(1);
        TellyFeature.setRotationTick(5);
        TellyFeature.setSneak(false);
        TellyFeature.setClutch(false);
        TellyFeature.setSlotMode(0);
        TellyFeature.setItemSpoof(false);
        TellyFeature.setHud(false);
        Properties saved = new Properties();
        saved.load(new StringReader(ScaffoldFeature.save() + "\n" + TellyFeature.save() + "next.setting=true\n"));
        TellyFeature.load(new Properties());
        TellyFeature.load(saved);
        check(TellyFeature.getMode() == 1 && TellyFeature.getRotationTick() == 5
                && !TellyFeature.isSneak() && !TellyFeature.isClutch()
                && TellyFeature.getSlotMode() == 0 && !TellyFeature.isItemSpoof() && !TellyFeature.isHud(),
                "all Telly settings survive a properties round trip");
        check("true".equals(saved.getProperty("next.setting")), "saved settings end with a newline");
        check(ScaffoldFeature.getMode() == ScaffoldFeature.MODE_KEEP_Y
                && ScaffoldFeature.getRotationTick() == 2, "Telly settings do not modify Scaffold");
        saved.setProperty("telly.mode", "99");
        saved.setProperty("telly.rotationTick", "invalid");
        saved.setProperty("telly.slotMode", "-1");
        TellyFeature.load(saved);
        check(TellyFeature.getMode() == 1 && TellyFeature.getRotationTick() == 3
                && TellyFeature.getSlotMode() == 0, "invalid settings use bounded values or defaults");
    }

    private static void mutualExclusion() {
        for (String name : new String[] {"Scaffold", "GodBridge", "Scaffold2"}) {
            module(name).setEnabled(true);
            module("Telly").setEnabled(true);
            check(!module(name).isEnabled(), "Telly disables " + name);
            module(name).setEnabled(true);
            check(!module("Telly").isEnabled(), name + " disables Telly");
        }
        check("movement.telly".equals(module("Telly").getConfigKey()), "module has its own config key");
    }

    private static void heightAndInput() throws Exception {
        fresh();
        keys.keyBindForward.pressed = true;
        start();
        check(keys.keyBindJump.pressed, "walking automatically holds jump");
        check(number("targetYLevel") == 63, "ground tick captures the bridge height");
        end();
        player.onGround = false;
        player.posY = 65.1;
        start();
        check(number("targetYLevel") == 63, "synthetic jump does not raise the bridge while airborne");
        end();
        Keyboard.setKeyDown(Keyboard.KEY_SPACE, true);
        start();
        check(number("targetYLevel") == 64, "physical space allows a higher bridge");
        end();
        Keyboard.setKeyDown(Keyboard.KEY_SPACE, false);
        keys.keyBindForward.pressed = false;
        start();
        check(!keys.keyBindJump.pressed, "stopping releases automatic jump");
        end();
        keys.keyBindJump.code = -99;
        Mouse.right = true;
        player.posY = 66.1;
        keys.keyBindForward.pressed = true;
        start();
        check(number("targetYLevel") == 65 && keys.keyBindJump.pressed,
                "a mouse-bound jump is recognized as physical input");
        module("Telly").setEnabled(false);
        start();
        check(keys.keyBindJump.pressed, "disabling preserves a physically held mouse-bound jump");
        end();
    }

    private static void competingFeatures() throws Exception {
        fresh();
        Method busy = AttackReduceFeature.class.getDeclaredMethod("rotationBusy");
        busy.setAccessible(true);
        check(Boolean.TRUE.equals(busy.invoke(null)), "Velocity yields rotation to standalone Telly");
        Method placing = OpenMaverFeatures.class.getDeclaredMethod("placementModuleEnabled");
        placing.setAccessible(true);
        check(Boolean.TRUE.equals(placing.invoke(null)), "FastPlace recognizes standalone Telly placement");
        check(ScaffoldHud.isEnabled(), "the shared block HUD recognizes standalone Telly");
        TellyFeature.setHud(false);
        check(!ScaffoldHud.isEnabled(), "Telly's own HUD setting controls the shared card");
    }

    private static void airbornePlacement() throws Exception {
        fresh();
        TellyFeature.setClutch(false);
        player.inventory.mainInventory[1] = new Stack();
        player.posZ = 1.2;
        keys.keyBindForward.pressed = true;
        start();
        check(player.inventory.currentItem == 1, "hotbar switches to a placeable block");
        check(state("pending") == null, "ground phase does not schedule placement");
        end();
        player.onGround = false;
        player.posY = 64.4;
        player.movementInput.moveForward = 1;
        start();
        check(state("pending") != null, "air phase schedules a supported placement");
        end(); // No movement hook ran, so this aim was never sent.
        start();
        check(((Controller) mc.playerController).placements == 0, "an unsent aim cannot place");
        ScaffoldFeature.afterMovementInput(player.movementInput);
        check(Math.abs(player.rotationYaw - 27) > 1, "movement hook applies silent aim");
        end();
        check(Math.abs(player.rotationYaw - 27) < 0.01 && Math.abs(player.rotationPitch - 12) < 0.01,
                "tick end restores the camera");
        for (int i = 0; i < 12 && ((Controller) mc.playerController).placements == 0; i++) {
            start();
            ScaffoldFeature.afterMovementInput(player.movementInput);
            end();
        }
        Controller controller = (Controller) mc.playerController;
        check(controller.placements == 1, "a sent aim that hits the supporting face places one block");
        check(controller.face == EnumFacing.SOUTH && controller.stack == player.getHeldItem(),
                "placement uses the supporting face and actual held stack");

        // A pending airborne aim must be discarded if the next tick lands.
        fresh();
        player.inventory.mainInventory[1] = new Stack();
        player.posZ = 1.2;
        keys.keyBindForward.pressed = true;
        start(); end();
        player.onGround = false;
        player.posY = 64.4;
        player.movementInput.moveForward = 1;
        start();
        ScaffoldFeature.afterMovementInput(player.movementInput);
        end();
        player.onGround = true;
        player.posY = 64;
        start();
        check(((Controller) mc.playerController).placements == 0 && state("pending") == null,
                "landing cancels an airborne pending placement");
        end();
    }

    private static void lifecycle() throws Exception {
        fresh();
        keys.keyBindForward.pressed = true;
        TellyFeature.setSneak(true);
        for (int i = 0; i < 18; i++) { start(); end(); }
        check(keys.keyBindSneak.pressed && ScaffoldFeature.ownsSneakKey(), "OpenZen sneak cadence engages at tick 18");
        mc.currentScreen = new Object();
        start();
        check(!keys.keyBindJump.pressed && !keys.keyBindSneak.pressed && !ScaffoldFeature.ownsSneakKey(),
                "opening a screen releases both automatic keys and ownership");
        mc.currentScreen = null;
        start(); end();
        check(keys.keyBindJump.pressed, "closing the screen resumes Telly");
        Player old = player;
        player = new Player();
        player.posY = 100;
        mc.thePlayer = player;
        start(); end();
        check(number("targetYLevel") == 99 && number("groundTicks") == 1,
                "respawn initializes height and tick counters for the new player");
        check(Math.abs(old.rotationYaw - 27) < 0.01, "respawn leaves the old camera restored");
        mc.theWorld = new World();
        player.posY = 80;
        start(); end();
        check(number("targetYLevel") == 79 && number("groundTicks") == 1, "world change resets the old bridge state");
        mc.theWorld = null;
        start();
        check(!keys.keyBindJump.pressed && state("pending") == null && !flag("hookArmed"),
                "disconnect releases inputs and disarms the movement hook");
        mc.theWorld = new World();
        start(); end();
        player.alive = false;
        start();
        check(!keys.keyBindJump.pressed && !flag("active"), "death suspends Telly and restores input");
        player.alive = true;
        start(); end();
        TellyFeature.setMode(TellyFeature.MODE_OLD_TELLY);
        start(); end();
        check(((ScaffoldFeature.Settings) state("bridgeSettings")).mode == ScaffoldFeature.MODE_OLD_TELLY,
                "Old Telly selects the existing OpenZen rotation branch");
        module("Scaffold").setEnabled(true);
        ScaffoldFeature.setMode(ScaffoldFeature.MODE_NORMAL);
        start(); end();
        check(!keys.keyBindJump.pressed && !module("Telly").isEnabled(),
                "switching to normal Scaffold releases Telly jump");
    }

    private static void srgInput() throws Exception {
        fresh();
        SrgSettings srg = new SrgSettings();
        mc.gameSettings = srg;
        srg.field_74351_w.field_74513_e = true;
        start(); end();
        player.onGround = false;
        player.posY = 65.1;
        start(); end();
        check(srg.field_74314_A.field_74513_e && number("targetYLevel") == 63,
                "SRG key fields preserve automatic jump and locked height");
        Keyboard.setKeyDown(Keyboard.KEY_SPACE, true);
        start(); end();
        check(number("targetYLevel") == 64, "SRG fields recognize physical space");
        module("Telly").setEnabled(false);
        start();
        check(srg.field_74314_A.field_74513_e, "disabling preserves physically held space");
    }

    private static Module module(String name) { return ModuleManager.getModule(name); }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object state(String name) throws Exception { return field(ScaffoldFeature.class, name).get(null); }
    private static boolean flag(String name) throws Exception { return Boolean.TRUE.equals(state(name)); }
    private static int number(String name) throws Exception { return ((Number) state(name)).intValue(); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static final class Binding {
        public int code;
        public boolean pressed;
        Binding(int code) { this.code = code; }
        public int getKeyCode() { return code; }
    }
    public static final class Settings extends GameSettings {
        public Binding keyBindForward = new Binding(17), keyBindBack = new Binding(31);
        public Binding keyBindLeft = new Binding(30), keyBindRight = new Binding(32);
        public Binding keyBindJump = new Binding(57), keyBindSneak = new Binding(42), keyBindSprint = new Binding(29);
        public float mouseSensitivity = 0.5F;
    }
    public static final class SrgSettings extends GameSettings {
        public SrgBinding field_74351_w = new SrgBinding(17), field_74368_y = new SrgBinding(31);
        public SrgBinding field_74370_x = new SrgBinding(30), field_74366_z = new SrgBinding(32);
        public SrgBinding field_74314_A = new SrgBinding(57), field_74311_E = new SrgBinding(42), field_74321_H = new SrgBinding(29);
        public float field_74341_c = 0.5F;
    }
    public static final class SrgBinding {
        private final int code;
        public boolean field_74513_e;
        SrgBinding(int code) { this.code = code; }
        public int func_151463_i() { return code; }
    }
    public static final class Inventory {
        public int currentItem;
        public Object[] mainInventory = new Object[36];
    }
    public static final class Player {
        public double posX = 0.5, posY = 64, posZ = 0.5, motionX, motionY, motionZ;
        public float rotationYaw = 27, rotationPitch = 12;
        public boolean onGround = true, alive = true;
        public int jumpTicks;
        public Inventory inventory = new Inventory();
        public MovementInput movementInput;
        public boolean isEntityAlive() { return alive; }
        public float getEyeHeight() { return 1.62F; }
        public Object getHeldItem() { return inventory.mainInventory[inventory.currentItem]; }
        public void swingItem() { }
        public boolean isSprinting() { return false; }
    }
    public static final class Stack {
        public int stackSize = 64;
        public Object getItem() { return new ItemBlock(); }
    }
    public static final class ItemBlock { public Block block = new Block(); }
    public static final class Block {
        public AxisAlignedBB getCollisionBoundingBox(World world, BlockPos pos, State state) {
            return new AxisAlignedBB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1);
        }
    }
    public static final class State { public Block getBlock() { return new Block(); } }
    public static final class Hit {
        public String typeOfHit = "BLOCK";
        public BlockPos blockPos = new BlockPos(0, 63, 0);
        public EnumFacing sideHit = EnumFacing.SOUTH;
    }
    public static final class World {
        public boolean isAirBlock(BlockPos pos) { return pos.x != 0 || pos.y != 63 || pos.z != 0; }
        public State getBlockState(BlockPos pos) { return isAirBlock(pos) ? null : new State(); }
        public Hit rayTraceBlocks(Vec3 from, Vec3 to) {
            // The fixture contains one full support block. Check the exposed south face.
            double t = (1 - from.zCoord) / (to.zCoord - from.zCoord);
            double x = from.xCoord + t * (to.xCoord - from.xCoord);
            double y = from.yCoord + t * (to.yCoord - from.yCoord);
            return t > 0 && t <= 1 && x > 0 && x < 1 && y > 63 && y < 64 ? new Hit() : null;
        }
    }
    public static final class Controller {
        public int placements;
        public Object stack;
        public EnumFacing face;
        public float getBlockReachDistance() { return 4.5F; }
        public boolean onPlayerRightClick(Player player, World world, Object stack, BlockPos pos, EnumFacing face, Vec3 hit) {
            placements++; this.stack = stack; this.face = face; return true;
        }
    }
}
