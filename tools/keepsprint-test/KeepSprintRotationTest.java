import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;
import java.util.Properties;

/** Executes the packaged SRG input hook at the physics / C03 / render boundaries. */
public final class KeepSprintRotationTest {
    private static int checks;
    private static final Minecraft mc = new Minecraft();
    private static final Settings settings = new Settings();
    private static final Player player = new Player();
    private static module.movement.KeepSprint keepSprint;

    public static void main(String[] args) {
        Minecraft.instance = mc;
        mc.field_71439_g = player;
        mc.field_71474_y = settings;
        player.movementInput = new MovementInputFromOptions(settings);
        KeepSprintFeature.load(new Properties());
        keepSprint = new module.movement.KeepSprint();
        ModuleManager.register(keepSprint);
        keepSprint.setEnabled(true);
        check("existing configurations default to rotation", KeepSprintFeature.isRotate());

        int[][] directions = {{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};
        float[] views = {-1080.25F, -179.9F, -90, 0, 89.9F, 179.9F, 1080.25F};
        for (float view : views) {
            for (int[] from : directions) for (int[] to : directions) {
                movementTick(view, from[0], from[1]);
                movementTick(view, to[0], to[1]);
            }
        }
        check("vanilla input is replaced with the shared hook", player.movementInput instanceof ScaffoldMovementInput);
        // Repeated backward input must not alternate camera and movement yaw.
        for (int i = 0; i < 10; i++) movementTick(37.5F, -1, 0);

        settings.set(-1, 0);
        settings.field_74314_A.pressed = true;
        begin();
        check("jump key survives the transform", player.movementInput.field_78901_c);
        double jumpYaw = Math.toRadians(player.rotationYaw);
        near("sprint jump impulse faces reported movement yaw", -Math.sin(jumpYaw), Math.sin(Math.toRadians(37.5)));
        finish();
        settings.field_74314_A.pressed = false;

        settings.set(0, 0);
        untouched("idle");
        settings.set(-1, 0);
        settings.field_74311_E.pressed = true;
        untouched("sneak"); settings.field_74311_E.pressed = false;
        player.usingItem = true; untouched("item"); player.usingItem = false;
        player.blind = true; untouched("blindness"); player.blind = false;
        player.isCollidedHorizontally = true; untouched("collision"); player.isCollidedHorizontally = false;
        player.food.level = 6; untouched("low food"); player.food.level = 20;
        player.capabilities.isFlying = true; untouched("flying"); player.capabilities.isFlying = false;
        player.riding = true; untouched("riding"); player.riding = false;
        player.alive = false; untouched("dead"); player.alive = true;
        mc.field_71462_r = new Object(); untouched("GUI"); mc.field_71462_r = null;

        normalScaffoldPause();
        normalScaffoldSprintInput();

        for (String name : new String[]{"Scaffold2", "Telly", "GodBridge", "KillAura"}) {
            Module owner = new Module(name) { };
            ModuleManager.register(owner); owner.setEnabled(true);
            untouched("rotation owner " + name);
            check("owner is visible in status", "YIELD".equals(KeepSprintFeature.getStatus()));
            owner.setEnabled(false);
        }

        Module velocity = new Module("Velocity") { };
        ModuleManager.register(velocity); velocity.setEnabled(true);
        movementTick(38, -1, 0);
        check("idle Velocity does not block KeepSprint", "SILENT".equals(KeepSprintFeature.getStatus()));
        velocity.setEnabled(false);

        player.movementInput = new MovementInput();
        untouched("custom input");
        check("custom input is retained", player.movementInput.getClass() == MovementInput.class);
        check("custom input does not silently enable OMNI", "NO HOOK".equals(KeepSprintFeature.getStatus()));
        player.movementInput = new MovementInputFromOptions(settings);

        // Relative camera input after the hook must survive camera restoration.
        player.rotationYaw = 10;
        begin();
        player.rotationYaw += 4;
        KeepSprintFeature.onPlayerTickEnd(player);
        finish();
        near("late yaw delta is preserved", player.rotationYaw, 14);

        // Disabling between input and END must not erase yaw before native C03.
        player.rotationYaw = 20;
        begin();
        float nativeYaw = player.rotationYaw;
        keepSprint.setEnabled(false);
        KeepSprintFeature.onPlayerTickEnd(player);
        near("disable retains physics yaw until C03", player.rotationYaw, nativeYaw);
        finish();
        near("disable restores camera after C03", player.rotationYaw, 20);
        check("disable clears status", "OFF".equals(KeepSprintFeature.getStatus()));
        keepSprint.setEnabled(true);

        player.rotationYaw = 25;
        begin();
        Player replacement = new Player();
        replacement.rotationYaw = 111;
        replacement.movementInput = new MovementInputFromOptions(settings);
        mc.field_71439_g = replacement;
        KeepSprintFeature.onPlayerTickStart(mc, replacement);
        near("world change restores the old player camera", player.rotationYaw, 25);
        near("world change leaves new player yaw intact", replacement.rotationYaw, 111);
        mc.field_71439_g = player;
        KeepSprintFeature.reset();

        player.rotationYaw = 30;
        begin();
        KeepSprintFeature.reset();
        near("reset restores camera", player.rotationYaw, 30);
        KeepSprintFeature.afterMovementInput(player.movementInput);
        near("reset disarms input callback", player.rotationYaw, 30);

        // Attack reset queues only the existing C0B, before the native movement boundary.
        KeepSprintFeature.onAttack(player);
        begin();
        KeepSprintFeature.onPlayerTickEnd(player);
        check("attack queues a single sprint restart", player.sendQueue.packets.size() == 1);
        KeepSprintFeature.onPlayerTickEnd(player);
        check("attack marker is consumed once", player.sendQueue.packets.size() == 1);
        finish();
        player.sendQueue.packets.clear();
        KeepSprintFeature.setAttackResend(false);
        KeepSprintFeature.onAttack(player);
        begin();
        KeepSprintFeature.onPlayerTickEnd(player);
        check("hit resend setting is honored in ROTATE", player.sendQueue.packets.isEmpty());
        finish();
        KeepSprintFeature.setAttackResend(true);
        KeepSprintFeature.setSpeedBoost(false);
        movementTick(0, -1, -1);
        KeepSprintFeature.setSpeedBoost(true);
        keepSprint.setEnabled(false);
        KeepSprintFeature.reset();
        System.out.println("KeepSprint rotation SRG: " + checks + " checks passed");
    }

    private static void movementTick(float view, int forward, int strafe) {
        player.rotationYaw = view;
        player.prevRotationYaw = view - 1.25F;
        player.rotationPitch = 17.5F;
        player.setSprinting(false); // first-tick start is tested on every direction
        player.attribute.modifier = new AttributeModifier(KeepSprintFeature.BOOST_UUID, "stale OMNI", 0.3, 2);
        settings.set(forward, strafe);
        begin();
        MovementInput input = player.movementInput;
        check("input is vanilla forward", input.field_78900_b == 1 && input.field_78902_a == 0);
        check("sprint begins before movement", player.sprinting);
        near("one vanilla sprint speed modifier", player.attribute.value(), 0.13);
        check("legacy modifier is removed before physics", player.attribute.modifier == null);
        double angle = Math.toRadians(view);
        double length = Math.hypot(forward, strafe);
        double x = (strafe * Math.cos(angle) - forward * Math.sin(angle)) / length;
        double z = (forward * Math.cos(angle) + strafe * Math.sin(angle)) / length;
        double yaw = Math.toRadians(player.rotationYaw);
        near("world X direction is preserved", -Math.sin(yaw), x);
        near("world Z direction is preserved", Math.cos(yaw), z);
        // Vanilla applies its own input damping and air factor after the hook.
        input.field_78900_b *= 0.98F;
        player.jumpMovementFactor = 0.026F;
        KeepSprintFeature.onPlayerTickEnd(player);
        near("END leaves vanilla air factor intact", player.jumpMovementFactor, 0.026);
        near("native movement yaw matches physics yaw", player.rotationYaw, Math.toDegrees(yaw));
        near("pitch remains the camera pitch", player.rotationPitch, 17.5);
        check("silent status reaches the summary", KeepSprintFeature.getSummary().contains("SILENT"));
        check("movement creates no extra packets", player.sendQueue.packets.isEmpty());
        finish();
        near("camera yaw is restored after native C03", player.rotationYaw, view);
        near("previous camera yaw is restored", player.prevRotationYaw, view - 1.25);
        near("arm yaw is restored", player.renderArmYaw, view);
    }

    private static void normalScaffoldPause() {
        Module scaffold = new module.movement.Scaffold();
        ModuleManager.register(scaffold);
        ScaffoldFeature.setMode(ScaffoldFeature.MODE_NORMAL);
        movementTick(38, 1, 0);
        check("KeepSprint is sprinting before Normal is enabled", player.sprinting);
        scaffold.setEnabled(true);
        player.attribute.modifier = new AttributeModifier(KeepSprintFeature.BOOST_UUID, "stale OMNI", 0.3, 2);
        begin();
        check("Normal releases the previous KeepSprint sprint", !player.sprinting);
        check("Normal removes the old speed modifier", player.attribute.modifier == null);
        near("Normal leaves camera yaw intact", player.rotationYaw, 38);
        KeepSprintFeature.onAttack(player);
        KeepSprintFeature.onPlayerTickEnd(player);
        check("Normal suspends KeepSprint without changing its switch", keepSprint.isEnabled()
                && "YIELD SCAFFOLD".equals(KeepSprintFeature.getSummary()));
        check("attacking during Normal does not resend sprint", player.sendQueue.packets.isEmpty());
        finish();

        settings.set(-1, 1);
        begin();
        check("Normal preserves the original movement input", player.movementInput.field_78900_b == -1
                && player.movementInput.field_78902_a == 1);
        KeepSprintFeature.onPlayerTickEnd(player);
        check("Normal does not force sprint on subsequent ticks", !player.sprinting);
        finish();

        player.setSprinting(true);
        begin();
        KeepSprintFeature.onPlayerTickEnd(player);
        check("paused KeepSprint leaves later manual sprint alone", player.sprinting);
        finish();
        scaffold.setEnabled(false);
        movementTick(38, -1, 0);
        check("closing Scaffold resumes KeepSprint", player.sprinting);

        // A mode switch can happen after START has armed the input callback.
        ScaffoldFeature.setMode(ScaffoldFeature.MODE_TELLY_BRIDGE);
        scaffold.setEnabled(true);
        begin();
        KeepSprintFeature.onPlayerTickEnd(player);
        check("Simple retains the existing rotation yield", "YIELD".equals(KeepSprintFeature.getStatus()));
        finish();
        ScaffoldFeature.setMode(ScaffoldFeature.MODE_NORMAL);
        begin();
        KeepSprintFeature.onPlayerTickEnd(player);
        check("switching Simple to Normal releases the carried sprint", !player.sprinting);
        finish();
        scaffold.setEnabled(false);

        player.rotationYaw = 38;
        begin();
        float nativeYaw = player.rotationYaw;
        KeepSprintFeature.onAttack(player);
        scaffold.setEnabled(true);
        KeepSprintFeature.onPlayerTickEnd(player);
        check("mid-tick Normal cancels the pending sprint resend", player.sendQueue.packets.isEmpty());
        check("mid-tick Normal releases automatic sprint", !player.sprinting);
        near("mid-tick pause retains physics yaw until C03", player.rotationYaw, nativeYaw);
        finish();
        near("mid-tick pause restores camera after C03", player.rotationYaw, 38);
        scaffold.setEnabled(false);
        movementTick(38, -1, 0);
        check("resume does not replay paused attacks", player.sendQueue.packets.isEmpty());

        // Normal can also be enabled between START and movement-input processing.
        player.rotationYaw = 38;
        KeepSprintFeature.onPlayerTickStart(mc, player);
        scaffold.setEnabled(true);
        player.movementInput.func_78898_a();
        check("input hook catches Normal enabled after START", !player.sprinting);
        near("input-hook pause does not rotate the player", player.rotationYaw, 38);
        KeepSprintFeature.onPlayerTickEnd(player);
        finish();

        keepSprint.setEnabled(false);
        scaffold.setEnabled(false);
        untouched("disabled during Normal pause");
        check("closing Scaffold respects a manual KeepSprint disable", !keepSprint.isEnabled());
        keepSprint.setEnabled(true);
        movementTick(38, -1, 0);
    }

    private static void untouched(String reason) {
        player.setSprinting(false);
        player.rotationYaw = 38;
        begin();
        KeepSprintFeature.onPlayerTickEnd(player);
        near(reason + " leaves yaw alone", player.rotationYaw, 38);
        check(reason + " does not force sprint", !player.sprinting);
        check(reason + " has no custom modifier", player.attribute.modifier == null);
        finish();
    }

    /** Exercise Scaffold's own remapping, then vanilla's sprint checks, with KeepSprint off/on. */
    private static void normalScaffoldSprintInput() {
        Module scaffold = ModuleManager.getModule("Scaffold");
        int[][] directions = {{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};
        for (int mode : new int[]{ScaffoldFeature.MODE_NORMAL, ScaffoldFeature.MODE_TELLY_BRIDGE}) {
            for (boolean enabled : new boolean[]{false, true}) {
                for (int[] direction : directions) for (boolean held : new boolean[]{false, true}) {
                    for (boolean sprinting : new boolean[]{false, true}) {
                        ScaffoldFeature.setMode(mode);
                        scaffold.setEnabled(true);
                        keepSprint.setEnabled(enabled);
                        player.rotationYaw = 37;
                        player.setSprinting(sprinting);
                        player.field_71156_d = 7; // Pending double-tap must not bypass the guard.
                        settings.set(direction[0], direction[1]);
                        settings.field_74321_H.field_74513_e = held;
                        org.lwjgl.input.Keyboard.setKeyDown(29, held);
                        KeepSprintFeature.onPlayerTickStart(mc, player);
                        // Fix the planner's aim to face this movement; every key becomes forward=1.
                        float aim = 37 - (float) Math.toDegrees(Math.atan2(direction[1], direction[0]));
                        seedScaffold("active", true);
                        seedScaffold("aimPlayer", player);
                        seedScaffold("cameraYaw", 37F);
                        seedScaffold("cameraPitch", player.rotationPitch);
                        seedScaffold("holdPlaceAim", true);
                        seedScaffold("holdPlaceYaw", aim);
                        seedScaffold("tickAimYaw", aim);
                        seedScaffold("tickAimPitch", 60F);
                        seedScaffold("hookArmed", true);
                        seedScaffold("hookArmedNanos", System.nanoTime());
                        player.movementInput.func_78898_a();
                        near("Scaffold remaps the actual hook input to forward", player.movementInput.field_78900_b, 1);
                        boolean allowed = mode != ScaffoldFeature.MODE_NORMAL || direction[0] > 0;
                        if (!allowed) {
                            check("Normal clears stale double-tap intent", player.field_71156_d == 0);
                            check("Normal blocks sprint key until vanilla checks finish", !settings.field_74321_H.field_74513_e);
                        }
                        // Vanilla onLivingUpdate: old input was below .8 before the key update.
                        if (!player.sprinting && player.movementInput.field_78900_b >= .8F) {
                            if (player.field_71156_d > 0 || settings.field_74321_H.field_74513_e)
                                player.setSprinting(true);
                            else player.field_71156_d = 7;
                        }
                        KeepSprintFeature.onPlayerTickEnd(player);
                        check("Scaffold respects original sprint eligibility with KeepSprint " + enabled,
                                player.sprinting == allowed);
                        near("Normal walking has no sprint speed modifier", player.attribute.value(), allowed ? .13 : .1);
                        check("input guard respects the KeepSprint switch", keepSprint.isEnabled() == enabled);
                        finish();
                        ScaffoldFeature.onTickEnd(mc);
                        check("END restores the physically held sprint key", settings.field_74321_H.field_74513_e == held);
                    }
                }
            }
        }
        // A missing END must also release the temporarily suppressed key on reset/disable.
        ScaffoldFeature.setMode(ScaffoldFeature.MODE_NORMAL);
        seedScaffold("active", true);
        seedScaffold("aimPlayer", player);
        seedScaffold("hookArmed", true);
        seedScaffold("hookArmedNanos", System.nanoTime());
        settings.set(-1, 0);
        settings.field_74321_H.field_74513_e = true;
        org.lwjgl.input.Keyboard.setKeyDown(29, true);
        player.movementInput.func_78898_a();
        check("reset case suppresses sprint before vanilla", !settings.field_74321_H.field_74513_e);
        ScaffoldFeature.reset();
        check("reset restores physically held sprint", settings.field_74321_H.field_74513_e);
        scaffold.setEnabled(false);
        keepSprint.setEnabled(true);
        settings.field_74321_H.field_74513_e = false;
        org.lwjgl.input.Keyboard.reset();
        movementTick(38, -1, 0);
    }

    private static void seedScaffold(String name, Object value) {
        try {
            java.lang.reflect.Field field = ScaffoldFeature.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void begin() {
        KeepSprintFeature.onPlayerTickStart(mc, player);
        player.movementInput.func_78898_a();
    }
    private static void finish() { KeepSprintFeature.onTickEnd(mc); }
    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }
    private static void near(String name, double value, double expected) {
        check(name + ": " + value + " expected " + expected, Math.abs(value - expected) < 2E-5);
    }

    public static final class Player extends EntityPlayerSP {
        public MovementInput movementInput;
        public final KeepSprintRuntimeTest.FakeQueue sendQueue = new KeepSprintRuntimeTest.FakeQueue();
        public final KeepSprintRuntimeTest.FakeAttribute attribute = new KeepSprintRuntimeTest.FakeAttribute();
        public final KeepSprintRuntimeTest.Caps capabilities = new KeepSprintRuntimeTest.Caps();
        public final Food food = new Food();
        public float prevRotationYaw, renderArmYaw, prevRenderArmYaw, renderYawOffset, rotationYawHead;
        public float jumpMovementFactor = 0.02F;
        public int field_71156_d;
        public boolean sprinting, isCollidedHorizontally, usingItem, riding, blind, alive = true;
        public boolean isEntityAlive() { return alive; }
        public boolean isRiding() { return riding; }
        public boolean isUsingItem() { return usingItem; }
        public boolean isSprinting() { return sprinting; }
        public boolean isPotionActive(int potion) { return potion == 15 && blind; }
        public Object getFoodStats() { return food; }
        public Object getEntityAttribute(Object ignored) { return attribute; }
        public void setSprinting(boolean value) {
            sprinting = value;
            attribute.vanillaModifier = value ? new AttributeModifier(KeepSprintRuntimeTest.VANILLA_SPRINT_UUID,
                    "Sprinting speed boost", 0.30000001192092896D, 2) : null;
        }
    }
    public static final class Food {
        public int level = 20;
        public int getFoodLevel() { return level; }
    }
    public static final class Key {
        public boolean pressed;
        public boolean func_151470_d() { return pressed; }
    }
    public static final class Settings extends GameSettings {
        public final Key field_74351_w = new Key(), field_74368_y = new Key(), field_74370_x = new Key(),
                field_74366_z = new Key(), field_74311_E = new Key(), field_74314_A = new Key();
        public final SprintKey field_74321_H = new SprintKey();
        void set(int forward, int strafe) {
            field_74351_w.pressed = forward > 0; field_74368_y.pressed = forward < 0;
            field_74370_x.pressed = strafe > 0; field_74366_z.pressed = strafe < 0;
        }
    }
    public static final class SprintKey {
        public boolean field_74513_e;
        public int func_151463_i() { return 29; }
    }
}
