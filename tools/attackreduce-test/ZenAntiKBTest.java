import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import module.Module;
import module.ModuleManager;
import module.movement.AttackReduce;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovingObjectPosition;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Real Netty 4.0 + Minecraft 主线程队列；验证实际模式状态机，而非源码字符串。 */
public final class ZenAntiKBTest {
    static final AttackReduce MODULE = new AttackReduce();
    static final Module AURA = new Module("KillAura", "combat.killaura") { };
    static int passed;
    public static class Player extends EntityPlayer {
        public NetHandlerPlayClient sendQueue;
        public MovementInput movementInput = new MovementInput();
        public boolean onGround = true, sprinting = true, isInWeb, water, lava, burning, ladder, sleeping, jumpBoost, alive = true;
        public int hurtTime = 10;
        EmbeddedChannel channel;
        public boolean func_70051_ag() { return sprinting; }
        public void func_70031_b(boolean v) { sprinting = v; }
        public boolean isEntityAlive() { return alive; }
        public boolean isInWater() { return water; }
        public boolean isInLava() { return lava; }
        public boolean isBurning() { return burning; }
        public boolean isOnLadder() { return ladder; }
        public boolean isPlayerSleeping() { return sleeping; }
        public boolean func_82165_m(int id) { return id == 8 && jumpBoost; }
        public boolean isPotionActive(int id) { return func_82165_m(id); }
        public float getEyeHeight() { return 1.62F; }
        public void swingItem() { channel.writeOutbound(new C0APacketAnimation()); }
    }
    public static class Target extends EntityLivingBase {
        public boolean alive = true;
        public AxisAlignedBB box = new AxisAlignedBB(-.3, 0, 2.2, .3, 1.8, 2.8);
        public AxisAlignedBB getEntityBoundingBox() { return box; }
        public boolean isEntityAlive() { return alive; }
    }
    public static class Controller {
        public int attacks;
        public boolean sawSprinting;
        public void attackEntity(Player p, EntityLivingBase target) {
            attacks++; sawSprinting |= p.sprinting;
            p.channel.writeOutbound(new C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK));
            // 实际 1.8.9 控制器会在疾跑攻击时减速；Zen 在调用前退出疾跑。
            if (p.sprinting) { p.motionX *= .6; p.motionZ *= .6; p.sprinting = false; }
        }
    }
    public static class PacketStub {
        public void processPacket(NetHandlerPlayClient handler) { handler.process(this); }
    }
    public static class S32PacketConfirmTransaction extends PacketStub { }
    public static class S14PacketEntity extends PacketStub { }
    public static class S15PacketEntityRelMove extends S14PacketEntity { }
    public static class S18PacketEntityTeleport extends PacketStub { }
    public static class S02PacketChat extends PacketStub { }
    public static class S03PacketTimeUpdate extends PacketStub { }
    public static class S06PacketUpdateHealth extends PacketStub { }
    public static class S19PacketEntityStatus extends PacketStub { }
    public static class S0BPacketAnimation extends PacketStub {
        public final int field_148981_a;
        S0BPacketAnimation(int id) { field_148981_a = id; }
    }
    public static class S08PacketPlayerPosLook extends PacketStub { }
    public static class S07PacketRespawn extends PacketStub { }
    public static class S40PacketDisconnect extends PacketStub { }
    public static class S00PacketKeepAlive extends PacketStub { }

    static final class Fixture implements AutoCloseable {
        final Minecraft mc = new Minecraft();
        final Player player = new Player();
        final Target target = new Target();
        final Controller controller = new Controller();
        final Object world = new Object();
        final List<Object> applied = new ArrayList<Object>();
        final EmbeddedChannel channel;
        final Thread main = Thread.currentThread();
        final MovingObjectPosition hit = new MovingObjectPosition();
        Fixture(int mode) {
            AttackReduceFeature.load(new Properties()); MODULE.setEnabled(true); AURA.setEnabled(false);
            channel = new EmbeddedChannel(new ChannelDuplexHandler() {
                public void channelRead(ChannelHandlerContext ctx, final Object msg) {
                    mc.addScheduledTask(new Runnable() {
                        public void run() { player.sendQueue.process(msg); }
                    });
                }
            });
            channel.pipeline().addBefore(channel.pipeline().firstContext().name(), "packet_handler", new ChannelDuplexHandler());
            NetworkManager manager = new NetworkManager(); manager.setChannel(channel);
            player.channel = channel; player.setEntityId(7);
            player.sendQueue = new NetHandlerPlayClient(manager);
            player.sendQueue.outbound = p -> channel.writeOutbound(p);
            player.sendQueue.inbound = p -> {
                check("packet handling stays on main thread", Thread.currentThread() == main);
                applied.add(p);
                if (p instanceof S12PacketEntityVelocity) {
                    S12PacketEntityVelocity v = (S12PacketEntityVelocity) p;
                    if (v.getEntityID() == player.getEntityId()) {
                        player.motionX = v.getMotionX() / 8000.0;
                        player.motionY = v.getMotionY() / 8000.0;
                        player.motionZ = v.getMotionZ() / 8000.0;
                    }
                }
            };
            mc.thePlayer = player; mc.theWorld = world; mc.playerController = controller;
            hit.typeOfHit = MovingObjectPosition.MovingObjectType.ENTITY; hit.entityHit = target; mc.objectMouseOver = hit;
            AttackReduceFeature.setMode(mode); start();
        }
        void start() { AttackReduceFeature.onTickStart(mc); channel.runPendingTasks(); }
        void input() {
            player.movementInput.moveForward = 0; player.movementInput.moveStrafe = 0;
            player.movementInput.jump = false;
            AttackReduceFeature.afterMovementInput(player.movementInput);
        }
        void end() { AttackReduceFeature.onTickEnd(mc); channel.runPendingTasks(); }
        void tick() { start(); input(); end(); }
        void drain() { Runnable task; while ((task = mc.tasks.poll()) != null) task.run(); channel.runPendingTasks(); }
        void receive(Object packet) { channel.writeInbound(packet); drain(); }
        S12PacketEntityVelocity velocity() { return new S12PacketEntityVelocity(7, .4, .36, -.2); }
        public void close() { MODULE.setEnabled(false); AttackReduceFeature.onTickStart(mc); channel.runPendingTasks(); channel.finish(); }
    }

    public static void main(String[] args) throws Exception {
        ModuleManager.register(MODULE); ModuleManager.register(AURA);
        settings(); noXZ(); jumpReset(); mix(); lifecycle(); threadIsolation();
        System.out.println("ZenAntiKBTest: " + passed + " passed, 0 failed");
    }
    static void settings() throws Exception {
        Properties old = new Properties(); old.setProperty("velocity.mode", "2"); old.setProperty("velocity.horizontal", "0");
        AttackReduceFeature.load(old);
        check("old numeric mode migrates to Zen NoXZ", AttackReduceFeature.getMode() == 0);
        check("only three Zen modes", AttackReduceFeature.MODE_NAMES.length == 3 && "NoXZ".equals(AttackReduceFeature.getSummary()));
        check("Zen default settings", AttackReduceFeature.getAttackAmount() == 5 && AttackReduceFeature.getRotateTicks() == 12
                && AttackReduceFeature.getSprintStateCheck() && !AttackReduceFeature.getRotate() && !AttackReduceFeature.getInstantAttack());
        AttackReduceFeature.setMode(2); AttackReduceFeature.setAttackAmount(9); AttackReduceFeature.setRotateTicks(7);
        AttackReduceFeature.setRotate(true); AttackReduceFeature.setTryAttack(true); AttackReduceFeature.setMovementOverride(true);
        AttackReduceFeature.setFollowDirection(true); AttackReduceFeature.setInstantAttack(true); AttackReduceFeature.setSprintStateCheck(false);
        Properties saved = new Properties(); saved.load(new StringReader(AttackReduceFeature.save())); AttackReduceFeature.load(saved);
        check("all Zen settings roundtrip", AttackReduceFeature.getMode() == 2 && AttackReduceFeature.getAttackAmount() == 9
                && AttackReduceFeature.getRotateTicks() == 7 && AttackReduceFeature.getRotate() && AttackReduceFeature.getTryAttack()
                && AttackReduceFeature.getMovementOverride() && AttackReduceFeature.getFollowDirection()
                && AttackReduceFeature.getInstantAttack() && !AttackReduceFeature.getSprintStateCheck());
        saved.setProperty("velocity.zen.attackAmount", "999"); saved.setProperty("velocity.zen.rotateTicks", "-1");
        AttackReduceFeature.load(saved); check("integer settings clamped", AttackReduceFeature.getAttackAmount() == 20 && AttackReduceFeature.getRotateTicks() == 3);
        saved.setProperty("velocity.zen.mode", "bad"); saved.setProperty("velocity.zen.attackAmount", "NaN");
        AttackReduceFeature.load(saved); check("invalid settings use Zen defaults", AttackReduceFeature.getMode() == 0 && AttackReduceFeature.getAttackAmount() == 5);
    }
    static void noXZ() throws Exception {
        try (Fixture f = new Fixture(0)) {
            check("hook installed before packet_handler", AttackReduceFeature.isPacketHookInstalled()
                    && f.channel.pipeline().names().indexOf(AttackReducePacketHook.NAME) < f.channel.pipeline().names().indexOf("packet_handler"));
            S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            check("NoXZ ground applies original velocity before attacking", f.applied.size() == 1 && f.applied.get(0) == v && near(f.player.motionX, .4) && f.controller.attacks == 0);
            f.tick();
            check("Zen controller plus manual reduction exactly once", f.controller.attacks == 1 && !f.controller.sawSprinting
                    && near(f.player.motionX, .24) && near(f.player.motionZ, -.12) && near(f.player.motionY, .36));
            check("Zen attack precedes swing", f.channel.readOutbound() instanceof C02PacketUseEntity && f.channel.readOutbound() instanceof C0APacketAnimation);
            f.tick(); check("sprint state check consumes failed attempt without attacking", f.controller.attacks == 1);
            for (int i = 0; i < 3; i++) { f.player.sprinting = true; f.tick(); }
            f.player.sprinting = true; f.tick(); check("default sequence has five attempts", f.controller.attacks == 4);
            check("S12 fields are never scaled", v.getMotionX() == 3200 && v.getMotionY() == 2880 && v.getMotionZ() == -1600);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false;
            S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            S32PacketConfirmTransaction ack = new S32PacketConfirmTransaction(); f.receive(ack);
            S06PacketUpdateHealth health = new S06PacketUpdateHealth(); f.receive(health);
            check("NoXZ holds initial velocity and transactions but permits health", f.applied.size() == 1 && f.applied.get(0) == health);
            C03PacketPlayer first = new C03PacketPlayer(1), second = new C03PacketPlayer(2);
            ChannelPromise a = f.channel.newPromise(), b = f.channel.newPromise();
            f.channel.pipeline().write(first, a); f.channel.pipeline().write(second, b); f.channel.pipeline().flush();
            check("NoXZ really holds outgoing movement and promises", f.channel.readOutbound() == null && !a.isDone() && !b.isDone());
            for (int i = 0; i < 11; i++) f.tick();
            check("NoXZ does not release before timeout", !f.applied.contains(v));
            f.tick();
            check("NoXZ twelve tick timeout replays originals in order", f.applied.size() == 3 && f.applied.get(1) == v && f.applied.get(2) == ack && near(f.player.motionY, .36));
            check("movement FIFO and promises completed on release", f.channel.readOutbound() == first && f.channel.readOutbound() == second && a.isSuccess() && b.isSuccess());
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            S32PacketConfirmTransaction ack = new S32PacketConfirmTransaction(); f.receive(ack);
            f.player.onGround = true; f.start();
            check("landing applies knockback then attacks before delayed entity state", f.applied.size() == 1 && f.applied.get(0) == v && f.controller.attacks == 1 && near(f.player.motionX, .24));
            f.input(); f.end(); check("next motion phase flushes remaining queue", f.applied.get(1) == ack);
        }
        try (Fixture f = new Fixture(0)) {
            f.mc.objectMouseOver = null; f.receive(f.velocity()); f.tick();
            check("ground without target releases unchanged", near(f.player.motionX, .4) && f.controller.attacks == 0);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity());
            S19PacketEntityStatus status = new S19PacketEntityStatus();
            S0BPacketAnimation other = new S0BPacketAnimation(8), self = new S0BPacketAnimation(7);
            f.receive(status); f.receive(other); f.receive(self);
            check("NoXZ permits hurt status and other entity animation only", f.applied.size() == 2
                    && f.applied.get(0) == status && f.applied.get(1) == other);
            for (int i = 0; i < 12; i++) f.start();
            check("NoXZ timeout applies knockback before motion queue flush", f.applied.size() == 3 && !f.applied.contains(self));
            f.input(); f.end(); check("NoXZ motion phase releases self animation", f.applied.get(3) == self);
        }
        try (Fixture f = new Fixture(0)) {
            f.mc.objectMouseOver = null; f.receive(f.velocity());
            f.input(); check("NoXZ positive velocity requests a sprint jump", f.player.movementInput.jump);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.jumpBoost = true; f.receive(f.velocity()); f.input();
            check("NoXZ jump boost effect prevents forced jump", !f.player.movementInput.jump);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity()); S08PacketPlayerPosLook correction = new S08PacketPlayerPosLook(); f.receive(correction);
            check("correction releases pending velocity before correction", f.applied.size() == 2 && f.applied.get(1) == correction);
            f.player.onGround = true; f.receive(f.velocity()); f.tick(); check("correction cooldown blocks attack", f.controller.attacks == 0);
            f.tick(); f.receive(f.velocity()); f.tick(); check("two tick correction cooldown recovers", f.controller.attacks == 1);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; AttackReduceFeature.setInstantAttack(true); f.mc.timer.timerSpeed = 1.25F;
            f.receive(f.velocity()); f.tick(); check("instant attack half speed is wired to actual timer", near(f.mc.timer.timerSpeed, .5));
            for (int i = 0; i < 5; i++) f.tick();
            f.tick(); check("instant attack stays slowed after progress cap until release", near(f.mc.timer.timerSpeed, .5));
            f.player.onGround = true; f.start(); check("instant attack landing uses four speed", near(f.mc.timer.timerSpeed, 4));
            f.end();
            for (int i = 0; i < 3; i++) { f.player.sprinting = true; f.tick(); }
            check("instant attack consumes accumulated attacks and restores prior speed", f.controller.attacks == 3 && near(f.mc.timer.timerSpeed, 1.25));
        }
        try (Fixture f = new Fixture(0)) {
            AURA.setEnabled(true); field(OpenMaverFeatures.class, "killAuraTarget", f.target);
            f.mc.objectMouseOver = null; f.receive(f.velocity()); f.tick();
            check("NoXZ uses Aura target without legacy aim-ready gate", f.controller.attacks == 1);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.water = true; f.player.onGround = false; f.receive(f.velocity());
            check("water bypasses queue", near(f.player.motionX, .4) && !AttackReduceFeature.current().isSuspending);
            f.player.water = false; f.player.isInWeb = true; f.receive(f.velocity());
            check("web bypasses queue", f.applied.size() == 2);
        }
        try (Fixture f = new Fixture(0)) {
            f.receive(new S12PacketEntityVelocity(8, .4, .36, -.2)); f.tick();
            check("other entity packet never arms local attack", f.controller.attacks == 0 && near(f.player.motionX, 0));
            f.receive(new S12PacketEntityVelocity(7, .4, -.1, -.2)); f.tick();
            check("nonpositive vertical packet does not arm NoXZ", f.controller.attacks == 0 && near(f.player.motionY, -.1));
            AttackReduceFeature.setAttackAmount(1); f.player.sprinting = true;
            f.receive(new S12PacketEntityVelocity(7, 0, .36, 0)); f.tick();
            check("Zen positive-Y trigger also handles vertical-only motion", f.controller.attacks == 1 && near(f.player.motionY, .36));
        }
    }
    static void jumpReset() {
        try (Fixture f = new Fixture(1)) {
            S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            S32PacketConfirmTransaction ack = new S32PacketConfirmTransaction(); f.receive(ack);
            S02PacketChat chat = new S02PacketChat(); S03PacketTimeUpdate time = new S03PacketTimeUpdate(); f.receive(chat); f.receive(time);
            check("Jump Reset bypasses chat/time while delaying other packets", f.applied.size() == 2 && f.applied.get(0) == chat && f.applied.get(1) == time);
            for (int i = 0; i < 10; i++) f.tick();
            check("Jump Reset ten-count has not yet released", !f.applied.contains(v));
            f.tick(); check("Jump Reset ground expiry replays FIFO and jumps", f.applied.get(2) == v && f.applied.get(3) == ack && f.player.movementInput.jump && near(f.player.motionX, .4));
            f.tick(); check("Jump Reset jump lasts one input update", !f.player.movementInput.jump && f.controller.attacks == 0);
        }
        try (Fixture f = new Fixture(1)) {
            f.player.onGround = false; S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            for (int i = 0; i < 20; i++) f.tick(); check("air delay includes twenty countdown ticks", !f.applied.contains(v));
            f.tick(); check("air timeout replays velocity", f.applied.contains(v));
        }
        try (Fixture f = new Fixture(1)) {
            f.player.onGround = false; f.receive(f.velocity()); f.player.onGround = true; f.tick();
            check("air branch releases early on landing without forcing jump", f.applied.size() == 1 && !f.player.movementInput.jump);
        }
        try (Fixture f = new Fixture(1)) {
            AttackReduceFeature.setRotate(true); AttackReduceFeature.setFollowDirection(true);
            f.player.onGround = false; f.player.rotationYaw = 25; f.receive(f.velocity());
            f.start(); f.input();
            check("silent rotation and follow-direction applied before movement", near(f.player.rotationYaw, 63.4349488) && f.player.movementInput.moveForward == 1 && f.player.movementInput.moveStrafe == 0);
            f.player.rotationYaw += 3; f.end(); check("camera restored preserving mouse delta", near(f.player.rotationYaw, 28));
            f.player.hurtTime = 0; f.tick(); check("rotation clears after hurt time", AttackReduceFeature.rotationYaw == null);
        }
        try (Fixture f = new Fixture(1)) {
            AttackReduceFeature.setRotate(true); f.player.onGround = false; f.player.rotationYaw = 25;
            f.receive(f.velocity()); f.start(); f.input(); f.player.rotationYaw += 3; f.input(); f.end();
            check("repeated input callbacks preserve camera plus mouse delta", near(f.player.rotationYaw, 28));
            f.start(); f.player.movementInput.sneak = true; f.player.movementInput.moveForward = .3F;
            f.player.movementInput.moveStrafe = 0; AttackReduceFeature.afterMovementInput(f.player.movementInput);
            check("silent MoveFix retains sneaking movement", near(f.player.movementInput.moveForward, .3));
            f.end();
        }
    }
    static void mix() throws Exception {
        try (Fixture f = new Fixture(2)) {
            f.player.onGround = false; S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            S15PacketEntityRelMove move = new S15PacketEntityRelMove(); S32PacketConfirmTransaction ack = new S32PacketConfirmTransaction();
            S18PacketEntityTeleport teleport = new S18PacketEntityTeleport(); S00PacketKeepAlive keepalive = new S00PacketKeepAlive();
            f.receive(move); f.receive(ack); f.receive(teleport); f.receive(keepalive);
            check("Mix queues entity movement / transaction / teleport only", f.applied.size() == 1 && f.applied.get(0) == keepalive);
            for (int i = 0; i < 23; i++) f.tick(); check("Mix waits through twenty-three air ticks", !f.applied.contains(v));
            f.tick(); check("Mix twenty-four air ticks flushes FIFO", f.applied.size() == 5 && f.applied.get(1) == v && f.applied.get(2) == move && f.applied.get(3) == ack && f.applied.get(4) == teleport);
            check("Mix hurt-time auto jump active", f.player.movementInput.jump);
        }
        try (Fixture f = new Fixture(2)) {
            AttackReduceFeature.setMovementOverride(true); f.receive(f.velocity()); f.start(); f.input();
            check("Mix overrides movement against knockback and jumps", f.player.movementInput.moveForward == 0 && f.player.movementInput.moveStrafe == -1 && f.player.movementInput.jump);
            f.end(); for (int i = 0; i < 3; i++) f.tick();
            check("Mix releases overridden movement after three ticks", f.player.movementInput.moveForward == 0 && f.player.movementInput.moveStrafe == 0 && !f.player.movementInput.jump);
        }
        try (Fixture f = new Fixture(2)) {
            AttackReduceFeature.setTryAttack(true); AURA.setEnabled(true);
            field(OpenMaverFeatures.class, "killAuraTarget", f.target);
            f.player.motionX = .4; f.player.motionZ = -.2; f.player.motionY = .1; f.receive(f.velocity());
            f.player.sprinting = false; f.start();
            check("Mix Try Attack invokes two Aura-target attacks with captured sprint", f.controller.attacks == 2
                    && !f.controller.sawSprinting && near(f.player.motionX, .144) && near(f.player.motionZ, -.072) && near(f.player.motionY, .1));
            f.input(); f.end();
        }
        try (Fixture f = new Fixture(2)) {
            AttackReduceFeature.setTryAttack(true); AURA.setEnabled(true); field(OpenMaverFeatures.class, "killAuraTarget", f.target);
            f.player.motionX = .4; f.player.sprinting = false; f.receive(f.velocity()); f.player.sprinting = true; f.start();
            check("Mix nonsprint receipt skips Keep Sprint scaling on both attacks", f.controller.attacks == 2 && near(f.player.motionX, .4));
        }
    }
    static void lifecycle() throws Exception {
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity());
            AttackReduceFeature.setMode(1); f.channel.runPendingTasks();
            check("mode switch replays pending original instead of dropping it", f.applied.size() == 1 && near(f.player.motionX, .4));
            f.start(); check("mode switch reattaches hook", AttackReduceFeature.isPacketHookInstalled());
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; AttackReduceFeature.setInstantAttack(true); f.receive(f.velocity()); f.tick();
            MODULE.setEnabled(false); f.tick();
            check("disable flushes pending velocity and restores timer", f.applied.size() == 1 && near(f.mc.timer.timerSpeed, 1) && !AttackReduceFeature.isPacketHookInstalled());
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity()); f.mc.theWorld = new Object(); f.tick();
            check("new world drops old world queue", f.applied.isEmpty() && near(f.player.motionX, 0));
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity());
            ChannelPromise promise = f.channel.newPromise(); f.channel.pipeline().write(new C03PacketPlayer(1), promise);
            f.channel.close(); f.channel.runPendingTasks(); f.drain();
            check("disconnect fails held promise instead of leaving it pending", promise.isDone() && !promise.isSuccess());
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity());
            ChannelPromise cancelled = f.channel.newPromise(), next = f.channel.newPromise();
            f.channel.pipeline().write(new C03PacketPlayer(1), cancelled); cancelled.cancel(false);
            C03PacketPlayer following = new C03PacketPlayer(2); f.channel.pipeline().write(following, next);
            MODULE.setEnabled(false); f.tick();
            check("cancelled movement promise does not block remaining FIFO", cancelled.isCancelled() && next.isSuccess()
                    && f.channel.readOutbound() == following && f.channel.readOutbound() == null);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity());
            ChannelPromise last = null;
            for (int i = 0; i < AttackReduceFeature.MAX_PENDING; i++) {
                last = f.channel.newPromise(); f.channel.pipeline().write(new C03PacketPlayer(i), last);
            }
            f.drain(); int count = 0; while (f.channel.readOutbound() != null) count++;
            check("outgoing queue cap releases all moves and pending knockback", count == AttackReduceFeature.MAX_PENDING
                    && last.isSuccess() && f.applied.size() == 1 && !AttackReduceFeature.current().isSuspending);
        }
        try (Fixture f = new Fixture(1)) {
            f.receive(f.velocity()); f.channel.pipeline().remove(AttackReducePacketHook.NAME); f.start();
            check("external hook removal releases queue and heals hook", f.applied.size() == 1 && AttackReduceFeature.isPacketHookInstalled());
        }
        try (Fixture f = new Fixture(1)) {
            f.receive(f.velocity()); S07PacketRespawn respawn = new S07PacketRespawn(); f.receive(respawn);
            check("respawn flushes earlier packets before respawn", f.applied.size() == 2 && f.applied.get(1) == respawn);
        }
        try (Fixture f = new Fixture(1)) {
            final java.util.function.Consumer<Object> handler = f.player.sendQueue.inbound;
            f.player.sendQueue.inbound = p -> { handler.accept(p); if (p instanceof S07PacketRespawn) f.mc.theWorld = new Object(); };
            S07PacketRespawn respawn = new S07PacketRespawn(); S00PacketKeepAlive following = new S00PacketKeepAlive();
            f.channel.writeInbound(respawn); f.channel.writeInbound(following); f.drain();
            check("same-connection respawn preserves packets already queued for new world", f.applied.size() == 2 && f.applied.get(1) == following);
            f.start(); f.receive(new S00PacketKeepAlive()); check("respawn hook rebinds to new world", f.applied.size() == 3 && AttackReduceFeature.isPacketHookInstalled());
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity());
            for (int i = 0; i < AttackReduceFeature.MAX_PENDING; i++) f.receive(new S32PacketConfirmTransaction());
            check("packet count cap forces release without losing packets", f.applied.size() == AttackReduceFeature.MAX_PENDING + 1 && !AttackReduceFeature.current().isSuspending);
        }
    }
    static void threadIsolation() throws Exception {
        try (Fixture f = new Fixture(0)) {
            Thread worker = new Thread(() -> f.channel.writeInbound(f.velocity())); worker.start(); worker.join();
            check("Netty receipt does not touch player motion", f.applied.isEmpty() && f.mc.tasks.size() == 1 && near(f.player.motionX, 0));
            f.drain(); check("SRG processPacket runs on main thread", near(f.player.motionX, .4));
        }
        try (Fixture f = new Fixture(1)) {
            f.channel.writeInbound(f.velocity()); AttackReduceFeature.setMode(2); f.drain();
            check("queued main-thread task survives mode switch exactly once", f.applied.size() == 1 && near(f.player.motionX, .4));
        }
        try (Fixture f = new Fixture(1)) {
            f.channel.writeInbound(f.velocity()); f.mc.theWorld = new Object(); f.drain();
            check("queued main-thread task cannot mutate new world", f.applied.isEmpty());
        }
    }
    static void field(Class<?> type, String name, Object value) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(null, value); }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1.0E-5; }
    static void check(String label, boolean condition) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
}
