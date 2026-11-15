import module.Module;
import module.ModuleManager;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/** Minecraft-side aim and living-entity outline features. */
public final class OpenMaverFeatures {

    private static boolean aimEnabled;
    private static boolean espEnabled;
    private static int settingsCheckTicks;
    private static long settingsModified = Long.MIN_VALUE;
    private static boolean settingsDirty;

    // ==================================================================
    // Grim 对测 profile
    // ==================================================================

    /** profile 档位：默认 balanced；aggressive 只用于对比测试。 */
    public static final int PROFILE_LEGIT = 0;
    public static final int PROFILE_BALANCED = 1;
    public static final int PROFILE_AGGRESSIVE = 2;

    private static final String[] PROFILE_KEYS = {"legit", "balanced", "aggressive"};
    private static final String[] PROFILE_NAMES = {"LEGIT", "BALANCED", "AGGRESSIVE"};

    private static boolean bypassEnabled = true;
    private static int bypassProfile = PROFILE_BALANCED;
    private static boolean debugEnabled;
    /** START 阶段发生反射/类型错误时只停用该阶段，避免拖垮整个 tick 桥。 */
    private static boolean tickStartFailed;

    /**
     * Grim 对 1.8 客户端的 Reach 语义：目标 AABB 先扩张
     * {@code Reach.threshold(0.0005) + 1.8 hitbox(0.1) + movementThreshold(0.03)}，
     * 然后要求眼睛到扩张后 AABB 的距离不超过 3.0。
     */
    private static final double REACH_GRIM_BOX_MARGIN = 0.1305D;
    private static final double REACH_VANILLA_BOX_MARGIN = 0.1D;
    /** profile 允许的 Grim 扩张箱距离上限；aggressive 仅用于对比。 */
    private static final double REACH_SAFE_CAP = 3.0D;
    private static final double REACH_AGGRESSIVE_CAP = 3.30D;

    // Aim Assist（数值范围与 GUI 滑块一致）
    private static float aimSpeed = 0.35F;
    private static float aimRange = 6.0F;
    private static float aimFov = 180.0F;
    private static int aimMode = 0;       // 0 = Smooth, 1 = Instant
    private static int aimTarget = 0;     // 0 = Head, 1 = Chest, 2 = Body
    private static boolean aimRequireClick = true;
    /** 将 rotation 步长量化到当前鼠标灵敏度对应的 GCD，避免非人类步长。 */
    private static boolean aimGcdEnabled = true;
    /** 单 tick 最大 rotation 步长（度）；profile 会再收紧。 */
    private static float aimMaxStep = 35.0F;
    /**
     * 渲染帧级 aim 状态：每 tick 的 delta 上限仍然生效，但步进分摊到每一帧，
     * 避免 20 TPS 下“每 tick 跳一下”的卡顿感。
     */
    private static float aimAppliedYaw;
    private static float aimAppliedPitch;
    /** GCD 量化残差，跨帧累积，保证每 tick 的服务端 delta 仍是 GCD 的倍数。 */
    private static float aimResidualYaw;
    private static float aimResidualPitch;
    private static long aimLastFrameNanos;
    /** 每个 tick 选一次目标；渲染帧只做平滑插值，避免逐帧遍历实体造成掉帧。 */
    private static boolean aimHasTarget;
    private static float aimCachedYaw;
    private static float aimCachedPitch;
    private static EntityLivingBase aimCachedPlayer;
    private static float aimCachedGcd;

    // Player ESP
    private static float espRange = 48.0F;
    private static float espThickness = 1.5F;
    private static float espOpacity = 0.9F;
    private static int espColor = 0;      // 0 = Orange, 1 = Cyan, 2 = Violet, 3 = Red
    private static boolean espPlayersOnly;

    // Target HUD
    private static float targetHudRange = 32.0F;
    private static float targetHudScale = 1.0F;
    private static boolean targetHudPlayersOnly;

    // Player X-Ray
    private static float playerXrayRange = 64.0F;
    private static int playerXrayColor = 0;      // 0 = Health, 1 = Orange, 2 = Cyan, 3 = Violet, 4 = Red

    // AutoSprint
    private static boolean sprintOnlyForward = true;
    private static boolean sprintPauseSneak = true;
    private static boolean sprintPauseItem = true;

    // AutoClicker
    private static int autoclickerCpsMin = 6;
    private static int autoclickerCpsMax = 10;
    private static boolean autoclickerLeft = true;
    private static boolean autoclickerRight = false;
    /**
     * 默认要求按住鼠标：模块只负责“按住时连点”，不会在玩家没有输入时
     * 继续向服务端发送点击。v2 曾把默认值迁移成 false，结果按一次 R
     * 后即使不碰鼠标也会一直攻击，这里恢复安全默认值。
     */
    private static boolean autoclickerRequireHold = true;
    /**
     * 准星在实体上时，等目标的受击无敌帧结束再发送真正的攻击。
     *
     * <p>1.8.9 的 {@code EntityLivingBase.hurtResistantTime} 会让 10 tick 内的
     * 重复攻击不造成伤害；高速连点会把大量“无效 C02”发给服务端，测试假人
     * 上就会表现为“预期伤害”和“实际伤害”对不上。开启后只有真正能造成伤害
     * 时才发 C02，等待期间不再发 C0A（1.9+ 服务端会用挥击动画重置蓄力）。
     */
    private static boolean autoclickerWaitForHurt = true;
    /**
     * 攻击节奏模式：
     * <ul>
     *   <li>{@link #ATTACK_MODE_FULL_CHARGE}：按武器 1.9+ 冷却打满蓄力，单次 1.0 倍；</li>
     *   <li>{@link #ATTACK_MODE_FASTEST}：只按 10 tick 无敌帧节流，约 2 次/秒，
     *       单次倍率较低但近战对拼时压迫感更强；</li>
     *   <li>{@link #ATTACK_MODE_VANILLA}：原版 C0A -> C02 顺序 + 旧版节流，
     *       适合 1.8 `/server legacy`（没有攻击冷却，Grim 1.8 还要求先挥手）。</li>
     * </ul>
     */
    private static final int ATTACK_MODE_FULL_CHARGE = 0;
    private static final int ATTACK_MODE_FASTEST = 1;
    private static final int ATTACK_MODE_VANILLA = 2;
    private static final String[] ATTACK_MODE_KEYS = {"full", "fast", "vanilla"};
    private static final String[] ATTACK_MODE_NAMES = {"FULL CHARGE", "FASTEST", "VANILLA"};
    private static int autoclickerAttackMode = ATTACK_MODE_FULL_CHARGE;
    private static boolean autoclickerHumanize = true;

    /** openmaver.properties 的结构版本，用于迁移旧默认值。 */
    private static final int SETTINGS_VERSION = 6;

    /**
     * 1.8.9 服务端的受击无敌窗口是 10 tick（hurtResistantTime 20 -> 10 才会结算）。
     * 用 11 tick 作为下限，给网络 / tick 对齐留 1 tick 余量：卡在 500ms 边界上
     * 的 C02 会有一半概率落在无敌帧最后一 tick 里被服务端直接丢弃。
     */
    private static final long AUTOCLICKER_HURT_TICKS = 11L;

    /** 下一次允许点击的时间（System.nanoTime）；0 表示尚未排程。 */
    private static long autoclickerLeftNextNanos;
    private static long autoclickerRightNextNanos;
    /** 人类化停顿截止时间；期间不发任何点击。 */
    private static long autoclickerPauseUntilNanos;
    /** 连续空挥计数，用于在 legit/balanced 下暂停无意义空挥。 */
    private static int autoclickerMissStreak;
    /** 本 tick 是否已经产生过一次点击，避免左右键同 tick 多动作。 */
    private static boolean autoclickerClickedThisTick;
    private static long autoclickerLastClickNanos;
    /** 上一次真正攻击到实体的时间；用于 WAIT FOR HURT 的 10 tick 兜底间隔。 */
    private static long autoclickerLastEntityAttackNanos;
    /** 上一次真正攻击到实体的客户端 tick；比墙上时钟更贴近服务端无敌帧。 */
    private static long autoclickerLastEntityAttackTick = Long.MIN_VALUE;
    private static final Random AUTOCLICKER_RANDOM = new Random();

    // Reach（攻击距离）
    private static float reachExtra = 0.0F;
    private static float reachChance = 100.0F;
    /** 上一次 tick 是否处于挥击动画中，用于识别“新的一次挥击”。 */
    private static boolean reachWasSwinging;
    private static int reachLastSwingProgress = Integer.MIN_VALUE;
    private static final Random REACH_RANDOM = new Random();
    /** 1.8.9 原版实体目标上限（EntityRenderer.getMouseOver 的硬编码）。 */
    private static final double REACH_BASE_DISTANCE = 3.0D;
    /** 关闭 bypass 时保留旧的对比行为；默认 profile 下不会用到。 */
    private static final double REACH_MAX_DISTANCE = 5.9D;

    // ==================================================================
    // Criticals（ALWAYS 刀暴 / 强制暴击）
    // ==================================================================

    /** 刀暴模式：0=ALWAYS(packet), 1=NCP, 2=FALLING, 3=GRIM（仅空中）, 4=JUMP（真实跳跃）, 5=LOW, 6=DOWN。 */
    private static final int CRITICALS_PACKET = 0;
    private static final int CRITICALS_NCP = 1;
    private static final int CRITICALS_FALLING = 2;
    private static final int CRITICALS_GRIM = 3;
    private static final int CRITICALS_JUMP = 4;
    /** LiquidBounce（水影）的 1e-9 级微型位移模式：低于 Grim 0.001 的 Simulation 阈值。 */
    private static final int CRITICALS_LOW = 5;
    private static final int CRITICALS_DOWN = 6;
    private static final int CRITICALS_LAST = CRITICALS_DOWN;
    private static final String[] CRITICALS_MODE_KEYS = {
            "packet", "ncp", "falling", "grim", "jump", "low", "down"};
    private static final String[] CRITICALS_MODE_NAMES = {
            "ALWAYS", "NCP", "FALLING", "GRIM", "JUMP", "LOW", "DOWN"};

    private static final int CRITICALS_ATTACK_AUTOCLICKER = 1;
    private static final int CRITICALS_ATTACK_REACH = 2;

    /**
     * 默认 JUMP：只有真实起跳后的下落才会暴击，不发送任何伪造移动包。
     *
     * <p>Matrix 的 {@code ka.critical.ground / hop / modify} 与 {@code bp.timer} 都是
     * 由“每次攻击额外补 2~3 条 C04”直接产生的；在默认 balanced 档下这类 packet
     * 模式会被降级为 JUMP，避免一开模块就连续吃 flag。
     */
    private static int criticalsMode = CRITICALS_JUMP;
    /** 1.9+ 服务端冲刺会取消暴击：攻击前 STOP_SPRINTING，攻击后恢复。 */
    private static boolean criticalsStopSprint = true;
    /** 两次刀暴包之间的最小间隔；默认 0，表示每次可结算的攻击都发。 */
    private static long criticalsDelayMs = 0L;
    private static long criticalsLastAttackNanos;
    /** STOP_SPRINTING 之前的冲刺状态，用于攻击后恢复。 */
    private static boolean criticalsSprintWasActive;
    /** JUMP 模式等待进入下落阶段的攻击状态。 */
    private static Object criticalsJumpTarget;
    private static int criticalsJumpAttackSource;
    private static long criticalsJumpDeadlineNanos;
    private static boolean criticalsAttackDeferred;

    /** 反射缓存：C04 / C06 / C0B$Action，避免每个 tick 重复 Class.forName。 */
    private static Class<?> criticalsPositionPacketType;
    private static Constructor<?> criticalsPositionConstructor;
    private static Class<?> criticalsPosLookPacketType;
    private static Constructor<?> criticalsPosLookConstructor;
    private static Class<?> criticalsEntityActionPacketType;
    private static Constructor<?> criticalsEntityActionConstructor;
    private static Class<?> criticalsActionType;
    private static Object criticalsStopSprintAction;
    private static Object criticalsStartSprintAction;
    private static boolean criticalsPositionLookupFailed;
    private static boolean criticalsPosLookLookupFailed;
    private static boolean criticalsEntityActionLookupFailed;
    /** 反射失败只记录一次，避免每 tick 刷屏。 */
    private static boolean criticalsFailureLogged;

    // FastPlace
    private static int fastPlaceDelayTicks = 4;
    private static boolean fastPlaceOnlyBlocks = true;
    private static boolean fastPlaceWasActive;
    private static boolean fastPlaceFailed;
    private static Class<?> itemBlockType;
    private static boolean itemBlockLookupFailed;

    /** 本 tick 是否已经有右键动作（AutoClicker），避免同一 tick 连续放置。 */
    private static boolean rightClickUsedThisTick;
    /** 上次成功放置的 tick，用于 debug 中的放置间隔。 */
    private static long lastPlacementTick = Long.MIN_VALUE;
    private static long clientTick;

    // GodBridge（神桥）
    private static int godBridgeDelayTicks = 0;
    private static boolean godBridgeFastPlace = true;
    private static boolean godBridgeRequireEdgeAim = true;
    private static boolean godBridgeAutoRotate = true;
    /**
     * 只用玩家真实准星放置：不自动转视角，也不额外发飞行包。
     * 这是当前对 Grim 最干净的模式；自动旋转/发包模式保留用于对比测试。
     */
    private static boolean godBridgeLegitAim = true;
    /** 通过 C05 视角包让服务端在收到 C08 前更新 yaw/pitch（实验性）。 */
    private static boolean godBridgePacketRotation;
    private static Class<?> godBridgeLookPacketType;
    private static java.lang.reflect.Constructor<?> godBridgeLookPacketConstructor;
    private static int godBridgeCooldownTicks;
    /** 起桥/落地时记录的桥面 Y，跳跃期间沿用它，避免原地跳一次就搭高一层。 */
    private static int godBridgeGroundY = Integer.MIN_VALUE;
    private static boolean godBridgeArmed;
    private static boolean godBridgeActive;
    private static boolean godBridgeTimerOwned;
    private static boolean godBridgeFailed;
    /** 鼠标命中点距离方块边缘小于该值时，视为“瞄准方块边缘”。 */
    private static final double GOD_BRIDGE_EDGE_TOLERANCE = 0.30D;

    private static Class<?> scaffoldBlockPosType;
    private static java.lang.reflect.Constructor<?> scaffoldBlockPosConstructor;
    private static Object[] scaffoldFacings;

    private static final String[] SCAFFOLD_FACING_NAMES = {
        "DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"
    };
    private static final int[][] SCAFFOLD_FACING_OFFSETS = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    private static final float[][] ESP_COLORS = {
        {1.0F, 0.42F, 0.22F},
        {0.36F, 0.88F, 1.0F},
        {0.69F, 0.55F, 1.0F},
        {1.0F, 0.35F, 0.40F}
    };

    private static final float[][] XRAY_COLORS = {
        {0.36F, 0.94F, 0.69F},
        {1.0F, 0.42F, 0.24F},
        {0.36F, 0.88F, 1.0F},
        {0.69F, 0.55F, 1.0F},
        {1.0F, 0.35F, 0.40F}
    };

    // FakePlayer（假人玩家，仅客户端生成 EntityOtherPlayerMP，不发包给服务端）
    private static int fakePlayerCount = 1;
    private static float fakePlayerDistance = 4.0F;
    private static boolean fakePlayerShowName = true;
    private static final List<EntityOtherPlayerMP> FAKE_PLAYERS = new ArrayList<EntityOtherPlayerMP>();
    /** 当前假人所属的世界；切换世界时只清空列表，避免触碰已卸载的旧世界。 */
    private static WorldClient fakePlayerWorld;
    /** 假人实体 ID 从高数值开始，避开服务端真实实体的 ID。 */
    private static int fakePlayerNextId = 1_000_000_000;
    /** 上次生成时的设置，用于 COUNT / DISTANCE 变化时重建假人。 */
    private static int fakePlayerSpawnedCount = -1;
    private static float fakePlayerSpawnedDistance = -1.0F;
    private static boolean fakePlayerFailed;

    private OpenMaverFeatures() {
    }

    /**
     * tick 开始阶段：在 vanilla 处理鼠标输入和发送 C03 移动包之前执行
     * AutoClicker / AutoSprint / Aim。
     *
     * <p>rotation 的平滑放在渲染帧里；本 tick 的 C03 使用上一窗口的帧步进结果，
     * 本阶段注入的 C02/C08 也出现在同一条 C03 之前，满足 Grim Post 的顺序要求。
     */
    public static void onTickStart(Minecraft mc) {
        // 先重置本 tick 的鼠标动作状态。AutoClicker 必须在 vanilla 发送 C03
        // 移动包之前执行：Grim 的 Post 检查要求 C02/C08 出现在移动包之前，
        // 放到 END 阶段注入会被服务端以 “failed Post: attack/place” 拒绝。
        rightClickUsedThisTick = false;
        autoclickerClickedThisTick = false;
        if (tickStartFailed) {
            processPendingCriticalJump(mc);
            updateAutoClicker(mc);
            suppressVanillaDuplicateAttack(mc);
            return;
        }
        try {
            refreshSettings();
            clientTick++;
            processPendingCriticalJump(mc);
            updateAutoClicker(mc);
            suppressVanillaDuplicateAttack(mc);
            boolean screenOpen = read(mc, "currentScreen", "field_71462_r", null) != null;
            if (screenOpen) {
                aimHasTarget = false;
                return;
            }
            updateAutoSprint(mc);
            updateAimTarget(mc);
            // 上一窗口的帧步进已经作为本 tick 的 C03 delta 发出；
            // 从这里开始的新窗口步进会进入下一 tick 的 C03。
            aimAppliedYaw = 0.0F;
            aimAppliedPitch = 0.0F;
        } catch (Throwable error) {
            tickStartFailed = true;
            System.err.println("[OpenMaver] Aim/AutoSprint start phase disabled: " + error);
        }
    }

    public static void onTick(Minecraft mc) {
        updateFakePlayers(mc);
        // AutoSprint / Aim / AutoClicker 已在 onTickStart 处理。
        updateFastPlace(mc);
        // GodBridge 放置成功后会置位 rightClickUsedThisTick，避免同 tick 重复右键。
        updateGodBridge(mc);
        // Reach 放在最后：AutoClicker 可能在本 tick 内产生新的挥击，需要立即识别。
        updateReach(mc);
        OpenMaverDebug.tickSummary();
    }

    /**
     * AutoSprint 的真实实现放在 MC 侧：core 里的 AutoSprint 模块只负责开关状态，
     * 因为它可能在独立模式/agent bootstrap 里加载，不能直接引用 net.minecraft 类。
     */
    private static void updateAutoSprint(Minecraft mc) {
        Module autoSprint = ModuleManager.getModule("AutoSprint");
        if (autoSprint == null || !autoSprint.isEnabled()) {
            return;
        }
        Object player = read(mc, "thePlayer", "field_71439_g", null);
        if (player == null) {
            return;
        }
        Object input = read(player, "movementInput", "field_71158_b", null);
        if (input == null) {
            return;
        }
        double forward = number(input, "moveForward", "field_78900_b");
        Object sneak = read(input, "sneak", "field_78899_d", Boolean.class);
        if (sprintOnlyForward && forward <= 0.0D) {
            return;
        }
        if (sprintPauseSneak && Boolean.TRUE.equals(sneak)) {
            return;
        }
        Object usingItem = call(player, "isUsingItem", "func_71039_bw", null);
        if (sprintPauseItem && Boolean.TRUE.equals(usingItem)) {
            return;
        }
        call(player, "setSprinting", "func_70031_b", null, Boolean.TRUE);
    }

    /**
     * AutoClicker 的真实实现：在安全的游戏状态下按配置 CPS 调用游戏自身的
     * clickMouse / rightClickMouse。
     *
     * <p>Grim 相关约束：
     * <ul>
     *   <li>同一 tick 最多一次点击（左右键互斥），避免 MultiActions 类检查；</li>
     *   <li>使用物品时完全不点击，避免 attack/swing while using；</li>
     *   <li>legit/balanced 限制 CPS 上限并加入抖动、偶发停顿；</li>
     *   <li>连续 MISS 空挥达到阈值时主动暂停，不做无目标刷包。</li>
     * </ul>
     */
    private static void updateAutoClicker(Minecraft mc) {
        try {
            Module autoClicker = ModuleManager.getModule("AutoClicker");
            boolean active = autoClicker != null && autoClicker.isEnabled();
            Object player = read(mc, "thePlayer", "field_71439_g", null);
            Object world = read(mc, "theWorld", "field_71441_e", null);
            boolean canClick = active
                    && read(mc, "currentScreen", "field_71462_r", null) == null
                    && player != null
                    && world != null
                    && alive(player);
            if (!canClick) {
                resetAutoClickerTimers();
                return;
            }

            long now = System.nanoTime();
            if (autoclickerPauseUntilNanos != 0L) {
                if (now < autoclickerPauseUntilNanos) {
                    return;
                }
                autoclickerPauseUntilNanos = 0L;
            }
            // JUMP 模式正在等待玩家进入下落阶段：此时不能再走普通点击调度，
            // 否则会在 pending 攻击完成前反复起跳/延迟。
            if (criticalsAttackDeferred) {
                return;
            }

            // 使用物品时攻击/挥击都会被 Grim 的 MultiActionsA/E 抓；先完全停手。
            boolean usingItem = Boolean.TRUE.equals(
                    call(player, "isUsingItem", "func_71039_bw", "bS"));
            boolean leftHeld = Mouse.isButtonDown(0);
            boolean rightHeld = Mouse.isButtonDown(1);
            boolean wantLeft = autoclickerLeft && !usingItem
                    && (!autoclickerRequireHold || leftHeld);
            boolean wantRight = autoclickerRight
                    && (!autoclickerRequireHold || rightHeld);

            if (!wantLeft) {
                autoclickerLeftNextNanos = 0L;
            } else {
                if (autoclickerLeftNextNanos == 0L) {
                    autoclickerLeftNextNanos = now + autoClickerIntervalNanos();
                } else if (now >= autoclickerLeftNextNanos && !autoclickerClickedThisTick) {
                    criticalsAttackDeferred = false;
                    if (tryAutoLeftClick(mc, now)) {
                        autoclickerClickedThisTick = true;
                    }
                    // 固定节拍：从上一次“计划时间”推进，而不是从 now 重算。
                    // tick 只有 20Hz，14~17 CPS 的间隔（约 59~71ms）大于 50ms；
                    // 从 now 重算会把每次点击都推到下一个 tick，实际只剩 6~10 CPS。
                    autoclickerLeftNextNanos = nextClickDeadline(autoclickerLeftNextNanos, now);
                    return;
                }
            }

            if (!wantRight) {
                autoclickerRightNextNanos = 0L;
            } else if (!autoclickerClickedThisTick) {
                if (autoclickerRightNextNanos == 0L) {
                    autoclickerRightNextNanos = now + autoClickerIntervalNanos();
                } else if (now >= autoclickerRightNextNanos) {
                    if (tryAutoRightClick(mc, now)) {
                        autoclickerClickedThisTick = true;
                    }
                    autoclickerRightNextNanos = nextClickDeadline(autoclickerRightNextNanos, now);
                }
            }
        } catch (Throwable ignored) {
            // 反射失败时静默复位，避免每 tick 抛异常/刷屏影响游戏。
            resetAutoClickerTimers();
        }
    }

    /**
     * 拦住原版「按住左键」路径在同一 tick 额外补发的 C02。
     *
     * <p>1.8.9 的 {@code Minecraft.runTick} 只要左键按住且
     * {@code leftClickCounter == 0} 就会再调一次 {@code clickMouse()}，
     * 而 {@code clickMouse()} 命中实体时不会重置这个计数器。结果 AutoClicker
     * 的 WAIT FOR HURT 只限制了它自己注入的点击，服务端仍然每 tick 收到一条
     * 攻击包（约 20 CPS）。在 1.9+ 子服上这会让服务端的攻击冷却读数一直贴在
     * 0 附近，真正结算的那一下伤害极低，看起来就像「连点伤害全无效」。
     *
     * <p>这里只在准星确实指着实体时把计数器抬到 2：原版递减后仍 > 0，
     * 「按住攻击」分支会被跳过；AutoClicker 自己真正攻击前会再清回 0，
     * 因此既不影响挖方块，也能保证每个受击窗口最多一条有效 C02。
     */
    private static void suppressVanillaDuplicateAttack(Minecraft mc) {
        try {
            Module autoClicker = ModuleManager.getModule("AutoClicker");
            if (autoClicker == null || !autoClicker.isEnabled() || !autoclickerLeft) {
                return;
            }
            if (autoclickerRequireHold && !Mouse.isButtonDown(0)) {
                return;
            }
            Object hit = read(mc, "objectMouseOver", "field_71476_x", null);
            if (hit == null || !isEntityHit(hit)) {
                return;
            }
            writeIntField(mc, "leftClickCounter", "field_71429_W", 2);
        } catch (Throwable ignored) {
            // objectMouseOver 在切世界时可能为空，忽略即可。
        }
    }

    /**
     * 下一次攻击还要等多久（tick）。
     *
     * <p>两个约束取较大值：
     * <ul>
     *   <li>目标受击无敌帧：1.8.9 / 1.9 都是 10 tick，所以最少 11 tick；</li>
     *   <li>1.9+ 攻击冷却：需要 {@code 20 / attackSpeed} tick 才能打出 1.0 倍伤害。</li>
     * </ul>
     * 1.8 客户端看不到 1.9 的 ATTACK_SPEED 属性，这里按原版物品类别近似。
     */
    private static long autoclickerAttackTicks(Object player) {
        long ticks = AUTOCLICKER_HURT_TICKS;
        if (autoclickerAttackMode != ATTACK_MODE_FULL_CHARGE) {
            return ticks;
        }
        double speed = heldItemAttackSpeed(player);
        if (speed > 0.0D) {
            // 1.9 的蓄力公式带 +0.5 tick 的 partial tick，因此 ceil(period)
            // 就能到 1.0；留 0~1 tick 的网络抖动余量即可，不必额外多等。
            ticks = Math.max(ticks, (long) Math.ceil(20.0D / speed));
        }
        return ticks;
    }

    private static double heldItemAttackSpeed(Object player) {
        try {
            Object stack = call(player, "getHeldItem", "func_70694_bm", null);
            Object item = stack == null ? null
                    : call(stack, "getItem", "func_77973_b", null);
            if (item == null) {
                return 4.0D; // 空手
            }
            if (itemIs(item, "ItemSword")) {
                return 1.6D;
            }
            if (itemIs(item, "ItemAxe")) {
                // 1.9 斧攻速：木/石 0.8、铁 0.9、钻石/金 1.0。1.8 客户端看不到
                // ATTACK_SPEED，只能用耐久近似分辨材质。
                Object maxDamage = call(stack, "getMaxDamage", "func_77952_i", null);
                int durability = maxDamage instanceof Number
                        ? ((Number) maxDamage).intValue() : 0;
                if (durability >= 1000 || (durability > 0 && durability <= 40)) {
                    return 1.0D;
                }
                return durability >= 200 ? 0.9D : 0.8D;
            }
            if (itemIs(item, "ItemPickaxe")) {
                return 1.2D;
            }
            if (itemIs(item, "ItemSpade")) {
                return 1.0D;
            }
            if (itemIs(item, "ItemHoe")) {
                return 1.0D;
            }
        } catch (Throwable ignored) {
            // 映射差异时按空手处理，至少保留 11 tick 无敌帧节流。
        }
        return 4.0D;
    }

    private static boolean itemIs(Object item, String simpleName) {
        for (Class<?> type = item.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            // 运行期是 MCP 类名（ItemSword）；自定义子类会继续往父类找。
            if (simpleName.equals(type.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 目标是否还处在受击无敌帧 / 1.9+ 攻击冷却内。
     *
     * <p>服务端的无敌帧判定是 {@code hurtResistantTime > maxHurtResistantTime / 2}：
     * 从 20 开始每 tick 减 1，减到 10 才重新接受伤害。按 tick 计比墙上时钟更贴近
     * 服务端；切世界后用 500ms 墙钟兜底。
     */
    private static boolean entityHurtWindowActive(long now, long requiredTicks) {
        long ticks = Math.max(AUTOCLICKER_HURT_TICKS, requiredTicks);
        if (autoclickerLastEntityAttackTick != Long.MIN_VALUE) {
            long elapsedTicks = clientTick - autoclickerLastEntityAttackTick;
            if (elapsedTicks >= 0L && elapsedTicks < ticks) {
                return true;
            }
        }
        long requiredNanos = Math.max(500_000_000L, ticks * 50_000_000L);
        return autoclickerLastEntityAttackNanos != 0L
                && now - autoclickerLastEntityAttackNanos < requiredNanos;
    }

    private static boolean tryAutoLeftClick(Minecraft mc, long now) {
        criticalsAttackDeferred = false;
        Object hit = read(mc, "objectMouseOver", "field_71476_x", null);
        Object hitType = hit == null ? null : read(hit, "typeOfHit", "field_72313_a", null);
        boolean miss = hitType == null || "MISS".equals(String.valueOf(hitType));
        if (miss) {
            autoclickerMissStreak++;
            // 仅 LEGIT 严格档在连续空挥后主动停一小段；BALANCED/AGGRESSIVE
            // 保持玩家设置的 CPS，避免在打空/测试 CPS 时被暂停压到 10 以下。
            if (bypassEnabled && bypassProfile == PROFILE_LEGIT && autoclickerMissStreak > 6) {
                autoclickerMissStreak = 0;
                autoclickerPauseUntilNanos = now + 250_000_000L
                        + (long) (AUTOCLICKER_RANDOM.nextDouble() * 250_000_000L);
                OpenMaverDebug.suppressed("autoclicker pause after repeated MISS");
                return false;
            }
        } else {
            autoclickerMissStreak = 0;
        }
        // 目标还在受击无敌帧 / 1.9+ 攻击冷却里时，不做任何点击注入：
        // - C02 会被服务端当作伤害无效或按低蓄力结算；
        // - C0A 也不能发：1.9+ 服务端把任何挥击动画都当成一次攻击并重置
        //   AttackStrengthTicker（MC-255058），等待期间的挥手会把后面的真正
        //   C02 压到 0.2 倍伤害。CPS HUD 仍按配置显示。
        boolean entityHit = !miss && isEntityHit(hit);
        long interval = clickIntervalMillis(now);
        Object player = read(mc, "thePlayer", "field_71439_g", null);
        long waitTicks = autoclickerAttackMode == ATTACK_MODE_FULL_CHARGE
                ? autoclickerAttackTicks(player) : AUTOCLICKER_HURT_TICKS;
        if (entityHit
                && (autoclickerAttackMode != ATTACK_MODE_VANILLA || autoclickerWaitForHurt)
                && (entityHurtWindowActive(now, waitTicks)
                        || !targetReadyForDamage(hit))) {
            if (autoclickerAttackMode == ATTACK_MODE_VANILLA) {
                // 1.8 服务端不会因为挥手重置蓄力，保留旧版的挥手动画与 CPS 手感。
                callVoid(player, "swingItem", "func_71038_i", null);
            }
            StatsHud.recordAutoClick(true, now);
            OpenMaverDebug.click("wait", interval, autoclickerCpsMax,
                    Mouse.isButtonDown(0), autoclickerMissStreak);
            autoclickerLastClickNanos = now;
            return true;
        }
        // Packet 刀暴必须在真正的 C02 之前发送：伪造移动包 -> clickMouse -> 恢复冲刺。
        // 等待蓄力的分支没有 C02，因此那里不调用 Criticals。
        Object target = entityHit ? read(hit, "entityHit", "field_72308_g", null) : null;
        boolean criticalsStopSprintSent = false;
        if (target instanceof EntityLivingBase && alive(target)) {
            criticalsStopSprintSent = applyCriticals(mc, player, target,
                    CRITICALS_ATTACK_AUTOCLICKER);
        }
        if (criticalsAttackDeferred) {
            return false;
        }
        // 1.8.9 的 clickMouse() 在 MISS 时会把 leftClickCounter 设为 10，
        // 之后 10 tick 内的自动点击会被原版完全吞掉（10 CPS 实际只剩约 2 CPS）。
        // 自动点击器要按配置 CPS 工作，必须在每次注入点击前清掉这个原版节流。
        if (!writeIntField(mc, "leftClickCounter", "field_71429_W", 0)) {
            OpenMaverDebug.noticeOnce("autoclicker.leftCounter",
                    "cannot reset leftClickCounter; clicks may be throttled by vanilla");
        }
        // 现代服务端（1.19.2+）收到任何挥击动画都会清空 AttackStrengthTicker
        // （MC-255058）。1.8 的 clickMouse 顺序是 C0A -> C02，导致 C02 结算时
        // 蓄力永远是 0，只能打出 0.2~0.4 倍伤害。FULL CHARGE / FASTEST 模式下改为先发
        // C02、再补 C0A，保证攻击吃到完整蓄力。
        boolean attacked = autoclickerAttackMode != ATTACK_MODE_VANILLA
                && target instanceof EntityLivingBase
                && attackEntityFirst(mc, player, target);
        if (!attacked) {
            call(mc, "clickMouse", "func_147116_af", null);
        }
        restoreCriticalsSprint(player, criticalsStopSprintSent);
        StatsHud.recordAutoClick(true, now);
        OpenMaverDebug.click("left", interval, autoclickerCpsMax,
                Mouse.isButtonDown(0), autoclickerMissStreak);
        if (entityHit) {
            autoclickerLastEntityAttackNanos = now;
            autoclickerLastEntityAttackTick = clientTick;
        }
        autoclickerLastClickNanos = now;
        return true;
    }

    /**
     * 先发 C02 再发 C0A。
     *
     * <p>1.8 的 {@code clickMouse()} 顺序是 {@code swingItem()} -> {@code attackEntity()}。
     * 在 1.19.2+ 服务端上，任何 swing 动画都会重置攻击蓄力（MC-255058），
     * 于是紧随其后的 C02 只能按 0 蓄力结算（约 0.2~0.4 倍伤害）。
     * 反过来发送可以让 C02 吃到完整蓄力，代价是不符合 Grim 1.8 的
     * {@code PacketOrderB}（ANIMATION -> INTERACT_ENTITY），所以只在
     * FULL CHARGE / FASTEST 模式下使用，VANILLA 模式仍走原版顺序。
     */
    private static boolean attackEntityFirst(Minecraft mc, Object player, Object target) {
        Object controller = read(mc, "playerController", "field_71442_b", null);
        if (controller == null || player == null || target == null) {
            return false;
        }
        boolean sent = callVoid(controller, "attackEntity", "func_78764_a", "a",
                player, target);
        if (sent) {
            callVoid(player, "swingItem", "func_71038_i", null);
        }
        return sent;
    }

    /**
     * 目标是否已经脱离受击无敌帧，下一次攻击能真正结算伤害。
     *
     * <p>1.8.9 的判定是 {@code hurtResistantTime > maxHurtResistantTime / 2} 时
     * 忽略伤害；玩家默认 max=20，也就是 10 tick（约 0.5 秒）窗口。高速连点
     * 时大部分 C02 都落在这个窗口里，服务端只计算第一下，测试假人就会显示
     * “预期伤害”和“实际伤害”不一致。读取不到字段时保持原版行为，避免让
     * 自动点击器因映射差异完全失效。
     */
    private static boolean targetReadyForDamage(Object hit) {
        Object entity = read(hit, "entityHit", "field_72308_g", null);
        if (entity == null) {
            return true;
        }
        return entityReadyForDamage(entity);
    }

    /** 实体是否已经脱离受击无敌帧，下一次攻击能真正结算伤害。 */
    private static boolean entityReadyForDamage(Object entity) {
        if (entity == null) {
            return true;
        }
        if (!alive(entity)) {
            return false;
        }
        int maxHurt = (int) number(entity, "maxHurtResistantTime", "field_70738_aO");
        if (maxHurt <= 0) {
            maxHurt = 20;
        }
        int hurt = (int) number(entity, "hurtResistantTime", "field_70737_aN");
        return hurt <= maxHurt / 2;
    }

    /** 当前准星目标是否为实体（而不是方块 / MISS）。 */
    private static boolean isEntityHit(Object hit) {
        if (hit == null) {
            return false;
        }
        Object type = read(hit, "typeOfHit", "field_72313_a", null);
        return type != null && "ENTITY".equals(String.valueOf(type));
    }

    private static boolean tryAutoRightClick(Minecraft mc, long now) {
        // 使用物品/进食/拉弓期间不再叠加右键动作。
        Object player = read(mc, "thePlayer", "field_71439_g", null);
        if (Boolean.TRUE.equals(call(player, "isUsingItem", "func_71039_bw", "bS"))) {
            return false;
        }
        long interval = clickIntervalMillis(now);
        boolean aggressive = !bypassEnabled || bypassProfile == PROFILE_AGGRESSIVE;
        if (aggressive) {
            // rightClickMouse() 每次调用后都会把 rightClickDelayTimer 设为 4；
            // aggressive 档位直接压到 0，按配置 CPS 注入右键。
            if (!writeIntField(mc, "rightClickDelayTimer", "field_71467_ac", 0)) {
                OpenMaverDebug.noticeOnce("autoclicker.rightTimer",
                        "cannot reset rightClickDelayTimer; clicks may be throttled by vanilla");
            }
        } else {
            // balanced/legit：右键放置交还原版 4 tick 冷却。直接按 CPS 调用
            // rightClickMouse() 会以 10~20 个/秒连续发 C08，矩阵的
            // block.fastplace 正是按这个间隔判定的。
            Integer timer = readIntField(mc, "rightClickDelayTimer", "field_71467_ac");
            if (timer == null) {
                OpenMaverDebug.noticeOnce("autoclicker.rightTimerRead",
                        "cannot read rightClickDelayTimer; right clicks throttled");
                return false;
            }
            if (timer.intValue() > 0) {
                return false;
            }
        }
        call(mc, "rightClickMouse", "func_147121_ag", "ax");
        rightClickUsedThisTick = true;
        StatsHud.recordAutoClick(false, now);
        OpenMaverDebug.click("right", interval, autoclickerCpsMax,
                Mouse.isButtonDown(1), autoclickerMissStreak);
        autoclickerLastClickNanos = now;
        return true;
    }

    /**
     * 人类化点击间隔：均匀 CPS + 抖动 + 低概率停顿。
     * 截止时间由 {@link #nextClickDeadline(long, long)} 按固定节拍推进，
     * 不因 tick 量化而丢掉余量；卡顿后只丢弃积压，不补发爆发点击。
     */
    private static long autoClickerIntervalNanos() {
        int min = autoclickerCpsMin;
        // 点击器使用玩家在 ClickGUI 里设置的 CPS，不再被 Grim Bypass profile
        // 静默截断。balance 默认曾把 14~17 CPS 压到 12 CPS，再叠加 20Hz tick
        // 调度后实际只剩 6~9 CPS，看起来像模块失效。
        int max = autoclickerCpsMax;
        if (min > max) {
            int swap = min;
            min = max;
            max = swap;
        }
        min = Math.max(1, min);
        max = Math.max(min, max);
        int cps = min + AUTOCLICKER_RANDOM.nextInt(max - min + 1);

        double base = 1_000_000_000D / cps;
        if (autoclickerHumanize) {
            base *= 0.88D + AUTOCLICKER_RANDOM.nextDouble() * 0.24D;
            if (AUTOCLICKER_RANDOM.nextDouble() < 0.02D) {
                base += 120_000_000D + AUTOCLICKER_RANDOM.nextDouble() * 230_000_000D;
            }
        }
        // 上限 20 CPS（50ms）；GUI 的 CPS MAX 也限制在 20。
        return Math.max(45_000_000L, (long) base);
    }

    /**
     * 按固定节拍推进下一次点击时间。
     *
     * <p>上一次实现是 {@code now + interval}。在 20 TPS 下，14~17 CPS 的目标
     * 间隔（59~71ms）刚好跨过 1~2 个 tick，从 now 重算会不断丢掉不足一个 tick
     * 的余量，实际速率被压到约 6~10 CPS。这里改成
     * {@code previousDeadline + interval}，把余量保留下来；只有游戏卡顿导致
     * 计划时间严重落后时，才丢弃积压、从当前时间重新开始，避免爆发式补点。
     */
    private static long nextClickDeadline(long deadline, long now) {
        long interval = autoClickerIntervalNanos();
        long next = deadline + interval;
        if (next <= now) {
            next = now + interval;
        }
        return next;
    }

    private static long clickIntervalMillis(long now) {
        if (autoclickerLastClickNanos == 0L) {
            return 0L;
        }
        return Math.max(0L, (now - autoclickerLastClickNanos) / 1_000_000L);
    }

    private static void resetAutoClickerTimers() {
        autoclickerLeftNextNanos = 0L;
        autoclickerRightNextNanos = 0L;
        autoclickerPauseUntilNanos = 0L;
        autoclickerMissStreak = 0;
        autoclickerClickedThisTick = false;
        autoclickerLastClickNanos = 0L;
        autoclickerLastEntityAttackNanos = 0L;
        autoclickerLastEntityAttackTick = Long.MIN_VALUE;
        if (criticalsJumpAttackSource == CRITICALS_ATTACK_AUTOCLICKER) {
            resetCriticalsJumpState();
        }
    }

    // ==================================================================
    // Reach（攻击距离）
    // ==================================================================

    /**
     * 1.8.9 原版在生存模式下把实体目标距离硬限制在 3 格：
     * {@code EntityRenderer.getMouseOver} 虽然会读取
     * {@code PlayerControllerMP.getBlockReachDistance()}（生存 4.5 / 创造 5.0），
     * 但 1.8.9 的该方法是根据 GameType 动态计算的，并不存在
     * {@code blockReachDistance} 字段可供反射修改；随后只要实体距离超过 3 格，
     * 原版就会把目标丢弃。因此这里采用自定义视线射线：命中 CHANCE 时在
     * profile 允许的距离内寻找最近的可见生物，通过
     * {@code PlayerControllerMP.attackEntity} 发送 {@code C02PacketUseEntity}；
     * 未命中时完全保持原版 3 格。
     *
     * <p><b>CHANCE 语义</b>：按“每次新的挥击/攻击”掷一次随机数，而不是每帧。
     * 通过 {@code isSwingInProgress} / {@code swingProgressInt} 识别挥击开始或进度被
     * {@code swingItem()} 重置；高频点击时进度会持续停在 -1，同样按每次点击计算。
     * 模块关闭时不修改任何原版字段，因此不会残留扩大后的 reach。
     *
     * <p>方块交互距离不在本实现范围内：1.8.9 的方块 reach 由
     * {@code getBlockReachDistance()} 动态返回，客户端没有可安全恢复的字段。
     */
    private static boolean reachFailed;

    private static void updateReach(Minecraft mc) {
        if (reachFailed) {
            return;
        }
        try {
            updateReachInternal(mc);
        } catch (Throwable error) {
            reachFailed = true;
            resetReachState();
            System.err.println("[OpenMaver] Reach disabled after error: " + error);
        }
    }

    private static void updateReachInternal(Minecraft mc) {
        Module reach = ModuleManager.getModule("Reach");
        if (reach == null || !reach.isEnabled()) {
            resetReachState();
            return;
        }
        Object player = read(mc, "thePlayer", "field_71439_g", null);
        Object world = read(mc, "theWorld", "field_71441_e", null);
        if (player == null || world == null
                || read(mc, "currentScreen", "field_71462_r", null) != null
                || !alive(player)) {
            resetReachState();
            return;
        }
        // 使用物品时攻击会被 MultiActionsA 抓；本 tick 已经有右键动作时也
        // 不再追加实体交互，避免 MultiActionsF（同 tick 方块+实体）。
        if (Boolean.TRUE.equals(call(player, "isUsingItem", "func_71039_bw", "bS"))
                || rightClickUsedThisTick) {
            return;
        }

        boolean swinging = Boolean.TRUE.equals(
                read(player, "isSwingInProgress", "field_82175_bq", Boolean.class));
        int swingProgress = (int) number(player, "swingProgressInt", "field_110158_av");
        // 挥击刚开始（false -> true），或 swingItem() 把进度重置（变小 / 停在 -1）时，
        // 视为一次新的攻击/挥击。
        boolean newSwing = swinging
                && (!reachWasSwinging || swingProgress <= reachLastSwingProgress);
        reachWasSwinging = swinging;
        reachLastSwingProgress = swingProgress;
        if (!newSwing) {
            return;
        }

        if (reachChance < 100.0F
                && REACH_RANDOM.nextFloat() * 100.0F >= reachChance) {
            return; // 本次挥击未命中几率：保持原版距离
        }
        attackNearestEntityInReach(mc, player, world);
    }

    /**
     * 在 profile 允许的距离内寻找最近的可见生物并攻击一次。
     *
     * <p>距离按 Grim 的 1.8 Reach 语义计算：把目标 AABB 按
     * {@link #REACH_GRIM_BOX_MARGIN} 扩张后，眼睛到扩张箱的距离不得超过
     * {@link #REACH_SAFE_CAP}(3.0)。折算回真实命中箱约 3.13 格。
     */
    private static void attackNearestEntityInReach(Minecraft mc, Object player, Object world) {
        Object hit = read(mc, "objectMouseOver", "field_71476_x", null);
        Object hitType = hit == null ? null : read(hit, "typeOfHit", "field_72313_a", null);
        // 原版已经命中实体或方块时不再补刀：避免同一次挥击打两次，也避免隔墙攻击。
        if (hitType != null && !"MISS".equals(String.valueOf(hitType))) {
            return;
        }

        boolean safe = safeProfile();
        double maxDistance;
        double borderValue;
        if (!bypassEnabled) {
            // 关闭 bypass：保留旧的对比行为。
            if (reachExtra <= 0.0F) {
                return;
            }
            maxDistance = Math.min(REACH_MAX_DISTANCE, REACH_BASE_DISTANCE + reachExtra);
            borderValue = REACH_VANILLA_BOX_MARGIN;
        } else if (bypassProfile == PROFILE_AGGRESSIVE) {
            maxDistance = Math.min(REACH_AGGRESSIVE_CAP, REACH_BASE_DISTANCE + reachExtra);
            borderValue = REACH_VANILLA_BOX_MARGIN;
        } else {
            maxDistance = Math.min(REACH_SAFE_CAP, REACH_BASE_DISTANCE + reachExtra);
            borderValue = REACH_GRIM_BOX_MARGIN;
        }
        if (maxDistance <= 0.0D) {
            return;
        }
        List<?> entities = (List<?>) read(world, "loadedEntityList", "field_72996_f", List.class);
        if (entities == null) {
            return;
        }

        double eyeX = number(player, "posX", "field_70165_t");
        double eyeY = number(player, "posY", "field_70163_u") + eyeHeight(player);
        double eyeZ = number(player, "posZ", "field_70161_v");
        double yaw = Math.toRadians(number(player, "rotationYaw", "field_70177_z"));
        double pitch = Math.toRadians(number(player, "rotationPitch", "field_70125_A"));
        double dirX = -Math.sin(yaw) * Math.cos(pitch);
        double dirY = -Math.sin(pitch);
        double dirZ = Math.cos(yaw) * Math.cos(pitch);

        Object bestTarget = null;
        double bestDistance = maxDistance;
        for (int i = 0; i < entities.size(); i++) {
            Object candidate = entities.get(i);
            if (!(candidate instanceof EntityLivingBase) || candidate == player) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) candidate;
            if (!alive(living)) {
                continue;
            }
            // 与原版 EntityRenderer.getMouseOver 一样，只考虑可碰撞实体。
            if (!Boolean.TRUE.equals(
                    call(living, "canBeCollidedWith", "func_70067_L", "ad"))) {
                continue;
            }
            AxisAlignedBB box = boundingBox(living);
            if (box == null) {
                continue;
            }
            // getDistanceSqToEntity 是到实体中心的距离；边缘命中时中心距离会更大，
            // 粗筛必须把 AABB 半对角线算进去，不能直接用 3.0 + border。
            double halfX = (number(box, "maxX", "field_72336_d")
                    - number(box, "minX", "field_72340_a")) * 0.5D;
            double halfY = (number(box, "maxY", "field_72337_e")
                    - number(box, "minY", "field_72338_b")) * 0.5D;
            double halfZ = (number(box, "maxZ", "field_72334_f")
                    - number(box, "minZ", "field_72339_c")) * 0.5D;
            double halfDiagonal = Math.sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ);
            double centerLimit = maxDistance + borderValue + halfDiagonal;
            if (distance(player, living) >= centerLimit * centerLimit) {
                continue;
            }
            double entityBorder = 0.1D;
            if (!safe) {
                Object border = call(living, "getCollisionBorderSize", "func_70111_Y", "ao");
                if (border instanceof Number) {
                    entityBorder = ((Number) border).doubleValue();
                }
            }
            double hitDistance = rayBoxDistance(eyeX, eyeY, eyeZ, dirX, dirY, dirZ,
                    maxDistance, box, safe ? borderValue : entityBorder);
            if (hitDistance < 0.0D || hitDistance >= bestDistance) {
                continue;
            }
            // 与服务端 NetHandlerPlayServer.processUseEntity 一致：不可见目标只允许
            // 3 格，因此这里直接排除隔墙目标，避免发送无效的 C02 包。
            if (!Boolean.TRUE.equals(
                    call(player, "canEntityBeSeen", "func_70685_l", "t", living))) {
                continue;
            }
            bestDistance = hitDistance;
            bestTarget = living;
        }
        if (bestTarget == null) {
            return;
        }

        // 目标还在受击无敌帧里时，服务端会丢弃这次 C02；Reach 在每次挥击都
        // 尝试补刀，如果不判断就会把大量无效攻击发给服务端，导致测试假人
        // 的预期伤害/实际伤害不一致。
        if (!entityReadyForDamage(bestTarget)) {
            OpenMaverDebug.noticeOnce("reach.hurtWait",
                    "reach attack waits for target hurtResistantTime");
            return;
        }

        // 1.8 PacketOrderB 要求 ANIMATION 在 INTERACT_ENTITY 之前。正常由
        // clickMouse/swingItem 已经发过 C0A；极端情况下这里补一次 swing。
        boolean alreadySwinging = Boolean.TRUE.equals(
                read(player, "isSwingInProgress", "field_82175_bq", Boolean.class));
        boolean animation = alreadySwinging;
        if (!animation) {
            animation = callVoid(player, "swingItem", "func_71038_i", null);
        }
        if (!animation) {
            OpenMaverDebug.suppressed("reach attack skipped: no animation packet");
            return;
        }

        Object controller = read(mc, "playerController", "field_71442_b", null);
        if (controller == null) {
            return;
        }
        // Reach 的补刀同样需要先伪造下落，再发送 C02；攻击后恢复冲刺。
        boolean criticalsStopSprintSent = applyCriticals(mc, player, bestTarget,
                CRITICALS_ATTACK_REACH);
        if (criticalsAttackDeferred) {
            return;
        }
        callVoid(controller, "attackEntity", "func_78764_a", "a", player, bestTarget);
        restoreCriticalsSprint(player, criticalsStopSprintSent);
        Object name = call(bestTarget, "getName", "func_70005_c_", null);
        OpenMaverDebug.attack(name == null ? String.valueOf(bestTarget) : String.valueOf(name),
                bestDistance, maxDistance,
                (float) number(player, "rotationYaw", "field_70177_z"),
                (float) number(player, "rotationPitch", "field_70125_A"),
                alreadySwinging);
    }

    /** 射线与实体碰撞箱（向外扩张 border）的最近交点距离；未命中返回 -1。 */
    private static double rayBoxDistance(double originX, double originY, double originZ,
            double dirX, double dirY, double dirZ, double maxDistance,
            AxisAlignedBB box, double border) {
        double[] enter = new double[] {0.0D};
        double[] exit = new double[] {maxDistance};
        if (!clipRayAxis(originX, dirX,
                number(box, "minX", "field_72340_a") - border,
                number(box, "maxX", "field_72336_d") + border, enter, exit)
                || !clipRayAxis(originY, dirY,
                number(box, "minY", "field_72338_b") - border,
                number(box, "maxY", "field_72337_e") + border, enter, exit)
                || !clipRayAxis(originZ, dirZ,
                number(box, "minZ", "field_72339_c") - border,
                number(box, "maxZ", "field_72334_f") + border, enter, exit)) {
            return -1.0D;
        }
        return enter[0];
    }

    /** 单轴 slab 裁剪，更新射线的进入/离开距离。 */
    private static boolean clipRayAxis(double origin, double direction, double min, double max,
            double[] enter, double[] exit) {
        if (Math.abs(direction) < 1.0E-7D) {
            return origin >= min && origin <= max;
        }
        double t1 = (min - origin) / direction;
        double t2 = (max - origin) / direction;
        if (t1 > t2) {
            double swap = t1;
            t1 = t2;
            t2 = swap;
        }
        if (t1 > enter[0]) {
            enter[0] = t1;
        }
        if (t2 < exit[0]) {
            exit[0] = t2;
        }
        return enter[0] <= exit[0];
    }

    private static void resetReachState() {
        // Reach 不做任何原版字段修改，只需清空挥击识别状态。
        reachWasSwinging = false;
        reachLastSwingProgress = Integer.MIN_VALUE;
    }

    // ==================================================================
    // Criticals（ALWAYS 刀暴 / 强制暴击）
    // ==================================================================

    /**
     * 完成 JUMP 模式上一次被延后的攻击。该方法在 tick START 执行，早于 vanilla
     * 移动包，因此 C02 仍保持在本 tick 的 C03 之前。
     */
    private static void processPendingCriticalJump(Minecraft mc) {
        Object target = criticalsJumpTarget;
        if (target == null) {
            return;
        }
        long now = System.nanoTime();
        if (now > criticalsJumpDeadlineNanos) {
            resetCriticalsJumpState();
            return;
        }
        Module criticals = ModuleManager.getModule("Criticals");
        Object player = read(mc, "thePlayer", "field_71439_g", null);
        if (criticals == null || !criticals.isEnabled() || player == null
                || !alive(player) || !alive(target)) {
            resetCriticalsJumpState();
            return;
        }
        if (criticalsJumpAttackSource == CRITICALS_ATTACK_AUTOCLICKER) {
            Module autoClicker = ModuleManager.getModule("AutoClicker");
            if (autoClicker == null || !autoClicker.isEnabled()
                    || (autoclickerRequireHold && !Mouse.isButtonDown(0))) {
                resetCriticalsJumpState();
                return;
            }
        }
        if (scaffoldOnGround(player) || !criticalPlayerIsFalling(player)
                || !entityReadyForDamage(target)) {
            return;
        }

        int source = criticalsJumpAttackSource;
        criticalsAttackDeferred = false;
        boolean stopSprintSent = applyCriticals(mc, player, target, source);
        if (criticalsAttackDeferred) {
            return;
        }
        resetCriticalsJumpState();

        if (source == CRITICALS_ATTACK_AUTOCLICKER) {
            Object hit = read(mc, "objectMouseOver", "field_71476_x", null);
            Object currentTarget = read(hit, "entityHit", "field_72308_g", null);
            if (currentTarget != target) {
                return;
            }
            writeIntField(mc, "leftClickCounter", "field_71429_W", 0);
            if (!((autoclickerAttackMode != ATTACK_MODE_VANILLA)
                    && attackEntityFirst(mc, player, target))) {
                call(mc, "clickMouse", "func_147116_af", null);
            }
            restoreCriticalsSprint(player, stopSprintSent);
            autoclickerClickedThisTick = true;
            autoclickerLastEntityAttackNanos = now;
            autoclickerLastEntityAttackTick = clientTick;
            autoclickerLastClickNanos = now;
            StatsHud.recordAutoClick(true, now);
            OpenMaverDebug.click("critical-jump", 0L, autoclickerCpsMax,
                    Mouse.isButtonDown(0), autoclickerMissStreak);
            return;
        }

        if (source == CRITICALS_ATTACK_REACH) {
            Object controller = read(mc, "playerController", "field_71442_b", null);
            if (controller != null) {
                callVoid(controller, "attackEntity", "func_78764_a", "a", player, target);
                restoreCriticalsSprint(player, stopSprintSent);
            }
        }
    }

    private static boolean criticalPlayerIsFalling(Object player) {
        double fallDistance = number(player, "fallDistance", "field_70143_R");
        double motionY = number(player, "motionY", "field_70181_x");
        return fallDistance > 0.0D || motionY < -0.01D;
    }

    private static boolean prepareCriticalJump(Object player, Object target, int source) {
        if (player == null || target == null) {
            return false;
        }
        if (scaffoldOnGround(player)) {
            if (!callVoid(player, "jump", "func_70664_aZ", "aF")) {
                logCriticalsFailure("jump", new IllegalStateException("jump method unavailable"));
                return false;
            }
        }
        criticalsJumpTarget = target;
        criticalsJumpAttackSource = source;
        criticalsJumpDeadlineNanos = System.nanoTime() + 750_000_000L;
        criticalsAttackDeferred = true;
        return true;
    }

    private static void resetCriticalsJumpState() {
        criticalsJumpTarget = null;
        criticalsJumpAttackSource = 0;
        criticalsJumpDeadlineNanos = 0L;
        criticalsAttackDeferred = false;
    }

    /**
     * 在真正发送 C02 之前伪造 C03/C06 移动包，让服务端认为玩家正在下落。
     *
     * <p>调用顺序必须是：伪造移动包 -> C02 攻击。本方法只负责前半段；
     * 调用方在 {@code clickMouse()} / {@code attackEntity()} 之后必须调用
     * {@link #restoreCriticalsSprint(Object, boolean)}。
     *
     * @return 是否发送过 STOP_SPRINTING（用于攻击后恢复冲刺）
     */
    private static boolean applyCriticals(Minecraft mc, Object player, Object target, int source) {
        boolean stopSprintSent = false;
        criticalsAttackDeferred = false;
        try {
            Module criticals = ModuleManager.getModule("Criticals");
            if (criticals == null || !criticals.isEnabled()) {
                return false;
            }
            if (player == null || !(target instanceof EntityLivingBase) || !alive(target)
                    || !alive(player)) {
                return false;
            }
            // 使用物品时攻击会被 Grim MultiActions 抓，而且原版暴击条件也不成立。
            if (Boolean.TRUE.equals(call(player, "isUsingItem", "func_71039_bw", "bS"))) {
                return false;
            }
            // 目标还在无敌帧里时，即使伪造下落，C02 也不会结算伤害。
            if (!entityReadyForDamage(target)) {
                return false;
            }
            long now = System.nanoTime();
            if (criticalsDelayMs > 0L && criticalsLastAttackNanos != 0L
                    && now - criticalsLastAttackNanos < criticalsDelayMs * 1_000_000L) {
                return false;
            }
            if (criticalsInInvalidState(player)) {
                return false;
            }

            boolean onGround = scaffoldOnGround(player);
            int mode = clamp(criticalsMode, CRITICALS_PACKET, CRITICALS_LAST);
            // LEGIT 完全不做刀暴；BALANCED 把任何伪造位移模式降级为真实起跳。
            // Matrix 的 ka.critical.*（ground / hop / modify）与 bp.timer 都直接源于
            // “每次攻击多发的移动包”，低风险档位不应再把它们发出去。
            if (bypassEnabled) {
                if (bypassProfile == PROFILE_LEGIT) {
                    return false;
                }
                if (bypassProfile == PROFILE_BALANCED && mode != CRITICALS_JUMP) {
                    mode = CRITICALS_JUMP;
                }
            }
            if (mode == CRITICALS_JUMP) {
                if (!criticalPlayerIsFalling(player)) {
                    prepareCriticalJump(player, target, source);
                    return false;
                }
                if (criticalsJumpTarget == target) {
                    criticalsJumpTarget = null;
                    criticalsJumpDeadlineNanos = 0L;
                }
            }
            // ALWAYS / NCP / FALLING / JUMP 在任意地面状态都可以执行；
            // GRIM 仍然只作为“已经在空中”时的补包模式，避免在地面伪造 onGround=false。
            if (mode == CRITICALS_GRIM && onGround) {
                return false;
            }

            double x = number(player, "posX", "field_70165_t");
            double y = number(player, "posY", "field_70163_u");
            double z = number(player, "posZ", "field_70161_v");
            float yaw = (float) number(player, "rotationYaw", "field_70177_z");
            float pitch = (float) number(player, "rotationPitch", "field_70125_A");

            // 1.8.9 客户端的暴击判定不检查冲刺，但 latest（1.9+）服务端的
            // Player.attack 明确要求 !isSprinting()。攻击时还在冲刺的话，伪造下落
            // 既不会暴击，还会白送 GroundSpoof / Post / Timer 三类 flag，所以这里
            // 要么真的先停冲刺，要么直接放弃这次刀暴（调用方随后按普通攻击结算）。
            boolean sprinting = Boolean.TRUE.equals(
                    call(player, "isSprinting", "func_70051_ag", null));
            if (sprinting) {
                if (!criticalsStopSprint) {
                    OpenMaverDebug.noticeOnce("criticals.sprint",
                            "criticals skipped: sprinting blocks 1.9+ critical hits");
                    return false;
                }
                stopSprintSent = stopCriticalsSprint(player);
                if (Boolean.TRUE.equals(
                        call(player, "isSprinting", "func_70051_ag", null))) {
                    restoreCriticalsSprint(player, stopSprintSent);
                    return false;
                }
            }

            boolean movementSent = mode == CRITICALS_JUMP || sendCriticalsMovement(player, mode,
                    x, y, z, yaw, pitch, onGround);
            if (!movementSent) {
                // 没有成功伪造下落就不要留下 STOP_SPRINTING 状态。
                restoreCriticalsSprint(player, stopSprintSent);
                return false;
            }
            criticalsLastAttackNanos = now;
            return stopSprintSent;
        } catch (Throwable error) {
            // 任何意外都不留下 STOP_SPRINTING 状态。
            restoreCriticalsSprint(player, stopSprintSent);
            logCriticalsFailure("apply", error);
            return false;
        }
    }

    /** 梯子/水面/蜘蛛网/骑乘状态下伪造下落容易被服务端判定为非法移动。 */
    private static boolean criticalsInInvalidState(Object player) {
        if (Boolean.TRUE.equals(call(player, "isInWater", "func_70090_H", null))) {
            return true;
        }
        if (Boolean.TRUE.equals(call(player, "isOnLadder", "func_70617_f_", "k_"))) {
            return true;
        }
        if (Boolean.TRUE.equals(read(player, "isInWeb", "field_70134_J", null))) {
            return true;
        }
        return Boolean.TRUE.equals(call(player, "isRiding", "func_70115_ae", null));
    }

    private static boolean sendCriticalsMovement(Object player, int mode,
            double x, double y, double z, float yaw, float pitch, boolean onGround) {
        switch (mode) {
            case CRITICALS_PACKET:
                // LiquidBounce Legacy Packet：先抬高 0.0625 再回到原位置。
                return sendCriticalsPosition(player, x, y + 0.0625D, z, true)
                        && sendCriticalsPosition(player, x, y, z, false);
            case CRITICALS_NCP:
                return sendCriticalsPosition(player, x, y + 0.11D, z, false)
                        && sendCriticalsPosition(player, x, y + 0.1100013579D, z, false)
                        && sendCriticalsPosition(player, x, y + 0.0000013579D, z, false);
            case CRITICALS_FALLING:
                return sendCriticalsPosition(player, x, y + 0.0625D, z, false)
                        && sendCriticalsPosition(player, x, y + 0.0625013579D, z, false)
                        && sendCriticalsPosition(player, x, y + 0.0000013579D, z, false);
            case CRITICALS_GRIM:
                // 现代 Grim 模式只在调用方确认玩家已离地后补一条 FULL 包。
                return sendCriticalsPosLook(player, x, y - 0.000001D, z,
                        yaw, pitch, false);
            case CRITICALS_LOW:
                // LiquidBounce / 水影 Low：先抬 1e-9 再回到原位。
                // 位移低于 Grim Simulation 的 0.001 阈值，同时制造 >0 的 fallDistance。
                // 用 C04（只带位置）而不是 C06：两条同 yaw/pitch 的 C06 会被 Grim 的
                // AimDuplicateLook 当成“重复 rotation”逐条记录，而伪造下落并不需要
                // 再发一遍视角。
                return sendCriticalsPosition(player, x, y + 1.0E-9D, z, false)
                        && sendCriticalsPosition(player, x, y, z, false);
            case CRITICALS_DOWN:
                // LiquidBounce / 水影 Down：只向下 1e-9，包数最少。
                // 如果客户端正好贴地、微型位移被地面碰撞吃掉，请改用 LOW。
                return sendCriticalsPosition(player, x, y - 1.0E-9D, z, false);
            default:
                return false;
        }
    }

    private static boolean sendCriticalsPosition(Object player,
            double x, double y, double z, boolean onGround) {
        try {
            if (!ensureCriticalsPositionPacket()) {
                return false;
            }
            Object packet = criticalsPositionConstructor.newInstance(
                    Double.valueOf(x), Double.valueOf(y), Double.valueOf(z),
                    Boolean.valueOf(onGround));
            return sendCriticalsPacket(player, packet);
        } catch (Throwable error) {
            criticalsPositionLookupFailed = true;
            logCriticalsFailure("C04", error);
            return false;
        }
    }

    private static boolean sendCriticalsPosLook(Object player, double x, double y, double z,
            float yaw, float pitch, boolean onGround) {
        try {
            if (!ensureCriticalsPosLookPacket()) {
                return false;
            }
            Object packet = criticalsPosLookConstructor.newInstance(
                    Double.valueOf(x), Double.valueOf(y), Double.valueOf(z),
                    Float.valueOf(yaw), Float.valueOf(pitch), Boolean.valueOf(onGround));
            return sendCriticalsPacket(player, packet);
        } catch (Throwable error) {
            criticalsPosLookLookupFailed = true;
            logCriticalsFailure("C06", error);
            return false;
        }
    }

    private static boolean ensureCriticalsPositionPacket() {
        if (criticalsPositionConstructor != null) {
            return true;
        }
        if (criticalsPositionLookupFailed) {
            return false;
        }
        try {
            criticalsPositionPacketType = Class.forName(
                    "net.minecraft.network.play.client.C03PacketPlayer$C04PacketPlayerPosition");
            criticalsPositionConstructor = criticalsPositionPacketType.getConstructor(
                    Double.TYPE, Double.TYPE, Double.TYPE, Boolean.TYPE);
            return true;
        } catch (Throwable error) {
            criticalsPositionLookupFailed = true;
            logCriticalsFailure("C04 lookup", error);
            return false;
        }
    }

    private static boolean ensureCriticalsPosLookPacket() {
        if (criticalsPosLookConstructor != null) {
            return true;
        }
        if (criticalsPosLookLookupFailed) {
            return false;
        }
        try {
            criticalsPosLookPacketType = Class.forName(
                    "net.minecraft.network.play.client.C03PacketPlayer$C06PacketPlayerPosLook");
            criticalsPosLookConstructor = criticalsPosLookPacketType.getConstructor(
                    Double.TYPE, Double.TYPE, Double.TYPE,
                    Float.TYPE, Float.TYPE, Boolean.TYPE);
            return true;
        } catch (Throwable error) {
            criticalsPosLookLookupFailed = true;
            logCriticalsFailure("C06 lookup", error);
            return false;
        }
    }

    private static boolean ensureCriticalsEntityAction() {
        if (criticalsEntityActionConstructor != null
                && criticalsStopSprintAction != null
                && criticalsStartSprintAction != null) {
            return true;
        }
        if (criticalsEntityActionLookupFailed) {
            return false;
        }
        try {
            criticalsActionType = Class.forName(
                    "net.minecraft.network.play.client.C0BPacketEntityAction$Action");
            criticalsStopSprintAction = criticalsAction(criticalsActionType, "STOP_SPRINTING", 4);
            criticalsStartSprintAction = criticalsAction(criticalsActionType, "START_SPRINTING", 3);
            if (criticalsStopSprintAction == null || criticalsStartSprintAction == null) {
                throw new IllegalStateException("sprint actions not found");
            }
            criticalsEntityActionPacketType = Class.forName(
                    "net.minecraft.network.play.client.C0BPacketEntityAction");
            criticalsEntityActionConstructor = criticalsEntityActionPacketType.getConstructor(
                    Entity.class, criticalsActionType);
            return true;
        } catch (Throwable error) {
            criticalsEntityActionLookupFailed = true;
            logCriticalsFailure("C0B lookup", error);
            return false;
        }
    }

    /** 优先按枚举名查找；运行期若被重映射，则回退到声明顺序。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object criticalsAction(Class<?> actionType, String name, int ordinal) {
        try {
            return Enum.valueOf((Class) actionType, name);
        } catch (Throwable ignored) {
            // Fall through to enum constants.
        }
        Object[] constants = actionType.getEnumConstants();
        if (constants == null) {
            return null;
        }
        for (int i = 0; i < constants.length; i++) {
            if (constants[i] instanceof Enum
                    && name.equalsIgnoreCase(((Enum) constants[i]).name())) {
                return constants[i];
            }
        }
        return ordinal >= 0 && ordinal < constants.length ? constants[ordinal] : null;
    }

    /** 攻击前发送 C0B STOP_SPRINTING，并同步客户端冲刺状态。 */
    private static boolean stopCriticalsSprint(Object player) {
        try {
            if (!ensureCriticalsEntityAction()) {
                return false;
            }
            criticalsSprintWasActive = true;
            Object packet = criticalsEntityActionConstructor.newInstance(
                    player, criticalsStopSprintAction);
            boolean packetSent = sendCriticalsPacket(player, packet);
            boolean stateChanged = callVoid(player, "setSprinting", "func_70031_b", null,
                    Boolean.FALSE);
            if (!packetSent && !stateChanged) {
                criticalsSprintWasActive = false;
                return false;
            }
            return true;
        } catch (Throwable error) {
            criticalsSprintWasActive = false;
            logCriticalsFailure("STOP_SPRINTING", error);
            return false;
        }
    }

    /** 攻击后恢复攻击前的冲刺状态（仅当 applyCriticals 发过 STOP_SPRINTING）。 */
    private static void restoreCriticalsSprint(Object player, boolean stopSent) {
        if (!stopSent || player == null) {
            return;
        }
        boolean wasActive = criticalsSprintWasActive;
        criticalsSprintWasActive = false;
        if (!wasActive) {
            return;
        }
        try {
            if (ensureCriticalsEntityAction()) {
                Object packet = criticalsEntityActionConstructor.newInstance(
                        player, criticalsStartSprintAction);
                sendCriticalsPacket(player, packet);
            }
            callVoid(player, "setSprinting", "func_70031_b", null, Boolean.TRUE);
        } catch (Throwable error) {
            logCriticalsFailure("START_SPRINTING", error);
        }
    }

    private static boolean sendCriticalsPacket(Object player, Object packet) {
        if (player == null || packet == null) {
            return false;
        }
        Object sendQueue = read(player, "sendQueue", "field_71174_a", null);
        if (sendQueue == null) {
            return false;
        }
        return callVoid(sendQueue, "addToSendQueue", "func_147297_a", null, packet);
    }

    private static void logCriticalsFailure(String stage, Throwable error) {
        if (criticalsFailureLogged) {
            return;
        }
        criticalsFailureLogged = true;
        System.err.println("[OpenMaver] Criticals packet path failed at "
                + stage + ": " + error);
    }

    private static boolean holdingBlock(Object player) {
        Object stack = call(player, "getHeldItem", "func_70694_bm", null);
        if (stack == null || number(stack, "stackSize", "field_77994_a") <= 0.0D) {
            return false;
        }
        Object item = call(stack, "getItem", "func_77973_b", null);
        Class<?> itemBlock = itemBlockClass();
        return item != null && itemBlock != null && itemBlock.isInstance(item);
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

    // ==================================================================
    // FastPlace
    // ==================================================================

    private static void updateFastPlace(Minecraft mc) {
        if (fastPlaceFailed) {
            return;
        }
        try {
            Module fastPlace = ModuleManager.getModule("FastPlace");
            Object player = read(mc, "thePlayer", "field_71439_g", null);
            Object world = read(mc, "theWorld", "field_71441_e", null);
            boolean active = fastPlace != null && fastPlace.isEnabled()
                    && read(mc, "currentScreen", "field_71462_r", null) == null
                    && player != null
                    && world != null
                    && alive(player);
            if (active && fastPlaceOnlyBlocks) {
                active = holdingBlock(player);
            }

            int limit = clamp(fastPlaceDelayTicks, 0, 4);
            if (bypassEnabled) {
                if (bypassProfile == PROFILE_LEGIT) {
                    // LEGIT：完全不碰原版右键冷却。
                    active = false;
                } else if (bypassProfile == PROFILE_BALANCED) {
                    // BALANCED：不低于原版 4 tick 冷却。Matrix 的 block.fastplace
                    // 按 C08 间隔判定，2~3 tick 的连续放置会稳定吃 flag。
                    limit = Math.max(4, limit);
                    // GodBridge 需要等待 timer 自然归零；FastPlace 与它
                    // 同时开启时会把 timer 卡在 2，导致永远无法放置。
                    if (placementModuleEnabled()) {
                        active = false;
                    }
                }
            }

            if (!active) {
                if (fastPlaceWasActive) {
                    fastPlaceWasActive = false;
                    if (!writeIntField(mc, "rightClickDelayTimer", "field_71467_ac", 4)) {
                        fastPlaceFailed = true;
                        System.err.println("[OpenMaver] FastPlace disabled: cannot restore rightClickDelayTimer");
                    }
                }
                return;
            }

            Integer current = readIntField(mc, "rightClickDelayTimer", "field_71467_ac");
            if (current == null) {
                fastPlaceFailed = true;
                System.err.println("[OpenMaver] FastPlace disabled: cannot read rightClickDelayTimer");
                return;
            }
            int next = Math.min(current.intValue(), limit);
            if (!writeIntField(mc, "rightClickDelayTimer", "field_71467_ac", next)) {
                fastPlaceFailed = true;
                System.err.println("[OpenMaver] FastPlace disabled: cannot write rightClickDelayTimer");
                return;
            }
            fastPlaceWasActive = true;
        } catch (Throwable error) {
            fastPlaceFailed = true;
            fastPlaceWasActive = false;
            System.err.println("[OpenMaver] FastPlace disabled after reflection error: " + error);
        }
    }

    /** GodBridge 是否启用；balanced 下 FastPlace 需要让位。 */
    private static boolean placementModuleEnabled() {
        Module godBridge = ModuleManager.getModule("GodBridge");
        return godBridge != null && godBridge.isEnabled();
    }

    private static Integer readIntField(Object target, String mcp, String srg) {        Field field = findField(target, runtimeCandidates(mcp, srg));
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writeIntField(Object target, String mcp, String srg, int value) {
        Field field = findField(target, runtimeCandidates(mcp, srg));
        if (field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            if (field.getType() == Integer.TYPE) {
                field.setInt(target, value);
                return true;
            }
            if (field.getType() == Byte.TYPE) {
                field.setByte(target, (byte) value);
                return true;
            }
            if (field.getType() == Long.TYPE) {
                field.setLong(target, value);
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    // ==================================================================
    // FakePlayer（假人玩家，仅客户端）
    // ==================================================================

    /**
     * 在玩家前方生成若干客户端假人（{@link EntityOtherPlayerMP}）。
     *
     * <p>假人只存在于本地世界：不会向服务端发送玩家生成/移动数据包，服务端与其他玩家
     * 都看不到。它们会被正常加入 {@code loadedEntityList} / {@code playerEntities}，
     * 因此 Aim Assist、Player ESP、Target HUD 等模块会像对待真实玩家一样处理它们。
     *
     * <p>假人的位置在生成时固定（面向玩家），玩家移动后不会跟随；切换世界或关闭
     * 模块时统一回收。整个流程包在 try/catch 中，任何反射/版本差异只会停用本功能。
     */
    private static void updateFakePlayers(Minecraft mc) {
        if (fakePlayerFailed) {
            return;
        }
        try {
            Module module = ModuleManager.getModule("FakePlayer");
            if (module == null || !module.isEnabled()) {
                despawnFakePlayers();
                return;
            }

            Object worldObject = read(mc, "theWorld", "field_71441_e", null);
            Object playerObject = read(mc, "thePlayer", "field_71439_g", null);
            if (!(worldObject instanceof WorldClient) || !(playerObject instanceof EntityPlayer)) {
                // 世界正在卸载：实体随旧世界一起销毁，不要回调已失效的 WorldClient。
                FAKE_PLAYERS.clear();
                fakePlayerWorld = null;
                fakePlayerSpawnedCount = -1;
                fakePlayerSpawnedDistance = -1.0F;
                return;
            }

            WorldClient world = (WorldClient) worldObject;
            EntityPlayer player = (EntityPlayer) playerObject;
            if (fakePlayerWorld != world) {
                // 进入新世界：旧实体已经不存在，只清空引用。
                FAKE_PLAYERS.clear();
                fakePlayerWorld = world;
                fakePlayerSpawnedCount = -1;
                fakePlayerSpawnedDistance = -1.0F;
            }

            // 名字显示是纯客户端 flag，可以实时修改，无需重建实体。
            for (int i = 0; i < FAKE_PLAYERS.size(); i++) {
                FAKE_PLAYERS.get(i).setAlwaysRenderNameTag(fakePlayerShowName);
            }

            if (fakePlayerSpawnedCount != fakePlayerCount
                    || fakePlayerSpawnedDistance != fakePlayerDistance) {
                despawnFakePlayers();
                for (int i = 0; i < fakePlayerCount; i++) {
                    spawnFakePlayer(world, player, i);
                }
                fakePlayerSpawnedCount = fakePlayerCount;
                fakePlayerSpawnedDistance = fakePlayerDistance;
                System.out.println("[OpenMaver] FakePlayer spawned: " + FAKE_PLAYERS.size()
                        + " entity/entities at " + fakePlayerDistance + "m");
                return;
            }

            // 如果实体被其他逻辑移除/杀死，补生成以保持配置的数量。
            for (int i = FAKE_PLAYERS.size() - 1; i >= 0; i--) {
                EntityOtherPlayerMP fake = FAKE_PLAYERS.get(i);
                if (fake.isEntityAlive()) {
                    continue;
                }
                try {
                    world.removeEntityFromWorld(fake.getEntityId());
                } catch (Throwable ignored) {
                    // 实体可能已经不在世界里。
                }
                FAKE_PLAYERS.remove(i);
            }
            while (FAKE_PLAYERS.size() < fakePlayerCount) {
                spawnFakePlayer(world, player, FAKE_PLAYERS.size());
            }
        } catch (Throwable error) {
            fakePlayerFailed = true;
            System.err.println("[OpenMaver] FakePlayer disabled after error: " + error);
            despawnFakePlayers();
        }
    }

    private static void spawnFakePlayer(WorldClient world, EntityPlayer player, int index) {
        String name = fakePlayerCount > 1 ? "FakePlayer-" + (index + 1) : "FakePlayer";
        EntityOtherPlayerMP fake = new EntityOtherPlayerMP(
                world, new GameProfile(UUID.randomUUID(), name));

        // Minecraft 的 yaw 0 度朝向 +Z；forward = (-sin, cos)，right = (cos, sin)。
        double yaw = Math.toRadians(player.rotationYaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double side = (index - (fakePlayerCount - 1) * 0.5D) * 1.35D;
        double x = player.posX + forwardX * fakePlayerDistance + rightX * side;
        double z = player.posZ + forwardZ * fakePlayerDistance + rightZ * side;

        // 面向玩家；竖直方向放在玩家脚下，EntityOtherPlayerMP 自身不施加重力。
        fake.setLocationAndAngles(x, player.posY, z, player.rotationYaw + 180.0F, 0.0F);
        fake.setCustomNameTag(name);
        fake.setAlwaysRenderNameTag(fakePlayerShowName);
        fake.setHealth(fake.getMaxHealth());

        int id = fakePlayerNextId++;
        world.addEntityToWorld(id, fake);
        FAKE_PLAYERS.add(fake);
    }

    private static void despawnFakePlayers() {
        WorldClient world = fakePlayerWorld;
        int removed = 0;
        for (int i = 0; i < FAKE_PLAYERS.size(); i++) {
            EntityOtherPlayerMP fake = FAKE_PLAYERS.get(i);
            if (world != null) {
                try {
                    world.removeEntityFromWorld(fake.getEntityId());
                } catch (Throwable ignored) {
                    // 世界可能已经卸载；忽略即可。
                }
            }
            removed++;
        }
        FAKE_PLAYERS.clear();
        fakePlayerSpawnedCount = -1;
        fakePlayerSpawnedDistance = -1.0F;
        if (removed > 0) {
            System.out.println("[OpenMaver] FakePlayer removed: " + removed + " entity/entities");
        }
    }

    // ==================================================================
    // GodBridge（神桥）
    // ==================================================================

    /**
     * 神桥的触发方式与手动操作保持一致：
     * <ol>
     *   <li>按住 Shift 蹲在方块边缘，手持可放置方块；</li>
     *   <li>视角瞄准方块边缘，并按住 D（右平移）；</li>
     *   <li>松开 Shift 后进入神桥，自动向移动方向铺路；</li>
     *   <li>期间可以直接跳跃，或用 A/D 改变方向；再次按下 Shift 停止。</li>
     * </ol>
     *
     * <p>神桥进行中每 tick 最多放置一次，并复用 {@link #tryGodBridgePlacement}
     * 的真实右键放置逻辑。默认在神桥期间把 {@code rightClickDelayTimer} 压到 0，
     * 否则原版 4 tick 的右键冷却不足以跟上行走速度。
     */
    private static void updateGodBridge(Minecraft mc) {
        if (godBridgeFailed) {
            restoreGodBridgeTimer(mc);
            return;
        }
        try {
            Module godBridge = ModuleManager.getModule("GodBridge");
            Object player = read(mc, "thePlayer", "field_71439_g", null);
            Object world = read(mc, "theWorld", "field_71441_e", null);
            boolean safe = godBridge != null && godBridge.isEnabled()
                    && read(mc, "currentScreen", "field_71462_r", null) == null
                    && player != null
                    && world != null
                    && alive(player);
            if (!safe) {
                stopGodBridge(mc);
                return;
            }

            Object input = read(player, "movementInput", "field_71158_b", null);
            if (input == null) {
                stopGodBridge(mc);
                return;
            }
            boolean holdingBlock = holdingBlock(player);
            boolean sneaking = Boolean.TRUE.equals(
                    call(player, "isSneaking", "func_70093_af", "av"));
            double forward = number(input, "moveForward", "field_78900_b");
            double strafe = number(input, "moveStrafe", "field_78901_c");

            if (!godBridgeActive) {
                if (!holdingBlock) {
                    resetGodBridgeState();
                    return;
                }
                if (sneaking) {
                    // 蹲下 + 瞄准方块边缘：进入待触发状态。
                    if (godBridgeArmed
                            || !godBridgeRequireEdgeAim
                            || isAimingAtBlockEdge(mc)) {
                        godBridgeArmed = scaffoldOnGround(player);
                    }
                    return;
                }
                if (!godBridgeArmed) {
                    return;
                }
                // 松开 Shift 时 D 必须仍被按住，才视为完成了“按住 D 再松开 Shift”的起桥动作；
                // 同时要求仍在地面上，避免跳跃或坠落过程中被误触发。
                if (strafe >= -0.01D || !scaffoldOnGround(player)) {
                    godBridgeArmed = false;
                    return;
                }
                // 松开 Shift：本 tick 立即开始铺路，避免多等 1 tick 踏空。
                godBridgeActive = true;
                godBridgeArmed = false;
                godBridgeCooldownTicks = 0;
                godBridgeGroundY = (int) Math.floor(number(player, "posY", "field_70163_u")) - 1;
            }

            // 神桥进行中：再次按 Shift 视为停止，避免与手动蹲桥冲突。
            if (!holdingBlock || sneaking) {
                stopGodBridge(mc);
                return;
            }
            if (Boolean.TRUE.equals(call(player, "isUsingItem", "func_71039_bw", "bS"))) {
                return;
            }
            if (rightClickUsedThisTick) {
                return;
            }
            if (godBridgeCooldownTicks > 0) {
                godBridgeCooldownTicks--;
                return;
            }

            double posX = number(player, "posX", "field_70165_t");
            double posY = number(player, "posY", "field_70163_u");
            double posZ = number(player, "posZ", "field_70161_v");
            double yaw = number(player, "rotationYaw", "field_70177_z");
            double inputMagnitude = Math.sqrt(forward * forward + strafe * strafe);
            double directionX = 0.0D;
            double directionZ = 0.0D;
            if (inputMagnitude > 0.01D) {
                double radians = Math.toRadians(yaw);
                double forwardX = -Math.sin(radians);
                double forwardZ = Math.cos(radians);
                double leftX = Math.cos(radians);
                double leftZ = Math.sin(radians);
                directionX = (forwardX * forward + leftX * strafe) / inputMagnitude;
                directionZ = (forwardZ * forward + leftZ * strafe) / inputMagnitude;
            }

            int groundY = (int) Math.floor(posY) - 1;
            if (scaffoldOnGround(player)) {
                godBridgeGroundY = groundY;
            } else if (godBridgeGroundY != Integer.MIN_VALUE) {
                // 跳跃期间仍沿桥面高度铺路，避免在玩家脚下叠出“跳一格搭一层”的柱子。
                groundY = godBridgeGroundY;
            }
            if (groundY < 0 || groundY > 255) {
                return;
            }
            if (groundY + 1.0D > posY + 0.001D) {
                return;
            }
            int currentX = (int) Math.floor(posX);
            int currentZ = (int) Math.floor(posZ);
            int aheadX = (int) Math.floor(posX + directionX);
            int aheadZ = (int) Math.floor(posZ + directionZ);

            // legit/balanced 不再把 rightClickDelayTimer 压到 0：
            // 只有 aggressive（或关闭 bypass）才允许 GodBridge 的 fastPlace。
            boolean fastPlaceAllowed = godBridgeFastPlace
                    && (!bypassEnabled || bypassProfile == PROFILE_AGGRESSIVE);
            if (!fastPlaceAllowed && godBridgeTimerOwned) {
                restoreGodBridgeTimer(mc);
            }
            if (godBridgeFastPlace && !fastPlaceAllowed) {
                OpenMaverDebug.noticeOnce("godbridge-fastplace-suppressed",
                        "GodBridge fastPlace suppressed by " + profileName() + " profile");
            }
            if (fastPlaceAllowed) {
                if (!writeIntField(mc, "rightClickDelayTimer", "field_71467_ac", 0)) {
                    failGodBridge("cannot write rightClickDelayTimer", null);
                    restoreGodBridgeTimer(mc);
                    return;
                }
                godBridgeTimerOwned = true;
            } else {
                Integer delayTimer = readIntField(mc, "rightClickDelayTimer", "field_71467_ac");
                if (delayTimer == null) {
                    failGodBridge("cannot read rightClickDelayTimer", null);
                    return;
                }
                if (delayTimer.intValue() > 0) {
                    return;
                }
            }

            float originalYaw = (float) yaw;
            float originalPitch = (float) number(player, "rotationPitch", "field_70125_A");
            Object originalHit = read(mc, "objectMouseOver", "field_71476_x", null);

            // 先尝试脚下：神桥真正需要的是“踏空瞬间在脚下补块”，此时身后的
            // 上一格方块侧面朝向玩家、能被正常 ray trace 命中。
            boolean placed = tryGodBridgePlacement(mc, player, world, currentX, groundY, currentZ,
                    originalYaw, originalPitch, originalHit);
            if (!placed && !godBridgeFailed && (aheadX != currentX || aheadZ != currentZ)) {
                placed = tryGodBridgePlacement(mc, player, world, aheadX, groundY, aheadZ,
                        originalYaw, originalPitch, originalHit);
            }
            if (godBridgeFailed) {
                restoreGodBridgeTimer(mc);
                return;
            }
            if (placed) {
                godBridgeCooldownTicks = clamp(godBridgeDelayTicks, 0, 4);
                rightClickUsedThisTick = true;
            }
        } catch (Throwable error) {
            failGodBridge("update failed", error);
            restoreGodBridgeTimer(mc);
        }
    }

    /**
     * 判断当前 {@code objectMouseOver} 是否落在方块边缘。读取不到命中点时
     * 退回为“只要瞄准方块即可”，避免因映射差异让整个模块不可用。
     */
    private static boolean isAimingAtBlockEdge(Minecraft mc) {
        Object hit = read(mc, "objectMouseOver", "field_71476_x", null);
        if (hit == null) {
            return false;
        }
        Object type = read(hit, "typeOfHit", "field_72313_a", null);
        if (type == null || !"BLOCK".equals(String.valueOf(type))) {
            return false;
        }
        Object hitVec = read(hit, "hitVec", "field_72307_f", null);
        Object blockPos = read(hit, "blockPos", "field_178783_e", null);
        Object sideHit = read(hit, "sideHit", "field_178784_b", null);
        if (hitVec == null || blockPos == null || sideHit == null) {
            return true;
        }
        Field xField = findField(hitVec, runtimeCandidates("xCoord", "field_72450_a"));
        Field yField = findField(hitVec, runtimeCandidates("yCoord", "field_72448_b"));
        Field zField = findField(hitVec, runtimeCandidates("zCoord", "field_72449_c"));
        if (xField == null || yField == null || zField == null) {
            return true;
        }
        Object blockX = call(blockPos, "getX", "func_177958_n", "n");
        Object blockY = call(blockPos, "getY", "func_177956_o", "o");
        Object blockZ = call(blockPos, "getZ", "func_177952_p", "p");
        if (!(blockX instanceof Number) || !(blockY instanceof Number) || !(blockZ instanceof Number)) {
            return true;
        }
        double localX = number(hitVec, "xCoord", "field_72450_a") - ((Number) blockX).intValue();
        double localY = number(hitVec, "yCoord", "field_72448_b") - ((Number) blockY).intValue();
        double localZ = number(hitVec, "zCoord", "field_72449_c") - ((Number) blockZ).intValue();

        String side = String.valueOf(sideHit);
        if ("UP".equals(side) || "DOWN".equals(side)) {
            return nearBlockEdge(localX) || nearBlockEdge(localZ);
        }
        if ("NORTH".equals(side) || "SOUTH".equals(side)) {
            return nearBlockEdge(localX) || nearBlockEdge(localY);
        }
        if ("WEST".equals(side) || "EAST".equals(side)) {
            return nearBlockEdge(localZ) || nearBlockEdge(localY);
        }
        return nearBlockEdge(localX) || nearBlockEdge(localZ);
    }

    private static boolean nearBlockEdge(double localCoordinate) {
        double clamped = Math.max(0.0D, Math.min(1.0D, localCoordinate));
        return clamped <= GOD_BRIDGE_EDGE_TOLERANCE
                || 1.0D - clamped <= GOD_BRIDGE_EDGE_TOLERANCE;
    }

    private static void stopGodBridge(Minecraft mc) {
        resetGodBridgeState();
        restoreGodBridgeTimer(mc);
    }

    private static void resetGodBridgeState() {
        godBridgeActive = false;
        godBridgeArmed = false;
        godBridgeCooldownTicks = 0;
        godBridgeGroundY = Integer.MIN_VALUE;
    }

    private static void restoreGodBridgeTimer(Minecraft mc) {
        if (!godBridgeTimerOwned) {
            return;
        }
        godBridgeTimerOwned = false;
        // 原版 rightClickMouse 点击后会把冷却设为 4；关闭神桥时恢复同一数值。
        writeIntField(mc, "rightClickDelayTimer", "field_71467_ac", 4);
    }

    private static void failGodBridge(String reason, Throwable error) {
        if (godBridgeFailed) {
            return;
        }
        godBridgeFailed = true;
        resetGodBridgeState();
        System.err.println("[OpenMaver] GodBridge disabled: " + reason
                + (error == null ? "" : " (" + error + ")"));
    }

    /**
     * 发送一个仅改变视角的 C05PacketPlayerLook，不改变客户端 position。
     *
     * <p>Grim 的 RotationPlace 在收到 C08 时直接用服务端当前 yaw/pitch 做射线校验。
     * 如果只在客户端临时改 rotation，服务端永远收不到，因此 C08 会被判失败。
     * 这里只在 C08 **之前**发一次 C05，让服务端先更新到用于 ray trace 的视角；
     * 不再发 placement 后的重复 C05，避免 AimDuplicateLook/Timer 额外的检测面。
     *
     * <p>放置逻辑在发送前调用这里；是否发送由调用方的设置决定。
     */
    private static boolean sendPlacementLook(Object player, float yaw, float pitch) {
        try {
            if (godBridgeLookPacketConstructor == null) {
                godBridgeLookPacketType = Class.forName(
                        "net.minecraft.network.play.client.C03PacketPlayer$C05PacketPlayerLook");
                godBridgeLookPacketConstructor = godBridgeLookPacketType.getConstructor(
                        Float.TYPE, Float.TYPE, Boolean.TYPE);
            }
            Object sendQueue = read(player, "sendQueue", "field_71174_a", null);
            if (sendQueue == null) {
                return false;
            }
            Object packet = godBridgeLookPacketConstructor.newInstance(
                    Float.valueOf(yaw),
                    Float.valueOf(pitch),
                    Boolean.valueOf(scaffoldOnGround(player)));
            return callVoid(sendQueue, "addToSendQueue", "func_147297_a", null, packet);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryGodBridgePlacement(Minecraft mc, Object player, Object world,
            int targetX, int targetY, int targetZ,
            float originalYaw, float originalPitch, Object originalHit) {
        return tryPlaceBlock(mc, player, world, targetX, targetY, targetZ,
                originalYaw, originalPitch, originalHit, godBridgeAutoRotate, true);
    }

    /**
     * 尝试在指定空气格放置方块。会依次检查六个相邻支撑面，只点击从眼睛方向
     * 真正可见、且 {@code objectMouseOver} 校验通过的面。
     *
     * <p>{@code godBridge} 用于区分搭桥的放置角度细节，{@code autoRotate}
     * 决定是否允许临时转向目标面。安全 profile
     * 或 {@code autoRotate=false} 时一律回退为真实准星放置。
     */
    private static boolean tryPlaceBlock(Minecraft mc, Object player, Object world,
            int targetX, int targetY, int targetZ,
            float originalYaw, float originalPitch, Object originalHit,
            boolean autoRotate, boolean godBridge) {
        Object targetPos = scaffoldBlockPos(targetX, targetY, targetZ, godBridge);
        if (targetPos == null) {
            return false;
        }
        Boolean targetAir = scaffoldAir(world, targetPos);
        if (targetAir == null) {
            failGodBridge("cannot read target block", null);
            return false;
        }
        if (!targetAir.booleanValue()) {
            return false;
        }

        Object[] facings = scaffoldFacings(godBridge);
        if (facings == null) {
            return false;
        }
        boolean legitAim = godBridgeLegitAim;
        if (!legitAim && (safeProfile() || !autoRotate)) {
            // legit/balanced 下不允许“只改客户端 rotation 再 C08”的路径：
            // 服务端看到的 yaw 仍是上一 tick 的，RotationPlace 会直接失败。
            // autoRotate=false 也改为完全使用真实准星，避免永久残留 rotation。
            if (safeProfile()) {
                OpenMaverDebug.noticeOnce("placement-rotation-suppressed",
                        "placement auto-rotation suppressed by " + profileName() + " profile");
            }
            legitAim = true;
        }
        if (legitAim) {
            return tryPlaceWithCurrentAim(mc, player, world, targetPos, targetX, targetY, targetZ,
                    facings, godBridge);
        }
        double eyeX = number(player, "posX", "field_70165_t");
        double eyeY = number(player, "posY", "field_70163_u") + eyeHeight(player);
        double eyeZ = number(player, "posZ", "field_70161_v");

        // 收集所有“从眼睛方向可见”的支撑面，按可见程度排序后逐个校验。
        int[] order = new int[6];
        double[] dots = new double[6];
        int count = 0;
        for (int i = 0; i < 6; i++) {
            int supportX = targetX + SCAFFOLD_FACING_OFFSETS[i][0];
            int supportY = targetY + SCAFFOLD_FACING_OFFSETS[i][1];
            int supportZ = targetZ + SCAFFOLD_FACING_OFFSETS[i][2];
            Object supportPos = scaffoldBlockPos(supportX, supportY, supportZ, godBridge);
            if (supportPos == null) {
                return false;
            }
            Boolean support = scaffoldSupport(world, supportPos);
            if (support == null) {
                failGodBridge("cannot read support block", null);
                return false;
            }
            if (!support.booleanValue()) {
                continue;
            }
            int sideIndex = i ^ 1;
            double faceX = supportX + 0.5D + SCAFFOLD_FACING_OFFSETS[sideIndex][0] * 0.5D;
            double faceY = supportY + 0.5D + SCAFFOLD_FACING_OFFSETS[sideIndex][1] * 0.5D;
            double faceZ = supportZ + 0.5D + SCAFFOLD_FACING_OFFSETS[sideIndex][2] * 0.5D;
            double dot = (eyeX - faceX) * SCAFFOLD_FACING_OFFSETS[sideIndex][0]
                    + (eyeY - faceY) * SCAFFOLD_FACING_OFFSETS[sideIndex][1]
                    + (eyeZ - faceZ) * SCAFFOLD_FACING_OFFSETS[sideIndex][2];
            // 神桥需要抓“刚踏空”时身后方块侧面，眼睛往往只比侧面高一点点；
            // 放宽到 >0 才能在踏空后的第一个 tick 就命中。最终仍由 ray trace 校验。
            double minDot = godBridge ? 0.0D : 0.02D;
            if (dot <= minDot) {
                continue;
            }
            order[count] = i;
            dots[count] = dot;
            count++;
        }
        for (int a = 1; a < count; a++) {
            int keyIndex = order[a];
            double keyDot = dots[a];
            int b = a - 1;
            while (b >= 0 && dots[b] < keyDot) {
                order[b + 1] = order[b];
                dots[b + 1] = dots[b];
                b--;
            }
            order[b + 1] = keyIndex;
            dots[b + 1] = keyDot;
        }

        Object renderer = read(mc, "entityRenderer", "field_71460_t", null);
        if (renderer == null) {
            failGodBridge("cannot read entityRenderer", null);
            return false;
        }

        boolean success = false;
        boolean rotated = false;
        try {
            for (int k = 0; k < count; k++) {
                int i = order[k];
                int sideIndex = i ^ 1;
                int supportX = targetX + SCAFFOLD_FACING_OFFSETS[i][0];
                int supportY = targetY + SCAFFOLD_FACING_OFFSETS[i][1];
                int supportZ = targetZ + SCAFFOLD_FACING_OFFSETS[i][2];
                double faceX = supportX + 0.5D + SCAFFOLD_FACING_OFFSETS[sideIndex][0] * 0.5D;
                double faceY = supportY + 0.5D + SCAFFOLD_FACING_OFFSETS[sideIndex][1] * 0.5D;
                double faceZ = supportZ + 0.5D + SCAFFOLD_FACING_OFFSETS[sideIndex][2] * 0.5D;

                // 稍微指向支撑方块内部，保证射线确实穿过目标面，而不是停在面边界上。
                // 神桥的侧面几乎与视线平行，inset 过大会先打到方块顶面，导致命中面校验失败。
                double inset = godBridge ? 0.002D : 0.05D;
                double dx = faceX - SCAFFOLD_FACING_OFFSETS[sideIndex][0] * inset - eyeX;
                double dy = faceY - SCAFFOLD_FACING_OFFSETS[sideIndex][1] * inset - eyeY;
                double dz = faceZ - SCAFFOLD_FACING_OFFSETS[sideIndex][2] * inset - eyeZ;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal < 1.0E-4D && Math.abs(dy) < 1.0E-4D) {
                    continue;
                }
                // 保持在 [-180, 180)：Grim 的 AimModulo360 会检查越界/绕圈视角。
                float yaw = wrapDegrees((float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F);
                float pitch = (float) (-Math.atan2(dy, horizontal) * 180.0D / Math.PI);
                pitch = Math.max(-90.0F, Math.min(90.0F, pitch));

                setNumber(player, "rotationYaw", "field_70177_z", yaw);
                setNumber(player, "rotationPitch", "field_70125_A", pitch);
                rotated = true;

                // 让游戏重新做一次视线检测；只有结果精确指向支撑面时才真正右键。
                call(renderer, "getMouseOver", "func_78473_a", "a", Float.valueOf(1.0F));
                Object hit = read(mc, "objectMouseOver", "field_71476_x", null);
                if (!scaffoldMouseOverMatches(hit, supportX, supportY, supportZ, facings[sideIndex])) {
                    continue;
                }
                Boolean stillAir = scaffoldAir(world, targetPos);
                if (stillAir == null) {
                    failGodBridge("cannot re-read target block", null);
                    return false;
                }
                if (!stillAir.booleanValue()) {
                    continue;
                }
                boolean packetRotation = godBridgePacketRotation;
                if (packetRotation && safeProfile()) {
                    packetRotation = false;
                    OpenMaverDebug.noticeOnce("placement-c05-suppressed",
                            "placement C05 packet rotation suppressed by " + profileName() + " profile");
                }
                if (packetRotation && !sendPlacementLook(player, yaw, pitch)) {
                    failGodBridge("cannot send pre-placement rotation packet", null);
                    return false;
                }
                call(mc, "rightClickMouse", "func_147121_ag", "ax");
                recordPlacement(player, targetX, targetY, targetZ, facings[sideIndex],
                        false, packetRotation);
                success = true;
                return true;
            }
            return false;
        } finally {
            if (rotated) {
                // 无论 autoRotate 设置如何，都恢复原视角和 objectMouseOver，
                // 避免关闭模块/切换 GUI 后残留临时 rotation。
                setNumber(player, "rotationYaw", "field_70177_z", originalYaw);
                setNumber(player, "rotationPitch", "field_70125_A", originalPitch);
                writeObjectField(mc, "objectMouseOver", "field_71476_x", originalHit);
            }
        }
    }

    /**
     * Legit 模式：完全使用玩家当前准星，不改 rotation、不补发 flying packet。
     * 只有 {@code objectMouseOver} 已经命中目标格的支撑面时才右键，因此服务端
     * 看到的就是玩家真实视角，不会触发 RotationPlace / Aim / Timer。
     */
    private static boolean tryPlaceWithCurrentAim(Minecraft mc, Object player, Object world,
            Object targetPos, int targetX, int targetY, int targetZ, Object[] facings,
            boolean godBridge) {
        Object renderer = read(mc, "entityRenderer", "field_71460_t", null);
        if (renderer == null) {
            failGodBridge("cannot read entityRenderer", null);
            return false;
        }
        // 用当前真实 rotation 刷新 objectMouseOver。
        call(renderer, "getMouseOver", "func_78473_a", "a", Float.valueOf(1.0F));
        Object hit = read(mc, "objectMouseOver", "field_71476_x", null);
        for (int i = 0; i < 6; i++) {
            int supportX = targetX + SCAFFOLD_FACING_OFFSETS[i][0];
            int supportY = targetY + SCAFFOLD_FACING_OFFSETS[i][1];
            int supportZ = targetZ + SCAFFOLD_FACING_OFFSETS[i][2];
            int sideIndex = i ^ 1;
            if (!scaffoldMouseOverMatches(hit, supportX, supportY, supportZ, facings[sideIndex])) {
                continue;
            }
            Boolean stillAir = scaffoldAir(world, targetPos);
            if (stillAir == null) {
                failGodBridge("cannot re-read target block", null);
                return false;
            }
            if (!stillAir.booleanValue()) {
                return false;
            }
            call(mc, "rightClickMouse", "func_147121_ag", "ax");
            recordPlacement(player, targetX, targetY, targetZ, facings[sideIndex], true, false);
            return true;
        }
        return false;
    }

    /** 记录一次成功放置，并更新 debug 用的放置间隔（tick）。 */
    private static void recordPlacement(Object player, int x, int y, int z, Object face,
            boolean legitAim, boolean packetRotation) {
        long interval = lastPlacementTick == Long.MIN_VALUE
                ? 0L : clientTick - lastPlacementTick;
        lastPlacementTick = clientTick;
        OpenMaverDebug.place(x, y, z, String.valueOf(face),
                (float) number(player, "rotationYaw", "field_70177_z"),
                (float) number(player, "rotationPitch", "field_70125_A"),
                legitAim, packetRotation, (int) Math.max(0L, interval));
    }

    private static Object scaffoldBlockPos(int x, int y, int z, boolean godBridge) {
        try {
            if (scaffoldBlockPosConstructor == null) {
                if (scaffoldBlockPosType == null) {
                    scaffoldBlockPosType = Class.forName("net.minecraft.util.BlockPos");
                }
                scaffoldBlockPosConstructor = scaffoldBlockPosType.getConstructor(
                        Integer.TYPE, Integer.TYPE, Integer.TYPE);
            }
            return scaffoldBlockPosConstructor.newInstance(
                    Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
        } catch (Throwable error) {
            failGodBridge("cannot create BlockPos", error);
            return null;
        }
    }

    private static Object[] scaffoldFacings(boolean godBridge) {
        if (scaffoldFacings != null) {
            return scaffoldFacings;
        }
        try {
            Class<?> type = Class.forName("net.minecraft.util.EnumFacing");
            Object[] constants = type.getEnumConstants();
            Object[] ordered = new Object[6];
            for (int i = 0; i < 6; i++) {
                Object found = null;
                try {
                    Field field = type.getField(SCAFFOLD_FACING_NAMES[i]);
                    found = field.get(null);
                } catch (Throwable ignored) {
                    // Fall back to the enum constants below.
                }
                if (found == null && constants != null) {
                    for (int c = 0; c < constants.length; c++) {
                        if (SCAFFOLD_FACING_NAMES[i].equalsIgnoreCase(String.valueOf(constants[c]))) {
                            found = constants[c];
                            break;
                        }
                    }
                    // 1.8.9 declaration order: DOWN, UP, NORTH, SOUTH, WEST, EAST
                    if (found == null && constants.length == 6) {
                        found = constants[i];
                    }
                }
                if (found == null) {
                    failGodBridge("cannot resolve EnumFacing." + SCAFFOLD_FACING_NAMES[i], null);
                    return null;
                }
                ordered[i] = found;
            }
            scaffoldFacings = ordered;
            return ordered;
        } catch (Throwable error) {
            failGodBridge("cannot load EnumFacing", error);
            return null;
        }
    }

    private static Boolean scaffoldAir(Object world, Object blockPos) {
        Object value = call(world, "isAirBlock", "func_175623_d", "d", blockPos);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static Boolean scaffoldSupport(Object world, Object blockPos) {
        // 1.8.9 的 Block.isFullCube() 对空气也返回 true，必须先排除空气。
        Object air = call(world, "isAirBlock", "func_175623_d", "d", blockPos);
        if (!(air instanceof Boolean)) {
            return null;
        }
        if (((Boolean) air).booleanValue()) {
            return Boolean.FALSE;
        }
        Object state = call(world, "getBlockState", "func_180495_p", "p", blockPos);
        if (state == null) {
            return null;
        }
        Object block = call(state, "getBlock", "func_177230_c", "c");
        if (block == null) {
            return null;
        }
        Object fullCube = call(block, "isFullCube", "func_149686_d", "d");
        return fullCube instanceof Boolean ? (Boolean) fullCube : null;
    }

    private static boolean scaffoldMouseOverMatches(Object hit, int x, int y, int z, Object side) {
        if (hit == null) {
            return false;
        }
        Object blockPos = read(hit, "blockPos", "field_178783_e", null);
        Object sideHit = read(hit, "sideHit", "field_178784_b", null);
        if (blockPos == null || sideHit == null || sideHit != side) {
            return false;
        }
        return scaffoldBlockPosMatches(blockPos, x, y, z);
    }

    private static boolean scaffoldBlockPosMatches(Object blockPos, int x, int y, int z) {
        Object posX = call(blockPos, "getX", "func_177958_n", "n");
        Object posY = call(blockPos, "getY", "func_177956_o", "o");
        Object posZ = call(blockPos, "getZ", "func_177952_p", "p");
        return posX instanceof Number && posY instanceof Number && posZ instanceof Number
                && ((Number) posX).intValue() == x
                && ((Number) posY).intValue() == y
                && ((Number) posZ).intValue() == z;
    }

    private static boolean scaffoldOnGround(Object player) {
        Object value = read(player, "onGround", "field_70122_E", null);
        return Boolean.TRUE.equals(value);
    }

    private static boolean writeObjectField(Object target, String mcp, String srg, Object value) {
        Field field = findField(target, runtimeCandidates(mcp, srg));
        if (field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void refreshSettings() {
        if (settingsCheckTicks++ < 20) {
            return;
        }
        settingsCheckTicks = 0;
        if (settingsDirty) {
            settingsDirty = false;
            saveSettings();
            return;
        }
        String path = System.getProperty("openmaver.features", "config/openmaver.properties");
        File file = new File(path);
        long modified = file.isFile() ? file.lastModified() : -1L;
        if (modified == settingsModified) {
            return;
        }
        settingsModified = modified;
        Properties values = new Properties();
        if (file.isFile()) {
            try (FileInputStream input = new FileInputStream(file)) {
                values.load(input);
            } catch (Exception error) {
                System.err.println("[OpenMaver] Cannot read feature settings: " + error);
                return;
            }
        }
        int settingsVersion = readInt(values, "settings.version", 1, 999);
        aimEnabled = readBoolean(values, "aim", false);
        espEnabled = readBoolean(values, "esp", false);
        bypassEnabled = readBoolean(values, "bypass.enabled", true);
        bypassProfile = readProfile(values, "bypass.profile", "balanced");
        debugEnabled = readBoolean(values, "debug.enabled", false);
        OpenMaverDebug.setEnabled(debugEnabled);
        aimSpeed = clamp(readFloat(values, "aim.speed", 0.35F), 0.05F, 1.0F);
        aimRange = clamp(readFloat(values, "aim.range", 6.0F), 3.0F, 12.0F);
        aimFov = clamp(readFloat(values, "aim.fov", 180.0F), 30.0F, 180.0F);
        aimMode = readInt(values, "aim.mode", 0, 1);
        aimTarget = readInt(values, "aim.target", 0, 2);
        aimRequireClick = readBoolean(values, "aim.requireClick", true);
        aimGcdEnabled = readBoolean(values, "aim.gcd", true);
        aimMaxStep = clamp(readFloat(values, "aim.maxStep", 35.0F), 5.0F, 90.0F);
        espRange = clamp(readFloat(values, "esp.range", 48.0F), 16.0F, 128.0F);
        espThickness = clamp(readFloat(values, "esp.thickness", 1.5F), 0.5F, 4.0F);
        espOpacity = clamp(readFloat(values, "esp.opacity", 0.9F), 0.2F, 1.0F);
        espColor = readInt(values, "esp.color", 0, 3);
        espPlayersOnly = readBoolean(values, "esp.playersOnly", false);
        targetHudRange = clamp(readFloat(values, "targethud.range", 32.0F), 8.0F, 64.0F);
        targetHudScale = clamp(readFloat(values, "targethud.scale", 1.0F), 0.7F, 1.4F);
        targetHudPlayersOnly = readBoolean(values, "targethud.playersOnly", false);
        playerXrayRange = clamp(readFloat(values, "playerxray.range", 64.0F), 8.0F, 128.0F);
        playerXrayColor = readInt(values, "playerxray.color", 0, 4);
        sprintOnlyForward = readBoolean(values, "sprint.onlyForward", true);
        sprintPauseSneak = readBoolean(values, "sprint.pauseSneak", true);
        sprintPauseItem = readBoolean(values, "sprint.pauseItem", true);
        autoclickerCpsMin = clamp(readInt(values, "autoclicker.cpsMin", 6, 20), 1, 20);
        autoclickerCpsMax = clamp(readInt(values, "autoclicker.cpsMax", 10, 20), 1, 20);
        if (autoclickerCpsMin > autoclickerCpsMax) {
            int swap = autoclickerCpsMin;
            autoclickerCpsMin = autoclickerCpsMax;
            autoclickerCpsMax = swap;
        }
        autoclickerLeft = readBoolean(values, "autoclicker.left", true);
        autoclickerRight = readBoolean(values, "autoclicker.right", false);
        autoclickerRequireHold = readBoolean(values, "autoclicker.requireHold", true);
        autoclickerWaitForHurt = readBoolean(values, "autoclicker.waitForHurt", true);
        autoclickerAttackMode = readAttackMode(values);
        if (settingsVersion < 3) {
            // v2 为了“模块一开就点击”强制把 requireHold 写成 false，导致
            // 玩家不碰鼠标也会持续攻击（按一次 R 后尤其明显）。v3 恢复
            // 按住才连点；同时默认等待目标的受击无敌帧，避免无效 C02。
            autoclickerRequireHold = true;
            autoclickerWaitForHurt = true;
            settingsDirty = true;
        }
        autoclickerHumanize = readBoolean(values, "autoclicker.humanize", true);
        reachExtra = clamp(readFloat(values, "reach.extra", 0.0F), 0.0F, 0.5F);
        reachChance = clamp(readFloat(values, "reach.chance", 100.0F), 1.0F, 100.0F);
        criticalsMode = readCriticalsMode(values, "criticals.mode", CRITICALS_JUMP);
        criticalsStopSprint = readBoolean(values, "criticals.stopSprint", true);
        fastPlaceDelayTicks = clamp(readInt(values, "fastplace.delayTicks", 4, 4), 0, 4);
        if (settingsVersion < 4) {
            // v3 及更早默认 JUMP：每次地面攻击都先起跳并延迟一 tick，
            // 会把 AutoClicker 的 14~17 CPS 压成 6~9。v4 曾改为 ALWAYS（packet），
            // v6 起重新以 JUMP 为默认（见下方迁移）。
            criticalsMode = CRITICALS_JUMP;
            settingsDirty = true;
        }
        if (settingsVersion < 5) {
            // v4 的对测配置里 stopSprint 曾默认 / 被手动设成 false。
            // 1.9+ 服务端在攻击瞬间只要玩家仍在冲刺就不会判定暴击，而
            // AutoSprint 会一直保持冲刺——这种组合会让 Criticals 完全失效，
            // 伪造出来的移动包还会持续触发 GroundSpoof / Post / Timer。
            // v5 起统一恢复 STOP SPRINT，玩家确实需要 1.8 的冲刺击退时可再关掉。
            criticalsStopSprint = true;
            settingsDirty = true;
        }
        if (settingsVersion < 6) {
            // v5 及更早的默认值会被 Matrix 直接标记：
            // - criticals ALWAYS/NCP 每次攻击额外补 2~3 条 C04
            //   -> ka.critical.ground / hop / modify + bp.timer；
            // - fastplace 2 tick 间隔 -> block.fastplace。
            // v6 起迁移为真实起跳暴击与原版 4 tick 右键间隔。
            criticalsMode = CRITICALS_JUMP;
            if (fastPlaceDelayTicks < 4) {
                fastPlaceDelayTicks = 4;
            }
            settingsDirty = true;
        }
        fastPlaceOnlyBlocks = readBoolean(values, "fastplace.onlyBlocks", true);
        godBridgeDelayTicks = clamp(readInt(values, "godbridge.delayTicks", 0, 4), 0, 4);
        godBridgeFastPlace = readBoolean(values, "godbridge.fastPlace", true);
        godBridgeRequireEdgeAim = readBoolean(values, "godbridge.requireEdgeAim", true);
        godBridgeAutoRotate = readBoolean(values, "godbridge.autoRotate", true);
        godBridgeLegitAim = readBoolean(values, "godbridge.legitAim", true);
        godBridgePacketRotation = readBoolean(values, "godbridge.packetRotation", false);
        fakePlayerCount = clamp(readInt(values, "fakeplayer.count", 1, 5), 1, 5);
        fakePlayerDistance = clamp(readFloat(values, "fakeplayer.distance", 4.0F), 2.0F, 10.0F);
        fakePlayerShowName = readBoolean(values, "fakeplayer.showName", true);
        resetAutoClickerTimers();
        resetGodBridgeState();
    }

    public static boolean isAimEnabled() {
        return aimEnabled;
    }

    public static void toggleAim() {
        aimEnabled = !aimEnabled;
        saveSettings();
    }

    public static boolean isEspEnabled() {
        return espEnabled;
    }

    public static void toggleEsp() {
        espEnabled = !espEnabled;
        saveSettings();
    }

    // ==================================================================
    // Grim profile（ClickGUI / properties 共用）
    // ==================================================================

    public static boolean isBypassEnabled() {
        return bypassEnabled;
    }

    public static void setBypassEnabled(boolean value) {
        bypassEnabled = value;
        resetAutoClickerTimers();
        markDirty();
    }

    public static int getBypassProfile() {
        return bypassProfile;
    }

    public static void setBypassProfile(int value) {
        bypassProfile = clamp(value, PROFILE_LEGIT, PROFILE_AGGRESSIVE);
        resetAutoClickerTimers();
        markDirty();
    }

    public static String getBypassProfileName() {
        return profileName();
    }

    /** 当前实际生效的实体攻击距离（Grim 扩张箱度量）。 */
    public static float getEffectiveReachDistance() {
        if (!bypassEnabled) {
            return (float) Math.min(REACH_MAX_DISTANCE, REACH_BASE_DISTANCE + reachExtra);
        }
        if (bypassProfile == PROFILE_AGGRESSIVE) {
            return (float) Math.min(REACH_AGGRESSIVE_CAP, REACH_BASE_DISTANCE + reachExtra);
        }
        return (float) Math.min(REACH_SAFE_CAP, REACH_BASE_DISTANCE + reachExtra);
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setDebugEnabled(boolean value) {
        debugEnabled = value;
        OpenMaverDebug.setEnabled(value);
        markDirty();
    }

    private static boolean safeProfile() {
        return bypassEnabled && bypassProfile != PROFILE_AGGRESSIVE;
    }

    private static String profileName() {
        return PROFILE_NAMES[clamp(bypassProfile, PROFILE_LEGIT, PROFILE_AGGRESSIVE)];
    }

    // ==================================================================
    // GUI 可编辑参数（内存即时生效，最多 1 秒落盘一次）
    // ==================================================================

    public static float getAimSpeed() { return aimSpeed; }
    public static void setAimSpeed(float value) { aimSpeed = clamp(value, 0.05F, 1.0F); markDirty(); }

    public static float getAimRange() { return aimRange; }
    public static void setAimRange(float value) { aimRange = clamp(value, 3.0F, 12.0F); markDirty(); }

    public static float getAimFov() { return aimFov; }
    public static void setAimFov(float value) { aimFov = clamp(value, 30.0F, 180.0F); markDirty(); }

    public static int getAimMode() { return aimMode; }
    public static void setAimMode(int value) { aimMode = clamp(value, 0, 1); markDirty(); }

    public static int getAimTarget() { return aimTarget; }
    public static void setAimTarget(int value) { aimTarget = clamp(value, 0, 2); markDirty(); }

    public static boolean isAimRequireClick() { return aimRequireClick; }
    public static void setAimRequireClick(boolean value) { aimRequireClick = value; markDirty(); }

    public static boolean isAimGcdEnabled() { return aimGcdEnabled; }
    public static void setAimGcdEnabled(boolean value) { aimGcdEnabled = value; markDirty(); }

    public static float getAimMaxStep() { return aimMaxStep; }
    public static void setAimMaxStep(float value) {
        aimMaxStep = clamp(value, 5.0F, 90.0F);
        markDirty();
    }

    public static float getEspRange() { return espRange; }
    public static void setEspRange(float value) { espRange = clamp(value, 16.0F, 128.0F); markDirty(); }

    public static float getEspThickness() { return espThickness; }
    public static void setEspThickness(float value) { espThickness = clamp(value, 0.5F, 4.0F); markDirty(); }

    public static float getEspOpacity() { return espOpacity; }
    public static void setEspOpacity(float value) { espOpacity = clamp(value, 0.2F, 1.0F); markDirty(); }

    public static int getEspColor() { return espColor; }
    public static void setEspColor(int value) { espColor = clamp(value, 0, 3); markDirty(); }

    public static boolean isEspPlayersOnly() { return espPlayersOnly; }
    public static void setEspPlayersOnly(boolean value) { espPlayersOnly = value; markDirty(); }

    public static float getTargetHudRange() { return targetHudRange; }
    public static void setTargetHudRange(float value) {
        targetHudRange = clamp(value, 8.0F, 64.0F);
        markDirty();
    }

    public static float getTargetHudScale() { return targetHudScale; }
    public static void setTargetHudScale(float value) {
        targetHudScale = clamp(value, 0.7F, 1.4F);
        markDirty();
    }

    public static boolean isTargetHudPlayersOnly() { return targetHudPlayersOnly; }
    public static void setTargetHudPlayersOnly(boolean value) {
        targetHudPlayersOnly = value;
        markDirty();
    }

    public static float getPlayerXrayRange() { return playerXrayRange; }
    public static void setPlayerXrayRange(float value) {
        playerXrayRange = clamp(value, 8.0F, 128.0F);
        markDirty();
    }

    public static int getPlayerXrayColor() { return playerXrayColor; }
    public static void setPlayerXrayColor(int value) {
        playerXrayColor = clamp(value, 0, 4);
        markDirty();
    }

    public static boolean isSprintOnlyForward() { return sprintOnlyForward; }
    public static void setSprintOnlyForward(boolean value) { sprintOnlyForward = value; markDirty(); }

    public static boolean isSprintPauseSneak() { return sprintPauseSneak; }
    public static void setSprintPauseSneak(boolean value) { sprintPauseSneak = value; markDirty(); }

    public static boolean isSprintPauseItem() { return sprintPauseItem; }
    public static void setSprintPauseItem(boolean value) { sprintPauseItem = value; markDirty(); }

    public static int getAutoClickerCpsMin() { return autoclickerCpsMin; }
    public static void setAutoClickerCpsMin(int value) {
        autoclickerCpsMin = clamp(value, 1, 20);
        if (autoclickerCpsMin > autoclickerCpsMax) {
            autoclickerCpsMax = autoclickerCpsMin;
        }
        resetAutoClickerTimers();
        markDirty();
    }

    public static int getAutoClickerCpsMax() { return autoclickerCpsMax; }
    public static void setAutoClickerCpsMax(int value) {
        autoclickerCpsMax = clamp(value, 1, 20);
        if (autoclickerCpsMax < autoclickerCpsMin) {
            autoclickerCpsMin = autoclickerCpsMax;
        }
        resetAutoClickerTimers();
        markDirty();
    }

    public static boolean isAutoClickerLeft() { return autoclickerLeft; }
    public static void setAutoClickerLeft(boolean value) {
        autoclickerLeft = value;
        resetAutoClickerTimers();
        markDirty();
    }

    public static boolean isAutoClickerRight() { return autoclickerRight; }
    public static void setAutoClickerRight(boolean value) {
        autoclickerRight = value;
        resetAutoClickerTimers();
        markDirty();
    }

    public static boolean isAutoClickerRequireHold() { return autoclickerRequireHold; }
    public static void setAutoClickerRequireHold(boolean value) {
        autoclickerRequireHold = value;
        resetAutoClickerTimers();
        markDirty();
    }

    public static boolean isAutoClickerWaitForHurt() { return autoclickerWaitForHurt; }
    public static void setAutoClickerWaitForHurt(boolean value) {
        autoclickerWaitForHurt = value;
        resetAutoClickerTimers();
        markDirty();
    }

    public static int getAutoClickerAttackMode() { return autoclickerAttackMode; }
    public static void setAutoClickerAttackMode(int value) {
        autoclickerAttackMode = clamp(value, ATTACK_MODE_FULL_CHARGE, ATTACK_MODE_VANILLA);
        resetAutoClickerTimers();
        markDirty();
    }

    public static String getAutoClickerAttackModeName() {
        return ATTACK_MODE_NAMES[clamp(autoclickerAttackMode,
                ATTACK_MODE_FULL_CHARGE, ATTACK_MODE_VANILLA)];
    }

    public static boolean isAutoClickerHumanize() { return autoclickerHumanize; }
    public static void setAutoClickerHumanize(boolean value) {
        autoclickerHumanize = value;
        resetAutoClickerTimers();
        markDirty();
    }

    public static float getReachExtra() { return reachExtra; }
    public static void setReachExtra(float value) {
        reachExtra = clamp(value, 0.0F, 0.5F);
        markDirty();
    }

    public static float getReachChance() { return reachChance; }
    public static void setReachChance(float value) {
        reachChance = clamp(value, 1.0F, 100.0F);
        markDirty();
    }

    public static int getCriticalsMode() { return criticalsMode; }
    public static void setCriticalsMode(int value) {
        criticalsMode = clamp(value, CRITICALS_PACKET, CRITICALS_LAST);
        markDirty();
    }

    public static String getCriticalsModeName() {
        return CRITICALS_MODE_NAMES[clamp(criticalsMode, CRITICALS_PACKET, CRITICALS_LAST)];
    }

    /** LEGIT 档会完全跳过刀暴。 */
    public static boolean isCriticalsDisabledByProfile() {
        return bypassEnabled && bypassProfile == PROFILE_LEGIT;
    }

    /** BALANCED 档会把 packet 模式降级为真实起跳（矩阵/Grim 下 packet 刀暴必吃 flag）。 */
    public static boolean isCriticalsPacketSuppressed() {
        if (!bypassEnabled || bypassProfile == PROFILE_AGGRESSIVE) {
            return false;
        }
        return clamp(criticalsMode, CRITICALS_PACKET, CRITICALS_LAST) != CRITICALS_JUMP;
    }

    public static boolean isCriticalsStopSprint() { return criticalsStopSprint; }
    public static void setCriticalsStopSprint(boolean value) {
        criticalsStopSprint = value;
        markDirty();
    }

    public static int getFastPlaceDelayTicks() { return fastPlaceDelayTicks; }
    public static void setFastPlaceDelayTicks(int value) {
        fastPlaceDelayTicks = clamp(value, 0, 4);
        markDirty();
    }

    public static boolean isFastPlaceOnlyBlocks() { return fastPlaceOnlyBlocks; }
    public static void setFastPlaceOnlyBlocks(boolean value) {
        fastPlaceOnlyBlocks = value;
        markDirty();
    }

    public static int getGodBridgeDelayTicks() { return godBridgeDelayTicks; }
    public static void setGodBridgeDelayTicks(int value) {
        godBridgeDelayTicks = clamp(value, 0, 4);
        godBridgeCooldownTicks = 0;
        markDirty();
    }

    public static boolean isGodBridgeFastPlace() { return godBridgeFastPlace; }
    public static void setGodBridgeFastPlace(boolean value) {
        godBridgeFastPlace = value;
        markDirty();
    }

    public static boolean isGodBridgeRequireEdgeAim() { return godBridgeRequireEdgeAim; }
    public static void setGodBridgeRequireEdgeAim(boolean value) {
        godBridgeRequireEdgeAim = value;
        godBridgeArmed = false;
        markDirty();
    }

    public static boolean isGodBridgeAutoRotate() { return godBridgeAutoRotate; }
    public static void setGodBridgeAutoRotate(boolean value) {
        godBridgeAutoRotate = value;
        markDirty();
    }

    public static boolean isGodBridgeLegitAim() { return godBridgeLegitAim; }
    public static void setGodBridgeLegitAim(boolean value) {
        godBridgeLegitAim = value;
        godBridgeArmed = false;
        markDirty();
    }

    public static boolean isGodBridgePacketRotation() { return godBridgePacketRotation; }
    public static void setGodBridgePacketRotation(boolean value) {
        godBridgePacketRotation = value;
        godBridgeArmed = false;
        markDirty();
    }

    public static int getFakePlayerCount() { return fakePlayerCount; }
    public static void setFakePlayerCount(int value) {
        fakePlayerCount = clamp(value, 1, 5);
        markDirty();
    }

    public static float getFakePlayerDistance() { return fakePlayerDistance; }
    public static void setFakePlayerDistance(float value) {
        fakePlayerDistance = clamp(value, 2.0F, 10.0F);
        markDirty();
    }

    public static boolean isFakePlayerShowName() { return fakePlayerShowName; }
    public static void setFakePlayerShowName(boolean value) {
        fakePlayerShowName = value;
        markDirty();
    }

    /** GUI 关闭时立即落盘，避免最后的拖动丢失。 */
    public static void flushSettings() {
        if (settingsDirty) {
            settingsDirty = false;
            saveSettings();
        }
    }

    /** 批量关闭所有 MC 侧功能（供 Panic / 关闭流程使用）。 */
    public static void disableAll() {
        aimEnabled = false;
        espEnabled = false;
        resetAutoClickerTimers();
        resetReachState();
        resetGodBridgeState();
        criticalsSprintWasActive = false;
        criticalsLastAttackNanos = 0L;
        resetCriticalsJumpState();
        rightClickUsedThisTick = false;
        autoclickerClickedThisTick = false;
        lastPlacementTick = Long.MIN_VALUE;
        godBridgeTimerOwned = false;
        FAKE_PLAYERS.clear();
        fakePlayerSpawnedCount = -1;
        fakePlayerSpawnedDistance = -1.0F;
        saveSettings();
    }

    private static void markDirty() {
        settingsDirty = true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean readBoolean(Properties values, String key, boolean fallback) {
        String raw = values.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    /** 读取 profile：支持 legit/balanced/aggressive，也接受 0/1/2。 */
    private static int readProfile(Properties values, String key, String fallback) {
        String raw = values.getProperty(key, fallback);
        if (raw == null) {
            return PROFILE_BALANCED;
        }
        raw = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < PROFILE_KEYS.length; i++) {
            if (PROFILE_KEYS[i].equals(raw)) {
                return i;
            }
        }
        try {
            return clamp(Integer.parseInt(raw), PROFILE_LEGIT, PROFILE_AGGRESSIVE);
        } catch (NumberFormatException ignored) {
            return PROFILE_BALANCED;
        }
    }

    /** 读取 Criticals 模式：支持 packet/ncp/falling/grim/jump，也接受 0~4。 */
    private static int readCriticalsMode(Properties values, String key, int fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        raw = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < CRITICALS_MODE_KEYS.length; i++) {
            if (CRITICALS_MODE_KEYS[i].equals(raw)) {
                return i;
            }
        }
        try {
            return clamp(Integer.parseInt(raw), CRITICALS_PACKET, CRITICALS_LAST);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 读取攻击节奏；兼容上一版的 `autoclicker.fullDamage` 布尔开关。 */
    private static int readAttackMode(Properties values) {
        String raw = values.getProperty("autoclicker.attackMode");
        if (raw != null) {
            raw = raw.trim().toLowerCase(java.util.Locale.ROOT);
            for (int i = 0; i < ATTACK_MODE_KEYS.length; i++) {
                if (ATTACK_MODE_KEYS[i].equals(raw)
                        || ATTACK_MODE_NAMES[i].equalsIgnoreCase(raw)) {
                    return i;
                }
            }
            try {
                return clamp(Integer.parseInt(raw), ATTACK_MODE_FULL_CHARGE,
                        ATTACK_MODE_VANILLA);
            } catch (NumberFormatException ignored) {
                // fall through to the legacy key / default
            }
        }
        String legacy = values.getProperty("autoclicker.fullDamage");
        if (legacy != null && "false".equalsIgnoreCase(legacy.trim())) {
            return ATTACK_MODE_VANILLA;
        }
        return ATTACK_MODE_FULL_CHARGE;
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

    private static int readInt(Properties values, String key, int fallback, int max) {
        String raw = values.getProperty(key);
        int parsed = fallback;
        if (raw != null) {
            try {
                parsed = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(0, Math.min(max, parsed));
    }

    /**
     * 把当前开关写回 openmaver.properties。
     *
     * <p>之前 GUI 里的 toggle 只改内存，20 tick 后 {@link #refreshSettings()} 又从磁盘读回
     * false，所以开关看起来是 ON，实际一秒后就被悄悄关掉了。
     */
    private static void saveSettings() {
        String path = System.getProperty("openmaver.features", "config/openmaver.properties");
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(("settings.version=" + SETTINGS_VERSION
                    + "\naim=" + aimEnabled
                    + "\nesp=" + espEnabled
                    + "\nbypass.enabled=" + bypassEnabled
                    + "\nbypass.profile=" + PROFILE_KEYS[clamp(bypassProfile, PROFILE_LEGIT, PROFILE_AGGRESSIVE)]
                    + "\ndebug.enabled=" + debugEnabled
                    + "\naim.speed=" + aimSpeed
                    + "\naim.range=" + aimRange
                    + "\naim.fov=" + aimFov
                    + "\naim.mode=" + aimMode
                    + "\naim.target=" + aimTarget
                    + "\naim.requireClick=" + aimRequireClick
                    + "\naim.gcd=" + aimGcdEnabled
                    + "\naim.maxStep=" + aimMaxStep
                    + "\nesp.range=" + espRange
                    + "\nesp.thickness=" + espThickness
                    + "\nesp.opacity=" + espOpacity
                    + "\nesp.color=" + espColor
                    + "\nesp.playersOnly=" + espPlayersOnly
                    + "\ntargethud.range=" + targetHudRange
                    + "\ntargethud.scale=" + targetHudScale
                    + "\ntargethud.playersOnly=" + targetHudPlayersOnly
                    + "\nplayerxray.range=" + playerXrayRange
                    + "\nplayerxray.color=" + playerXrayColor
                    + "\nsprint.onlyForward=" + sprintOnlyForward
                    + "\nsprint.pauseSneak=" + sprintPauseSneak
                    + "\nsprint.pauseItem=" + sprintPauseItem
                    + "\nautoclicker.cpsMin=" + autoclickerCpsMin
                    + "\nautoclicker.cpsMax=" + autoclickerCpsMax
                    + "\nautoclicker.left=" + autoclickerLeft
                    + "\nautoclicker.right=" + autoclickerRight
                    + "\nautoclicker.requireHold=" + autoclickerRequireHold
                    + "\nautoclicker.waitForHurt=" + autoclickerWaitForHurt
                    + "\nautoclicker.attackMode="
                    + ATTACK_MODE_KEYS[clamp(autoclickerAttackMode,
                            ATTACK_MODE_FULL_CHARGE, ATTACK_MODE_VANILLA)]
                    + "\nautoclicker.humanize=" + autoclickerHumanize
                    + "\nreach.extra=" + reachExtra
                    + "\nreach.chance=" + Math.round(reachChance)
                    + "\ncriticals.mode=" + CRITICALS_MODE_KEYS[
                            clamp(criticalsMode, CRITICALS_PACKET, CRITICALS_LAST)]
                    + "\ncriticals.stopSprint=" + criticalsStopSprint
                    + "\nfastplace.delayTicks=" + fastPlaceDelayTicks
                    + "\nfastplace.onlyBlocks=" + fastPlaceOnlyBlocks
                    + "\ngodbridge.delayTicks=" + godBridgeDelayTicks
                    + "\ngodbridge.fastPlace=" + godBridgeFastPlace
                    + "\ngodbridge.requireEdgeAim=" + godBridgeRequireEdgeAim
                    + "\ngodbridge.autoRotate=" + godBridgeAutoRotate
                    + "\ngodbridge.legitAim=" + godBridgeLegitAim
                    + "\ngodbridge.packetRotation=" + godBridgePacketRotation
                    + "\nfakeplayer.count=" + fakePlayerCount
                    + "\nfakeplayer.distance=" + fakePlayerDistance
                    + "\nfakeplayer.showName=" + fakePlayerShowName
                    + "\n").getBytes("UTF-8"));
        } catch (IOException error) {
            System.err.println("[OpenMaver] Cannot save feature settings: " + error);
            return;
        }
        settingsDirty = false;
        settingsModified = file.lastModified();
    }

    /** 渲染帧开始：只做 aim 的视觉平滑；目标选择仍然每个 tick 一次。 */
    public static void onRenderTick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            updateAimFrame(mc);
        }
    }

    public static void onRender(RenderWorldLastEvent event) {
        Module xrayModule = ModuleManager.getModule("Player X-Ray");
        boolean xrayEnabled = xrayModule != null && xrayModule.isEnabled();
        if (!espEnabled && !xrayEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Object worldObject = read(mc, "theWorld", "field_71441_e", null);
        Object playerObject = read(mc, "thePlayer", "field_71439_g", null);
        if (!(worldObject instanceof net.minecraft.world.World)
                || !(playerObject instanceof EntityLivingBase)) {
            return;
        }
        net.minecraft.world.World world = (net.minecraft.world.World) worldObject;
        EntityLivingBase player = (EntityLivingBase) playerObject;

        RenderManager renderManager = (RenderManager) read(mc, "renderManager", "field_175616_W", RenderManager.class);
        if (renderManager == null) {
            return;
        }
        List<?> entities = (List<?>) read(world, "loadedEntityList", "field_72996_f", List.class);
        if (entities == null) {
            return;
        }
        if (espEnabled) {
            GlColor.restore();
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT
                    | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glLineWidth(espThickness);
            float[] espRgb = ESP_COLORS[Math.max(0, Math.min(ESP_COLORS.length - 1, espColor))];
            GlColor.set(espRgb[0], espRgb[1], espRgb[2], espOpacity);
            GL11.glBegin(GL11.GL_LINES);
            double espRangeSq = espRange * espRange;
            for (int i = 0; i < entities.size(); i++) {
                Object candidate = entities.get(i);
                if (!(candidate instanceof EntityLivingBase) || candidate == player) {
                    continue;
                }
                if (espPlayersOnly && !(candidate instanceof EntityPlayer)) {
                    continue;
                }
                EntityLivingBase entity = (EntityLivingBase) candidate;
                if (!alive(entity) || distance(player, entity) > espRangeSq) {
                    continue;
                }
                AxisAlignedBB box = boundingBox(entity);
                if (box == null) {
                    continue;
                }
                drawBox(box,
                        number(renderManager, "viewerPosX", "field_78725_b"),
                        number(renderManager, "viewerPosY", "field_78726_c"),
                        number(renderManager, "viewerPosZ", "field_78723_d"));
            }
            GL11.glEnd();
            GL11.glPopAttrib();
            GlColor.restore();
        }
        if (xrayEnabled) {
            float partialTicks = 1.0F;
            Object eventPartial = read(event, "partialTicks", "partialTicks", Float.class);
            if (eventPartial instanceof Number) {
                partialTicks = ((Number) eventPartial).floatValue();
            }
            renderPlayerXray(renderManager, player, entities, partialTicks);
        }
    }

    // ==================================================================
    // Player X-Ray（玩家透视）
    // ==================================================================

    private static void renderPlayerXray(RenderManager renderManager, EntityLivingBase self,
            List<?> entities, float partialTicks) {
        double cameraX = number(renderManager, "viewerPosX", "field_78725_b");
        double cameraY = number(renderManager, "viewerPosY", "field_78726_c");
        double cameraZ = number(renderManager, "viewerPosZ", "field_78723_d");
        double rangeSq = playerXrayRange * playerXrayRange;

        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT
                | GL11.GL_LIGHTING_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        for (int i = 0; i < entities.size(); i++) {
            Object candidate = entities.get(i);
            if (!(candidate instanceof EntityPlayer) || candidate == self) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) candidate;
            if (!alive(player) || distance(self, player) > rangeSq) {
                continue;
            }
            AxisAlignedBB box = boundingBox(player);
            if (box == null) {
                continue;
            }
            float[] rgb = xrayColor(player);

            // 先渲染玩家皮肤模型：关闭深度测试，墙体不会遮挡。
            renderPlayerSkin(renderManager, player, partialTicks);

            // 再画 1.5px 实线描边，围在皮肤模型外侧。
            GlColor.restore();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GlColor.set(rgb[0], rgb[1], rgb[2], 0.90F);
            GL11.glLineWidth(1.5F);
            drawBoxOutline(box, cameraX, cameraY, cameraZ);
        }

        GL11.glPopAttrib();
        GlColor.restore();
    }

    /**
     * 用游戏自己的 {@code RenderManager.renderEntitySimple} 画完整玩家皮肤模型。
     * 临时关闭深度测试实现穿墙；渲染器的状态全部包在 push/pop 中，不影响后续 HUD。
     */
    private static void renderPlayerSkin(RenderManager renderManager, EntityPlayer player,
            float partialTicks) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT
                | GL11.GL_LIGHTING_BIT);
        GlColor.restore();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_CULL_FACE);
        call(renderManager, "renderEntitySimple", "func_147937_a", null,
                player, Float.valueOf(partialTicks));
        GL11.glPopAttrib();
        GlColor.restore();
    }

    private static float[] xrayColor(EntityLivingBase player) {
        if (playerXrayColor <= 0) {
            float ratio = healthRatio(player);
            if (ratio > 0.60F) {
                return XRAY_COLORS[0];
            }
            if (ratio > 0.30F) {
                return XRAY_COLORS[1];
            }
            return XRAY_COLORS[4];
        }
        int index = Math.max(1, Math.min(XRAY_COLORS.length - 1, playerXrayColor));
        return XRAY_COLORS[index];
    }

    private static float healthRatio(Object entity) {
        Object healthValue = call(entity, "getHealth", "func_110143_aJ", null);
        Object maxValue = call(entity, "getMaxHealth", "func_110138_aP", null);
        float health = healthValue instanceof Number ? ((Number) healthValue).floatValue() : 0.0F;
        float max = maxValue instanceof Number ? ((Number) maxValue).floatValue() : 0.0F;
        if (max <= 0.0F) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, health / max));
    }

    private static void drawBoxOutline(AxisAlignedBB box, double cameraX, double cameraY,
            double cameraZ) {
        double x1 = number(box, "minX", "field_72340_a") - cameraX - 0.04D;
        double y1 = number(box, "minY", "field_72338_b") - cameraY - 0.04D;
        double z1 = number(box, "minZ", "field_72339_c") - cameraZ - 0.04D;
        double x2 = number(box, "maxX", "field_72336_d") - cameraX + 0.04D;
        double y2 = number(box, "maxY", "field_72337_e") - cameraY + 0.04D;
        double z2 = number(box, "maxZ", "field_72334_f") - cameraZ + 0.04D;

        GL11.glBegin(GL11.GL_LINES);
        line(x1, y1, z1, x2, y1, z1);
        line(x2, y1, z1, x2, y1, z2);
        line(x2, y1, z2, x1, y1, z2);
        line(x1, y1, z2, x1, y1, z1);
        line(x1, y2, z1, x2, y2, z1);
        line(x2, y2, z1, x2, y2, z2);
        line(x2, y2, z2, x1, y2, z2);
        line(x1, y2, z2, x1, y2, z1);
        line(x1, y1, z1, x1, y2, z1);
        line(x2, y1, z1, x2, y2, z1);
        line(x2, y1, z2, x2, y2, z2);
        line(x1, y1, z2, x1, y2, z2);
        GL11.glEnd();
    }

    /**
     * 每个 tick 选一次目标（实体遍历放在 20 TPS，避免逐帧反射造成掉帧），
     * 渲染帧只负责朝缓存角度平滑。
     */
    private static void updateAimTarget(Minecraft mc) {
        if (!aimEnabled || (aimRequireClick && !Mouse.isButtonDown(0))) {
            aimHasTarget = false;
            aimResidualYaw = 0.0F;
            aimResidualPitch = 0.0F;
            return;
        }
        EntityLivingBase player = (EntityLivingBase) read(mc, "thePlayer", "field_71439_g", null);
        net.minecraft.world.World world = (net.minecraft.world.World) read(mc, "theWorld", "field_71441_e", null);
        if (player == null || world == null) {
            aimCachedPlayer = null;
            aimHasTarget = false;
            return;
        }
        aimCachedPlayer = player;
        aimCachedGcd = aimGcdEnabled ? (float) mouseGcd(mc) : 0.0F;
        List<?> entities = (List<?>) read(world, "loadedEntityList", "field_72996_f", List.class);
        if (entities == null) {
            aimHasTarget = false;
            return;
        }
        EntityLivingBase nearest = null;
        double nearestDistance = aimRange * aimRange;
        double yaw = number(player, "rotationYaw", "field_70177_z");
        double pitch = number(player, "rotationPitch", "field_70125_A");
        double playerX = number(player, "posX", "field_70165_t");
        double playerZ = number(player, "posZ", "field_70161_v");
        double targetFraction = aimTarget == 0 ? 0.90D : (aimTarget == 1 ? 0.70D : 0.50D);
        float bestTargetYaw = 0.0F;
        float bestTargetPitch = 0.0F;
        for (int i = 0; i < entities.size(); i++) {
            Object candidate = entities.get(i);
            if (!(candidate instanceof EntityLivingBase) || candidate == player) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) candidate;
            if (!alive(living)) {
                continue;
            }
            double distance = distance(player, living);
            if (distance >= nearestDistance) {
                continue;
            }

            double dx = number(living, "posX", "field_70165_t") - playerX;
            double dz = number(living, "posZ", "field_70161_v") - playerZ;
            double targetY = number(living, "posY", "field_70163_u")
                    + number(living, "height", "field_70131_O") * targetFraction;
            double playerY = number(player, "posY", "field_70163_u") + eyeHeight(player);
            double dy = targetY - playerY;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float targetYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
            float targetPitch = (float) (-Math.atan2(dy, horizontal) * 180.0D / Math.PI);

            // FOV 限制：只瞄准当前视角半角范围内的目标
            if (aimFov < 179.9F) {
                float half = aimFov * 0.5F;
                if (Math.abs(wrapDegrees(targetYaw - (float) yaw)) > half
                        || Math.abs(wrapDegrees(targetPitch - (float) pitch)) > half) {
                    continue;
                }
            }
            nearestDistance = distance;
            nearest = living;
            bestTargetYaw = targetYaw;
            bestTargetPitch = targetPitch;
        }
        if (nearest == null) {
            aimHasTarget = false;
            aimResidualYaw = 0.0F;
            aimResidualPitch = 0.0F;
            return;
        }
        aimCachedYaw = bestTargetYaw;
        aimCachedPitch = bestTargetPitch;
        aimHasTarget = true;
    }

    private static void applyAimRotation(EntityLivingBase player, float oldYaw, float oldPitch,
            float stepYaw, float stepPitch, float gcd) {
        // 没有实际变化就不写字段：AimDuplicateLook 会检查完全重复的 rotation。
        if (Math.abs(stepYaw) < 1.0E-4F && Math.abs(stepPitch) < 1.0E-4F) {
            return;
        }
        float newYaw = wrapDegrees(oldYaw + stepYaw);
        float newPitch = Math.max(-90.0F, Math.min(90.0F, oldPitch + stepPitch));
        setNumber(player, "rotationYaw", "field_70177_z", newYaw);
        setNumber(player, "rotationPitch", "field_70125_A", newPitch);
        // 与 Entity.setAngles 一样同步 prevRotation：渲染立即使用新角度，
        // 避免 20 TPS 插值造成的“慢半拍 / 每 tick 弹一下”。
        setNumber(player, "prevRotationYaw", "field_70126_B", newYaw);
        setNumber(player, "prevRotationPitch", "field_70127_C", newPitch);
        aimAppliedYaw += Math.abs(stepYaw);
        aimAppliedPitch += Math.abs(stepPitch);
        OpenMaverDebug.rotation("aim", oldYaw, oldPitch, newYaw, newPitch, gcd);
    }

    /**
     * 每渲染帧更新一次 aim，而不是只在 20 TPS 的 tick 里跳一步。
     *
     * <p>平滑速度按真实帧间隔换算：50ms 内累计的转向量与旧版每 tick
     * {@code aimSpeed} 一致；同时用 {@link #aimAppliedYaw}/{@link #aimAppliedPitch}
     * 把每 tick 的 rotation delta 限制在 profile 上限内，服务端看到的仍是安全步长。
     */
    private static void updateAimFrame(Minecraft mc) {
        if (!aimEnabled || !aimHasTarget
                || (aimRequireClick && !Mouse.isButtonDown(0))) {
            resetAimFrameState();
            return;
        }
        if (read(mc, "currentScreen", "field_71462_r", null) != null) {
            resetAimFrameState();
            return;
        }
        EntityLivingBase player = aimCachedPlayer;
        if (player == null) {
            resetAimFrameState();
            return;
        }

        float oldYaw = (float) number(player, "rotationYaw", "field_70177_z");
        float oldPitch = (float) number(player, "rotationPitch", "field_70125_A");
        float gcd = aimCachedGcd;

        long now = System.nanoTime();
        float dtMs = aimLastFrameNanos == 0L ? 50.0F
                : (float) Math.min(100.0D,
                        Math.max(0.0D, (now - aimLastFrameNanos) / 1_000_000.0D));
        aimLastFrameNanos = now;

        // 50ms 内累计的平滑量与旧版“每 tick 一步”保持一致，但分摊到每一帧。
        float factor = aimMode == 1 ? 1.0F : aimSpeed;
        float alpha = factor >= 0.999F ? 1.0F
                : (float) (1.0D - Math.pow(1.0D - factor, dtMs / 50.0D));
        float budgetYaw = Math.max(0.0F, effectiveAimMaxStep(true) - aimAppliedYaw);
        float budgetPitch = Math.max(0.0F, effectiveAimMaxStep(false) - aimAppliedPitch);
        float stepYaw = stepAimFrame(oldYaw, aimCachedYaw, alpha, true, gcd, budgetYaw);
        float stepPitch = stepAimFrame(oldPitch, aimCachedPitch, alpha, false, gcd, budgetPitch);
        applyAimRotation(player, oldYaw, oldPitch, stepYaw, stepPitch, gcd);
    }

    private static void resetAimFrameState() {
        aimResidualYaw = 0.0F;
        aimResidualPitch = 0.0F;
        aimLastFrameNanos = 0L;
    }

    /**
     * 单帧平滑 + 每 tick 预算 + GCD 量化。
     *
     * <p>GCD 来自鼠标灵敏度：{@code gcd = (sens*0.6+0.2)^3 * 1.2} 度。
     * 每帧步长都量化到 GCD 的整数倍，并把未走完的零头留在 residual 里，
     * 因此一个 tick 内所有帧的 delta 累加后仍是 GCD 的倍数，不会破坏
     * AimProcessor 的“人类步长”特征。
     */
    private static float stepAimFrame(float current, float target, float alpha,
            boolean yawAxis, float gcd, float budget) {
        if (budget <= 0.0F) {
            return 0.0F;
        }
        float desired = yawAxis ? wrapDegrees(target - current) : (target - current);
        if (Math.abs(desired) < 1.0E-4F) {
            if (yawAxis) {
                aimResidualYaw = 0.0F;
            } else {
                aimResidualPitch = 0.0F;
            }
            return 0.0F;
        }
        float raw = desired * alpha;
        if (raw > budget) {
            raw = budget;
        } else if (raw < -budget) {
            raw = -budget;
        }

        float residual = yawAxis ? aimResidualYaw : aimResidualPitch;
        if ((residual > 0.0F && desired <= 0.0F)
                || (residual < 0.0F && desired >= 0.0F)) {
            residual = 0.0F;
        }
        float step;
        float nextResidual = 0.0F;
        if (gcd > 0.0F) {
            float pending = residual + raw;
            float quantized = Math.round(pending / gcd) * gcd;
            float desiredMagnitude = Math.abs(desired);
            if (Math.abs(quantized) > desiredMagnitude) {
                quantized = (float) (Math.signum(desired)
                        * Math.floor(desiredMagnitude / gcd) * gcd);
            }
            if (Math.abs(quantized) > budget) {
                quantized = (float) (Math.signum(quantized)
                        * Math.floor(budget / gcd) * gcd);
            }
            if (Math.abs(quantized) < gcd) {
                quantized = 0.0F;
            }
            step = quantized;
            nextResidual = pending - step;
            // 残差只保留不到一个 GCD 的零头；目标太近时允许停在 GCD 误差内。
            float limit = gcd * 1.25F;
            if (Math.abs(nextResidual) > limit) {
                nextResidual = Math.copySign(limit, nextResidual);
            }
        } else {
            step = raw;
        }
        if (yawAxis) {
            aimResidualYaw = nextResidual;
        } else {
            aimResidualPitch = nextResidual;
        }
        return step;
    }

    private static float effectiveAimMaxStep(boolean yawAxis) {
        float configured = clamp(aimMaxStep, 5.0F, 90.0F);
        if (!bypassEnabled || bypassProfile == PROFILE_AGGRESSIVE) {
            return Math.max(configured, 60.0F);
        }
        if (bypassProfile == PROFILE_LEGIT) {
            return yawAxis ? Math.min(configured, 18.0F) : Math.min(configured, 12.0F);
        }
        return yawAxis ? Math.min(configured, 35.0F) : Math.min(configured, 20.0F);
    }

    /** 当前鼠标灵敏度对应的 rotation GCD（度）。 */
    private static double mouseGcd(Minecraft mc) {
        Object settings = read(mc, "gameSettings", "field_71474_y", null);
        if (settings == null) {
            return 0.15D; // 常见默认灵敏度 0.5 对应的 GCD
        }
        double sensitivity = number(settings, "mouseSensitivity", "field_74341_c");
        double f = sensitivity * 0.6D + 0.2D;
        return Math.max(0.0001D, f * f * f * 1.2D);
    }

    private static Object read(Object target, String mcp, String srg, Class<?> expected) {
        if (target == null) return null;
        Field field = findField(target, runtimeCandidates(mcp, srg));
        if (field != null) {
            try {
                field.setAccessible(true);
                Object value = field.get(target);
                if (expected == null || value == null || expected.isInstance(value)) return value;
            } catch (Throwable ignored) {
                // Fall through to the type based fallback below.
            }
        }
        // 只在调用方明确给出期望类型时才做类型兜底；expected == null 时返回任意字段
        // 会读到完全不相干的成员（例如把 renderViewEntity 当成别的对象）。
        if (expected == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field candidate : type.getDeclaredFields()) {
                if (!expected.isAssignableFrom(candidate.getType())) continue;
                try {
                    candidate.setAccessible(true);
                    Object value = candidate.get(target);
                    if (value == null || expected.isInstance(value)) return value;
                } catch (Throwable ignored) {
                    // Continue searching without affecting the game loop.
                }
            }
        }
        return null;
    }

    /** 沿继承链查找字段。getDeclaredField 不会搜索父类，之前因此读不到 posX/rotationYaw。 */
    private static Field findField(Object target, String[] names) {
        if (target == null) return null;
        for (String name : names) {
            if (name == null) continue;
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // Try the superclass, then the next runtime name.
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean alive(Object entity) {
        Object value = call(entity, "isEntityAlive", "func_70089_S", "E");
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static double distance(Object entity, Object other) {
        Object value = call(entity, "getDistanceSqToEntity", "func_70068_e", "e", other);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.MAX_VALUE;
    }

    private static AxisAlignedBB boundingBox(Object entity) {
        Object value = call(entity, "getEntityBoundingBox", "func_174813_aQ", "aQ");
        return value instanceof AxisAlignedBB ? (AxisAlignedBB) value : null;
    }

    private static double eyeHeight(Object entity) {
        Object value = call(entity, "getEyeHeight", "func_70047_e", "bB");
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0D;
    }

    private static Object call(Object target, String mcp, String srg, String obf, Object... args) {
        if (target == null) return null;
        for (String name : new String[] {mcp, srg, obf}) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
                    if (!parametersMatch(method.getParameterTypes(), args)) continue;
                    try {
                        method.setAccessible(true);
                        return method.invoke(target, args);
                    } catch (Throwable ignored) {
                        // Try another mapping or inherited declaration.
                    }
                }
            }
        }
        return null;
    }

    /** 与 {@link #call} 相同，但用于返回 void 的方法，并明确告知是否调用成功。 */
    private static boolean callVoid(Object target, String mcp, String srg, String obf, Object... args) {
        if (target == null) return false;
        for (String name : new String[] {mcp, srg, obf}) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
                    if (!parametersMatch(method.getParameterTypes(), args)) continue;
                    try {
                        method.setAccessible(true);
                        method.invoke(target, args);
                        return true;
                    } catch (Throwable ignored) {
                        // Try another mapping or inherited declaration.
                    }
                }
            }
        }
        return false;
    }

    private static boolean parametersMatch(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (!parameterMatches(types[i], args[i])) return false;
        }
        return true;
    }

    private static boolean parameterMatches(Class<?> type, Object arg) {
        if (arg == null) return !type.isPrimitive();
        if (!type.isPrimitive()) return type.isInstance(arg);
        if (type == Boolean.TYPE) return arg instanceof Boolean;
        if (type == Byte.TYPE) return arg instanceof Byte;
        if (type == Character.TYPE) return arg instanceof Character;
        if (type == Short.TYPE) return arg instanceof Short;
        if (type == Integer.TYPE) return arg instanceof Integer;
        if (type == Long.TYPE) return arg instanceof Long;
        if (type == Float.TYPE) return arg instanceof Float;
        if (type == Double.TYPE) return arg instanceof Double;
        return false;
    }

    private static String[] runtimeCandidates(String mcp, String srg) {
        if ("field_71441_e".equals(srg)) return new String[] {mcp, srg, "f"};
        if ("field_71439_g".equals(srg)) return new String[] {mcp, srg, "i"};
        if ("field_71462_r".equals(srg)) return new String[] {mcp, srg, "h"};
        if ("field_71467_ac".equals(srg)) return new String[] {mcp, srg, "ap"};
        if ("field_71442_b".equals(srg)) return new String[] {mcp, srg, "c"};
        if ("field_82175_bq".equals(srg)) return new String[] {mcp, srg, "ar"};
        if ("field_110158_av".equals(srg)) return new String[] {mcp, srg, "as"};
        if ("field_71158_b".equals(srg)) return new String[] {mcp, srg, "b"};
        if ("field_78900_b".equals(srg)) return new String[] {mcp, srg, "b"};
        if ("field_78901_c".equals(srg)) return new String[] {mcp, srg, "c"};
        if ("field_77994_a".equals(srg)) return new String[] {mcp, srg, "b"};
        if ("field_71460_t".equals(srg)) return new String[] {mcp, srg, "o"};
        if ("field_71476_x".equals(srg)) return new String[] {mcp, srg, "s"};
        if ("field_70122_E".equals(srg)) return new String[] {mcp, srg, "C"};
        if ("field_72313_a".equals(srg)) return new String[] {mcp, srg, "a"};
        if ("field_178783_e".equals(srg)) return new String[] {mcp, srg, "e"};
        if ("field_178784_b".equals(srg)) return new String[] {mcp, srg, "b"};
        if ("field_72996_f".equals(srg)) return new String[] {mcp, srg, "f"};
        if ("field_70165_t".equals(srg)) return new String[] {mcp, srg, "p"};
        if ("field_70163_u".equals(srg)) return new String[] {mcp, srg, "q"};
        if ("field_70161_v".equals(srg)) return new String[] {mcp, srg, "r"};
        if ("field_70131_O".equals(srg)) return new String[] {mcp, srg, "U"};
        if ("field_70177_z".equals(srg)) return new String[] {mcp, srg, "y"};
        if ("field_70125_A".equals(srg)) return new String[] {mcp, srg, "z"};
        if ("field_72340_a".equals(srg)) return new String[] {mcp, srg, "a"};
        if ("field_72338_b".equals(srg)) return new String[] {mcp, srg, "b"};
        if ("field_72339_c".equals(srg)) return new String[] {mcp, srg, "c"};
        if ("field_72336_d".equals(srg)) return new String[] {mcp, srg, "d"};
        if ("field_72337_e".equals(srg)) return new String[] {mcp, srg, "e"};
        if ("field_72334_f".equals(srg)) return new String[] {mcp, srg, "f"};
        return new String[] {mcp, srg};
    }

    private static double number(Object target, String mcp, String srg) {
        Field field = findField(target, runtimeCandidates(mcp, srg));
        if (field == null) return 0.0D;
        try {
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof Number ? ((Number) value).doubleValue() : 0.0D;
        } catch (Throwable ignored) {
            return 0.0D;
        }
    }

    private static void setNumber(Object target, String mcp, String srg, double value) {
        Field field = findField(target, runtimeCandidates(mcp, srg));
        if (field == null) return;
        try {
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == Float.TYPE) field.setFloat(target, (float) value);
            else if (type == Double.TYPE) field.setDouble(target, value);
            else if (type == Integer.TYPE) field.setInt(target, (int) value);
            else if (type == Long.TYPE) field.setLong(target, (long) value);
        } catch (Throwable ignored) {
            // Never let a reflection mismatch break the game loop.
        }
    }

    private static void drawBox(AxisAlignedBB box, double cameraX, double cameraY, double cameraZ) {
        double x1 = number(box, "minX", "field_72340_a") - cameraX;
        double y1 = number(box, "minY", "field_72338_b") - cameraY;
        double z1 = number(box, "minZ", "field_72339_c") - cameraZ;
        double x2 = number(box, "maxX", "field_72336_d") - cameraX;
        double y2 = number(box, "maxY", "field_72337_e") - cameraY;
        double z2 = number(box, "maxZ", "field_72334_f") - cameraZ;
        line(x1, y1, z1, x2, y1, z1);
        line(x2, y1, z1, x2, y1, z2);
        line(x2, y1, z2, x1, y1, z2);
        line(x1, y1, z2, x1, y1, z1);
        line(x1, y2, z1, x2, y2, z1);
        line(x2, y2, z1, x2, y2, z2);
        line(x2, y2, z2, x1, y2, z2);
        line(x1, y2, z2, x1, y2, z1);
        line(x1, y1, z1, x1, y2, z1);
        line(x2, y1, z1, x2, y2, z1);
        line(x2, y1, z2, x2, y2, z2);
        line(x1, y1, z2, x1, y2, z2);
    }

    private static void line(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
    }

    private static float wrapDegrees(float angle) {
        angle %= 360.0F;
        if (angle >= 180.0F) {
            angle -= 360.0F;
        }
        if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }
}
