import java.lang.reflect.Field;
import java.util.Properties;
import module.ModuleManager;
import module.movement.Scaffold;
import module.movement.Telly;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;
import org.lwjgl.input.Keyboard;

/** Horizontal physics driven by the real input hook, with a fixed reachable support block. */
public final class SneakMovementTest {
    private static int checks;
    private static long tick;

    public static void main(String[] args) throws Exception {
        ModuleManager.register(new Scaffold());
        ModuleManager.register(new Telly());
        for (boolean standalone : new boolean[] {false, true}) {
            for (int mode = standalone ? 1 : 0; mode <= 2; mode++) {
                for (int sneak = 0; sneak <= 2; sneak++) {
                    for (int direction = 0; direction < 8; direction++) {
                        scenario(standalone, mode, sneak, direction);
                    }
                }
            }
        }
        ModuleManager.disableAll();
        ScaffoldFeature.reset();
        System.out.println("[sneak-movement-test] " + checks + " checks passed");
    }

    private static void scenario(boolean standalone, int mode, int sneak, int direction) throws Exception {
        ModuleManager.disableAll();
        ScaffoldFeature.reset();
        ScaffoldFeature.load(new Properties());
        TellyFeature.load(new Properties());
        Keyboard.reset();
        if (standalone) {
            TellyFeature.setMode(mode - 1);
            TellyFeature.setSneak(sneak == 2);
            TellyFeature.setClutch(false);
        } else {
            ScaffoldFeature.setMode(mode);
            ScaffoldFeature.setSneak(sneak == 2);
            ScaffoldFeature.setEagle(false);
            ScaffoldFeature.setClutch(false);
        }
        Minecraft mc = new Minecraft();
        TellyBehaviorTest.Settings keys = new TellyBehaviorTest.Settings();
        TellyBehaviorTest.Player player = new TellyBehaviorTest.Player();
        player.posZ = 1.2;
        player.posY = 64.4;
        player.onGround = false;
        player.inventory.mainInventory[1] = new TellyBehaviorTest.Stack();
        player.movementInput = new MovementInputFromOptions(keys);
        int[][] directions = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        keys.keyBindForward.pressed = directions[direction][0] > 0;
        keys.keyBindBack.pressed = directions[direction][0] < 0;
        keys.keyBindLeft.pressed = directions[direction][1] > 0;
        keys.keyBindRight.pressed = directions[direction][1] < 0;
        keys.keyBindSneak.pressed = sneak == 1;
        Keyboard.setKeyDown(42, sneak == 1);
        mc.thePlayer = player;
        mc.theWorld = new TellyBehaviorTest.World(true);
        mc.playerController = new TellyBehaviorTest.Controller();
        mc.gameSettings = keys;
        ModuleManager.getModule(standalone ? "Telly" : "Scaffold").setEnabled(true);
        Field budget = field(OpenMaverFeatures.class, "placementsThisTick");
        Field armed = field(ScaffoldFeature.class, "hookArmed");
        Field failed = field(ScaffoldFeature.class, "failed");
        Field pending = field(ScaffoldFeature.class, "pending");
        Field placement = field(ScaffoldFeature.class, "placement");
        Field hold = field(ScaffoldFeature.class, "holdPlaceAim");
        int remapped = 0;
        // Keep planning geometry fixed; integrate horizontal momentum/displacement separately.
        double vx = .13, vz = -.04, x = 0, z = 0, predictedX = 0, predictedZ = 0;
        for (int frame = 1; frame <= 24; frame++) {
            budget.setInt(null, 0);
            ScaffoldFeature.tickCounter(++tick);
            ScaffoldFeature.onTickStart(mc);
            check(!failed.getBoolean(null), "planner remains active");
            if (armed.getBoolean(null) && (pending.get(null) != null || placement.get(null) != null || hold.getBoolean(null))) remapped++;
            player.movementInput.updatePlayerMoveState();
            MovementInput input = player.movementInput;
            boolean expectedSneak = sneak == 1 || (sneak == 2 && frame >= 18 && frame <= 20);
            check(input.sneak == expectedSneak, "manual/automatic sneak lifecycle");
            double[] actual = acceleration(input.moveStrafe, input.moveForward, player.rotationYaw);
            double[] expected = closestVanillaInput(actual, player.rotationYaw, expectedSneak);
            // Integrate the actual hook's acceleration, rather than overwriting horizontal speed.
            x += vx + actual[0];
            z += vz + actual[1];
            predictedX += vx + expected[0];
            predictedZ += vz + expected[1];
            check(Math.abs(x - predictedX) < 1e-8 && Math.abs(z - predictedZ) < 1e-8,
                    "hook-driven displacement matches a possible vanilla input trajectory");
            vx = (vx + actual[0]) * .91F;
            vz = (vz + actual[1]) * .91F;
            ScaffoldFeature.onTickEnd(mc);
        }
        check(remapped > 0, "scenario exercises silent MoveFix with a target");
    }

    private static double[] closestVanillaInput(double[] actual, float yaw, boolean sneak) {
        double best = Double.POSITIVE_INFINITY;
        double[] result = null;
        for (int f = -1; f <= 1; f++) for (int s = -1; s <= 1; s++) {
            float forward = sneak ? (float) (f * .3D) : f;
            float strafe = sneak ? (float) (s * .3D) : s;
            double[] candidate = acceleration(strafe, forward, yaw);
            double distance = Math.hypot(actual[0] - candidate[0], actual[1] - candidate[1]);
            if (distance < best) { best = distance; result = candidate; }
        }
        check(best < 1e-9, "acceleration belongs to vanilla's legal sneak/key combinations");
        return result;
    }

    /** EntityLivingBase's .98 input damping and Entity.moveFlying, airborne friction .02. */
    private static double[] acceleration(float strafe, float forward, float yaw) {
        strafe *= .98F;
        forward *= .98F;
        float length = strafe * strafe + forward * forward;
        if (length < 1e-4F) return new double[] {0, 0};
        length = (float) Math.sqrt(length);
        if (length < 1) length = 1;
        float scale = .02F / length;
        strafe *= scale;
        forward *= scale;
        float sin = (float) Math.sin(yaw * (float) Math.PI / 180F);
        float cos = (float) Math.cos(yaw * (float) Math.PI / 180F);
        return new double[] {strafe * cos - forward * sin, forward * cos + strafe * sin};
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
    }
}
