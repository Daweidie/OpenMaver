import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import java.util.List;

/** Independent model of Grim's 1.8 sprint/attack state, driven by actual Netty output. */
final class JumpResetGrimTest {
    static final class GrimSprint {
        boolean sprinting, lastSprinting;
        double x, z;
        int attacks;
        void velocity(double x, double z) { this.x = x; this.z = z; }
        void accept(List<Object> packets) {
            for (Object packet : packets) {
                if (packet instanceof C0BPacketEntityAction) {
                    C0BPacketEntityAction.Action action = ((C0BPacketEntityAction) packet).action;
                    if (action == C0BPacketEntityAction.Action.START_SPRINTING) sprinting = true;
                    if (action == C0BPacketEntityAction.Action.STOP_SPRINTING) sprinting = false;
                } else if (packet instanceof C02PacketUseEntity) {
                    attacks++;
                    // Plain player target, no knockback enchantment, accepted damage.
                    if (lastSprinting) { x *= .6; z *= .6; }
                } else if (packet instanceof C03PacketPlayer.C04PacketPlayerPosition
                        || packet instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
                    // CheckManagerListener only runs position prediction for position packets.
                    lastSprinting = sprinting;
                }
            }
        }
        double offset(ZenAntiKBTest.Fixture f) { return Math.hypot(x - f.player.motionX, z - f.player.motionZ); }
    }
    static void check(String text, boolean value) { ZenAntiKBTest.check(text, value); }
    static void move(ZenAntiKBTest.Fixture f) {
        check("vanilla sprint sync succeeds", OpenMaverFeatures.syncSprintBeforeAttack(f.player));
        f.channel.writeOutbound(new C03PacketPlayer.C04PacketPlayerPosition());
    }
    static void run() throws Exception {
        delayedSprint(); stableSprint(); positionBoundary(); expireAndCancel(); heldFrames(); corrections(); sharedClicks();
    }
    static void delayedSprint() {
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            GrimSprint grim = new GrimSprint();
            f.player.onGround = false;
            f.player.sprinting = false;
            move(f); grim.accept(Protocol18Test.outbound(f));
            f.receive(new S12PacketEntityVelocity(7, -.3, .38, .8)); grim.velocity(-.3, .8);
            f.input(); grim.accept(Protocol18Test.outbound(f));
            check("new sprint waits without the logged 0.341760 attack mismatch", grim.attacks == 0
                    && f.controller.attacks == 0 && grim.offset(f) < 1.0e-8);
            move(f); grim.accept(Protocol18Test.outbound(f));
            f.start(); f.input(); grim.accept(Protocol18Test.outbound(f));
            check("next input attacks after a real sprinting position frame", grim.attacks == 1
                    && f.controller.attacks == 1 && grim.offset(f) < 1.0e-8
                    && ZenAntiKBTest.near(f.player.motionY, .38));
            move(f); grim.accept(Protocol18Test.outbound(f)); f.tick(); f.tick();
            grim.accept(Protocol18Test.outbound(f));
            check("waiting reset never duplicates its attack", grim.attacks == 1 && grim.offset(f) < 1.0e-8);
        }
    }
    static void stableSprint() {
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_JUMP_RESET, false)) {
            GrimSprint grim = new GrimSprint();
            f.player.onGround = false; f.player.serverSprintState = false;
            move(f); grim.accept(Protocol18Test.outbound(f));
            f.receive(f.velocity()); grim.velocity(.4, -.2);
            f.input(); grim.accept(Protocol18Test.outbound(f));
            check("established sprint still attacks immediately with matching Grim slowdown", grim.attacks == 1
                    && f.controller.attacks == 1 && grim.offset(f) < 1.0e-8);
        }
    }
    static void positionBoundary() {
        for (boolean posLook : new boolean[] {false, true})
            try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_JUMP_RESET, false)) {
                f.player.onGround = false; f.receive(f.velocity()); f.input();
                check("local serverSprintState alone never establishes lastSprinting", f.controller.attacks == 0);
                f.channel.writeOutbound(new C03PacketPlayer(0));
                f.channel.writeOutbound(new C03PacketPlayer.C05PacketPlayerLook());
                f.input();
                check("ground-only and look-only packets cannot release an air attack", f.controller.attacks == 0);
                f.channel.writeOutbound(posLook ? new C03PacketPlayer.C06PacketPlayerPosLook()
                        : new C03PacketPlayer.C04PacketPlayerPosition());
                f.start(); f.input();
                check("normal C04/C06 establishes sprint for the next input: " + posLook, f.controller.attacks == 1);
            }
    }
    static void expireAndCancel() {
        for (int stop = 0; stop < 5; stop++)
            try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_JUMP_RESET, false)) {
                f.player.onGround = false; f.receive(f.velocity()); f.input();
                if (stop == 1) f.player.onGround = true;
                if (stop == 2) f.target.alive = false;
                if (stop == 3) f.mc.currentScreen = new Object();
                if (stop == 4) f.receive(new ZenAntiKBTest.S07PacketRespawn());
                for (int i = 0; i < 4; i++) f.tick();
                move(f); f.tick();
                check("waiting attack cancels on expiry/landing/target loss/GUI/respawn: " + stop,
                        f.controller.attacks == 0 && f.player.movementInput.moveForward == 0
                        && ZenAntiKBTest.near(f.player.motionX, .4) && ZenAntiKBTest.near(f.player.motionY, .36));
            }
    }
    static void heldFrames() {
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_JUMP_RESET, false)) {
            f.holdOutbound();
            f.player.serverSprintState = false; move(f);
            check("held sprint and position packets never establish a server frame",
                    "unknown".equals(AttackReduceFeature.sprintFrameState())
                    && Protocol18Test.outbound(f).isEmpty()
                    && !OpenMaverFeatures.attackEntity18(f.mc, f.player, f.target));
            AttackReduceFeature.sendMovePackets(); f.channel.runPendingTasks();
            check("flushed FIFO establishes sprint without fabricating a movement packet",
                    "true".equals(AttackReduceFeature.sprintFrameState())
                    && Protocol18Test.outbound(f).size() == 2
                    && OpenMaverFeatures.attackEntity18(f.mc, f.player, f.target));
        }
    }
    static void corrections() {
        for (boolean async : new boolean[] {false, true})
            try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
                java.util.function.Consumer<Object> original = f.player.sendQueue.inbound;
                C03PacketPlayer ack = new C03PacketPlayer.C06PacketPlayerPosLook();
                f.player.sendQueue.inbound = packet -> {
                    original.accept(packet);
                    if (packet instanceof ZenAntiKBTest.S08PacketPlayerPosLook) {
                        if (async) f.channel.eventLoop().execute(() -> f.channel.writeOutbound(ack));
                        else f.channel.writeOutbound(ack);
                    }
                };
                f.receive(new ZenAntiKBTest.S08PacketPlayerPosLook());
                check("S08 confirmation never establishes normal sprint frame: async=" + async,
                        Protocol18Test.outbound(f).contains(ack)
                        && "unknown".equals(AttackReduceFeature.sprintFrameState()));
                f.player.onGround = false; f.receive(f.velocity()); f.input();
                check("post-correction air reset waits for real movement", f.controller.attacks == 0);
                move(f); f.start(); f.input();
                check("post-correction real position allows one attack", f.controller.attacks == 1);
            }
    }
    static void sharedClicks() throws Exception {
        java.lang.reflect.Method click = OpenMaverFeatures.class.getDeclaredMethod("tryAutoLeftClick",
                net.minecraft.client.Minecraft.class, long.class);
        click.setAccessible(true);
        for (boolean previous : new boolean[] {false, true})
            try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
                f.player.sprinting = previous; move(f); Protocol18Test.outbound(f);
                f.player.sprinting = !previous;
                ZenAntiKBTest.field(OpenMaverFeatures.class, "autoclickerLastEntityAttackNanos", 0L);
                ZenAntiKBTest.field(OpenMaverFeatures.class, "autoclickerLastEntityAttackTick", Long.MIN_VALUE);
                check("AutoClicker defers mismatched sprint state in either direction: " + previous,
                        !Boolean.TRUE.equals(click.invoke(null, f.mc, System.nanoTime()))
                        && f.controller.attacks == 0 && !OpenMaverFeatures.hasEntityAttackThisTick()
                        && Protocol18Test.outbound(f).isEmpty());
                move(f); Protocol18Test.outbound(f);
                check("AutoClicker resumes after a matching position frame: " + previous,
                        Boolean.TRUE.equals(click.invoke(null, f.mc, System.nanoTime())) && f.controller.attacks == 1);
            }
    }
    public static void main(String[] args) throws Exception {
        module.ModuleManager.register(ZenAntiKBTest.MODULE);
        module.ModuleManager.register(ZenAntiKBTest.AURA);
        run();
    }
}
