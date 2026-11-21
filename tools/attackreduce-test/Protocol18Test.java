import io.netty.channel.ChannelPromise;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import java.util.ArrayList;
import java.util.List;

/** 1.8 协议回归：消费真实 Netty 出站记录，校验顺序、事务边界和原版物理分支。 */
final class Protocol18Test {
    static void check(String message, boolean result) { ZenAntiKBTest.check(message, result); }
    static boolean near(double a, double b) { return ZenAntiKBTest.near(a, b); }
    static List<Object> outbound(ZenAntiKBTest.Fixture f) {
        f.channel.runPendingTasks();
        List<Object> packets = new ArrayList<Object>();
        Object packet;
        while ((packet = f.channel.readOutbound()) != null) packets.add(packet);
        return packets;
    }
    static void run() throws Exception {
        nativeAttack(); autoClickerModes(); criticalSprint(); replayBoundaries(); outboundFifo(); repeatedVelocity(); sounds();
    }
    static void autoClickerModes() throws Exception {
        java.lang.reflect.Method click = OpenMaverFeatures.class.getDeclaredMethod("tryAutoLeftClick", net.minecraft.client.Minecraft.class, long.class);
        click.setAccessible(true);
        for (int mode = 0; mode < 3; mode++) try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(1)) {
            OpenMaverFeatures.setAutoClickerAttackMode(mode);
            ZenAntiKBTest.field(OpenMaverFeatures.class, "autoclickerLastEntityAttackNanos", 0L);
            ZenAntiKBTest.field(OpenMaverFeatures.class, "autoclickerLastEntityAttackTick", Long.MIN_VALUE);
            check("actual AutoClicker attack succeeds in mode " + mode, Boolean.TRUE.equals(click.invoke(null, f.mc, System.nanoTime())));
            List<Object> wire = outbound(f);
            check("AutoClicker mode " + mode + " uses 1.8 animation-before-attack", wire.size() == 2
                    && wire.get(0) instanceof C0APacketAnimation && wire.get(1) instanceof C02PacketUseEntity);
        }
        OpenMaverFeatures.setAutoClickerAttackMode(0);
    }
    static void nativeAttack() {
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(0)) {
            f.player.serverSprintState = false; f.player.motionX = .4; f.player.motionY = .36;
            check("sprint attack succeeds", AttackReduceFeature.attack(f.target, true));
            List<Object> wire = outbound(f);
            check("actual wire is START_SPRINTING -> C0A -> C02", wire.size() == 3
                    && wire.get(0) instanceof C0BPacketEntityAction
                    && ((C0BPacketEntityAction) wire.get(0)).action == C0BPacketEntityAction.Action.START_SPRINTING
                    && wire.get(1) instanceof C0APacketAnimation && wire.get(2) instanceof C02PacketUseEntity);
            check("native attack reduces X once and preserves Y", near(f.player.motionX, .24) && near(f.player.motionY, .36));
            check("sent sprint state matches server snapshot", f.player.serverSprintState && !f.player.sprinting);
            check("Velocity also suppresses vanilla same-tick duplicate attack", f.mc.leftClickCounter == 2);
            check("shared attack budget rejects a second module attack", !OpenMaverFeatures.attackEntity18(f.mc, f.player, f.target)
                    && outbound(f).isEmpty() && f.controller.attacks == 1);
        }
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(0)) {
            f.target.acceptsDamage = false; f.player.motionX = .4; f.player.motionZ = -.2;
            check("damage-rejected attack is still sent", AttackReduceFeature.attack(f.target, true));
            check("failed damage never fabricates 0.6 damping", near(f.player.motionX, .4)
                    && near(f.player.motionZ, -.2) && f.player.sprinting);
        }
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(0)) {
            f.player.swingFails = true; f.player.motionX = .4;
            check("failed swing prevents C02 and motion changes", !AttackReduceFeature.attack(f.target, false)
                    && f.controller.attacks == 0 && outbound(f).isEmpty() && near(f.player.motionX, .4));
            f.player.swingFails = false; f.player.usingItem = true;
            check("item use prevents Velocity attack", !AttackReduceFeature.attack(f.target, false) && outbound(f).isEmpty());
            f.player.usingItem = false; f.mc.currentScreen = new Object();
            check("GUI prevents Velocity attack", !AttackReduceFeature.attack(f.target, false) && outbound(f).isEmpty());
            f.mc.currentScreen = null; f.target.box = new net.minecraft.util.AxisAlignedBB(-.3, 0, 3.2, .3, 1.8, 3.8);
            check("out-of-range target cannot trigger local damping", !AttackReduceFeature.attack(f.target, false)
                    && f.controller.attacks == 0 && near(f.player.motionX, .4));
        }
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity());
            f.player.onGround = true; f.player.sprinting = false;
            OpenMaverFeatures.beginAttackTick(); AttackReduceFeature.beforeTick(f.mc);
            f.player.sprinting = true; // 对应正常的 AutoSprint 更新阶段。
            AttackReduceFeature.onTickStart(f.mc);
            check("NoXZ checks sprint after AutoSprint stage", f.controller.attacks == 1 && near(f.player.motionX, .24));
        }
    }
    static void criticalSprint() throws Exception {
        java.lang.reflect.Method stop = OpenMaverFeatures.class.getDeclaredMethod("stopCriticalsSprint", Object.class);
        java.lang.reflect.Method restore = OpenMaverFeatures.class.getDeclaredMethod("restoreCriticalsSprint", Object.class, boolean.class);
        stop.setAccessible(true); restore.setAccessible(true);
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(1)) {
            boolean stopped = Boolean.TRUE.equals(stop.invoke(null, f.player));
            check("critical sprint stop updates server snapshot", stopped && !f.player.sprinting && !f.player.serverSprintState);
            check("critical attack shares 1.8 entry", OpenMaverFeatures.attackEntity18(f.mc, f.player, f.target));
            restore.invoke(null, f.player, stopped);
            List<Object> wire = outbound(f);
            check("critical attack has exactly STOP -> C0A -> C02 -> START", wire.size() == 4
                    && wire.get(0) instanceof C0BPacketEntityAction && wire.get(1) instanceof C0APacketAnimation
                    && wire.get(2) instanceof C02PacketUseEntity && wire.get(3) instanceof C0BPacketEntityAction
                    && ((C0BPacketEntityAction) wire.get(0)).action == C0BPacketEntityAction.Action.STOP_SPRINTING
                    && ((C0BPacketEntityAction) wire.get(3)).action == C0BPacketEntityAction.Action.START_SPRINTING);
            check("critical sprint restoration updates server snapshot", f.player.sprinting && f.player.serverSprintState);
        }
    }
    static void replayBoundaries() {
        for (int mode = 0; mode < 3; mode++) try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(mode)) {
            f.channel.writeOutbound(new C03PacketPlayer(0)); outbound(f);
            f.player.onGround = false;
            S12PacketEntityVelocity velocity = f.velocity(); f.receive(velocity);
            f.receive(new ZenAntiKBTest.S32PacketConfirmTransaction());
            if (mode == 0) {
                List<Object> immediate = outbound(f);
                check("NoXZ applies S12 and acknowledges S32 before actions", f.applied.size() == 2
                        && f.applied.get(0) == velocity && immediate.size() == 1 && immediate.get(0) instanceof C0FPacketConfirmTransaction);
                OpenMaverFeatures.attackEntity18(f.mc, f.player, f.target);
                AttackReduceFeature.current().release();
                f.channel.writeOutbound(new C03PacketPlayer(1));
                List<Object> wire = outbound(f);
                check("NoXZ release never injects a late transaction or movement burst", wire.size() == 3
                        && wire.get(0) instanceof C0APacketAnimation && wire.get(1) instanceof C02PacketUseEntity
                        && wire.get(2) instanceof C03PacketPlayer);
                f.start();
                check("NoXZ next tick never reapplies physics", f.applied.size() == 2 && near(f.player.motionX, .24));
                continue;
            }
            OpenMaverFeatures.attackEntity18(f.mc, f.player, f.target);
            AttackReduceFeature.current().release(); AttackReduceFeature.current().onMotion();
            check("late release defers incoming physics until next tick: " + mode, f.applied.isEmpty());
            f.channel.writeOutbound(new C03PacketPlayer(1));
            List<Object> wire = outbound(f);
            check("late release has no mid-action C0F: " + mode, wire.stream().noneMatch(p -> p instanceof C0FPacketConfirmTransaction));
            f.start(); wire.addAll(outbound(f));
            int movement = -1, ack = -1;
            boolean flying = true, post = false;
            int flags = 0;
            for (int i = 0; i < wire.size(); i++) {
                Object p = wire.get(i);
                if (p instanceof C03PacketPlayer) { movement = i; flying = true; post = false; }
                else if (p instanceof C0FPacketConfirmTransaction) {
                    ack = i; if (flying && post) flags++; flying = false; post = false;
                } else if (p instanceof C0APacketAnimation || p instanceof C02PacketUseEntity) post = true;
            }
            check("C0F follows movement boundary and Post model stays clear: " + mode, ack > movement && movement >= 0 && flags == 0);
            check("deferred S12 precedes its transaction exactly once: " + mode,
                    f.applied.size() == 2 && f.applied.get(0) == velocity && near(f.player.motionX, .4));
        }
    }
    static void outboundFifo() {
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity());
            Object[] writes = {new C03PacketPlayer(1), new C0APacketAnimation(),
                    new C02PacketUseEntity(f.target, C02PacketUseEntity.Action.ATTACK),
                    new C0FPacketConfirmTransaction(), new C03PacketPlayer(2)};
            List<ChannelPromise> promises = new ArrayList<ChannelPromise>();
            for (Object packet : writes) {
                ChannelPromise promise = f.channel.newPromise(); promises.add(promise);
                f.channel.pipeline().write(packet, promise);
            }
            f.channel.pipeline().flush();
            List<Object> wire = outbound(f);
            check("NoXZ passes movement, actions and ack without holding", wire.size() == writes.length
                    && promises.stream().allMatch(p -> p.isSuccess()));
            AttackReduceFeature.current().release(); AttackReduceFeature.current().onMotion();
            check("NoXZ release has no outgoing backlog", outbound(f).isEmpty());
            for (int i = 0; i < writes.length; i++) check("outgoing identity " + i, wire.get(i) == writes[i]);
        }
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(1)) {
            f.holdOutbound();
            C03PacketPlayer first = new C03PacketPlayer(1), second = new C03PacketPlayer(2);
            ChannelPromise a = f.channel.newPromise(), b = f.channel.newPromise();
            f.channel.pipeline().write(first, a); f.channel.pipeline().write(second, b); f.channel.pipeline().flush();
            check("adapter hold retains movement and promises", outbound(f).isEmpty() && !a.isDone() && !b.isDone());
            AttackReduceFeature.current().release();
            List<Object> wire = outbound(f);
            check("adapter release preserves FIFO and promises", wire.size() == 2 && wire.get(0) == first
                    && wire.get(1) == second && a.isSuccess() && b.isSuccess());
        }
    }
    static void repeatedVelocity() {
        for (int mode = 0; mode < 3; mode++) try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(mode)) {
            f.player.onGround = false;
            S12PacketEntityVelocity first = f.velocity(), second = new S12PacketEntityVelocity(7, .2, .3, .1);
            f.receive(first); f.receive(second);
            if (mode == 0) {
                ChannelPromise sent = f.channel.newPromise(); C03PacketPlayer move = new C03PacketPlayer(2);
                f.channel.pipeline().write(move, sent); f.channel.pipeline().flush();
                List<Object> wire = outbound(f);
                check("successive airborne NoXZ impulses apply immediately", f.applied.size() == 2 && near(f.player.motionX, .2));
                check("second NoXZ impulse leaves movement unblocked", wire.size() == 1 && wire.get(0) == move && sent.isSuccess());
            }
            AttackReduceFeature.current().release(); AttackReduceFeature.current().onMotion(); f.start();
            check("successive S12 preserve arrival order and newest impulse: " + mode,
                    f.applied.size() == 2 && f.applied.get(0) == first && f.applied.get(1) == second
                            && near(f.player.motionX, .2) && near(f.player.motionY, .3) && near(f.player.motionZ, .1));
        }
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(1)) {
            f.player.onGround = false; f.receive(f.velocity());
            AttackReduceFeature.current().release();
            S12PacketEntityVelocity newest = new S12PacketEntityVelocity(7, .1, .2, .3);
            f.receive(newest); AttackReduceFeature.current().release(); f.start();
            check("late replay stays before newly arriving S12", f.applied.size() == 2 && f.applied.get(1) == newest
                    && near(f.player.motionX, .1) && near(f.player.motionY, .2) && near(f.player.motionZ, .3));
        }
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(1)) {
            f.player.onGround = false; f.receive(f.velocity());
            AttackReduceFeature.setMode(0); f.mc.theWorld = new Object(); f.start();
            check("deferred replay never applies old-world velocity", f.applied.isEmpty());
        }
    }
    public static final class S29PacketSoundEffect extends ZenAntiKBTest.PacketStub {
        private String field_149219_a;
        final double x = 1, y = 2, z = 3;
        final float volume = .7F, pitch = .9F;
        S29PacketSoundEffect(String name) { field_149219_a = name; }
    }
    static void sounds() {
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(0)) {
            ZenAntiKBTest.MODULE.setEnabled(false); f.start();
            S29PacketSoundEffect thorns = new S29PacketSoundEffect("damage.thorns"); f.receive(thorns);
            check("sound compatibility remains installed with Velocity disabled", f.channel.pipeline().context(LegacySoundFeature.NAME) != null
                    && !AttackReduceFeature.isPacketHookInstalled());
            check("unknown thorns aliases to vanilla hurt without changing payload", "game.player.hurt".equals(thorns.field_149219_a)
                    && thorns.x == 1 && thorns.y == 2 && thorns.z == 3 && thorns.volume == .7F && thorns.pitch == .9F);
            S29PacketSoundEffect namespaced = new S29PacketSoundEffect("minecraft:damage.thorns"); f.receive(namespaced);
            check("namespaced thorns is normalized", "game.player.hurt".equals(namespaced.field_149219_a));
            S29PacketSoundEffect other = new S29PacketSoundEffect("random.orb"); f.receive(other);
            check("unrelated sound is untouched and processed once", "random.orb".equals(other.field_149219_a)
                    && f.applied.size() == 3 && f.applied.get(2) == other);
            f.channel.pipeline().remove(LegacySoundFeature.NAME); f.start();
            check("sound hook self-heals", f.channel.pipeline().context(LegacySoundFeature.NAME) != null);
        }
    }
}
