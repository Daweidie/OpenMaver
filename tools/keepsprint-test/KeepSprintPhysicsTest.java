import module.ModuleManager;

import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

/** Samples the attribute used by movement after vanilla's sprint cancellation. */
public final class KeepSprintPhysicsTest {
    private static int checks;

    public static void main(String[] args) {
        KeepSprintFeature.reset();
        java.util.Properties legacy = new java.util.Properties();
        legacy.setProperty("keepSprint.rotate", "false");
        KeepSprintFeature.load(legacy);
        module.movement.KeepSprint module = new module.movement.KeepSprint();
        ModuleManager.register(module);
        module.setEnabled(true);

        Minecraft mc = new Minecraft();
        Minecraft.instance = mc;
        Settings settings = new Settings();
        mc.field_71474_y = settings;
        KeepSprintRuntimeTest.FakePlayer player = new KeepSprintRuntimeTest.FakePlayer();
        mc.field_71439_g = player;
        player.setSprinting(true);

        // MovementInputFromOptions leaves diagonal components at +/-1. Normalization
        // happens later in moveFlying, after the sprint flag has already been checked.
        int[][] directions = {{1, 1}, {1, -1}, {1, 0}, {0, 1}, {0, -1}, {-1, 0}, {-1, 1}, {-1, -1}};
        for (int[] from : directions) {
            for (int[] to : directions) {
                tick(mc, player, settings, from[0], from[1], 0.13D);
                tick(mc, player, settings, to[0], to[1], 0.13D);
            }
        }

        tick(mc, player, settings, 0, 0, 0.1D);
        check("idle removes the custom modifier", player.attribute.modifier == null);

        settings.set(1, 1);
        settings.field_74311_E.field_74513_e = true;
        sample(mc, player, settings, 0.1D);
        check("sneak pause removes the custom modifier", player.attribute.modifier == null);
        settings.field_74311_E.field_74513_e = false;

        player.usingItem = true;
        sample(mc, player, settings, 0.1D);
        check("item pause removes the custom modifier", player.attribute.modifier == null);
        player.usingItem = false;

        check("normal movement creates no packets", player.sendQueue.packets.isEmpty());
        module.setEnabled(false);
        KeepSprintFeature.onPlayerTickStart(mc, player);
        Keyboard.reset();
        System.out.println("KeepSprint physics: " + checks + " checks passed");
    }

    private static void tick(Minecraft mc, KeepSprintRuntimeTest.FakePlayer player,
            Settings settings, int forward, int strafe, double expected) {
        settings.set(forward, strafe);
        Keyboard.reset();
        Keyboard.setKeyDown(17, forward > 0);
        Keyboard.setKeyDown(31, forward < 0);
        Keyboard.setKeyDown(30, strafe > 0);
        Keyboard.setKeyDown(32, strafe < 0);
        sample(mc, player, settings, expected);
    }

    private static void sample(Minecraft mc, KeepSprintRuntimeTest.FakePlayer player,
            Settings settings, double expected) {
        KeepSprintFeature.onPlayerTickStart(mc, player);
        float forward = settings.forward();
        float strafe = settings.strafe();
        boolean sneak = settings.field_74311_E.field_74513_e;
        if (sneak) { forward *= 0.3F; strafe *= 0.3F; }
        if (player.usingItem) { forward *= 0.2F; strafe *= 0.2F; }
        // Vanilla checks the raw forward component before movement integration.
        if (player.isSprinting() && forward < 0.8F) player.setSprinting(false);
        double actual = player.attribute.value();
        check("physics speed for input " + forward + "," + strafe
                + " is " + expected + " (actual " + actual + ")", Math.abs(actual - expected) < 1.0E-7D);
        player.movementInput.moveForward = forward * 0.98F;
        player.movementInput.moveStrafe = strafe * 0.98F;
        player.movementInput.sneak = sneak;
        player.jumpMovementFactor = player.isSprinting() ? 0.026F : 0.02F;
        KeepSprintFeature.onPlayerTickEnd(player);
    }

    private static void check(String name, boolean result) {
        if (!result) throw new AssertionError(name);
        checks++;
    }

    public static final class Settings {
        public final Binding field_74351_w = new Binding(17);
        public final Binding field_74368_y = new Binding(31);
        public final Binding field_74370_x = new Binding(30);
        public final Binding field_74366_z = new Binding(32);
        public final Binding field_74311_E = new Binding(42);

        void set(int forward, int strafe) {
            field_74351_w.field_74513_e = forward > 0;
            field_74368_y.field_74513_e = forward < 0;
            field_74370_x.field_74513_e = strafe > 0;
            field_74366_z.field_74513_e = strafe < 0;
        }

        float forward() { return (field_74351_w.field_74513_e ? 1 : 0) - (field_74368_y.field_74513_e ? 1 : 0); }
        float strafe() { return (field_74370_x.field_74513_e ? 1 : 0) - (field_74366_z.field_74513_e ? 1 : 0); }
    }

    public static final class Binding {
        public boolean field_74513_e;
        private final int code;

        Binding(int code) { this.code = code; }
        public int func_151463_i() { return code; }
        public boolean func_151470_d() { return field_74513_e; }
    }
}
