import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovementInput;
import util.Logger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Properties;

/** Velocity 的 1.8.9 适配层；输入式重置与 OpenZen 状态机位于 antikb/。 */
public final class AttackReduceFeature {
    public static final int MODE_NOXZ = 0, MODE_JUMP_RESET = 1, MODE_MIX = 2, MODE_SPRINT_RESET = 3;
    public static final String[] MODE_NAMES = {"NoXZ", "Jump Reset", "Mix", "Sprint Reset"};
    private static int mode = MODE_SPRINT_RESET;
    private static boolean rotate, tryAttack, movementOverride, instantAttack;
    private static boolean onlyWhenTargeting, waterCheck = true;
    private static int chance = 100, accuracy = 100;
    private static boolean sprintStateCheck = true;
    private static boolean debugChat;
    private static int attackAmount = 5;
    private static final AntiKBMode[] MODES = {new NoXZMode(), new JumpResetMode(false), new MixMode(), new JumpResetMode(true)};
    private static volatile Session session;
    private static long knockbacks, attacks;
    private static int tick, nextHookAttempt;
    private static Object timerOwner;
    private static double timerBefore = 1;
    private static Object cameraPlayer;
    private static float cameraYaw, cameraPitch, writtenYaw, writtenPitch;
    private static boolean cameraChanged;
    static Float rotationYaw;
    private static float rotationPitch;
    private static String status = "IDLE";
    // 包回放只在 scheduled-task / tick 准备阶段同步执行，避免在 action 与 C03 之间发 C0F。
    private static boolean replayAllowed;
    private static final Deque<Packet> deferredReplays = new ArrayDeque<Packet>();
    static final int MAX_PENDING = 4096;
    private AttackReduceFeature() { }

    static final class Session {
        final Minecraft mc;
        final Object player, world, handler;
        final int entityId, mode;
        final AttackReducePacketHook hook;
        final ChannelPipeline pipeline;
        final Deque<Move> moves = new ArrayDeque<Move>();
        volatile boolean holdMoves, closed;
        boolean worldTransition;
        boolean releaseScheduled;
        Session(Minecraft mc, Object player, Object world, Object handler, int id,
                int mode, AttackReducePacketHook hook, ChannelPipeline pipeline) {
            this.mc = mc; this.player = player; this.world = world; this.handler = handler;
            this.entityId = id; this.mode = mode; this.hook = hook; this.pipeline = pipeline;
        }
    }
    private static final class Move {
        final ChannelHandlerContext ctx;
        final Object packet;
        final ChannelPromise promise;
        Move(ChannelHandlerContext c, Object p, ChannelPromise f) { ctx = c; packet = p; promise = f; }
    }
    /** 原始包及所属连接；回放直接调用 processPacket，避免再次经过钩子。 */
    static final class Packet {
        final Object raw;
        final Session owner;
        final Method process;
        Packet(Object raw, Session owner, Method process) { this.raw = raw; this.owner = owner; this.process = process; }
        boolean is(String name) { return packetName(raw, name); }
        int id() { return integer(raw, "entityID", "entityId", "field_149417_a", "field_148981_a"); }
        int x() { return integer(raw, "motionX", "field_149415_b"); }
        int y() { return integer(raw, "motionY", "field_149416_c"); }
        int z() { return integer(raw, "motionZ", "field_149414_d"); }
        void handle() {
            if (!sameConnection(owner)) return;
            if (replayAllowed) process();
            // Minecraft.addScheduledTask 在主线程会立即执行，不能用它推迟 action 阶段的回放。
            else deferredReplays.add(this);
        }
        void process() {
            try { process.invoke(raw, owner.handler); }
            catch (Exception error) { Logger.warn("Velocity", "Packet replay failed: " + error); }
        }
    }
    static final class ReceivePacketEvent {
        final Packet packet;
        boolean cancelled;
        ReceivePacketEvent(Packet packet) { this.packet = packet; }
        void cancel() { cancelled = true; }
    }

    /** 在箱子点击、Aura 和 AutoClicker 之前准备连接并回放到期包。 */
    public static void beforeTick(Minecraft mc) {
        boolean previous = replayAllowed;
        replayAllowed = true;
        try {
            drainDeferredReplays();
            prepareTick(mc);
            // 调试行在 Netty 线程入队，这里回到主线程写聊天框。
            VelocityDebug.flush(mc);
        }
        finally { replayAllowed = previous; }
    }
    private static void drainDeferredReplays() {
        Packet packet;
        while ((packet = deferredReplays.poll()) != null) packet.handle();
    }
    private static void prepareTick(Minecraft mc) {
        tick++;
        restoreCamera();
        Object player = read(mc, "thePlayer", "field_71439_g");
        Object world = read(mc, "theWorld", "field_71441_e");
        Object handler = read(player, "sendQueue", "field_71174_a");
        Session s = session;
        if (!enabled() || player == null || world == null || handler == null) { stop(true); return; }
        if (s != null && (!sameConnection(s) || s.mode != mode || !isPacketHookInstalled())) {
            stop(true); nextHookAttempt = tick; s = null;
        }
        if (s == null && tick >= nextHookAttempt) {
            nextHookAttempt = tick + 20;
            try {
                Object manager = fieldByType(handler, "net.minecraft.network.NetworkManager");
                Object channel = fieldByType(manager, "io.netty.channel.Channel");
                ChannelPipeline pipeline = (ChannelPipeline) call(channel, "pipeline", "pipeline");
                if (pipeline == null || pickBaseName(pipeline.names()) == null) throw new IllegalStateException("packet_handler missing");
                if (pipeline.names().contains(AttackReducePacketHook.NAME)) pipeline.remove(AttackReducePacketHook.NAME);
                AttackReducePacketHook hook = new AttackReducePacketHook();
                Object id = call(player, "getEntityId", "func_145782_y");
                if (!(id instanceof Number)) throw new IllegalStateException("entity id missing");
                s = new Session(mc, player, world, handler, ((Number) id).intValue(), mode, hook, pipeline);
                session = s;
                current().onEnable();
                pipeline.addBefore("packet_handler", AttackReducePacketHook.NAME, hook);
                Logger.info("Velocity", MODE_NAMES[mode] + " packet hook installed");
            } catch (Exception error) {
                stop(false); Logger.warn("Velocity", "Packet hook retry in 20 ticks: " + error); return;
            }
        }
        if (s == null) return;
        ensureMovementHook(s);
        current().onTick();
    }
    /** 目标与 AutoSprint 更新后执行攻击；此阶段的包回放留到下次 scheduled-task。 */
    public static void onTickStart(Minecraft mc) {
        Session s = session;
        if (s == null || !enabled() || !sameConnection(s)) return;
        current().onSprint();
    }
    public static void onTickEnd(Minecraft mc) {
        Session s = session;
        if (s != null) {
            if (!enabled() || !sameConnection(s)) stop(true);
            else current().onMotion();
        }
        restoreCamera();
    }

    /** Netty 只转交包；世界与目标判断都发生在 Minecraft 主线程。 */
    static boolean captureInbound(AttackReducePacketHook source, Object raw) {
        final Session s = session;
        if (s == null || s.hook != source || s.closed || raw == null || !enabled()) return false;
        Method process = packetMethod(raw, s.handler);
        if (process == null) return false;
        final Packet packet = new Packet(raw, s, process);
        return schedule(s, new Runnable() {
            public void run() {
                boolean previous = replayAllowed;
                replayAllowed = true;
                try {
                    drainDeferredReplays();
                    if (!sameConnection(s)) {
                        // S07 会在同一网络连接中更换 player/world；其后的批量包属于新世界。
                        if (s.worldTransition && s.pipeline.channel().isOpen()
                                && read(read(s.mc, "thePlayer", "field_71439_g"), "sendQueue", "field_71174_a") == s.handler) packet.process();
                        return;
                    }
                    if (session != s || !enabled()) { packet.handle(); return; }
                    if (packet.is("S08PacketPlayerPosLook") || packet.is("S07PacketRespawn") || packet.is("S40PacketDisconnect")) {
                        current().release(); current().onMotion(); current().onEnable(); restoreTimer(); rotationYaw = null;
                        if (packet.is("S08PacketPlayerPosLook")) current().onCorrection();
                        if (packet.is("S07PacketRespawn")) s.worldTransition = true;
                        packet.handle(); return;
                    }
                    // 新击退是新的物理边界：先按序应用旧队列，避免旧 S12 最后覆盖新 S12。
                    if (ownVelocity(packet) && current().isSuspending) {
                        current().release(); current().onMotion(); current().onEnable();
                    }
                    if (ownVelocity(packet)) knockbacks++;
                    if (ignored()) { current().release(); current().onMotion(); packet.handle(); return; }
                    ReceivePacketEvent event = new ReceivePacketEvent(packet);
                    current().onReceivePacket(event);
                    if (!event.cancelled) packet.handle();
                    if (current().pendingCount() >= MAX_PENDING) { current().release(); current().onMotion(); status("QUEUE LIMIT"); }
                } finally { replayAllowed = previous; }
            }
        });
    }
    /** 挂起移动时保留整个出站包序，避免 action/ack 越过先前的 C03。 */
    static boolean captureOutbound(AttackReducePacketHook source, ChannelHandlerContext ctx, Object packet, ChannelPromise promise) {
        final Session s = session;
        if (s == null || s.hook != source || s.closed) return false;
        synchronized (s.moves) {
            VelocityDebug.outbound(packet, s.holdMoves, s.moves.size());
            if (!s.holdMoves) return false;
            s.moves.add(new Move(ctx, packet, promise));
            if (s.moves.size() >= MAX_PENDING && !s.releaseScheduled) {
                s.releaseScheduled = true;
                if (!schedule(s, new Runnable() { public void run() { if (session == s) current().release(); } })) flushMoves(s);
            }
            return true;
        }
    }
    static void holdMoves() {
        final Session s = session;
        if (s == null) return;
        ChannelHandlerContext ctx = s.hook.context;
        if (ctx == null) return;
        // 与 flushMoves 使用同一个 eventLoop；连续击退时旧 flush 不会清掉新的 hold。
        ctx.channel().eventLoop().execute(new Runnable() {
            public void run() { synchronized (s.moves) { if (!s.closed) s.holdMoves = true; } }
        });
    }
    static void sendMovePackets() { flushMoves(session); }
    private static void flushMoves(final Session s) {
        if (s == null) return;
        Runnable flush = new Runnable() {
            public void run() {
                synchronized (s.moves) {
                    Move move; ChannelHandlerContext last = null; int sent = 0;
                    while ((move = s.moves.poll()) != null) {
                        if (move.promise.isDone()) continue;
                        if (s.closed || !move.ctx.channel().isOpen()) move.promise.tryFailure(new IllegalStateException("Velocity connection closed"));
                        else try { move.ctx.write(move.packet, move.promise); last = move.ctx; sent++; }
                        catch (Exception error) { move.promise.tryFailure(error); }
                    }
                    if (last != null) last.flush();
                    s.holdMoves = false; s.releaseScheduled = false;
                    VelocityDebug.flushed(sent);
                }
            }
        };
        try {
            ChannelHandlerContext ctx = s.hook.context;
            if (ctx == null) { s.closed = true; flush.run(); }
            else ctx.channel().eventLoop().execute(flush);
        } catch (Exception error) { s.closed = true; flush.run(); }
    }
    static void connectionClosed(final AttackReducePacketHook hook) {
        final Session s = session;
        if (s == null || s.hook != hook) return;
        s.closed = true; flushMoves(s);
        schedule(s, new Runnable() { public void run() { if (session == s) stop(false); } });
    }
    private static void stop(boolean release) {
        Session s = session;
        if (s != null) {
            if (!sameConnection(s)) s.closed = true;
            if (release && sameConnection(s)) { MODES[s.mode].release(); MODES[s.mode].onMotion(); }
            MODES[s.mode].onDisable(); flushMoves(s); session = null;
            try { s.pipeline.remove(s.hook); } catch (Exception ignored) { }
        }
        rotationYaw = null; restoreCamera(); restoreTimer(); status = "IDLE";
    }

    static void afterMovementInput(MovementInput input) {
        restoreCamera();
        Session s = session;
        if (s == null || !sameConnection(s) || !enabled()) return;
        current().onStrafe(input);
        if (rotationYaw != null && !rotationBusy()) {
            cameraPlayer = s.player;
            cameraYaw = (float) number(s.player, "rotationYaw", "field_70177_z");
            cameraPitch = (float) number(s.player, "rotationPitch", "field_70125_A");
            writtenYaw = cameraYaw + OpenMaverFeatures.wrapDegrees(rotationYaw - cameraYaw); writtenPitch = rotationPitch;
            moveFix(input, cameraYaw, writtenYaw);
            set(s.player, "rotationYaw", "field_70177_z", writtenYaw);
            set(s.player, "rotationPitch", "field_70125_A", writtenPitch); cameraChanged = true;
        }
        current().onMotion();
    }
    private static void moveFix(MovementInput input, float from, float to) {
        float scale = input.sneak ? 0.3F : 1;
        double radians = Math.toRadians(from - to);
        double x = (input.moveForward * Math.cos(radians) + input.moveStrafe * Math.sin(radians)) / scale;
        double y = (input.moveStrafe * Math.cos(radians) - input.moveForward * Math.sin(radians)) / scale;
        double best = Double.MAX_VALUE; float bestF = 0, bestZ = 0;
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            double d = (x - a) * (x - a) + (y - b) * (y - b);
            if (d < best) { best = d; bestF = a; bestZ = b; }
        }
        input.moveForward = bestF * scale; input.moveStrafe = bestZ * scale;
    }
    private static void restoreCamera() {
        if (!cameraChanged) return;
        set(cameraPlayer, "rotationYaw", "field_70177_z", cameraYaw + number(cameraPlayer, "rotationYaw", "field_70177_z") - writtenYaw);
        set(cameraPlayer, "rotationPitch", "field_70125_A", cameraPitch + number(cameraPlayer, "rotationPitch", "field_70125_A") - writtenPitch);
        cameraChanged = false; cameraPlayer = null;
    }
    private static boolean rotationBusy() { return moduleEnabled("Scaffold") || moduleEnabled("GodBridge") || OpenMaverFeatures.velocityAuraTarget() != null; }
    private static void ensureMovementHook(Session s) {
        Object input = read(s.player, "movementInput", "field_71158_b");
        if (input instanceof ScaffoldMovementInput) return;
        if (input != null && !input.getClass().getName().equals("net.minecraft.util.MovementInputFromOptions")) return;
        Object settings = read(s.mc, "gameSettings", "field_71474_y");
        if (settings instanceof GameSettings) OpenMaverFeatures.writeObjectField(s.player, "movementInput", "field_71158_b", new ScaffoldMovementInput((GameSettings) settings));
    }
    static void timer(float multiplier) {
        Object owner = read(mc(), "timer", "field_71428_T");
        if (owner == null) return;
        if (timerOwner != owner) { restoreTimer(); timerOwner = owner; timerBefore = number(owner, "timerSpeed", "field_74278_d"); }
        set(owner, "timerSpeed", "field_74278_d", multiplier);
    }
    static void restoreTimer() { if (timerOwner != null) set(timerOwner, "timerSpeed", "field_74278_d", timerBefore); timerOwner = null; }
    /** 当前客户端 tick 倍率；调试输出用，读不到时返回 0。 */
    static double timerSpeed() {
        Object owner = read(mc(), "timer", "field_71428_T");
        return owner == null ? 0.0D : number(owner, "timerSpeed", "field_74278_d");
    }
    static boolean ignored() {
        Object p = player(), abilities = read(p, "capabilities", "field_71075_bZ");
        return p == null || !OpenMaverFeatures.alive(p) || truth(p, "isSpectator", "func_175149_v")
                || flag(abilities, "isFlying", "field_75100_b")
                || (truth(p, "isInWater", "func_70090_H") && (!isResetMode() || waterCheck))
                || truth(p, "isInLava", "func_180799_ab") || truth(p, "isBurning", "func_70027_ad")
                || truth(p, "isOnLadder", "func_70617_f_") || truth(p, "isPlayerSleeping", "func_70608_bn")
                || flag(p, "isInWeb", "field_70134_J") || moduleEnabled("Stuck") || moduleEnabled("FireballBlink") || moduleEnabled("HighJump");
    }
    static Object attackTarget() {
        Object target = OpenMaverFeatures.velocityAuraTarget();
        if (target != null) return target;
        Object hit = read(mc(), "objectMouseOver", "field_71476_x");
        return "ENTITY".equals(String.valueOf(read(hit, "typeOfHit", "field_72313_a"))) ? read(hit, "entityHit", "field_72308_g") : null;
    }
    static boolean validTarget(Object target) {
        if (!(target instanceof EntityLivingBase) || target == player() || !OpenMaverFeatures.alive(target) || truth(target, "isSpectator", "func_175149_v")) return false;
        AxisAlignedBB box = OpenMaverFeatures.boundingBox(target);
        if (box == null) return false;
        Object p = player();
        double x = number(p, "posX", "field_70165_t"), y = number(p, "posY", "field_70163_u") + OpenMaverFeatures.eyeHeight(p), z = number(p, "posZ", "field_70161_v");
        double dx = x - Math.max(box.minX, Math.min(x, box.maxX)), dy = y - Math.max(box.minY, Math.min(y, box.maxY)), dz = z - Math.max(box.minZ, Math.min(z, box.maxZ));
        return dx * dx + dy * dy + dz * dz <= 3.0D * 3.0D
                && truth(p, "canEntityBeSeen", "func_70685_l", target);
    }
    /** 1.8.9：同步疾跑 -> swing -> controller.attack；减速只由原版攻击结果决定。 */
    static boolean attack(Object target, boolean checkSprint) {
        Object p = player(), controller = read(mc(), "playerController", "field_71442_b");
        if (!validTarget(target) || controller == null || (checkSprint && !sprinting())) return false;
        if (read(mc(), "currentScreen", "field_71462_r") != null
                || truth(p, "isUsingItem", "func_71039_bw")) return false;
        if (!OpenMaverFeatures.attackEntity18(mc(), p, target)) return false;
        attacks++; status("ATTACK"); return true;
    }
    /** Mix 复用同一条 1.8 攻击路径，使用执行时的真实疾跑状态。 */
    static boolean attackMix(Object target) {
        return attack(target, false);
    }
    static void setRotation(Packet packet) {
        rotationYaw = (float) Math.toDegrees(Math.atan2(packet.x() / 8000.0D, -packet.z() / 8000.0D));
        rotationPitch = (float) number(player(), "rotationPitch", "field_70125_A");
    }
    static AntiKBMode current() { return MODES[mode]; }
    static Minecraft mc() { return session == null ? null : session.mc; }
    static Object player() { return session == null ? null : session.player; }
    static int sessionEntityId() { return session == null ? -1 : session.entityId; }
    /** 当前挂起、尚未写出的出站包数量（调试输出用）。 */
    static int queuedMoves() {
        Session s = session;
        if (s == null) return 0;
        synchronized (s.moves) { return s.moves.size(); }
    }
    static boolean onGround() { return flag(player(), "onGround", "field_70122_E"); }
    static boolean sprinting() { return truth(player(), "isSprinting", "func_70051_ag"); }
    static int tickId() { return tick; }
    private static boolean isResetMode() { return mode == MODE_JUMP_RESET || mode == MODE_SPRINT_RESET; }
    static boolean ownVelocity(Packet p) { return p.is("S12PacketEntityVelocity") && session != null && p.id() == session.entityId; }
    static void status(String v) { status = v; }
    static boolean moduleEnabled(String name) { Module m = ModuleManager.getModule(name); return m != null && m.isEnabled(); }
    static boolean jumpResetSuspended() {
        Module noFall = ModuleManager.getModule("NoFall"), backtrack = ModuleManager.getModule("Backtrack");
        return noFall != null && noFall.isEnabled()
                && (flag(noFall, "jumpLandingBoost", "jumpLandingBoost") || flag(noFall, "boostActive", "boostActive"))
                || backtrack != null && backtrack.isEnabled()
                && (truth(backtrack, "isActive", "isActive") || truth(backtrack, "isBacktracking", "isBacktracking"));
    }
    private static boolean enabled() { return moduleEnabled("Velocity"); }
    private static boolean sameConnection(Session s) { return !s.closed && read(s.mc, "thePlayer", "field_71439_g") == s.player && read(s.mc, "theWorld", "field_71441_e") == s.world && read(s.player, "sendQueue", "field_71174_a") == s.handler; }
    static Object read(Object o, String m, String s) { return OpenMaverFeatures.read(o, m, s, null); }
    static double number(Object o, String m, String s) { return OpenMaverFeatures.number(o, m, s); }
    static void set(Object o, String m, String s, double v) { OpenMaverFeatures.setNumber(o, m, s, v); }
    static Object call(Object o, String m, String s, Object... args) { return OpenMaverFeatures.call(o, m, s, null, args); }
    static boolean truth(Object o, String m, String s, Object... args) { return Boolean.TRUE.equals(call(o, m, s, args)); }
    static boolean flag(Object o, String m, String s) { return Boolean.TRUE.equals(read(o, m, s)); }
    private static int integer(Object o, String... names) { Field f = OpenMaverFeatures.findField(o, names); try { return f == null ? 0 : f.getInt(o); } catch (Exception ignored) { return 0; } }
    private static Method packetMethod(Object raw, Object handler) {
        for (Method m : raw.getClass().getMethods()) if ((m.getName().equals("processPacket") || m.getName().equals("func_148833_a")) && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isInstance(handler)) { m.setAccessible(true); return m; }
        return null;
    }
    private static boolean packetName(Object packet, String name) { for (Class<?> c = packet.getClass(); c != null; c = c.getSuperclass()) if (name.equals(c.getSimpleName())) return true; return false; }
    private static boolean schedule(Session s, Runnable task) { return OpenMaverFeatures.callVoid(s.mc, "addScheduledTask", "func_152344_a", null, task); }
    static Object fieldByType(Object target, String name) {
        if (target != null) for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) for (Field f : c.getDeclaredFields()) if (name.equals(f.getType().getName())) try { f.setAccessible(true); return f.get(target); } catch (Exception ignored) { }
        return null;
    }
    static String pickBaseName(List<String> names) { return names != null && names.contains("packet_handler") ? "packet_handler" : null; }

    public static void load(Properties p) {
        stop(true);
        String value = p.getProperty("velocity.zen.mode", "Sprint Reset");
        mode = MODE_SPRINT_RESET;
        for (int i = 0; i < MODE_NAMES.length; i++) if (MODE_NAMES[i].equalsIgnoreCase(value)) mode = i;
        rotate = bool(p, "rotate", false); tryAttack = bool(p, "tryAttack", false);
        movementOverride = bool(p, "movementOverride", false);
        onlyWhenTargeting = bool(p, "onlyWhenTargeting", false); waterCheck = bool(p, "waterCheck", true);
        chance = bounded(p, "chance", 100, 0, 100); accuracy = bounded(p, "accuracy", 100, 0, 100);
        instantAttack = bool(p, "instantAttack", false); sprintStateCheck = bool(p, "sprintStateCheck", true);
        attackAmount = bounded(p, "attackAmount", 5, 1, 20);
        debugChat = bool(p, "debug", false);
        VelocityDebug.setEnabled(debugChat);
        nextHookAttempt = tick;
    }
    private static boolean bool(Properties p, String key, boolean fallback) { return Boolean.parseBoolean(p.getProperty("velocity.zen." + key, String.valueOf(fallback))); }
    private static int bounded(Properties p, String key, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(p.getProperty("velocity.zen." + key)))); } catch (Exception ignored) { return fallback; }
    }
    public static String save() {
        return "velocity.zen.mode=" + MODE_NAMES[mode] + "\nvelocity.zen.rotate=" + rotate + "\nvelocity.zen.tryAttack=" + tryAttack
                + "\nvelocity.zen.movementOverride=" + movementOverride + "\nvelocity.zen.attackAmount=" + attackAmount
                + "\nvelocity.zen.chance=" + chance + "\nvelocity.zen.accuracy=" + accuracy
                + "\nvelocity.zen.onlyWhenTargeting=" + onlyWhenTargeting + "\nvelocity.zen.waterCheck=" + waterCheck
                + "\nvelocity.zen.instantAttack=" + instantAttack + "\nvelocity.zen.sprintStateCheck=" + sprintStateCheck + "\n"
                + "velocity.zen.debug=" + debugChat + "\n";
    }
    public static int getMode() { return mode; }
    public static void setMode(int v) { stop(true); mode = v >= 0 && v < MODES.length ? v : MODE_SPRINT_RESET; nextHookAttempt = tick; OpenMaverFeatures.markDirty(); }
    public static String getSummary() { return MODE_NAMES[mode]; }
    public static String getStatus() { return status; }
    public static long getSprintHits() { return attacks; }
    public static long getKnockbackApplied() { return knockbacks; }
    public static boolean isPacketHookInstalled() { Session s = session; return s != null && !s.closed && s.pipeline.context(AttackReducePacketHook.NAME) != null; }
    public static boolean getRotate() { return rotate; }
    public static void setRotate(boolean v) { rotate = v; OpenMaverFeatures.markDirty(); }
    public static boolean getTryAttack() { return tryAttack; }
    public static void setTryAttack(boolean v) { tryAttack = v; OpenMaverFeatures.markDirty(); }
    public static boolean getMovementOverride() { return movementOverride; }
    public static void setMovementOverride(boolean v) { movementOverride = v; OpenMaverFeatures.markDirty(); }
    public static int getChance() { return chance; }
    public static void setChance(int v) { chance = Math.max(0, Math.min(100, v)); OpenMaverFeatures.markDirty(); }
    public static int getAccuracy() { return accuracy; }
    public static void setAccuracy(int v) { accuracy = Math.max(0, Math.min(100, v)); OpenMaverFeatures.markDirty(); }
    public static boolean getOnlyWhenTargeting() { return onlyWhenTargeting; }
    public static void setOnlyWhenTargeting(boolean v) { onlyWhenTargeting = v; OpenMaverFeatures.markDirty(); }
    public static boolean getWaterCheck() { return waterCheck; }
    public static void setWaterCheck(boolean v) { waterCheck = v; OpenMaverFeatures.markDirty(); }
    public static int getAttackAmount() { return attackAmount; }
    public static void setAttackAmount(int v) { attackAmount = Math.max(1, Math.min(20, v)); OpenMaverFeatures.markDirty(); }
    public static boolean getInstantAttack() { return instantAttack; }
    public static void setInstantAttack(boolean v) { instantAttack = v; if (!v) restoreTimer(); OpenMaverFeatures.markDirty(); }
    public static boolean getSprintStateCheck() { return sprintStateCheck; }
    public static void setSprintStateCheck(boolean v) { sprintStateCheck = v; OpenMaverFeatures.markDirty(); }
    public static boolean getDebugChat() { return debugChat; }
    public static void setDebugChat(boolean v) {
        debugChat = v; VelocityDebug.setEnabled(v);
        status(v ? "DEBUG ON" : "DEBUG OFF");
        OpenMaverFeatures.markDirty();
    }
}
