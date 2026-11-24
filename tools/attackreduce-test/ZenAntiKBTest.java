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
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
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
        public boolean onGround = true, sprinting = true, serverSprintState = true, isInWeb, water, lava, burning, ladder, sleeping, jumpBoost, alive = true;
        public boolean usingItem, swingFails, isAirBorne;
        public int hurtTime = 10, jumpCalls;
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
        public boolean isUsingItem() { return usingItem; }
        // 1.8.9 EntityLivingBase.jump：原版方法本身没有 onGround 限制。
        protected void func_70664_aZ() {
            jumpCalls++;
            motionY = (double) .42F + (jumpBoost ? (double) .1F : 0);
            if (sprinting) {
                float yaw = rotationYaw * .017453292F;
                motionX -= (double) ((float) Math.sin(yaw) * .2F);
                motionZ += (double) ((float) Math.cos(yaw) * .2F);
            }
            isAirBorne = true;
        }
        public void swingItem() {
            if (swingFails) throw new IllegalStateException("simulated swing failure");
            channel.writeOutbound(new C0APacketAnimation());
        }
    }
    public static class Target extends EntityLivingBase {
        public boolean alive = true;
        public boolean acceptsDamage = true;
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
            // 来自真实 1.8.9 EntityPlayer.attackTargetEntityWithCurrentItem：
            // 仅当伤害被接受且 knockback > 0 时减速、结束疾跑。
            if (p.sprinting && ((Target) target).acceptsDamage) {
                p.motionX *= .6; p.motionZ *= .6; p.sprinting = false;
            }
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
                if (p instanceof S32PacketConfirmTransaction) channel.writeOutbound(new C0FPacketConfirmTransaction());
            };
            mc.thePlayer = player; mc.theWorld = world; mc.playerController = controller;
            hit.typeOfHit = MovingObjectPosition.MovingObjectType.ENTITY; hit.entityHit = target; mc.objectMouseOver = hit;
            AttackReduceFeature.setMode(mode); start();
        }
        void start() {
            drain(); OpenMaverFeatures.beginAttackTick(); LegacySoundFeature.onTick(mc);
            AttackReduceFeature.beforeTick(mc); AttackReduceFeature.onTickStart(mc); channel.runPendingTasks();
        }
        void input() {
            player.movementInput.moveForward = 0; player.movementInput.moveStrafe = 0;
            player.movementInput.jump = false;
            AttackReduceFeature.afterMovementInput(player.movementInput);
        }
        void end() { AttackReduceFeature.onTickEnd(mc); channel.runPendingTasks(); }
        void tick() { start(); input(); end(); }
        void drain() { Runnable task; while ((task = mc.tasks.poll()) != null) task.run(); channel.runPendingTasks(); }
        void receive(Object packet) { channel.writeInbound(packet); drain(); }
        void holdOutbound() { AttackReduceFeature.holdMoves(); channel.runPendingTasks(); }
        S12PacketEntityVelocity velocity() { return new S12PacketEntityVelocity(7, .4, .36, -.2); }
        public void close() {
            MODULE.setEnabled(false); AttackReduceFeature.beforeTick(mc); channel.runPendingTasks();
            mc.thePlayer = null; LegacySoundFeature.onTick(mc); channel.finish();
        }
    }

    public static void main(String[] args) throws Exception {
        ModuleManager.register(MODULE); ModuleManager.register(AURA);
        settings(); noXZ(); jumpReset(); mix(); lifecycle(); threadIsolation(); debugChat(); Protocol18Test.run();
        keepSprintCoexistence();
        System.out.println("ZenAntiKBTest: " + passed + " passed, 0 failed");
    }

    /** Exercise both real feature callbacks, including modes that write unchanged values. */
    static void keepSprintCoexistence() throws Exception {
        module.movement.KeepSprint sprint = new module.movement.KeepSprint();
        ModuleManager.register(sprint);
        KeepSprintFeature.load(new Properties());
        sprint.setEnabled(true);
        try {
            for (int mode = 0; mode < 3; mode++) {
                try (Fixture f = new Fixture(mode)) {
                    Minecraft.instance = f.mc;
                    f.player.movementInput = new ScaffoldMovementInput(f.mc.gameSettings);
                    f.mc.objectMouseOver = null;
                    f.player.hurtTime = 0;
                    f.start();
                    coexistInput(f, -1, 0, false);
                    check("idle Velocity permits KeepSprint in mode " + mode,
                            "ROTATE".equals(KeepSprintFeature.getStatus())
                                    && near(Math.abs(f.player.rotationYaw), 180)
                                    && !AttackReduceFeature.ownsMovementInput(f.player, f.player.movementInput));
                    coexistEnd(f);
                    check("idle coexistence restores camera " + mode, near(f.player.rotationYaw, 0));

                    // Mix uses actual silent rotation alone; NoXZ / JumpReset own input pulses.
                    if (mode == AttackReduceFeature.MODE_MIX) {
                        AttackReduceFeature.setRotate(true);
                        f.player.onGround = false;
                    }
                    f.receive(f.velocity());
                    f.start();
                    coexistInput(f, 1, 0, true);
                    check("active Velocity owns this input in mode " + mode,
                            AttackReduceFeature.ownsMovementInput(f.player, f.player.movementInput));
                    check("KeepSprint yields only during actual Velocity work " + mode,
                            "YIELD VELOCITY".equals(KeepSprintFeature.getStatus()));
                    check("claim is tied to the input object " + mode,
                            !AttackReduceFeature.ownsMovementInput(f.player, new MovementInput()));
                    check("claim is tied to the local player " + mode,
                            !AttackReduceFeature.ownsMovementInput(new Player(), f.player.movementInput));
                    if (mode == AttackReduceFeature.MODE_JUMP_RESET) {
                        check("unchanged forward/jump values still count as intentional ownership",
                                f.player.movementInput.moveForward == 1
                                        && f.player.movementInput.moveStrafe == 0
                                        && f.player.movementInput.jump && near(f.player.rotationYaw, 0));
                    }
                    if (mode == AttackReduceFeature.MODE_MIX) {
                        check("KeepSprint leaves Velocity rotation in place until native C03",
                                near(f.player.rotationYaw, 63.4349488));
                    }
                    coexistEnd(f);
                    check("END clears Velocity input claim " + mode,
                            !AttackReduceFeature.ownsMovementInput(f.player, f.player.movementInput));
                    f.player.onGround = true;
                    for (int i = 0; i < 12; i++) {
                        f.start();
                        check("new tick starts without a stale input claim " + mode,
                                !AttackReduceFeature.ownsMovementInput(f.player, f.player.movementInput));
                        coexistInput(f, -1, 0, false);
                        coexistEnd(f);
                    }
                    check("KeepSprint resumes automatically after Velocity pulse " + mode,
                            "ROTATE".equals(KeepSprintFeature.getStatus()));
                    MODULE.setEnabled(false);
                    f.start();
                    check("disabled Velocity cannot retain ownership " + mode,
                            !AttackReduceFeature.ownsMovementInput(f.player, f.player.movementInput));
                } finally {
                    KeepSprintFeature.reset();
                    Minecraft.instance = null;
                }
            }
        } finally {
            sprint.setEnabled(false);
            KeepSprintFeature.reset();
        }
    }

    static void coexistInput(Fixture f, float forward, float strafe, boolean jump) {
        KeepSprintFeature.onPlayerTickStart(f.mc, f.player);
        f.player.movementInput.moveForward = forward;
        f.player.movementInput.moveStrafe = strafe;
        f.player.movementInput.jump = jump;
        AttackReduceFeature.afterMovementInput(f.player.movementInput);
        KeepSprintFeature.afterMovementInput(f.player.movementInput);
        KeepSprintFeature.onPlayerTickEnd(f.player);
    }

    static void coexistEnd(Fixture f) {
        KeepSprintFeature.onTickEnd(f.mc);
        f.end();
    }
    static void settings() throws Exception {
        Properties old = new Properties(); old.setProperty("velocity.mode", "2"); old.setProperty("velocity.horizontal", "0");
        AttackReduceFeature.load(old);
        check("old numeric mode uses JumpReset default", AttackReduceFeature.getMode() == AttackReduceFeature.MODE_JUMP_RESET);
        check("three modes with JumpReset default", AttackReduceFeature.MODE_NAMES.length == 3 && "JumpReset".equals(AttackReduceFeature.getSummary()));
        check("Zen default settings", AttackReduceFeature.getAttackAmount() == 5 && AttackReduceFeature.getChance() == 100 && AttackReduceFeature.getAccuracy() == 100
                && AttackReduceFeature.getWaterCheck() && !AttackReduceFeature.getOnlyWhenTargeting()
                && AttackReduceFeature.getSprintStateCheck() && !AttackReduceFeature.getRotate() && !AttackReduceFeature.getInstantAttack());
        AttackReduceFeature.setMode(2); AttackReduceFeature.setAttackAmount(9); AttackReduceFeature.setChance(47); AttackReduceFeature.setAccuracy(63);
        AttackReduceFeature.setRotate(true); AttackReduceFeature.setTryAttack(true); AttackReduceFeature.setMovementOverride(true);
        AttackReduceFeature.setOnlyWhenTargeting(true); AttackReduceFeature.setWaterCheck(false); AttackReduceFeature.setInstantAttack(true); AttackReduceFeature.setSprintStateCheck(false);
        Properties saved = new Properties(); saved.load(new StringReader(AttackReduceFeature.save())); AttackReduceFeature.load(saved);
        check("all Zen settings roundtrip", AttackReduceFeature.getMode() == 2 && AttackReduceFeature.getAttackAmount() == 9
                && AttackReduceFeature.getChance() == 47 && AttackReduceFeature.getAccuracy() == 63 && AttackReduceFeature.getRotate() && AttackReduceFeature.getTryAttack()
                && AttackReduceFeature.getMovementOverride() && AttackReduceFeature.getOnlyWhenTargeting() && !AttackReduceFeature.getWaterCheck()
                && AttackReduceFeature.getInstantAttack() && !AttackReduceFeature.getSprintStateCheck());
        saved.setProperty("velocity.zen.attackAmount", "999"); saved.setProperty("velocity.zen.chance", "-1"); saved.setProperty("velocity.zen.accuracy", "999");
        AttackReduceFeature.load(saved); check("integer settings clamped", AttackReduceFeature.getAttackAmount() == 20 && AttackReduceFeature.getChance() == 0 && AttackReduceFeature.getAccuracy() == 100);
        saved.setProperty("velocity.zen.mode", "bad"); saved.setProperty("velocity.zen.attackAmount", "NaN");
        AttackReduceFeature.load(saved); check("invalid settings use Zen defaults", AttackReduceFeature.getMode() == AttackReduceFeature.MODE_JUMP_RESET && AttackReduceFeature.getAttackAmount() == 5);
        Properties debug = new Properties(); debug.setProperty("velocity.zen.debug", "true");
        AttackReduceFeature.load(debug);
        check("debug chat option loads", AttackReduceFeature.getDebugChat());
        check("debug chat option saves", AttackReduceFeature.save().contains("velocity.zen.debug=true"));
        AttackReduceFeature.setDebugChat(false);
        check("debug chat option clears", !AttackReduceFeature.getDebugChat());
        for (String name : AttackReduceFeature.MODE_NAMES) {
            Properties p = new Properties(); p.setProperty("velocity.zen.mode", name); AttackReduceFeature.load(p);
            check("explicit saved mode is preserved: " + name, name.equals(AttackReduceFeature.getSummary()));
        }
        for (String name : new String[] {"Sprint Reset", "Jump Reset", "jumpreset"}) {
            Properties legacy = new Properties(); legacy.setProperty("velocity.zen.mode", name);
            legacy.setProperty("velocity.zen.followDirection", "true"); legacy.setProperty("velocity.zen.rotateTicks", "20");
            AttackReduceFeature.load(legacy);
            check("legacy reset mode normalizes to JumpReset: " + name, AttackReduceFeature.getMode() == AttackReduceFeature.MODE_JUMP_RESET
                    && AttackReduceFeature.save().startsWith("velocity.zen.mode=JumpReset\n")
                    && !AttackReduceFeature.save().contains("followDirection") && !AttackReduceFeature.save().contains("rotateTicks"));
        }
        AttackReduceFeature.setMode(3);
        check("removed mode index falls back to JumpReset", AttackReduceFeature.getMode() == AttackReduceFeature.MODE_JUMP_RESET);
    }
    static boolean has(List<String> lines, String needle) {
        for (String line : lines) if (line.contains(needle)) return true;
        return false;
    }
    static boolean hasOut(List<String> lines, String needle) {
        for (String line : lines) if (line.startsWith("OUT ") && line.contains(needle)) return true;
        return false;
    }
    /** NoXZ 调试通道必须说明立即直通，不再产生挂起/回放文本。 */
    static void debugChat() throws Exception {
        try (Fixture f = new Fixture(0)) {
            AttackReduceFeature.setDebugChat(true); VelocityDebug.drain();
            check("debug chat toggles VelocityDebug", VelocityDebug.isEnabled());
            f.player.onGround = false;
            S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            C03PacketPlayer move = new C03PacketPlayer(2);
            f.channel.pipeline().write(move, f.channel.newPromise()); f.channel.pipeline().flush();
            f.channel.runPendingTasks();
            List<String> first = VelocityDebug.history();
            check("debug prints immediate airborne knockback and timer", has(first, "KB in PASS AIR")
                    && has(first, "落点") && has(first, "timer=1.00"));
            check("debug prints outgoing sent packet", has(first, "OUT SEND"));
            check("debug omits bogus coordinates for positionless C03", !hasOut(first, "(0.00, 0.00, 0.00)"));
            for (int i = 0; i < 12; i++) f.tick();
            List<String> after = VelocityDebug.history();
            check("NoXZ debug never claims suspension or replay", !has(after, "SUSPEND")
                    && !has(after, "KB release") && !has(after, "OUT HOLD") && !has(after, "FLUSH"));
            f.player.onGround = true; f.player.sprinting = false; f.receive(f.velocity());
            check("debug preserves grounded not-sprinting reason", has(VelocityDebug.history(), "PASS NOT SPRINTING"));
        } finally {
            AttackReduceFeature.setDebugChat(false); VelocityDebug.drain();
            check("debug chat off clears queue", !VelocityDebug.isEnabled() && VelocityDebug.drain().isEmpty());
        }
    }
    static void noXZ() throws Exception {
        try (Fixture f = new Fixture(0)) {
            check("hook installed before packet_handler", AttackReduceFeature.isPacketHookInstalled()
                    && f.channel.pipeline().names().indexOf(AttackReducePacketHook.NAME) < f.channel.pipeline().names().indexOf("packet_handler"));
            S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            check("NoXZ ground applies original velocity before attacking", f.applied.size() == 1 && f.applied.get(0) == v && near(f.player.motionX, .4) && f.controller.attacks == 0);
            f.tick();
            check("native sprint attack performs reduction exactly once", f.controller.attacks == 1 && f.controller.sawSprinting
                    && near(f.player.motionX, .24) && near(f.player.motionZ, -.12) && near(f.player.motionY, .36));
            check("1.8 swing precedes attack", f.channel.readOutbound() instanceof C0APacketAnimation && f.channel.readOutbound() instanceof C02PacketUseEntity);
            f.tick(); check("sprint state check consumes failed attempt without attacking", f.controller.attacks == 1);
            for (int i = 0; i < 3; i++) { f.player.sprinting = true; f.tick(); }
            f.player.sprinting = true; f.tick(); check("default sequence has five attempts", f.controller.attacks == 4);
            check("S12 fields are never scaled", v.getMotionX() == 3200 && v.getMotionY() == 2880 && v.getMotionZ() == -1600);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.sprinting = false;
            S12PacketEntityVelocity pass = f.velocity(); f.receive(pass);
            check("grounded knockback that cannot attack passes straight through", f.applied.size() == 1 && f.applied.get(0) == pass);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false;
            S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            S32PacketConfirmTransaction ack = new S32PacketConfirmTransaction(); f.receive(ack);
            S06PacketUpdateHealth health = new S06PacketUpdateHealth(); f.receive(health);
            check("airborne NoXZ immediately applies velocity and subsequent packets in order", f.applied.size() == 3
                    && f.applied.get(0) == v && f.applied.get(1) == ack && f.applied.get(2) == health
                    && near(f.player.motionX, .4) && near(f.player.motionY, .36));
            check("airborne transaction is acknowledged immediately", f.channel.readOutbound() instanceof C0FPacketConfirmTransaction);
            C03PacketPlayer first = new C03PacketPlayer(1), second = new C03PacketPlayer(2);
            ChannelPromise a = f.channel.newPromise(), b = f.channel.newPromise();
            f.channel.pipeline().write(first, a); f.channel.pipeline().write(second, b); f.channel.pipeline().flush();
            check("airborne movement and promises pass immediately", f.channel.readOutbound() == first
                    && f.channel.readOutbound() == second && a.isSuccess() && b.isSuccess());
            for (int i = 0; i < 20; i++) {
                f.tick();
                C03PacketPlayer move = new C03PacketPlayer(i + 3);
                ChannelPromise promise = f.channel.newPromise(); f.channel.pipeline().write(move, promise); f.channel.pipeline().flush();
                check("airborne tick " + i + " sends exactly its current move", f.channel.readOutbound() == move
                        && f.channel.readOutbound() == null && promise.isSuccess());
                check("airborne tick " + i + " retains no packets or attacks", AttackReduceFeature.current().pendingCount() == 0
                        && !AttackReduceFeature.current().isSuspending && AttackReduceFeature.queuedMoves() == 0
                        && f.controller.attacks == 0);
            }
            check("airborne timeout never reapplies velocity", f.applied.size() == 3 && near(f.player.motionY, .36));
            f.player.onGround = true; f.tick();
            check("expired landing opportunity does not attack later", f.controller.attacks == 0);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            S32PacketConfirmTransaction ack = new S32PacketConfirmTransaction(); f.receive(ack);
            f.tick(); check("airborne landing intent does not attack early", f.controller.attacks == 0);
            // 让正常物理已更新的速度区别于 S12，落地时不得重新覆盖。
            f.player.motionX = .2; f.player.motionY = -.1; f.player.onGround = true; f.start();
            check("landing attacks current physics without reapplying velocity", f.applied.size() == 2 && f.applied.get(0) == v
                    && f.applied.get(1) == ack && f.controller.attacks == 1 && near(f.player.motionX, .12) && near(f.player.motionY, -.1));
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
            check("NoXZ passes hurt status and all animations immediately", f.applied.size() == 4
                    && f.applied.get(1) == status && f.applied.get(2) == other && f.applied.get(3) == self);
            for (int i = 0; i < 12; i++) f.start();
            check("NoXZ timeout never replays earlier packets", f.applied.size() == 4);
            f.input(); f.end(); f.start(); check("NoXZ replay is not repeated by motion callbacks", f.applied.size() == 4);
        }
        try (Fixture f = new Fixture(0)) {
            f.mc.objectMouseOver = null; f.receive(f.velocity());
            f.input(); check("NoXZ no-target hit neither jumps nor overwrites packet Y", !f.player.movementInput.jump && near(f.player.motionY, .36));
        }
        try (Fixture f = new Fixture(0)) {
            f.player.jumpBoost = true; f.receive(f.velocity()); f.input();
            check("NoXZ jump boost does not add a jump impulse", !f.player.movementInput.jump && near(f.player.motionY, .36));
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity()); S08PacketPlayerPosLook correction = new S08PacketPlayerPosLook(); f.receive(correction);
            check("correction releases pending velocity before correction", f.applied.size() == 2 && f.applied.get(1) == correction);
            f.player.onGround = true; f.receive(f.velocity()); f.tick(); check("correction cooldown blocks attack", f.controller.attacks == 0);
            f.tick(); f.receive(f.velocity()); f.tick(); check("two tick correction cooldown recovers", f.controller.attacks == 1);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; AttackReduceFeature.setInstantAttack(true); f.mc.timer.timerSpeed = 1.25F;
            f.receive(f.velocity()); f.tick(); check("instant attack never touches the tick timer", near(f.mc.timer.timerSpeed, 1.25));
            for (int i = 0; i < 5; i++) f.tick();
            f.player.onGround = true; f.start();
            check("instant attack landing keeps the original timer", near(f.mc.timer.timerSpeed, 1.25));
            f.end();
            for (int i = 0; i < 3; i++) { f.player.sprinting = true; f.tick(); }
            for (int i = 0; i < 7; i++) { f.player.sprinting = true; f.tick(); }
            check("instant attack caps landing bonus at three normal-tick attacks", f.controller.attacks == 8 && near(f.mc.timer.timerSpeed, 1.25));
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
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity());
            f.receive(new S12PacketEntityVelocity(7, .1, -.1, .2));
            f.player.onGround = true; f.tick();
            check("new nonpositive impulse clears old landing attack and jump", f.controller.attacks == 0
                    && !f.player.movementInput.jump && near(f.player.motionX, .1) && near(f.player.motionY, -.1));
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity()); f.tick();
            f.mc.objectMouseOver = null; f.player.onGround = true; f.tick();
            check("landing without target leaves sprint and velocity untouched", f.controller.attacks == 0
                    && f.player.sprinting && near(f.player.motionX, .4));
            f.mc.objectMouseOver = f.hit; f.tick();
            check("failed landing opportunity does not arm a later target", f.controller.attacks == 0);
        }
        try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; f.receive(f.velocity()); f.player.water = true; f.tick();
            f.player.water = false; f.player.onGround = true; f.tick();
            check("ignored environment clears pending landing attack", f.controller.attacks == 0);
        }
        for (boolean instant : new boolean[] {false, true})
            for (boolean sprint : new boolean[] {false, true})
                for (boolean target : new boolean[] {false, true}) try (Fixture f = new Fixture(0)) {
                    AttackReduceFeature.setInstantAttack(instant); f.player.sprinting = sprint;
                    f.mc.objectMouseOver = target ? f.hit : null; f.player.onGround = false;
                    S12PacketEntityVelocity v = f.velocity(); f.receive(v);
                    check("airborne S12 passes for all Instant/sprint/target settings", f.applied.size() == 1
                            && f.applied.get(0) == v && near(f.player.motionX, .4) && near(f.player.motionY, .36)
                            && !AttackReduceFeature.current().isSuspending && AttackReduceFeature.current().pendingCount() == 0);
                }
    }
    static void jumpReset() throws Exception {
        int mode = AttackReduceFeature.MODE_JUMP_RESET;
        try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; f.player.sprinting = false;
            S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            check("air reset applies original velocity before acting", f.applied.size() == 1 && f.applied.get(0) == v
                    && near(f.player.motionX, .4) && near(f.player.motionY, .36) && near(f.player.motionZ, -.2) && f.player.jumpCalls == 0);
            f.start(); f.input();
            check("air hit uses one native sprint attack and preserves vertical velocity", f.player.jumpCalls == 0
                    && f.controller.attacks == 1 && f.controller.sawSprinting && !f.player.movementInput.jump
                    && f.player.movementInput.moveForward == 1 && !f.player.onGround
                    && near(f.player.motionX, .24) && near(f.player.motionY, .36) && near(f.player.motionZ, -.12));
            f.input(); f.tick();
            check("air reset repeated input and hurt time never repeat attack or jump", f.player.jumpCalls == 0 && f.controller.attacks == 1
                    && !f.player.movementInput.jump && f.player.movementInput.moveForward == 1);
            f.tick();
            check("air reset returns owned sprint and input after two ticks", !f.player.sprinting
                    && !f.player.movementInput.jump && f.player.movementInput.moveForward == 0 && f.player.jumpCalls == 0);
            f.player.onGround = true; f.tick();
            check("completed air reset never jumps on landing", f.player.jumpCalls == 0 && !f.player.movementInput.jump);
        }
        try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            check("air hit is immediate in reset mode " + mode, f.applied.contains(v));
            f.player.onGround = true; f.tick();
            check("air hit cannot arm a delayed landing jump " + mode, !f.player.movementInput.jump && f.player.movementInput.moveForward == 0);
        }
        for (boolean airborne : new boolean[] {false, true}) try (Fixture f = new Fixture(mode)) {
            f.player.onGround = !airborne;
            AttackReduceFeature.setChance(0); f.receive(f.velocity()); f.tick();
            check("zero chance never resets; air=" + airborne, !f.player.movementInput.jump
                    && f.player.movementInput.moveForward == 0 && f.applied.size() == 1 && f.player.jumpCalls == 0);
        }
        try (Fixture f = new Fixture(mode)) {
            AttackReduceFeature.setOnlyWhenTargeting(true); f.mc.objectMouseOver = null;
            f.receive(f.velocity()); f.input();
            check("Only When Targeting rejects missing target " + mode, !f.player.movementInput.jump);
            f.mc.objectMouseOver = f.hit; f.receive(f.velocity()); f.input();
            check("Only When Targeting accepts visible reachable target " + mode, f.player.movementInput.jump);
            f.target.alive = false; f.input();
            check("target death cancels active reset " + mode, !f.player.movementInput.jump && f.player.movementInput.moveForward == 0);
            f.target.alive = true; f.target.box = new AxisAlignedBB(20, 0, 20, 21, 2, 21);
            f.receive(f.velocity()); f.input();
            check("Only When Targeting rejects out-of-range target " + mode, !f.player.movementInput.jump);
        }
        try (Fixture f = new Fixture(mode)) {
            f.mc.objectMouseOver = null; f.receive(f.velocity()); f.input();
            check("target check disabled allows missing target " + mode, f.player.movementInput.jump);
        }
        try (Fixture f = new Fixture(mode)) {
            f.player.water = true; f.receive(f.velocity()); f.input();
            check("Water Check enabled skips reset but keeps velocity " + mode, !f.player.movementInput.jump && near(f.player.motionX, .4));
            AttackReduceFeature.setWaterCheck(false); f.receive(f.velocity()); f.input();
            check("Water Check can be disabled " + mode, f.player.movementInput.jump);
        }
        try (Fixture f = new Fixture(mode)) {
            AttackReduceFeature.setAccuracy(0); f.receive(f.velocity()); f.input();
            check("zero accuracy delays input only " + mode, !f.player.movementInput.jump && f.applied.size() == 1 && near(f.player.motionY, .36));
            int jumps = 0;
            for (int i = 0; i < 4; i++) { f.tick(); if (f.player.movementInput.jump) jumps++; }
            check("inaccurate reset still produces exactly one jump within two ticks " + mode, jumps == 1);
        }
        try (Fixture f = new Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            f.player.sprinting = false; f.player.rotationYaw = 25; f.mc.timer.timerSpeed = .8F;
            S12PacketEntityVelocity v = f.velocity(); f.receive(v); f.input();
            check("JumpReset starts forward sprint plus one jump", f.player.sprinting && f.player.movementInput.jump
                    && f.player.movementInput.moveForward == 1 && f.player.movementInput.moveStrafe == 0);
            S32PacketConfirmTransaction ack = new S32PacketConfirmTransaction(); f.receive(ack);
            S02PacketChat chat = new S02PacketChat(); S03PacketTimeUpdate time = new S03PacketTimeUpdate();
            f.receive(chat); f.receive(time);
            check("JumpReset immediately applies velocity and following packets in FIFO", f.applied.size() == 4
                    && f.applied.get(0) == v && f.applied.get(1) == ack && f.applied.get(2) == chat && f.applied.get(3) == time
                    && !AttackReduceFeature.current().isSuspending && AttackReduceFeature.current().pendingCount() == 0);
            check("JumpReset immediately sends transaction reply", f.channel.readOutbound() instanceof C0FPacketConfirmTransaction);
            check("JumpReset preserves original motion camera and timer", near(f.player.motionX, .4) && near(f.player.motionY, .36)
                    && near(f.player.motionZ, -.2) && near(f.player.rotationYaw, 25) && near(f.mc.timer.timerSpeed, .8));
            C03PacketPlayer move = new C03PacketPlayer(1); f.channel.writeOutbound(move);
            check("JumpReset does not hold outbound movement", f.channel.readOutbound() == move && AttackReduceFeature.queuedMoves() == 0);
            f.input(); check("repeated callbacks do not repeat jump or consume sprint ticks", !f.player.movementInput.jump && f.player.movementInput.moveForward == 1);
            f.tick(); check("forward sprint lasts second tick without jump", f.player.sprinting && !f.player.movementInput.jump && f.player.movementInput.moveForward == 1);
            f.tick(); check("pulse expires after two ticks and returns sprint and input", !f.player.sprinting && !f.player.movementInput.jump && f.player.movementInput.moveForward == 0);
            for (int i = 0; i < 24; i++) f.tick();
            check("JumpReset never repeats while hurt time stays positive", !f.player.movementInput.jump && !f.player.sprinting
                    && f.player.movementInput.moveForward == 0 && f.controller.attacks == 0 && f.applied.size() == 4 && near(f.player.motionX, .4));
        }
        try (Fixture f = new Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            f.receive(f.velocity()); f.input(); f.tick(); f.tick();
            check("pre-existing sprint remains active after reset", f.player.sprinting);
        }
        try (Fixture f = new Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            f.player.sprinting = false; f.receive(f.velocity());
            f.player.movementInput.moveForward = -1; f.player.movementInput.moveStrafe = 1; f.player.movementInput.jump = true;
            AttackReduceFeature.afterMovementInput(f.player.movementInput);
            AttackReduceFeature.setMode(AttackReduceFeature.MODE_NOXZ);
            check("cleanup returns original movement and held manual jump", f.player.movementInput.moveForward == -1
                    && f.player.movementInput.moveStrafe == 1 && f.player.movementInput.jump && !f.player.sprinting);
        }
        try (Fixture f = new Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            AttackReduceFeature.setAccuracy(0); f.receive(f.velocity()); f.player.onGround = false; f.tick();
            f.tick();
            check("ground hit that leaves ground before delayed input uses air attack", f.player.jumpCalls == 0
                    && f.controller.attacks == 1 && near(f.player.motionY, .36));
            f.player.onGround = true; f.tick(); f.tick();
            check("delayed air reset never repeats on later landing", f.player.jumpCalls == 0 && f.controller.attacks == 1
                    && !f.player.movementInput.jump && f.player.movementInput.moveForward == 0);
        }
        try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; f.player.jumpBoost = true; f.player.rotationYaw = 90;
            AttackReduceFeature.setAccuracy(0); f.receive(f.velocity()); f.input();
            check("air accuracy delay leaves native motion untouched", f.player.jumpCalls == 0 && near(f.player.motionY, .36));
            for (int i = 0; i < 4; i++) f.tick();
            check("air reset with jump boost never applies a second jump or sprint jump impulse", f.player.jumpCalls == 0
                    && f.controller.attacks == 1 && near(f.player.motionY, .36)
                    && near(f.player.motionX, .24) && near(f.player.motionZ, -.12));
        }
        try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; AttackReduceFeature.setOnlyWhenTargeting(true);
            f.receive(f.velocity()); f.target.alive = false; f.input();
            check("air reset rechecks target before attacking", f.player.jumpCalls == 0
                    && f.controller.attacks == 0 && !f.player.movementInput.jump);
        }
        for (boolean airborne : new boolean[] {false, true}) for (int exit = 0; exit < 9; exit++) try (Fixture f = new Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            f.player.onGround = !airborne;
            f.player.sprinting = false; f.receive(f.velocity()); f.input();
            if (exit == 0) { MODULE.setEnabled(false); f.start(); }
            else if (exit == 1) AttackReduceFeature.setMode(AttackReduceFeature.MODE_NOXZ);
            else if (exit == 2) { f.mc.theWorld = new Object(); f.start(); }
            else if (exit == 3) f.receive(new S08PacketPlayerPosLook());
            else if (exit == 4) f.receive(new S07PacketRespawn());
            else if (exit == 5) { f.mc.currentScreen = new Object(); f.start(); }
            else if (exit == 6) { f.player.water = true; f.start(); }
            else if (exit == 7) { f.player.alive = false; f.start(); }
            else { f.channel.close(); f.drain(); }
            check("active JumpReset cleanup restores input and owned sprint: " + exit + "; air=" + airborne, !f.player.sprinting
                    && f.player.movementInput.moveForward == 0 && !f.player.movementInput.jump);
        }
        for (boolean airborne : new boolean[] {false, true}) for (int skip = 0; skip < 7; skip++) try (Fixture f = new Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            f.player.onGround = !airborne;
            if (skip == 0) f.mc.currentScreen = new Object();
            else if (skip == 1) f.player.usingItem = true;
            else if (skip == 2) f.player.lava = true;
            else if (skip == 3) f.player.burning = true;
            else if (skip == 4) f.player.isInWeb = true;
            S12PacketEntityVelocity v = skip == 5 ? new S12PacketEntityVelocity(99, .4, .36, -.2)
                    : skip == 6 ? new S12PacketEntityVelocity(7, .4, 0, -.2) : f.velocity();
            f.receive(v); f.input();
            check("reset environment and non-hit guards: " + skip + "; air=" + airborne, !f.player.movementInput.jump
                    && f.player.movementInput.moveForward == 0 && f.applied.size() == 1 && f.player.jumpCalls == 0);
        }
        try (Fixture f = new Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            f.receive(f.velocity()); f.player.movementInput.sneak = true; f.input();
            check("sneak cancels pending reset", !f.player.movementInput.jump && f.player.movementInput.moveForward == 0);
        }
        try (Fixture f = new Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            f.receive(f.velocity()); f.receive(new S12PacketEntityVelocity(7, .1, 0, 0)); f.input();
            check("new non-positive velocity invalidates old jump", !f.player.movementInput.jump && near(f.player.motionX, .1));
        }
        for (boolean airborne : new boolean[] {false, true}) try (Fixture f = new Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            f.player.onGround = !airborne;
            f.receive(f.velocity()); for (int i = 0; i < 6; i++) f.start(); f.input();
            check("missing input callbacks expire reset rather than jump later; air=" + airborne,
                    !f.player.movementInput.jump && f.player.movementInput.moveForward == 0 && f.player.jumpCalls == 0);
        }
        try (Fixture f = new Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            f.player.sprinting = false; f.receive(f.velocity()); f.input();
            f.receive(new S12PacketEntityVelocity(7, .2, .3, .1)); f.input(); f.tick(); f.tick();
            check("consecutive hits replace intent without motion replay or stuck sprint", f.applied.size() == 2 && near(f.player.motionX, .2)
                    && !f.player.sprinting && f.player.movementInput.moveForward == 0);
        }
        try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; f.player.sprinting = false;
            f.receive(f.velocity()); f.receive(new S12PacketEntityVelocity(7, .2, .3, .1)); f.input();
            check("consecutive airborne packets replace pending reset", f.player.jumpCalls == 0 && f.controller.attacks == 1
                    && f.applied.size() == 2 && near(f.player.motionX, .12) && near(f.player.motionY, .3) && near(f.player.motionZ, .06));
            f.receive(f.velocity()); f.input(); f.tick(); f.tick();
            check("new air hit gets one new attack without retaining owned sprint", f.player.jumpCalls == 0 && f.controller.attacks == 2
                    && f.applied.size() == 3 && !f.player.sprinting && f.player.movementInput.moveForward == 0);
        }
        try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; f.player.sprinting = false; f.mc.objectMouseOver = null;
            f.receive(f.velocity()); f.input();
            check("air without target only changes movement input, never motion", f.controller.attacks == 0
                    && f.player.jumpCalls == 0 && !f.player.movementInput.jump && f.player.movementInput.moveForward == 1
                    && near(f.player.motionX, .4) && near(f.player.motionY, .36) && near(f.player.motionZ, -.2)
                    && "AIR MOVE / NO TARGET".equals(AttackReduceFeature.getStatus()));
            f.tick(); f.tick();
            check("air without target still releases owned sprint", !f.player.sprinting && f.player.movementInput.moveForward == 0);
        }
        try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; f.target.acceptsDamage = false;
            f.receive(f.velocity()); f.input(); f.input(); f.tick();
            check("damage rejection never fabricates air knockback reduction", f.controller.attacks == 1 && f.player.jumpCalls == 0
                    && near(f.player.motionX, .4) && near(f.player.motionY, .36) && near(f.player.motionZ, -.2));
        }
        try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; f.player.swingFails = true;
            f.receive(f.velocity()); f.input(); f.input();
            check("failed air attack never writes velocity or sends C02", f.controller.attacks == 0 && f.player.jumpCalls == 0
                    && near(f.player.motionX, .4) && near(f.player.motionY, .36) && near(f.player.motionZ, -.2)
                    && !f.player.movementInput.jump && "AIR MOVE / ATTACK FAILED".equals(AttackReduceFeature.getStatus()));
        }
        try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; f.receive(f.velocity());
            check("other module can consume current attack budget", AttackReduceFeature.attack(f.target, true));
            f.input(); f.input();
            check("air reset shares same-tick budget without manual reduction", f.controller.attacks == 1
                    && near(f.player.motionX, .24) && near(f.player.motionY, .36) && f.player.jumpCalls == 0);
            f.tick(); f.tick(); f.tick();
            check("pending air attack uses next input tick exactly once", f.controller.attacks == 2
                    && near(f.player.motionX, .144) && near(f.player.motionY, .36) && f.player.jumpCalls == 0);
        }
        for (int stop = 0; stop < 3; stop++) try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; f.receive(f.velocity()); AttackReduceFeature.attack(f.target, true); f.input();
            if (stop == 0) f.receive(new S08PacketPlayerPosLook());
            else if (stop == 1) f.player.onGround = true;
            else f.target.alive = false;
            f.tick(); f.tick();
            check("correction, landing or lost target clears deferred air attack: " + stop,
                    f.controller.attacks == 1 && f.player.jumpCalls == 0 && !f.player.movementInput.jump);
        }
        try (Fixture f = new Fixture(mode)) {
            f.player.onGround = false; AttackReduceFeature.setDebugChat(true); VelocityDebug.drain();
            f.receive(f.velocity()); f.input();
            check("debug distinguishes air ready from executed attack with unchanged Y", has(VelocityDebug.history(), "KB in PASS AIR RESET READY")
                    && has(VelocityDebug.history(), "RESET AIR ATTACK ground=false v=(0.24, 0.36, -0.12)"));
        } finally { AttackReduceFeature.setDebugChat(false); VelocityDebug.drain(); }
    }
    static void mix() throws Exception {
        try (Fixture f = new Fixture(AttackReduceFeature.MODE_MIX)) {
            AttackReduceFeature.setRotate(true); f.player.onGround = false; f.player.rotationYaw = 25;
            f.receive(f.velocity()); f.start(); f.input();
            check("Mix silent rotation applied before movement", near(f.player.rotationYaw, 63.4349488));
            f.player.rotationYaw += 3; f.input(); f.end();
            check("repeated input callbacks preserve camera plus mouse delta", near(f.player.rotationYaw, 28));
            f.start(); f.player.movementInput.sneak = true; f.player.movementInput.moveForward = .3F;
            f.player.movementInput.moveStrafe = 0; AttackReduceFeature.afterMovementInput(f.player.movementInput);
            check("silent MoveFix retains sneaking movement", near(f.player.movementInput.moveForward, .3));
            f.end();
        }
        try (Fixture f = new Fixture(2)) {
            f.player.onGround = false; S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            S15PacketEntityRelMove move = new S15PacketEntityRelMove(); S32PacketConfirmTransaction ack = new S32PacketConfirmTransaction();
            S18PacketEntityTeleport teleport = new S18PacketEntityTeleport(); S00PacketKeepAlive keepalive = new S00PacketKeepAlive();
            f.receive(move); f.receive(ack); f.receive(teleport); f.receive(keepalive);
            check("Mix queues entity movement / transaction / teleport only", f.applied.size() == 1 && f.applied.get(0) == keepalive);
            for (int i = 0; i < 23; i++) f.tick(); check("Mix waits through twenty-three air ticks", !f.applied.contains(v));
            f.tick(); f.start(); check("Mix motion release replays FIFO before next actions", f.applied.size() == 5 && f.applied.get(1) == v && f.applied.get(2) == move && f.applied.get(3) == ack && f.applied.get(4) == teleport);
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
            check("Mix uses current nonsprint state without manual damping", f.controller.attacks == 1
                    && !f.controller.sawSprinting && near(f.player.motionX, .4) && near(f.player.motionZ, -.2) && near(f.player.motionY, .1));
            f.input(); f.end();
            f.start(); check("Mix second attack uses next tick budget", f.controller.attacks == 2);
        }
        try (Fixture f = new Fixture(2)) {
            AttackReduceFeature.setTryAttack(true); AURA.setEnabled(true); field(OpenMaverFeatures.class, "killAuraTarget", f.target);
            f.player.motionX = .4; f.player.sprinting = false; f.receive(f.velocity()); f.player.sprinting = true; f.start();
            check("Mix native attack uses execution-time sprint state", f.controller.attacks == 1 && near(f.player.motionX, .24));
        }
    }
    static void lifecycle() throws Exception {
        try (Fixture f = new Fixture(2)) {
            f.player.onGround = false; f.receive(f.velocity());
            AttackReduceFeature.setMode(0); f.start();
            check("mode switch replays pending original instead of dropping it", f.applied.size() == 1 && near(f.player.motionX, .4));
            f.start(); check("mode switch reattaches hook", AttackReduceFeature.isPacketHookInstalled());
        }
        try (Fixture f = new Fixture(2)) {
            f.player.onGround = false; AttackReduceFeature.setInstantAttack(true); f.receive(f.velocity()); f.tick();
            MODULE.setEnabled(false); f.tick();
            check("disable flushes pending velocity and restores timer", f.applied.size() == 1 && near(f.mc.timer.timerSpeed, 1) && !AttackReduceFeature.isPacketHookInstalled());
        }
        try (Fixture f = new Fixture(2)) {
            f.player.onGround = false; f.receive(f.velocity()); f.mc.theWorld = new Object(); f.tick();
            check("new world drops old world queue", f.applied.isEmpty() && near(f.player.motionX, 0));
        }
        try (Fixture f = new Fixture(2)) {
            f.player.onGround = false; f.receive(f.velocity());
            f.holdOutbound();
            ChannelPromise promise = f.channel.newPromise(); f.channel.pipeline().write(new C03PacketPlayer(1), promise);
            f.channel.close(); f.channel.runPendingTasks(); f.drain();
            check("disconnect fails held promise instead of leaving it pending", promise.isDone() && !promise.isSuccess());
        }
        try (Fixture f = new Fixture(2)) {
            f.player.onGround = false; f.receive(f.velocity());
            f.holdOutbound();
            ChannelPromise cancelled = f.channel.newPromise(), next = f.channel.newPromise();
            f.channel.pipeline().write(new C03PacketPlayer(1), cancelled); cancelled.cancel(false);
            C03PacketPlayer following = new C03PacketPlayer(2); f.channel.pipeline().write(following, next);
            MODULE.setEnabled(false); f.tick();
            check("cancelled movement promise does not block remaining FIFO", cancelled.isCancelled() && next.isSuccess()
                    && f.channel.readOutbound() == following && f.channel.readOutbound() == null);
        }
        try (Fixture f = new Fixture(2)) {
            f.player.onGround = false; f.receive(f.velocity());
            f.holdOutbound();
            ChannelPromise last = null;
            for (int i = 0; i < AttackReduceFeature.MAX_PENDING; i++) {
                last = f.channel.newPromise(); f.channel.pipeline().write(new C03PacketPlayer(i), last);
            }
            f.drain(); f.start(); int count = 0; while (f.channel.readOutbound() != null) count++;
            check("outgoing queue cap releases all moves and pending knockback", count == AttackReduceFeature.MAX_PENDING
                    && last.isSuccess() && f.applied.size() == 1 && !AttackReduceFeature.current().isSuspending);
        }
        try (Fixture f = new Fixture(2)) {
            f.receive(f.velocity()); f.channel.pipeline().remove(AttackReducePacketHook.NAME); f.start();
            check("external hook removal releases queue and heals hook", f.applied.size() == 1 && AttackReduceFeature.isPacketHookInstalled());
        }
        try (Fixture f = new Fixture(2)) {
            f.receive(f.velocity()); S07PacketRespawn respawn = new S07PacketRespawn(); f.receive(respawn);
            check("respawn flushes earlier packets before respawn", f.applied.size() == 2 && f.applied.get(1) == respawn);
        }
        try (Fixture f = new Fixture(2)) {
            final java.util.function.Consumer<Object> handler = f.player.sendQueue.inbound;
            f.player.sendQueue.inbound = p -> { handler.accept(p); if (p instanceof S07PacketRespawn) f.mc.theWorld = new Object(); };
            S07PacketRespawn respawn = new S07PacketRespawn(); S00PacketKeepAlive following = new S00PacketKeepAlive();
            f.channel.writeInbound(respawn); f.channel.writeInbound(following); f.drain();
            check("same-connection respawn preserves packets already queued for new world", f.applied.size() == 2 && f.applied.get(1) == following);
            f.start(); f.receive(new S00PacketKeepAlive()); check("respawn hook rebinds to new world", f.applied.size() == 3 && AttackReduceFeature.isPacketHookInstalled());
        }
        try (Fixture f = new Fixture(2)) {
            f.player.onGround = false; f.receive(f.velocity());
            for (int i = 0; i < AttackReduceFeature.MAX_PENDING; i++) f.receive(new S32PacketConfirmTransaction());
            check("packet count cap forces release without losing packets", f.applied.size() == AttackReduceFeature.MAX_PENDING + 1 && !AttackReduceFeature.current().isSuspending);
        }
        for (int exit = 0; exit < 4; exit++) try (Fixture f = new Fixture(0)) {
            f.player.onGround = false; S12PacketEntityVelocity v = f.velocity(); f.receive(v);
            if (exit == 0) { MODULE.setEnabled(false); f.tick(); MODULE.setEnabled(true); }
            else if (exit == 1) { AttackReduceFeature.setMode(1); f.tick(); AttackReduceFeature.setMode(0); }
            else if (exit == 2) f.mc.theWorld = new Object();
            else f.receive(new S08PacketPlayerPosLook());
            f.player.onGround = true; f.tick();
            check("NoXZ lifecycle clears landing intent without replay: " + exit, f.controller.attacks == 0
                    && f.applied.get(0) == v && f.applied.stream().filter(p -> p == v).count() == 1);
        }
    }
    static void threadIsolation() throws Exception {
        try (Fixture f = new Fixture(0)) {
            Thread worker = new Thread(() -> f.channel.writeInbound(f.velocity())); worker.start(); worker.join();
            check("Netty receipt does not touch player motion", f.applied.isEmpty() && f.mc.tasks.size() == 1 && near(f.player.motionX, 0));
            f.drain(); check("SRG processPacket runs on main thread", near(f.player.motionX, .4));
        }
        try (Fixture f = new Fixture(1)) {
            Thread worker = new Thread(() -> f.channel.writeInbound(f.velocity())); worker.start(); worker.join();
            AttackReduceFeature.setMode(2); f.start();
            check("queued main-thread task survives mode switch exactly once", f.applied.size() == 1 && near(f.player.motionX, .4));
        }
        try (Fixture f = new Fixture(1)) {
            Thread worker = new Thread(() -> f.channel.writeInbound(f.velocity())); worker.start(); worker.join();
            f.mc.theWorld = new Object(); f.drain();
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
