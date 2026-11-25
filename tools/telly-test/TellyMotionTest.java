import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import module.ModuleManager;
import module.movement.Scaffold;
import module.movement.Telly;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovementInputFromOptions;
import net.minecraft.util.Vec3;

/** Runs actual input/tick hooks while the player moves through a vanilla jump arc. */
public final class TellyMotionTest {
    private static final int[][] DELTAS = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };
    private static int checks;

    public static void main(String[] args) throws Exception {
        ModuleManager.register(new Telly());
        ModuleManager.register(new Scaffold());
        for (boolean standalone : new boolean[] {false, true}) {
            for (int mode = 0; mode <= 1; mode++) {
                for (double speed : new double[] {0.22D, 0.28D, 0.35D}) {
                    movingJump(standalone, mode, speed);
                }
            }
        }
        System.out.println("[telly-motion-test] " + checks + " checks passed");
    }

    private static void movingJump(boolean standalone, int mode, double speed) throws Exception {
        ModuleManager.disableAll();
        ScaffoldFeature.reset();
        ScaffoldFeature.load(new Properties());
        TellyFeature.load(new Properties());
        String module = standalone ? "Telly" : "Scaffold";
        String scenario = module + " mode=" + mode + " speed=" + speed;
        if (speed == 0.22D) {
            // Config loading and GUI selection must both activate the shared behavior.
            Properties config = new Properties();
            if (standalone) {
                config.setProperty("telly.mode", String.valueOf(mode));
                TellyFeature.load(config);
            } else {
                config.setProperty("scaffold.mode", String.valueOf(mode + ScaffoldFeature.MODE_TELLY_BRIDGE));
                ScaffoldFeature.load(config);
            }
        } else if (standalone) {
            TellyFeature.setMode(mode);
        } else {
            ScaffoldFeature.setMode(mode + ScaffoldFeature.MODE_TELLY_BRIDGE);
        }
        if (standalone) TellyFeature.setSneak(false);
        else ScaffoldFeature.setSneak(false);
        Minecraft mc = new Minecraft();
        TellyBehaviorTest.Settings keys = new TellyBehaviorTest.Settings();
        TellyBehaviorTest.Player player = new TellyBehaviorTest.Player();
        player.rotationYaw = 0;
        player.posZ = 0.7;
        player.inventory.mainInventory[1] = new TellyBehaviorTest.Stack();
        player.movementInput = new MovementInputFromOptions(keys);
        World world = new World();
        Controller controller = new Controller();
        mc.thePlayer = player;
        mc.theWorld = world;
        mc.gameSettings = keys;
        mc.playerController = controller;
        keys.keyBindForward.pressed = true;
        ModuleManager.getModule(module).setEnabled(true);
        Field placements = OpenMaverFeatures.class.getDeclaredField("placementsThisTick");
        placements.setAccessible(true);

        for (int tick = 1; tick <= 24; tick++) {
            placements.setInt(null, 0);
            ScaffoldFeature.tickCounter(tick);
            ScaffoldFeature.onTickStart(mc);
            check(controller.failure == null, controller.failure);
            player.movementInput.updatePlayerMoveState();
            if (Boolean.getBoolean("telly.test.trace")) {
                System.out.printf("%s tick=%d y=%.3f z=%.3f aim=%.2f/%.2f blocks=%s%n", scenario, tick,
                        player.posY, player.posZ, player.rotationYaw, player.rotationPitch, world.blocks);
            }
            if (player.onGround && player.movementInput.jump) {
                player.motionY = 0.42D;
                player.onGround = false;
            }
            player.motionZ = speed;
            player.posZ += player.motionZ;
            player.posY += player.motionY;
            if (player.posY < 64 && world.hasSupport(player.posZ)) {
                player.posY = 64;
                player.motionY = 0;
                player.onGround = true;
            } else {
                player.motionY = (player.motionY - 0.08D) * 0.98D;
            }
            // EntityPlayerSP sends C03 after movement and before ClientTickEvent.END.
            controller.serverYaw = player.rotationYaw;
            controller.serverPitch = player.rotationPitch;
            ScaffoldFeature.onTickEnd(mc);
            check(player.posY >= 64, scenario + " has a landing block at tick=" + tick
                    + " z=" + player.posZ + " placements=" + controller.placements);
        }
        check(controller.placements >= (int) Math.floor(player.posZ - 0.3D),
                "both jumps extend the bridge in " + scenario);
        ModuleManager.disableAll();
        ScaffoldFeature.reset();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static final class Block {
        public AxisAlignedBB func_180640_a(World world, BlockPos pos, State state) {
            return new AxisAlignedBB(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1);
        }
    }

    public static final class State {
        public Block getBlock() { return new Block(); }
    }

    public static final class Hit {
        public String typeOfHit = "BLOCK";
        public BlockPos blockPos;
        public EnumFacing sideHit;
        public Vec3 hitVec;
        Hit(BlockPos pos, int face, Vec3 point) {
            blockPos = pos;
            sideHit = EnumFacing.values()[face];
            hitVec = point;
        }
    }

    public static final class World {
        private final Set<Integer> blocks = new HashSet<Integer>();
        World() { blocks.add(0); }
        boolean hasSupport(double z) {
            for (int blockZ : blocks) {
                if (z + 0.3 > blockZ && z - 0.3 < blockZ + 1) return true;
            }
            return false;
        }
        public boolean isAirBlock(BlockPos pos) { return pos.x != 0 || pos.y != 63 || !blocks.contains(pos.z); }
        public State getBlockState(BlockPos pos) { return isAirBlock(pos) ? null : new State(); }
        public Hit rayTraceBlocks(Vec3 from, Vec3 to) {
            double[] start = {from.xCoord, from.yCoord, from.zCoord};
            double[] delta = {to.xCoord - from.xCoord, to.yCoord - from.yCoord, to.zCoord - from.zCoord};
            double best = Double.POSITIVE_INFINITY;
            Hit result = null;
            for (int z : blocks) {
                int[] base = {0, 63, z};
                for (int face = 0; face < 6; face++) {
                    int axis = face <= 1 ? 1 : face <= 3 ? 2 : 0;
                    if (delta[axis] * DELTAS[face][axis] >= 0) continue;
                    double t = (base[axis] + (face % 2) - start[axis]) / delta[axis];
                    if (t < 0 || t > 1 || t >= best) continue;
                    double[] p = {start[0] + t * delta[0], start[1] + t * delta[1], start[2] + t * delta[2]};
                    boolean inside = true;
                    for (int a = 0; a < 3; a++) {
                        if (a != axis && (p[a] < base[a] || p[a] > base[a] + 1)) inside = false;
                    }
                    if (inside) {
                        best = t;
                        result = new Hit(new BlockPos(0, 63, z), face, new Vec3(p[0], p[1], p[2]));
                    }
                }
            }
            if (Boolean.getBoolean("telly.test.trace")) {
                System.out.printf("ray from=%.3f/%.3f/%.3f hit=%s%n", from.xCoord, from.yCoord,
                        from.zCoord, result == null ? "miss" : result.blockPos.z + ":" + result.sideHit);
            }
            return result;
        }
    }

    public static final class Controller {
        int placements;
        float serverYaw, serverPitch;
        String failure;
        private void verify(boolean value, String message) {
            if (!value) failure = message;
            check(value, message);
        }
        public float getBlockReachDistance() { return 4.5F; }
        public boolean onPlayerRightClick(TellyBehaviorTest.Player player, World world, Object stack,
                BlockPos pos, EnumFacing face, Vec3 point) {
            double yaw = Math.toRadians(serverYaw), pitch = Math.toRadians(serverPitch);
            Vec3 eye = new Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ);
            Hit hit = world.rayTraceBlocks(eye, new Vec3(eye.xCoord - Math.sin(yaw) * Math.cos(pitch) * 4.5,
                    eye.yCoord - Math.sin(pitch) * 4.5, eye.zCoord + Math.cos(yaw) * Math.cos(pitch) * 4.5));
            verify(hit != null && hit.blockPos.z == pos.z && hit.sideHit == face,
                    "placement uses the last C03 aim from the current eye position");
            verify(Math.abs(hit.hitVec.xCoord - point.xCoord) < 0.001
                    && Math.abs(hit.hitVec.yCoord - point.yCoord) < 0.001
                    && Math.abs(hit.hitVec.zCoord - point.zCoord) < 0.001,
                    "C08 click point matches the ray after movement");
            int[] delta = DELTAS[face.ordinal()];
            verify(pos.x + delta[0] == 0 && pos.y + delta[1] == 63,
                    "automatic Telly keeps the bridge level");
            verify(world.blocks.add(pos.z + delta[2]), "placement fills an empty cell");
            placements++;
            return true;
        }
    }
}
