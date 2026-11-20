import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.AxisAlignedBB;

import org.lwjgl.input.Keyboard;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GodBridge（神桥 / 蹲起搭路）—— 按手动搭桥教材重写。
 *
 * <p>教材里的三种手法，就是本模块的三个模式。所有速度都是 20 TPS 下按
 * “每个方块要几个 tick”反推出来的：
 *
 * <pre>
 *   SPEED BRIDGE  Shift + S，Shift 一直按住            12 tick/方块 -> 1.67 方块/秒
 *   DIAGONAL 45   Shift + S + D，视角与桥成 45°        10 tick/方块 -> 2.00 方块/秒
 *   SNEAK TOGGLE  蹲起搭路（放块后松 Shift，贴边再按回）  7 tick/方块 -> 2.86 方块/秒
 * </pre>
 *
 * <h3>蹲起搭路的循环（教材原文 → 状态机）</h3>
 *
 * <pre>
 *   1. “准星对准方块边缘，按一下右键”
 *        -> 静默转向把 aim 压到目标格支撑面的上沿，C08 迟一 tick 发出
 *   2. “与此同时松开 shift”
 *        -> PHASE_RELEASE：写完 C08 的下一 tick 立刻把 keyBindSneak 松开，
 *           玩家恢复全速（0.216 格/tick），这一步是 3 方块/秒的来源
 *   3. “然后尽快按下 shift，防止掉落”
 *        -> PHASE_RESNEAK：releaseTicks 到预算、或贴边余量 <= 0.12、或脚下一空，
 *           三者任一成立就立刻按回 Shift（原版潜行会锁住边缘）
 *   4. “等待潜行走到下一个方块边缘……再按一下右键”
 *        -> PHASE_WAIT_EDGE：潜行走到贴边（余量 <= edgeMargin），
 *           且距离上次放置超过 minPlaceInterval 才规划下一块
 * </pre>
 *
 * <p>教材里的 45° 斜桥：S + D 的移动方向相对视角是 225°（等价于与桥线 45°），
 * 所以 {@link #travelYaw(float, int, int)} 把“相机朝向 + 按键”换算成真正的行进方向，
 * 目标格沿这条对角线上前 0.5~0.6 格取；视角本身不用玩家手动摆正。
 *
 * <h3>和 Scaffold 的关系</h3>
 *
 * <p>{@code Scaffold} 是 OpenZen 的移植（Telly/Normal/KeepY，靠旋转表与跳跃节拍）。
 * 神桥是另一套模型：不跳、不锁 targetYLevel，只做“贴边判定 + Shift 节拍 + 点方块边缘”。
 * 两边同时开会在同一个 tick 抢 C08 名额与 Shift 键，所以 {@code GodBridge.onEnable()}
 * 会关掉 Scaffold，本类 tick 里也会再挡一次（见 {@code OpenMaverFeatures} 的调用顺序）。
 *
 * <p>静默转向复用 {@link ScaffoldMovementInput} 这个 movementInput hook：
 * {@link #afterMovementInput(Object)} 把 aim 写进 rotationYaw/Pitch（本 tick 的 C03
 * 带回服务端），同时把输入向量换算到 aim 坐标系，保证世界位移仍然是玩家按的方向；
 * {@link #onTickEnd(Minecraft)} 在渲染前恢复相机角度。
 *
 * <p>行为说明与教材对照见 {@code design/GODBRIDGE_HANDOFF.md}，
 * 回归测试见 {@code tools/godbridge-test/}。
 */
public final class GodBridgeFeature {

    // ==================================================================
    // 模式 / 阶段
    // ==================================================================

    /** 教材第一种：Shift + S，全程潜行。 */
    public static final int MODE_SPEEDBRIDGE = 0;
    /** 教材第二种：Shift + S + D，视角与桥成 45°。 */
    public static final int MODE_DIAGONAL = 1;
    /** 教材第三种：蹲起搭路（放块后松 Shift，贴边按回）。 */
    public static final int MODE_SNEAK_TOGGLE = 2;
    public static final String[] MODE_NAMES = {
            "SPEED BRIDGE", "DIAGONAL 45", "SNEAK TOGGLE"
    };

    /** 没在搭路（没按 S / 打开 GUI / 死亡）。 */
    public static final int PHASE_IDLE = 0;
    /** 潜行走到边缘，等“贴边”条件成立后放置。 */
    public static final int PHASE_WAIT_EDGE = 1;
    /** 刚放完一块：松开 Shift 全速前进。 */
    public static final int PHASE_RELEASE = 2;
    /** 松开预算用完 / 快掉下去：按回 Shift。 */
    public static final int PHASE_RESNEAK = 3;
    public static final String[] PHASE_NAMES = {
            "IDLE", "EDGE", "RELEASE", "RESNEAK"
    };

    // ==================================================================
    // 设置（config/openmaver.properties 的 godbridge.*）
    // ==================================================================

    private static int mode = MODE_SNEAK_TOGGLE;
    /** 松 Shift 的 tick 预算（教材的“松开 shift … 尽快按下 shift”）。 */
    private static int sneakWindow = 3;
    /** 贴边判定余量：箱体前沿到落差 <= 该值时算“站在方块边缘”。 */
    private static double edgeMargin = 0.30D;
    /** 允许松 Shift 的最小余量（贴着边缘时不能松，先潜行走过去）。 */
    private static final double RELEASE_MIN_CLEARANCE = 0.45D;
    /** 无论预算剩多少，余量低于该值立刻按回 Shift。 */
    private static final double EMERGENCY_CLEARANCE = 0.12D;
    /** 掉落时的兜底放置：脚下这格空了就往下补。 */
    private static boolean safety = true;
    /** 手部渲染保持开启时的物品（服务端仍认为手持方块）。 */
    private static boolean itemSpoof = true;
    /** 神桥 HUD（实测方块/秒）。 */
    private static boolean hud = true;

    /** 同一个目标最多重试几次（每次重试 = 1 tick）。 */
    static final int MAX_PENDING_RETRIES = 1;
    /** 检索目标格时沿行进方向的取样距离（先近后远）。 */
    private static final double[] TARGET_ADVANCES = {0.60D, 0.45D, 0.75D, 0.30D, 0.90D};
    /** 面中心到眼睛的最大距离平方（4.5²，与 Scaffold 一致）。 */
    private static final double REACH_SQ = 20.25D;
    /** HUD 的测速窗口（tick）。 */
    private static final int SPEED_WINDOW_TICKS = 40;

    /** 1.8.9 EnumFacing：0 DOWN / 1 UP / 2 NORTH / 3 SOUTH / 4 WEST / 5 EAST。 */
    private static final String[] FACING_NAMES = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
    private static final int[][] FACING_DELTAS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };
    private static final int[] OPPOSITE_FACING = {1, 0, 3, 2, 5, 4};

    // ==================================================================
    // 运行状态
    // ==================================================================

    private static boolean active;
    private static boolean failed;
    private static long currentTick;

    private static int phase = PHASE_IDLE;
    private static int phaseTicks;
    private static long lastPlaceTick = Long.MIN_VALUE;

    private static float cameraYaw;
    private static float cameraPitch;
    private static float aimYaw;
    private static float aimPitch;
    private static boolean aimThisTick;
    private static boolean aimApplied;
    private static boolean rotationDirty;
    private static float prevAimYaw = Float.NaN;
    private static float prevAimPitch = Float.NaN;

    /** 本 tick 的行进方向（世界 yaw）与贴边余量。 */
    private static float travelYaw;
    private static double edgeClearance;

    private static boolean hookArmed;
    private static long hookArmedNanos;
    private static Object aimPlayer;
    private static Object trackedPlayer;

    private static int oldSlot = -1;
    private static int blockSlot = -1;
    private static int serverSlot = -1;

    private static boolean hasPending;
    private static boolean pendingSent;
    private static int pendingX;
    private static int pendingY;
    private static int pendingZ;
    private static int pendingFace;
    private static int pendingRetries;
    private static float sentYaw;
    private static float sentPitch;

    /** HUD：本次开启以来放了多少块 + 最近 2 秒的放置刻度。 */
    private static int placedTotal;
    private static final long[] placeTickRing = new long[64];
    private static int placeRingIndex;

    // ------------------------------------------------------------------
    // 反射缓存
    // ------------------------------------------------------------------

    private static Class<?> blockPosType;
    private static Constructor<?> blockPosConstructor;
    private static Class<?> vec3Type;
    private static Constructor<?> vec3Constructor;
    private static Object[] facings;
    private static final Map<Class<?>, Boolean> SOLID_CACHE =
            new ConcurrentHashMap<Class<?>, Boolean>();
    private static final Map<Class<?>, Boolean> PLACEABLE_CACHE =
            new ConcurrentHashMap<Class<?>, Boolean>();

    private GodBridgeFeature() {
    }

    // ==================================================================
    // 数据载体
    // ==================================================================

    /** 目标格 + 要点击的支撑面 + 对应的 aim。 */
    private static final class Placement {
        final int targetX;
        final int targetY;
        final int targetZ;
        final int supportX;
        final int supportY;
        final int supportZ;
        final int face;
        final float aimYaw;
        final float aimPitch;

        Placement(int targetX, int targetY, int targetZ, int supportX, int supportY,
                int supportZ, int face, float aimYaw, float aimPitch) {
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
            this.supportX = supportX;
            this.supportY = supportY;
            this.supportZ = supportZ;
            this.face = face;
            this.aimYaw = aimYaw;
            this.aimPitch = aimPitch;
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** ClientTickEvent.START：在 vanilla 发送 C03 之前跑完状态机。 */
    public static void onTickStart(Minecraft mc) {
        Object player = mc == null ? null
                : OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        if (player != null) {
            if (rotationDirty) {
                restoreCameraView(player);
            }
            cameraYaw = playerYaw(player);
            cameraPitch = playerPitch(player);
            aimPlayer = player;
            aimApplied = false;
            aimThisTick = false;
            if (player != trackedPlayer) {
                trackedPlayer = player;
                if (active) {
                    // 重生 / 切维度：服务端会重置选中槽位，重新同步一次。
                    serverSlot = -1;
                    oldSlot = currentHotbarSlot(player);
                }
            }
            hookArmed = true;
            hookArmedNanos = System.nanoTime();
        } else {
            hookArmed = false;
        }

        Module module = ModuleManager.getModule("GodBridge");
        if (module == null || !module.isEnabled()) {
            if (active) {
                disable(mc);
            }
            return;
        }
        if (failed) {
            return;
        }
        try {
            if (!active) {
                enable(mc, player);
            }
            Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
            Object screen = OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null);
            if (player == null || world == null || screen != null
                    || !OpenMaverFeatures.alive(player)) {
                hasPending = false;
                aimThisTick = false;
                return;
            }
            ensureMovementHook(mc, player);
            tick(mc, player, world);
        } catch (Throwable error) {
            failed = true;
            active = false;
            hasPending = false;
            aimThisTick = false;
            System.err.println("[OpenMaver] GodBridge disabled after error: " + error);
        }
    }

    /** ClientTickEvent.END：渲染前恢复相机，物品伪装复位。 */
    public static void onTickEnd(Minecraft mc) {
        hookArmed = false;
        Object player = aimPlayer;
        if (player == null || !active) {
            aimApplied = false;
            return;
        }
        if (rotationDirty) {
            restoreCameraView(player);
            if (!Float.isNaN(prevAimYaw)) {
                setFloat(player, "renderYawOffset", "field_70761_aq", prevAimYaw);
                setFloat(player, "rotationYawHead", "field_70759_as", prevAimYaw);
            }
        }
        aimApplied = false;
        if (itemSpoof && oldSlot >= 0 && serverSlot >= 0) {
            setSlotClientOnly(player, oldSlot);
        }
    }

    private static void enable(Minecraft mc, Object player) {
        active = true;
        failed = false;
        phase = PHASE_IDLE;
        phaseTicks = 0;
        lastPlaceTick = Long.MIN_VALUE;
        blockSlot = -1;
        serverSlot = -1;
        hasPending = false;
        pendingSent = false;
        pendingRetries = 0;
        aimThisTick = false;
        aimApplied = false;
        rotationDirty = false;
        placedTotal = 0;
        placeRingIndex = 0;
        for (int i = 0; i < placeTickRing.length; i++) {
            placeTickRing[i] = Long.MIN_VALUE;
        }
        if (player != null) {
            oldSlot = currentHotbarSlot(player);
            serverSlot = oldSlot;
            trackedPlayer = player;
            cameraYaw = playerYaw(player);
            cameraPitch = playerPitch(player);
            aimYaw = cameraYaw;
            aimPitch = cameraPitch;
            aimPlayer = player;
        } else {
            oldSlot = -1;
            serverSlot = -1;
        }
        OpenMaverDebug.trace("godbridge", "enabled mode=" + MODE_NAMES[clamp(mode, 0, 2)]
                + " sneakWindow=" + sneakWindow + " edgeMargin=" + edgeMargin);
    }

    private static void disable(Minecraft mc) {
        Object player = aimPlayer;
        if (player != null) {
            restoreSlot(player);
            restoreCameraView(player);
        }
        restoreKeys(mc);
        active = false;
        phase = PHASE_IDLE;
        phaseTicks = 0;
        hasPending = false;
        aimThisTick = false;
        aimApplied = false;
        blockSlot = -1;
        OpenMaverDebug.trace("godbridge", "disabled");
    }

    /** 配置重载 / 世界切换 / Panic 时调用。 */
    public static void reset() {
        Minecraft mc = Minecraft.getMinecraft();
        Object player = null;
        if (mc != null) {
            player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        }
        if (active && player != null) {
            restoreSlot(player);
            restoreCameraView(player);
        }
        restoreKeys(mc);
        active = false;
        failed = false;
        phase = PHASE_IDLE;
        phaseTicks = 0;
        hasPending = false;
        aimThisTick = false;
        aimApplied = false;
        rotationDirty = false;
        blockSlot = -1;
    }

    // ==================================================================
    // 主 tick：教材循环
    // ==================================================================

    private static void tick(Minecraft mc, Object player, Object world) {
        currentTick++;

        // 1) 上一 tick 写进 C03 的 aim：先校验再放置（Grim RotationPlace 的顺序）。
        boolean placedThisTick = flushPending(mc, player, world);

        // 2) 读按键：教材的搭路姿势永远是“按 S 后退”，S+D 是 45° 斜桥。
        boolean forwardKey = keyDown(mc, "keyBindForward");
        boolean backKey = keyDown(mc, "keyBindBack");
        boolean leftKey = keyDown(mc, "keyBindLeft");
        boolean rightKey = keyDown(mc, "keyBindRight");
        int forward = (forwardKey ? 1 : 0) - (backKey ? 1 : 0);
        int strafe = (rightKey ? 1 : 0) - (leftKey ? 1 : 0);

        if (forward >= 0 || (forward == 0 && strafe == 0)) {
            // 没在后退搭路：把 Shift 还给物理键盘，不锁玩家输入。
            phase = PHASE_IDLE;
            phaseTicks = 0;
            hasPending = false;
            aimThisTick = false;
            restoreSneakToPhysical(mc);
            return;
        }

        travelYaw = travelYaw(cameraYaw, forward, strafe);
        double dirX = -Math.sin(Math.toRadians(travelYaw));
        double dirZ = Math.cos(Math.toRadians(travelYaw));
        edgeClearance = edgeClearance(player, world, dirX, dirZ);

        boolean onGround = OpenMaverFeatures.scaffoldOnGround(player);
        updateHotbar(player);

        // 3) Shift 节拍：教材的“松一下 → 尽快按回”。
        if (mode == MODE_SNEAK_TOGGLE) {
            boolean canRelease = edgeClearance >= RELEASE_MIN_CLEARANCE;
            phaseTicks++;
            int nextPhase = stepPhase(phase, placedThisTick, canRelease, phaseTicks,
                    sneakReleaseBudget(mode, sneakWindow),
                    edgeClearance <= EMERGENCY_CLEARANCE, onGround);
            if (nextPhase != phase) {
                phase = nextPhase;
                phaseTicks = 0;
            }
        } else {
            phase = PHASE_WAIT_EDGE;
            phaseTicks = 0;
        }

        // 4) 贴边 + 冷却到了才规划下一块；目标沿行进方向取前 0.5~0.6 格。
        long sincePlace = currentTick - lastPlaceTick;
        boolean atEdge = edgeClearance <= edgeMargin && edgeClearance >= -0.001D;
        Placement target = null;
        if (mayPlace(phase, atEdge, sincePlace, minPlaceInterval(mode), true)) {
            target = findPlacement(player, world, travelYaw);
        }
        if (target == null && safety && !onGround
                && OpenMaverFeatures.number(player, "motionY", "field_70181_x") < -0.08D) {
            // 已经踩空：往下补一块（教材没写，但松 Shift 的窗口判断失误时会用到）。
            target = findFallbackPlacement(player, world, travelYaw);
        }

        if (target != null) {
            aimYaw = target.aimYaw;
            aimPitch = target.aimPitch;
            aimThisTick = true;
            pendingX = target.supportX;
            pendingY = target.supportY;
            pendingZ = target.supportZ;
            pendingFace = target.face;
            pendingRetries = 0;
            pendingSent = false;
            hasPending = true;
        }

        // 5) 写 Shift 键：RELEASE 阶段松开，其余时间按住。
        boolean desiredSneak = mode != MODE_SNEAK_TOGGLE || sneakForPhase(phase);
        if (placedThisTick && mode == MODE_SNEAK_TOGGLE) {
            desiredSneak = false;
        }
        setKeyPressed(mc, "keyBindSneak", desiredSneak);
    }

    // ==================================================================
    // 静默转向 / MoveFix（movementInput hook）
    // ==================================================================

    static void afterMovementInput(Object input) {
        if (!hookArmed) {
            return;
        }
        if (System.nanoTime() - hookArmedNanos > 500_000_000L) {
            hookArmed = false;
            return;
        }
        if (!active || failed || !aimThisTick || aimApplied) {
            return;
        }
        Object player = aimPlayer;
        if (player == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null) != player) {
            return;
        }
        try {
            float originalForward = (float) OpenMaverFeatures.number(
                    input, "moveForward", "field_78900_b");
            float originalStrafe = (float) OpenMaverFeatures.number(
                    input, "moveStrafe", "field_78901_c");
            float forward = originalForward;
            float strafe = originalStrafe;

            // MoveFix：把“相机坐标系里的按键”换算到 aim 坐标系，世界位移方向不变。
            if (forward != 0.0F || strafe != 0.0F) {
                float cameraMoveYaw = adjustYaw(cameraYaw,
                        signum(forward), signum(strafe));
                float angle = wrapDegrees(cameraMoveYaw - aimYaw + 22.5F);
                int sector = ((int) (angle + 180.0F) / 45) % 8;
                switch (sector) {
                    case 0: forward = -1.0F; strafe = 0.0F; break;
                    case 1: forward = -1.0F; strafe = 1.0F; break;
                    case 2: forward = 0.0F; strafe = 1.0F; break;
                    case 3: forward = 1.0F; strafe = 1.0F; break;
                    case 4: forward = 1.0F; strafe = 0.0F; break;
                    case 5: forward = 1.0F; strafe = -1.0F; break;
                    case 6: forward = 0.0F; strafe = -1.0F; break;
                    case 7: forward = -1.0F; strafe = -1.0F; break;
                    default: break;
                }
                if (forward > 0.0F && forward < 0.8F) {
                    float scale = 0.8F / forward;
                    forward *= scale;
                    strafe *= scale;
                }
            }
            if (forward != originalForward || strafe != originalStrafe) {
                OpenMaverFeatures.setNumber(input, "moveForward", "field_78900_b", forward);
                OpenMaverFeatures.setNumber(input, "moveStrafe", "field_78901_c", strafe);
            }

            float writeYaw = OpenMaverFeatures.continuousYaw(playerYaw(player), aimYaw);
            setFloat(player, "rotationYaw", "field_70177_z", writeYaw);
            setFloat(player, "rotationPitch", "field_70125_A", aimPitch);
            aimApplied = true;
            rotationDirty = true;
            prevAimYaw = writeYaw;
            prevAimPitch = aimPitch;
            if (hasPending && !pendingSent) {
                sentYaw = writeYaw;
                sentPitch = aimPitch;
                pendingSent = true;
            }
        } catch (Throwable ignored) {
            // hook 失败不能打断玩家 tick。
        }
    }

    private static void restoreCameraView(Object player) {
        if (player == null) {
            rotationDirty = false;
            aimApplied = false;
            return;
        }
        float restoreYaw = OpenMaverFeatures.continuousYaw(playerYaw(player), cameraYaw);
        setFloat(player, "rotationYaw", "field_70177_z", restoreYaw);
        setFloat(player, "rotationPitch", "field_70125_A", cameraPitch);
        setFloat(player, "prevRotationYaw", "field_70126_B", restoreYaw);
        setFloat(player, "prevRotationPitch", "field_70127_C", cameraPitch);
        setFloat(player, "renderArmYaw", "field_71154_f", cameraYaw);
        setFloat(player, "prevRenderArmYaw", "field_71163_h", cameraYaw);
        setFloat(player, "renderArmPitch", "field_71155_g", cameraPitch);
        setFloat(player, "prevRenderArmPitch", "field_71164_i", cameraPitch);
        setFloat(player, "renderYawOffset", "field_70761_aq", cameraYaw);
        setFloat(player, "rotationYawHead", "field_70759_as", cameraYaw);
        rotationDirty = false;
        aimApplied = false;
    }

    /** 安装 {@link ScaffoldMovementInput}（与 Scaffold 共用同一个 hook）。 */
    private static void ensureMovementHook(Minecraft mc, Object player) {
        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        if (input instanceof ScaffoldMovementInput) {
            return;
        }
        if (input != null
                && !"net.minecraft.util.MovementInputFromOptions".equals(
                        input.getClass().getName())) {
            OpenMaverDebug.noticeOnce("godbridge.hook.custom",
                    "custom MovementInput detected; silent rotation hook not installed");
            return;
        }
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        if (!(settings instanceof GameSettings)) {
            return;
        }
        ScaffoldMovementInput hook = new ScaffoldMovementInput((GameSettings) settings);
        if (input != null) {
            OpenMaverFeatures.setNumber(hook, "moveForward", "field_78900_b",
                    OpenMaverFeatures.number(input, "moveForward", "field_78900_b"));
            OpenMaverFeatures.setNumber(hook, "moveStrafe", "field_78901_c",
                    OpenMaverFeatures.number(input, "moveStrafe", "field_78901_c"));
            setBoolean(hook, "jump", "field_78903_e", Boolean.TRUE.equals(
                    OpenMaverFeatures.read(input, "jump", "field_78903_e", Boolean.class)));
            setBoolean(hook, "sneak", "field_78899_d", Boolean.TRUE.equals(
                    OpenMaverFeatures.read(input, "sneak", "field_78899_d", Boolean.class)));
        }
        if (OpenMaverFeatures.writeObjectField(player, "movementInput", "field_71158_b", hook)) {
            OpenMaverDebug.noticeOnce("godbridge.hook",
                    "ScaffoldMovementInput installed (godbridge silent aim + move fix)");
        }
    }

    // ==================================================================
    // 贴边判定
    // ==================================================================

    /**
     * 玩家“沿行进方向还能走多远才踩空”的余量（格）。
     *
     * <p>做法：把脚下一块薄片（{@code minY-0.15 .. minY-0.02}）沿行进方向以 0.05 格
     * 步进平移，第一次取不到任何碰撞箱的位置就是落差。教材里的“方块边缘”就是
     * 这个值趋近 0 的时刻。
     */
    private static double edgeClearance(Object player, Object world, double dirX, double dirZ) {
        AxisAlignedBB box = OpenMaverFeatures.boundingBox(player);
        if (box == null) {
            return 0.0D;
        }
        double minY = box.minY - 0.15D;
        double maxY = box.minY - 0.02D;
        for (int step = 1; step <= 12; step++) {
            double d = step * 0.05D;
            AxisAlignedBB probe = new AxisAlignedBB(
                    box.minX + dirX * d, minY, box.minZ + dirZ * d,
                    box.maxX + dirX * d, maxY, box.maxZ + dirZ * d);
            Object colliding = OpenMaverFeatures.call(world, "getCollidingBoundingBoxes",
                    "func_72945_a", null, player, probe);
            if (!(colliding instanceof List) || ((List<?>) colliding).isEmpty()) {
                return (step - 1) * 0.05D;
            }
        }
        return 0.60D;
    }

    // ==================================================================
    // 目标格搜索：“准星对准方块边缘”
    // ==================================================================

    /**
     * 沿行进方向取下一格，枚举它的六个面找支撑面，按“最靠后（贴着自己脚下这块）”
     * 优先打分，最后把 aim 指向该面的上沿（教材的“方块边缘”）。
     */
    private static Placement findPlacement(Object player, Object world, float travelYaw) {
        double posX = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double posY = OpenMaverFeatures.number(player, "posY", "field_70163_u");
        double posZ = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        double eyeY = posY + OpenMaverFeatures.eyeHeight(player);
        double dirX = -Math.sin(Math.toRadians(travelYaw));
        double dirZ = Math.cos(Math.toRadians(travelYaw));
        int baseY = floor(posY) - 1;

        for (int i = 0; i < TARGET_ADVANCES.length; i++) {
            double advance = TARGET_ADVANCES[i];
            int tx = floor(posX + dirX * advance);
            int tz = floor(posZ + dirZ * advance);
            Placement found = probeCell(world, tx, baseY, tz, posX, eyeY, posZ, dirX, dirZ);
            if (found == null && i == 0) {
                // 同一格往下再看一层（半砖/台阶/掉一格的情况）。
                found = probeCell(world, tx, baseY - 1, tz, posX, eyeY, posZ, dirX, dirZ);
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 踩空兜底：脚下这一格空了就往下补。 */
    private static Placement findFallbackPlacement(Object player, Object world, float travelYaw) {
        double posX = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double posY = OpenMaverFeatures.number(player, "posY", "field_70163_u");
        double posZ = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        double eyeY = posY + OpenMaverFeatures.eyeHeight(player);
        double dirX = -Math.sin(Math.toRadians(travelYaw));
        double dirZ = Math.cos(Math.toRadians(travelYaw));
        int y = floor(posY) - 1;
        for (int drop = 0; drop <= 2; drop++) {
            int tx = floor(posX + dirX * 0.30D);
            int tz = floor(posZ + dirZ * 0.30D);
            Placement found = probeCell(world, tx, y - drop, tz,
                    posX, eyeY, posZ, dirX, dirZ);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Placement probeCell(Object world, int tx, int ty, int tz,
            double eyeX, double eyeY, double eyeZ, double dirX, double dirZ) {
        if (!isAir(world, tx, ty, tz)) {
            return null;
        }
        Placement best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int face = 0; face < 6; face++) {
            int[] delta = FACING_DELTAS[face];
            int sx = tx + delta[0];
            int sy = ty + delta[1];
            int sz = tz + delta[2];
            if (!isSolidSupport(world, sx, sy, sz)) {
                continue;
            }
            double centerX = tx + 0.5D + delta[0] * 0.5D;
            double centerY = ty + 0.5D + delta[1] * 0.5D;
            double centerZ = tz + 0.5D + delta[2] * 0.5D;
            double dx = centerX - eyeX;
            double dy = centerY - eyeY;
            double dz = centerZ - eyeZ;
            double lengthSq = dx * dx + dy * dy + dz * dz;
            if (lengthSq > REACH_SQ || lengthSq < 1.0E-6D) {
                continue;
            }
            // 面法线必须朝着眼睛，否则射线打不到。
            if ((dx * delta[0] + dy * delta[1] + dz * delta[2]) < 0.0D) {
                continue;
            }
            // 支撑面越靠后（身体下面那块）越优先：斜桥时它能避开侧向遮挡。
            double align = -(delta[0] * dirX + delta[2] * dirZ);
            double score = align * 2.0D - Math.sqrt(lengthSq) * 0.05D
                    + (face == 1 ? 0.35D : 0.0D);
            if (score <= bestScore) {
                continue;
            }
            double[] hit = hitVec(tx, ty, tz, face);
            float[] rotation = rotationTo(eyeX, eyeY, eyeZ, hit[0], hit[1], hit[2]);
            best = new Placement(tx, ty, tz, sx, sy, sz, OPPOSITE_FACING[face],
                    rotation[0], rotation[1]);
            bestScore = score;
        }
        return best;
    }

    /** 教材的“准星对准方块边缘”：面中心偏上沿 0.25，水平做小幅抖动。 */
    private static double[] hitVec(int tx, int ty, int tz, int face) {
        double x = tx + 0.5D;
        double y = ty + 0.5D;
        double z = tz + 0.5D;
        if (face == 0 || face == 1) {
            x += jitter(0.28D);
            z += jitter(0.28D);
        } else {
            y += 0.25D;
            if (face == 2 || face == 3) {
                x += jitter(0.28D);
            } else {
                z += jitter(0.28D);
            }
        }
        return new double[]{x, y, z};
    }

    private static double jitter(double max) {
        return (Math.random() * 2.0D - 1.0D) * max;
    }

    // ==================================================================
    // 放置（迟一 tick，先校验服务端已收到的 aim）
    // ==================================================================

    private static boolean flushPending(Minecraft mc, Object player, Object world) {
        if (!hasPending) {
            return false;
        }
        hasPending = false;
        if (!pendingSent || blockSlot < 0 || serverSlot != blockSlot) {
            return false;
        }
        if (!canPlaceNow(mc, player) || !isAir(world, pendingX, pendingY, pendingZ)) {
            return false;
        }
        float[] eye = eyePosition(player);
        Object mop = rayTraceFrom(world, eye, sentYaw, sentPitch, blockReachDistance());
        if (!mopMatches(mop, pendingX, pendingY, pendingZ, pendingFace)) {
            pendingRetries++;
            if (pendingRetries <= MAX_PENDING_RETRIES) {
                // 角度没对上：保留同一格，下一 tick 重新瞄一次再放。
                hasPending = true;
                pendingSent = false;
            }
            OpenMaverDebug.trace("godbridge", "aim miss (retry=" + pendingRetries + ")");
            return false;
        }
        if (!place(mc, player, world)) {
            return false;
        }
        lastPlaceTick = currentTick;
        placedTotal++;
        placeTickRing[placeRingIndex++ % placeTickRing.length] = currentTick;
        return true;
    }

    private static boolean place(Minecraft mc, Object player, Object world) {
        if (!OpenMaverFeatures.claimPlacement()) {
            OpenMaverDebug.noticeOnce("godbridge.place.shared",
                    "another module already sent a C08 this tick (MultiPlace)");
            return false;
        }
        Object stack = blockStack(player);
        Object controller = OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        if (stack == null || controller == null) {
            return false;
        }
        // pendingFace 已经是“要点击的支撑面”，点击点按该面取（教材的方块边缘）。
        double[] hit = hitVec(pendingX, pendingY, pendingZ, pendingFace);
        Object result = OpenMaverFeatures.call(controller, "onPlayerRightClick",
                "func_178890_a", null, player, world, stack,
                blockPos(pendingX, pendingY, pendingZ), facing(pendingFace), vec3(hit));
        if (!Boolean.TRUE.equals(result)) {
            OpenMaverDebug.noticeOnce("godbridge.place.failed",
                    "right click rejected: " + String.valueOf(result));
            return false;
        }
        OpenMaverFeatures.callVoid(player, "swingItem", "func_71038_i", null);
        OpenMaverDebug.place(pendingX, pendingY, pendingZ, FACING_NAMES[pendingFace],
                sentYaw, sentPitch, false, false, 1);
        return true;
    }

    private static boolean canPlaceNow(Minecraft mc, Object player) {
        if (mc == null || player == null) {
            return false;
        }
        if (OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null) != null) {
            return false;
        }
        return OpenMaverFeatures.alive(player) && blockSlot >= 0 && blockStack(player) != null;
    }

    // ==================================================================
    // 快捷栏 / C09
    // ==================================================================

    private static void updateHotbar(Object player) {
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        Object main = OpenMaverFeatures.read(inventory, "mainInventory", "field_70462_a", null);
        if (!(main instanceof Object[])) {
            blockSlot = -1;
            return;
        }
        Object[] slots = (Object[]) main;
        int found = -1;
        for (int i = 0; i < 9 && i < slots.length; i++) {
            if (isPlaceableBlock(slots[i])) {
                found = i;
                break;
            }
        }
        blockSlot = found;
        if (found >= 0) {
            if (currentHotbarSlot(player) != found) {
                setSlotClientOnly(player, found);
            }
            if (serverSlot != found) {
                applyHeldSlot(player, found);
            }
        }
    }

    private static Object blockStack(Object player) {
        if (blockSlot < 0) {
            return null;
        }
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        Object main = OpenMaverFeatures.read(inventory, "mainInventory", "field_70462_a", null);
        if (!(main instanceof Object[])) {
            return null;
        }
        Object[] slots = (Object[]) main;
        if (blockSlot >= slots.length) {
            return null;
        }
        Object stack = slots[blockSlot];
        return isPlaceableBlock(stack) ? stack : null;
    }

    private static int currentHotbarSlot(Object player) {
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        return (int) OpenMaverFeatures.number(inventory, "currentItem", "field_70461_c");
    }

    private static void setSlotClientOnly(Object player, int slot) {
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        if (inventory != null) {
            OpenMaverFeatures.setNumber(inventory, "currentItem", "field_70461_c", slot);
        }
    }

    private static void restoreSlot(Object player) {
        if (oldSlot < 0) {
            return;
        }
        applyHeldSlot(player, oldSlot);
    }

    /**
     * 切换到某个槽位：只改客户端 {@code inventory.currentItem}，C09 交给原版。
     *
     * <p>1.8.9 每 tick 的 {@code updateController()}、以及原版每次右键 / 挖掘的第一
     * 步都是 {@code syncCurrentPlayItem()}，它按 {@code currentPlayerItem} 缓存决定
     * 要不要发 C09。我们手发一条之后原版还会再补一条相同 slot 的 C09，直接命中
     * Grim 的 {@code BadPacketsA}（"Sent duplicate slot id"）。
     */
    private static void applyHeldSlot(Object player, int slot) {
        setSlotClientOnly(player, slot);
        serverSlot = slot;
    }

    private static boolean isPlaceableBlock(Object stack) {
        if (stack == null) {
            return false;
        }
        if (OpenMaverFeatures.number(stack, "stackSize", "field_77994_a") <= 0.0D) {
            return false;
        }
        Object item = OpenMaverFeatures.call(stack, "getItem", "func_77973_b", null);
        if (item == null) {
            return false;
        }
        Class<?> type = item.getClass();
        Boolean cached = PLACEABLE_CACHE.get(type);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean value = hasSuperclass(type, "ItemBlock") && placeableBlockItem(item);
        PLACEABLE_CACHE.put(type, Boolean.valueOf(value));
        return value;
    }

    private static boolean placeableBlockItem(Object item) {
        Object block = OpenMaverFeatures.read(item, "block", "field_150939_a", null);
        if (block == null) {
            return false;
        }
        String[] excluded = {
                "BlockFlower", "BlockBush", "BlockCrops", "BlockSlab", "BlockWeb",
                "BlockTorch", "BlockRail", "BlockRailBase", "BlockFire", "BlockSign",
                "BlockDoor", "BlockTrapDoor", "BlockLadder", "BlockPane", "BlockButton",
                "BlockLever", "BlockTripWire", "BlockTripWireHook", "BlockVine",
                "BlockCarpet", "BlockSnow", "BlockRedstoneWire", "BlockRedstoneDiode",
                "BlockBasePressurePlate", "BlockSapling", "BlockMushroom", "BlockReed",
                "BlockLiquid", "BlockAir"
        };
        for (int i = 0; i < excluded.length; i++) {
            if (hasSuperclass(block.getClass(), excluded[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSuperclass(Class<?> type, String simpleName) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            if (simpleName.equals(current.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 输入接管
    // ==================================================================

    private static void setKeyPressed(Minecraft mc, String fieldName, boolean pressed) {
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object binding = OpenMaverFeatures.read(settings, fieldName,
                keyBindingSrg(fieldName), null);
        if (binding != null) {
            setBoolean(binding, "pressed", "field_74513_e", pressed);
        }
    }

    private static boolean keyDown(Minecraft mc, String fieldName) {
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object binding = OpenMaverFeatures.read(settings, fieldName,
                keyBindingSrg(fieldName), null);
        Object pressed = OpenMaverFeatures.read(binding, "pressed", "field_74513_e", Boolean.class);
        return Boolean.TRUE.equals(pressed);
    }

    /** 空闲时把 Shift 还原成玩家真实按键状态。 */
    private static void restoreSneakToPhysical(Minecraft mc) {
        if (mc == null) {
            return;
        }
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object binding = OpenMaverFeatures.read(settings, "keyBindSneak", "field_74311_E", null);
        if (binding == null) {
            return;
        }
        Object code = OpenMaverFeatures.call(binding, "getKeyCode", "func_151463_i", null);
        boolean physical = code instanceof Number
                && Keyboard.isKeyDown(((Number) code).intValue());
        setBoolean(binding, "pressed", "field_74513_e", physical);
    }

    private static void restoreKeys(Minecraft mc) {
        if (mc == null) {
            return;
        }
        restoreSneakToPhysical(mc);
    }

    private static String keyBindingSrg(String fieldName) {
        if ("keyBindForward".equals(fieldName)) {
            return "field_74351_w";
        }
        if ("keyBindBack".equals(fieldName)) {
            return "field_74368_y";
        }
        if ("keyBindLeft".equals(fieldName)) {
            return "field_74370_x";
        }
        if ("keyBindRight".equals(fieldName)) {
            return "field_74366_z";
        }
        if ("keyBindJump".equals(fieldName)) {
            return "field_74314_A";
        }
        if ("keyBindSneak".equals(fieldName)) {
            return "field_74311_E";
        }
        if ("keyBindSprint".equals(fieldName)) {
            return "field_74321_H";
        }
        return fieldName;
    }

    private static int signum(float value) {
        return value > 0.0F ? 1 : (value < 0.0F ? -1 : 0);
    }

    /** 相机 yaw + 按键 → 实际移动方向（MoveFix 里的同一个公式）。 */
    private static float adjustYaw(float yaw, int forwardSign, int strafeSign) {
        double forward = forwardSign;
        double strafe = strafeSign;
        double yawRad = Math.toRadians(yaw);
        double dx = strafe * Math.cos(yawRad) - forward * Math.sin(yawRad);
        double dz = forward * Math.cos(yawRad) + strafe * Math.sin(yawRad);
        if (Math.abs(dx) < 1.0E-6D && Math.abs(dz) < 1.0E-6D) {
            return yaw;
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // ==================================================================
    // 世界查询
    // ==================================================================

    private static float[] eyePosition(Object player) {
        return new float[]{
                (float) OpenMaverFeatures.number(player, "posX", "field_70165_t"),
                (float) (OpenMaverFeatures.number(player, "posY", "field_70163_u")
                        + OpenMaverFeatures.eyeHeight(player)),
                (float) OpenMaverFeatures.number(player, "posZ", "field_70161_v")
        };
    }

    private static Object rayTraceFrom(Object world, float[] eye, float yaw, float pitch,
            double distance) {
        if (world == null) {
            return null;
        }
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);
        Object from = vec3(eye[0], eye[1], eye[2]);
        Object to = vec3(eye[0] + lookX * distance, eye[1] + lookY * distance,
                eye[2] + lookZ * distance);
        return OpenMaverFeatures.call(world, "rayTraceBlocks", "func_72933_a", null, from, to);
    }

    private static boolean mopMatches(Object mop, int x, int y, int z, int face) {
        if (mop == null) {
            return false;
        }
        Object type = OpenMaverFeatures.read(mop, "typeOfHit", "field_72313_a", null);
        if (type == null || !"BLOCK".equalsIgnoreCase(String.valueOf(type))) {
            return false;
        }
        Object hitPos = OpenMaverFeatures.read(mop, "blockPos", "field_178783_e", null);
        Object side = OpenMaverFeatures.read(mop, "sideHit", "field_178784_b", null);
        return hitPos != null && facingIndex(side) == face && blockPosEquals(hitPos, x, y, z);
    }

    private static int facingIndex(Object facingObj) {
        if (facingObj == null) {
            return -1;
        }
        if (facings == null) {
            facing(0);
        }
        if (facings != null) {
            for (int i = 0; i < facings.length; i++) {
                if (facings[i] == facingObj) {
                    return i;
                }
            }
        }
        String name = String.valueOf(facingObj);
        for (int i = 0; i < FACING_NAMES.length; i++) {
            if (name.toUpperCase().contains(FACING_NAMES[i])) {
                return i;
            }
        }
        return -1;
    }

    private static boolean blockPosEquals(Object pos, int x, int y, int z) {
        Object valueX = OpenMaverFeatures.call(pos, "getX", "func_177958_n", null);
        Object valueY = OpenMaverFeatures.call(pos, "getY", "func_177956_o", null);
        Object valueZ = OpenMaverFeatures.call(pos, "getZ", "func_177952_p", null);
        return valueX instanceof Number && valueY instanceof Number && valueZ instanceof Number
                && ((Number) valueX).intValue() == x
                && ((Number) valueY).intValue() == y
                && ((Number) valueZ).intValue() == z;
    }

    private static Object blockPos(int x, int y, int z) {
        try {
            if (blockPosConstructor == null) {
                if (blockPosType == null) {
                    blockPosType = Class.forName("net.minecraft.util.BlockPos");
                }
                blockPosConstructor = blockPosType.getConstructor(
                        Integer.TYPE, Integer.TYPE, Integer.TYPE);
            }
            return blockPosConstructor.newInstance(Integer.valueOf(x), Integer.valueOf(y),
                    Integer.valueOf(z));
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object vec3(double x, double y, double z) {
        try {
            if (vec3Constructor == null) {
                if (vec3Type == null) {
                    vec3Type = Class.forName("net.minecraft.util.Vec3");
                }
                vec3Constructor = vec3Type.getConstructor(
                        Double.TYPE, Double.TYPE, Double.TYPE);
            }
            return vec3Constructor.newInstance(Double.valueOf(x), Double.valueOf(y),
                    Double.valueOf(z));
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object vec3(double[] values) {
        return vec3(values[0], values[1], values[2]);
    }

    private static Object facing(int index) {
        if (facings == null) {
            Object[] resolved = new Object[6];
            try {
                Class<?> type = Class.forName("net.minecraft.util.EnumFacing");
                for (int i = 0; i < 6; i++) {
                    resolved[i] = type.getField(FACING_NAMES[i]).get(null);
                }
                facings = resolved;
            } catch (Throwable error) {
                return null;
            }
        }
        return facings[index];
    }

    private static Object getBlockState(Object world, Object pos) {
        if (world == null || pos == null) {
            return null;
        }
        return OpenMaverFeatures.call(world, "getBlockState", "func_180495_p", null, pos);
    }

    private static boolean isAir(Object world, int x, int y, int z) {
        if (world == null) {
            return false;
        }
        Object pos = blockPos(x, y, z);
        Object air = OpenMaverFeatures.call(world, "isAirBlock", "func_175623_d", null, pos);
        if (air instanceof Boolean) {
            return ((Boolean) air).booleanValue();
        }
        Object state = getBlockState(world, pos);
        Object block = state == null ? null
                : OpenMaverFeatures.call(state, "getBlock", "func_177230_c", null);
        if (block == null) {
            return false;
        }
        Object material = OpenMaverFeatures.call(block, "getMaterial", "func_149688_o", null);
        Object replaceable = material == null ? null
                : OpenMaverFeatures.call(material, "isReplaceable", "func_76222_j", null);
        return Boolean.TRUE.equals(replaceable);
    }

    private static boolean isSolidSupport(Object world, int x, int y, int z) {
        Object pos = blockPos(x, y, z);
        Object state = getBlockState(world, pos);
        Object block = state == null ? null
                : OpenMaverFeatures.call(state, "getBlock", "func_177230_c", null);
        if (block == null || !isSolidBlock(block)) {
            return false;
        }
        Object box = OpenMaverFeatures.call(block, "getCollisionBoundingBox",
                "func_180646_a", null, world, pos, state);
        return box instanceof AxisAlignedBB;
    }

    private static boolean isSolidBlock(Object block) {
        Class<?> type = block.getClass();
        Boolean cached = SOLID_CACHE.get(type);
        if (cached != null) {
            return cached.booleanValue();
        }
        String[] nonSolid = {
                "BlockLiquid", "BlockAir", "BlockStairs", "BlockSlab", "BlockEndPortal",
                "BlockEndPortalFrame", "BlockVine", "BlockPumpkin", "BlockCactus",
                "BlockBush", "BlockFalling", "BlockWeb", "BlockPane", "BlockCarpet",
                "BlockSnow", "BlockFence", "BlockFenceGate", "BlockWall", "BlockLadder",
                "BlockTorch", "BlockRedstoneWire", "BlockRedstoneDiode",
                "BlockBasePressurePlate", "BlockTripWire", "BlockTripWireHook",
                "BlockRailBase", "BlockSlime", "BlockTNT", "BlockChest",
                "BlockFurnace", "BlockWorkbench"
        };
        boolean value = true;
        for (int i = 0; i < nonSolid.length; i++) {
            if (hasSuperclass(type, nonSolid[i])) {
                value = false;
                break;
            }
        }
        SOLID_CACHE.put(type, Boolean.valueOf(value));
        return value;
    }

    private static double blockReachDistance() {
        Minecraft mc = Minecraft.getMinecraft();
        Object controller = mc == null ? null
                : OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        Object value = OpenMaverFeatures.call(controller, "getBlockReachDistance",
                "func_78757_d", null);
        double reach = value instanceof Number ? ((Number) value).doubleValue() : 4.5D;
        return Math.max(4.5D, Math.min(5.0D, reach));
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static float playerYaw(Object player) {
        return (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z");
    }

    private static float playerPitch(Object player) {
        return (float) OpenMaverFeatures.number(player, "rotationPitch", "field_70125_A");
    }

    private static void setFloat(Object target, String mcp, String srg, float value) {
        OpenMaverFeatures.setNumber(target, mcp, srg, value);
    }

    private static void setBoolean(Object target, String mcp, String srg, boolean value) {
        if (target == null) {
            return;
        }
        Field field = OpenMaverFeatures.findField(target,
                OpenMaverFeatures.runtimeCandidates(mcp, srg));
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.setBoolean(target, value);
        } catch (Throwable ignored) {
            // 映射差异时忽略。
        }
    }

    static int floor(double value) {
        int truncated = (int) value;
        return value < (double) truncated ? truncated - 1 : truncated;
    }

    static float wrapDegrees(float angle) {
        float wrapped = angle % 360.0F;
        if (wrapped <= -180.0F) {
            wrapped += 360.0F;
        }
        if (wrapped > 180.0F) {
            wrapped -= 360.0F;
        }
        return wrapped;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================================================================
    // 教材 → 参数（回归测试直接调这些纯函数）
    // ==================================================================

    /** 每个方块要几个 tick：12 / 10 / 7，对应 1.67 / 2.00 / 2.86 方块每秒。 */
    static int ticksPerBlock(int modeId) {
        if (modeId == MODE_SPEEDBRIDGE) {
            return 12;
        }
        if (modeId == MODE_DIAGONAL) {
            return 10;
        }
        return 7;
    }

    /** 教材给出的速度（方块/秒），用于 HUD 目标值与测试。 */
    static double targetBps(int modeId) {
        return 20.0D / ticksPerBlock(modeId);
    }

    /** 两次放置之间的最小 tick 间隔（防 Grim Timer / MultiPlace）。 */
    static int minPlaceInterval(int modeId) {
        return ticksPerBlock(modeId) - 1;
    }

    /** 松 Shift 的 tick 预算；只有蹲起模式才有松手。 */
    static int sneakReleaseBudget(int modeId, int window) {
        if (modeId != MODE_SNEAK_TOGGLE) {
            return 0;
        }
        return clamp(window, 1, 4);
    }

    /**
     * 相机 yaw + 按键 → 世界行进方向。
     *
     * <pre>
     *   S        -> 相机 + 180°（正对桥后退）
     *   S + D    -> 相机 + 225°（等价于视角与桥线成 45°，教材的斜桥）
     * </pre>
     */
    static float travelYaw(float cameraYaw, int forward, int strafe) {
        if (forward == 0 && strafe == 0) {
            return cameraYaw;
        }
        return wrapDegrees(adjustYaw(cameraYaw, forward, strafe));
    }

    /** 反推：想沿 travelYaw 搭桥，相机应该摆在哪（教材的“视角与桥呈 45 度”）。 */
    static float suggestedCameraYaw(float travelYaw, int modeId) {
        return wrapDegrees(travelYaw + (modeId == MODE_DIAGONAL ? 135.0F : 180.0F));
    }

    /** 贴边判定：余量 <= 阈值才算站到方块边缘。 */
    static boolean atEdge(double clearance, double margin) {
        return clearance <= margin && clearance >= -0.001D;
    }

    /**
     * 蹲起搭路的阶段转移（教材第 2~4 步）。
     *
     * @param phase           当前阶段
     * @param placedThisTick  这一 tick 是否刚放完一块
     * @param canRelease      余量是否够松 Shift（贴着边缘不能松）
     * @param releaseTicks    已经松了几个 tick
     * @param releaseBudget   松手预算（1..4）
     * @param clearanceLow    余量是否已经到紧急线
     * @param onGround        是否在地面
     * @return 下一阶段
     */
    static int stepPhase(int phase, boolean placedThisTick, boolean canRelease,
            int releaseTicks, int releaseBudget, boolean clearanceLow, boolean onGround) {
        if (placedThisTick) {
            return canRelease ? PHASE_RELEASE : PHASE_WAIT_EDGE;
        }
        if (phase == PHASE_RELEASE) {
            if (releaseTicks >= releaseBudget || clearanceLow || !onGround) {
                return PHASE_RESNEAK;
            }
            return PHASE_RELEASE;
        }
        if (phase == PHASE_RESNEAK) {
            return PHASE_WAIT_EDGE;
        }
        return PHASE_WAIT_EDGE;
    }

    /** 该阶段要不要按住 Shift。 */
    static boolean sneakForPhase(int phase) {
        return phase != PHASE_RELEASE;
    }

    /** 放置闸门：必须贴边、冷却到、有目标，且不在松手阶段。 */
    static boolean mayPlace(int phase, boolean atEdge, long ticksSincePlace,
            int minInterval, boolean hasTarget) {
        if (!hasTarget || !atEdge || phase == PHASE_RELEASE) {
            return false;
        }
        return ticksSincePlace >= minInterval;
    }

    /** 眼睛 → 目标点的 yaw/pitch（MC 约定：yaw 0 朝 +Z，pitch 向下为正）。 */
    static float[] rotationTo(double eyeX, double eyeY, double eyeZ,
            double targetX, double targetY, double targetZ) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, horizontal));
        if (pitch > 89.5F) {
            pitch = 89.5F;
        }
        if (pitch < -89.5F) {
            pitch = -89.5F;
        }
        return new float[]{wrapDegrees(yaw), pitch};
    }

    // ==================================================================
    // HUD / 对外查询
    // ==================================================================

    public static boolean isActive() {
        return active && !failed;
    }

    public static boolean isHud() {
        return hud;
    }

    public static int getPhase() {
        return phase;
    }

    public static String getPhaseName() {
        return PHASE_NAMES[clamp(phase, 0, 3)];
    }

    public static int getPlacedCount() {
        return placedTotal;
    }

    /** 最近 2 秒的实测放置速度（方块/秒），教材里的 1.67 / 2 / 3 就是这个读数。 */
    public static double hudBlocksPerSecond() {
        int count = 0;
        for (int i = 0; i < placeTickRing.length; i++) {
            long tick = placeTickRing[i];
            if (tick != Long.MIN_VALUE && currentTick - tick <= SPEED_WINDOW_TICKS) {
                count++;
            }
        }
        return count * 20.0D / SPEED_WINDOW_TICKS;
    }

    public static int hudBlockCount(Object player) {
        Object stack = hudBlockStack(player);
        return stack == null ? 0
                : (int) OpenMaverFeatures.number(stack, "stackSize", "field_77994_a");
    }

    public static Object hudBlockStack(Object player) {
        Object held = OpenMaverFeatures.call(player, "getHeldItem", "func_70694_bm", null);
        if (isPlaceableBlock(held)) {
            return held;
        }
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        Object main = OpenMaverFeatures.read(inventory, "mainInventory", "field_70462_a", null);
        if (!(main instanceof Object[])) {
            return null;
        }
        Object[] slots = (Object[]) main;
        for (int i = 0; i < 9 && i < slots.length; i++) {
            if (isPlaceableBlock(slots[i])) {
                return slots[i];
            }
        }
        return null;
    }

    public static double hudEdgeClearance() {
        return edgeClearance;
    }

    // ==================================================================
    // 配置读写
    // ==================================================================

    public static void load(Properties values) {
        mode = clamp(readInt(values, "godbridge.mode", MODE_SNEAK_TOGGLE), 0, 2);
        sneakWindow = clamp(readInt(values, "godbridge.sneakWindow", 3), 1, 4);
        edgeMargin = Math.max(0.10D, Math.min(0.45D,
                readDouble(values, "godbridge.edgeMargin", 0.30D)));
        safety = readBoolean(values, "godbridge.safety", true);
        itemSpoof = readBoolean(values, "godbridge.itemSpoof", true);
        hud = readBoolean(values, "godbridge.hud", true);
        reset();
    }

    public static String save() {
        return "godbridge.mode=" + mode
                + "\ngodbridge.sneakWindow=" + sneakWindow
                + "\ngodbridge.edgeMargin=" + edgeMargin
                + "\ngodbridge.safety=" + safety
                + "\ngodbridge.itemSpoof=" + itemSpoof
                + "\ngodbridge.hud=" + hud;
    }

    private static int readInt(Properties values, String key, int fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double readDouble(Properties values, String key, double fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(Properties values, String key, boolean fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(raw.trim());
    }

    // ------------------------------------------------------------------
    // ClickGUI 绑定
    // ------------------------------------------------------------------

    public static int getMode() {
        return mode;
    }

    public static void setMode(int value) {
        mode = clamp(value, 0, 2);
        phase = PHASE_IDLE;
        OpenMaverFeatures.markDirty();
    }

    public static int getSneakWindow() {
        return sneakWindow;
    }

    public static void setSneakWindow(int value) {
        sneakWindow = clamp(value, 1, 4);
        OpenMaverFeatures.markDirty();
    }

    public static double getEdgeMargin() {
        return edgeMargin;
    }

    public static void setEdgeMargin(double value) {
        edgeMargin = Math.max(0.10D, Math.min(0.45D, value));
        OpenMaverFeatures.markDirty();
    }

    public static boolean isSafety() {
        return safety;
    }

    public static void setSafety(boolean value) {
        safety = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isItemSpoof() {
        return itemSpoof;
    }

    public static void setItemSpoof(boolean value) {
        itemSpoof = value;
        OpenMaverFeatures.markDirty();
    }

    public static void setHud(boolean value) {
        hud = value;
        OpenMaverFeatures.markDirty();
    }
}
