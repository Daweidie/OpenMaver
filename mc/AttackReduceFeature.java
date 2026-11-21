import module.Module;
import module.ModuleManager;
import util.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import io.netty.channel.ChannelPipeline;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 1.8.9 Velocity：Myau Slap_Attack 攻击窗口 + Zen 的 Aura/准星目标选择。 */
public final class AttackReduceFeature {
    public static final int MODE_SPRINT_HIT = 0;
    public static final int MODE_SIMPLE = 1;
    public static final int MODE_SLAP_ATTACK = 2;
    public static final String[] MODE_NAMES = {"SPRINT HIT", "SIMPLE", "SLAP ATTACK"};
    private static volatile int mode = MODE_SLAP_ATTACK;
    private static volatile boolean slapExact = true;
    private static volatile boolean slapCrosshair = true;
    private static volatile boolean chatDebug = true;
    private static volatile double horizontal = 0.0D;
    private static volatile double vertical = 100.0D;
    private static volatile boolean active;
    private static volatile int localEntityId = Integer.MIN_VALUE;
    private static volatile Module activeModule;
    private static final AtomicLong KB_SEEN = new AtomicLong();
    private static volatile Object trackedPlayer;
    private static volatile Object trackedWorld;
    private static volatile Minecraft client;
    private static volatile long generation;
    private static int attackWindow;
    private static long sprintHits;
    private static String status = "IDLE";
    private static String lastDebugState;
    private static boolean chatWarned;
    // Netty 的 SEND 观察与主线程攻击共用闸门。保留本地预约，直到自己的 C02 及
    // 后续 C03 都已经过管线；滞后的 C03 不能把尚未发出的本地攻击预约清掉。
    private static final Object ACTION_LOCK = new Object();
    private static final AtomicInteger ACTIONS = new AtomicInteger();
    private static Object reservedAttack;
    private static boolean reservedAttackWritten;
    private static final int SLOT = 1, ATTACK = 2, SWING = 4, BLOCK = 8, INVENTORY = 16, DIG = 32;
    private static final String[] ACTION_MODULES = {
            "AutoClicker", "KillAura", "Scaffold", "Scaffold2", "GodBridge", "Chest Stealer", "Criticals"
    };
    private static volatile Object hookNetHandler;
    private static ChannelPipeline hookPipeline;
    private static volatile AttackReducePacketHook hookInstance;
    private static boolean hookInstalled;
    private static boolean hookWarned;
    private static boolean packetWarned;
    private static int clientTick;
    private static int hookAttemptTick = -20;

    private AttackReduceFeature() { }

    public static void onTickStart(Minecraft mc) {
        Module module = ModuleManager.getModule("Velocity");
        Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
        if (module == null || !module.isEnabled() || player == null || world == null) {
            deactivate();
            return;
        }
        if (!active || trackedPlayer != player || trackedWorld != world) {
            deactivate();
            trackedPlayer = player;
            trackedWorld = world;
            Object id = OpenMaverFeatures.call(player, "getEntityId", "func_145782_y", null);
            localEntityId = id instanceof Number ? ((Number) id).intValue() : Integer.MIN_VALUE;
            activeModule = module;
            client = mc;
            active = true;
        }
        clientTick++;
        maintainHook(mc, player);
        if (mode == MODE_SLAP_ATTACK && attackWindow > 0) {
            double beforeX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
            double beforeY = OpenMaverFeatures.number(player, "motionY", "field_70181_x");
            double beforeZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
            boolean exhausted = --attackWindow == 0; // Myau Update PRE：先消耗，再判断。
            updateSlapAttack(mc, player);
            debugAttempt(player, beforeX, beforeY, beforeZ, exhausted);
            if (exhausted && !"HIT".equals(status) && !status.endsWith("FAILED")) status = "EXPIRED";
        }
        if (mode == MODE_SPRINT_HIT && attackWindow > 0) {
            double beforeX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
            double beforeY = OpenMaverFeatures.number(player, "motionY", "field_70181_x");
            double beforeZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
            updateSprintHit(mc, player);
            debugAttempt(player, beforeX, beforeY, beforeZ, attackWindow == 1);
            if (attackWindow > 0 && --attackWindow == 0) status = "EXPIRED";
        }
    }

    private static void deactivate() {
        active = false;
        generation++;
        attackWindow = 0;
        resetActions();
        status = "IDLE";
        lastDebugState = null;
        chatWarned = false;
        client = null;
        activeModule = null;
        detachHook("模块关闭 / 世界变化");
        hookNetHandler = null;
        trackedPlayer = null;
        trackedWorld = null;
        localEntityId = Integer.MIN_VALUE;
        hookAttemptTick = clientTick - 20;
        packetWarned = false;
    }

    /** 在 Netty 上只解析包；返回的任务必须排在 vanilla 的包处理任务之后。 */
    static Runnable beforeInbound(final AttackReducePacketHook source, Object packet) {
        Module module = activeModule;
        if (!active || module == null || !module.isEnabled() || packet == null
                || localEntityId == Integer.MIN_VALUE
                || !packet.getClass().getName().equals(
                        "net.minecraft.network.play.server.S12PacketEntityVelocity")) return null;
        try {
            Field id = intField(packet, "entityID", "entityId", "field_149417_a");
            Field x = intField(packet, "motionX", "field_149415_b");
            Field y = intField(packet, "motionY", "field_149416_c");
            Field z = intField(packet, "motionZ", "field_149414_d");
            if (id == null || x == null || y == null || z == null) {
                throw new IllegalStateException("S12 字段映射缺失");
            }
            if (id.getInt(packet) != localEntityId) return null;
            final int packetMode = mode;
            final Minecraft mc = client;
            final Object player = trackedPlayer, world = trackedWorld;
            final long epoch = generation;
            final int oldX = x.getInt(packet), oldY = y.getInt(packet), oldZ = z.getInt(packet);
            if (packetMode != MODE_SIMPLE) {
                final boolean horizontalMotion = oldX != 0 || oldZ != 0;
                final int window = horizontalMotion
                        ? (packetMode == MODE_SLAP_ATTACK ? calculateSlapTicks(x.getInt(packet), z.getInt(packet)) : 6) : 0;
                KB_SEEN.incrementAndGet();
                return new Runnable() {
                    public void run() {
                        if (!active || !acceptsHook(source) || !module.isEnabled() || mode != packetMode
                                || epoch != generation || mc == null
                                || player != trackedPlayer || world != trackedWorld
                                || OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null) != player
                                || OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null) != world) return;
                        // 现在 vanilla 已经施加这份 S12；从此处开窗，避免先攻击后受力。
                        attackWindow = window;
                        lastDebugState = null;
                        status = horizontalMotion ? "READY" : "NO HORIZONTAL KB";
                        if (!horizontalMotion) debugVelocity(player, oldX / 8000.0D, oldY / 8000.0D,
                                oldZ / 8000.0D, oldX / 8000.0D, oldY / 8000.0D, oldZ / 8000.0D,
                                status, false);
                    }
                };
            }
            double h = horizontal / 100.0D;
            double v = vertical / 100.0D;
            final int newX = (int) (oldX * h), newY = (int) (oldY * v), newZ = (int) (oldZ * h);
            // S12 的速度单位为 1/8000；先解析全部字段再写入，不触碰其它实体。
            x.setInt(packet, newX);
            y.setInt(packet, newY);
            z.setInt(packet, newZ);
            KB_SEEN.incrementAndGet();
            if (!chatDebug) return null;
            return new Runnable() {
                public void run() {
                    if (!active || !acceptsHook(source) || !module.isEnabled() || mode != packetMode
                            || epoch != generation || mc == null
                            || player != trackedPlayer || world != trackedWorld
                            || OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null) != player
                            || OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null) != world) return;
                    debugVelocity(player, oldX / 8000.0D, oldY / 8000.0D, oldZ / 8000.0D,
                            newX / 8000.0D, newY / 8000.0D, newZ / 8000.0D, "S12 SCALE", false);
                }
            };
        } catch (Throwable error) {
            if (!packetWarned) {
                packetWarned = true;
                Logger.warn("Velocity", "击退包缩放失败: " + error);
            }
        }
        return null;
    }

    static void afterInbound(Runnable task) {
        if (task != null && !OpenMaverFeatures.callVoid(client,
                "addScheduledTask", "func_152344_a", null, task) && !packetWarned) {
            packetWarned = true;
            Logger.warn("Velocity", "主线程回调入队失败");
        }
    }

    static boolean acceptsHook(AttackReducePacketHook source) {
        // 重连后旧 channel 的已排队回调不得污染新连接的窗口/出站动作。
        return hookInstance == source || (hookInstance == null && hookNetHandler == null);
    }

    /** OpenMyau-Fix Velocity.calculateSlapTicks：输入为 S12 整数，不是 /8000 后的速度。 */
    static int calculateSlapTicks(int x, int z) {
        double magnitude = Math.hypot((double) x, (double) z);
        if (!slapExact) return Math.max(1, Math.min(10,
                (int) Math.round(6.43153527E-4D * magnitude + 2.9419087136D)));
        int[] bounds = {500, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000};
        int[] ticks = {3, 4, 4, 5, 6, 6, 7, 7, 8, 8};
        for (int i = 0; i < bounds.length; i++) if (magnitude <= bounds[i]) return ticks[i];
        return 9;
    }

    /** 只观察已经发出的动作；不取消、修改、缓存任何出站包。 */
    static void noteOutbound(AttackReducePacketHook source, Object packet) {
        Module module = activeModule;
        if (!active || module == null || !module.isEnabled() || packet == null) return;
        synchronized (ACTION_LOCK) {
            if (!active || !acceptsHook(source)) return;
            if (isPacket(packet, "C03PacketPlayer")) {
                ACTIONS.set(0);
                if (reservedAttackWritten) {
                    reservedAttack = null;
                    reservedAttackWritten = false;
                }
                return;
            }
            int bits = 0;
            if (isPacket(packet, "C09PacketHeldItemChange")) bits = SLOT;
            else if (isPacket(packet, "C0APacketAnimation")) bits = SWING;
            else if (isPacket(packet, "C02PacketUseEntity")) {
                Object action = OpenMaverFeatures.call(packet, "getAction", "func_149565_c", null);
                // 映射失效时保守地视为 ATTACK；INTERACT 不误计入。
                if (action == null || "ATTACK".equals(String.valueOf(action))) bits = ATTACK;
            } else if (isPacket(packet, "C08PacketPlayerBlockPlacement")) bits = BLOCK;
            else if (isPacket(packet, "C07PacketPlayerDigging")) bits = BLOCK | DIG;
            else if (isPacket(packet, "C0DPacketCloseWindow") || isPacket(packet, "C0EPacketClickWindow")) bits = INVENTORY;
            else if (isPacket(packet, "C16PacketClientStatus")) {
                Object action = OpenMaverFeatures.call(packet, "getStatus", "func_149435_c", null);
                if (action == null || "OPEN_INVENTORY_ACHIEVEMENT".equals(String.valueOf(action))) bits = INVENTORY;
            }
            ACTIONS.set(ACTIONS.get() | bits);
            if (packet == reservedAttack) reservedAttackWritten = true;
        }
    }

    private static boolean isPacket(Object packet, String name) {
        for (Class<?> type = packet.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("net.minecraft.network.play.client." + name)) return true;
        }
        return false;
    }

    private static void resetActions() {
        synchronized (ACTION_LOCK) {
            ACTIONS.set(0);
            reservedAttack = null;
            reservedAttackWritten = false;
        }
    }

    static boolean hasBadPackets() {
        synchronized (ACTION_LOCK) { return ACTIONS.get() != 0 || reservedAttack != null; }
    }

    private static boolean enabled(String name) {
        Module module = ModuleManager.getModule(name);
        return module != null && module.isEnabled();
    }

    /** 同一尝试前后取样，排除 tick 间摩擦/重力；阻断状态重复时仅在窗口结束再报一次。 */
    private static void debugAttempt(Object player, double x, double y, double z, boolean exhausted) {
        if (!chatDebug) return;
        String state = status + "|" + debugContext(player);
        if (!exhausted && !"HIT".equals(status) && state.equals(lastDebugState)) return;
        lastDebugState = state;
        debugVelocity(player, x, y, z,
                OpenMaverFeatures.number(player, "motionX", "field_70159_w"),
                OpenMaverFeatures.number(player, "motionY", "field_70181_x"),
                OpenMaverFeatures.number(player, "motionZ", "field_70179_y"), status, exhausted);
    }

    private static String debugContext(Object player) {
        StringBuilder modules = new StringBuilder();
        for (String name : ACTION_MODULES) if (enabled(name)) {
            if (modules.length() > 0) modules.append(',');
            modules.append(name);
        }
        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        Module autoSprint = ModuleManager.getModule("AutoSprint");
        return "left=" + Mouse.isButtonDown(0) + " right=" + Mouse.isButtonDown(1)
                + " sprint=" + Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isSprinting", "func_70051_ag", null))
                + " sentSprint=" + Boolean.TRUE.equals(OpenMaverFeatures.read(player, "serverSprintState", "field_175171_bO", null))
                + " actions=" + ACTIONS.get() + " localAction=" + OpenMaverFeatures.velocityActionUsedThisTick()
                + " forward=" + debugNumber(OpenMaverFeatures.number(input, "moveForward", "field_78900_b"))
                + " strafe=" + debugNumber(OpenMaverFeatures.number(input, "moveStrafe", "field_78902_a"))
                + " sneak=" + Boolean.TRUE.equals(OpenMaverFeatures.read(input, "sneak", "field_78899_d", Boolean.class))
                + " usingItem=" + Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isUsingItem", "func_71039_bw", null))
                + " autoSprint=" + (autoSprint != null && autoSprint.isEnabled())
                + " onlyForward=" + OpenMaverFeatures.isSprintOnlyForward()
                + " modules=" + (modules.length() == 0 ? "none" : modules.toString());
    }

    private static String debugNumber(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** 仅主线程调用：addChatMessage 是本地显示，不向服务器发送聊天。 */
    private static void debugVelocity(Object player, double x, double y, double z,
            double afterX, double afterY, double afterZ, String reason, boolean exhausted) {
        if (!chatDebug) return;
        try {
            String motion = String.format(Locale.ROOT,
                    "[Maver] Velocity before(x:%.4f y:%.4f z:%.4f) after(x:%.4f y:%.4f z:%.4f)",
                    x, y, z, afterX, afterY, afterZ);
            String detail = "[Maver] Velocity mode=" + MODE_NAMES[mode] + " reason=" + reason
                    + " window=" + attackWindow + (exhausted ? " expired=true" : "") + " " + debugContext(player);
            Logger.info("Velocity", motion + " " + detail);
            Class<?> component = Class.forName("net.minecraft.util.ChatComponentText");
            Constructor<?> ctor = component.getConstructor(String.class);
            if (!OpenMaverFeatures.callVoid(player, "addChatMessage", "func_145747_a", null, ctor.newInstance(motion))
                    || !OpenMaverFeatures.callVoid(player, "addChatMessage", "func_145747_a", null, ctor.newInstance(detail))) {
                throw new IllegalStateException("addChatMessage 映射不可用");
            }
        } catch (Throwable error) {
            if (!chatWarned) { chatWarned = true; Logger.warn("Velocity", "聊天调试显示失败: " + error); }
        }
    }

    private static void updateSlapAttack(Minecraft mc, Object player) {
        if (!isPacketHookInstalled()) { status = "WAIT HOOK"; return; }
        if (OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null) != null
                || !OpenMaverFeatures.alive(player)
                || Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isUsingItem", "func_71039_bw", null))
                || OpenMaverFeatures.read(player, "ridingEntity", "field_70154_o", null) != null
                || Boolean.TRUE.equals(OpenMaverFeatures.read(player, "isInWeb", "field_70134_J", null))
                || Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isInWater", "func_70090_H", null))
                || Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isInLava", "func_180799_ab", null))
                || Mouse.isButtonDown(0) || Mouse.isButtonDown(1)) {
            status = "PLAYER BUSY"; return;
        }
        if (enabled("Scaffold") || enabled("Scaffold2") || enabled("GodBridge")
                || enabled("Chest Stealer") || enabled("Criticals")
                || OpenMaverFeatures.velocityActionUsedThisTick()) {
            status = "OTHER ACTION"; return;
        }
        // 不再仅因 Aura/AutoClicker 启用而拒绝：只让位于本 tick 实际发生的动作。
        if (hasBadPackets()) { status = "BAD PACKETS"; return; }
        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        if (input == null || (OpenMaverFeatures.number(input, "moveForward", "field_78900_b") == 0.0D
                && OpenMaverFeatures.number(input, "moveStrafe", "field_78902_a") == 0.0D)) {
            status = "NOT MOVING"; return;
        }
        if (Boolean.TRUE.equals(OpenMaverFeatures.read(input, "sneak", "field_78899_d", Boolean.class))) {
            status = "SNEAKING"; return;
        }
        if (!Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isSprinting", "func_70051_ag", null))) {
            if (OpenMaverFeatures.callVoid(player, "setSprinting", "func_70031_b", null, Boolean.TRUE)) {
                status = "REQUEST SPRINT SYNC";
            } else {
                status = "SPRINT REQUEST FAILED";
            }
            return;
        }
        if (!Boolean.TRUE.equals(OpenMaverFeatures.read(player, "serverSprintState", "field_175171_bO", null))) {
            status = "WAIT SPRINT SYNC"; return;
        }
        // Zen NoXZ.getAttackTarget：Aura 优先，否则回落到准星生物。
        Object target = OpenMaverFeatures.velocityAuraTarget();
        if (target == null && slapCrosshair) {
            Object hit = OpenMaverFeatures.read(mc, "objectMouseOver", "field_71476_x", null);
            if ("ENTITY".equals(String.valueOf(OpenMaverFeatures.read(hit, "typeOfHit", "field_72313_a", null))))
                target = OpenMaverFeatures.read(hit, "entityHit", "field_72308_g", null);
        }
        if (!(target instanceof EntityLivingBase) || target == player || !OpenMaverFeatures.alive(target)
                || !crosshairWithinReach(player, target)
                || !Boolean.TRUE.equals(OpenMaverFeatures.call(player, "canEntityBeSeen", "func_70685_l", null, target))) {
            status = "NO TARGET"; return;
        }
        Object queue = OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
        if (queue == null) { status = "NO CONNECTION"; return; }
        try {
            Class<?> animationType = Class.forName("net.minecraft.network.play.client.C0APacketAnimation");
            Class<?> attackType = Class.forName("net.minecraft.network.play.client.C02PacketUseEntity");
            Class<?> actionType = Class.forName("net.minecraft.network.play.client.C02PacketUseEntity$Action");
            Object action = null;
            for (Object value : actionType.getEnumConstants()) if ("ATTACK".equals(String.valueOf(value))) action = value;
            Constructor<?> ctor = attackType.getConstructor(net.minecraft.entity.Entity.class, actionType);
            Object animation = animationType.getConstructor().newInstance();
            Object attack = ctor.newInstance(target, action);
            synchronized (ACTION_LOCK) {
                if (ACTIONS.get() != 0 || reservedAttack != null) { status = "BAD PACKETS"; return; }
                reservedAttack = attack;
                reservedAttackWritten = false;
            }
            // Myau Slap_Attack：C0A -> C02 -> XZ *= .6 -> sprint=false。
            // 这里不调用 controller.attackEntity，否则会再执行一次原版减速。
            if (!OpenMaverFeatures.callVoid(queue, "addToSendQueue", "func_147297_a", null, animation)
                    || !OpenMaverFeatures.callVoid(queue, "addToSendQueue", "func_147297_a", null, attack)) {
                // 保留预约直到连接/模式重置，避免部分发包失败后继续补攻击。
                attackWindow = 0;
                status = "SEND FAILED"; return;
            }
            OpenMaverFeatures.setNumber(player, "motionX", "field_70159_w",
                    OpenMaverFeatures.number(player, "motionX", "field_70159_w") * 0.6D);
            OpenMaverFeatures.setNumber(player, "motionZ", "field_70179_y",
                    OpenMaverFeatures.number(player, "motionZ", "field_70179_y") * 0.6D);
            if (!OpenMaverFeatures.callVoid(player, "setSprinting", "func_70031_b", null, Boolean.FALSE)) {
                attackWindow = 0;
                status = "SPRINT RESET FAILED"; return;
            }
            sprintHits++;
            status = "HIT";
        } catch (Throwable error) {
            attackWindow = 0;
            status = "ATTACK FAILED";
            if (!packetWarned) { packetWarned = true; Logger.warn("Velocity", "Slap_Attack 发包失败: " + error); }
        }
    }

    private static void updateSprintHit(Minecraft mc, Object player) {
        if (OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null) != null
                || !OpenMaverFeatures.alive(player)
                || Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isUsingItem", "func_71039_bw", null))
                || OpenMaverFeatures.read(player, "ridingEntity", "field_70154_o", null) != null
                || Mouse.isButtonDown(0) || Mouse.isButtonDown(1)) {
            status = "INPUT BUSY";
            return;
        }
        // 不叠加其它模块或手动攻击，避免同 tick 多动作/重复减速。
        for (String name : ACTION_MODULES) {
            Module other = ModuleManager.getModule(name);
            if (other != null && other.isEnabled()) {
                status = "OTHER ACTION";
                return;
            }
        }
        // setSprinting(true) 到 C0B 发出之间存在一 tick 窗口。只接受原版已发出的状态，
        // 不提前伪造 START_SPRINTING，也不手写 serverSprintState。
        if (!Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isSprinting", "func_70051_ag", null))
                || !Boolean.TRUE.equals(OpenMaverFeatures.read(player,
                        "serverSprintState", "field_175171_bO", null))) {
            status = "WAIT SPRINT SYNC";
            return;
        }
        Object hit = OpenMaverFeatures.read(mc, "objectMouseOver", "field_71476_x", null);
        Object target = OpenMaverFeatures.read(hit, "entityHit", "field_72308_g", null);
        if (!"ENTITY".equals(String.valueOf(OpenMaverFeatures.read(hit, "typeOfHit", "field_72313_a", null)))
                || !(target instanceof EntityLivingBase) || target == player
                || !OpenMaverFeatures.alive(target) || !crosshairWithinReach(player, target)
                || !Boolean.TRUE.equals(OpenMaverFeatures.call(player,
                        "canEntityBeSeen", "func_70685_l", null, target))) {
            status = "NO TARGET";
            return;
        }
        Object hurt = OpenMaverFeatures.read(target, "hurtTime", "field_70737_aN", null);
        if (hurt instanceof Number && ((Number) hurt).intValue() > 0) {
            status = "TARGET HURT";
            return;
        }
        Object controller = OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        if (controller == null) { status = "NO CONTROLLER"; return; }
        double oldX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
        double oldZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
        if (oldX * oldX + oldZ * oldZ < 0.0009D) { status = "LOW MOTION"; return; }
        attackWindow = 0; // 每份 S12 最多一次尝试，失败也不爆发重试。
        // 原版 1.8 顺序：C0A -> controller.attackEntity(C02) -> 原版自身减速。
        boolean swung = OpenMaverFeatures.callVoid(player, "swingItem", "func_71038_i", null);
        boolean attacked = swung && OpenMaverFeatures.callVoid(controller,
                "attackEntity", "func_78764_a", null, player, target);
        double newX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
        double newZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
        if (attacked && Math.abs(newX - oldX * 0.6D) < 1.0E-8D
                && Math.abs(newZ - oldZ * 0.6D) < 1.0E-8D) {
            sprintHits++;
            status = "HIT";
            if (sprintHits <= 3 || sprintHits % 20 == 0) {
                Logger.info("Velocity", "SPRINT HIT #" + sprintHits + " 原版水平减速生效（保留 60%），S12 未修改");
            }
        } else {
            status = "NO SLOWDOWN";
        }
    }

    /** 重验当前视线与 3 格射线，避免上一帧 objectMouseOver 已过期或被 Reach 扩大。 */
    static boolean crosshairWithinReach(Object player, Object target) {
        AxisAlignedBB box = OpenMaverFeatures.boundingBox(target);
        if (box == null) return false;
        double yaw = Math.toRadians(OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z"));
        double pitch = Math.toRadians(OpenMaverFeatures.number(player, "rotationPitch", "field_70125_A"));
        double[] origin = {OpenMaverFeatures.number(player, "posX", "field_70165_t"),
                OpenMaverFeatures.number(player, "posY", "field_70163_u") + OpenMaverFeatures.eyeHeight(player),
                OpenMaverFeatures.number(player, "posZ", "field_70161_v")};
        double[] direction = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        double near = 0.0D, far = 3.0D;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(direction[i]) < 1.0E-8D) {
                if (origin[i] < min[i] || origin[i] > max[i]) return false;
            } else {
                double a = (min[i] - origin[i]) / direction[i], b = (max[i] - origin[i]) / direction[i];
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (near > far) return false;
            }
        }
        return true;
    }

    private static Field intField(Object target, String... names) {
        Field field = OpenMaverFeatures.findField(target, names);
        return field != null && field.getType() == Integer.TYPE ? field : null;
    }

    private static void maintainHook(Minecraft mc, Object player) {
        Object handler = OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
        if (handler == null) handler = OpenMaverFeatures.call(mc, "getNetHandler", "func_147114_u", null);
        if (handler != hookNetHandler) {
            generation++;
            attackWindow = 0;
            detachHook("连接变化");
            resetActions();
            hookNetHandler = handler;
            hookAttemptTick = clientTick - 20;
        }
        if (clientTick - hookAttemptTick < 20) return;
        hookAttemptTick = clientTick;
        if (hookPipeline != null && hookPipeline.names().contains(AttackReducePacketHook.NAME)) return;
        detachHook("重新安装");
        generation++;
        attackWindow = 0;
        resetActions();
        if (handler == null) return;
        try {
            Object manager = fieldByTypeName(handler, "net.minecraft.network.NetworkManager");
            Object channel = fieldByTypeName(manager, "io.netty.channel.Channel");
            Object value = OpenMaverFeatures.call(channel, "pipeline", "pipeline", null);
            if (!(value instanceof ChannelPipeline)) throw new IllegalStateException("NetworkManager.channel 不可用");
            ChannelPipeline pipeline = (ChannelPipeline) value;
            String base = pickBaseName(pipeline.names());
            if (base == null) throw new IllegalStateException("缺少 packet_handler");
            // 清掉本工程旧实例，避免把其他 handler 当成自己的实例复用。
            if (pipeline.names().contains(AttackReducePacketHook.NAME)) pipeline.remove(AttackReducePacketHook.NAME);
            AttackReducePacketHook hook = new AttackReducePacketHook();
            pipeline.addBefore(base, AttackReducePacketHook.NAME, hook);
            hookPipeline = pipeline;
            hookInstance = hook;
            hookInstalled = true;
            hookWarned = false;
            Logger.info("Velocity", "防击退包处理器已安装");
        } catch (Throwable error) {
            if (!hookWarned) {
                hookWarned = true;
                Logger.warn("Velocity", "包观察器安装失败，20 tick 后重试: " + error);
            }
        }
    }

    private static void detachHook(String reason) {
        AttackReducePacketHook old = hookInstance;
        hookInstance = null;
        if (hookPipeline != null && old != null) {
            try { hookPipeline.remove(old); } catch (Throwable ignored) { }
        }
        hookPipeline = null;
        hookInstalled = false;
    }

    static String pickBaseName(List<String> names) {
        return names != null && names.contains("packet_handler") ? "packet_handler" : null;
    }

    private static Object fieldByTypeName(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().getName().equals(name)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null) return value;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    public static void load(Properties values) {
        int loadedMode;
        try { loadedMode = Integer.parseInt(values.getProperty("velocity.mode", "2")); }
        catch (Exception ignored) { loadedMode = MODE_SLAP_ATTACK; }
        mode = validMode(loadedMode);
        generation++;
        attackWindow = 0;
        resetActions();
        lastDebugState = null;
        chatDebug = Boolean.parseBoolean(values.getProperty("velocity.chatDebug", "true"));
        slapExact = Boolean.parseBoolean(values.getProperty("velocity.slapExact", "true"));
        slapCrosshair = Boolean.parseBoolean(values.getProperty("velocity.slapCrosshair", "true"));
        horizontal = readPercent(values, "velocity.horizontal", 0.0D);
        vertical = readPercent(values, "velocity.vertical", 100.0D);
    }

    private static double readPercent(Properties values, String key, double fallback) {
        try { return percent(Double.parseDouble(values.getProperty(key)), fallback); }
        catch (Exception ignored) { return fallback; }
    }

    private static double percent(double value, double fallback) {
        return Double.isNaN(value) || Double.isInfinite(value)
                ? fallback : Math.max(0.0D, Math.min(100.0D, value));
    }

    public static String save() {
        return "velocity.mode=" + mode + "\nvelocity.horizontal=" + horizontal + "\nvelocity.vertical=" + vertical
                + "\nvelocity.slapExact=" + slapExact + "\nvelocity.slapCrosshair=" + slapCrosshair
                + "\nvelocity.chatDebug=" + chatDebug + "\n";
    }
    public static int getMode() { return mode; }
    public static void setMode(int value) {
        mode = validMode(value);
        generation++;
        attackWindow = 0;
        resetActions();
        status = "IDLE";
        lastDebugState = null;
        OpenMaverFeatures.markDirty();
    }
    public static long getSprintHits() { return sprintHits; }
    private static int validMode(int value) {
        return value >= MODE_SPRINT_HIT && value <= MODE_SLAP_ATTACK ? value : MODE_SLAP_ATTACK;
    }
    public static boolean getSlapExact() { return slapExact; }
    public static boolean getChatDebug() { return chatDebug; }
    public static void setChatDebug(boolean value) {
        chatDebug = value;
        lastDebugState = null;
        OpenMaverFeatures.markDirty();
    }
    public static boolean getSlapCrosshair() { return slapCrosshair; }
    public static void setSlapExact(boolean value) { slapExact = value; OpenMaverFeatures.markDirty(); }
    public static void setSlapCrosshair(boolean value) { slapCrosshair = value; OpenMaverFeatures.markDirty(); }
    public static String getStatus() { return status; }
    public static double getHorizontal() { return horizontal; }
    public static double getVertical() { return vertical; }
    public static void setHorizontal(double value) {
        horizontal = percent(value, 0.0D);
        OpenMaverFeatures.markDirty();
    }
    public static void setVertical(double value) {
        vertical = percent(value, 100.0D);
        OpenMaverFeatures.markDirty();
    }
    public static boolean isPacketHookInstalled() {
        return hookInstalled && hookPipeline != null && hookPipeline.context(AttackReducePacketHook.NAME) != null;
    }
    public static long getKnockbackApplied() { return KB_SEEN.get(); }
    public static String getSummary() {
        return mode == MODE_SLAP_ATTACK ? "SLAP ATTACK" : mode == MODE_SPRINT_HIT ? "SPRINT HIT"
                : "SIMPLE H " + Math.round(horizontal) + "% / V " + Math.round(vertical) + "%";
    }
}
