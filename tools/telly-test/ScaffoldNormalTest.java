import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import module.ModuleManager;
import module.movement.Scaffold;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Normal's actual lifecycle, world ray traces, settings and placement transport. */
public final class ScaffoldNormalTest {
    static final int[][] DELTAS = {{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    static int checks;
    static long tick;
    static Minecraft mc;
    static TellyBehaviorTest.Settings keys;
    static Player player;
    static World world;
    static Controller controller;
    static double frameX, frameZ;

    public static void main(String[] args) throws Exception {
        ModuleManager.register(new Scaffold());
        settings();
        rotationMath();
        lifecycle();
        invalidTargets();
        clickSettings();
        ModuleManager.disableAll();
        ScaffoldFeature.reset();
        System.out.println("[scaffold-normal-test] " + checks + " checks passed");
    }

    static void fresh(int origin) {
        ModuleManager.disableAll();
        ScaffoldFeature.reset();
        ScaffoldFeature.load(new Properties());
        Keyboard.reset();
        Mouse.left = Mouse.right = false;
        mc = new Minecraft();
        keys = new TellyBehaviorTest.Settings();
        player = new Player();
        player.posX += origin;
        player.posZ += origin;
        player.lastTickPosX += origin;
        player.lastTickPosZ += origin;
        player.inventory.mainInventory[1] = new TellyBehaviorTest.Stack();
        player.movementInput = new MovementInputFromOptions(keys);
        world = new World();
        world.add(origin, 63, origin);
        controller = new Controller();
        mc.thePlayer = player;
        mc.theWorld = world;
        mc.gameSettings = keys;
        mc.playerController = controller;
        ModuleManager.getModule("Scaffold").setEnabled(true);
    }

    static Object state(String name) throws Exception {
        Field f = ScaffoldFeature.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    static void start() throws Exception {
        frameX = player.posX;
        frameZ = player.posZ;
        Field f = OpenMaverFeatures.class.getDeclaredField("placementsThisTick");
        f.setAccessible(true);
        f.setInt(null, 0);
        ScaffoldFeature.tickCounter(++tick);
        ScaffoldFeature.onTickStart(mc);
        check(!Boolean.TRUE.equals(state("failed")), "Normal reflection remains usable");
        player.movementInput.updatePlayerMoveState();
        check(!ScaffoldFeature.ownsSneakKey(), "Normal never owns legacy sneak");
    }

    static void end() {
        controller.serverYaw = player.rotationYaw;
        controller.serverPitch = player.rotationPitch;
        controller.serverSlot = player.inventory.currentItem;
        player.lastTickPosX = frameX;
        player.lastTickPosZ = frameZ;
        ScaffoldFeature.onTickEnd(mc);
        check(Math.abs(ScaffoldFeature.wrapDegrees(player.rotationYaw)) < .001F
                && Math.abs(player.rotationPitch - 12) < .001F,
                "camera survives silent rotations: " + player.rotationYaw + "/" + player.rotationPitch);
    }

    static void frame() throws Exception { start(); end(); }

    private static void settings() throws Exception {
        ScaffoldFeature.load(new Properties());
        AugustusScaffold.Settings s = AugustusScaffold.SETTINGS;
        check(s.yawSpeed.low == 30 && s.yawSpeed.high == 50 && s.cps.low == 10 && s.cps.high == 20,
                "Augustus default rotation speed and CPS ranges");
        check(s.alwaysPlace && s.forcePlace && s.extraBlockSearch && !s.noSwing && s.pitchSelection == 0,
                "Augustus default placement and pitch selection");
        s.yawSpeed.setLow(36); s.yawSpeed.setHigh(42);
        s.pitchSelection = 4; s.maxPitchCorrection = 27; s.noSwing = true; s.alwaysPlace = false;
        Properties saved = new Properties();
        saved.load(new StringReader(ScaffoldFeature.save()));
        saved.setProperty("scaffold.eagle", "true");
        saved.setProperty("scaffold.snap", "true");
        ScaffoldFeature.load(saved);
        check(s.yawSpeed.low == 36 && s.yawSpeed.high == 42 && s.pitchSelection == 4
                && s.maxPitchCorrection == 27 && s.noSwing && !s.alwaysPlace, "Normal settings round trip");
        check(!ScaffoldFeature.save().contains("scaffold.eagle") && !ScaffoldFeature.save().contains("scaffold.snap"),
                "old Normal settings are retired");
        saved.setProperty("scaffold.normal.yawSpeedMin", "NaN");
        saved.setProperty("scaffold.normal.yawSpeedMax", "Infinity");
        saved.setProperty("scaffold.normal.cpsMin", "-10");
        saved.setProperty("scaffold.normal.cpsMax", "99");
        saved.setProperty("scaffold.normal.pitchSelection", "99");
        saved.setProperty("scaffold.normal.maxPitchCorrection", "NaN");
        ScaffoldFeature.load(saved);
        check(s.yawSpeed.low == 30 && s.yawSpeed.high == 50 && s.cps.low == 0 && s.cps.high == 40
                && s.pitchSelection == 5 && s.maxPitchCorrection == 180, "invalid config is finite and bounded");
    }

    private static void rotationMath() {
        check(AugustusScaffold.movementYaw(0, 1, 1) == -45, "forward diagonal movement yaw");
        check(AugustusScaffold.movementYaw(0, -1, 1) == 225, "backward diagonal movement yaw");
        check(AugustusScaffold.movementYaw(0, 0, -1) == 90, "side movement yaw");
        check(AugustusScaffold.accelerate(0, 90, 30, 7) == 7, "rotation acceleration limit");
        check(AugustusScaffold.accelerate(28, 90, 30, 7) == 30, "rotation speed limit");
        check(AugustusScaffold.accelerate(28, 2, 30, 7) == 2, "rotation stops at target");
        check(AugustusScaffold.accelerate(28, -90, 30, 7) == -7, "a new face restarts the turn toward its valid interval");
        float angle = AugustusScaffold.quantize(359.9F, 362.3F, .15);
        check(Math.abs((angle - 359.9F) / .15 - 16) < .001, "mouse grid is relative to previous angle");
    }

    private static void lifecycle() throws Exception {
        fresh(0);
        keys.keyBindForward.pressed = true;
        for (int i = 0; i < 12; i++) frame();
        check(Math.abs(ScaffoldFeature.wrapDegrees(controller.serverYaw + 180)) < 1,
                "Normal prepares backward movement yaw before the edge");
        for (String name : new String[] {"velocityDelay", "rotationDelay", "targetYLevel"}) {
            Field legacy = ScaffoldFeature.class.getDeclaredField(name);
            legacy.setAccessible(true);
            legacy.setInt(null, 60);
        }
        player.posZ = 1.16;
        player.lastTickPosZ = player.posZ;
        for (int i = 0; i < 8 && controller.placements == 0; i++) frame();
        check(controller.placements > 0 && !Mouse.right, "Normal places automatically without right mouse");
        check(((Integer) state("velocityDelay")) == 60 && ((Integer) state("rotationDelay")) == 60,
                "Normal never runs the old Clutch, target height or knockback controller");
        check(player.swings > 0, "default placement swings");
        check(!keys.keyBindJump.pressed && !keys.keyBindSneak.pressed, "old autojump and Eagle are absent");
        keys.keyBindSneak.pressed = true;
        Keyboard.setKeyDown(42, true);
        frame();
        check(player.movementInput.sneak, "physical sneak remains available");
        Keyboard.setKeyDown(42, false); keys.keyBindSneak.pressed = false;
        player.posZ = 2.16;
        player.lastTickPosZ = player.posZ;
        for (int i = 0; i < 8 && state("pending") == null; i++) frame();
        check(state("pending") != null, "lifecycle test has a transmitted pending aim");
        int placed = controller.placements;
        mc.currentScreen = new Object();
        frame();
        check(controller.placements == placed && state("pending") == null, "GUI drops pending placement");
        mc.currentScreen = null;
        ScaffoldFeature.setMode(ScaffoldFeature.MODE_TELLY_BRIDGE);
        check(state("pending") == null && state("placement") == null, "mode switch clears Normal targets");
        ScaffoldFeature.setMode(ScaffoldFeature.MODE_NORMAL);
        ModuleManager.getModule("Scaffold").setEnabled(false);
        frame();
        check(!keys.keyBindJump.pressed && !keys.keyBindSneak.pressed, "disable restores input");
        fresh(0);
        player.inventory.mainInventory[1] = null;
        for (int i = 0; i < 24; i++) frame();
        check(controller.placements == 0 && player.movementInput.moveForward == 0
                && player.movementInput.moveStrafe == 0, "no blocks or WASD creates no placement or movement");
    }

    private static void invalidTargets() throws Exception {
        fresh(1_000_000);
        player.posZ += .66;
        player.lastTickPosZ = player.posZ;
        keys.keyBindForward.pressed = true;
        for (int i = 0; i < 20 && state("pending") == null; i++) frame();
        check(state("pending") != null, "large coordinates preserve an exact side ray");
        int before = controller.placements;
        world.add(1_000_000, 63, 1_000_001);
        frame();
        check(controller.placements == before, "occupied destination invalidates a queued click");
        world.blocks.clear();
        for (int i = 0; i < 2; i++) frame();
        check(controller.placements == before && state("pending") == null, "missing support drops stale target");
        check(!AugustusScaffold.canPlace(world, 0, 63, 0, 0), "DOWN face is rejected");
        fresh(0);
        player.posZ = player.lastTickPosZ = 1.16;
        keys.keyBindForward.pressed = true;
        for (int i = 0; i < 20 && state("pending") == null; i++) frame();
        check(state("pending") != null, "occlusion test has a transmitted pending aim");
        before = controller.placements;
        world.add(0, 64, 1);
        frame();
        check(controller.placements == before, "an intervening block cancels the queued click");
    }

    private static void clickSettings() throws Exception {
        fresh(0);
        AugustusScaffold normal = new AugustusScaffold();
        AugustusScaffold.Settings s = AugustusScaffold.SETTINGS;
        s.alwaysPlace = s.forcePlace = false;
        s.cps.low = s.cps.high = 0;
        check(!normal.clickReady(player, world), "zero CPS disables timed placement");
        s.alwaysPlace = true;
        check(normal.clickReady(player, world), "always place overrides the CPS timer");
        s.alwaysPlace = false; s.forcePlace = true; player.posZ = 1.2;
        player.lastTickPosZ = player.posZ;
        check(normal.clickReady(player, world), "force place detects an unsupported next step");
        s.forcePlace = false; s.cps.low = s.cps.high = 10;
        normal.reset();
        check(normal.clickReady(player, world), "first timed click is available");
        normal.placed();
        check(!normal.clickReady(player, world), "timed click waits for its interval");
        s.alwaysPlace = true; s.noSwing = true;
        keys.keyBindForward.pressed = true;
        for (int i = 0; i < 20 && controller.placements == 0; i++) frame();
        check(controller.placements > 0 && player.swings == 0, "no swing suppresses the animation");
    }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static final class Player {
        public double posX = .5, posY = 64, posZ = .5, motionX, motionY, motionZ;
        public double lastTickPosX = .5, lastTickPosZ = .5;
        public float rotationYaw, rotationPitch = 12;
        public boolean onGround = true;
        public int swings;
        public TellyBehaviorTest.Inventory inventory = new TellyBehaviorTest.Inventory();
        public MovementInput movementInput;
        public boolean isEntityAlive() { return true; }
        public boolean isSprinting() { return false; }
        public void setSprinting(boolean value) { }
        public float getEyeHeight() { return 1.62F; }
        public Object getHeldItem() { return inventory.mainInventory[inventory.currentItem]; }
        public void swingItem() { swings++; }
    }

    public static final class Block {
        public AxisAlignedBB getCollisionBoundingBox(World w, BlockPos p, State s) {
            return new AxisAlignedBB(p.x, p.y, p.z, p.x+1, p.y+1, p.z+1);
        }
    }
    public static final class State { public Block getBlock() { return new Block(); } }
    public static final class Hit {
        public String typeOfHit = "BLOCK";
        public BlockPos blockPos;
        public EnumFacing sideHit;
        public Vec3 hitVec;
        Hit(int x, int y, int z, int face, double[] p) {
            blockPos = new BlockPos(x,y,z); sideHit = EnumFacing.values()[face];
            hitVec = new Vec3(p[0],p[1],p[2]);
        }
    }
    public static final class World {
        final Set<String> blocks = new HashSet<String>();
        static String key(int x, int y, int z) { return x+","+y+","+z; }
        boolean add(int x, int y, int z) { return blocks.add(key(x,y,z)); }
        public boolean isAirBlock(BlockPos p) { return !blocks.contains(key(p.x,p.y,p.z)); }
        public State getBlockState(BlockPos p) { return isAirBlock(p) ? null : new State(); }
        boolean supports(double x, double z) {
            for (int bx = ScaffoldFeature.floor(x-.299); bx <= ScaffoldFeature.floor(x+.299); bx++)
                for (int bz = ScaffoldFeature.floor(z-.299); bz <= ScaffoldFeature.floor(z+.299); bz++)
                    if (blocks.contains(key(bx,63,bz))) return true;
            return false;
        }
        public Hit rayTraceBlocks(Vec3 from, Vec3 to) {
            double[] start = {from.xCoord,from.yCoord,from.zCoord};
            double[] delta = {to.xCoord-from.xCoord,to.yCoord-from.yCoord,to.zCoord-from.zCoord};
            double best = Double.POSITIVE_INFINITY;
            Hit result = null;
            for (String key : blocks) {
                String[] parts = key.split(",");
                int[] base = {Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])};
                for (int face = 0; face < 6; face++) {
                    int axis = face <= 1 ? 1 : face <= 3 ? 2 : 0;
                    // Minecraft also intersects an exit face when the probe starts inside a block.
                    if (Math.abs(delta[axis]) < 1e-10) continue;
                    double t = (base[axis]+face%2-start[axis])/delta[axis];
                    if (t < 0 || t > 1 || t >= best) continue;
                    double[] p = {start[0]+t*delta[0],start[1]+t*delta[1],start[2]+t*delta[2]};
                    boolean inside = true;
                    for (int a = 0; a < 3; a++)
                        if (a != axis && (p[a] < base[a] || p[a] > base[a]+1)) inside = false;
                    if (inside) { best=t; result=new Hit(base[0],base[1],base[2],face,p); }
                }
            }
            return result;
        }
    }
    public static final class Controller {
        int placements, serverSlot;
        float serverYaw, serverPitch;
        long placedTick = -1;
        public float getBlockReachDistance() { return 4.5F; }
        public boolean onPlayerRightClick(Player p, World w, Object stack, BlockPos pos, EnumFacing face, Vec3 point) {
            check(placedTick != tick, "at most one placement per tick");
            check(serverSlot == p.inventory.currentItem && stack == p.getHeldItem(), "C09 precedes C08 with the held stack");
            AugustusScaffold.Target hit = AugustusScaffold.ray(w,p.posX,p.posY+p.getEyeHeight(),p.posZ,serverYaw,serverPitch,4.5);
            check(hit != null && hit.x == pos.x && hit.y == pos.y && hit.z == pos.z && hit.face == face.ordinal(),
                    "C08 uses last transmitted C03 at the current eye");
            check(Math.abs(hit.hitX-point.xCoord)<1e-6 && Math.abs(hit.hitY-point.yCoord)<1e-6
                    && Math.abs(hit.hitZ-point.zCoord)<1e-6, "C08 contains the actual ray intersection");
            int[] d=DELTAS[face.ordinal()];
            if (Boolean.getBoolean("normal.test.trace")) System.out.printf("place tick=%d support=%d/%d/%d face=%s dest=%d/%d/%d%n",
                    tick,pos.x,pos.y,pos.z,face,pos.x+d[0],pos.y+d[1],pos.z+d[2]);
            check(w.add(pos.x+d[0],pos.y+d[1],pos.z+d[2]), "placement fills a real empty neighbour");
            placements++; placedTick=tick;
            return true;
        }
    }
}
