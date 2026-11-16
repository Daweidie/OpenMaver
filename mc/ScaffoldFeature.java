import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.AxisAlignedBB;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scaffold（自动搭路 / 神桥）的完整行为移植。
 *
 * <p>本类逐项参考 {@code OpenMyau-Fix} 的 {@code Scaffold}：
 * 旋转模式（NONE / DEFAULT / BACKWARDS / SIDEWAYS / OFFSET / SNAP / HYPIXEL）、
 * MoveFix、Tower（VANILLA / EXTRA / HYPIXEL）、KeepY、Telly 旋转、
 * MultiPlace、SafeWalk、Sprint/Motion 控制、自动切方块、BlockCounter、输入接管。
 *
 * <p>OpenMaver 没有 Myau 的 Mixin / EventManager，因此通过 Forge ClientTickEvent
 * 的 START + {@code ScaffoldMovementInput}（在 vanilla updatePlayerMoveState
 * 之后回调）+ END 恢复实现 tick 内换视角；输入拦截通过压制
 * {@code leftClickCounter} / {@code rightClickDelayTimer} 近似，放置使用
 * {@code PlayerControllerMP.onPlayerRightClick} 直接发包。
 */
public final class ScaffoldFeature {

    // ==================================================================
    // Myau Scaffold 设置
    // ==================================================================

    /** NONE, DEFAULT, BACKWARDS, SIDEWAYS, OFFSET, SNAP, HYPIXEL */
    private static int rotations = 1;
    /** NONE, SILENT */
    private static int moveFix = 1;
    /** NONE, VANILLA */
    private static int sprintMode = 0;
    private static int groundMotion = 100;
    private static int airMotion = 100;
    private static int speedMotion = 100;
    private static boolean keepYonPress;
    private static boolean multiPlace = true;
    private static boolean safeWalk = true;
    private static boolean swing = true;
    private static boolean itemSpoof;
    private static boolean blockCounter = true;
    private static boolean candiffplace = true;
    private static int towerFlatTicks = 4;
    private static int angleDiff = 50;
    private static float tellyStartRotMinSpeed = 80.0F;
    private static float tellyStartRotMaxSpeed = 85.0F;
    private static float tellyNormalRotMinSpeed = 30.0F;
    private static float tellyNormalRotMaxSpeed = 35.0F;
    /** 1TICK, 2TICK, 3TICK, 4TICK, NORMAL */
    private static int rotationSpeed = 4;
    /** NONE, VANILLA, EXTRA, TELLY, HYPIXEL */
    private static int tower = 0;
    /** NONE, VANILLA, EXTRA, TELLY, EXTRATELLY */
    private static int keepY = 0;

    // ==================================================================
    // 运行状态（与 Myau 字段一一对应）
    // ==================================================================

    private static final double[] PLACE_OFFSETS = new double[]{
            0.03125, 0.09375, 0.15625, 0.21875, 0.28125, 0.34375, 0.40625, 0.46875,
            0.53125, 0.59375, 0.65625, 0.71875, 0.78125, 0.84375, 0.90625, 0.96875
    };
    /** FACING_DELTAS 的反向：target 在 support 的哪一侧，就点击 support 的这个面。 */
    private static final int[] OPPOSITE_FACING = {1, 0, 3, 2, 5, 4};
    /** 点击面采样点（相对坐标）。中心点 + 四个角落附近，足以避开遮挡。 */
    private static final double[] FACE_SAMPLE_MIN = {0.5D, 0.25D, 0.75D};
    private static final double[] FACE_SAMPLE_MAX = {0.5D, 0.75D, 0.25D};
    /** 一次放置后 aim 的保留 tick 数；避免无目标时服务端视角永远停在旧角度。 */
    private static int aimHoldTicks;

    private static int rotationTick;
    private static int lastSlot = -1;
    private static int activeSlot = -1;
    private static int blockCount = -1;
    /**
     * 静默瞄准角（silent aim）。
     *
     * <p>tick 内部只用于目标搜索/射线检测；真正的字段写入在
     * {@link ScaffoldMovementInput} 回调里（vanilla updatePlayerMoveState 之后），
     * 本 tick 的 C03 会带上它，ClientTickEvent.END 再恢复相机。
     * 不能直接在 START 阶段写 rotationYaw：START 在 movementInput 更新之前，
     * 写入会被原版覆盖，而且没有 hook 的话相机会跟着 aim 一起转。
     */
    private static float yaw = -180.0F;
    private static float pitch;
    /** 服务端最后收到的 aim（C03 实际带走的角度），F5 身体渲染也用它。 */
    private static float serverYaw = Float.NaN;
    private static float serverPitch = Float.NaN;
    /** 玩家/世界对象变化（重生、切维度）时服务端视角会被重置为真实视角。 */
    private static Object trackedPlayer;
    private static Object trackedWorld;
    private static boolean canRotate;
    private static int towerTick;
    private static int towerDelay;
    private static int stage;
    private static int startY = 256;
    private static boolean shouldKeepY;
    private static boolean towering;
    private static Object targetFacing;
    private static boolean canplace;
    private static boolean onairplace;
    private static boolean wasInAir;
    private static boolean isSnapping;
    private static int snapCooldown;
    private static int rotationZeroCount;
    private static boolean hasPlacedThisJump;
    private static int currentFlatTick;
    private static long lastPlacementTick = Long.MIN_VALUE;
    /** 最近一次成功放置的目标空气格；用于避免服务端方块变更回来之前重复发 C08。 */
    private static int lastPlacedTargetX;
    private static int lastPlacedTargetY;
    private static int lastPlacedTargetZ;
    private static long lastPlacedTargetTick = Long.MIN_VALUE;
    private static int debugTick;
    private static String lastTargetInfo = "?";

    private static boolean active;
    private static boolean failed;

    // ------------------------------------------------------------------
    // tick 内换视角（silent rotation via C03）
    //
    // START 阶段只计算 aim；真正的字段写入发生在 vanilla
    // updatePlayerMoveState() 之后（ScaffoldMovementInput 回调），因此本 tick
    // 的 C03 会带上 aim。ClientTickEvent.END 在渲染之前把 rotation 恢复成
    // 相机角度，所以第一人称视角不受影响；renderYawOffset/rotationYawHead
    // 则保留 aim，让 F5 的身体朝向和服务端一致。
    // ------------------------------------------------------------------
    /** 本 tick 的真实相机角度（写入 aim 前保存）。 */
    private static float cameraYaw;
    private static float cameraPitch;
    /** 本 tick 要写入 C03 的 aim。 */
    private static float tickAimYaw;
    private static float tickAimPitch;
    private static boolean aimReady;
    private static boolean aimApplied;
    private static boolean rotationDirty;
    /** onTickStart 正常执行才允许 hook 写 rotation；tickStartFailed 时不写。 */
    private static boolean hookArmed;
    private static long hookArmedNanos;
    private static Object aimPlayer;
    /** 一旦算出一个可放置的 aim，就一直把它保持到模块关闭，避免 C03 在
     *  aim 与相机之间来回跳（AimProcessor 会看到大角度抖动）。 */
    private static boolean hasAim;
    private static float lastAimYaw;
    private static float lastAimPitch;
    /** 上一 tick 选好的放置目标：服务端此时已经收到它的 aim。 */
    private static PendingPlace pendingPlace;
    /**
     * 本 tick 已经发出过一条 C08 —— 本 tick 的飞包**必须**携带同一个角度。
     *
     * <p>这是 Grim 1.8 的真实语义（{@code CheckManagerListener#handleQueuedPlaces}）：
     * C08 会先入队 {@code placeUseItemPackets}，等**下一条飞包**到达时才被派发给
     * {@code onPostFlyingBlockPlace}；而 1.8 客户端走的分支是无条件的
     *
     * <pre><code>if ((now - lastBlockPlaceUseItem &lt; 15
     *         || getClientVersion().isOlderThan(V_1_9)) &amp;&amp; hasLook) {
     *     player.yaw = yaw;      // ← 用的是「后一条飞包」的 rotation
     *     player.pitch = pitch;
     * }</code></pre>
     *
     * <p>同时 {@code player.x/y/z} 被设成 {@code lastClaimedPosition}（**C08 之前**
     * 那条飞包的位置，也就是发包时的当前位置）。于是 C08 的判定条件是：
     * <b>用当前眼睛 + 本 tick 飞包里的 rotation 做 ray trace</b>。
     *
     * <p>所以「先发 aim，下一 tick 再放」只对了一半：aim 必须**跟着同一条 C08**
     * 写进本 tick 的飞包，否则 Grim 用的是下一条飞包（下一个目标/相机）的 pitch。
     */
    private static boolean holdPlaceAim;
    private static float holdPlaceYaw;
    private static float holdPlacePitch;
    /**
     * 同一个目标最多重试几次。
     *
     * <p>每次重试都会让新角度先进一条 C03，所以「重试」的代价固定是 1 tick。
     * 上限存在的意义不是省 tick，而是防止一个永远打不到的面把 {@code pendingPlace}
     * 钉死、把后面 planPlacement 的新目标全部挡掉（那会表现为「站在原地不放方块」）。
     */
    static final int MAX_PENDING_RETRIES = 2;

    private static Class<?> blockPosType;
    private static Constructor<?> blockPosConstructor;
    private static Class<?> vec3Type;
    private static Constructor<?> vec3Constructor;
    private static Object[] facings;
    private static final String[] FACING_NAMES = {
            "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"
    };
    private static final int[][] FACING_DELTAS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };
    private static Class<?> itemBlockType;
    private static boolean itemBlockLookupFailed;
    private static Class<?> animationPacketType;
    private static Constructor<?> animationPacketConstructor;
    private static Object jumpPotion;
    private static Object speedPotion;
    private static boolean potionLookupFailed;
    /** 方块类型判定结果缓存：scaffold 每 tick 会扫描数百个方块，不能反复走继承链。 */
    private static final Map<Class<?>, Boolean> SOLID_CACHE =
            new ConcurrentHashMap<Class<?>, Boolean>();
    private static final Map<Class<?>, Boolean> INTERACTABLE_CACHE =
            new ConcurrentHashMap<Class<?>, Boolean>();

    private ScaffoldFeature() {
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 在 ClientTickEvent.START（vanilla 发送 C03 之前）调用。 */
    public static void onTickStart(Minecraft mc) {
        Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        if (player != null) {
            // 上一 tick 如果因为异常没能恢复，这里兜底，避免相机停在 aim。
            if (rotationDirty) {
                restoreCameraView(player);
            }
            cameraYaw = wrapPlayerYaw(player);
            cameraPitch = playerPitch(player);
            holdPlaceAim = false;
            aimPlayer = player;
            aimApplied = false;
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
                enable(mc);
            }
            if (player != null) {
                // 只在模块开启时替换 MovementInput，避免影响未开启时的原版输入。
                ensureMovementHook(mc, player);
            }
            if (rotations == 0) {
                // NONE：服务端视角必须保持真实相机，不能让上一次的 aim 残留。
                hasAim = false;
                aimReady = false;
            }
            tick(mc);
        } catch (Throwable error) {
            failed = true;
            active = false;
            resetState();
            System.err.println("[OpenMaver] Scaffold disabled after error: " + error);
        }
    }

    /**
     * ClientTickEvent.END（渲染之前）恢复第一人称视角。
     *
     * <p>C03 已经在 tick 中间发出，所以这里只影响客户端渲染：
     * rotationYaw/Pitch 回到相机角度；renderYawOffset/rotationYawHead
     * 保留服务端最后收到的 aim，F5 身体因此显示为“在搭路”。
     */
    public static void onTickEnd(Minecraft mc) {
        hookArmed = false;
        if (!rotationDirty) {
            aimApplied = false;
            return;
        }
        Object player = aimPlayer;
        if (player == null) {
            player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        }
        rotationDirty = false;
        aimApplied = false;
        if (player == null) {
            return;
        }
        restoreCameraView(player);
        // restoreCameraView 会把 render 字段也恢复成相机；这里再把
        // F5 的身体/头设回服务端最后收到的 aim。
        if (!Float.isNaN(serverYaw)) {
            setNumber(player, "renderYawOffset", "field_70761_aq", serverYaw);
            setNumber(player, "rotationYawHead", "field_70759_as", serverYaw);
        }
    }

    private static void enable(Minecraft mc) {
        active = true;
        Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        if (player != null) {
            lastSlot = currentHotbarSlot(player);
        } else {
            lastSlot = -1;
        }
        activeSlot = lastSlot;
        blockCount = -1;
        rotationTick = 0;
        yaw = -180.0F;
        pitch = 0.0F;
        canRotate = false;
        towerTick = 0;
        towerDelay = 0;
        towering = false;
        snapCooldown = 0;
        isSnapping = false;
        hasPlacedThisJump = false;
        failed = false;
        if (player != null) {
            onairplace = OpenMaverFeatures.scaffoldOnGround(player);
            // 开启时服务端视角就是玩家真实视角；之后的 aim 由正常 C03 携带。
            serverYaw = wrapPlayerYaw(player);
            serverPitch = playerPitch(player);
            trackedPlayer = player;
        } else {
            serverYaw = Float.NaN;
            serverPitch = Float.NaN;
            trackedPlayer = null;
        }
        trackedWorld = null;
        pendingPlace = null;
        aimReady = false;
        aimApplied = false;
        rotationDirty = false;
        hasAim = false;
        aimHoldTicks = 0;
        if (safeWalk) {
            OpenMaverDebug.noticeOnce("scaffold.safewalk.grim",
                    grimStrict()
                            ? "SafeWalk disabled while bypass is enabled (Simulation risk)"
                            : "SafeWalk clamps the final edge step; disable it for Grim testing "
                                    + "(otherwise Simulation/GroundSpoof may remain)");
        }
        if (tower != 0 || keepY != 0) {
            OpenMaverDebug.noticeOnce("scaffold.tower.grim",
                    "Tower/KeepY write motionY directly; disable for Grim testing");
        }
        lastAimYaw = 0.0F;
        lastAimPitch = 0.0F;
        rotationZeroCount = 0;
        currentFlatTick = 0;
        stage = 0;
        startY = 256;
        shouldKeepY = false;
        targetFacing = null;
    }

    private static void disable(Minecraft mc) {
        active = false;
        Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        if (player != null && lastSlot != -1) {
            setHotbarSlot(player, lastSlot);
        }
        // 不再补发 C05：停止写入 aim 后，下一条 C03 自然会把真实相机角度
        // 告诉服务端。这里只保证客户端当前帧/本 tick 不残留 aim。
        restoreCameraView(player);
        aimReady = false;
        hasAim = false;
        pendingPlace = null;
        onairplace = false;
        targetFacing = null;
        resetState();
    }

    public static void reset() {
        if (active) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
                restoreCameraView(player);
            }
        }
        active = false;
        aimReady = false;
        hasAim = false;
        pendingPlace = null;
        resetState();
    }

    private static void resetState() {
        rotationTick = 0;
        towerTick = 0;
        towerDelay = 0;
        currentFlatTick = 0;
        rotationZeroCount = 0;
        stage = 0;
        startY = 256;
        shouldKeepY = false;
        towering = false;
        canplace = false;
        targetFacing = null;
        blockCount = -1;
        lastPlacementTick = Long.MIN_VALUE;
        lastPlacedTargetTick = Long.MIN_VALUE;
        pendingPlace = null;
        holdPlaceAim = false;
        aimReady = false;
        aimApplied = false;
        hasAim = false;
        aimHoldTicks = 0;
        hookArmed = false;
    }

    // ==================================================================
    // 主 tick（对应 Myau onUpdate PRE + onStrafe + onMoveInput + onLivingUpdate）
    // ==================================================================

    private static void tick(Minecraft mc) {
        Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
        if (player == null || world == null || !OpenMaverFeatures.alive(player)
                || OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null) != null) {
            resetState();
            return;
        }

        // Myau 在 Scaffold 开启时取消左/右键和挖方块。这里用原版节流字段近似：
        // leftClickCounter > 0 会同时阻止 clickMouse / sendClickBlockToController；
        // rightClickDelayTimer > 0 会阻止 vanilla rightClickMouse，但不会影响本类
        // 直接调用 PlayerControllerMP.onPlayerRightClick。
        OpenMaverFeatures.writeIntField(mc, "leftClickCounter", "field_71429_W", 2);
        OpenMaverFeatures.writeIntField(mc, "rightClickDelayTimer", "field_71467_ac", 4);

        // 真实相机视角：START 时保存，本 tick 的 aim 写入发生在
        // updatePlayerMoveState() 之后，因此这里的值始终是鼠标视角。
        float eventYaw = cameraYaw;
        float eventPitch = cameraPitch;
        if (player != trackedPlayer || world != trackedWorld) {
            // 重生 / 切换维度后，服务端看到的是客户端上报的真实视角。
            trackedPlayer = player;
            trackedWorld = world;
            serverYaw = eventYaw;
            serverPitch = eventPitch;
            pendingPlace = null;
            hasAim = false;
            lastAimYaw = eventYaw;
            lastAimPitch = eventPitch;
        }

        boolean onGround = OpenMaverFeatures.scaffoldOnGround(player);
        if (onGround) {
            if (wasInAir && hasPlacedThisJump) {
                rotationZeroCount++;
                if (rotationZeroCount >= 2) {
                    currentFlatTick = towerFlatTicks;
                    rotationZeroCount = 0;
                }
            }
            hasPlacedThisJump = false;
            wasInAir = false;
        } else {
            wasInAir = true;
        }
        if (currentFlatTick > 0) {
            currentFlatTick--;
        }
        if (!onairplace && onGround) {
            onairplace = true;
        }
        if (rotationTick > 0) {
            rotationTick--;
        }
        if (snapCooldown > 0) {
            snapCooldown--;
        }
        if (onGround) {
            if (stage > 0) {
                stage--;
            }
            if (stage < 0) {
                stage++;
            }
            if (stage == 0 && keepY != 0
                    && (!keepYonPress || keyDown(mc, "keyBindUseItem"))
                    && !keyDown(mc, "keyBindJump")) {
                stage = 1;
            }
            startY = shouldKeepY ? startY : floor(OpenMaverFeatures.number(player, "posY", "field_70163_u"));
            shouldKeepY = false;
            towering = false;
        }

        // 自动切方块 + 方块计数。
        updateBlockSlot(player);
        debugTick++;
        if (blockCount <= 0) {
            // 没方块可放时不要把服务端视角继续留在上一次的 aim 上，
            // 否则切剑/吃东西时攻击会用到一个很怪的 rotation。
            hasAim = false;
            aimReady = false;
            if (OpenMaverDebug.isEnabled() && debugTick % 20 == 0) {
                OpenMaverDebug.trace("SCAFFOLD", "idle: blockCount=" + blockCount
                        + " slot=" + currentHotbarSlot(player)
                        + " held=" + heldItemName(player)
                        + " (hotbar 里没有可放置方块？)");
            }
            return;
        }

        // 先放置上一 tick 已经选好 aim 的目标。
        //
        // 时序（Grim 1.8 实测语义，见 holdPlaceAim）：C08 在本 tick 的开头发出去，
        // 紧接着本 tick 的飞包才带着 rotation 到达服务端，Grim 就是用**这条飞包**
        // 的 yaw/pitch 去 ray trace 的。所以 flush 一旦成功，本 tick 剩下的时间
        // 都必须把 rotation 固定在那个角度上 —— 不能再规划下一个目标（那会把
        // aim 换成另一个目标的 pitch，判定必然失败）。
        flushPendingPlace(mc, player, world);
        if (holdPlaceAim) {
            // 立刻把 hold 的角度挂到 tickAim 上：后面任何提前 return（例如刚放完
            // 最后一个方块）都不会让本 tick 的飞包退回相机角度 —— 那会让刚发出去
            // 的 C08 被 Grim 用相机 rotation 判定（RotationPlace）。
            applyHoldAim();
        }
        if (blockCount <= 0) {
            if (!holdPlaceAim) {
                hasAim = false;
                aimReady = false;
            }
            return;
        }

        float currentYaw = currentYaw(mc, player);
        float yawDiffTo180 = wrapAngleDiff(currentYaw - 180.0F, eventYaw);
        float diagonalYaw = isDiagonal(currentYaw)
                ? yawDiffTo180
                : wrapAngleDiff(currentYaw - 135.0F
                        * ((currentYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F), eventYaw);

        boolean useSnap = rotations == 5 && !isSnapDisabled(mc, player);
        int effectiveMode = useSnap ? 5 : (rotations == 5 ? 3 : rotations);

        updateRotation(mc, player, currentYaw, eventYaw, eventPitch, yawDiffTo180,
                diagonalYaw, useSnap, effectiveMode);

        // holdPlaceAim：本 tick 的飞包已经被刚发出的 C08 占用，不能再改 rotation。
        Placement placement = holdPlaceAim || pendingPlace != null || (useSnap && !isSnapping)
                ? null
                : planPlacement(mc, player, world, useSnap, effectiveMode,
                        eventYaw, eventPitch);
        AimHit hit = placement == null ? null : placement.hit;
        if (OpenMaverDebug.isEnabled() && debugTick % 20 == 0) {
            OpenMaverDebug.trace("SCAFFOLD", "tick: pos="
                    + format(OpenMaverFeatures.number(player, "posX", "field_70165_t")) + ","
                    + format(OpenMaverFeatures.number(player, "posY", "field_70163_u")) + ","
                    + format(OpenMaverFeatures.number(player, "posZ", "field_70161_v"))
                    + " ground=" + onGround + " blockCount=" + blockCount
                    + " target=" + lastTargetInfo
                    + " data=" + describePlacement(placement)
                    + " hit=" + (hit != null)
                    + " view=" + format(eventYaw) + "/" + format(eventPitch)
                    + " aim=" + (hit == null ? "null"
                            : format(hit.yaw) + "/" + format(hit.pitch))
                    + " serverView=" + format(serverYaw) + "/" + format(serverPitch));
        }

        boolean isTellyTowering = isTowering(mc, player) && tower == 3;
        if (canRotate && forwardPressed(mc) && eventYaw != 0.0F
                && Math.abs(OpenMaverFeatures.wrapDegrees(yawDiffTo180 - yaw)) < 90.0F) {
            if (!(isTellyTowering && effectiveMode == 3)) {
                if (effectiveMode == 2) {
                    yaw = quantize(yawDiffTo180);
                } else if (effectiveMode == 3) {
                    yaw = quantize(diagonalYaw);
                }
            }
        }

        if (effectiveMode != 0) {
            updateAim(mc, player, eventYaw, eventPitch, useSnap);
        }

        boolean canSnapPlace = true;
        if (useSnap) {
            boolean moving = Math.abs(OpenMaverFeatures.number(player, "motionX", "field_70159_w")) > 0.02
                    || Math.abs(OpenMaverFeatures.number(player, "motionZ", "field_70179_y")) > 0.02;
            boolean nearEdge = isNearEdge(mc, player, 1.0D);
            boolean runningToEdge = moving && (isAirBelow(mc, player) || nearEdge);
            if (isSnapping) {
                float absoluteYawDiff = Math.abs(OpenMaverFeatures.wrapDegrees(yaw - eventYaw));
                if (absoluteYawDiff > 45.0F && !runningToEdge) {
                    canSnapPlace = false;
                }
            } else {
                if (isAirBelow(mc, player)) {
                    isSnapping = true;
                    snapCooldown = 0;
                } else {
                    canSnapPlace = false;
                }
            }
        }

        if (placement != null && rotationTick <= 0 && canSnapPlace) {
            // 一律排队：C08 只能使用服务端已经在 C03 里收到的 aim。
            //
            // 以前 immediate（玩家已经踏空）会在 ClientTickEvent.START 里直接
            // place()，那是 pre-flying 形态：服务端此刻的 rotation 还是相机角度，
            // 一旦 flagBuffer>0，Grim 的 onBlockPlace 会 place.resync() 直接
            // 取消这次放置 —— 既掉下去又记账（RotationPlace: pre-flying）。
            // 排队只慢一 tick：本 tick 的 C03 带上 aim，下一 tick 开头再发 C08。
            queuePlacement(placement);
            if (useSnap) {
                snapCooldown = 1;
                isSnapping = false;
            }
            // MultiPlace 与“一 tick 一次放置”互斥：额外 C08 仍会触发
            // MultiPlace / Post / Timer，因此这里不再重复放置。
        }

        if (!holdPlaceAim && targetFacing != null && canSnapPlace) {
            if (rotationTick <= 0 || (rotationTick <= 1 && tower == 4 && canplace
                    && onairplace && candiffplace)) {
                int bx = floor(OpenMaverFeatures.number(player, "posX", "field_70165_t"));
                int by = floor(OpenMaverFeatures.number(player, "posY", "field_70163_u"));
                int bz = floor(OpenMaverFeatures.number(player, "posZ", "field_70161_v"));
                Object below = blockPos(bx, by - 1, bz);
                AimHit aim = aimAtBlock(mc, player, world, below, targetFacing,
                        aimBaseYaw(effectiveMode, eventYaw), aimBasePitch(effectiveMode, eventPitch));
                if (aim != null) {
                    queueSupportPlacement(below, targetFacing, aim);
                    if (useSnap) {
                        snapCooldown = 3;
                        isSnapping = false;
                    }
                }
            }
            targetFacing = null;
        } else if (!holdPlaceAim && (keepY == 2 || keepY == 4) && stage > 0
                && !onGround && canSnapPlace) {
            int nextBlockY = floor(OpenMaverFeatures.number(player, "posY", "field_70163_u")
                    + OpenMaverFeatures.number(player, "motionY", "field_70181_x"));
            if (nextBlockY <= startY && OpenMaverFeatures.number(player, "posY", "field_70163_u")
                    > (double) (startY + 1)) {
                shouldKeepY = true;
                Placement next = planPlacementAt(mc, player, world,
                        floor(OpenMaverFeatures.number(player, "posX", "field_70165_t")),
                        nextBlockY - 1,
                        floor(OpenMaverFeatures.number(player, "posZ", "field_70161_v")),
                        false, effectiveMode, eventYaw, eventPitch);
                if (next != null && (rotationTick <= 0
                        || (rotationTick <= 1 && tower == 4 && canplace
                                && onairplace && candiffplace))) {
                    queuePlacement(next);
                    if (useSnap) {
                        snapCooldown = 3;
                        isSnapping = false;
                    }
                }
            }
        }

        if (effectiveMode == 0) {
            // 真实准星模式：服务端视角就是相机，不需要任何 aim。
            hasAim = false;
        }
        if (holdPlaceAim) {
            // 本 tick 的 C08 已经用这个角度做过 ray trace：飞包必须逐位相同，
            // 不允许再加噪声（噪声已经在 flush 里加过一次、并且参与过校验）。
            applyHoldAim();
        } else if (hasAim && aimHoldTicks > 0) {
            // 只保留很短一段时间：避免没有新目标时服务端视角一直停在旧 aim，
            // 玩家转头后仍然朝很久以前的方向（看起来像“转了头却没反应”）。
            aimHoldTicks--;
            // 同样要加微噪声：连续几 tick 保持同一个 aim 时，包里的 rotation
            // 完全相等会被 Grim 记成 AimDuplicateLook。
            tickAimYaw = lastAimYaw + aimMicroNoise();
            tickAimPitch = lastAimPitch + aimMicroNoise();
            aimReady = true;
        } else {
            hasAim = false;
            aimReady = false;
        }

        applyTower(mc, player);
        applyMovement(mc, player);
    }

    // ==================================================================
    // 旋转（Myau onUpdate 中的 rotation 状态机）
    // ==================================================================

    private static void updateRotation(Minecraft mc, Object player, float currentYaw,
            float eventYaw, float eventPitch, float yawDiffTo180, float diagonalYaw,
            boolean useSnap, int effectiveMode) {
        if (useSnap) {
            Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
            int sy = floor(OpenMaverFeatures.number(player, "posY", "field_70163_u"));
            int ty = (stage != 0 && !shouldKeepY ? Math.min(sy, startY) : sy) - 1;
            int tx = floor(OpenMaverFeatures.number(player, "posX", "field_70165_t")
                    + OpenMaverFeatures.number(player, "motionX", "field_70159_w"));
            int tz = floor(OpenMaverFeatures.number(player, "posZ", "field_70161_v")
                    + OpenMaverFeatures.number(player, "motionZ", "field_70179_y"));
            boolean targetAir = isReplaceable(world, blockPos(tx, ty, tz));
            if (targetAir && snapCooldown == 0) {
                yaw = quantize(diagonalYaw);
                pitch = quantize(85.0F);
                canRotate = true;
                isSnapping = true;
            } else {
                yaw = quantize(eventYaw);
                pitch = quantize(eventPitch);
                canRotate = true;
                isSnapping = false;
            }
            return;
        }
        if (canRotate) {
            return;
        }
        switch (effectiveMode) {
            case 1:
                if (yaw == -180.0F && pitch == 0.0F) {
                    yaw = quantize(diagonalYaw);
                    pitch = quantize(85.0F);
                } else {
                    yaw = quantize(diagonalYaw);
                }
                break;
            case 2:
                if (yaw == -180.0F && pitch == 0.0F) {
                    yaw = quantize(yawDiffTo180);
                    pitch = quantize(85.0F);
                } else {
                    yaw = quantize(yawDiffTo180);
                }
                break;
            case 3:
                if (yaw == -180.0F && pitch == 0.0F) {
                    yaw = quantize(diagonalYaw);
                    pitch = quantize(85.0F);
                } else {
                    yaw = quantize(diagonalYaw);
                }
                break;
            case 4:
                float roundedYaw = Math.round(currentYaw / 45.0F) * 45.0F;
                yaw = quantize(roundedYaw);
                if (pitch == 0.0F || !canRotate) {
                    pitch = quantize(79.3F);
                }
                break;
            case 5:
                if (yaw == -180.0F && pitch == 0.0F) {
                    yaw = quantize(diagonalYaw);
                    pitch = quantize(85.0F);
                } else {
                    float targetYaw = isDiagonal(currentYaw) ? diagonalYaw : yawDiffTo180;
                    float yawDelta = OpenMaverFeatures.wrapDegrees(targetYaw - yaw);
                    float pitchDelta = OpenMaverFeatures.wrapDegrees(85.0F - pitch);
                    float yawTolerance = rotationTick >= 2
                            ? randomFloat(tellyStartRotMinSpeed, tellyStartRotMaxSpeed)
                            : randomFloat(tellyNormalRotMinSpeed, tellyNormalRotMaxSpeed);
                    float pitchTolerance = rotationTick >= 2
                            ? randomFloat(tellyStartRotMinSpeed, tellyStartRotMaxSpeed)
                            : randomFloat(tellyNormalRotMinSpeed, tellyNormalRotMaxSpeed);
                    yaw = quantize(yaw + clampAngle(yawDelta, yawTolerance));
                    pitch = quantize(pitch + clampAngle(pitchDelta, pitchTolerance));
                }
                break;
            default:
                break;
        }
    }

    /**
     * 更新静默瞄准的目标角（Myau 的 rotation 状态机）。
     *
     * <p>本方法只更新内部 aim 状态，不直接写 player.rotationYaw/rotationPitch：
     * START 阶段写视角会被原版 movementInput 更新覆盖，也会让相机跟着转。
     * 真正用于放置的精确角度由 findBestHit()/aimAtBlock() 产出，随后交给
     * ScaffoldMovementInput 在玩家输入更新之后写进本 tick 的 C03。
     */
    private static void updateAim(Minecraft mc, Object player, float eventYaw,
            float eventPitch, boolean useSnap) {
        boolean keepYActive = keepY != 0 && (stage > 0 || shouldKeepY);
        float targetYaw = yaw;
        float targetPitch = pitch;

        if (towering && !useSnap
                && (OpenMaverFeatures.number(player, "motionY", "field_70181_x") > 0.0D
                        || OpenMaverFeatures.number(player, "posY", "field_70163_u")
                                > (double) (startY + 1))) {
            float yawDiff = OpenMaverFeatures.wrapDegrees(yaw - eventYaw);
            float tolerance = rotationTick >= 2
                    ? randomFloat(tellyStartRotMinSpeed, tellyStartRotMaxSpeed)
                    : randomFloat(tellyNormalRotMinSpeed, tellyNormalRotMaxSpeed);
            if (Math.abs(yawDiff) > tolerance) {
                float clampedYaw = clampAngle(yawDiff, tolerance);
                targetYaw = quantize(eventYaw + clampedYaw);
                if (!keepYActive && !useSnap
                        && (OpenMaverFeatures.number(player, "motionY", "field_70181_x") > 0.0D
                                || OpenMaverFeatures.number(player, "posY", "field_70163_u")
                                        > (double) (startY + 1))
                        && candiffplace) {
                    rotationTick = 0;
                } else {
                    rotationTick = 1;
                }
            }
        }

        if (useSnap) {
            float diffYaw = OpenMaverFeatures.wrapDegrees(targetYaw - eventYaw);
            float diffPitch = targetPitch - eventPitch;
            float maxSnapDelta = 100.0F;
            if (Math.abs(diffYaw) > maxSnapDelta) {
                targetYaw = eventYaw + copySign(maxSnapDelta, diffYaw);
            }
            if (Math.abs(diffPitch) > maxSnapDelta) {
                targetPitch = eventPitch + copySign(maxSnapDelta, diffPitch);
            }
        } else if (rotationSpeed < 4) {
            int speedTicks = rotationSpeed + 1;
            float diffYaw = OpenMaverFeatures.wrapDegrees(targetYaw - eventYaw);
            float diffPitch = targetPitch - eventPitch;
            float maxYawDelta = 360.0F / speedTicks;
            float maxPitchDelta = 360.0F / speedTicks;
            if (Math.abs(diffYaw) > maxYawDelta) {
                targetYaw = eventYaw + copySign(maxYawDelta, diffYaw);
            }
            if (Math.abs(diffPitch) > maxPitchDelta) {
                targetPitch = eventPitch + copySign(maxPitchDelta, diffPitch);
            }
        }

        if (isTowering(mc, player) && !useSnap) {
            float optimalYaw = yaw;
            float optimalPitch = pitch;
            float yawDelta = OpenMaverFeatures.wrapDegrees(
                    (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z") - eventYaw);
            targetYaw = quantize(eventYaw + yawDelta * randomFloat(0.98F, 0.99F));
            targetPitch = quantize(randomFloat(25.0F, 70.0F));
            towering = true;
            if (tower == 4) {
                double dot = dotOfDirections(optimalYaw, optimalPitch, targetYaw, targetPitch);
                double angleDiffDeg = Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
                canplace = angleDiffDeg < angleDiff;
                if (canplace) {
                    rotationTick = 3;
                }
            }
        }

        // 归一化后写回内部 aim 状态（不碰相机）。之前这里写 player.rotationYaw，
        // 是“数值累积到 993° / 相机持续转圈”的直接原因。
        yaw = OpenMaverFeatures.wrapDegrees(targetYaw);
        pitch = Math.max(-90.0F, Math.min(90.0F, targetPitch));
        // 注意：不要在这里把 canRotate 设为 true。Myau 只有 SNAP / findBestHit
        // 命中时才置位；常驻 true 会让 rotation 状态机再也不更新目标角。
    }

    // ==================================================================
    // 静默瞄准 / 服务端视角同步
    // ==================================================================

    private static float wrapPlayerYaw(Object player) {
        return OpenMaverFeatures.wrapDegrees(
                (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z"));
    }

    private static float playerPitch(Object player) {
        return Math.max(-90.0F, Math.min(90.0F,
                (float) OpenMaverFeatures.number(player, "rotationPitch", "field_70125_A")));
    }

    // 旧实现通过额外 C05 让服务端提前看到 aim；Grim 会把它算成一个额外
    // tick（Timer），并让随后的 C08 变成 post-flying（Post）。现在 aim 由
    // 本 tick 正常的 C03 携带，放置延后一 tick（见 flushPendingPlace），
    // 因此不再需要 trackServerView / ensureServerLook / restoreServerLook。

    /** NONE 模式用真实视角，其余模式用静默 aim 状态作为放置基点。 */
    private static float aimBaseYaw(int effectiveMode, float eventYaw) {
        return effectiveMode == 0 ? eventYaw : yaw;
    }

    private static float aimBasePitch(int effectiveMode, float eventPitch) {
        return effectiveMode == 0 ? eventPitch : pitch;
    }

    // ==================================================================
    // 目标搜索 / 放置（Myau getBlockData / place）
    // ==================================================================

    // ==================================================================
    // 放置目标规划
    //
    // Myau 直接按“离目标格最近的实心方块 + 最近的可替换邻居”选面，但那个面
    // 可能被玩家脚下的方块自己遮住（例如平桥上想放前方一格时，会去点脚下
    // 方块的侧面，从上方射线先撞到顶面）。这里改成：
    //   1. 支撑方块必须与目标空气格直接相邻（六个邻居之一）；
    //   2. 每个候选面都用真实 rayTrace 验证，不可见就换下一个；
    //   3. 无论玩家是否已经站在空气上，都提前一 tick 用“预测位置”瞄准，
    //      下一 tick 再 flush；C08 永远只用服务端已收到的 aim，不需要额外 C05。
    // ==================================================================

    private static Placement planPlacement(Minecraft mc, Object player, Object world,
            boolean useSnap, int effectiveMode, float eventYaw, float eventPitch) {
        if (world == null) {
            return null;
        }
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double py = OpenMaverFeatures.number(player, "posY", "field_70163_u");
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        double eye = OpenMaverFeatures.eyeHeight(player);
        int sy = floor(py);
        int ty = (stage != 0 && !shouldKeepY ? Math.min(sy, startY) : sy) - 1;
        int bx = floor(px);
        int bz = floor(pz);

        // 预测下一 tick 会进入的方块。aim 先按预测位置算好，下一 tick 的
        // flush 就能用同一个角度命中；这样不靠额外 C05 也能跟上移动速度。
        double motionX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
        double motionZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
        double motionSpeed = Math.hypot(motionX, motionZ);
        boolean inputHeld = forwardPressed(mc);

        double inputStepX = 0.0D;
        double inputStepZ = 0.0D;
        if (inputHeld) {
            float moveYaw = adjustYaw(wrapPlayerYaw(player), forwardValue(mc), leftValue(mc));
            double dirX = -Math.sin(Math.toRadians(moveYaw));
            double dirZ = Math.cos(Math.toRadians(moveYaw));
            double step = Math.max(motionSpeed, 0.12D);
            step = Math.min(step, 0.65D);
            if (keyDown(mc, "keyBindSneak")) {
                step = Math.min(step, 0.12D);
            }
            inputStepX = dirX * step;
            inputStepZ = dirZ * step;
        }

        // 用“当前实际速度”预测下一 tick 落点，而不是按键方向。
        // 快速转头时输入方向已经变了，但这一 tick 的位移仍主要由惯性决定；
        // 用按键方向会把目标格算到错误的一侧，下一 tick flush 时角度对不上，
        // bypass 模式会直接丢弃这次放置，表现就是“在边缘转头不放方块”。
        // 惯性很小（刚起步/站定）时才退回按键方向。
        double stepX;
        double stepZ;
        if (motionSpeed > 0.02D) {
            stepX = motionX;
            stepZ = motionZ;
        } else {
            stepX = inputStepX;
            stepZ = inputStepZ;
        }
        int ax = floor(px + stepX);
        int az = floor(pz + stepZ);
        int ix = floor(px + inputStepX);
        int iz = floor(pz + inputStepZ);

        boolean currentAir = isReplaceable(world, blockPos(bx, ty, bz));
        boolean aheadDifferent = ax != bx || az != bz;
        boolean aheadAir = aheadDifferent && isReplaceable(world, blockPos(ax, ty, az));
        // 快速转身时惯性方向和按键方向可能落在两个不同的方块上：两个都作为候选，
        // 哪个能真实命中就先放哪个。等速度方向稳定后会自然收敛到按键方向。
        boolean inputDifferent = inputHeld
                && (ix != ax || iz != az) && (ix != bx || iz != bz);
        boolean inputAir = inputDifferent && isReplaceable(world, blockPos(ix, ty, iz));

        // 静止/低速时在方块边缘转头：没有 WASD 预测就完全不会产生目标，
        // 这里按真实相机方向找前方一格。只在确实靠近该方向的边缘时启用，
        // 避免站在平台中间环顾四周就不停铺路。
        int lx = bx;
        int lz = bz;
        boolean lookAir = false;
        if (!currentAir && !aheadAir && !inputAir && motionSpeed <= 0.02D) {
            double lookX = -Math.sin(Math.toRadians(eventYaw));
            double lookZ = Math.cos(Math.toRadians(eventYaw));
            double edge = edgeDistance(px, pz, lookX, lookZ);
            if (edge <= 0.55D) {
                double lookStep = Math.max(edge + 0.15D, 0.6D);
                lx = floor(px + lookX * lookStep);
                lz = floor(pz + lookZ * lookStep);
                lookAir = (lx != bx || lz != bz)
                        && isReplaceable(world, blockPos(lx, ty, lz));
            }
        }

        lastTargetInfo = bx + "," + ty + "," + bz + " replaceable=" + currentAir
                + (aheadDifferent
                        ? " next=" + ax + "," + ty + "," + az + " replaceable=" + aheadAir
                        : "")
                + (inputDifferent
                        ? " input=" + ix + "," + ty + "," + iz + " replaceable=" + inputAir
                        : "")
                + (lookAir ? " look=" + lx + "," + ty + "," + lz : "");
        if (!currentAir && !aheadAir && !inputAir && !lookAir) {
            return null;
        }

        if (currentAir) {
            Placement urgent = findPlacementForTarget(mc, player, world, bx, ty, bz,
                    px, py + eye, pz, false, effectiveMode, eventYaw, eventPitch);
            if (urgent != null) {
                return urgent;
            }
            // 玩家可能刚好站在边缘：当前眼睛还在支撑方块上方，侧面被顶面挡住。
            // 用“预测位置”再试一次，保证这一步不会因为遮挡而直接掉下去。
            if (stepX != 0.0D || stepZ != 0.0D) {
                urgent = findPlacementForTarget(mc, player, world, bx, ty, bz,
                        px + stepX, py + eye, pz + stepZ, false,
                        effectiveMode, eventYaw, eventPitch);
                if (urgent != null) {
                    urgent.immediate = true;
                    return urgent;
                }
            }
        }
        if (aheadAir) {
            Placement predicted = findPlacementForTarget(mc, player, world, ax, ty, az,
                    px + stepX, py + eye, pz + stepZ, true,
                    effectiveMode, eventYaw, eventPitch);
            if (predicted != null) {
                if (currentAir) {
                    // 已经踏空：标记为紧急（仍然要排队一 tick，见 Placement#immediate）。
                    predicted.immediate = true;
                }
                return predicted;
            }
        }
        if (inputAir) {
            Placement predicted = findPlacementForTarget(mc, player, world, ix, ty, iz,
                    px + inputStepX, py + eye, pz + inputStepZ, true,
                    effectiveMode, eventYaw, eventPitch);
            if (predicted != null) {
                if (currentAir) {
                    predicted.immediate = true;
                }
                return predicted;
            }
        }
        if (lookAir) {
            // 玩家基本没动：下一 tick 眼睛还在同一位置，直接用当前眼睛瞄准。
            // 仍然排队一拍，让 aim 先进入本 tick 的 C03，服务端下一 tick
            // 才能用同一个角度校验 C08。
            Placement look = findPlacementForTarget(mc, player, world, lx, ty, lz,
                    px, py + eye, pz, true, effectiveMode, eventYaw, eventPitch);
            if (look != null) {
                return look;
            }
        }
        return null;
    }

    /** keepY / tower 等特殊路径的指定目标格。 */
    private static Placement planPlacementAt(Minecraft mc, Object player, Object world,
            int tx, int ty, int tz, boolean predicted, int effectiveMode,
            float eventYaw, float eventPitch) {
        if (!isReplaceable(world, blockPos(tx, ty, tz))) {
            return null;
        }
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double py = OpenMaverFeatures.number(player, "posY", "field_70163_u")
                + OpenMaverFeatures.eyeHeight(player);
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        return findPlacementForTarget(mc, player, world, tx, ty, tz, px, py, pz,
                predicted, effectiveMode, eventYaw, eventPitch);
    }

    private static Placement findPlacementForTarget(Minecraft mc, Object player, Object world,
            int tx, int ty, int tz, double eyeX, double eyeY, double eyeZ,
            boolean predicted, int effectiveMode, float eventYaw, float eventPitch) {
        Object targetPos = blockPos(tx, ty, tz);
        if (!isReplaceable(world, targetPos) || isRecentlyPlaced(tx, ty, tz)) {
            return null;
        }
        double reach = blockReachDistance(mc, player);
        ArrayList<SupportFace> faces = new ArrayList<SupportFace>(5);
        for (int d = 0; d < 6; d++) {
            if (d == 1) {
                // 目标格上方是支撑方块时只能点底面；普通搭路不需要，跳过。
                continue;
            }
            Object support = blockPos(tx + FACING_DELTAS[d][0],
                    ty + FACING_DELTAS[d][1], tz + FACING_DELTAS[d][2]);
            if (isReplaceable(world, support) || isInteractable(world, support)) {
                continue;
            }
            double distance = distanceSqToCenter(support, eyeX, eyeY, eyeZ);
            if (distance > (reach + 1.0D) * (reach + 1.0D)) {
                continue;
            }
            faces.add(new SupportFace(support, OPPOSITE_FACING[d], distance));
        }
        if (faces.isEmpty()) {
            return null;
        }
        faces.sort(new Comparator<SupportFace>() {
            public int compare(SupportFace a, SupportFace b) {
                return Double.compare(a.distanceSq, b.distanceSq);
            }
        });
        for (SupportFace face : faces) {
            AimHit hit = aimAtFace(mc, world, face.supportPos, face.faceIndex,
                    eyeX, eyeY, eyeZ, reach, effectiveMode, eventYaw, eventPitch);
            if (hit == null) {
                continue;
            }
            Object facingObj = facing(face.faceIndex);
            if (facingObj == null) {
                continue;
            }
            return new Placement(targetPos, face.supportPos, facingObj, hit, predicted);
        }
        return null;
    }

    /**
     * 找一个能真实命中 {@code supportPos} 指定面的角度。
     *
     * <p>NONE 模式只接受真实相机角度；其它模式优先瞄准面的正中心（对预测位置
     * 误差的容错最大），中心不可见时再试当前内部 aim 和四角采样。这样下一 tick
     * flush 时不会因为擦边角度先撞到支撑方块顶面而丢放置。
     */
    private static AimHit aimAtFace(Minecraft mc, Object world, Object supportPos, int faceIndex,
            double eyeX, double eyeY, double eyeZ, double reach, int effectiveMode,
            float eventYaw, float eventPitch) {
        if (effectiveMode == 0) {
            return castAtFace(world, eyeX, eyeY, eyeZ, eventYaw, eventPitch, reach,
                    supportPos, faceIndex);
        }
        // 优先瞄面的正中心。预测位置和下一 tick 的真实眼睛位置之间必然有
        // 一点误差；贴着面边缘的“擦边角”在这一 tick 能命中，下一 tick flush
        // 时却可能先撞到支撑方块顶面，bypass 模式会直接丢弃放置。
        // 中心点的角度容错最大，也是转头时不再丢方块的关键。
        double[] center = facePoint(supportPos, faceIndex, 0.5D, 0.5D);
        if (center != null) {
            float[] centerRots = rotationsTo(center[0] - eyeX, center[1] - eyeY,
                    center[2] - eyeZ, eventYaw, eventPitch);
            AimHit centerHit = castAtFace(world, eyeX, eyeY, eyeZ, centerRots[0],
                    centerRots[1], reach, supportPos, faceIndex);
            if (centerHit != null) {
                return centerHit;
            }
        }
        AimHit current = castAtFace(world, eyeX, eyeY, eyeZ, yaw, pitch, reach,
                supportPos, faceIndex);
        if (current != null) {
            return current;
        }
        int samples = Math.min(FACE_SAMPLE_MIN.length, FACE_SAMPLE_MAX.length);
        for (int a = 0; a < samples; a++) {
            for (int b = 0; b < samples; b++) {
                double[] point = facePoint(supportPos, faceIndex,
                        FACE_SAMPLE_MIN[a], FACE_SAMPLE_MAX[b]);
                if (point == null) {
                    continue;
                }
                float[] rots = rotationsTo(point[0] - eyeX, point[1] - eyeY,
                        point[2] - eyeZ, eventYaw, eventPitch);
                AimHit hit = castAtFace(world, eyeX, eyeY, eyeZ, rots[0], rots[1], reach,
                        supportPos, faceIndex);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static AimHit castAtFace(Object world, double eyeX, double eyeY, double eyeZ,
            float yawValue, float pitchValue, double reach, Object supportPos, int faceIndex) {
        Object facingObj = facing(faceIndex);
        if (facingObj == null) {
            return null;
        }
        Object mop = rayTraceFrom(world, eyeX, eyeY, eyeZ, yawValue, pitchValue, reach);
        if (!mopMatches(mop, supportPos, facingObj)) {
            return null;
        }
        Object vec = OpenMaverFeatures.read(mop, "hitVec", "field_72307_f", null);
        return vec == null ? null : new AimHit(vec, yawValue, pitchValue);
    }

    private static double[] facePoint(Object supportPos, int faceIndex, double u, double v) {
        double x = blockPosX(supportPos);
        double y = blockPosY(supportPos);
        double z = blockPosZ(supportPos);
        switch (faceIndex) {
            case 0: return new double[]{x + u, y, z + v}; // DOWN
            case 1: return new double[]{x + u, y + 1.0D, z + v}; // UP
            case 2: return new double[]{x + u, y + v, z}; // NORTH
            case 3: return new double[]{x + u, y + v, z + 1.0D}; // SOUTH
            case 4: return new double[]{x, y + v, z + u}; // WEST
            case 5: return new double[]{x + 1.0D, y + v, z + u}; // EAST
            default: return null;
        }
    }

    private static BlockData getBlockData(Minecraft mc, Object player, Object world, boolean useSnap) {
        if (world == null) {
            return null;
        }
        int sy = floor(OpenMaverFeatures.number(player, "posY", "field_70163_u"));
        int targetY = (stage != 0 && !shouldKeepY ? Math.min(sy, startY) : sy) - 1;
        int targetX;
        int targetZ;
        double posX = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double posZ = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        if (useSnap) {
            targetX = floor(posX + OpenMaverFeatures.number(player, "motionX", "field_70159_w"));
            targetZ = floor(posZ + OpenMaverFeatures.number(player, "motionZ", "field_70179_y"));
        } else {
            targetX = floor(posX);
            targetZ = floor(posZ);
            // 向前预判一格：只在“前方脚下是空气、当前脚下是实心”的踏空窗口里
            // 提前把前方的方块补上，避免走到边缘那 1~2 tick 来不及放。
            // 平地时前方脚下是实心，targetPos 不可替换，自然回退到脚下列。
            if (OpenMaverFeatures.scaffoldOnGround(player) && forwardPressed(mc)) {
                // 用当前按键 + 真实视角算方向（movementInput 要到本 tick 玩家更新时
                // 才会被 vanilla 重写，会晚 1 tick）。
                float moveYaw = adjustYaw(wrapPlayerYaw(player), forwardValue(mc), leftValue(mc));
                double dirX = -Math.sin(Math.toRadians(moveYaw));
                double dirZ = Math.cos(Math.toRadians(moveYaw));
                int aheadX = floor(posX + dirX * 0.55D);
                int aheadZ = floor(posZ + dirZ * 0.55D);
                if (aheadX != targetX || aheadZ != targetZ) {
                    Object aheadPos = blockPos(aheadX, targetY, aheadZ);
                    if (aheadPos != null && isReplaceable(world, aheadPos)) {
                        targetX = aheadX;
                        targetZ = aheadZ;
                    }
                }
            }
        }
        Object targetPos = blockPos(targetX, targetY, targetZ);
        boolean targetReplaceable = isReplaceable(world, targetPos);
        lastTargetInfo = targetX + "," + targetY + "," + targetZ
                + " replaceable=" + targetReplaceable;
        if (!targetReplaceable) {
            return null;
        }

        double reach = blockReachDistance(mc, player);
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double py = OpenMaverFeatures.number(player, "posY", "field_70163_u");
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");

        ArrayList<Object> positions = new ArrayList<Object>();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 0; y++) {
                for (int z = -4; z <= 4; z++) {
                    Object pos = blockPos(targetX + x, targetY + y, targetZ + z);
                    if (isReplaceable(world, pos) || isInteractable(world, pos)) {
                        continue;
                    }
                    if (distanceSqToCenter(pos, px, py, pz) > reach * reach) {
                        continue;
                    }
                    if (stage != 0 && !shouldKeepY
                            && blockPosY(pos) >= startY) {
                        continue;
                    }
                    for (int i = 0; i < 6; i++) {
                        if (i == 0) {
                            continue; // DOWN
                        }
                        Object adjacent = offset(pos, i);
                        if (isReplaceable(world, adjacent)) {
                            positions.add(pos);
                            break;
                        }
                    }
                }
            }
        }
        if (positions.isEmpty()) {
            return null;
        }
        final double tx = targetX + 0.5D;
        final double ty = targetY + 0.5D;
        final double tz = targetZ + 0.5D;
        positions.sort(new Comparator<Object>() {
            public int compare(Object a, Object b) {
                return Double.compare(
                        distanceSqToCenter(a, tx, ty, tz),
                        distanceSqToCenter(b, tx, ty, tz));
            }
        });
        Object best = positions.get(0);
        Object facing = getBestFacing(best, targetPos);
        return facing == null ? null : new BlockData(best, facing);
    }

    private static Object getBestFacing(Object supportPos, Object targetPos) {
        double offset = 0.0D;
        Object best = null;
        int targetY = blockPosY(targetPos);
        for (int i = 0; i < 6; i++) {
            if (i == 0) {
                continue; // DOWN
            }
            Object adjacent = offset(supportPos, i);
            if (blockPosY(adjacent) > targetY) {
                continue;
            }
            double distance = distanceSqToCenter(adjacent,
                    blockPosX(targetPos) + 0.5D,
                    blockPosY(targetPos) + 0.5D,
                    blockPosZ(targetPos) + 0.5D);
            if (best == null || distance < offset
                    || (distance == offset && i == 1)) {
                offset = distance;
                best = facing(i);
            }
        }
        return best;
    }

    /**
     * 在目标格周围 16×16（单面 N×M）采点，用 rayTrace 找出真正能命中
     * {@code data.blockPos / data.facing} 的角度。
     *
     * @return 精确命中角与 hitVec；这个角度会写进本 tick 的 C03，并在下一
     *         tick 用同一角度放置，否则 RotationPlace 的射线校验会失败。
     */
    private static AimHit findBestHit(Minecraft mc, Object player, Object world,
            BlockData data, float eventYaw, float eventPitch) {
        double[] xs = PLACE_OFFSETS;
        double[] ys = PLACE_OFFSETS;
        double[] zs = PLACE_OFFSETS;
        // 注意：1.8.9 的 EnumFacing.toString() 返回小写名（"north"/"east"...），
        // 不能用 String.valueOf(facing) 和 "NORTH" 比较，否则 6 个分支全部失配，
        // 采样会退化成 16×16×16 的整块体积，射线先打到支撑方块顶面，
        // 命中面校验失败 -> 永远放不下去。
        int faceIndex = facingIndex(data.facing);
        switch (faceIndex) {
            case 2: zs = new double[]{0.0D}; break; // NORTH
            case 5: xs = new double[]{1.0D}; break; // EAST
            case 3: zs = new double[]{1.0D}; break; // SOUTH
            case 4: xs = new double[]{0.0D}; break; // WEST
            case 0: ys = new double[]{0.0D}; break; // DOWN
            case 1: ys = new double[]{1.0D}; break; // UP
            default: break;
        }

        float bestYaw = -180.0F;
        float bestPitch = 0.0F;
        float bestDiff = 0.0F;
        Object bestHit = null;
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double py = OpenMaverFeatures.number(player, "posY", "field_70163_u")
                + OpenMaverFeatures.eyeHeight(player);
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        double reach = blockReachDistance(mc, player);
        float baseYaw = wrapAngleDiff(yaw, eventYaw);
        int bx = blockPosX(data.blockPos);
        int by = blockPosY(data.blockPos);
        int bz = blockPosZ(data.blockPos);
        int sampleMops = 0;
        String firstMop = null;
        String firstRot = null;

        for (int xi = 0; xi < xs.length; xi++) {
            for (int yi = 0; yi < ys.length; yi++) {
                for (int zi = 0; zi < zs.length; zi++) {
                    double relX = bx + xs[xi] - px;
                    double relY = by + ys[yi] - py;
                    double relZ = bz + zs[zi] - pz;
                    float[] rots = rotationsTo(relX, relY, relZ, baseYaw, pitch);
                    Object mop = rayTrace(mc, player, world, rots[0], rots[1], reach);
                    if (mop != null) {
                        sampleMops++;
                        if (firstMop == null) {
                            firstMop = describeMop(mop);
                            firstRot = format(rots[0]) + "/" + format(rots[1]);
                        }
                    }
                    if (mop != null && mopMatches(mop, data.blockPos, data.facing)) {
                        // 放置会延迟一 tick；优先选靠近面中心的采样点，给玩家
                        // 移动 0.2~0.3 格后的同一角度留出命中余量。
                        float totalDiff = Math.abs(rots[0] - baseYaw)
                                + Math.abs(rots[1] - pitch)
                                + (float) (faceCenterDistance(
                                        faceIndex, xs[xi], ys[yi], zs[zi]) * 2.0D);
                        if ((bestYaw == -180.0F && bestPitch == 0.0F) || totalDiff < bestDiff) {
                            bestYaw = rots[0];
                            bestPitch = rots[1];
                            bestDiff = totalDiff;
                            bestHit = OpenMaverFeatures.read(mop, "hitVec", "field_72307_f", null);
                        }
                    }
                }
            }
        }
        if (OpenMaverDebug.isEnabled() && debugTick % 20 == 0) {
            OpenMaverDebug.trace("SCAFFOLD", "ray: want=" + describeBlockData(data)
                    + " samples=" + (xs.length * ys.length * zs.length)
                    + " mops=" + sampleMops
                    + " firstMop=" + (firstMop == null ? "null" : firstMop)
                    + " firstRot=" + (firstRot == null ? "null" : firstRot));
        }
        if (bestYaw != -180.0F || bestPitch != 0.0F) {
            yaw = OpenMaverFeatures.wrapDegrees(bestYaw);
            pitch = Math.max(-90.0F, Math.min(90.0F, bestPitch));
            canRotate = true;
        }
        if (bestHit == null) {
            return null;
        }
        return new AimHit(bestHit, OpenMaverFeatures.wrapDegrees(bestYaw),
                Math.max(-90.0F, Math.min(90.0F, bestPitch)));
    }

    /**
     * 先尝试用 baseYaw/basePitch 直接命中；不行再算一个到点击点的角度并复核。
     * 两条路径都经过 rayTrace + 面校验，返回的角度保证能命中目标面。
     */
    /**
     * 只验证给定角度是否命中目标面，不尝试别的角度。
     *
     * <p>延迟放置时服务端只认上一 tick 已经收到的 aim；如果这个角度现在
     * 已经打不到目标，就不能偷偷换一个角度发 C08，否则 RotationPlace 会失败。
     */
    private static AimHit rayAtBlock(Minecraft mc, Object player, Object world,
            Object blockPos, Object facing, float aimYaw, float aimPitch) {
        if (blockPos == null || facing == null) {
            return null;
        }
        double reach = blockReachDistance(mc, player);
        Object mop = rayTrace(mc, player, world, aimYaw, aimPitch, reach);
        if (mop == null || !mopMatches(mop, blockPos, facing)) {
            return null;
        }
        Object vec = OpenMaverFeatures.read(mop, "hitVec", "field_72307_f", null);
        return vec == null ? null : new AimHit(vec, aimYaw, aimPitch);
    }

    private static AimHit aimAtBlock(Minecraft mc, Object player, Object world,
            Object blockPos, Object facing, float baseYaw, float basePitch) {
        if (blockPos == null || facing == null) {
            return null;
        }
        double reach = blockReachDistance(mc, player);
        Object mop = rayTrace(mc, player, world, baseYaw, basePitch, reach);
        if (mop != null && mopMatches(mop, blockPos, facing)) {
            Object vec = OpenMaverFeatures.read(mop, "hitVec", "field_72307_f", null);
            return vec == null ? null : new AimHit(vec, baseYaw, basePitch);
        }
        Object target = clickVec(mc, world, blockPos, facing);
        if (target == null) {
            return null;
        }
        double dx = vecX(target) - OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double dy = vecY(target) - OpenMaverFeatures.number(player, "posY", "field_70163_u")
                - OpenMaverFeatures.eyeHeight(player);
        double dz = vecZ(target) - OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        float[] rots = rotationsTo(dx, dy, dz, baseYaw, basePitch);
        mop = rayTrace(mc, player, world, rots[0], rots[1], reach);
        if (mop == null || !mopMatches(mop, blockPos, facing)) {
            return null;
        }
        Object vec = OpenMaverFeatures.read(mop, "hitVec", "field_72307_f", null);
        return vec == null ? null : new AimHit(vec, rots[0], rots[1]);
    }

    /**
     * 放置一个方块。
     *
     * <p>C08 本身不带 rotation。服务端处理 C08 时使用的是上一 tick C03 里的
     * aim（见 {@link #flushPendingPlace}），因此这里不再补 C05：额外 C05 会被
     * Grim 记成额外 tick（Timer），并让 C08 变成 post-flying（Post）。
     */
    private static boolean place(Minecraft mc, Object player, Object world,
            Object blockPos, Object facing, AimHit aim) {
        if (aim == null || aim.hitVec == null || !holdingBlock(player) || blockCount <= 0) {
            return false;
        }
        if (placedThisTick()) {
            // 一 tick 只能有一个 C08：第二个会被 Grim 记成 MultiPlace（并在
            // Post 里多一次 C08 记账）。flush 与队列都按「每 tick 一个」设计，
            // 这里再兜一层。
            OpenMaverDebug.noticeOnce("scaffold.place.multi",
                    "second C08 in the same tick suppressed (MultiPlace)");
            return false;
        }
        if (!OpenMaverFeatures.claimPlacement()) {
            // 本 tick 的 C08 名额已经被别的模块（Velocity 的 farm）用掉了。
            // 两个模块各自维护 "一 tick 一次" 挡不住对方，必须走公共闸门。
            OpenMaverDebug.noticeOnce("scaffold.place.shared",
                    "another module already sent a C08 this tick (MultiPlace)");
            return false;
        }
        Object stack = OpenMaverFeatures.call(player, "getHeldItem", "func_70694_bm", null);
        Object controller = OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        if (stack == null || controller == null) {
            return false;
        }
        Object result = OpenMaverFeatures.call(controller, "onPlayerRightClick",
                "func_178890_a", null, player, world, stack, blockPos, facing, aim.hitVec);
        if (!Boolean.TRUE.equals(result)) {
            OpenMaverDebug.noticeOnce("scaffold.place.failed",
                    "onPlayerRightClick returned " + String.valueOf(result));
            return false;
        }
        if (!isCreative(mc, controller)) {
            blockCount--;
        }
        // 本 tick 的飞包必须携带这个角度：Grim 先收 C08、再收本 tick 的飞包，
        // 然后用那条飞包的 rotation 去 ray trace（见 holdPlaceAim）。
        holdPlaceAim = true;
        holdPlaceYaw = aim.yaw;
        holdPlacePitch = aim.pitch;
        lastPlacedTargetX = blockPosX(blockPos) + FACING_DELTAS[facingIndex(facing)][0];
        lastPlacedTargetY = blockPosY(blockPos) + FACING_DELTAS[facingIndex(facing)][1];
        lastPlacedTargetZ = blockPosZ(blockPos) + FACING_DELTAS[facingIndex(facing)][2];
        lastPlacedTargetTick = currentTick;
        // HUD 方块图标抖一下；数量由 HUD 每帧从快捷栏重新统计。
        ScaffoldHud.onBlockPlaced();
        if (swing) {
            OpenMaverFeatures.callVoid(player, "swingItem", "func_71038_i", null);
        } else {
            sendAnimation(player);
        }
        if (!OpenMaverFeatures.scaffoldOnGround(player)) {
            hasPlacedThisJump = true;
        }
        long interval = lastPlacementTick == Long.MIN_VALUE
                ? 0L : Math.max(0L, currentTick - lastPlacementTick);
        lastPlacementTick = currentTick;
        OpenMaverDebug.place(blockPosX(blockPos), blockPosY(blockPos), blockPosZ(blockPos),
                FACING_NAMES[facingIndex(facing)], aim.yaw, aim.pitch,
                false, false, (int) interval);
        return true;
    }

    /**
     * 把本地玩家的 MovementInput 换成 {@link ScaffoldMovementInput}。
     *
     * <p>只在本地玩家上安装；模块关闭时子类不会改任何字段，因此不需要恢复。
     * 重生/切维度后玩家对象会变化，每 tick 检查一次即可。
     */
    private static void ensureMovementHook(Minecraft mc, Object player) {
        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        if (input instanceof ScaffoldMovementInput) {
            return;
        }
        // 不要覆盖其它 mod 自定义的 MovementInput。
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
            // 保留本 tick 已有的输入，避免替换后有一 tick 变成静止。
            setNumber(hook, "moveForward", "field_78900_b",
                    OpenMaverFeatures.number(input, "moveForward", "field_78900_b"));
            setNumber(hook, "moveStrafe", "field_78901_c",
                    OpenMaverFeatures.number(input, "moveStrafe", "field_78901_c"));
            setBooleanField(hook, "jump", "field_78903_e", Boolean.TRUE.equals(
                    OpenMaverFeatures.read(input, "jump", "field_78903_e", Boolean.class)));
            setBooleanField(hook, "sneak", "field_78899_d", Boolean.TRUE.equals(
                    OpenMaverFeatures.read(input, "sneak", "field_78899_d", Boolean.class)));
        }
        if (OpenMaverFeatures.writeObjectField(player, "movementInput", "field_71158_b", hook)) {
            OpenMaverDebug.noticeOnce("scaffold.hook",
                    "ScaffoldMovementInput installed (tick rotation + moveFix)");
        } else {
            OpenMaverDebug.noticeOnce("scaffold.hook.fail",
                    "cannot replace movementInput; silent rotation disabled");
        }
    }

    /**
     * 由 {@link ScaffoldMovementInput} 在 vanilla 更新完按键输入之后调用。
     *
     * <p>此时 movementInput 不会被原版立刻覆盖，而 moveFlying() 还没执行：
     * <ol>
     *   <li>按 aim 与相机的差值旋转输入向量，让 WASD 的世界方向保持不变；</li>
     *   <li>把 aim 写进 rotationYaw/Pitch，本 tick 末尾的 C03 会带上它；</li>
     *   <li>tick END 再把 rotation 恢复成相机角度（见 onTickEnd）。</li>
     * </ol>
     */
    static void afterMovementInput(Object input) {
        if (!hookArmed) {
            return;
        }
        if (System.nanoTime() - hookArmedNanos > 500_000_000L) {
            // tick 桥接失效（END 没执行）时兜底恢复相机，避免视角卡在 aim。
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
            // 原版刚更新完按键输入，moveFlying() 还没执行：这里是修改移动量
            // 和写入本 tick C03 rotation 的正确时机。
            float originalForward = (float) OpenMaverFeatures.number(
                    input, "moveForward", "field_78900_b");
            float originalStrafe = (float) OpenMaverFeatures.number(
                    input, "moveStrafe", "field_78901_c");
            float forward = originalForward;
            float strafe = originalStrafe;
            float speed = getSpeedFactor(mc, player);

            boolean rotate = (aimReady || pendingPlace != null) && !aimApplied;
            if (rotate && moveFix != 0 && forwardPressed(mc)) {
                // Myau 的 fixStrafe：把输入旋转到目标 yaw 之后，再吸附回
                // 8 个整数方向之一（每 45° 一个），而不是连续旋转。
                //
                // 连续旋转会得到任意角度的移动向量，而服务端（Grim 的
                // Simulation）只会用 -1/0/1 的输入方向做预测；哪怕只差
                // 几度，也会被算成 0.002~0.03 的位移误差并持续累积。
                // 吸附到整数方向后，客户端移动永远能被服务端精确复现。
                float aimYawForMove = pendingPlace != null ? pendingPlace.yaw : tickAimYaw;
                float cameraMoveYaw = adjustYaw(cameraYaw,
                        forwardValue(mc), leftValue(mc));
                float angle = OpenMaverFeatures.wrapDegrees(
                        cameraMoveYaw - aimYawForMove + 22.5F);
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
                // 原版在 updatePlayerMoveState() 之后会用 moveForward >= 0.8
                // 维持冲刺。斜着搭路时 forward 只有 0.707，会导致冲刺被关掉。
                // moveFlying() 会把向量归一化，等比放大不改变世界方向。
                if (speed == 1.0F && forward > 0.0F && forward < 0.8F) {
                    float scale = 0.8F / forward;
                    forward *= scale;
                    strafe *= scale;
                }
            }

            // ground/air/speed motion：之前在 START 阶段改会被原版输入覆盖，
            // 结果设置永远不生效（表现为“速度一直是默认值”）。放到这里才有效。
            if (speed != 1.0F) {
                if (forward != 0.0F && strafe != 0.0F) {
                    forward *= 0.7071067811865476F;
                    strafe *= 0.7071067811865476F;
                }
                forward *= speed;
                strafe *= speed;
            }
            if (forward != originalForward || strafe != originalStrafe) {
                setNumber(input, "moveForward", "field_78900_b", forward);
                setNumber(input, "moveStrafe", "field_78901_c", strafe);
            }

            // Myau 在 MoveInputEvent 里按下 jump；这里同样必须在原版输入更新之后。
            if (stage > 0 && forwardPressed(mc)) {
                setBooleanField(input, "jump", "field_78903_e", true);
            }

            // AutoSprint 在 START 阶段可能先把 sprint 打开，而原版稍后会因为
            // moveForward < 0.8 关掉它，导致 C0B start/stop 每 tick 交替。
            // 注意必须用“真实按键输入”判断：moveFix 会把 forward 改小（例如
            // 斜着搭路时只剩 0.7），拿 remap 后的值判断会让冲刺被永久关掉，
            // 这才是移动速度慢的主要原因。
            boolean sprinting = Boolean.TRUE.equals(OpenMaverFeatures.call(
                    player, "isSprinting", "func_70051_ag", null));
            boolean stopSprint = shouldStopSprint(mc, player)
                    || (!grimStrict() && originalForward < 0.8F);
            if (sprinting && stopSprint) {
                OpenMaverFeatures.callVoid(player, "setSprinting",
                        "func_70031_b", null, Boolean.FALSE);
            }

            if (rotate) {
                if (holdPlaceAim) {
                    // 本 tick 有一条 C08 已经发出：飞包必须和它逐位相同，
                    // 不再叠加噪声（噪声已在 flush 的校验角度里）。
                    tickAimYaw = holdPlaceYaw;
                    tickAimPitch = holdPlacePitch;
                } else if (pendingPlace != null) {
                    // pending 的 aim 就是这一条 C03 的角度，只额外加一层 ±0.005°
                    // 微噪声（连续几 tick 同一个 pending 时不能发出两份完全相同的
                    // rotation，那是 Grim 的 AimDuplicateLook）。
                    tickAimYaw = pendingPlace.yaw + aimMicroNoise();
                    tickAimPitch = pendingPlace.pitch + aimMicroNoise();
                }
                // 连续角写包：yaw 差 360 是同一朝向，但服务端看原始浮点差，
                // 从 -179 跳到 +179 会产生 358° 的包间 delta（Grim AimModulo360）。
                float writeYaw = OpenMaverFeatures.continuousYaw(
                        (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z"),
                        tickAimYaw);
                setNumber(player, "rotationYaw", "field_70177_z", writeYaw);
                setNumber(player, "rotationPitch", "field_70125_A", tickAimPitch);
                aimApplied = true;
                rotationDirty = true;
                serverYaw = writeYaw;
                serverPitch = tickAimPitch;
                if (pendingPlace != null) {
                    // 记下**真正写进 C03** 的角度（含噪声、含 continuousYaw 的
                    // 360° 平移）。下一 tick 的 flush 用这两个值做 rayTrace，
                    // C08 于是和服务端持有的 rotation 完全一致（RotationPlace）。
                    // 只有真的写进过 C03 的 aim，才允许下一 tick 用它发 C08。
                    pendingPlace.sentYaw = writeYaw;
                    pendingPlace.sentPitch = tickAimPitch;
                    pendingPlace.sent = true;
                }
            }
        } catch (Throwable ignored) {
            // 绝不能让渲染/玩家 tick 因 hook 失败而中断。
        }
    }

    /** 记录下一 tick 的放置目标；同一 tick 只接受第一个候选。 */
    private static void queuePlacement(Placement placement) {
        if (placement == null || pendingPlace != null) {
            return;
        }
        AimHit aim = placement.hit;
        if (aim == null || Float.isNaN(aim.yaw) || Float.isNaN(aim.pitch)) {
            return;
        }
        pendingPlace = new PendingPlace(placement.targetPos, placement.supportPos,
                placement.facing, aim, aim.yaw, aim.pitch);
        setAim(aim.yaw, aim.pitch);
    }

    /**
     * 写进 C03 的 aim 会加一个极小的噪声。
     *
     * <p>原因：Grim 的 {@code AimDuplicateLook} 判据是「连续两个包的 rotation 完全相同」。
     * Scaffold 为了放置会连续几个 tick 保持同一个 aim，包里的 yaw/pitch 就会一模一样
     * —— 真实玩家不可能做到，于是被判机械。噪声量级取 ±0.005°，比任何方块
     * AABB 的角度尺度小两个数量级，不影响 ray trace，也不影响服务端命中判定。
     *
     * <p>注意：{@code lastAimYaw/lastAimPitch}（给 {@code rayAtBlock} 重算用的基准）
     * 保留原值，只有真正写进包的 {@code tickAimYaw/tickAimPitch} 加噪声。
     */
    private static void setAim(float aimYaw, float aimPitch) {
        lastAimYaw = aimYaw;
        lastAimPitch = aimPitch;
        hasAim = true;
        tickAimYaw = aimYaw + aimMicroNoise();
        tickAimPitch = aimPitch + aimMicroNoise();
        aimReady = true;
        // 只需要保留到本 tick 的 C03：下一 tick 开头 flush 用的是 pending.yaw，
        // 之后就让真实相机重新接管，避免转头后服务端还停在旧 aim 上。
        aimHoldTicks = 1;
    }

    /**
     * 把「本 tick 那条 C08 的角度」写进 {@code tickAim*}。
     *
     * <p>不加噪声、不再从 {@code pendingPlace} 或相机取值：Grim 会拿本 tick 飞包的
     * rotation 去 ray trace 这条 C08，角度只要差一点点就可能落到面外。
     */
    private static void applyHoldAim() {
        tickAimYaw = holdPlaceYaw;
        tickAimPitch = holdPlacePitch;
        lastAimYaw = holdPlaceYaw;
        lastAimPitch = holdPlacePitch;
        hasAim = true;
        aimReady = true;
        aimHoldTicks = 1;
        hookArmed = true;
    }

    /** tower / keepY 的路径拿到的是“支撑方块 + 点击面”，这里换算成目标空气格。 */
    private static void queueSupportPlacement(Object supportPos, Object facingObj, AimHit aim) {
        if (supportPos == null || facingObj == null || aim == null || pendingPlace != null) {
            return;
        }
        Object targetPos = offset(supportPos, facingIndex(facingObj));
        pendingPlace = new PendingPlace(targetPos, supportPos, facingObj, aim,
                aim.yaw, aim.pitch);
        setAim(aim.yaw, aim.pitch);
    }

    /**
     * 放置上一 tick 选好的目标。
     *
     * <p>判定链（Grim 1.8，见 {@link #holdPlaceAim} 里引的源码）：C08 先入队，
     * **本 tick 的飞包**到达时 Grim 才用它的 yaw/pitch + 当前眼睛做 ray trace，
     * 去撞 C08 里那个 {@code place.position}（被点击的方块，即 {@code supportPos}）。
     *
     * <p>所以这里必须做两件事，缺一就会 flag：
     * <ol>
     *   <li>选一个角度，并且**用它自己**做一次 rayTrace（不是拿另一个角度校验、
     *       再发第三个角度）；</li>
     *   <li>把这个角度锁进本 tick 的飞包（{@code place()} 里的
     *       {@code holdPlaceAim}），本 tick 不许再规划别的目标。</li>
     * </ol>
     *
     * <p>角度对不上（玩家移动/转头）时不换角度直接发 C08，而是重新瞄准、只把新
     * 角度交给本 tick 的飞包，下一 tick 再放置（{@code MAX_PENDING_RETRIES} 封顶）。
     *
     * @see #pendingAction(boolean, boolean, int) 决策表（包层测试直接断言它）
     */
    private static void flushPendingPlace(Minecraft mc, Object player, Object world) {
        PendingPlace pending = pendingPlace;
        pendingPlace = null;
        if (pending == null || !holdingBlock(player) || blockCount <= 0) {
            return;
        }
        if (VelocityFeature.isBlinking()) {
            // Velocity 的 blink 窗口里所有位置包都被扣住了，Grim 的
            // lastClaimedPosition 比客户端老 1~2 tick。这时候用「当前眼睛」
            // 算出来的 hitVec 与它判定的几何不是同一个位置 —— 日志里
            // PositionPlace / FarPlace / AirLiquidPlace 就是从这个错位来的。
            // 位置包恢复之前一律不放，pending 原样留到下一 tick。
            pendingPlace = pending;
            OpenMaverDebug.noticeOnce("scaffold.pending.blink",
                    "placement held: Velocity is blinking (position base is stale)");
            return;
        }
        if (!pending.sent) {
            // 上一 tick 的飞包没有真正带上这个 aim（输入 hook 没执行、GUI/暂停
            // 切换、刚开启模块）。补写进本 tick 的飞包，下一 tick 再放置。
            // 这样 pre 分支（C08 立刻触发的 onBlockPlace）也能看到同一个角度。
            if (pendingAction(false, false, pending.retries) == PendingAction.RESEND_AIM) {
                PendingPlace again = pending.withAim(pending.hit, pending.yaw, pending.pitch);
                again.retries = pending.retries + 1;
                pendingPlace = again;
                setAim(pending.yaw, pending.pitch);
            } else {
                OpenMaverDebug.noticeOnce("scaffold.pending.unsent",
                        "pending aim never reached a C03; dropped");
            }
            return;
        }
        if (!isReplaceable(world, pending.targetPos)
                || isReplaceable(world, pending.supportPos)
                || isInteractable(world, pending.supportPos)) {
            return;
        }
        // 选一个「本 tick 的飞包会原样发出去」的角度，并且**用它自己**做一次
        // ray trace。Grim 判定 C08 用的就是这条飞包的 rotation（见 holdPlaceAim），
        // 所以校验角度与发包角度必须逐位相同 —— 这是 RotationPlace 的唯一判据。
        //
        // 候选阶梯：
        //   1. sentYaw/sentPitch + 微噪声（上一 tick 发过的角度，量级差 0.005°，
        //      保证连续两条飞包的 rotation 不完全相等 → AimDuplicateLook）；
        //   2. sentYaw/sentPitch（噪声把射线挤出命中面时的回退）；
        //   3. pending.yaw/pitch（上一 tick 的连续角被平移过 360° 时用基准值）。
        float placeYaw = pending.sentYaw + aimMicroNoise();
        float placePitch = pending.sentPitch + aimMicroNoise();
        AimHit hit = rayAtBlock(mc, player, world, pending.supportPos, pending.facing,
                placeYaw, placePitch);
        if (hit == null) {
            placeYaw = pending.sentYaw;
            placePitch = pending.sentPitch;
            hit = rayAtBlock(mc, player, world, pending.supportPos, pending.facing,
                    placeYaw, placePitch);
        }
        if (hit == null) {
            placeYaw = pending.yaw;
            placePitch = pending.pitch;
            hit = rayAtBlock(mc, player, world, pending.supportPos, pending.facing,
                    placeYaw, placePitch);
        }
        if (hit == null) {
            // §4.1：目标格还在，但上一 tick 发出去的角度已经打不到原来的面
            // （玩家移动/转头）。重新算一个角度，**只写进本 tick 的 C03**，
            // 下一 tick 再用它放置 —— 绝不立即发 C08。
            if (pendingAction(true, false, pending.retries) == PendingAction.REQUEUE) {
                Placement reaim = reaimPending(mc, player, world, pending);
                if (reaim != null && reaim.hit != null) {
                    PendingPlace retry = new PendingPlace(reaim.targetPos, reaim.supportPos,
                            reaim.facing, reaim.hit, reaim.hit.yaw, reaim.hit.pitch);
                    retry.retries = pending.retries + 1;
                    pendingPlace = retry;
                    setAim(reaim.hit.yaw, reaim.hit.pitch);
                    OpenMaverDebug.noticeOnce("scaffold.pending.requeue",
                            "pending aim missed server rotation; re-aimed for next tick");
                    return;
                }
            }
            OpenMaverDebug.noticeOnce("scaffold.pending.ray",
                    "pending placement no longer ray-hits; dropped");
            return;
        }
        place(mc, player, world, pending.supportPos, pending.facing, hit);
    }

    /**
     * 为同一个目标格重新找一个「本 tick 就该发出去」的角度。
     *
     * <p>返回值只会交给 {@link #setAim} 写进本 tick 的 C03，下一 tick 才用它发
     * C08，所以这里可以放心按**预测眼睛位置**重新选面/重新采样（预测位置才是
     * 这条 C03 发出时的真实眼睛）。同 tick 立即使用的写法会直接命中
     * {@code RotationPlace: post-flying}。
     */
    private static Placement reaimPending(Minecraft mc, Object player, Object world,
            PendingPlace pending) {
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double py = OpenMaverFeatures.number(player, "posY", "field_70163_u");
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        double eye = OpenMaverFeatures.eyeHeight(player);
        // 眼睛用**当前位置**：Grim 在派发 C08 时把 player.x/y/z 设成
        // lastClaimedPosition（C08 之前那条飞包的位置 = 现在的客户端位置），
        // 不是下一 tick 的预测位置。
        Placement placement = findPlacementForTarget(mc, player, world,
                blockPosX(pending.targetPos), blockPosY(pending.targetPos),
                blockPosZ(pending.targetPos),
                px, py + eye, pz,
                false, rotations == 0 ? 0 : 1, cameraYaw, cameraPitch);
        if (placement != null && placement.hit != null) {
            return placement;
        }
        // 目标格周围再没有别的可见面：退回「从当前眼睛重瞄 pending.hitVec」，
        // 仍然必须通过一次真实 rayTrace 才算数（没有 hitVec 就放弃）。
        if (pending.hit == null || pending.hit.hitVec == null) {
            return null;
        }
        double dx = vecX(pending.hit.hitVec) - px;
        double dy = vecY(pending.hit.hitVec) - py - eye;
        double dz = vecZ(pending.hit.hitVec) - pz;
        float[] rots = rotationsTo(dx, dy, dz, pending.yaw, pending.pitch);
        AimHit hit = rayAtBlock(mc, player, world, pending.supportPos, pending.facing,
                rots[0], rots[1]);
        return hit == null ? null
                : new Placement(pending.targetPos, pending.supportPos, pending.facing,
                        hit, false);
    }

    /**
     * flush 时对一个 pending 目标的决策。
     *
     * <p>纯函数（不碰世界、不碰包），{@code tools/scaffold-test} 直接断言这张
     * 表：**只有 rayHit 为真时才允许 PLACE**，其它分支一律先让角度进 C03。
     */
    static PendingAction pendingAction(boolean sent, boolean rayHit, int retries) {
        if (!sent) {
            return retries >= MAX_PENDING_RETRIES ? PendingAction.DROP : PendingAction.RESEND_AIM;
        }
        if (rayHit) {
            return PendingAction.PLACE;
        }
        return retries >= MAX_PENDING_RETRIES ? PendingAction.DROP : PendingAction.REQUEUE;
    }

    /** {@link #pendingAction} 的结果。 */
    enum PendingAction {
        /** 用服务端已收到的 aim 发 C08。 */
        PLACE,
        /** 角度没进过 C03：写 aim，本 tick 不发 C08。 */
        RESEND_AIM,
        /** 角度打不到原面：重算角度写进 C03，下一 tick 再放。 */
        REQUEUE,
        /** 重试到上限：丢弃这次放置。 */
        DROP
    }

    /**
     * 把 rotation 恢复到真实相机角度。
     *
     * <p>同时把 renderYawOffset/rotationYawHead 恢复成相机；调用方若想让
     * F5 身体保持搭路朝向，会在之后再把它们改成 serverYaw（见 onTickEnd）。
     */
    private static void restoreCameraView(Object player) {
        if (player == null) {
            rotationDirty = false;
            aimApplied = false;
            return;
        }
        // 恢复视角也要走最短路径：cameraYaw 是取模后的值，而 aim 期间写出去的是
        // 连续角（可能 180.6）。直接写 -179.4 会让服务端看到 360° 的包间 delta
        // —— 正好命中 Grim AimModulo360（|delta| > 320 且上一 tick < 30）。
        float restoreYaw = OpenMaverFeatures.continuousYaw(
                (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z"),
                cameraYaw);
        setNumber(player, "rotationYaw", "field_70177_z", restoreYaw);
        setNumber(player, "rotationPitch", "field_70125_A", cameraPitch);
        setNumber(player, "prevRotationYaw", "field_70126_B", restoreYaw);
        setNumber(player, "prevRotationPitch", "field_70127_C", cameraPitch);
        // 第一人称手臂也回到相机方向，否则手会朝脚下抬。
        setNumber(player, "renderArmYaw", "field_71154_f", cameraYaw);
        setNumber(player, "prevRenderArmYaw", "field_71163_h", cameraYaw);
        setNumber(player, "renderArmPitch", "field_71155_g", cameraPitch);
        setNumber(player, "prevRenderArmPitch", "field_71164_i", cameraPitch);
        setNumber(player, "renderYawOffset", "field_70761_aq", cameraYaw);
        setNumber(player, "rotationYawHead", "field_70759_as", cameraYaw);
        rotationDirty = false;
        aimApplied = false;
    }

    private static long currentTick;

    /** ±0.005° 的确定性微噪声（不用 Random，避免影响任何随机节奏）。 */
    private static float aimMicroNoise() {
        long n = System.nanoTime() % 1000L;
        return (float) ((n - 500L) * 1.0E-5D);
    }

    /** 本 tick 是否已经放过方块：一 tick 只允许一个 C08（MultiPlace）。 */
    private static boolean placedThisTick() {
        return lastPlacementTick != Long.MIN_VALUE && lastPlacementTick == currentTick;
    }

    /**
     * Grim 模式：所有放置都必须使用“服务端已经收到的 aim”，不再即时放置，
     * 也不在 flush 失败时偷偷换角度/换 hitVec。否则会触发 PositionPlace /
     * RotationPlace；同时 SafeWalk 不再直接改 motion（Simulation）。
     */
    private static boolean grimStrict() {
        return OpenMaverFeatures.isBypassEnabled();
    }

    /** 服务端方块变更回来之前（1 tick 内）不要对同一格重复发 C08。 */
    private static boolean isRecentlyPlaced(int x, int y, int z) {
        return lastPlacedTargetTick != Long.MIN_VALUE
                && currentTick - lastPlacedTargetTick <= 1L
                && lastPlacedTargetX == x
                && lastPlacedTargetY == y
                && lastPlacedTargetZ == z;
    }

    /** 由 OpenMaverFeatures 每 tick 递增，仅用于 debug 的放置间隔。 */
    public static void tickCounter(long tick) {
        currentTick = tick;
    }

    // ==================================================================
    // Tower / KeepY / 移动（对应 Myau onStrafe / onMoveInput / onLivingUpdate）
    // ==================================================================

    private static void applyTower(Minecraft mc, Object player) {
        if (currentFlatTick > 0) {
            towerTick = 0;
            towerDelay = 0;
            return;
        }
        boolean holding = holdingBlock(player);
        if (OpenMaverFeatures.read(player, "isCollidedHorizontally",
                "field_70123_F", Boolean.class) == Boolean.TRUE
                || OpenMaverFeatures.number(player, "hurtTime", "field_70737_aN") > 5.0D
                || isPotionActive(player, jumpPotion())
                || !keyDown(mc, "keyBindJump")
                || !holding) {
            towerTick = 0;
            towerDelay = 0;
            return;
        }
        int yState = (int) (OpenMaverFeatures.number(player, "posY", "field_70163_u")
                % 1.0D * 100.0D);
        if (tower == 1) {
            switch (towerTick) {
                case 0:
                    if (OpenMaverFeatures.scaffoldOnGround(player)) {
                        towerTick = 1;
                        setNumber(player, "motionY", "field_70181_x", -0.0784000015258789D);
                    }
                    return;
                case 1:
                    if (yState == 0 && isAirBelow(mc, player)) {
                        startY = floor(OpenMaverFeatures.number(player, "posY", "field_70163_u"));
                        towerTick = 2;
                        setNumber(player, "motionY", "field_70181_x", 0.42F);
                        if (forwardPressed(mc)) {
                            setSpeed(player, getSpeed(player), getMoveYaw(mc, player));
                        } else {
                            setSpeed(player, 0.0D, getMoveYaw(mc, player));
                            setMoveInput(player, 0.0F, 0.0F);
                        }
                    } else {
                        towerTick = 0;
                    }
                    return;
                case 2:
                    towerTick = 3;
                    setNumber(player, "motionY", "field_70181_x",
                            0.75D - OpenMaverFeatures.number(player, "posY", "field_70163_u") % 1.0D);
                    return;
                case 3:
                    towerTick = 1;
                    setNumber(player, "motionY", "field_70181_x",
                            1.0D - OpenMaverFeatures.number(player, "posY", "field_70163_u") % 1.0D);
                    return;
                default:
                    towerTick = 0;
                    return;
            }
        }
        if (tower == 2) {
            switch (towerTick) {
                case 0:
                    if (OpenMaverFeatures.scaffoldOnGround(player)) {
                        towerTick = 1;
                        setNumber(player, "motionY", "field_70181_x", -0.0784000015258789D);
                    }
                    return;
                case 1:
                    if (yState == 0 && isAirBelow(mc, player)) {
                        startY = floor(OpenMaverFeatures.number(player, "posY", "field_70163_u"));
                        if (!forwardPressed(mc)) {
                            towerDelay = 2;
                            setSpeed(player, 0.0D, getMoveYaw(mc, player));
                            setMoveInput(player, 0.0F, 0.0F);
                            Object facing = yawToFacing(OpenMaverFeatures.wrapDegrees(yaw - 180.0F));
                            double distance = distanceToEdge(player, facing);
                            if (distance > 0.1D) {
                                if (OpenMaverFeatures.scaffoldOnGround(player)) {
                                    int dir = facingIndex(facing);
                                    double offsetAmount = Math.min(getRandomOffset(), distance - 0.05D);
                                    double jitter = randomDouble(0.02D, 0.03D);
                                    AxisAlignedBB box = OpenMaverFeatures.boundingBox(player);
                                    if (box != null) {
                                        AxisAlignedBB nextBox = offsetBox(box,
                                                FACING_DELTAS[dir][0] * (offsetAmount - jitter), 0.0D,
                                                FACING_DELTAS[dir][2] * (offsetAmount - jitter));
                                        if (collidesEmpty(mc, player, nextBox)) {
                                            setNumber(player, "motionY", "field_70181_x",
                                                    -0.0784000015258789D);
                                            double cx = (box.minX + box.maxX) / 2.0D;
                                            double cz = (box.minZ + box.maxZ) / 2.0D;
                                            callVoid(player, "setPosition", "func_70107_b", null,
                                                    cx + FACING_DELTAS[dir][0] * (offsetAmount - jitter),
                                                    box.minY,
                                                    cz + FACING_DELTAS[dir][2] * (offsetAmount - jitter));
                                        }
                                    }
                                }
                                return;
                            } else {
                                towerTick = 2;
                                targetFacing = facing;
                                setNumber(player, "motionY", "field_70181_x", 0.42F);
                            }
                            return;
                        } else {
                            towerTick = 2;
                            towerDelay++;
                            setNumber(player, "motionY", "field_70181_x", 0.42F);
                            setSpeed(player, getSpeed(player), getMoveYaw(mc, player));
                            return;
                        }
                    } else {
                        towerTick = 0;
                        towerDelay = 0;
                    }
                    return;
                case 2:
                    towerTick = 3;
                    setNumber(player, "motionY", "field_70181_x",
                            OpenMaverFeatures.number(player, "motionY", "field_70181_x")
                                    - randomDouble(0.00101D, 0.00109D));
                    return;
                case 3:
                    if (towerDelay >= 4) {
                        towerTick = 4;
                        towerDelay = 0;
                    } else {
                        towerTick = 1;
                        setNumber(player, "motionY", "field_70181_x",
                                1.0D - OpenMaverFeatures.number(player, "posY", "field_70163_u") % 1.0D);
                    }
                    return;
                case 4:
                    towerTick = 5;
                    return;
                case 5:
                    if (!isAirBelow(mc, player)) {
                        towerTick = 0;
                    } else {
                        towerTick = 1;
                        double my = OpenMaverFeatures.number(player, "motionY", "field_70181_x");
                        my -= 0.08D;
                        my *= 0.98F;
                        my -= 0.08D;
                        my *= 0.98F;
                        setNumber(player, "motionY", "field_70181_x", my);
                    }
                    return;
                default:
                    towerTick = 0;
                    towerDelay = 0;
                    return;
            }
        }
        if (tower == 4) {
            double mx = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
            double mz = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
            if (mx == 0.0D && mz == 0.0D) {
                if (yState == 0 && isAirBelow(mc, player)
                        && OpenMaverFeatures.scaffoldOnGround(player)) {
                    startY = floor(OpenMaverFeatures.number(player, "posY", "field_70163_u"));
                    setNumber(player, "motionY", "field_70181_x", 0.42F);
                }
                if (!OpenMaverFeatures.scaffoldOnGround(player)
                        && OpenMaverFeatures.number(player, "motionY", "field_70181_x") < 0.0D) {
                    setNumber(player, "motionY", "field_70181_x", -0.3F);
                }
                towerTick = 0;
                towerDelay = 0;
            }
        }
    }

    private static void applyMovement(Minecraft mc, Object player) {
        // 移动速度倍率 / 自动跳跃 / Sprint 都移到
        // ScaffoldMovementInput.afterMovementInput()：START 阶段修改
        // movementInput 会被原版 updatePlayerMoveState() 覆盖，实际从不生效。
        // 这里只处理 SafeWalk。
        applySafeWalk(mc, player);
    }

    /**
     * SafeWalk：不直接清空 motion。
     *
     * <p>旧实现只要前方 0.45 格内是空气就把 motionX/Z 清零，等于在离边缘还有
     * 半格时每 tick 刹车，速度会掉到原来的 1/3 左右，表现出来就是“Scaffold
     * 开启后走不动”。这里改成原版潜行防边缘的逻辑：只把“会踏空的那部分位移”
     * 收缩到 0.05 的步长，直到刚好停在方块边缘；前方是实心时完全不影响速度。
     */
    private static void applySafeWalk(Minecraft mc, Object player) {
        if (!safeWalk || grimStrict()
                || !OpenMaverFeatures.scaffoldOnGround(player)) {
            return;
        }
        if (OpenMaverFeatures.number(player, "motionY", "field_70181_x") > 0.0D) {
            return;
        }
        AxisAlignedBB box = OpenMaverFeatures.boundingBox(player);
        if (box == null) {
            return;
        }
        double motionX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
        double motionZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
        if (motionX == 0.0D && motionZ == 0.0D) {
            return;
        }
        double safeX = motionX;
        double safeZ = motionZ;
        while (safeX != 0.0D
                && collidesEmpty(mc, player, offsetBox(box, safeX, -1.0D, 0.0D))) {
            safeX = shrinkEdgeStep(safeX);
        }
        while (safeZ != 0.0D
                && collidesEmpty(mc, player, offsetBox(box, 0.0D, -1.0D, safeZ))) {
            safeZ = shrinkEdgeStep(safeZ);
        }
        while (safeX != 0.0D && safeZ != 0.0D
                && collidesEmpty(mc, player, offsetBox(box, safeX, -1.0D, safeZ))) {
            safeX = shrinkEdgeStep(safeX);
            safeZ = shrinkEdgeStep(safeZ);
        }
        if (safeX != motionX || safeZ != motionZ) {
            setNumber(player, "motionX", "field_70159_w", safeX);
            setNumber(player, "motionZ", "field_70179_y", safeZ);
        }
    }

    private static double shrinkEdgeStep(double value) {
        if (value < 0.05D && value > -0.05D) {
            return 0.0D;
        }
        return value > 0.0D ? 0.05D : -0.05D;
    }

    // ==================================================================
    // Myau Util 的反射实现
    // ==================================================================

    private static void updateBlockSlot(Object player) {
        Object stack = OpenMaverFeatures.call(player, "getHeldItem", "func_70694_bm", null);
        int count = isBlock(stack) ? (int) OpenMaverFeatures.number(stack, "stackSize", "field_77994_a") : 0;
        blockCount = Math.min(blockCount, count);
        if (blockCount > 0) {
            activeSlot = currentHotbarSlot(player);
            return;
        }
        int slot = currentHotbarSlot(player);
        if (blockCount == 0) {
            slot--;
        }
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        for (int i = slot; i > slot - 9; i--) {
            int hotbarSlot = ((i % 9) + 9) % 9;
            Object candidate = OpenMaverFeatures.call(inventory, "getStackInSlot",
                    "func_70301_a", null, Integer.valueOf(hotbarSlot));
            if (isBlock(candidate)) {
                setHotbarSlot(player, hotbarSlot);
                activeSlot = hotbarSlot;
                blockCount = (int) OpenMaverFeatures.number(candidate, "stackSize", "field_77994_a");
                break;
            }
        }
        if (activeSlot != -1) {
            setHotbarSlot(player, activeSlot);
        }
    }

    private static int countBlocks(Object player) {
        int count = 0;
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        if (inventory == null) {
            return 0;
        }
        for (int i = 0; i < 9; i++) {
            Object stack = OpenMaverFeatures.call(inventory, "getStackInSlot",
                    "func_70301_a", null, Integer.valueOf(i));
            if (isCountedBlock(stack)) {
                count += (int) OpenMaverFeatures.number(stack, "stackSize", "field_77994_a");
            }
        }
        return count;
    }

    /** HUD 用：当前快捷栏里可放置方块的总数。 */
    public static int hudBlockCount(Object player) {
        return countBlocks(player);
    }

    /** HUD 用：优先返回手持方块，否则返回快捷栏里第一组可放置方块。 */
    public static Object hudBlockStack(Object player) {
        Object held = OpenMaverFeatures.call(player, "getHeldItem", "func_70694_bm", null);
        if (isCountedBlock(held)) {
            return held;
        }
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        if (inventory == null) {
            return null;
        }
        for (int i = 0; i < 9; i++) {
            Object stack = OpenMaverFeatures.call(inventory, "getStackInSlot",
                    "func_70301_a", null, Integer.valueOf(i));
            if (isCountedBlock(stack)) {
                return stack;
            }
        }
        return null;
    }

    private static boolean isCountedBlock(Object stack) {
        if (!isBlock(stack)) {
            return false;
        }
        Object item = OpenMaverFeatures.call(stack, "getItem", "func_77973_b", null);
        Object block = item == null ? null
                : OpenMaverFeatures.call(item, "getBlock", "func_179223_d", null);
        return block != null && isSolid(block) && !isInteractableBlock(block);
    }

    private static boolean isBlock(Object stack) {
        if (stack == null || OpenMaverFeatures.number(stack, "stackSize", "field_77994_a") <= 0.0D) {
            return false;
        }
        Object item = OpenMaverFeatures.call(stack, "getItem", "func_77973_b", null);
        return item != null && itemBlockClass() != null && itemBlockClass().isInstance(item);
    }

    private static Class<?> itemBlockClass() {
        if (itemBlockType == null && !itemBlockLookupFailed) {
            try {
                itemBlockType = Class.forName("net.minecraft.item.ItemBlock");
            } catch (Throwable ignored) {
                itemBlockLookupFailed = true;
            }
        }
        return itemBlockType;
    }

    private static boolean isReplaceable(Object world, Object pos) {
        Object state = OpenMaverFeatures.call(world, "getBlockState", "func_180495_p", null, pos);
        Object block = state == null ? null
                : OpenMaverFeatures.call(state, "getBlock", "func_177230_c", null);
        if (block == null) {
            return false;
        }
        Object material = OpenMaverFeatures.call(block, "getMaterial", "func_149688_o", null);
        // 注意：func_76220_a 是 Material.isSolid()（默认 true），
        // Material.isReplaceable() 是 func_76222_j。用错会让逻辑完全反过来：
        // 空气被当成“不可替换”（target 直接 null），实心方块被当成“可放置”。
        Object replaceable = material == null ? null
                : OpenMaverFeatures.call(material, "isReplaceable", "func_76222_j", null);
        return Boolean.TRUE.equals(replaceable);
    }

    private static boolean isInteractable(Object world, Object pos) {
        Object state = OpenMaverFeatures.call(world, "getBlockState", "func_180495_p", null, pos);
        Object block = state == null ? null
                : OpenMaverFeatures.call(state, "getBlock", "func_177230_c", null);
        return block != null && isInteractableBlock(block);
    }

    private static boolean isInteractableBlock(Object block) {
        Class<?> type = block.getClass();
        Boolean cached = INTERACTABLE_CACHE.get(type);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean value = hasSuperclass(type, "BlockContainer")
                || hasSuperclass(type, "BlockWorkbench")
                || hasSuperclass(type, "BlockAnvil")
                || hasSuperclass(type, "BlockBed")
                || hasSuperclass(type, "BlockDoor")
                || hasSuperclass(type, "BlockTrapDoor")
                || hasSuperclass(type, "BlockFenceGate")
                || hasSuperclass(type, "BlockFence")
                || hasSuperclass(type, "BlockButton")
                || hasSuperclass(type, "BlockLever")
                || hasSuperclass(type, "BlockJukebox");
        INTERACTABLE_CACHE.put(type, Boolean.valueOf(value));
        return value;
    }

    private static boolean isSolid(Object block) {
        Class<?> type = block.getClass();
        Boolean cached = SOLID_CACHE.get(type);
        if (cached != null) {
            return cached.booleanValue();
        }
        String[] nonSolid = {
                "BlockStairs", "BlockSlab", "BlockEndPortalFrame", "BlockEndPortal",
                "BlockVine", "BlockPumpkin", "BlockCactus", "BlockBush", "BlockFalling",
                "BlockWeb", "BlockPane", "BlockCarpet", "BlockSnow", "BlockFence",
                "BlockFenceGate", "BlockWall", "BlockLadder", "BlockTorch",
                "BlockRedstoneWire", "BlockRedstoneDiode", "BlockBasePressurePlate",
                "BlockTripWire", "BlockTripWireHook", "BlockRailBase", "BlockSlime",
                "BlockTNT"
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

    private static boolean hasSuperclass(Class<?> type, String simpleName) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (simpleName.equals(c.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static float getSpeedFactor(Minecraft mc, Object player) {
        if (!OpenMaverFeatures.scaffoldOnGround(player)) {
            return airMotion / 100.0F;
        }
        return getSpeedLevel(player) > 0 ? speedMotion / 100.0F : groundMotion / 100.0F;
    }

    private static boolean shouldStopSprint(Minecraft mc, Object player) {
        if (isTowering(mc, player)) {
            return false;
        }
        if (rotations == 5 && !isSnapDisabled(mc, player)) {
            return false;
        }
        boolean stageActive = keepY == 1 || keepY == 2 || keepY == 4;
        return (!stageActive || stage <= 0) && sprintMode == 0;
    }

    private static boolean isTowering(Minecraft mc, Object player) {
        if (currentFlatTick > 0) {
            return false;
        }
        if (OpenMaverFeatures.scaffoldOnGround(player) && forwardPressed(mc)
                && !isAirAbove(mc, player)) {
            boolean keepYTelly = keepY == 3 || keepY == 4;
            boolean towerTelly = tower == 3 || tower == 4;
            return (keepYTelly && stage > 0) || (towerTelly && keyDown(mc, "keyBindJump"));
        }
        return false;
    }

    private static boolean isSnapDisabled(Minecraft mc, Object player) {
        boolean keepYEnabled = keepY != 0;
        boolean keepYActive = keepYEnabled && (!keepYonPress || keyDown(mc, "keyBindUseItem"));
        boolean jumping = keyDown(mc, "keyBindJump");
        return keepYActive || jumping;
    }

    private static boolean isDiagonal(float value) {
        float abs = Math.abs(value % 90.0F);
        return abs > 20.0F && abs < 70.0F;
    }

    private static float currentYaw(Minecraft mc, Object player) {
        return adjustYaw((float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z"),
                forwardValue(mc), leftValue(mc));
    }

    private static float adjustYaw(float yawValue, float forward, float strafe) {
        if (forward < 0.0F) {
            yawValue += 180.0F;
        }
        if (strafe != 0.0F) {
            float multiplier = forward == 0.0F ? 1.0F : 0.5F * Math.signum(forward);
            yawValue += -90.0F * multiplier * Math.signum(strafe);
        }
        return OpenMaverFeatures.wrapDegrees(yawValue);
    }

    private static boolean forwardPressed(Minecraft mc) {
        boolean forward = keyDown(mc, "keyBindForward");
        boolean back = keyDown(mc, "keyBindBack");
        boolean left = keyDown(mc, "keyBindLeft");
        boolean right = keyDown(mc, "keyBindRight");
        if (forward != back) {
            return true;
        }
        return left != right;
    }

    private static int forwardValue(Minecraft mc) {
        int value = 0;
        if (keyDown(mc, "keyBindForward")) {
            value++;
        }
        if (keyDown(mc, "keyBindBack")) {
            value--;
        }
        return value;
    }

    private static int leftValue(Minecraft mc) {
        int value = 0;
        if (keyDown(mc, "keyBindLeft")) {
            value++;
        }
        if (keyDown(mc, "keyBindRight")) {
            value--;
        }
        return value;
    }

    private static boolean keyDown(Minecraft mc, String fieldName) {
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object binding = OpenMaverFeatures.read(settings, fieldName, keyBindingSrg(fieldName), null);
        Object pressed = OpenMaverFeatures.read(binding, "pressed", "field_74513_e", Boolean.class);
        return Boolean.TRUE.equals(pressed);
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
        if ("keyBindUseItem".equals(fieldName)) {
            return "field_74313_G";
        }
        return fieldName;
    }

    private static double getSpeed(Object player) {
        return Math.hypot(OpenMaverFeatures.number(player, "motionX", "field_70159_w"),
                OpenMaverFeatures.number(player, "motionZ", "field_70179_y"));
    }

    private static void setSpeed(Object player, double speed, float yawValue) {
        setNumber(player, "motionX", "field_70159_w",
                -Math.sin(Math.toRadians(yawValue)) * speed);
        setNumber(player, "motionZ", "field_70179_y",
                Math.cos(Math.toRadians(yawValue)) * speed);
    }

    private static float getMoveYaw(Minecraft mc, Object player) {
        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        float forward = input == null ? 0.0F
                : (float) OpenMaverFeatures.number(input, "moveForward", "field_78900_b");
        float strafe = input == null ? 0.0F
                : (float) OpenMaverFeatures.number(input, "moveStrafe", "field_78901_c");
        return adjustYaw((float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z"),
                forward, strafe);
    }

    private static int getSpeedLevel(Object player) {
        Object potion = speedPotion();
        if (potion != null && isPotionActive(player, potion)) {
            Object effect = OpenMaverFeatures.call(player, "getActivePotionEffect",
                    "func_70660_b", null, potion);
            Object amplifier = effect == null ? null
                    : OpenMaverFeatures.call(effect, "getAmplifier", "func_76458_c", null);
            if (amplifier instanceof Number) {
                return ((Number) amplifier).intValue() + 1;
            }
        }
        return 0;
    }

    private static boolean isPotionActive(Object player, Object potion) {
        if (potion == null) {
            return false;
        }
        Object value = OpenMaverFeatures.call(player, "isPotionActive", "func_70644_a", null, potion);
        return Boolean.TRUE.equals(value);
    }

    private static Object speedPotion() {
        if (speedPotion == null && !potionLookupFailed) {
            try {
                Class<?> type = Class.forName("net.minecraft.potion.Potion");
                speedPotion = staticField(type, "moveSpeed", "field_76424_c");
            } catch (Throwable ignored) {
                potionLookupFailed = true;
            }
        }
        return speedPotion;
    }

    private static Object staticField(Class<?> type, String mcp, String srg) {
        try {
            return type.getField(mcp).get(null);
        } catch (Throwable ignored) {
            try {
                return type.getField(srg).get(null);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static Object jumpPotion() {
        if (jumpPotion == null && !potionLookupFailed) {
            try {
                Class<?> type = Class.forName("net.minecraft.potion.Potion");
                jumpPotion = staticField(type, "jump", "field_76430_j");
            } catch (Throwable ignored) {
                potionLookupFailed = true;
            }
        }
        return jumpPotion;
    }

    private static void setMoveInput(Object player, float forward, float strafe) {
        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        if (input != null) {
            setNumber(input, "moveForward", "field_78900_b", forward);
            setNumber(input, "moveStrafe", "field_78901_c", strafe);
        }
    }

    private static boolean isAirBelow(Minecraft mc, Object player) {
        AxisAlignedBB box = OpenMaverFeatures.boundingBox(player);
        if (box == null) {
            return false;
        }
        return !collidesEmpty(mc, player, offsetBox(box, 0.0D, -1.0D, 0.0D));
    }

    private static boolean isAirAbove(Minecraft mc, Object player) {
        AxisAlignedBB box = OpenMaverFeatures.boundingBox(player);
        if (box == null) {
            return false;
        }
        return !collidesEmpty(mc, player, offsetBox(box, 0.0D, 1.0D, 0.0D));
    }

    private static boolean canMove(Minecraft mc, Object player, double x, double z, double y) {
        AxisAlignedBB box = OpenMaverFeatures.boundingBox(player);
        return box != null && collidesEmpty(mc, player, offsetBox(box, x, y, z));
    }

    private static boolean collidesEmpty(Minecraft mc, Object player, AxisAlignedBB box) {
        Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
        Object list = OpenMaverFeatures.call(world, "getCollidingBoundingBoxes",
                "func_72945_a", null, player, box);
        if (!(list instanceof List)) {
            return false;
        }
        return ((List<?>) list).isEmpty();
    }

    private static AxisAlignedBB offsetBox(AxisAlignedBB box, double x, double y, double z) {
        return new AxisAlignedBB(box.minX + x, box.minY + y, box.minZ + z,
                box.maxX + x, box.maxY + y, box.maxZ + z);
    }

    private static boolean isNearEdge(Minecraft mc, Object player, double lookAhead) {
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        float moveYaw = getMoveYaw(mc, player);
        double dirX = -Math.sin(Math.toRadians(moveYaw));
        double dirZ = Math.cos(Math.toRadians(moveYaw));
        int floorY = floor(OpenMaverFeatures.number(player, "posY", "field_70163_u") - 1.0D);
        Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
        for (double dist = 0.2D; dist <= lookAhead; dist += 0.3D) {
            int checkX = floor(px + dirX * dist);
            int checkZ = floor(pz + dirZ * dist);
            Object pos = blockPos(checkX, floorY, checkZ);
            Object state = OpenMaverFeatures.call(world, "getBlockState", "func_180495_p", null, pos);
            Object block = state == null ? null
                    : OpenMaverFeatures.call(state, "getBlock", "func_177230_c", null);
            if (block == null || (!isSolid(block) && !isInteractableBlock(block))) {
                return true;
            }
        }
        return false;
    }

    private static Object yawToFacing(float yawValue) {
        if (yawValue < -135.0F || yawValue > 135.0F) {
            return facing(2); // NORTH
        }
        if (yawValue < -45.0F) {
            return facing(5); // EAST
        }
        return yawValue < 45.0F ? facing(3) : facing(4); // SOUTH : WEST
    }

    private static double distanceToEdge(Object player, Object facingObj) {
        int index = facingIndex(facingObj);
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        switch (index) {
            case 2: return pz - Math.floor(pz); // NORTH
            case 5: return Math.ceil(px) - px; // EAST
            case 3: return Math.ceil(pz) - pz; // SOUTH
            default: return px - Math.floor(px); // WEST
        }
    }

    /**
     * 沿单位水平方向走到当前方块边界的距离。
     *
     * <p>用于静止时“在边缘转头”补块：只有玩家离该方向的边缘足够近，
     * 才把相机前方的空气格当作放置目标，避免在平台中央环顾也乱铺。
     */
    private static double edgeDistance(double px, double pz, double dirX, double dirZ) {
        double best = Double.MAX_VALUE;
        if (Math.abs(dirX) > 1.0E-4D) {
            double bound = dirX > 0.0D ? Math.floor(px) + 1.0D : Math.floor(px);
            double distance = (bound - px) / dirX;
            if (distance >= 0.0D) {
                best = Math.min(best, distance);
            }
        }
        if (Math.abs(dirZ) > 1.0E-4D) {
            double bound = dirZ > 0.0D ? Math.floor(pz) + 1.0D : Math.floor(pz);
            double distance = (bound - pz) / dirZ;
            if (distance >= 0.0D) {
                best = Math.min(best, distance);
            }
        }
        return best;
    }

    private static double getRandomOffset() {
        return 0.2155D - randomDouble(0.0001D, 0.0009D);
    }

    private static Object rayTrace(Minecraft mc, Object player, Object world,
            float yawValue, float pitchValue, double distance) {
        double eyeX = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double eyeY = OpenMaverFeatures.number(player, "posY", "field_70163_u")
                + OpenMaverFeatures.eyeHeight(player);
        double eyeZ = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        return rayTraceFrom(world, eyeX, eyeY, eyeZ, yawValue, pitchValue, distance);
    }

    private static Object rayTraceFrom(Object world, double eyeX, double eyeY, double eyeZ,
            float yawValue, float pitchValue, double distance) {
        if (world == null) {
            return null;
        }
        double yawRad = Math.toRadians(yawValue);
        double pitchRad = Math.toRadians(pitchValue);
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);
        Object from = vec3(eyeX, eyeY, eyeZ);
        Object to = vec3(eyeX + lookX * distance, eyeY + lookY * distance,
                eyeZ + lookZ * distance);
        // 1.8.9 的 World.rayTraceBlocks 有三个重载，SRG 名各不相同：
        //   (Vec3,Vec3)            -> func_72933_a
        //   (Vec3,Vec3,Z)          -> func_72901_a
        //   (Vec3,Vec3,Z,Z,Z)      -> func_147447_a
        // 之前误用了 5 参数重载的 SRG，2 参数调用永远找不到方法，rayTrace 恒为 null。
        return OpenMaverFeatures.call(world, "rayTraceBlocks", "func_72933_a", null, from, to);
    }

    private static float[] rotationsTo(double dx, double dy, double dz,
            float currentYaw, float currentPitch) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yawDelta = OpenMaverFeatures.wrapDegrees(
                (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F - currentYaw);
        float pitchDelta = OpenMaverFeatures.wrapDegrees(
                (float) (-Math.atan2(dy, horizontal) * 180.0D / Math.PI) - currentPitch);
        yawDelta = Math.abs(yawDelta) <= 1.0F ? 0.0F : yawDelta;
        pitchDelta = Math.abs(pitchDelta) <= 1.0F ? 0.0F : pitchDelta;
        return new float[]{
                OpenMaverFeatures.wrapDegrees(quantize(currentYaw + yawDelta)),
                Math.max(-90.0F, Math.min(90.0F, quantize(currentPitch + pitchDelta)))
        };
    }

    private static Object clickVec(Minecraft mc, Object world, Object blockPos, Object facingObj) {
        int index = facingIndex(facingObj);
        double x = blockPosX(blockPos) + randomDouble(0.25D, 0.75D);
        double y = blockPosY(blockPos) + randomDouble(0.25D, 0.75D);
        double z = blockPosZ(blockPos) + randomDouble(0.25D, 0.75D);
        switch (index) {
            case 0: y = blockPosY(blockPos); break; // DOWN
            case 1: y = blockPosY(blockPos) + 1.0D; break; // UP
            case 2: z = blockPosZ(blockPos); break; // NORTH
            case 3: z = blockPosZ(blockPos) + 1.0D; break; // SOUTH
            case 4: x = blockPosX(blockPos); break; // WEST
            default: x = blockPosX(blockPos) + 1.0D; break; // EAST
        }
        return vec3(x, y, z);
    }

    private static boolean mopMatches(Object mop, Object blockPos, Object facingObj) {
        if (mop == null) {
            return false;
        }
        Object type = OpenMaverFeatures.read(mop, "typeOfHit", "field_72313_a", null);
        if (type == null || !"BLOCK".equalsIgnoreCase(String.valueOf(type))) {
            return false;
        }
        Object hitPos = OpenMaverFeatures.read(mop, "blockPos", "field_178783_e", null);
        Object side = OpenMaverFeatures.read(mop, "sideHit", "field_178784_b", null);
        return hitPos != null && side == facingObj && blockPosEquals(hitPos, blockPos);
    }

    private static boolean isCreative(Minecraft mc, Object controller) {
        Object gameType = OpenMaverFeatures.call(controller, "getCurrentGameType",
                "func_178889_l", null);
        return gameType != null && "CREATIVE".equalsIgnoreCase(String.valueOf(gameType));
    }

    private static double blockReachDistance(Minecraft mc, Object player) {
        Object controller = OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        Object value = OpenMaverFeatures.call(controller, "getBlockReachDistance",
                "func_78757_d", null);
        return value instanceof Number ? ((Number) value).doubleValue() : 4.5D;
    }

    private static void sendAnimation(Object player) {
        try {
            if (animationPacketConstructor == null) {
                animationPacketType = Class.forName(
                        "net.minecraft.network.play.client.C0APacketAnimation");
                animationPacketConstructor = animationPacketType.getConstructor();
            }
            Object sendQueue = OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
            if (sendQueue != null) {
                OpenMaverFeatures.callVoid(sendQueue, "addToSendQueue", "func_147297_a", null,
                        animationPacketConstructor.newInstance());
            }
        } catch (Throwable ignored) {
            // 动画包不可用时退回 swingItem。
            OpenMaverFeatures.callVoid(player, "swingItem", "func_71038_i", null);
        }
    }

    private static int currentHotbarSlot(Object player) {
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        return (int) OpenMaverFeatures.number(inventory, "currentItem", "field_70461_c");
    }

    private static void setHotbarSlot(Object player, int slot) {
        Object inventory = OpenMaverFeatures.read(player, "inventory", "field_71071_by", null);
        if (inventory != null) {
            setNumber(inventory, "currentItem", "field_70461_c", slot);
        }
    }

    private static void setBooleanField(Object target, String mcp, String srg, boolean value) {
        Field field = OpenMaverFeatures.findField(target,
                OpenMaverFeatures.runtimeCandidates(mcp, srg));
        if (field != null) {
            try {
                field.setAccessible(true);
                field.setBoolean(target, value);
            } catch (Throwable ignored) {
                // 映射差异时忽略。
            }
        }
    }

    private static void setNumber(Object target, String mcp, String srg, double value) {
        OpenMaverFeatures.setNumber(target, mcp, srg, value);
    }

    private static void callVoid(Object target, String mcp, String srg, String obf, Object... args) {
        OpenMaverFeatures.callVoid(target, mcp, srg, obf, args);
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

    private static int blockPosX(Object pos) {
        Object value = OpenMaverFeatures.call(pos, "getX", "func_177958_n", null);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static int blockPosY(Object pos) {
        Object value = OpenMaverFeatures.call(pos, "getY", "func_177956_o", null);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static int blockPosZ(Object pos) {
        Object value = OpenMaverFeatures.call(pos, "getZ", "func_177952_p", null);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static double vecX(Object vec) {
        return OpenMaverFeatures.number(vec, "xCoord", "field_72450_a");
    }

    private static double vecY(Object vec) {
        return OpenMaverFeatures.number(vec, "yCoord", "field_72448_b");
    }

    private static double vecZ(Object vec) {
        return OpenMaverFeatures.number(vec, "zCoord", "field_72449_c");
    }

    private static boolean blockPosEquals(Object a, Object b) {
        return a == b || (a != null && b != null
                && blockPosX(a) == blockPosX(b)
                && blockPosY(a) == blockPosY(b)
                && blockPosZ(a) == blockPosZ(b));
    }

    private static Object offset(Object pos, int facingIndex) {
        return blockPos(blockPosX(pos) + FACING_DELTAS[facingIndex][0],
                blockPosY(pos) + FACING_DELTAS[facingIndex][1],
                blockPosZ(pos) + FACING_DELTAS[facingIndex][2]);
    }

    private static Object facing(int index) {
        if (facings == null) {
            facings = new Object[6];
            try {
                Class<?> type = Class.forName("net.minecraft.util.EnumFacing");
                for (int i = 0; i < 6; i++) {
                    facings[i] = type.getField(FACING_NAMES[i]).get(null);
                }
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

    /** 采样点到目标面中心的曼哈顿距离（0~1），用于延迟放置时的余量偏好。 */
    private static double faceCenterDistance(int facingIdx, double x, double y, double z) {
        switch (facingIdx) {
            case 2: // NORTH
            case 3: // SOUTH
                return Math.abs(x - 0.5D) + Math.abs(y - 0.5D);
            case 4: // WEST
            case 5: // EAST
                return Math.abs(y - 0.5D) + Math.abs(z - 0.5D);
            case 0: // DOWN
            case 1: // UP
                return Math.abs(x - 0.5D) + Math.abs(z - 0.5D);
            default:
                return 0.0D;
        }
    }

    private static double distanceSqToCenter(Object pos, double x, double y, double z) {
        double dx = blockPosX(pos) + 0.5D - x;
        double dy = blockPosY(pos) + 0.5D - y;
        double dz = blockPosZ(pos) + 0.5D - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean holdingBlock(Object player) {
        return OpenMaverFeatures.holdingBlock(player);
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }

    private static String format(double value) {
        return Double.toString(Math.round(value * 1000.0D) / 1000.0D);
    }

    private static String heldItemName(Object player) {
        Object stack = OpenMaverFeatures.call(player, "getHeldItem", "func_70694_bm", null);
        if (stack == null) {
            return "null";
        }
        Object item = OpenMaverFeatures.call(stack, "getItem", "func_77973_b", null);
        return item == null ? "null" : item.getClass().getName();
    }

    private static String describeBlockData(BlockData data) {
        if (data == null) {
            return "null";
        }
        return blockPosX(data.blockPos) + "," + blockPosY(data.blockPos) + ","
                + blockPosZ(data.blockPos) + "/" + FACING_NAMES[facingIndex(data.facing)];
    }

    private static String describePlacement(Placement placement) {
        if (placement == null) {
            return "null";
        }
        return "support=" + blockPosX(placement.supportPos) + ","
                + blockPosY(placement.supportPos) + "," + blockPosZ(placement.supportPos)
                + "/" + FACING_NAMES[facingIndex(placement.facing)]
                + (placement.immediate ? " urgent(queued)" : " queued");
    }

    private static String describeMop(Object mop) {
        if (mop == null) {
            return "null";
        }
        Object type = OpenMaverFeatures.read(mop, "typeOfHit", "field_72313_a", null);
        Object pos = OpenMaverFeatures.read(mop, "blockPos", "field_178783_e", null);
        Object side = OpenMaverFeatures.read(mop, "sideHit", "field_178784_b", null);
        return String.valueOf(type)
                + "@" + (pos == null ? "?" : blockPosX(pos) + "," + blockPosY(pos) + "," + blockPosZ(pos))
                + "/" + (side == null ? "?" : FACING_NAMES[facingIndex(side)]);
    }

    private static float quantize(float angle) {
        return (float) ((double) angle - (double) angle % (double) 0.0096F);
    }

    private static float wrapAngleDiff(float angle, float target) {
        return target + OpenMaverFeatures.wrapDegrees(angle - target);
    }

    private static float clampAngle(float angle, float maxAngle) {
        maxAngle = Math.max(0.0F, Math.min(180.0F, maxAngle));
        if (angle > maxAngle) {
            return maxAngle;
        }
        if (angle < -maxAngle) {
            return -maxAngle;
        }
        return angle;
    }

    private static float copySign(float magnitude, float sign) {
        return Math.copySign(magnitude, sign);
    }

    private static double dotOfDirections(float yaw1, float pitch1, float yaw2, float pitch2) {
        double y1 = Math.toRadians(yaw1);
        double p1 = Math.toRadians(pitch1);
        double y2 = Math.toRadians(yaw2);
        double p2 = Math.toRadians(pitch2);
        double x1 = -Math.sin(y1) * Math.cos(p1);
        double yy1 = -Math.sin(p1);
        double z1 = Math.cos(y1) * Math.cos(p1);
        double x2 = -Math.sin(y2) * Math.cos(p2);
        double yy2 = -Math.sin(p2);
        double z2 = Math.cos(y2) * Math.cos(p2);
        return x1 * x2 + yy1 * yy2 + z1 * z2;
    }

    private static float randomFloat(float min, float max) {
        return min + (float) Math.random() * (max - min);
    }

    private static double randomDouble(double min, double max) {
        return min + Math.random() * (max - min);
    }

    // ==================================================================
    // 配置读写
    // ==================================================================

    public static void load(Properties values) {
        rotations = clamp(readInt(values, "scaffold.rotations", 1), 0, 6);
        moveFix = clamp(readInt(values, "scaffold.moveFix", 1), 0, 1);
        sprintMode = clamp(readInt(values, "scaffold.sprint", 1), 0, 1);
        groundMotion = clamp(readInt(values, "scaffold.groundMotion", 100), 0, 100);
        airMotion = clamp(readInt(values, "scaffold.airMotion", 100), 0, 100);
        speedMotion = clamp(readInt(values, "scaffold.speedMotion", 100), 0, 100);
        keepYonPress = readBoolean(values, "scaffold.keepYonPress", false);
        multiPlace = readBoolean(values, "scaffold.multiPlace", true);
        safeWalk = readBoolean(values, "scaffold.safeWalk", true);
        swing = readBoolean(values, "scaffold.swing", true);
        itemSpoof = readBoolean(values, "scaffold.itemSpoof", false);
        blockCounter = readBoolean(values, "scaffold.blockCounter", true);
        candiffplace = readBoolean(values, "scaffold.candiffplace", true);
        towerFlatTicks = clamp(readInt(values, "scaffold.towerFlatTicks", 4), 0, 20);
        angleDiff = clamp(readInt(values, "scaffold.angleDiff", 50), 0, 180);
        tellyStartRotMinSpeed = clamp(readFloat(values, "scaffold.tellyStartRotMin", 80.0F), 1.0F, 180.0F);
        tellyStartRotMaxSpeed = clamp(readFloat(values, "scaffold.tellyStartRotMax", 85.0F), 1.0F, 180.0F);
        tellyNormalRotMinSpeed = clamp(readFloat(values, "scaffold.tellyNormalRotMin", 30.0F), 1.0F, 180.0F);
        tellyNormalRotMaxSpeed = clamp(readFloat(values, "scaffold.tellyNormalRotMax", 35.0F), 1.0F, 180.0F);
        rotationSpeed = clamp(readInt(values, "scaffold.rotationSpeed", 4), 0, 4);
        tower = clamp(readInt(values, "scaffold.tower", 0), 0, 4);
        keepY = clamp(readInt(values, "scaffold.keepY", 0), 0, 4);
        reset();
    }

    public static String save() {
        return "scaffold.rotations=" + rotations
                + "\nscaffold.moveFix=" + moveFix
                + "\nscaffold.sprint=" + sprintMode
                + "\nscaffold.groundMotion=" + groundMotion
                + "\nscaffold.airMotion=" + airMotion
                + "\nscaffold.speedMotion=" + speedMotion
                + "\nscaffold.keepYonPress=" + keepYonPress
                + "\nscaffold.multiPlace=" + multiPlace
                + "\nscaffold.safeWalk=" + safeWalk
                + "\nscaffold.swing=" + swing
                + "\nscaffold.itemSpoof=" + itemSpoof
                + "\nscaffold.blockCounter=" + blockCounter
                + "\nscaffold.candiffplace=" + candiffplace
                + "\nscaffold.towerFlatTicks=" + towerFlatTicks
                + "\nscaffold.angleDiff=" + angleDiff
                + "\nscaffold.tellyStartRotMin=" + tellyStartRotMinSpeed
                + "\nscaffold.tellyStartRotMax=" + tellyStartRotMaxSpeed
                + "\nscaffold.tellyNormalRotMin=" + tellyNormalRotMinSpeed
                + "\nscaffold.tellyNormalRotMax=" + tellyNormalRotMaxSpeed
                + "\nscaffold.rotationSpeed=" + rotationSpeed
                + "\nscaffold.tower=" + tower
                + "\nscaffold.keepY=" + keepY
                + "\n";
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

    private static float readFloat(Properties values, String key, float fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(Properties values, String key, boolean fallback) {
        String raw = values.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================================================================
    // GUI getter / setter
    // ==================================================================

    public static int getRotations() { return rotations; }
    public static void setRotations(int value) { rotations = clamp(value, 0, 6); reset(); OpenMaverFeatures.markDirty(); }
    public static int getMoveFix() { return moveFix; }
    public static void setMoveFix(int value) { moveFix = clamp(value, 0, 1); OpenMaverFeatures.markDirty(); }
    public static int getSprintMode() { return sprintMode; }
    public static void setSprintMode(int value) { sprintMode = clamp(value, 0, 1); OpenMaverFeatures.markDirty(); }
    public static int getGroundMotion() { return groundMotion; }
    public static void setGroundMotion(int value) { groundMotion = clamp(value, 0, 100); OpenMaverFeatures.markDirty(); }
    public static int getAirMotion() { return airMotion; }
    public static void setAirMotion(int value) { airMotion = clamp(value, 0, 100); OpenMaverFeatures.markDirty(); }
    public static int getSpeedMotion() { return speedMotion; }
    public static void setSpeedMotion(int value) { speedMotion = clamp(value, 0, 100); OpenMaverFeatures.markDirty(); }
    public static boolean isKeepYonPress() { return keepYonPress; }
    public static void setKeepYonPress(boolean value) { keepYonPress = value; OpenMaverFeatures.markDirty(); }
    public static boolean isMultiPlace() { return multiPlace; }
    public static void setMultiPlace(boolean value) { multiPlace = value; OpenMaverFeatures.markDirty(); }
    public static boolean isSafeWalk() { return safeWalk; }
    public static void setSafeWalk(boolean value) { safeWalk = value; OpenMaverFeatures.markDirty(); }
    public static boolean isSwing() { return swing; }
    public static void setSwing(boolean value) { swing = value; OpenMaverFeatures.markDirty(); }
    public static boolean isItemSpoof() { return itemSpoof; }
    public static void setItemSpoof(boolean value) { itemSpoof = value; OpenMaverFeatures.markDirty(); }
    public static boolean isBlockCounter() { return blockCounter; }
    public static void setBlockCounter(boolean value) { blockCounter = value; OpenMaverFeatures.markDirty(); }
    public static boolean isCandiffplace() { return candiffplace; }
    public static void setCandiffplace(boolean value) { candiffplace = value; OpenMaverFeatures.markDirty(); }
    public static int getTowerFlatTicks() { return towerFlatTicks; }
    public static void setTowerFlatTicks(int value) { towerFlatTicks = clamp(value, 0, 20); OpenMaverFeatures.markDirty(); }
    public static int getAngleDiff() { return angleDiff; }
    public static void setAngleDiff(int value) { angleDiff = clamp(value, 0, 180); OpenMaverFeatures.markDirty(); }
    public static float getTellyStartRotMinSpeed() { return tellyStartRotMinSpeed; }
    public static void setTellyStartRotMinSpeed(float value) { tellyStartRotMinSpeed = clamp(value, 1.0F, 180.0F); OpenMaverFeatures.markDirty(); }
    public static float getTellyStartRotMaxSpeed() { return tellyStartRotMaxSpeed; }
    public static void setTellyStartRotMaxSpeed(float value) { tellyStartRotMaxSpeed = clamp(value, 1.0F, 180.0F); OpenMaverFeatures.markDirty(); }
    public static float getTellyNormalRotMinSpeed() { return tellyNormalRotMinSpeed; }
    public static void setTellyNormalRotMinSpeed(float value) { tellyNormalRotMinSpeed = clamp(value, 1.0F, 180.0F); OpenMaverFeatures.markDirty(); }
    public static float getTellyNormalRotMaxSpeed() { return tellyNormalRotMaxSpeed; }
    public static void setTellyNormalRotMaxSpeed(float value) { tellyNormalRotMaxSpeed = clamp(value, 1.0F, 180.0F); OpenMaverFeatures.markDirty(); }
    public static int getRotationSpeed() { return rotationSpeed; }
    public static void setRotationSpeed(int value) { rotationSpeed = clamp(value, 0, 4); OpenMaverFeatures.markDirty(); }
    public static int getTower() { return tower; }
    public static void setTower(int value) { tower = clamp(value, 0, 4); reset(); OpenMaverFeatures.markDirty(); }
    public static int getKeepY() { return keepY; }
    public static void setKeepY(int value) { keepY = clamp(value, 0, 4); reset(); OpenMaverFeatures.markDirty(); }
    public static int getBlockCount() { return blockCount; }

    /**
     * 本 tick 是否会用「静默 aim」覆盖 rotation。
     *
     * <p>Scaffold 的 aim 是在 {@code updatePlayerMoveState()} 里写进
     * {@code rotationYaw/Pitch} 的，比其它模块（Velocity 的 sprint-slap、
     * KillAura）都晚。所以别人如果按**当前** rotation 做射线判定（攻击是否命中
     * Hitbox、C08 是否打在这个面上），那条 C03 实际带出去的角度会与判定的不一致。
     * 这里给它们一个"本 tick 视角会被改"的信号，让它们跳过这一 tick。
     */
    public static boolean isAimingThisTick() {
        return holdPlaceAim || pendingPlace != null || (hasAim && aimReady);
    }

    private static final class BlockData {
        private final Object blockPos;
        private final Object facing;

        private BlockData(Object blockPos, Object facing) {
            this.blockPos = blockPos;
            this.facing = facing;
        }
    }

    /**
     * 一次“已验证”的放置瞄准：hitVec 确实是由 (yaw, pitch) 射线命中
     * blockPos/facing 得到的。该角度会先进入 C03，下一 tick 再用同一角度放置。
     */
    private static final class AimHit {
        private final Object hitVec;
        private final float yaw;
        private final float pitch;

        private AimHit(Object hitVec, float yaw, float pitch) {
            this.hitVec = hitVec;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    /** 延迟一 tick 执行的放置目标；aim 已在本 tick 的 C03 中发给服务端。 */
    private static final class PendingPlace {
        private final Object targetPos;
        private final Object supportPos;
        private final Object facing;
        private final AimHit hit;
        /** 没加微噪声的基准角度（重算 aim、移动修正用）。 */
        private final float yaw;
        private final float pitch;
        /** 真正写进 C03 的角度：flush 用它做 rayTrace，才和服务端 rotation 一致。 */
        private float sentYaw;
        private float sentPitch;
        /** 已经重新瞄准过几次（{@link #MAX_PENDING_RETRIES} 是上限）。 */
        private int retries;
        /** 该 pending 的 aim 是否真的写进了上一 tick 的 C03。 */
        private boolean sent;

        private PendingPlace(Object targetPos, Object supportPos, Object facing, AimHit hit,
                float yaw, float pitch) {
            this.targetPos = targetPos;
            this.supportPos = supportPos;
            this.facing = facing;
            this.hit = hit;
            this.yaw = yaw;
            this.pitch = pitch;
            this.sentYaw = yaw;
            this.sentPitch = pitch;
        }

        private PendingPlace withAim(AimHit hit, float yaw, float pitch) {
            return new PendingPlace(targetPos, supportPos, facing, hit, yaw, pitch);
        }
    }

    /** 一次已验证的放置方案：目标空气格、支撑方块、点击面与命中点。 */
    private static final class Placement {
        private final Object targetPos;
        private final Object supportPos;
        private final Object facing;
        private final AimHit hit;
        private final boolean predicted;
        /**
         * 玩家已经站在空气上（这一 tick 不放就会掉下去）。
         *
         * <p>只影响优先级/日志：{@link #tick} 对 immediate 与普通目标一样排队，
         * 因为抢在 C03 之前发 C08 是 pre-flying 形态，会被 Grim 的
         * {@code onBlockPlace} 直接 {@code place.resync()} 取消（RotationPlace）。
         */
        private boolean immediate;

        private Placement(Object targetPos, Object supportPos, Object facing, AimHit hit,
                boolean predicted) {
            this.targetPos = targetPos;
            this.supportPos = supportPos;
            this.facing = facing;
            this.hit = hit;
            this.predicted = predicted;
            this.immediate = !predicted;
        }
    }

    private static final class SupportFace {
        private final Object supportPos;
        private final int faceIndex;
        private final double distanceSq;

        private SupportFace(Object supportPos, int faceIndex, double distanceSq) {
            this.supportPos = supportPos;
            this.faceIndex = faceIndex;
            this.distanceSq = distanceSq;
        }
    }
}
