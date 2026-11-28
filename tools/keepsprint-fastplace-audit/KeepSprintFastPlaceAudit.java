import java.util.Properties;
import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;

/** Packaged SRG callbacks, vanilla look history, and ray/AABB placement regression. */
public final class KeepSprintFastPlaceAudit {
    private static int checks;
    private static final Client mc = new Client();
    private static Player player = new Player();
    private static final Settings settings = new Settings();
    private static Module sprint, fastPlace, scaffold;
    private static float nativeYaw, nativePitch, serverYaw, lastServerYaw, view;
    private static boolean hasLook;
    private static int accepted, blocked, cooldown;

    public static void main(String[] args) {
        Minecraft.instance = mc;
        mc.field_71439_g = player;
        mc.field_71474_y = settings;
        KeepSprintFeature.load(new Properties());
        sprint = new module.movement.KeepSprint();
        fastPlace = new module.player.FastPlace();
        scaffold = new module.movement.Scaffold();
        ModuleManager.register(sprint); ModuleManager.register(fastPlace); ModuleManager.register(scaffold);
        sprint.setEnabled(true); fastPlace.setEnabled(true);
        try {
            int[][] directions = {{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};
            for (int delay = 0; delay <= 5; delay++) {
                for (float yaw : new float[]{-1080.25F,-179.9F,-90,0,89.9F,179.9F,1080.25F}) {
                    for (int[] direction : directions) {
                        fresh(yaw, delay);
                        step(direction[0], direction[1], false, false, 60);
                        float previous = serverYaw;
                        int first = -1, last = -1;
                        for (int tick = 0; tick < 26; tick++) {
                            int before = accepted;
                            step(direction[0], direction[1], true, false, tick % 2 == 0 ? 60 : 60.25F);
                            check("handoff has no raw wrap discontinuity", Math.abs(serverYaw - previous) <= 20.01F);
                            previous = serverYaw;
                            if (accepted > before) {
                                if (first < 0) first = tick;
                                if (last >= 0) check("FastPlace held interval preserved", tick - last == Math.max(1, delay));
                                last = tick;
                                check("placement uses camera yaw", angle(serverYaw - view) < .01F);
                                check("camera-facing backwards input clears sprint", direction[0] >= 1 || !player.sprinting);
                            }
                        }
                        check("held click resumes within bounded turn", first >= 0 && first <= 9);
                        for (int tick = 0; tick < 16; tick++) {
                            step(direction[0], direction[1], false, false, 60);
                            check("return to movement has no raw wrap discontinuity", Math.abs(serverYaw - previous) <= 20.01F);
                            previous = serverYaw;
                        }
                        check("release restores movement-facing sprint", player.sprinting);
                        check("release restores intended movement yaw", angle(serverYaw - KeepSprintFeature.movementYaw(view, direction[0], direction[1])) < .01F);
                    }
                }
                System.out.println("[placement-regression] delay=" + delay + "t: 8 directions x 7 camera yaws, pre/post rays and smooth handoff passed");
            }
            // The old pitch-only history failure is now aligned while held.
            fresh(0, 0); step(-1,0,false,false,60);
            for (int i=0;i<14;i++) step(-1,0,true,false,60);
            step(-1,0,true,false,60.25F);
            check("pitch-only look packet emitted", hasLook);
            check("both Grim yaw candidates hit after pitch-only look", rayHitsSupport(serverYaw) && rayHitsSupport(lastServerYaw));

            fresh(179.9F, 2); step(-1,0,false,false,60);
            step(-1,0,false,true,60);
            check("unaligned tap is deferred without changing cooldown", accepted == 0 && cooldown == 0 && blocked == 1);
            for (int i=0;i<8;i++) step(-1,0,false,false,60);
            view += 5; // Retry must refresh the target after a real camera change.
            for (int i=0;i<22;i++) step(-1,0,false,false,60);
            check("released short tap replays exactly once", accepted == 1 && mc.field_71460_t.refreshes > 0);

            // The coordinator is required for ordinary manual placement too.
            fresh(0, 4); fastPlace.setEnabled(false); step(-1,0,false,false,60);
            for (int i=0;i<20;i++) step(-1,0,true,false,60);
            check("FastPlace OFF retains vanilla cooldown", accepted > 1 && cooldown <= 4);
            fastPlace.setEnabled(true);

            for (String reason : new String[]{"slot","stack","nonblock","world","player","gui","focus","dead","disabled","owner"}) {
                fresh(0, 1); step(-1,0,false,false,60); step(-1,0,false,true,60);
                check("tap initially blocked: " + reason, accepted == 0);
                if (reason.equals("slot")) player.inventory.field_70461_c++;
                if (reason.equals("stack")) player.stack = new Stack();
                if (reason.equals("nonblock")) player.stack.item = new Object();
                if (reason.equals("world")) mc.field_71441_e = new Object();
                if (reason.equals("player")) { player = new Player(); player.movementInput = new MovementInputFromOptions(settings); mc.field_71439_g = player; }
                if (reason.equals("gui")) mc.field_71462_r = new Object();
                if (reason.equals("focus")) mc.field_71415_G = false;
                if (reason.equals("dead")) player.living = false;
                if (reason.equals("disabled")) sprint.setEnabled(false);
                if (reason.equals("owner")) scaffold.setEnabled(true);
                for (int i=0;i<16;i++) step(-1,0,false,false,60);
                check("deferred tap canceled on " + reason, accepted == 0);
                mc.field_71462_r = null; mc.field_71415_G = true; player.living = true;
                sprint.setEnabled(true); scaffold.setEnabled(false);
            }
            // Queued vanilla presses own dispatch; START must not replay a second click.
            fresh(0, 0); step(-1,0,false,false,60); step(-1,0,false,true,60);
            settings.field_74313_G.field_151474_i = 1;
            for (int i=0;i<12;i++) step(-1,0,false,false,60);
            check("queued key press prevents replay duplication", accepted == 0);
            mc.field_71460_t.func_78473_a(1); mc.func_147121_ag();
            settings.field_74313_G.field_151474_i = 0;
            for (int i=0;i<5;i++) step(-1,0,false,false,60);
            check("queued vanilla click dispatches once", accepted == 1);
            // Releasing a rotation owner starts from the last real native report.
            fresh(0, 1); player.field_175164_bL = nativeYaw = serverYaw = lastServerYaw = -180;
            for (int i=0;i<12;i++) step(-1,0,true,false,60);
            check("first click after ownership change waits for alignment", blocked >= 9 && accepted > 0);
            fresh(0, 1); step(-1,0,false,false,60);
            for (int i=0;i<12;i++) step(-1,0,true,false,60);
            float previous = serverYaw;
            for (int i=0;i<24;i++) {
                // Stop in the middle of the return, then let vanilla own idle yaw.
                step(i < 5 ? -1 : 0, 0, false, false, 60);
                check("stopping mid-return keeps raw native yaw continuous", Math.abs(serverYaw-previous) <= 20.01F);
                previous = serverYaw;
            }
            check("idle ends at the exact camera yaw", serverYaw == view);
            fresh(0, 1); step(-1,0,false,false,60);
            step(-1,0,true,false,60,4);
            previous = serverYaw;
            step(-1,0,true,false,60);
            check("late camera delta is counted once in the next turn", Math.abs(serverYaw-previous-20) < .01F);
            fresh(0, 1); step(-1,0,false,false,60);
            settings.field_74311_E.pressed = true;
            for (int i=0;i<14;i++) step(-1,0,true,false,60);
            check("sneak placement keeps vanilla scaling and no sprint", accepted > 0 && !player.sprinting
                    && Math.abs(player.movementInput.field_78900_b + .3F) < .001F);
            System.out.println("[placement-regression] " + checks + " checks passed (packaged SRG callbacks + ray/AABB model)");
        } finally {
            KeepSprintFeature.onTickEnd(mc); KeepSprintFeature.reset();
            fastPlace.setEnabled(false); sprint.setEnabled(false); scaffold.setEnabled(false);
        }
    }

    private static void fresh(float yaw, int delay) {
        KeepSprintFeature.reset();
        mc.field_71462_r = null; mc.field_71415_G = true;
        player.stack = new Stack(); player.inventory.field_70461_c = 0;
        player.movementInput = new MovementInputFromOptions(settings);
        settings.field_74313_G.pressed = false; settings.field_74313_G.field_151474_i = 0;
        settings.field_74311_E.pressed = false;
        player.rotationYaw = view = nativeYaw = serverYaw = lastServerYaw = yaw;
        player.field_175164_bL = yaw;
        player.rotationPitch = nativePitch = 60;
        accepted = blocked = cooldown = 0;
        OpenMaverFeatures.setFastPlaceDelayTicks(delay);
    }

    /** Verified native order: client START, mouse-over/click, input, physics, native C03, client END. */
    private static void step(int forward, int strafe, boolean held, boolean click, float pitch) {
        step(forward, strafe, held, click, pitch, 0);
    }

    private static void step(int forward, int strafe, boolean held, boolean click, float pitch, float lateDelta) {
        player.rotationYaw = view; player.rotationPitch = pitch;
        settings.set(forward, strafe); settings.field_74313_G.pressed = held;
        if (cooldown > 0) cooldown--;
        mc.field_71467_ac = cooldown;
        int before = accepted;
        FastPlaceFeature.onTickStart(mc);
        mc.field_71460_t.func_78473_a(1);
        if (click || held && cooldown == 0) mc.func_147121_ag();
        KeepSprintFeature.onPlayerTickStart(mc, player);
        player.movementInput.func_78898_a();
        MovementInput input = player.movementInput;
        float f = input.field_78900_b, s = input.field_78902_a;
        float scale = input.field_78899_d ? .3F : 1;
        check("discrete vanilla movement input", Math.abs(f/scale-Math.round(f/scale)) < .001F
                && Math.abs(s/scale-Math.round(s/scale)) < .001F && Math.abs(f) <= scale && Math.abs(s) <= scale);
        if (forward == 0 && strafe == 0) check("idle input stays idle", f == 0 && s == 0);
        else if (sprint.isEnabled() && !scaffold.isEnabled() && mc.field_71462_r == null && player.living && mc.field_71415_G) {
            float desired = KeepSprintFeature.movementYaw(view, forward, strafe);
            float actual = KeepSprintFeature.movementYaw(player.rotationYaw, f, s);
            check("turn preserves nearest discrete world direction", angle(actual - desired) <= 22.51F);
        }
        player.rotationYaw += lateDelta; view += lateDelta;
        KeepSprintFeature.onPlayerTickEnd(player);
        float reported = player.rotationYaw;
        hasLook = reported != nativeYaw || pitch != nativePitch;
        if (hasLook) { lastServerYaw = serverYaw; serverYaw = nativeYaw = reported; nativePitch = pitch; }
        player.field_175164_bL = nativeYaw;
        check("pitch preserved at native boundary", player.rotationPitch == pitch);
        if (accepted > before) check("post-flying placement ray hits", rayHitsSupport(serverYaw));
        KeepSprintFeature.onTickEnd(mc);
        check("camera restored after movement", Math.abs(player.rotationYaw - view) < .01F);
    }

    private static float angle(float value) { return Math.abs(OpenMaverFeatures.wrapDegrees(value)); }

    /** Support AABB in a frame rotated with camera yaw; eye=(.5,2.62,.5), reach=4.5. */
    private static boolean rayHitsSupport(float yaw) {
        double y = Math.toRadians(yaw - view), p = Math.toRadians(player.rotationPitch);
        double[] origin = {.5, 2.62, .5};
        double[] direction = {-Math.sin(y)*Math.cos(p), -Math.sin(p), Math.cos(y)*Math.cos(p)};
        double[] min = {0,0,1}, max = {1,1,2};
        double enter = 0, leave = 4.5;
        for (int axis=0;axis<3;axis++) {
            if (Math.abs(direction[axis]) < 1E-12) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) return false;
            } else {
                double a=(min[axis]-origin[axis])/direction[axis], b=(max[axis]-origin[axis])/direction[axis];
                enter=Math.max(enter,Math.min(a,b)); leave=Math.min(leave,Math.max(a,b));
                if (leave < enter) return false;
            }
        }
        return true;
    }

    private static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); checks++; }

    public static final class Client extends Minecraft {
        public Object field_71441_e = new Object();
        public boolean field_71415_G = true;
        public final Renderer field_71460_t = new Renderer();
        public int field_71467_ac; // Retry reads vanilla's timer, mirrored below.
        public void func_147121_ag() {
            field_71467_ac = cooldown;
            if (!KeepSprintPlacement.allowRightClick(this)) { blocked++; return; }
            check("pre-flying ray hits current or previous look", rayHitsSupport(serverYaw) || rayHitsSupport(lastServerYaw));
            check("replayed mouse-over refreshed before dispatch", angle(field_71460_t.pickedYaw - player.rotationYaw) < .01F);
            cooldown = FastPlaceFeature.useCooldown(4, this); field_71467_ac = cooldown;
            accepted++;
        }
    }
    public static final class Renderer {
        public int refreshes;
        public float pickedYaw;
        public void func_78473_a(float partial) { refreshes++; pickedYaw = player.rotationYaw; }
    }
    public static final class Key {
        public boolean pressed;
        public int field_151474_i;
        public boolean func_151470_d() { return pressed; }
    }
    public static final class Settings extends GameSettings {
        public final Key field_74351_w=new Key(), field_74368_y=new Key(), field_74370_x=new Key(),
                field_74366_z=new Key(), field_74311_E=new Key(), field_74314_A=new Key(), field_74313_G=new Key();
        public void set(int f,int s) {
            field_74351_w.pressed=f>0; field_74368_y.pressed=f<0;
            field_74370_x.pressed=s>0; field_74366_z.pressed=s<0;
        }
    }
    public static final class Inventory { public int field_70461_c; }
    public static final class Stack extends ItemStack {
        public int stackSize=64;
        public Object item=new net.minecraft.item.ItemBlock();
        public Object getItem() { return item; }
    }
    public static final class Player extends EntityPlayerSP {
        public MovementInput movementInput;
        public float field_175164_bL;
        public Stack stack = new Stack();
        public final Inventory inventory = new Inventory();
        public final KeepSprintRuntimeTest.FakeAttribute attribute=new KeepSprintRuntimeTest.FakeAttribute();
        public final KeepSprintRuntimeTest.Caps capabilities=new KeepSprintRuntimeTest.Caps();
        public boolean sprinting, living=true;
        public boolean isUsingItem() { return false; }
        public boolean isRiding() { return false; }
        public boolean isSprinting() { return sprinting; }
        public boolean isEntityAlive() { return living; }
        public void setSprinting(boolean value) { sprinting=value; }
        public Object getFoodStats() { return new KeepSprintRuntimeTest.FoodStats(); }
        public Object getEntityAttribute(Object ignored) { return attribute; }
        @Override public ItemStack getHeldItem() { return stack; }
    }
}
