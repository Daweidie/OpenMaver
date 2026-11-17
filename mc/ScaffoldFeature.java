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
 * Scaffold（自动搭路 / 搭桥）——按 {@code ../OpenZen} 的
 * {@code shit.zen.modules.impl.movement.Scaffold} 全量重写。
 *
 * <p>OpenZen 的搭桥模型和之前的 Myau 移植差别很大，这里按它自己的状态机实现：
 *
 * <ol>
 *   <li><b>目标高度</b>：{@code targetYLevel = floor(y) - 1}，只在“落地 / 停下 /
 *       按住空格 / Normal 模式”时刷新，其余时间锁住 —— 这是 telly bridge 能连续
 *       向上铺的原因。</li>
 *   <li><b>目标格搜索</b>（{@code applyRotations}）：先看脚下这一格是不是已经能站；
 *       不能站就从 {@code targetYLevel} 开始按半径 1..6 的菱形向下扩，找到第一个
 *       “空气格 + 六个邻居里有实心支撑面 + 面中心在 4.5 格内且朝向可见”的组合。
 *       放置点记成 <em>支撑方块 + 指向目标格的那一面</em>。</li>
 *   <li><b>模式</b>：{@code Normal / Telly Bridge / Old Telly / Keep Y}。Telly 用
 *       一个独立的起步/空中旋转表（groundTicks 1/2、airTicks 1、俯角 75.5°、
 *       90°/50° 的单 tick 限速），Normal 用 snap 直接对齐。</li>
 *   <li><b>Eagle / Sneak</b>：{@code sneak} 是 OpenZen 的 18→21 tick 自动潜行节拍；
 *       {@code eagle} 是 Normal 模式下“贴边才潜行”。</li>
 *   <li><b>Clutch</b>：用两 tick 的垂直运动模拟判断“再掉下去目标就够不着了”，
 *       够不着时把 aim 锁在目标上并不放置（{@code rotationDelay} 计数，上限 8）。</li>
 *   <li><b>击退感知</b>：收到自己的 S12 且水平速度 ≥ 1.5 时 {@code velocityDelay=60}，
 *       期间同样走 clutch 的“按住不放”分支。</li>
 * </ol>
 *
 * <h3>与 OpenZen 的落地差异（OpenMaver 没有 Mixin / EventManager）</h3>
 *
 * <ul>
 *   <li><b>静默转向</b>：OpenZen 靠 {@code RotationHandler} 在 MotionEvent 里改出站
 *       rotation，客户端视角不动。OpenMaver 在
 *       {@link ScaffoldMovementInput}（vanilla {@code updatePlayerMoveState()} 之后、
 *       {@code moveFlying()} 之前）把 aim 写进 {@code rotationYaw/Pitch}，本 tick 的
 *       C03 自然带上它；{@code ClientTickEvent.END} 再恢复相机角度（第一人称不变，
 *       F5 身体保留服务端最后收到的 aim）。</li>
 *   <li><b>MoveFix</b>：OpenZen 的 {@code MovementUtil.handleStrafe} 把输入吸附到
 *       相对 aim 的 8 个整数方向。这里在同一个 hook 里做同一件事，另外把输入向量
 *       等比放大到 {@code forward >= 0.8}，避免原版随后关掉冲刺。</li>
 *   <li><b>C08 时序</b>：OpenZen 的 {@code onPreMotion} 在 {@code handleKeybinds} 里，
 *       也就是本 tick 的 C03 之后。OpenMaver 的等价点是“下一 tick 开头”，所以放置
 *       走 {@code pending → flush}：tick N 把 aim 写进 C03，tick N+1 开头用<em>服务端
 *       已经收到的那个角度</em>做 ray trace 再发 C08。放置成功的那一 tick 把 aim
 *       冻结成刚校验过的角度（{@code holdPlaceAim}），保证 C08 之后那条飞包携带同一
 *       个角度。</li>
 *   <li><b>快捷栏</b>：1.8.9 的 {@code inventory.currentItem} 不会自动同步给服务端，
 *       所以切到方块槽时会显式发 {@code C09PacketHeldItemChange}（{@code serverSlot}
 *       去重）。{@code itemSpoof} 在 tick 末尾把客户端槽位切回开启时的槽（不发 C09），
 *       于是手部渲染看到旧物品、服务端仍然认为手持方块 —— 与 OpenZen 的
 *       {@code Render Item Spoof} 可见效果一致。</li>
 *   <li><b>空闲时不转</b>：OpenZen 的 {@code getPlayerYawRotation()} 在
 *       {@code currentPlacement == null} 时返回 {@code (0,0)}，会让服务端视角在没有
 *       目标时漂到 yaw 0。这里改成回落到相机角度（见 {@link #getPlayerYawRotation}），
 *       其余状态机逻辑不变。</li>
 *   <li><b>{@code delayPackets}</b>：OpenZen 在 clutch 分支里往
 *       {@code ClientLevel#tickNonPassenger} 插 runnable 来“拆 tick”。1.8.9 没有这个
 *       注入点，这里只保留它的语义（hold aim + rotationDelay 计数 + 到期重试），
 *       不再拆 tick 发额外 C05。</li>
 * </ul>
 *
 * <p>行为说明见 {@code design/SCAFFOLD_HANDOFF.md}，回归测试见
 * {@code tools/scaffold-test/}。
 */
public final class ScaffoldFeature {

    // ==================================================================
    // 设置（对应 OpenZen Scaffold 的 Setting 字段）
    // ==================================================================

    public static final int MODE_NORMAL = 0;
    public static final int MODE_TELLY_BRIDGE = 1;
    public static final int MODE_OLD_TELLY = 2;
    public static final int MODE_KEEP_Y = 3;
    public static final String[] MODE_NAMES = {"NORMAL", "TELLY BRIDGE", "OLD TELLY", "KEEP Y"};

    /** Normal / Telly Bridge / Old Telly / Keep Y */
    private static int mode = MODE_NORMAL;
    /** Normal 模式下“贴边才潜行”。 */
    private static boolean eagle = true;
    /** 18→21 tick 的自动潜行节拍（OpenZen 的 sneak）。 */
    private static boolean sneak = true;
    /** Normal 模式下直接对齐目标角（不做插值）。 */
    private static boolean snap = true;
    /** 掉出目标高度时先按住不放，等够得着再放。 */
    private static boolean clutch = true;
    /** 旋转速度档位 1..6：单 tick 步长 = 360 / tick。 */
    private static int rotationTick = 3;
    /** 手部渲染保持开启时的物品（服务端仍认为手持方块）。 */
    private static boolean itemSpoof = true;
    /** 方块计数 HUD。 */
    private static boolean hud = true;

    // ==================================================================
    // 运行状态（与 OpenZen Scaffold 字段一一对应）
    // ==================================================================

    /** 六个面：0 DOWN / 1 UP / 2 NORTH / 3 SOUTH / 4 WEST / 5 EAST（1.8.9 EnumFacing 顺序）。 */
    private static final String[] FACING_NAMES = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
    private static final int[][] FACING_DELTAS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };
    /** 目标格相对支撑方块的方向 → 要点击的支撑面。 */
    private static final int[] OPPOSITE_FACING = {1, 0, 3, 2, 5, 4};

    /** 目标格搜索半径上限（OpenZen applyRotations 的 1..6）。 */
    private static final int SEARCH_RADIUS = 6;
    /** 面中心到眼睛的最大距离平方（4.5²），OpenZen 的 20.25。 */
    private static final double REACH_SQ = 20.25D;
    /** clutch 的 rotationDelay 上限。 */
    private static final int MAX_ROTATION_DELAY = 8;
    /** 同一个目标最多重试几次（每次重试 = 1 tick）。 */
    static final int MAX_PENDING_RETRIES = 2;
    /** flushPending 的三种结果。 */
    static final int PENDING_DROP = 0;
    static final int PENDING_RETRY = 1;
    static final int PENDING_PLACE = 2;
    /** 击退后抑制放置的 tick 数（OpenZen 的 velocityDelay = 60）。 */
    private static final int VELOCITY_DELAY_TICKS = 60;

    private static boolean active;
    private static boolean failed;
    private static long currentTick;

    /** 当前 aim（OpenZen 的 {@code rots}）。 */
    private static float rotsYaw;
    private static float rotsPitch;
    /** 本 tick 算出来的“应该看向哪”（OpenZen 的 {@code correctRotation}）。 */
    private static float correctYaw;
    private static float correctPitch;
    /** 上一条真正写进 C03 的 aim（OpenZen 的 {@code RotationHandler.prevRotation}）。 */
    private static float prevAimYaw = Float.NaN;
    private static float prevAimPitch = Float.NaN;

    /** OpenZen：enable 时 10000，第一 tick 收敛到 floor(y)-1。 */
    private static int targetYLevel = 10000;
    private static int velocityDelay;
    private static int oldSlot = -1;
    /** 快捷栏里第一组可放置方块的槽位，-1 = 没有。 */
    private static int blockSlot = -1;
    /** 服务端已经确认的槽位（C09 去重用）。 */
    private static int serverSlot = -1;
    private static int blockCount = -1;

    private static int eagleTimer;
    private static int groundTicks;
    private static int airTicks;
    private static int rotationDelay;
    private static boolean canBuildNow = true;
    private static int jitterCounter;
    private static float yawDiff;
    private static float pitchDiff;
    private static double lastYawDiff = Double.NaN;
    private static double lastPitchDiff = Double.NaN;

    /** 当前放置目标（支撑方块 + 点击面）；OpenZen 不清空它，这里同样保留。 */
    private static Placement placement;
    /** 上一 tick 规划、本 tick 开头执行的目标。 */
    private static Pending pending;

    // ------------------------------------------------------------------
    // 静默转向（tick 内换视角）
    // ------------------------------------------------------------------

    private static float cameraYaw;
    private static float cameraPitch;
    private static float tickAimYaw;
    private static float tickAimPitch;
    private static boolean aimApplied;
    private static boolean rotationDirty;
    private static boolean hookArmed;
    private static long hookArmedNanos;
    private static Object aimPlayer;
    /** 用于检测重生/切维度（玩家对象会换）。 */
    private static Object trackedPlayer;

    /**
     * 本 tick 的 C08 已经发出：飞包必须携带同一个角度。
     *
     * <p>Grim 1.8 的 {@code RotationPlace} 用 C08 <em>之后</em>那条飞包的
     * rotation 去 ray trace，所以放置那一 tick 不能再转向新目标。
     */
    private static boolean holdPlaceAim;
    private static float holdPlaceYaw;
    private static float holdPlacePitch;

    // ------------------------------------------------------------------
    // 反射缓存
    // ------------------------------------------------------------------

    private static Class<?> blockPosType;
    private static Constructor<?> blockPosConstructor;
    private static Class<?> vec3Type;
    private static Constructor<?> vec3Constructor;
    private static Object[] facings;
    private static Constructor<?> heldItemChangeConstructor;
    private static boolean heldItemChangeFailed;
    private static Object sprintKeyBinding;
    private static final Map<Class<?>, Boolean> SOLID_CACHE =
            new ConcurrentHashMap<Class<?>, Boolean>();
    private static final Map<Class<?>, Boolean> PLACEABLE_CACHE =
            new ConcurrentHashMap<Class<?>, Boolean>();

    private ScaffoldFeature() {
    }

    // ==================================================================
    // 数据载体
    // ==================================================================

    /** 支撑方块与要点击的面。 */
    private static final class Placement {
        final int x;
        final int y;
        final int z;
        final int face;

        Placement(int x, int y, int z, int face) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.face = face;
        }

        boolean sameAs(Placement other) {
            return other != null && other.x == x && other.y == y && other.z == z
                    && other.face == face;
        }
    }

    /** 已经写进 C03、等下一 tick 开头放置的目标。 */
    private static final class Pending {
        final int x;
        final int y;
        final int z;
        final int face;
        float aimYaw;
        float aimPitch;
        float sentYaw;
        float sentPitch;
        boolean sent;
        int retries;

        Pending(Placement placement, float aimYaw, float aimPitch) {
            this.x = placement.x;
            this.y = placement.y;
            this.z = placement.z;
            this.face = placement.face;
            this.aimYaw = aimYaw;
            this.aimPitch = aimPitch;
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 在 ClientTickEvent.START（vanilla 发送 C03 之前）调用。 */
    public static void onTickStart(Minecraft mc) {
        Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        if (player != null) {
            // 上一 tick 万一没恢复，先兜底，避免相机停在 aim 上。
            if (rotationDirty) {
                restoreCameraView(player);
            }
            cameraYaw = playerYaw(player);
            cameraPitch = playerPitch(player);
            aimPlayer = player;
            aimApplied = false;
            if (player != trackedPlayer) {
                trackedPlayer = player;
                if (active) {
                    // 重生 / 切维度：服务端会把选中槽位重置，重新从当前槽同步。
                    serverSlot = -1;
                    oldSlot = currentHotbarSlot(player);
                }
            }
            // 上一 tick 的“放置锁定”只活一 tick：本 tick 的 flush 会重新决定。
            holdPlaceAim = false;
            hookArmed = true;
            hookArmedNanos = System.nanoTime();
        } else {
            hookArmed = false;
        }

        Module module = ModuleManager.getModule("Scaffold");
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
                // 打开 GUI / 死亡 / 没世界时：不清状态机，只是本 tick 不动手。
                pending = null;
                holdPlaceAim = false;
                aimApplied = false;
                return;
            }
            ensureMovementHook(mc, player);
            tick(mc, player, world);
        } catch (Throwable error) {
            failed = true;
            active = false;
            pending = null;
            placement = null;
            System.err.println("[OpenMaver] Scaffold disabled after error: " + error);
        }
    }

    /**
     * ClientTickEvent.END（渲染之前）恢复第一人称视角。
     *
     * <p>C03 已经在 tick 中间发出，所以这里只影响客户端：
     * {@code rotationYaw/Pitch} 回到相机；{@code renderYawOffset/rotationYawHead}
     * 保留服务端最后收到的 aim，F5 身体因此显示成“在搭桥”。
     * {@code itemSpoof} 也在这里把客户端槽位切回开启时的槽。
     */
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
        blockSlot = -1;
        serverSlot = -1;
        blockCount = -1;
        targetYLevel = 10000;
        velocityDelay = 0;
        eagleTimer = 0;
        groundTicks = 0;
        airTicks = 0;
        rotationDelay = 0;
        canBuildNow = true;
        jitterCounter = 0;
        yawDiff = 0.0F;
        pitchDiff = 0.0F;
        lastYawDiff = Double.NaN;
        lastPitchDiff = Double.NaN;
        placement = null;
        pending = null;
        holdPlaceAim = false;
        rotationDirty = false;
        aimApplied = false;

        if (player != null) {
            oldSlot = currentHotbarSlot(player);
            serverSlot = oldSlot;
            trackedPlayer = player;
            // OpenZen: 启用时 aim 放在“身后”，telly bridge 从这个角度起步。
            rotsYaw = playerYaw(player) - 180.0F;
            rotsPitch = playerPitch(player);
            prevAimYaw = playerYaw(player);
            prevAimPitch = playerPitch(player);
            cameraYaw = playerYaw(player);
            cameraPitch = playerPitch(player);
            aimPlayer = player;
        } else {
            oldSlot = -1;
            serverSlot = -1;
            rotsYaw = -180.0F;
            rotsPitch = 0.0F;
            prevAimYaw = Float.NaN;
            prevAimPitch = Float.NaN;
        }
        correctYaw = rotsYaw;
        correctPitch = rotsPitch;
        OpenMaverDebug.trace("scaffold", "enabled mode=" + MODE_NAMES[clamp(mode, 0, 3)]
                + " rotationTick=" + rotationTick);
    }

    private static void disable(Minecraft mc) {
        active = false;
        Object player = aimPlayer;
        if (player != null) {
            restoreSlot(player);
            restoreCameraView(player);
            restoreKeys(mc);
        }
        pending = null;
        placement = null;
        holdPlaceAim = false;
        blockSlot = -1;
        blockCount = -1;
        rotationDelay = 0;
        aimApplied = false;
        OpenMaverDebug.trace("scaffold", "disabled");
    }

    /** 配置重新加载 / 世界切换时由 OpenMaverFeatures 调用。 */
    public static void reset() {
        Minecraft mc = Minecraft.getMinecraft();
        Object player = null;
        if (mc != null) {
            player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        }
        if (active && player != null) {
            restoreSlot(player);
            restoreCameraView(player);
            restoreKeys(mc);
        }
        active = false;
        failed = false;
        pending = null;
        placement = null;
        holdPlaceAim = false;
        blockSlot = -1;
        blockCount = -1;
        rotationDelay = 0;
        aimApplied = false;
        rotationDirty = false;
    }

    /** 由 OpenMaverFeatures 每 tick 递增，仅用于 debug 与 snap 的相位。 */
    public static void tickCounter(long tick) {
        currentTick = tick;
    }

    // ==================================================================
    // 主 tick（对应 OpenZen Scaffold#onTick）
    // ==================================================================

    private static void tick(Minecraft mc, Object player, Object world) {
        updateAirGroundTicks(player);

        boolean jumpHeld = keyDown(mc, "keyBindJump");
        boolean onGround = OpenMaverFeatures.scaffoldOnGround(player);
        boolean moving = isMoving(mc);
        double posY = OpenMaverFeatures.number(player, "posY", "field_70163_u");

        // OpenZen onPacket/onTick 的击退窗口：每 tick 递减，落地且剩 <= 30 时清零。
        if (velocityDelay > 0) {
            velocityDelay--;
        }
        if (onGround && velocityDelay <= 30) {
            velocityDelay = 0;
        }

        updateHotbar(player);

        // 1) targetYLevel（OpenZen 的条件原样保留）
        if (targetYLevel == -1
                || targetYLevel > floor(posY) - 1
                || onGround
                || !moving
                || jumpHeld
                || mode == MODE_NORMAL) {
            targetYLevel = floor(posY) - 1;
        }

        // 2) 上一 tick 已经写进 C03 的目标：本 tick 开头校验并发 C08
        if (pending != null) {
            flushPending(mc, player, world);
        }

        // 3) 目标格搜索（OpenZen applyRotations）
        float[] eye = eyePosition(player);
        if (blockSlot >= 0) {
            applyRotations(player, world, eye);
        }

        // 4) clutch：两 tick 垂直模拟，够不着就按住不放
        boolean firstGroundTick = false;
        canBuildNow = true;
        double motionY = OpenMaverFeatures.number(player, "motionY", "field_70181_x");
        if (placement != null && blockSlot >= 0) {
            if (groundTicks == 1 && jumpHeld) {
                firstGroundTick = true;
            }
            if (clutch && motionY < -0.1D) {
                double simY = simulateFallY(player, 2);
                if (placement.y > simY) {
                    canBuildNow = false;
                }
            }
        }
        if (onGround) {
            canBuildNow = true;
        }

        // 5) correctRotation
        if (placement == null) {
            // 没有目标就跟随相机（见类注释里的落地差异）。
            correctYaw = cameraYaw;
            correctPitch = cameraPitch;
        } else if (mode == MODE_TELLY_BRIDGE && canBuildNow) {
            float[] target = tellyRotation(player, firstGroundTick, jumpHeld);
            correctYaw = target[0];
            correctPitch = target[1];
        } else {
            float[] target = getPlayerYawRotation(player);
            correctYaw = target[0];
            correctPitch = target[1];
        }

        // 6) rots：clutch 按住 / 正常插值 + sneak / jump
        boolean holding = false;
        if (placement != null && clutchHold(clutch, canBuildNow, velocityDelay, rotationDelay)) {
            float[] locked = rotationToBlock(player, placement, 1.0F);
            rotsYaw = locked[0];
            rotsPitch = locked[1];
            rotationDelay++;
            holding = true;
            pending = null;
        } else {
            canBuildNow = true;
            rotationDelay = 0;
            if (mode == MODE_NORMAL && snap) {
                rotsYaw = correctYaw;
            } else {
                rotsYaw = rotateTowards(getBlockDistance(), rotsYaw, correctYaw);
            }
            rotsPitch = correctPitch;

            updateSneak(mc, player, onGround);
            updateJumpKey(mc, moving, jumpHeld);

            if ((mode == MODE_TELLY_BRIDGE || mode == MODE_OLD_TELLY) && airTicks < 1 && moving) {
                if (mode == MODE_OLD_TELLY) {
                    rotsYaw = playerYaw(player);
                }
                // OpenZen 在这一分支保留上一拍的 aim 并直接 return：起跳首 tick
                // 既不改写 lastRots，也不规划新的放置。
                aimForThisTick();
                return;
            }
            if (mode == MODE_NORMAL && snap && !jumpHeld) {
                resetSnap(player, world);
            }
        }

        // 7) 用本 tick 的 aim 规划下一 tick 的放置
        if (!holding && !holdPlaceAim && placement != null && pending == null
                && canBuildNow && shouldBuild(player, world)) {
            pending = new Pending(placement, rotsYaw, rotsPitch);
        }

        aimForThisTick();
    }

    /** 决定本 tick 写进 C03 的角度。 */
    private static void aimForThisTick() {
        if (holdPlaceAim) {
            tickAimYaw = holdPlaceYaw;
            tickAimPitch = holdPlacePitch;
            return;
        }
        if (pending != null) {
            tickAimYaw = pending.aimYaw;
            tickAimPitch = pending.aimPitch;
            return;
        }
        tickAimYaw = rotsYaw;
        tickAimPitch = rotsPitch;
    }

    /** 对应 OpenZen Scaffold#onMotion 里的 airTicks / groundTicks。 */
    private static void updateAirGroundTicks(Object player) {
        if (OpenMaverFeatures.scaffoldOnGround(player)) {
            airTicks = 0;
            groundTicks++;
        } else {
            groundTicks = 0;
            airTicks++;
        }
    }

    // ==================================================================
    // 目标格搜索（OpenZen applyRotations / isAbovePlaceable）
    // ==================================================================

    private static void applyRotations(Object player, Object world, float[] eye) {
        double eyeX = eye[0];
        double eyeY = eye[1];
        double eyeZ = eye[2];

        if (!canBuildNow) {
            eyeX += OpenMaverFeatures.number(player, "motionX", "field_70159_w") * 2.0D;
            eyeY += OpenMaverFeatures.number(player, "motionY", "field_70181_x") * 2.0D;
            eyeZ += OpenMaverFeatures.number(player, "motionZ", "field_70179_y") * 2.0D;
        }
        if (clutch && OpenMaverFeatures.number(player, "motionY", "field_70181_x") < 0.01D) {
            double simY = simulateFallY(player, 2)
                    + OpenMaverFeatures.eyeHeight(player);
            eyeY = Math.max(simY, eyeY);
        }

        int feetX = floor(eyeX);
        int feetZ = floor(eyeZ);
        // OpenZen: BlockPos.containing(eye.x, targetYLevel + 0.1, eye.z) —— +0.1 不会
        // 跨过方块边界，floor 之后仍是 targetYLevel。
        if (canStandOn(world, feetX, targetYLevel, feetZ)) {
            return;
        }
        if (isAbovePlaceable(world, eyeX, eyeY, eyeZ, feetX, targetYLevel, feetZ)) {
            return;
        }
        for (int radius = 1; radius <= SEARCH_RADIUS; radius++) {
            if (isAbovePlaceable(world, eyeX, eyeY, eyeZ,
                    feetX, targetYLevel - radius, feetZ)) {
                return;
            }
            for (int x = 1; x <= radius; x++) {
                for (int z = 0; z <= radius - x; z++) {
                    int yOff = radius - x - z;
                    for (int signX = 0; signX <= 1; signX++) {
                        for (int signZ = 0; signZ <= 1; signZ++) {
                            int testX = feetX + (signX == 0 ? x : -x);
                            int testY = targetYLevel - yOff;
                            int testZ = feetZ + (signZ == 0 ? z : -z);
                            if (isAbovePlaceable(world, eyeX, eyeY, eyeZ,
                                    testX, testY, testZ)) {
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 目标格必须是空气，且六个邻居里有一个实心支撑面：面中心到眼睛 4.5 格内、
     * 且法线朝向眼睛（{@code dot >= 0}，和 OpenZen 一致）。
     */
    private static boolean isAbovePlaceable(Object world, double eyeX, double eyeY,
            double eyeZ, int x, int y, int z) {
        if (!isAir(world, x, y, z)) {
            return false;
        }
        for (int face = 0; face < 6; face++) {
            int[] delta = FACING_DELTAS[face];
            int supportX = x + delta[0];
            int supportY = y + delta[1];
            int supportZ = z + delta[2];
            if (!isSolidSupport(world, supportX, supportY, supportZ)) {
                continue;
            }
            double centerX = x + 0.5D + delta[0] * 0.5D;
            double centerY = y + 0.5D + delta[1] * 0.5D;
            double centerZ = z + 0.5D + delta[2] * 0.5D;
            double dx = centerX - eyeX;
            double dy = centerY - eyeY;
            double dz = centerZ - eyeZ;
            double lengthSq = dx * dx + dy * dy + dz * dz;
            if (lengthSq > REACH_SQ || lengthSq < 1.0E-9D) {
                continue;
            }
            double dot = (dx * delta[0] + dy * delta[1] + dz * delta[2])
                    / Math.sqrt(lengthSq);
            if (dot < 0.0D) {
                continue;
            }
            placement = new Placement(supportX, supportY, supportZ, OPPOSITE_FACING[face]);
            return true;
        }
        return false;
    }

    // ==================================================================
    // 旋转（OpenZen getTargetRotation / findValidRotation / getSnappedRotation）
    // ==================================================================

    /**
     * Telly Bridge 的起步/空中旋转表。
     *
     * <p>groundTicks == 1 是起跳那一 tick：第一次按空格时 clamp 到一半的 yaw 差，
     * 否则直接用命中点角度，并把 1.8.9 的 {@code jumpTicks} 设成 2（OpenZen 的
     * {@code setJumpDelay(2)}）；groundTicks == 2 固定俯角 75.5°。空中第一 tick 允许
     * 90° 的 yaw 步长，之后收到 50°。
     */
    private static float[] tellyRotation(Object player, boolean firstGround,
            boolean jumpHeld) {
        if (!isInputActive(player)) {
            return getPlayerYawRotation(player);
        }
        if (placement == null) {
            return new float[]{playerYaw(player), playerPitch(player)};
        }
        float[] aim = rotationToHitVec(player, placement);
        float referenceYaw = Float.isNaN(prevAimYaw) ? playerYaw(player) : prevAimYaw;
        double yawDelta = angleDiffDouble(aim[0], referenceYaw);
        if (groundTicks > 0) {
            if (!jumpHeld) {
                return new float[]{playerYaw(player), 75.5F};
            }
            if (groundTicks == 1) {
                if (!firstGround) {
                    aim[0] = referenceYaw + clampAngle((float) yawDelta,
                            (float) (yawDelta / 2.0D));
                    aim[1] = 75.5F;
                } else {
                    aim = rotationToHitVec(player, placement);
                }
                setJumpDelay(player, 2);
            } else if (groundTicks == 2) {
                return new float[]{playerYaw(player), 75.5F};
            }
        } else {
            float clampLimit = airTicks == 1 ? 90.0F : 50.0F;
            clampLimit -= randomFloat(0.001F, 0.006F);
            aim[0] = referenceYaw + clampAngle((float) yawDelta, clampLimit);
        }
        aim = findValidRotation(player, aim, firstGround);
        return getSnappedRotation(player, aim);
    }

    private static float[] findValidRotation(Object player, float[] aim, boolean firstGround) {
        if (firstGround) {
            return aim;
        }
        float referenceYaw = Float.isNaN(prevAimYaw) ? playerYaw(player) : prevAimYaw;
        float[] optimal = getOptimalRotation(aim, referenceYaw);
        double maxStep = Math.max(45.0D, 180.0D / Math.max(1.0D, rotationTick));
        if (mode == MODE_TELLY_BRIDGE) {
            maxStep = Math.max(maxStep, 75.0D);
        }
        return smoothRotation(referenceYaw,
                Float.isNaN(prevAimPitch) ? playerPitch(player) : prevAimPitch,
                optimal[0], optimal[1], maxStep);
    }

    /** yaw 相对参考角最多偏 90°。 */
    private static float[] getOptimalRotation(float[] aim, float referenceYaw) {
        float yaw = aim[0];
        double delta = wrapDegrees(yaw - referenceYaw);
        if (Math.abs(delta) > 90.0D) {
            yaw = (float) (referenceYaw + Math.copySign(90.0D, delta));
        }
        return new float[]{yaw, clampPitch(aim[1])};
    }

    /**
     * 连续两 tick 的 yaw/pitch 差完全一样时加一点抖动，避免服务端看到
     * “每 tick 分毫不差”的机械转向（OpenZen 的 getSnappedRotation）。
     */
    private static float[] getSnappedRotation(Object player, float[] rotation) {
        float referenceYaw = Float.isNaN(prevAimYaw) ? playerYaw(player) : prevAimYaw;
        float referencePitch = Float.isNaN(prevAimPitch) ? playerPitch(player) : prevAimPitch;
        yawDiff = Math.abs(wrapDegrees(rotation[0] - referenceYaw));
        pitchDiff = Math.abs(rotation[1] - referencePitch);
        boolean stuckPitch = pitchDiff > 2.0F
                && !Double.isNaN(lastPitchDiff)
                && Math.abs(pitchDiff - lastPitchDiff) < 1.0E-4D;
        boolean stuckYaw = yawDiff > 2.0F
                && !Double.isNaN(lastYawDiff)
                && Math.abs(yawDiff - lastYawDiff) < 1.0E-4D;
        float yaw = rotation[0];
        float pitch = rotation[1];
        if (stuckPitch || stuckYaw) {
            float jitterYaw = randomFloat(0.095F, 0.19F);
            float jitterPitch = randomFloat(0.016F, 0.055F);
            if ((jitterCounter++ & 1) == 0) {
                jitterYaw = -jitterYaw;
            }
            yaw += jitterYaw;
            pitch = Math.max(-89.5F, Math.min(89.5F, pitch + jitterPitch));
            yawDiff = Math.abs(wrapDegrees(yaw - referenceYaw));
            pitchDiff = Math.abs(pitch - referencePitch);
        }
        lastYawDiff = yawDiff;
        lastPitchDiff = pitchDiff;
        return new float[]{yaw, pitch};
    }

    /** OpenZen RotationUtil.smoothRotation：按角差比例分配步长 + 灵敏度量化 + 抖动。 */
    private static float[] smoothRotation(float fromYaw, float fromPitch,
            float toYaw, float toPitch, double speed) {
        float yaw = toYaw;
        float pitch = toPitch;
        if (speed != 0.0D) {
            float maxStep = (float) speed;
            double yawDelta = wrapDegrees(toYaw - fromYaw);
            double pitchDelta = toPitch - fromPitch;
            double distance = Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);
            if (distance < 1.0E-9D) {
                return new float[]{toYaw, toPitch};
            }
            double maxYawStep = maxStep * Math.abs(yawDelta / distance);
            double maxPitchStep = maxStep * Math.abs(pitchDelta / distance);
            float yawStep = (float) Math.max(Math.min(yawDelta, maxYawStep), -maxYawStep);
            float pitchStep = (float) Math.max(Math.min(pitchDelta, maxPitchStep),
                    -maxPitchStep);
            yaw = fromYaw + yawStep;
            pitch = fromPitch + pitchStep;
        }
        boolean addJitter = Math.random() > 0.8D;
        int iterations = (int) (2.0D + Math.random() * 2.0D);
        for (int i = 1; i <= iterations; i++) {
            if (addJitter) {
                yaw += (float) ((Math.random() - 0.5D) / 1.0E8D);
                pitch -= (float) (Math.random() / 2.0E8D);
            }
            float[] snapped = snapToSensitivity(yaw, pitch);
            yaw = snapped[0];
            pitch = clampPitch(snapped[1]);
        }
        return new float[]{yaw, pitch};
    }

    /** 把 yaw/pitch 量化到当前鼠标灵敏度对应的步长。 */
    private static float[] snapToSensitivity(float yaw, float pitch) {
        double sensitivity = 0.5D;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
            sensitivity = OpenMaverFeatures.number(settings, "mouseSensitivity", "field_74341_c");
        }
        float scaled = (float) sensitivity * 0.6F + 0.2F;
        float step = scaled * scaled * scaled * 1.2F;
        if (step <= 0.0F) {
            return new float[]{yaw, pitch};
        }
        return new float[]{yaw - yaw % step, pitch - pitch % step};
    }

    /** OpenZen getBlockDistance：单 tick 步长（rotationTick 越小越快）。 */
    static double getBlockDistance() {
        return getBlockDistance(mode, rotationTick);
    }

    static double getBlockDistance(int scaffoldMode, int ticks) {
        if (scaffoldMode == MODE_OLD_TELLY) {
            return 180.0D;
        }
        double base = Math.max(60.0D, 360.0D / Math.max(1, ticks));
        return Math.max(base, 180.0D);
    }

    /** OpenZen resetSnap：瞄准点没落在目标上时，每 4 tick 在相机角上加 ±0.25° 抖动。 */
    private static void resetSnap(Object player, Object world) {
        if (placement == null) {
            return;
        }
        boolean lookingAtBlock = false;
        Object mop = rayTraceFrom(world, eyePosition(player), rotsYaw, rotsPitch,
                blockReachDistance());
        if (mop != null) {
            Object hitPos = OpenMaverFeatures.read(mop, "blockPos", "field_178783_e", null);
            Object side = OpenMaverFeatures.read(mop, "sideHit", "field_178784_b", null);
            if (hitPos != null && blockPosEquals(hitPos, placement.x, placement.y, placement.z)
                    && facingIndex(side) != 1) {
                lookingAtBlock = true;
            }
        }
        if (!lookingAtBlock && currentTick % 4L == 0L) {
            rotsYaw = playerYaw(player) + randomFloat(0.0F, 0.5F) - 0.25F;
        }
    }

    private static float[] getPlayerYawRotation(Object player) {
        if (placement == null) {
            // OpenZen 这里返回 (0,0)；没有目标时跟随相机更安全（见类注释）。
            return new float[]{cameraYaw, cameraPitch};
        }
        return rotationToBlock(player, placement, 0.0F);
    }

    /** 从眼睛指向支撑面中心（带 OpenZen rotationToBlock 的噪声）。 */
    private static float[] rotationToBlock(Object player, Placement target, float partialTicks) {
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t")
                + OpenMaverFeatures.number(player, "motionX", "field_70159_w") * partialTicks;
        double py = OpenMaverFeatures.number(player, "posY", "field_70163_u")
                + OpenMaverFeatures.eyeHeight(player)
                + OpenMaverFeatures.number(player, "motionY", "field_70181_x") * partialTicks;
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v")
                + OpenMaverFeatures.number(player, "motionZ", "field_70179_y") * partialTicks;
        double dx = target.x - px + 0.5D;
        double dy = target.y - py + 0.5D;
        double dz = target.z - pz + 0.5D;
        return rotationFromDeltas(addNoise(dx), addNoise(dy), addNoise(dz));
    }

    /** 从眼睛指向随机化的点击点（OpenZen getHitVec + RotationUtil.rotationFromVec）。 */
    private static float[] rotationToHitVec(Object player, Placement target) {
        double[] hit = hitVec(target);
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double py = OpenMaverFeatures.number(player, "posY", "field_70163_u")
                + OpenMaverFeatures.eyeHeight(player);
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        return rotationFromDeltas(addNoise(hit[0] - px), addNoise(hit[1] - py),
                addNoise(hit[2] - pz));
    }

    private static float[] rotationFromDeltas(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return new float[]{wrapDegrees(yaw), wrapDegrees(pitch)};
    }

    private static double addNoise(double value) {
        return value + randomDouble(0.05D, 0.08D) * (randomDouble(0.0D, 1.0D) * 2.0D - 1.0D);
    }

    // ==================================================================
    // 放置
    // ==================================================================

    /**
     * 校验并执行上一 tick 排队的放置。
     *
     * <p>只有「aim 真的写进了上一 tick 的 C03」＋「用同一个角度、当前眼睛做
     * ray trace 仍命中支撑面」才发 C08；打不到就重算角度、下一 tick 再来，
     * 重试上限 {@link #MAX_PENDING_RETRIES}。
     */
    private static void flushPending(Minecraft mc, Object player, Object world) {
        Pending target = pending;
        if (target == null) {
            return;
        }
        if (!target.sent || !comesFromServerSlot()) {
            pending = null;
            return;
        }
        if (!canPlaceNow(player, world) || !shouldBuild(player, world)) {
            pending = null;
            return;
        }
        Object mop = rayTraceFrom(world, eyePosition(player), target.sentYaw, target.sentPitch,
                blockReachDistance());
        boolean rayHit = mopMatches(mop, target.x, target.y, target.z, target.face);
        int decision = pendingDecision(target.sent, rayHit, target.retries);
        if (decision == PENDING_DROP) {
            OpenMaverDebug.trace("scaffold", "target dropped (sent=" + target.sent
                    + " hit=" + rayHit + " retries=" + target.retries + ")");
            pending = null;
            return;
        }
        if (decision == PENDING_RETRY) {
            target.retries++;
            return;
        }
        if (place(mc, player, world, target)) {
            holdPlaceAim = true;
            holdPlaceYaw = target.sentYaw;
            holdPlacePitch = target.sentPitch;
            pending = null;
        } else {
            pending = null;
        }
    }

    /** OpenZen doSnap：真实右键（服务端已持有这条 aim）。 */
    private static boolean place(Minecraft mc, Object player, Object world, Pending target) {
        if (!targetBlockReachableByFace(player, target)) {
            return false;
        }
        if (!OpenMaverFeatures.claimPlacement()) {
            // 一 tick 一个 C08：与 Velocity 的 farm 共用名额（MultiPlace）。
            OpenMaverDebug.noticeOnce("scaffold.place.shared",
                    "another module already sent a C08 this tick (MultiPlace)");
            return false;
        }
        Object stack = blockStack(player);
        Object controller = OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        if (stack == null || controller == null) {
            return false;
        }
        Object result = OpenMaverFeatures.call(controller, "onPlayerRightClick",
                "func_178890_a", null, player, world, stack,
                blockPos(target.x, target.y, target.z), facing(target.face),
                vec3(hitVec(target)));
        if (!Boolean.TRUE.equals(result)) {
            OpenMaverDebug.noticeOnce("scaffold.place.failed",
                    "onPlayerRightClick returned " + String.valueOf(result));
            return false;
        }
        OpenMaverFeatures.callVoid(player, "swingItem", "func_71038_i", null);
        blockCount = countBlocks(player);
        OpenMaverDebug.place(target.x, target.y, target.z, FACING_NAMES[target.face],
                target.sentYaw, target.sentPitch, false, false, 1);
        return true;
    }

    /** OpenZen doSnap 里的额外过滤：向上搭时空中平走不点顶面。 */
    private static boolean targetBlockReachableByFace(Object player, Pending target) {
        Minecraft mc = Minecraft.getMinecraft();
        if (target.face != 1 || mc == null || mode == MODE_NORMAL) {
            return true;
        }
        return OpenMaverFeatures.scaffoldOnGround(player)
                || !isMoving(mc)
                || keyDown(mc, "keyBindJump");
    }

    /** OpenZen shouldBuild：脚下方块是空气 + 手持可放置方块。 */
    private static boolean shouldBuild(Object player, Object world) {
        double x = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double y = OpenMaverFeatures.number(player, "posY", "field_70163_u");
        double z = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        return isAir(world, floor(x), floor(y - 0.5D), floor(z)) && blockSlot >= 0;
    }

    /** 屏幕 / 存活 / 快捷栏 / 手里到底有没有方块的综合判定。 */
    private static boolean canPlaceNow(Object player, Object world) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        if (OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null) != null) {
            return false;
        }
        return player != null && world != null && OpenMaverFeatures.alive(player)
                && blockSlot >= 0 && blockStack(player) != null;
    }

    /** 服务端确认的槽位就是方块槽（itemSpoof 时客户端槽位会临时切回去）。 */
    private static boolean comesFromServerSlot() {
        return blockSlot >= 0 && serverSlot == blockSlot;
    }

    // ==================================================================
    // 静默转向 / MoveFix（MovementInput hook）
    // ==================================================================

    /**
     * 由 {@link ScaffoldMovementInput} 在 vanilla 更新完按键输入之后调用。
     *
     * <ol>
     *   <li>按 aim 与相机的差值旋转输入向量，再吸附到 8 个整数方向之一
     *       （OpenZen MovementUtil.handleStrafe 的等价物）；</li>
     *   <li>把 aim 写进 rotationYaw/Pitch，本 tick 的 C03 会带上它；</li>
     *   <li>tick END 恢复相机（见 {@link #onTickEnd}）。</li>
     * </ol>
     */
    static void afterMovementInput(Object input) {
        if (!hookArmed) {
            return;
        }
        if (System.nanoTime() - hookArmedNanos > 500_000_000L) {
            hookArmed = false;
            restoreCameraView(aimPlayer);
            return;
        }
        if (!active || failed) {
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

            // 只有真的有 WASD 输入时才改输入向量：没有输入却套用 8 方向映射，
            // 会凭空造出一个 forward=-1 的“自己往后走”。
            if (!aimApplied && isMoving(mc)
                    && (pending != null || holdPlaceAim || placement != null)) {
                float aimYaw = holdPlaceAim ? holdPlaceYaw
                        : (pending != null ? pending.aimYaw : rotsYaw);
                float cameraMoveYaw = adjustYaw(cameraYaw, forwardSign(mc), strafeSign(mc));
                float angle = OpenMaverFeatures.wrapDegrees(cameraMoveYaw - aimYaw + 22.5F);
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
                // 原版随后用 moveForward >= 0.8 维持冲刺；斜着搭桥只有 0.707，
                // 等比放大不改变世界方向（moveFlying 会归一化），但能保住冲刺。
                if (forward > 0.0F && forward < 0.8F) {
                    float scale = 0.8F / forward;
                    forward *= scale;
                    strafe *= scale;
                }
            }
            if (forward != originalForward || strafe != originalStrafe) {
                setNumber(input, "moveForward", "field_78900_b", forward);
                setNumber(input, "moveStrafe", "field_78901_c", strafe);
            }

            if (!aimApplied) {
                float writeYaw = OpenMaverFeatures.continuousYaw(playerYaw(player), tickAimYaw);
                setFloat(player, "rotationYaw", "field_70177_z", writeYaw);
                setFloat(player, "rotationPitch", "field_70125_A", tickAimPitch);
                aimApplied = true;
                rotationDirty = true;
                prevAimYaw = writeYaw;
                prevAimPitch = tickAimPitch;
                if (pending != null) {
                    pending.sentYaw = writeYaw;
                    pending.sentPitch = tickAimPitch;
                    pending.sent = true;
                }
            }
        } catch (Throwable ignored) {
            // hook 失败绝不能打断玩家 tick。
        }
    }

    /** 把 rotation 恢复到真实相机角度（同时走最短路径，避免 360° 跳变）。 */
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

    /**
     * 安装 {@link ScaffoldMovementInput}。
     *
     * <p>只在没有别的 mod 覆盖 {@code movementInput} 时替换；模块关闭时子类不改
     * 任何字段，所以不需要恢复。重生/切维度后玩家对象会变，每 tick 检查一次。
     */
    private static void ensureMovementHook(Minecraft mc, Object player) {
        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        if (input instanceof ScaffoldMovementInput) {
            return;
        }
        if (input != null
                && !"net.minecraft.util.MovementInputFromOptions".equals(
                        input.getClass().getName())) {
            OpenMaverDebug.noticeOnce("scaffold.hook.custom",
                    "custom MovementInput detected; silent rotation hook not installed");
            return;
        }
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        if (!(settings instanceof GameSettings)) {
            return;
        }
        ScaffoldMovementInput hook = new ScaffoldMovementInput((GameSettings) settings);
        if (input != null) {
            setNumber(hook, "moveForward", "field_78900_b",
                    OpenMaverFeatures.number(input, "moveForward", "field_78900_b"));
            setNumber(hook, "moveStrafe", "field_78901_c",
                    OpenMaverFeatures.number(input, "moveStrafe", "field_78901_c"));
            setBoolean(hook, "jump", "field_78903_e", Boolean.TRUE.equals(
                    OpenMaverFeatures.read(input, "jump", "field_78903_e", Boolean.class)));
            setBoolean(hook, "sneak", "field_78899_d", Boolean.TRUE.equals(
                    OpenMaverFeatures.read(input, "sneak", "field_78899_d", Boolean.class)));
        }
        if (OpenMaverFeatures.writeObjectField(player, "movementInput", "field_71158_b", hook)) {
            OpenMaverDebug.noticeOnce("scaffold.hook",
                    "ScaffoldMovementInput installed (silent aim + move fix)");
        } else {
            OpenMaverDebug.noticeOnce("scaffold.hook.fail",
                    "cannot replace movementInput; silent rotation disabled");
        }
    }

    // ==================================================================
    // Eagle / Sneak / Jump（OpenZen onTick 的输入接管部分）
    // ==================================================================

    /** OpenZen 的 sneak：第 18 tick 停下冲刺并按下潜行，第 21 tick 松开。 */
    private static void updateSneak(Minecraft mc, Object player, boolean onGround) {
        if (sneak) {
            eagleTimer++;
            if (eagleTimer == 18) {
                if (Boolean.TRUE.equals(OpenMaverFeatures.call(player,
                        "isSprinting", "func_70051_ag", null))) {
                    setKeyPressed(mc, "keyBindSprint", false);
                    OpenMaverFeatures.callVoid(player, "setSprinting", "func_70031_b",
                            null, Boolean.FALSE);
                }
                setKeyPressed(mc, "keyBindSneak", true);
            } else if (eagleTimer >= 21) {
                setKeyPressed(mc, "keyBindSneak", false);
                eagleTimer = 0;
            }
        }
        if (mode == MODE_NORMAL && eagle) {
            setKeyPressed(mc, "keyBindSneak", onGround && isOnBlockEdge(player, 0.3F));
        }
    }

    /** Telly / KeepY 会自动按住空格；Normal 不动跳跃键。 */
    private static void updateJumpKey(Minecraft mc, boolean moving, boolean jumpHeld) {
        if (mode == MODE_TELLY_BRIDGE || mode == MODE_OLD_TELLY || mode == MODE_KEEP_Y) {
            setKeyPressed(mc, "keyBindJump", moving || jumpHeld);
        }
    }

    /** 关闭模块时把被接管的按键还给物理键盘状态。 */
    private static void restoreKeys(Minecraft mc) {
        if (mc == null) {
            return;
        }
        restoreKey(mc, "keyBindJump", "field_74314_A");
        restoreKey(mc, "keyBindSneak", "field_74311_E");
        restoreKey(mc, "keyBindSprint", "field_74321_H");
    }

    private static void restoreKey(Minecraft mc, String fieldName, String srg) {
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object binding = OpenMaverFeatures.read(settings, fieldName, srg, null);
        if (binding == null) {
            return;
        }
        Object code = OpenMaverFeatures.call(binding, "getKeyCode", "func_151463_i", null);
        boolean physical = code instanceof Number
                && Keyboard.isKeyDown(((Number) code).intValue());
        setBoolean(binding, "pressed", "field_74513_e", physical);
    }

    /** 与 OpenZen isOnBlockEdge(0.3)：脚下半格、水平收缩一圈后没有碰撞面。 */
    private static boolean isOnBlockEdge(Object player, float inflate) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
        AxisAlignedBB box = OpenMaverFeatures.boundingBox(player);
        if (world == null || box == null) {
            return false;
        }
        AxisAlignedBB shrunk = new AxisAlignedBB(
                box.minX + inflate, box.minY - 0.5D, box.minZ + inflate,
                box.maxX - inflate, box.maxY - 0.5D, box.maxZ - inflate);
        Object colliding = OpenMaverFeatures.call(world, "getCollidingBoundingBoxes",
                "func_72945_a", null, player, shrunk);
        return colliding instanceof List && ((List<?>) colliding).isEmpty();
    }

    // ==================================================================
    // 击退感知（OpenZen onPacket: S12）
    // ==================================================================

    /**
     * 入站包观察者，由 {@link VelocityPacketHook} 在 Netty 线程调用。
     *
     * <p>OpenZen 在 {@code ClientboundSetEntityMotionPacket} 里判断“水平速度 ≥ 1.5
     * → velocityDelay = 60”。1.8.9 这里是 {@code S12PacketEntityVelocity}，只读字段，
     * 不碰任何游戏对象，因此可以在网络线程安全调用。
     */
    public static void noteInboundPacket(Object msg) {
        if (!active || failed || msg == null) {
            return;
        }
        if (!msg.getClass().getName().startsWith(
                "net.minecraft.network.play.server.S12PacketEntityVelocity")) {
            return;
        }
        int[] values = intFields(msg);
        if (values == null || values.length < 4) {
            return;
        }
        Object player = aimPlayer;
        if (player == null) {
            return;
        }
        Object id = OpenMaverFeatures.call(player, "getEntityId", "func_145782_y", null);
        if (!(id instanceof Number) || values[0] != ((Number) id).intValue()) {
            return;
        }
        double length = Math.hypot(values[1] / 8000.0D, values[3] / 8000.0D);
        if (length >= 1.5D) {
            velocityDelay = VELOCITY_DELAY_TICKS;
        }
    }

    /** S12 的四个 int 字段按声明顺序：entityId, motionX, motionY, motionZ。 */
    private static int[] intFields(Object packet) {
        try {
            int[] values = new int[4];
            int index = 0;
            for (Field field : packet.getClass().getDeclaredFields()) {
                if (field.getType() != Integer.TYPE || index >= 4) {
                    continue;
                }
                field.setAccessible(true);
                values[index++] = field.getInt(packet);
            }
            return index == 4 ? values : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 快捷栏 / 方块统计
    // ==================================================================

    /**
     * 扫描快捷栏并切到第一组可放置方块。
     *
     * <p>切槽必须让服务端知道（否则 C08 会在服务端用错槽位的物品）：
     * {@code serverSlot} 变化时补一条 C09。
     */
    private static void updateHotbar(Object player) {
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        Object main = OpenMaverFeatures.read(inventory, "mainInventory", "field_70462_a", null);
        if (!(main instanceof Object[])) {
            blockSlot = -1;
            blockCount = 0;
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
                sendHeldItemChange(player, found);
                serverSlot = found;
            }
        }
        blockCount = countBlocks(slots);
    }

    private static int countBlocks(Object[] slots) {
        int total = 0;
        for (int i = 0; i < slots.length; i++) {
            if (!isPlaceableBlock(slots[i])) {
                continue;
            }
            total += (int) OpenMaverFeatures.number(slots[i], "stackSize", "field_77994_a");
        }
        return total;
    }

    private static int countBlocks(Object player) {
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        Object main = OpenMaverFeatures.read(inventory, "mainInventory", "field_70462_a", null);
        return main instanceof Object[] ? countBlocks((Object[]) main) : 0;
    }

    /** 当前要用的方块堆（方块槽里的那一组）。 */
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

    /** HUD 用：OpenZen ScaffoldHud 显示的是手持/首格方块的堆叠数。 */
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

    /** HUD 用：当前可放置方块总数（-1 = 本 tick 还没统计）。 */
    public static int getBlockCount() {
        return blockCount;
    }

    /**
     * 本 tick 是否会写入静默 aim。
     *
     * <p>Velocity 的 SPRINT SLAP 用它避让：Scaffold 在 movementInput hook 里改
     * rotation，那条写包比 SLAP 的准星判定更晚；同一 tick 两个模块都改视角时，
     * 服务端看到的 rotation 和 SLAP 判定的不是同一个角度。
     */
    public static boolean isAimingThisTick() {
        if (!active || failed) {
            return false;
        }
        return hookArmed && (holdPlaceAim || pending != null || placement != null);
    }

    /** HUD 用：OpenZen 的 {@code %.2fb/s}。 */
    public static double hudSpeedBps(Object player) {
        double dx = OpenMaverFeatures.number(player, "posX", "field_70165_t")
                - OpenMaverFeatures.number(player, "lastTickPosX", "field_70169_q");
        double dz = OpenMaverFeatures.number(player, "posZ", "field_70161_v")
                - OpenMaverFeatures.number(player, "lastTickPosZ", "field_70166_s");
        return Math.hypot(dx, dz) * 20.0D;
    }

    /** 可放置方块：ItemBlock + 不是花/农作物/半砖/蛛网之类。 */
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

    private static int currentHotbarSlot(Object player) {
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        return (int) OpenMaverFeatures.number(inventory, "currentItem", "field_70461_c");
    }

    /** 只改客户端槽位（渲染用），不给服务端发 C09。 */
    private static void setSlotClientOnly(Object player, int slot) {
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        if (inventory != null) {
            setNumber(inventory, "currentItem", "field_70461_c", slot);
        }
    }

    private static void restoreSlot(Object player) {
        if (oldSlot < 0) {
            return;
        }
        setSlotClientOnly(player, oldSlot);
        if (serverSlot != oldSlot) {
            sendHeldItemChange(player, oldSlot);
            serverSlot = oldSlot;
        }
    }

    private static void sendHeldItemChange(Object player, int slot) {
        if (heldItemChangeFailed) {
            return;
        }
        try {
            if (heldItemChangeConstructor == null) {
                Class<?> type = Class.forName(
                        "net.minecraft.network.play.client.C09PacketHeldItemChange");
                heldItemChangeConstructor = type.getConstructor(Integer.TYPE);
            }
            Object sendQueue = OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
            if (sendQueue == null) {
                return;
            }
            OpenMaverFeatures.callVoid(sendQueue, "addToSendQueue", "func_147297_a", null,
                    heldItemChangeConstructor.newInstance(Integer.valueOf(slot)));
        } catch (Throwable error) {
            heldItemChangeFailed = true;
            OpenMaverDebug.noticeOnce("scaffold.slot.packet",
                    "C09PacketHeldItemChange unavailable: " + error);
        }
    }

    // ==================================================================
    // 世界坐标 / 方块判定
    // ==================================================================

    private static float[] eyePosition(Object player) {
        return new float[]{
                (float) OpenMaverFeatures.number(player, "posX", "field_70165_t"),
                (float) (OpenMaverFeatures.number(player, "posY", "field_70163_u")
                        + OpenMaverFeatures.eyeHeight(player)),
                (float) OpenMaverFeatures.number(player, "posZ", "field_70161_v")
        };
    }

    /** 两 tick 的垂直运动模拟（OpenZen MotionSimulator 里唯一被 clutch 用到的部分）。 */
    private static double simulateFallY(Object player, int ticks) {
        double y = OpenMaverFeatures.number(player, "posY", "field_70163_u");
        double motionY = OpenMaverFeatures.number(player, "motionY", "field_70181_x");
        for (int i = 0; i < ticks; i++) {
            motionY -= 0.08D;
            motionY *= 0.98D;
            y += motionY;
        }
        return y;
    }

    private static boolean canStandOn(Object world, int x, int y, int z) {
        Object pos = blockPos(x, y, z);
        Object state = getBlockState(world, pos);
        Object block = state == null ? null
                : OpenMaverFeatures.call(state, "getBlock", "func_177230_c", null);
        if (block == null || !isSolidBlock(block)) {
            return false;
        }
        Object box = OpenMaverFeatures.call(block, "getCollisionBoundingBox",
                "func_180646_a", null, world, pos, state);
        if (!(box instanceof AxisAlignedBB)) {
            return false;
        }
        return ((AxisAlignedBB) box).maxY - y >= 0.999D;
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
        Object material = block == null ? null
                : OpenMaverFeatures.call(block, "getMaterial", "func_149688_o", null);
        Object replaceable = material == null ? null
                : OpenMaverFeatures.call(material, "isReplaceable", "func_76222_j", null);
        return Boolean.TRUE.equals(replaceable);
    }

    /** 实心方块：排除液体/空气以及 OpenZen BlockUtil.isSolid 里那一组“非整块”。 */
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

    // ==================================================================
    // ray trace / 点击点 / EnumFacing
    // ==================================================================

    private static double blockReachDistance() {
        Minecraft mc = Minecraft.getMinecraft();
        Object controller = mc == null ? null
                : OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        Object value = OpenMaverFeatures.call(controller, "getBlockReachDistance",
                "func_78757_d", null);
        double reach = value instanceof Number ? ((Number) value).doubleValue() : 4.5D;
        return Math.max(4.5D, Math.min(5.0D, reach));
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
        // 1.8.9 World.rayTraceBlocks 的重载各有不同 SRG：两参数版是 func_72933_a。
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

    /** OpenZen getHitVec：面中心 + 面内随机偏移（不沿法线偏移）。 */
    private static double[] hitVec(Placement target) {
        double x = target.x + 0.5D;
        double y = target.y + 0.5D;
        double z = target.z + 0.5D;
        if (target.face != 1 && target.face != 0) {
            y += randomDouble(0.3D, -0.3D);
        } else {
            x += randomDouble(0.3D, -0.3D);
            z += randomDouble(0.3D, -0.3D);
        }
        if (target.face == 4 || target.face == 5) {
            z += randomDouble(0.3D, -0.3D);
        }
        if (target.face == 2 || target.face == 3) {
            x += randomDouble(0.3D, -0.3D);
        }
        return new double[]{x, y, z};
    }

    private static double[] hitVec(Pending target) {
        return hitVec(new Placement(target.x, target.y, target.z, target.face));
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

    private static int facingIndex(Object facingObj) {
        if (facings == null) {
            facing(0);
        }
        if (facings != null) {
            for (int i = 0; i < 6; i++) {
                if (facings[i] == facingObj) {
                    return i;
                }
            }
        }
        String name = String.valueOf(facingObj);
        for (int i = 0; i < 6; i++) {
            if (FACING_NAMES[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return 1;
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

    // ==================================================================
    // 输入 / 字段读写
    // ==================================================================

    private static boolean isMoving(Minecraft mc) {
        return keyDown(mc, "keyBindForward") || keyDown(mc, "keyBindBack")
                || keyDown(mc, "keyBindLeft") || keyDown(mc, "keyBindRight");
    }

    private static boolean isInputActive(Object player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || player == null) {
            return false;
        }
        float forward = (float) OpenMaverFeatures.number(
                OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null),
                "moveForward", "field_78900_b");
        float strafe = (float) OpenMaverFeatures.number(
                OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null),
                "moveStrafe", "field_78901_c");
        return forward != 0.0F || strafe != 0.0F;
    }

    private static int forwardSign(Minecraft mc) {
        int value = 0;
        if (keyDown(mc, "keyBindForward")) {
            value++;
        }
        if (keyDown(mc, "keyBindBack")) {
            value--;
        }
        return value;
    }

    private static int strafeSign(Minecraft mc) {
        int value = 0;
        if (keyDown(mc, "keyBindLeft")) {
            value++;
        }
        if (keyDown(mc, "keyBindRight")) {
            value--;
        }
        return value;
    }

    /** 相机 yaw + WASD 的期望世界方向（OpenZen MovementUtil.getDirectionYaw）。 */
    private static float adjustYaw(float yaw, float forward, float strafe) {
        if (forward < 0.0F) {
            yaw += 180.0F;
        }
        if (strafe != 0.0F) {
            float multiplier = forward == 0.0F ? 1.0F : 0.5F * Math.signum(forward);
            yaw += -90.0F * multiplier * Math.signum(strafe);
        }
        return OpenMaverFeatures.wrapDegrees(yaw);
    }

    private static boolean keyDown(Minecraft mc, String fieldName) {
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object binding = OpenMaverFeatures.read(settings, fieldName,
                keyBindingSrg(fieldName), null);
        Object pressed = OpenMaverFeatures.read(binding, "pressed", "field_74513_e", Boolean.class);
        return Boolean.TRUE.equals(pressed);
    }

    /**
     * 直接改 KeyBinding.pressed（等价 OpenZen 的 {@code setDown}）。
     *
     * <p>必须每 tick 重新写：Forge 的按键事件会在物理松开时把它清掉。
     */
    private static void setKeyPressed(Minecraft mc, String fieldName, boolean pressed) {
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object binding = OpenMaverFeatures.read(settings, fieldName,
                keyBindingSrg(fieldName), null);
        if (binding != null) {
            setBoolean(binding, "pressed", "field_74513_e", pressed);
        }
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
        if ("keyBindUseItem".equals(fieldName)) {
            return "field_74313_G";
        }
        return fieldName;
    }

    /** OpenZen 的 {@code ReflectionUtil.setJumpDelay}：1.8.9 是 jumpTicks。 */
    private static void setJumpDelay(Object player, int delay) {
        setNumber(player, "jumpTicks", "field_70767_bF", delay);
    }

    private static float playerYaw(Object player) {
        return (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z");
    }

    private static float playerPitch(Object player) {
        return (float) OpenMaverFeatures.number(player, "rotationPitch", "field_70125_A");
    }

    private static void setFloat(Object target, String mcp, String srg, float value) {
        OpenMaverFeatures.setNumber(target, mcp, srg, value);
    }

    private static void setNumber(Object target, String mcp, String srg, double value) {
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
            // 映射差异时忽略：按键接管失败不能拖垮 tick。
        }
    }

    // ==================================================================
    // 世界渲染（OpenZen Scaffold#onRender）
    // ==================================================================

    /** RenderWorldLastEvent：把目标空气格画成蓝色半透明方块。 */
    public static void onRenderWorld(net.minecraftforge.client.event.RenderWorldLastEvent event) {
        if (!active || failed || placement == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        Object renderManager = OpenMaverFeatures.read(mc, "renderManager",
                "field_175616_W", null);
        double cameraX = OpenMaverFeatures.number(renderManager, "viewerPosX", "field_78725_b");
        double cameraY = OpenMaverFeatures.number(renderManager, "viewerPosY", "field_78726_c");
        double cameraZ = OpenMaverFeatures.number(renderManager, "viewerPosZ", "field_78723_d");

        int[] delta = FACING_DELTAS[placement.face];
        double x1 = placement.x + delta[0] - cameraX;
        double y1 = placement.y + delta[1] - cameraY;
        double z1 = placement.z + delta[2] - cameraZ;
        double x2 = x1 + 1.0D;
        double y2 = y1 + 1.0D;
        double z2 = z1 + 1.0D;

        GlColor.restore();
        org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ENABLE_BIT
                | org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
                | org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA,
                org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_LIGHTING);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);

        // 面：74,144,226 @ 0.25
        GlColor.set(0.29F, 0.56F, 0.89F, 0.25F);
        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS);
        quad(x1, y1, z1, x2, y2, z2);
        org.lwjgl.opengl.GL11.glEnd();
        // 边框：同色 @ 0.75
        GlColor.set(0.29F, 0.56F, 0.89F, 0.75F);
        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_LINES);
        edges(x1, y1, z1, x2, y2, z2);
        org.lwjgl.opengl.GL11.glEnd();

        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glPopAttrib();
        GlColor.restore();
    }

    private static void quad(double x1, double y1, double z1,
            double x2, double y2, double z2) {
        org.lwjgl.opengl.GL11.glVertex3d(x1, y1, z1);
        org.lwjgl.opengl.GL11.glVertex3d(x2, y1, z1);
        org.lwjgl.opengl.GL11.glVertex3d(x2, y1, z2);
        org.lwjgl.opengl.GL11.glVertex3d(x1, y1, z2);
        org.lwjgl.opengl.GL11.glVertex3d(x1, y2, z1);
        org.lwjgl.opengl.GL11.glVertex3d(x1, y2, z2);
        org.lwjgl.opengl.GL11.glVertex3d(x2, y2, z2);
        org.lwjgl.opengl.GL11.glVertex3d(x2, y2, z1);
    }

    private static void edges(double x1, double y1, double z1,
            double x2, double y2, double z2) {
        line(x1, y1, z1, x2, y1, z1);
        line(x2, y1, z1, x2, y1, z2);
        line(x2, y1, z2, x1, y1, z2);
        line(x1, y1, z2, x1, y1, z1);
        line(x1, y1, z1, x1, y2, z1);
        line(x2, y1, z1, x2, y2, z1);
        line(x2, y1, z2, x2, y2, z2);
        line(x1, y1, z2, x1, y2, z2);
        line(x1, y2, z1, x2, y2, z1);
        line(x2, y2, z1, x2, y2, z2);
        line(x2, y2, z2, x1, y2, z2);
        line(x1, y2, z2, x1, y2, z1);
    }

    private static void line(double x1, double y1, double z1,
            double x2, double y2, double z2) {
        org.lwjgl.opengl.GL11.glVertex3d(x1, y1, z1);
        org.lwjgl.opengl.GL11.glVertex3d(x2, y2, z2);
    }

    // ==================================================================
    // 数学
    // ==================================================================

    static int oppositeFace(int face) {
        return OPPOSITE_FACING[face];
    }

    static float rotateTowards(double maxStep, float current, float target) {
        float diff = wrapDegrees(target - current);
        float step = (float) maxStep;
        if (diff > step) {
            diff = step;
        }
        if (diff < -step) {
            diff = -step;
        }
        return current + diff;
    }

    static float clampAngle(float angle, float max) {
        if (Math.abs(angle) < max) {
            return angle;
        }
        return angle > 0.0F ? max : (angle < 0.0F ? -max : 0.0F);
    }

    static double angleDiffDouble(float angleA, float angleB) {
        double diff = angleA - angleB;
        return ((diff + 180.0D) % 360.0D + 360.0D) % 360.0D - 180.0D;
    }

    static float wrapDegrees(float angle) {
        return OpenMaverFeatures.wrapDegrees(angle);
    }

    static float clampPitch(float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    static int floor(double value) {
        int truncated = (int) value;
        return value < (double) truncated ? truncated - 1 : truncated;
    }

    private static float randomFloat(float max, float min) {
        return (float) (Math.random() * (max - min) + min);
    }

    private static double randomDouble(double min, double max) {
        return min + Math.random() * (max - min);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * flushPending 的决策表（回归测试直接调它）。
     *
     * <p>不变量：<b>没写进过 C03 的 aim 永远不能放置</b>；角度对不上就重试，
     * 但重试有上限，避免一个永远打不到的面把队列钉死。
     */
    static int pendingDecision(boolean sent, boolean rayHit, int retries) {
        if (!sent) {
            return PENDING_DROP;
        }
        if (rayHit) {
            return PENDING_PLACE;
        }
        return retries >= MAX_PENDING_RETRIES ? PENDING_DROP : PENDING_RETRY;
    }

    /** clutch 的“按住不放”条件（OpenZen onTick 里那个 if 的等价物）。 */
    static boolean clutchHold(boolean clutchEnabled, boolean canBuildNow, int velocityDelayTicks,
            int rotationDelayTicks) {
        return clutchEnabled && (!canBuildNow || velocityDelayTicks > 0)
                && rotationDelayTicks <= MAX_ROTATION_DELAY;
    }

    // ==================================================================
    // 配置读写
    // ==================================================================

    public static void load(Properties values) {
        mode = clamp(readInt(values, "scaffold.mode", MODE_NORMAL), 0, 3);
        eagle = readBoolean(values, "scaffold.eagle", true);
        sneak = readBoolean(values, "scaffold.sneak", true);
        snap = readBoolean(values, "scaffold.snap", true);
        clutch = readBoolean(values, "scaffold.clutch", true);
        rotationTick = clamp(readInt(values, "scaffold.rotationTick", 3), 1, 6);
        itemSpoof = readBoolean(values, "scaffold.itemSpoof", true);
        hud = readBoolean(values, "scaffold.hud", true);
        reset();
    }

    public static String save() {
        return "scaffold.mode=" + mode
                + "\nscaffold.eagle=" + eagle
                + "\nscaffold.sneak=" + sneak
                + "\nscaffold.snap=" + snap
                + "\nscaffold.clutch=" + clutch
                + "\nscaffold.rotationTick=" + rotationTick
                + "\nscaffold.itemSpoof=" + itemSpoof
                + "\nscaffold.hud=" + hud;
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
        mode = clamp(value, 0, 3);
        OpenMaverFeatures.markDirty();
    }

    public static boolean isEagle() {
        return eagle;
    }

    public static void setEagle(boolean value) {
        eagle = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isSneak() {
        return sneak;
    }

    public static void setSneak(boolean value) {
        sneak = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isSnap() {
        return snap;
    }

    public static void setSnap(boolean value) {
        snap = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isClutch() {
        return clutch;
    }

    public static void setClutch(boolean value) {
        clutch = value;
        OpenMaverFeatures.markDirty();
    }

    public static int getRotationTick() {
        return rotationTick;
    }

    public static void setRotationTick(int value) {
        rotationTick = clamp(value, 1, 6);
        OpenMaverFeatures.markDirty();
    }

    public static boolean isItemSpoof() {
        return itemSpoof;
    }

    public static void setItemSpoof(boolean value) {
        itemSpoof = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isHud() {
        return hud;
    }

    public static void setHud(boolean value) {
        hud = value;
        OpenMaverFeatures.markDirty();
    }
}
