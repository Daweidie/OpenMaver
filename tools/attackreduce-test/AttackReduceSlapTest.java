import io.netty.channel.embedded.EmbeddedChannel;
import module.Module;
import module.ModuleManager;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Properties;
import java.util.function.Consumer;

/** Myau 的多 tick 减速、Zen 的目标复用，以及真实 Netty 异步 SEND 闸门。 */
public final class AttackReduceSlapTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        ModuleManager.register(AttackReduceSprintHitTest.MODULE);
        testSettingsAndWindows();
        try (AttackReduceSprintHitTest.Fixture f = new AttackReduceSprintHitTest.Fixture()) {
            reset(f);
            S12PacketEntityVelocity packet = f.receive(7, .4, .36, -.2);
            check("Slap never rewrites S12", packet.getMotionX() == 3200 && packet.getMotionY() == 2880 && packet.getMotionZ() == -1600);
            f.tick();
            check("no attack before vanilla applies S12", f.channel.readOutbound() == null);
            f.drain(); f.tick();
            checkPair(f.channel);
            check("raw packet path avoids native controller double slowdown", f.controller.attacks == 0 && near(f.player.motionX, .24) && near(f.player.motionZ, -.12));
            check("Slap preserves vertical and ground/sprint-sync state", near(f.player.motionY, .36) && f.player.field_175171_bO && !f.player.sprinting);
            f.player.sprinting = true; f.tick();
            check("no second action before C03", f.channel.readOutbound() == null);
            move(f); f.tick(); checkPair(f.channel);
            check("window supports repeated attacks on later movement boundaries", near(f.player.motionX, .144) && near(f.player.motionZ, -.072));

            blocked(f, "not moving", x -> x.player.movementInput.moveForward = 0);
            blocked(f, "not sprinting", x -> x.player.sprinting = false);
            blocked(f, "sprint not sent yet", x -> x.player.field_175171_bO = false);
            blocked(f, "web", x -> x.player.isInWeb = true);
            blocked(f, "water", x -> x.player.water = true);
            blocked(f, "lava", x -> x.player.lava = true);
            blocked(f, "using item", x -> x.player.usingItem = true);
            blocked(f, "GUI", x -> x.mc.currentScreen = new Object());
            blocked(f, "manual click", x -> Mouse.left = true);
            blocked(f, "dead target", x -> x.target.alive = false);
            blocked(f, "target behind wall", x -> x.player.visible = false);
            blocked(f, "stale crosshair", x -> x.player.rotationYaw = 90);
            blocked(f, "no connection", x -> x.player.sendQueue = null);
            // Fixture 的 reset 使用原 queue；该用例后恢复。
        }
        try (AttackReduceSprintHitTest.Fixture f = new AttackReduceSprintHitTest.Fixture()) {
            testActions(f);
            testAura(f);
            testAsyncReservation(f);
            testLifecycle(f);
            testStaleHook(f);
        }
        System.out.println("AttackReduceSlapTest: " + passed + " passed, 0 failed");
    }

    private static void testActions(AttackReduceSprintHitTest.Fixture f) throws Exception {
        Object[] actions = {new C09PacketHeldItemChange(), new C0APacketAnimation(),
                new C02PacketUseEntity(f.target, C02PacketUseEntity.Action.ATTACK),
                new C08PacketPlayerBlockPlacement(), new C07PacketPlayerDigging(),
                new C0DPacketCloseWindow(), new C0EPacketClickWindow(),
                new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT)};
        for (Object action : actions) {
            reset(f); f.arm();
            f.channel.writeOutbound(action);
            check("action forwarded verbatim", f.channel.readOutbound() == action);
            f.tick(); check("action gates Slap until movement: " + action.getClass().getSimpleName(), f.channel.readOutbound() == null);
            move(f); f.tick(); checkPair(f.channel);
        }
        Object[] permitted = {new C02PacketUseEntity(f.target, C02PacketUseEntity.Action.INTERACT),
                new C16PacketClientStatus(C16PacketClientStatus.EnumState.REQUEST_STATS)};
        for (Object action : permitted) {
            reset(f); f.arm(); f.channel.writeOutbound(action); f.channel.readOutbound();
            f.tick(); checkPair(f.channel);
        }
        C03PacketPlayer[] moves = {new C03PacketPlayer.C04PacketPlayerPosition(),
                new C03PacketPlayer.C05PacketPlayerLook(), new C03PacketPlayer.C06PacketPlayerPosLook()};
        for (Object movement : moves) {
            reset(f); f.arm(); f.channel.writeOutbound(new C0APacketAnimation()); f.channel.readOutbound();
            f.channel.writeOutbound(movement);
            check("C03 subclasses pass and reset", f.channel.readOutbound() == movement && !AttackReduceFeature.hasBadPackets());
            f.tick(); checkPair(f.channel);
        }
        reset(f); f.arm(); f.target.hurtTime = 8; f.tick(); checkPair(f.channel);
        check("reference Slap does not require a fresh damage window", "HIT".equals(AttackReduceFeature.getStatus()));
        reset(f); f.receive(7, .0625, .36, 0); f.drain();
        f.hit.entityHit = null; f.tick(); f.tick(); f.hit.entityHit = f.target; f.tick();
        checkPair(f.channel);
        check("third tick included at exact 500 boundary", near(f.player.motionX, .0375));
        reset(f); f.receive(7, .0625, .36, 0); f.drain(); f.hit.entityHit = null;
        f.tick(); f.tick(); f.tick(); f.hit.entityHit = f.target; f.tick();
        check("expired Slap window cannot attack", f.channel.readOutbound() == null && "EXPIRED".equals(AttackReduceFeature.getStatus()));
    }

    private static void testAura(AttackReduceSprintHitTest.Fixture f) throws Exception {
        Module aura = new Module("KillAura", "combat.killaura") { };
        Module clicker = new Module("AutoClicker", "combat.autoclicker") { };
        ModuleManager.register(aura); ModuleManager.register(clicker);
        reset(f); aura.setEnabled(true); clicker.setEnabled(true);
        set("killAuraTarget", f.target); set("killAuraAimReady", true);
        f.mc.objectMouseOver = null; f.arm(); f.tick(); checkPair(f.channel);
        check("enabled Aura and clicker do not block reduction by themselves", near(f.player.motionX, .24));
        reset(f); set("killAuraTarget", f.target); set("killAuraAimReady", true);
        set("killAuraAttackedThisTick", true); f.arm(); f.tick();
        check("main-thread Aura send blocks even before IO observer sees it", f.channel.readOutbound() == null);
        reset(f); set("autoclickerClickedThisTick", true); f.arm(); f.tick();
        check("actual clicker action blocks extra attack", f.channel.readOutbound() == null);
        reset(f); set("rightClickUsedThisTick", true); f.arm(); f.tick();
        check("actual right-click action blocks extra attack", f.channel.readOutbound() == null);
        reset(f); AttackReduceFeature.setSlapCrosshair(false); f.arm(); f.tick();
        check("Aura-only setting does not attack crosshair", f.channel.readOutbound() == null);
        reset(f); set("killAuraTarget", f.target); set("killAuraAimReady", false); f.mc.objectMouseOver = null;
        f.arm(); f.tick(); check("new unsent Aura rotation is not used for attack", f.channel.readOutbound() == null);
        aura.setEnabled(false); clicker.setEnabled(false);
    }

    private static void testAsyncReservation(AttackReduceSprintHitTest.Fixture f) throws Exception {
        reset(f); f.arm();
        ArrayDeque<Object> writes = new ArrayDeque<Object>();
        Consumer<Object> send = f.player.sendQueue.outbound;
        f.player.sendQueue.outbound = writes::add;
        f.tick();
        check("queued local attack reserves gate before Netty observes it", writes.size() == 2 && AttackReduceFeature.hasBadPackets());
        f.player.sprinting = true;
        move(f); f.tick();
        check("older delayed C03 cannot clear pending reservation", writes.size() == 2);
        while (!writes.isEmpty()) f.channel.writeOutbound(writes.poll());
        checkPair(f.channel);
        move(f); f.player.sendQueue.outbound = send; f.tick(); checkPair(f.channel);
        check("reservation releases only after own C02 then C03", near(f.player.motionX, .144));
        reset(f); f.player.sendQueue.failAt = 2; f.arm(); f.tick();
        check("partial send failure forwards only animation", f.channel.readOutbound() instanceof C0APacketAnimation && f.channel.readOutbound() == null);
        check("partial failure leaves motion untouched and reports failure (motion=" + f.player.motionX
                + ", status=" + AttackReduceFeature.getStatus() + ", sends=" + f.player.sendQueue.sent + ")",
                near(f.player.motionX, .4) && "SEND FAILED".equals(AttackReduceFeature.getStatus()));
        move(f); f.tick(); check("partial failure never retries burst", f.channel.readOutbound() == null);
    }

    private static void testLifecycle(AttackReduceSprintHitTest.Fixture f) throws Exception {
        reset(f); f.receive(7, .4, .36, -.2);
        AttackReduceFeature.setMode(AttackReduceFeature.MODE_SIMPLE);
        AttackReduceFeature.setMode(AttackReduceFeature.MODE_SLAP_ATTACK);
        f.drain(); f.tick(); check("mode switching invalidates queued Slap", f.channel.readOutbound() == null);
        reset(f); f.receive(8, .4, .36, -.2); f.drain(); f.tick();
        check("other player S12 does not arm Slap", f.channel.readOutbound() == null);
        reset(f); f.receive(7, 0, .36, 0); f.drain(); f.tick();
        check("vertical-only S12 does not arm Slap", f.channel.readOutbound() == null);
        reset(f); f.receive(7, .4, .36, -.2); f.mc.theWorld = new Object(); f.drain(); f.tick();
        check("world change invalidates pending callback and actions", f.channel.readOutbound() == null && !AttackReduceFeature.hasBadPackets());
        reset(f); f.arm(); AttackReduceSprintHitTest.MODULE.setEnabled(false); f.tick();
        check("disable removes outstanding action window", f.channel.readOutbound() == null && !AttackReduceFeature.hasBadPackets());
    }

    private static void testSettingsAndWindows() throws Exception {
        AttackReduceFeature.load(new Properties());
        check("fresh settings select Slap", AttackReduceFeature.getMode() == AttackReduceFeature.MODE_SLAP_ATTACK);
        int[] limits = {500,1000,2000,3000,4000,5000,6000,7000,8000,9000};
        int[] ticks = {3,4,4,5,6,6,7,7,8,8,9};
        for (int i = 0; i < limits.length; i++) {
            check("table boundary " + limits[i], AttackReduceFeature.calculateSlapTicks(limits[i], 0) == ticks[i]);
            check("table next band " + limits[i], AttackReduceFeature.calculateSlapTicks(limits[i]+1, 0) == ticks[i+1]);
            check("table sign symmetry", AttackReduceFeature.calculateSlapTicks(-limits[i], 0) == ticks[i]);
        }
        check("uses vector magnitude not largest axis", AttackReduceFeature.calculateSlapTicks(300,400) == 3 && AttackReduceFeature.calculateSlapTicks(500,500) == 4);
        check("huge values do not overflow", AttackReduceFeature.calculateSlapTicks(Integer.MIN_VALUE, Integer.MAX_VALUE) == 9);
        AttackReduceFeature.setSlapExact(false); AttackReduceFeature.setSlapCrosshair(false);
        check("regression formula", AttackReduceFeature.calculateSlapTicks(3000,4000) == 6);
        check("regression upper clamp", AttackReduceFeature.calculateSlapTicks(Integer.MAX_VALUE,0) == 10);
        Properties saved = new Properties(); saved.load(new java.io.StringReader(AttackReduceFeature.save()));
        AttackReduceFeature.load(saved);
        check("Slap settings roundtrip", !AttackReduceFeature.getSlapExact() && !AttackReduceFeature.getSlapCrosshair() && AttackReduceFeature.getMode() == 2);
        saved.setProperty("velocity.mode", "0"); AttackReduceFeature.load(saved);
        check("explicit old SprintHit choice retained", AttackReduceFeature.getMode() == 0);
    }

    private static void testStaleHook(AttackReduceSprintHitTest.Fixture f) throws Exception {
        reset(f);
        AttackReducePacketHook old = (AttackReducePacketHook) f.channel.pipeline().get(AttackReducePacketHook.NAME);
        f.receive(7, .4, .36, -.2);
        f.channel.pipeline().remove(AttackReducePacketHook.NAME);
        for (int i = 0; i < 21; i++) f.tick();
        check("external removal installs a new observer", f.channel.pipeline().get(AttackReducePacketHook.NAME) != old);
        f.drain(); f.tick();
        check("old observer's queued S12 callback cannot open a new window", f.channel.readOutbound() == null);
        C0APacketAnimation stale = new C0APacketAnimation();
        old.write(f.channel.pipeline().context(AttackReducePacketHook.NAME), stale, f.channel.newPromise());
        f.channel.flush();
        check("late old observer forwards but cannot poison action state", f.channel.readOutbound() == stale && !AttackReduceFeature.hasBadPackets());
        reset(f); f.arm(); f.tick(); checkPair(f.channel);
        f.channel.pipeline().remove(AttackReducePacketHook.NAME);
        check("external removal is reflected immediately in GUI state", !AttackReduceFeature.isPacketHookInstalled());
        move(f); // 这份 C03 没经过观察器，预约尚未确认；重装必须清掉。
        for (int i = 0; i < 21; i++) f.tick();
        check("observer repair clears orphaned reservations", AttackReduceFeature.isPacketHookInstalled() && !AttackReduceFeature.hasBadPackets());
    }

    private static void reset(AttackReduceSprintHitTest.Fixture f) throws Exception {
        f.reset(); AttackReduceFeature.setMode(AttackReduceFeature.MODE_SLAP_ATTACK);
        set("killAuraTarget", null); set("killAuraAimReady", false); set("killAuraAttackedThisTick", false);
        set("autoclickerClickedThisTick", false); set("rightClickUsedThisTick", false); set("placementsThisTick", 0);
    }
    private static void blocked(AttackReduceSprintHitTest.Fixture f, String label, Consumer<AttackReduceSprintHitTest.Fixture> action) throws Exception {
        reset(f); f.arm(); action.accept(f); f.tick();
        check(label, f.channel.readOutbound() == null && near(f.player.motionX,.4) && near(f.player.motionY,.36));
    }
    private static void move(AttackReduceSprintHitTest.Fixture f) {
        C03PacketPlayer movement = new C03PacketPlayer(0);
        f.channel.writeOutbound(movement); check("movement passes immediately", f.channel.readOutbound() == movement);
    }
    private static void checkPair(EmbeddedChannel channel) {
        check("one animation then one attack", channel.readOutbound() instanceof C0APacketAnimation && channel.readOutbound() instanceof C02PacketUseEntity && channel.readOutbound() == null);
    }
    private static void set(String name, Object value) throws Exception {
        Field field = OpenMaverFeatures.class.getDeclaredField(name); field.setAccessible(true); field.set(null,value);
    }
    private static boolean near(double a, double b) { return Math.abs(a-b) < 1E-8; }
    private static void check(String label, boolean condition) { if (!condition) throw new AssertionError(label); passed++; }
}
