import module.Module;
import module.ModuleManager;
import util.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Velocity（防击退）MC 侧实现。
 *
 * <p>core 里的 {@code module.movement.Velocity} 只保存开关，真正的逻辑在这里。
 * 设计参考 LiquidBounce 的 {@code Velocity}：一种「怎么处理这次击退」的策略表，
 * 加上本工程特有的「blink 5 tick -> 一口气打 5 个攻击包」对测思路。
 *
 * <h3>数据来源</h3>
 * 击退的权威来源是入站的 {@code S12PacketEntityVelocity}。本类在 Netty 管线里插
 * {@link VelocityPacketHook}：在包交给 {@code NetHandlerPlayClient} 之前把它吞掉，
 * 于是原版不会给玩家施加任何击退，再由这里按模式写入我们想要的速度。
 * 管线注入失败时退化为「运动差分 + 受击标记」嗅探（{@link #sniffKnockback}），
 * 精度略低但不需要任何注入能力。
 *
 * <h3>模式</h3>
 * <ul>
 *   <li>{@link #MODE_MODIFY}：水平/垂直比例直接缩放击退，0 = 完全无视，1 = 原版；</li>
 *   <li>{@link #MODE_REVERSAL}：延迟 {@code reversalDelay} 后把击退反向打出去，
 *       把自己推向对手而不是被弹开；</li>
 *   <li>{@link #MODE_STRAFE}：把向后的击退转成当前输入方向上的速度（无输入时
 *       沿击退方向的垂线侧移），方便绕着对手走位；</li>
 *   <li>{@link #MODE_JUMP_RESET}：受击瞬间（可延迟 0~3 tick）起跳，用跳跃覆盖掉
 *       击退的水平分量；</li>
 *   <li>{@link #MODE_BLINK}：收到击退后憋住 {@code blinkTicks} 个 tick 的位置包，
 *       憋包结束时一口气补发 {@code burstAttacks} 条攻击包（C0A + C02），
 *       最后再补发缓存的位置包 —— 服务端结算这些攻击时用的仍是憋包前的旧位置。</li>
 * </ul>
 *
 * <h3>服务器预设</h3>
 * {@code CUSTOM / VANILLA / HYPIXEL / GRIM / INTAVE} 只是把上面这些参数配成一组
 * 针对该服务端反作弊观感的组合（见 {@link #applyPreset}），用户仍可继续微调。
 *
 * <p>Grim 对测 profile 为 {@code LEGIT} 时：blink 被禁用（降级为 JumpReset），
 * 水平/垂直保留比例也会被抬高，避免「一次抹平击退」被运动预测直接判出。
 */
public final class VelocityFeature {

    // ==================================================================
    // 模式
    // ==================================================================

    /** 按比例缩放击退（LiquidBounce: Modify）。 */
    public static final int MODE_MODIFY = 0;
    /** 延迟后反向击退，把自己推向对手（Reversal）。 */
    public static final int MODE_REVERSAL = 1;
    /** 把向后击退转成移动方向上的速度（Strafe）。 */
    public static final int MODE_STRAFE = 2;
    /** 受击起跳覆盖击退（JumpReset）。 */
    public static final int MODE_JUMP_RESET = 3;
    /** 憋包 + 攻击包爆发（本工程的对测思路）。 */
    public static final int MODE_BLINK = 4;
    /**
     * 制造 uncertainty 后归零击退。
     *
     * <p>Grim 的判定不是「位移对不对」，而是「位移对不对 **在它自己算出来的不确定度之内**」。
     * 它的 UncertaintyHandler 会为这些合法动作主动放宽容差：放置方块（碰撞箱变了）、
     * 开始挖掘、手持切换、附近有船/实体…… 所以受击瞬间先做一次 ghost-block
     * （放置 + 立刻开始挖掘同一格，世界净变化为零），把这几 tick 的容差撑开，
     * 再归零击退 —— 缺掉的位移落进容差里就不记 vl。
     */
    public static final int MODE_UNCERTAINTY = 5;
    /**
     * 完全合规的反制：一个字节的速度都不改。
     *
     * <p>不碰 motion、不憋包、不伪造位置包 —— Grim 的预测与实际完全一致，
     * 结构上不可能产生 offset。做的事情是「把击退换成滞空」：受击当 tick 起跳，
     * 水平滑行被跳跃吃掉一大截，同时立刻反打。位移削减不如归零，
     * 但是唯一零风险的路。
     */
    public static final int MODE_COUNTER = 6;
    /**
     * 只用「Grim 预测里本来就存在的分支」削击退：sprint-attack 的自减速。
     *
     * <p>1.8 的 {@code EntityPlayer.attackTargetEntityWithCurrentItem} 里有一段
     * （已用 {@code minecraft-1.8.9-client.jar} 的字节码核对过：
     * {@code wn.f(Lpk;)V}，`if (i > 0)` 分支内 {@code ldc2_w 0.6d} 两次
     * {@code dmul} + {@code setSprinting(false)}）：
     * <pre>
     *   i = 击退附魔等级 + (isSprinting ? 1 : 0);
     *   if (i > 0) { target.addVelocity(...); motionX *= 0.6; motionZ *= 0.6; setSprinting(false); }
     * </pre>
     * 也就是说**冲刺时每攻击一次，客户端自己的水平速度就乘 0.6**，而 Grim 的
     * {@code PredictionEngine.addAttackSlowToPossibilities} 会为每个攻击包生成
     * 同样的一条 {@code (0.6, 1, 0.6)} 分支（上限 5 条）。所以这条路：
     * <ul>
     *   <li>不改 S12、不憋包、不发伪造 C08 —— 不会有 AntiKB / Timer / FabricatedPlace；</li>
     *   <li>0.6 的削减落在预测的分支集合里 —— 不会有 Simulation offset
     *       （前提：tick 结束时保持在冲刺状态，Grim 的
     *       `PacketPlayerAttack` 用上一 tick 的 `lastSprinting` 决定要不要生成
     *       这条分支；见 {@link #ensureSprinting(Object)}）；</li>
     *   <li>重复 N tick 就是 0.6^N（6 tick ≈ 95%），全部是「服务端自己算得出来」的量。</li>
     * </ul>
     * 代价：每一 tick 都必须真的打到一个**准星里的**实体（否则那一 tick 不乘 0.6），
     * 且需要处于冲刺状态（原版只在冲刺时乘 0.6）。
     */
    public static final int MODE_SPRINT_SLAP = 7;
    /**
     * 冻结 transaction + 移动包，落地后再放包并攻击（OpenZen `AntiKB/NoXZ` 移植）。
     *
     * <p>原理（Grim 的击退是「两个 transaction 夹住的 sandwich」）：
     * <ol>
     *   <li>收到**向上的**本方 S12 且**不在地上**时：吞掉它（客户端先不吃击退），
     *       进入挂起；</li>
     *   <li>挂起期间：出站 C03 全部扣住（服务端看不到我们动），入站
     *       {@code S32PacketConfirmTransaction} 全部扣住 —— **这是关键**：
     *       击退的确认没回去，Grim 的 {@code KnockbackHandler} 只能把它当成
     *       「可能还没到」，于是预测里「没吃这段击退」也是一条合法分支；</li>
     *   <li>落地（或 12 tick 超时）：先把扣住的 C03 放出去（服务端看到的是
     *       **不含击退**的轨迹，正好落在允许的分支上），再把 S12 补给客户端
     *       （此刻才真的吃击退），然后每 tick 打一次准星目标并 ×0.6；</li>
     *   <li>下一 tick 把扣住的 S32 放出去 —— Grim 在这时才把击退算进它自己的
     *       velocity，而客户端也是在这一刻才吃，两边重新对齐。</li>
     * </ol>
     *
     * <p>与 OpenZen 的差异：只扣 S32（不扣聊天/音效/实体这些），超时 12 tick，
     * 其余（Strafe 加速、serverTickRate 改速）没有移植 —— 那些是计时器作弊，
     * 在 Grim 上会额外吃 {@code Timer}。
     */
    public static final int MODE_NOXZ = 8;
    private static final int MODE_LAST = MODE_NOXZ;

    static final String[] MODE_KEYS = {"modify", "reversal", "strafe", "jumpreset", "blink",
            "uncertainty", "counter", "sprintslap", "noxz"};
    static final String[] MODE_NAMES = {"MODIFY", "REVERSAL", "STRAFE", "JUMP RESET", "BLINK BURST",
            "UNCERTAINTY", "COUNTER", "SPRINT SLAP", "NOXZ"};

    // ==================================================================
    // 服务器预设
    // ==================================================================

    public static final int SERVER_CUSTOM = 0;
    public static final int SERVER_VANILLA = 1;
    public static final int SERVER_HYPIXEL = 2;
    public static final int SERVER_GRIM = 3;
    public static final int SERVER_INTAVE = 4;

    static final String[] SERVER_KEYS = {"custom", "vanilla", "hypixel", "grim", "intave"};
    static final String[] SERVER_NAMES = {"CUSTOM", "VANILLA", "HYPIXEL", "GRIM", "INTAVE"};

    // ==================================================================
    // 设置
    // ==================================================================

    private static int mode = MODE_MODIFY;
    private static int server = SERVER_CUSTOM;

    /** 水平击退保留比例：0 = 完全无视，1 = 原版击退。 */
    private static float horizontal = 0.0F;
    /** 垂直击退保留比例。 */
    private static float vertical = 0.0F;

    /** Reversal：延迟多久后把反向速度打出去（毫秒）。 */
    private static int reversalDelayMs = 120;
    /** Reversal：反向力度倍率。 */
    private static float reversalStrength = 1.0F;

    /** Strafe：转成移动方向速度的倍率。 */
    private static float strafeFactor = 1.0F;

    /** JumpReset：受击后延迟多少 tick 起跳。 */
    private static int jumpDelayTicks = 0;

    /** Blink：憋包 tick 数。 */
    private static int blinkTicks = 5;
    /** Blink：憋包结束时一口气补发的攻击包数量。 */
    private static int burstAttacks = 5;
    /** Blink：burst 攻击的最大距离（服务端 1.8 的交互上限是 3 格）。 */
    private static float burstRange = 3.0F;
    /** Blink：憋包结束后是否补发缓存的位置包（false = 丢弃，表现为瞬移）。 */
    private static boolean blinkFlush = true;

    /**
     * Blink：憋包窗口内把击退按比例写进 motion。
     *
     * <p><b>这是能不能过 Grim 的唯一开关</b>：Grim 服务端自己知道它发了 S12，
     * 运动预测里那一段位移它一定会等。憋包只是让这 5 tick 的位置**迟到**，
     * 不是让它们消失。所以补发时每一 tick 的 delta 必须仍然是原版击退轨迹
     * （1.0）；写成 0 就是「每 tick 欠它 0.15~0.4 格」，Simulation 的 vl
     * 会一路涨到几十（日志里 vl:78 就是这么来的）。
     */
    private static float blinkKeep = 1.0F;

    /** Uncertainty：受击后憋几 tick 位置包（越短断档越不明显，1~3）。 */
    private static int uncertaintyTicks = 2;

    /** Uncertainty：是否真的发 ghost-block（空手/类名对不上时自动退化）。 */
    private static boolean uncertaintyFarm = true;

    /** Blink：爆发攻击前先补一条「只改视角」的 C05（不改坐标，Grim 不跑位置预测）。 */
    private static boolean burstRotate = true;

    /** Blink：弃包时把本地坐标回退到憋包前（表现成瞬移，只用于测位移校验）。 */
    private static boolean blinkRewind = false;

    /** SprintSlap：一个击退窗口最多重复几个 tick（每个 tick 一次攻击 = 一次 ×0.6）。 */
    private static int slapMaxTicks = 6;

    /**
     * SprintSlap：准星没打到人时，要不要**自己把视角转过去**再打。
     *
     * <p>这是让它在实战里真的生效的关键：准星里没实体时按不了鼠标，而「没有
     * C02 就不能乘 0.6」—— 不转视角这个模式就只是站着挨打。
     *
     * <p>限制：只转**前半身**（与当前 yaw 相差 ≤ {@link #SLAP_AIM_MAX_YAW}°），
     * 这样 1.8 的 `moveFlying` 用新 yaw 算出来的移动方向偏移很小，不会把 WASD
     * 的方向拧过去；转完在 tick END 立刻恢复相机（和 Scaffold 的静默 aim 一样）。
     */
    private static boolean slapAim = true;

    /** SprintSlap 自动转视角的最大角度（超过这个角度宁可不动手）。 */
    private static final float SLAP_AIM_MAX_YAW = 75.0F;

    /**
     * Grim 守护（默认开）。
     *
     * <p>Grim 的 Simulation / AntiKB 是**逐 tick 全量预测**：只要客户端那一刻比
     * 预测少走一段位移，就是一条 flag。所以「把速度归零」这类模式在 Grim 上
     * 不是"可能被抓"，是"一定被抓，且每 tick 抓一次"：
     * <pre>
     *   AntiKB (vl:0.0): o: .507910     &lt;- 缺掉的那一次击退的水平量
     *   Simulation (vl:0.0→53): .194 / .151 / .143 / .375 …   &lt;- 每 tick 一条
     *   GroundSpoof (vl:0.0): claimed false    &lt;- 垂直归零造成的滞空
     * </pre>
     * 打开守护后：{@code REVERSAL} 与「水平/垂直保留 &lt; 30% 的 MODIFY」会被
     * 自动升级成 {@code BLINK BURST}（满受力），垂直比例抬到 ≥ 35%，
     * 这样三轮 flag 从源头上都不再产生。
     *
     * <p>明确选了非 Grim 预设（VANILLA / HYPIXEL / INTAVE）时守护不介入，
     * 尊重用户对该服务端的判断。
     */
    private static boolean grimGuard = true;

    /** 守护把配置模式改写成了什么（GUI/日志用；null 表示没改写）。 */
    private static int guardOverride = -1;

    // ==================================================================
    // 运行状态
    // ==================================================================

    /** 模块是否启用；由主线程每 tick 刷新，Netty 线程只读。 */
    private static volatile boolean active;
    /** 本地玩家的实体 ID；S12 只处理自己的击退。 */
    private static volatile int localEntityId = Integer.MIN_VALUE;

    /** 本次击退向量（S12 的原始值，单位 方块/tick）。 */
    private static volatile double pendingKbX;
    private static volatile double pendingKbY;
    private static volatile double pendingKbZ;
    private static volatile boolean kbPending;
    private static volatile long kbArriveNanos;

    /** tick 计数，Blink 用它对齐憋包窗口。 */
    private static int clientTick;

    private static Object trackedPlayer;
    private static Object trackedWorld;

    /** 管线注入状态。 */
    private static boolean hookInstalled;
    private static ChannelPipeline hookPipeline;
    private static VelocityPacketHook hookInstance;
    private static volatile ChannelHandlerContext hookContext;
    /** 当前挂着的 {@code NetHandlerPlayClient}；换连接时对象会变，用来触发重挂。 */
    private static Object hookNetHandler;
    /**
     * 上次尝试挂管线的 tick 与重试间隔（每 20 tick = 1 秒）。
     *
     * <p>初值不能取 {@code Integer.MIN_VALUE}：{@code clientTick - MIN_VALUE}
     * 会整型溢出成负数，第一次尝试就被节流挡掉。
     */
    private static int hookAttemptTick = -1000;
    private static final int HOOK_RETRY_INTERVAL = 20;
    /** 注入失败的日志只打一次，避免每秒刷屏。 */
    private static boolean hookWarned;

    /** 日志标签（写进 logs/openmaver.log，方便在游戏里取证）。 */
    private static final String TAG = "Velocity";

    /**
     * 入站 {@code S32PacketConfirmTransaction} 计数。
     *
     * <p>Grim 的击退是「两个 transaction 把 S12 夹住」结算的：S12 到达时它先不下结论，
     * 等客户端回执（S32）之后才把这段击退算进自己的 {@code player.velocity}。
     * 在这之前客户端已经吃到击退、乘出来的 0.6 是拿 {@code v_kb} 乘的，而它算的是
     * {@code 0.6 · v_old} —— 两边对不上，就是日志里那条
     * {@code AntiKB (vl:17.0): o: .239005} + 紧随其后的同值 Simulation。
     */
    private static final java.util.concurrent.atomic.AtomicLong TRANSACTIONS =
            new java.util.concurrent.atomic.AtomicLong();

    /** 收到击退那一刻的 S32 计数；slap 窗口要等它前进（服务端已结算击退）才出手。 */
    private static volatile long kbTransactionMark;

    /** 诊断计数：收到的本方击退包 / 实际按模式处理过的次数 / blink 次数。 */
    private static final java.util.concurrent.atomic.AtomicLong KB_SEEN =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong KB_APPLIED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong BLINK_CYCLES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong BURST_SENT =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong BURST_EMPTY =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ROTATE_SYNCED =
            new java.util.concurrent.atomic.AtomicLong();

    /** JumpReset 的起跳倒计时（-1 = 没有待执行的起跳）。 */
    private static int jumpResetCountdown = -1;

    /** Reversal 的待发速度。 */
    private static boolean reversalPending;
    private static double reversalX;
    private static double reversalZ;
    private static long reversalDeadlineNanos;

    /** Blink 状态。 */
    private static volatile boolean blinking;
    private static int blinkStartTick;
    /** 被憋住的位置包（Netty 线程写、主线程补发，需要加锁）。 */
    private static final List<Object> heldPackets = new ArrayList<Object>();
    /** 安全阀：一次憋包最多缓存多少个位置包。 */
    private static final int HELD_LIMIT = 120;

    /**
     * 憋包起点。
     *
     * <p>这 5 tick 里服务端认为我们一直站在这里，所以爆发攻击时它的 reach
     * 判定用的是**这个点**，不是客户端当前位置。攻击目标筛选必须按它算距离，
     * 否则憋包期间已经被推开 0.4×5 = 2 格，客户端以为还在 3 格内，
     * 服务端算出来是 5 格 —— 5 条 C02 全部被 reach 丢掉（看着像「打了没伤害」）。
     */
    private static double blinkOriginX;
    private static double blinkOriginY;
    private static double blinkOriginZ;
    private static float blinkOriginYaw;
    private static float blinkOriginPitch;
    private static boolean blinkOriginValid;

    /** 无管线时运动差分检测的击退水平阈值：冲刺跳约 0.28，击退必 ≥ 0.4。 */
    private static final double SNIFF_MIN_HORIZONTAL = 0.33D;

    /** 本地实体 id 是否已经报过解析失败（只提示一次）。 */
    private static boolean idResolveWarned;

    /** 无管线时的运动差分检测。 */
    private static double lastMotionX;
    private static double lastMotionY;
    private static double lastMotionZ;
    private static boolean motionTracked;

    // ==================================================================
    // SPRINT SLAP 状态
    // ==================================================================

    /** 本次 S12 的水平量（S12 的 int 原值，1/8000 格/tick）。 */
    private static volatile double pendingKbRawHorizontal;
    /** 本窗口还需要「攻击一次 → 原版 ×0.6」多少 tick；-1 = 空闲。 */
    private static int slapTicksLeft = -1;
    /** 本窗口计划的总 tick 数（诊断用）。 */
    private static int slapTicksTotal;
    /** 窗口的过期 tick：准星一直没目标时也不能无限等。 */
    private static int slapExpireTick = -1;
    /**
     * 窗口从第几 tick 开始出手。
     *
     * <p><b>不能从挨打那一 tick 就开始</b>：Grim 的击退是**用 transaction 对齐**
     * 的（`KnockbackHandler` 把 S12 夹在两个 transaction 之间），它要等客户端
     * 回执才把这段速度算进 `player.velocityX/Z`。我们在 tick START 就把 C02 发出去
     * 时，它的分支集合还是「挨打前」的 `{v, 0.6v}`，而客户端已经吃到了击退、
     * 乘出来的是 `0.6 · v_kb` —— 两边都对不上，于是：
     * <pre>
     *   AntiKB (vl:0.0): o: .143991
     *   Simulation (vl:1..3): .143991 / .104390 / .094995   ← 一次误差按摩擦衰减
     * </pre>
     * 实测（18:11:56 那局）就是这么来的。隔一个 tick 再打，Grim 的 velocity
     * 已经是 `v_kb`，`addAttackSlowToPossibilities` 生成的 0.6 分支才能对上。
     */
    private static int slapStartTick = -1;
    /** 最近一次没有出手的原因（诊断用，写进日志与 HUD 摘要）。 */
    private static String slapSkip = "空闲";

    /**
     * 一条击退最多能换到几条 0.6 分支。
     *
     * <p>Grim 的 {@code PredictionEngine.addAttackSlowToPossibilities} 每个 tick
     * 只保留 {@code Math.min(maxAttackSlow, 5)} 条 attack-slow 分支。第 6 次
     * ×0.6（0.6^6 ≈ 4.7%）在服务端**没有对应分支**，等于凭空少 40% 位移。
     * 所以窗口上限跟它对齐，多打的那一次纯粹是送 Simulation。
     */
    private static final int SLAP_MAX_BRANCHES = 5;

    /**
     * 出手距离上限（格）。
     *
     * <p>1.8 服务端的交互距离是 3.0：超出后 {@code ServerPlayNetHandler} 直接把
     * C02 丢掉，Grim 也就不会为这条攻击生成 0.6 分支 —— 而客户端已经乘过了，
     * 差额正好是日志里那串 {@code AntiKB o: .046243 / .235468}。
     * 客户端自己的 {@code getBlockReachDistance()} 是 4.5，不能拿它当判据。
     */
    private static final double SLAP_MAX_REACH = 2.9D;

    /** 是否自己发包同步冲刺状态（关掉 = 回到旧行为，只用于对测）。 */
    private static boolean slapSprintSync = true;

    /** 收到（并写进 motion）这次击退的 tick；slap 窗口至少晚一 tick 才出手。 */
    private static int kbArriveTick = -1;

    /**
     * 本 tick 已经发出去的攻击包（C02 ATTACK）数量。
     *
     * <p>Grim 的 `PacketPlayerAttack` 把 {@code maxAttackSlow/minAttackSlow} 卡在
     * **1**（没有击退附魔时「一 tick 不可能自己减速两次」），也就是说一个 tick 里
     * 只有一条 0.6 分支。如果 KillAura 已经在这 tick 打了一发，我们再打一发就是
     * 0.36 而预测只有 0.6 —— 差额 0.4·0.6v 又是一条 Simulation。
     * 所以出手前先看这一 tick 有没有别的 C02。
     */
    private static final java.util.concurrent.atomic.AtomicInteger ATTACKS_THIS_TICK =
            new java.util.concurrent.atomic.AtomicInteger();

    /** {@code C0BPacketEntityAction} 的反射缓存。 */
    private static Class<?> entityActionType;
    private static java.lang.reflect.Constructor<?> entityActionCtor;
    private static Object actionStartSprint;
    private static Object actionStopSprint;
    private static boolean entityActionLookupFailed;
    /** 服务端已知的冲刺状态（-1 = 未知，0 = 不冲刺，1 = 冲刺）。 */
    private static int sprintSynced = -1;
    /**
     * 上一 tick 结束时客户端是否在冲刺。
     *
     * <p>Grim 生成 0.6 分支用的是 `player.lastSprinting`（它自己上一 tick 结束时
     * 记下的冲刺状态），不是这一 tick 的。所以「这一 tick 才打开冲刺」并不能换来
     * 分支 —— 必须在**每个 tick 结束时**都保持在冲刺状态。
     */
    private static boolean sprintAtLastTickEnd;
    /** 本 tick 是否由本模块改过 rotation（tick END 负责恢复相机）。 */
    private static boolean slapAimDirty;
    private static float slapCameraYaw;
    private static float slapCameraPitch;
    /** 窗口内「准星里没有目标」的空转次数（诊断用）。 */
    private static final java.util.concurrent.atomic.AtomicLong SLAP_MISS =
            new java.util.concurrent.atomic.AtomicLong();
    /** 窗口内真正打出去的攻击次数。 */
    private static final java.util.concurrent.atomic.AtomicLong SLAP_HITS =
            new java.util.concurrent.atomic.AtomicLong();
    /** 已完成的 slap 窗口数。 */
    private static final java.util.concurrent.atomic.AtomicLong SLAP_WINDOWS =
            new java.util.concurrent.atomic.AtomicLong();

    /** C05（纯视角包）类型的反射缓存。 */
    private static Class<?> lookPacketType;

    /** C08（放置）构造的反射缓存。 */
    private static java.lang.reflect.Constructor<?> placePacketCtor;

    /** uncertainty farm 成功/失败计数（诊断用）。 */
    private static final java.util.concurrent.atomic.AtomicLong FARM_OK =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong FARM_FAIL =
            new java.util.concurrent.atomic.AtomicLong();

    /** 反射缓存：Packet 的 int 字段（entityId, motionX, motionY, motionZ）。 */
    private static final Map<Class<?>, Field[]> INT_FIELD_CACHE =
            new HashMap<Class<?>, Field[]>();

    private static boolean failed;

    private VelocityFeature() {
    }

    // ==================================================================
    // 主线程入口
    // ==================================================================

    /**
     * tick 开始阶段（vanilla 发送本 tick 的 C03 之前）：安装管线、消费击退、
     * 执行 JumpReset / Reversal / Blink 的状态机。
     */
    public static void onTickStart(Minecraft mc) {
        if (mc == null) {
            return;
        }
        Module module = ModuleManager.getModule("Velocity");
        boolean enabled = module != null && module.isEnabled();
        if (!enabled) {
            active = false;
            // 关掉模块时清掉熔断标记：再次启用会重新尝试注入管线。
            failed = false;
            stopNoXz("模块关闭");
            if (hookInstalled || hookPipeline != null) {
                detachHook("模块关闭");
            }
            hookNetHandler = null;
            stopBlinkAndFlush();
            resetRuntime();
            return;
        }
        active = true;
        if (failed) {
            return;
        }
        try {
            Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
            Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
            if (player == null || world == null) {
                resetRuntime();
                return;
            }
            if (player != trackedPlayer || world != trackedWorld) {
                // 重登 / 换维度 / 重生：旧的位置包已经没有意义，直接丢弃。
                trackedPlayer = player;
                trackedWorld = world;
                stopNoXz("换世界/重生");
                stopBlinkAndFlush();
                heldPackets.clear();
                resetRuntime();
            }
            localEntityId = readLocalEntityId(player);
            ATTACKS_THIS_TICK.set(0);
            clientTick++;
            riding = OpenMaverFeatures.read(player, "ridingEntity", "field_70154_o", null) != null;
            maintainHook(mc, player);
            sniffKnockback(player);
            applyPendingKnockback(mc, player);
            tickJumpReset(player);
            tickReversal(player);
            tickSprintSlap(mc, player);
            tickNoXz(mc, player);
            tickBlink(mc, player);
        } catch (Throwable error) {
            failed = true;
            active = false;
            System.err.println("[OpenMaver] Velocity disabled after error: " + error);
        }
    }

    /** tick 结束阶段：记录运动快照，供无管线时的差分检测使用。 */
    public static void onTickEnd(Minecraft mc) {
        if (mc == null) {
            return;
        }
        if (slapAimDirty) {
            restoreSlapAim(OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null));
        }
        if (!active || failed) {
            return;
        }
        try {
            Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
            if (player == null) {
                motionTracked = false;
                return;
            }
            // sprint-slap 的静默 aim：C03 已经在 tick 中间带着它发出去了，
            // 现在把相机（含手臂/渲染字段）恢复回来。
            restoreSlapAim(player);
            // Grim 的 attack-slow 分支看的是「上一 tick 结束时的冲刺状态」，
            // 这里记下来给下一次 slap 当闸门用。
            sprintAtLastTickEnd = isSprinting(player);
            lastMotionX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
            lastMotionY = OpenMaverFeatures.number(player, "motionY", "field_70181_x");
            lastMotionZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
            motionTracked = true;
        } catch (Throwable ignored) {
            motionTracked = false;
        }
    }

    // ==================================================================
    // 击退处理
    // ==================================================================

    /**
     * 消费一次被吞掉的击退，按当前模式写回速度。
     *
     * <p>S12 的语义是「把速度设为 (x,y,z)」，所以这里也是**覆盖**而不是叠加。
     */
    private static void applyPendingKnockback(Minecraft mc, Object player) {
        if (!kbPending) {
            return;
        }
        double kx = pendingKbX;
        double ky = pendingKbY;
        double kz = pendingKbZ;
        long arrived = kbArriveNanos;
        kbPending = false;
        if (Double.isNaN(kx) || Double.isNaN(ky) || Double.isNaN(kz)) {
            return;
        }
        long applied = KB_APPLIED.incrementAndGet();
        // 这次击退是在哪一 tick 写进 motion 的：slap 窗口要晚一 tick 才允许出手
        // （Grim 的击退事务下一 tick 才确认，早一 tick 乘的 0.6 是 0.6·v_old）。
        kbArriveTick = clientTick;
        if (applied <= 5L || applied % 20L == 0L) {
            Logger.info(TAG, "击退 #" + applied + " mode=" + MODE_NAMES[effectiveMode()]
                    + " kb=(" + round3(kx) + ", " + round3(ky) + ", " + round3(kz) + ")");
        }
        switch (effectiveMode()) {
            case MODE_REVERSAL:
                // 先彻底吃掉这次击退，延迟到 reversalDelay 之后再反向打出去。
                writeHorizontal(player, 0.0D, 0.0D);
                writeVerticalKb(player, ky * vRatio());
                reversalPending = true;
                reversalX = -kx * clamp(reversalStrength, 0.2F, 1.5F);
                reversalZ = -kz * clamp(reversalStrength, 0.2F, 1.5F);
                reversalDeadlineNanos = arrived
                        + (long) clamp(reversalDelayMs, 0, 600) * 1_000_000L;
                break;
            case MODE_STRAFE:
                applyStrafe(player, kx, ky, kz);
                break;
            case MODE_JUMP_RESET:
                // 水平交给起跳覆盖，垂直保留比例由设置决定。
                writeHorizontal(player, kx * hRatio(), kz * hRatio());
                writeVerticalKb(player, ky * vRatio());
                jumpResetCountdown = clamp(jumpDelayTicks, 0, 3);
                break;
            case MODE_BLINK:
                blitz(player, kx, ky, kz);
                break;
            case MODE_UNCERTAINTY:
                farmAndFreeze(mc, player);
                break;
            case MODE_SPRINT_SLAP:
                // 这个模式**不碰运动**：S12 已经在入站分支原样放行了，客户端真的
                // 吃到了这次击退。这里只开一个窗口，让接下来每个 tick 打一次
                // 「准星里的实体」，由 1.8 原版的 sprint-attack 自己乘 0.6。
                beginSlap();
                break;
            case MODE_NOXZ:
                // NOXZ 的 S12 在两处是原样放行的：站地上、以及刚被 setback 的
                // 冷却期（见 noxzInboundFilter）。之前这个 case 掉进 default 的
                // MODIFY 分支，而 GRIM 预设的 horizontal/vertical 都是 100% ——
                // 等于把击退原封不动写回去，模块看着就是「完全没效果」。
                // 地面这一下不可能靠 transaction 时序吃掉（Grim 立刻能对上位移），
                // 所以和 SPRINT SLAP 一样开攻击窗口：每个 tick 一次 ×0.6。
                pendingKbRawHorizontal = Math.hypot(kx, kz) * 8000.0D;
                beginSlap();
                break;
            case MODE_COUNTER:
                counterAttack(mc, player);
                break;
            case MODE_MODIFY:
            default:
                writeHorizontal(player, kx * hRatio(), kz * hRatio());
                writeVerticalKb(player, ky * vRatio());
                break;
        }
    }

    /**
     * BLINK 的入场：**照比例受力** + 记录服务端当前认定的坐标 + 开始憋包。
     *
     * <p>顺序不能颠倒：`recordBlinkOrigin` 必须在任何位置包被扣住之前读，
     * 否则记下来的是已经位移过的坐标，burst 的 reach 就会按错误的点算。
     */
    private static void blitz(Object player, double kx, double ky, double kz) {
        double keep = blinkKeepRatio();
        writeHorizontal(player, kx * keep, kz * keep);
        writeVerticalKb(player, ky * keep);
        if (!blinking) {
            recordBlinkOrigin(player);
        }
        startBlink();
    }

    /**
     * Strafe：把「向后被打飞」转成「沿当前输入方向移动」。
     *
     * <p>没有移动输入时沿击退方向的垂线侧移，效果就是绕着对手横移而不是被推开。
     */
    private static void applyStrafe(Object player, double kx, double ky, double kz) {
        double magnitude = Math.sqrt(kx * kx + kz * kz);
        float factor = clamp(strafeFactor, 0.2F, 1.6F);
        if (magnitude < 1.0E-4D) {
            writeHorizontal(player, kx * hRatio(), kz * hRatio());
            writeVerticalKb(player, ky * vRatio());
            return;
        }
        double yaw = Math.toRadians(OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z"));
        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        double forward = input == null
                ? 0.0D : OpenMaverFeatures.number(input, "moveForward", "field_78900_b");
        double strafe = input == null
                ? 0.0D : OpenMaverFeatures.number(input, "moveStrafe", "field_78901_c");
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // 与原版 Entity.moveFlying 的方向分解保持一致。
        double dirX = strafe * cos - forward * sin;
        double dirZ = forward * cos + strafe * sin;
        double dirLength = Math.sqrt(dirX * dirX + dirZ * dirZ);
        double outX;
        double outZ;
        if (dirLength < 0.1D) {
            // 站桩受击：沿击退法线的垂线侧移（-kz, kx），也就是绕到对手侧面。
            outX = -kz * factor;
            outZ = kx * factor;
        } else {
            outX = dirX / dirLength * magnitude * factor;
            outZ = dirZ / dirLength * magnitude * factor;
        }
        writeHorizontal(player, outX, outZ);
        writeVerticalKb(player, ky * vRatio());
    }

    /** Reversal：延迟到期后把反向速度打出去。 */
    private static void tickReversal(Object player) {
        if (!reversalPending) {
            return;
        }
        if (System.nanoTime() < reversalDeadlineNanos) {
            return;
        }
        reversalPending = false;
        OpenMaverFeatures.setNumber(player, "motionX", "field_70159_w", reversalX);
        OpenMaverFeatures.setNumber(player, "motionZ", "field_70179_y", reversalZ);
    }

    /**
     * JumpReset：执行延迟到期的那次起跳。
     *
     * <p>触发点是**击退事件本身**（{@link #applyPendingKnockback} 的
     * {@code MODE_JUMP_RESET} 分支），而不是 {@code hurtTime} 之类的受击字段：
     * 1.8.9 里 hurtTime / hurtResistantTime 的 SRG 名相近，猜错就会永远读 0，
     * 而击退事件在管线与嗅探两条路径上都能稳定拿到。
     *
     * <p>1.8.9 的移动是客户端权威的：起跳把 motionY 覆盖成 0.42，同时水平的
     * 击退已经被按比例削掉，服务端只能接受我们发出去的这份位置。
     */
    private static void tickJumpReset(Object player) {
        if (jumpResetCountdown < 0) {
            return;
        }
        if (jumpResetCountdown == 0) {
            jumpResetCountdown = -1;
            doJump(player);
        } else {
            jumpResetCountdown--;
        }
    }

    private static void doJump(Object player) {
        if (!OpenMaverFeatures.callVoid(player, "jump", "func_70664_aZ", null)) {
            OpenMaverFeatures.setNumber(player, "motionY", "field_70181_x", 0.42D);
        }
    }

    // ==================================================================
    // Blink（憋包 + 攻击包爆发）
    // ==================================================================

    /**
     * UNCERTAINTY 的入场：先把 Grim 的容差撑开，再归零。
     *
     * <p>顺序必须是「先 farm 再归零」：farm 出来的 uncertainty 从**收到包的那一 tick**
     * 开始生效，而缺掉的位移也是从这一 tick 开始欠。反过来做，第一 tick 就白欠了。
     *
     * <p>归零只归水平：垂直留原样（`writeVerticalKb` 在落地时本来就不写），
     * 否则 2~3 tick 的滞空会换来一条 `GroundSpoof`。
     */
    private static void farmAndFreeze(Minecraft mc, Object player) {
        boolean farmed = uncertaintyFarm && farmUncertainty(mc, player);
        if (!farmed) {
            // 容差没撑开时**不能**硬归零：那就是「每 tick 欠一段位移」，
            // Simulation 会跟之前一样一路涨 vl。宁可退化成合规反制（不碰速度 + 起跳）。
            counterAttack(mc, player);
            return;
        }
        writeHorizontal(player, 0.0D, 0.0D);
        if (!blinking) {
            recordBlinkOrigin(player);
        }
        startBlink();
    }

    /**
     * 制造一次 uncertainty：**用玩家真实视线命中点发一条完全合法的 C08**。
     *
     * <p><b>这里改过一次，原实现是 ghost-block，会被反噬</b>：
     * 侧向放一个方块再 {@code C07 START_DIGGING} 挖它，结果日志里同时出现
     * <pre>
     *   AirLiquidBreak (vl:0.0): block=air, type=START_DIGGING     &lt;- C07 打在空气上
     *   RotationPlace (vl:0.0): pre-flying / post-flying           &lt;- C08 的角度服务端没见过
     * </pre>
     * Grim 的 {@code RotationPlace} 会拿**服务端当前的 yaw/pitch** 去 ray trace
     * 你 C08 指向的方块（{@code didRayTraceHit}），伪造侧向角度必然失败。
     *
     * <p>现在改成：读 {@code mc.objectMouseOver} —— 客户端本来就是用
     * 「服务端已知的那个 rotation」算出来的射线。命中方块时，把同一个
     * blockPos / sideHit / hitVec 原样发进 C08，等于「玩家自己右键了一下」：
     * <ul>
     *   <li>ray trace 一定通过（角度就是服务端那份）→ 不触发 RotationPlace；</li>
     *   <li>不发 C07 → 不触发 AirLiquidBreak；</li>
     *   <li>服务端会真的放下一格方块（合法动作），这才是它能用来加容差的依据。</li>
     * </ul>
     *
     * <p>代价是这次受击会真的多一格方块（消耗一个物品）。没有视线命中就放弃 farm，
     * 上游 {@link #farmAndFreeze} 会自动退化成 COUNTER，不会硬归零。
     */
    private static boolean farmUncertainty(Minecraft mc, Object player) {
        if (mc == null) {
            FARM_FAIL.incrementAndGet();
            return false;
        }
        Object netHandler = OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
        if (netHandler == null) {
            FARM_FAIL.incrementAndGet();
            return false;
        }
        Object held = OpenMaverFeatures.call(player, "getHeldItem", "func_70694_bm", "bm");
        if (held == null) {
            // 空手：服务端会直接丢掉 C08，farm 没有意义。
            FARM_FAIL.incrementAndGet();
            return false;
        }
        Object mop = OpenMaverFeatures.read(mc, "objectMouseOver", "field_71476_x", null);
        if (mop == null) {
            FARM_FAIL.incrementAndGet();
            return false;
        }
        Object blockPos = OpenMaverFeatures.call(mop, "getBlockPos", "func_178782_a", null);
        Object facing = OpenMaverFeatures.read(mop, "sideHit", "field_178784_b", null);
        Object hitVec = OpenMaverFeatures.read(mop, "hitVec", "field_72307_f", null);
        if (blockPos == null || facing == null || hitVec == null) {
            FARM_FAIL.incrementAndGet();
            return false;
        }
        int side = facing instanceof Enum ? ((Enum<?>) facing).ordinal() : -1;
        if (side < 0) {
            FARM_FAIL.incrementAndGet();
            return false;
        }
        try {
            // C08 的后三个 float 是**相对方块原点**的点击点，合法域 [0,1]。
            // 直接塞世界坐标会被 Grim 的 FabricatedPlace 抓住（"Sent out of
            // bounds cursor position"），日志里那串
            //   cursor=6.3125, -2.0, -4.6875 limit=-0.0000000074505806
            // 就是世界坐标被按 1/16 量化成 byte 之后的样子（62.0×16=992→byte -32
            // → -2.0）。原版走的是 `hitVec.x - pos.getX()`，这里也一样。
            float hx = (float) (OpenMaverFeatures.number(hitVec, "xCoord", "field_72450_a")
                    - blockPosX(blockPos));
            float hy = (float) (OpenMaverFeatures.number(hitVec, "yCoord", "field_72448_b")
                    - blockPosY(blockPos));
            float hz = (float) (OpenMaverFeatures.number(hitVec, "zCoord", "field_72449_c")
                    - blockPosZ(blockPos));
            if (hx < -0.001F || hx > 1.001F || hy < -0.001F || hy > 1.001F
                    || hz < -0.001F || hz > 1.001F) {
                // 拿不到方块坐标（反射失败）或命中点不在方块上：宁可不 farm。
                FARM_FAIL.incrementAndGet();
                return false;
            }
            Object place = placePacketCtor().newInstance(blockPos, Integer.valueOf(side), held,
                    Float.valueOf(hx), Float.valueOf(hy), Float.valueOf(hz));
            OpenMaverFeatures.call(netHandler, "addToSendQueue", "func_147297_a", "a", place);
            long ok = FARM_OK.incrementAndGet();
            if (ok == 1L) {
                Logger.info(TAG, "uncertainty farm 已生效：用真实视线命中点发 C08"
                        + "（side=" + side + " 相对 cursor=" + round3(hx) + "," + round3(hy)
                        + "," + round3(hz) + "），本窗口 " + activeBlinkTicks() + " tick 内归零击退");
            }
            return true;
        } catch (Throwable error) {
            FARM_FAIL.incrementAndGet();
            return false;
        }
    }

    /** {@code BlockPos#getX}（SRG: func_177958_n）。 */
    private static int blockPosX(Object pos) {
        Object value = OpenMaverFeatures.call(pos, "getX", "func_177958_n", null);
        return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
    }

    /** {@code BlockPos#getY}（SRG: func_177956_o）。 */
    private static int blockPosY(Object pos) {
        Object value = OpenMaverFeatures.call(pos, "getY", "func_177956_o", null);
        return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
    }

    /** {@code BlockPos#getZ}（SRG: func_177952_p）。 */
    private static int blockPosZ(Object pos) {
        Object value = OpenMaverFeatures.call(pos, "getZ", "func_177952_p", null);
        return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
    }

    /**
     * COUNTER 的入场：不碰速度，只把击退换成滞空 + 立刻反打。
     *
     * <p>这里一个字节的速度都不改，所以 Grim 的预测与实际完全一致，不可能产生 offset。
     * 起跳在 tick START 执行，vanilla 之后的 move 会把它带进这一 tick 的位移里，
     * 结果是「被推」变成「被推 + 起跳」，水平滑行被跳跃吃掉一大截。
     */
    private static void counterAttack(Minecraft mc, Object player) {
        jumpResetCountdown = 0;
        fireBurst(mc, player);
    }

    // ==================================================================
    // SPRINT SLAP：只用「预测里本来就存在的分支」削击退
    // ==================================================================

    /**
     * 开一个 slap 窗口。
     *
     * <p>这里一个字节的运动都不写：S12 已经在入站分支放行，客户端真的吃了这次击退。
     * 接下来每 tick 打一次准星里的实体，由 1.8 原版
     * {@code EntityPlayer.attackTargetEntityWithCurrentItem} 在冲刺状态下乘 0.6
     * （见 {@link #MODE_SPRINT_SLAP} 的字节码依据），Grim 的预测里有对应分支。
     */
    private static void beginSlap() {
        int ticks = slapTicksFor(pendingKbRawHorizontal);
        // ★ 必须晚一 tick 才出手。
        //
        // Grim 的击退是「两个 transaction 把 S12 夹住」结算的：S12 到达后它先把这段
        // 速度挂起来，等客户端回执（S32）才写进自己的 velocity。挨打那一 tick 我们
        // 就发 C02 的话，它算的是 0.6·v_old，客户端乘的是 0.6·v_kb，两边对不上 ——
        // 18:53 那局每开一个窗口就来一条
        //   AntiKB (vl:0.0): o: .046243 / .235468 / .254650
        // 后面跟着一串按摩擦衰减的 Simulation，就是这么来的。
        //
        // 代价：第一 tick 那 0.36~0.4 格是满受力（削不掉）。这一块换不来
        // 「合法的 0.6 分支」，硬削只会把 vl 推到几十。
        slapStartTick = Math.max(clientTick, kbArriveTick) + 1;
        slapTicksLeft = ticks;
        slapTicksTotal = ticks;
        slapExpireTick = slapStartTick + ticks * 3 + 2;
        long windows = SLAP_WINDOWS.incrementAndGet();
        if (windows <= 5L || windows % 20L == 0L) {
            Logger.info(TAG, "sprint-slap #" + windows + " 开窗 " + ticks + "t"
                    + "（|kb|=" + round3(pendingKbRawHorizontal / 8000.0D) + " 格/tick，"
                    + "击退包原样放行；下一 tick 起每 tick 一次 ×0.6，"
                    + "冲刺状态用 C0B 同步）"
                    + "｜累计 命中=" + SLAP_HITS.get() + " 空转=" + SLAP_MISS.get()
                    + " 上次跳过=" + slapSkip);
        }
    }

    /**
     * 由击退强度决定要重复几个 tick。
     *
     * <p>沿用 OpenMyau-Fix 的拟合（{@code Velocity.calculateSlapTicks}）：
     * {@code ticks = 6.43153527E-4 * |kb| + 2.9419087136}，其中 |kb| 是 S12 的
     * 原始 int 水平量。一次普通击退 |kb|≈4525 → 拟合 6 tick，但 Grim 每 tick 只留
     * {@link #SLAP_MAX_BRANCHES} 条 attack-slow 分支，第 6 次没有分支可落 ——
     * 所以上限卡在 5（0.6^5 ≈ 7.8%，≈92% 削减）。
     */
    private static int slapTicksFor(double rawHorizontal) {
        int max = clamp(Math.min(slapMaxTicks, SLAP_MAX_BRANCHES), 1, SLAP_MAX_BRANCHES);
        if (rawHorizontal <= 0.0D) {
            return max;
        }
        double fit = 6.43153527E-4D * rawHorizontal + 2.9419087136D;
        return clamp((int) Math.round(fit), 1, max);
    }

    private static void tickSprintSlap(Minecraft mc, Object player) {
        int current = effectiveMode();
        if (current != MODE_SPRINT_SLAP && current != MODE_NOXZ) {
            // NOXZ 在地面/冷却期放行 S12 时也走这条攻击窗口（见
            // applyPendingKnockback 的 MODE_NOXZ 分支）。
            slapTicksLeft = -1;
            return;
        }
        if (slapTicksLeft <= 0 || player == null || mc == null) {
            if (player != null) {
                // 没有窗口也要对齐：AutoSprint / Scaffold 在 tick 开头改了冲刺状态，
                // 原版那条 C0B 要等 tick 末尾，服务端会慢一 tick（差 0.006 格）。
                reconcileSprint(player, isSprinting(player));
            }
            return;
        }
        // 窗口内每 tick 先对齐冲刺状态：这一步必须发生在任何 C03 之前。
        reconcileSprint(player, isSprinting(player));
        if (noxzSuspending) {
            // NOXZ 挂起期间 S12 还没补给客户端，此刻的 motion 里根本没有击退，
            // 这会儿乘 0.6 是白乘（还会和 Grim 的预测分支错位）。等放包再打，
            // 同时把上一个窗口关掉：放包那一刻由 noxzAttacksLeft 接手，
            // 否则同一 tick 会出现两条 C02（多乘一次 0.36，预测只有 0.6）。
            slapTicksLeft = 0;
            return;
        }
        if (clientTick < slapStartTick) {
            slapSkip = "等一 tick（窗口还没开始）";
            return;
        }
        if (clientTick > slapExpireTick) {
            slapSkip = "窗口过期（准星一直没回到实体）";
            slapTicksLeft = 0;
            return;
        }
        if (ScaffoldFeature.isAimingThisTick()) {
            slapSkip = "Scaffold 本 tick 会改视角";
            // Scaffold 会在 movementInput 里把 rotation 改成它的静默 aim，而那条
            // 写包比这里更晚。我们按当前 rotation 判定的"准星里有没有实体"
            // 与服务端最终看到的 rotation 就不是同一个角度了 —— 这一 tick 不打。
            return;
        }
        Object target = crosshairTarget(mc, player);
        if (target == null && slapAim) {
            // 准星里没人：自己转过去（只转前半身）。不转就永远没有 C02，
            // 也就永远削不掉击退 —— 这正是 18:11 那局「空转」的原因。
            Object front = scanFrontTarget(mc, player, SLAP_AIM_MAX_YAW);
            if (front != null && aimAtSlapTarget(player, (EntityLivingBase) front)) {
                target = front;
            }
        }
        if (target == null) {
            // 连转视角都没得打（3 格内没有活着的实体）：这一 tick 削不了。
            slapSkip = "3 格内没有可攻击实体";
            long empty = SLAP_MISS.incrementAndGet();
            if (empty <= 3L) {
                Logger.warn(TAG, "sprint-slap 空转：准星里没有可攻击实体"
                        + "（攻击和 ×0.6 必须成对出现，所以这一 tick 什么都不做）");
            }
            return;
        }
        Object controller = OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        if (controller == null) {
            slapSkip = "playerController 缺失";
            slapTicksLeft = 0;
            return;
        }
        // ★ 闸门一：Grim 必须已经结算过这次击退。
        //
        // 18:48 / 18:53 的实机日志：`AntiKB (vl:0.0): o: .235468` + 同值 Simulation，
        // 紧接着 vl 一路涨到 83 —— 都是在「服务端还拿 v_old 当基准」的时候乘了 0.6。
        //
        // 这里不再用 S32 计数当判据：TRANSACTIONS 是**全服**回执计数，背包/其它
        // 事务的 S32 会让它提前跳过去，于是又回到「0.6·v_old」。改成按 tick 计：
        // S12 是这一 tick 才写进 motion 的，Grim 的同批事务下一 tick 才确认，
        // 所以出手最早只能是 kbArriveTick + 1（beginSlap 已经这么排了）。
        if (clientTick <= kbArriveTick) {
            slapSkip = "等一 tick（Grim 还没把这次击退算进 velocity）";
            return;
        }
        // 闸门二：击退必须真的落到 motion 上才有得削：S12 是 Netty 线程异步到的，
        // 有可能这一 tick START 时客户端还没 setVelocity（下一 tick 才吃到）。
        // 那时候打一下只会把「走路速度」乘成 0.6 —— 白送一条 offset，还没削到击退。
        if (clientTick == slapStartTick) {
            double kbHorizontal = pendingKbRawHorizontal / 8000.0D;
            double currentHorizontal = Math.hypot(
                    OpenMaverFeatures.number(player, "motionX", "field_70159_w"),
                    OpenMaverFeatures.number(player, "motionZ", "field_70179_y"));
            if (kbHorizontal > 0.05D && currentHorizontal < kbHorizontal * 0.5D) {
                slapStartTick = clientTick + 1;
                slapSkip = "击退还没进 motion，等下一 tick";
                return;
            }
        }
        // ★ 闸门三：距离。1.8 服务端只认 3.0，超出的 C02 被直接丢掉 ——
        // 那一条攻击在 Grim 那边**没有 0.6 分支**，我们这边却乘了 0.6，
        // 差额原样变成 AntiKB 的 o（日志里 .046243 / .163036 那一串）。
        double reach = slapReach(player, target);
        if (reach > SLAP_MAX_REACH) {
            slapSkip = "目标 " + round3(reach) + " 格（服务端 3.0 之外没有 0.6 分支）";
            return;
        }
        // ★ 闸门三半：这一 tick 有没有别的 C02（KillAura / AutoClicker）。
        // Grim 每 tick 只认一条 0.6 分支，两条攻击 = 0.36 对 0.6。
        if (ATTACKS_THIS_TICK.get() > 0) {
            slapSkip = "本 tick 已有其它攻击包（Grim 每 tick 只认一条 0.6 分支）";
            return;
        }
        // ★ 闸门四：必须**正在冲刺**，而且上一 tick 结束时也是。
        //
        // Grim `PacketPlayerAttack` 里生成 0.6 分支的条件是
        //   `player.lastSprinting && !hasNegativeKB && sufficientCooldownProgress || knockbackLevel > 0`
        // 其中 1.8 的 `sufficientCooldownProgress` 恒为 true，`lastSprinting` 是
        // **上一 tick 结束时的冲刺状态**（MovementCheckRunner 末尾赋值）。
        // 所以「这一 tick 才打开冲刺」是没用的 —— 0.6 依然没有分支可落。
        // 日志里那串数字就是 0.4·v：0.4×0.36(|kb|)=.143976，一字不差。
        if (!isSprinting(player) || !sprintAtLastTickEnd) {
            slapSkip = "没在冲刺（0.6 分支只认上一 tick 的 lastSprinting）";
            return;
        }
        double beforeX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
        double beforeZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
        // 1.8 的包序：ANIMATION 必须在 INTERACT_ENTITY 之前（PacketOrderB）。
        OpenMaverFeatures.callVoid(player, "swingItem", "func_71038_i", null);
        // attackEntity 在 1.8.9 里是 **void**（joined.srg:
        // `func_78764_a (Lwn;Lpk;)V`，`(EntityPlayer, Entity)` → V）。
        // 之前用 call() 的返回值判成败，而 Method.invoke 对 void 恒返回 null，
        // 于是每一次攻击都被当成失败直接 return：C02 照发（有伤害），但 0.6
        // 一次都没乘上，命中计数永远是 0 —— 表现就是「Velocity 启用后没用」。
        if (!OpenMaverFeatures.callVoid(controller, "attackEntity",
                "func_78764_a", "a", player, target)) {
            // SRG 没解析到 / 旁观者：这一 tick 没有 C02，绝不能单独乘 0.6。
            slapSkip = "attackEntity 调用失败（不是 void 返回值的问题）";
            return;
        }
        // ---- 0.6 只认原版那一次 ----
        //
        // 原版的 0.6 前置条件（字节码 `wn.f(Lpk;)V`）：`flag = attackEntityFrom(...)`
        // 成功 **且** `i > 0`（i = 击退附魔 + (冲刺 ? 1 : 0)）。
        //
        // 以前原版没乘时这里会「自己补乘 0.6」。那是最坏的一种：服务端根本没生成
        // 0.6 分支（伤害没结算 / 够不着），客户端却少了 40% 位移 —— 每条一条
        // Simulation + AntiKB。现在原版没乘就**放弃这一 tick**，一个字节都不动。
        double afterX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
        double afterZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
        boolean vanillaSlowed = closeEnough(afterX, beforeX * 0.6D)
                && closeEnough(afterZ, beforeZ * 0.6D);
        if (!vanillaSlowed) {
            slapSkip = "原版没乘 0.6（目标无敌帧 / 伤害没结算），本 tick 放弃";
            return;
        }
        // 原版 `motionXZ *= 0.6` 之后会 `setSprinting(false)`。这里**立刻再打开**：
        //  · 客户端这一 tick 的移动仍按冲刺算（0.026），服务端也认为在冲刺 → 对齐；
        //  · tick 结束时的冲刺状态是 true → 下一 tick 的 `lastSprinting` 才是 true，
        //    下一条 0.6 才有分支可落。
        // 不发 C0B：状态从 true 出发又回到 true，服务端那份本来就是 true，
        // 原版 `serverSprintState` 比对也认为没变 —— 一条包都不需要。
        ensureSprinting(player);
        slapTicksLeft--;
        slapSkip = "已出手";
        long hits = SLAP_HITS.incrementAndGet();
        if (hits <= 5L || hits % 40L == 0L) {
            Logger.info(TAG, "sprint-slap 攻击 #" + hits + " target="
                    + target.getClass().getSimpleName()
                    + " 距离=" + round3(reach) + "格"
                    + " motion=(" + round3(beforeX) + "," + round3(beforeZ) + ")->("
                    + round3(afterX) + "," + round3(afterZ) + ")"
                    + " 原版乘过·冲刺已同步"
                    + " 剩余窗口=" + slapTicksLeft + "/" + slapTicksTotal + "t");
        }
    }

    /** 浮点近似相等（相对误差 1e-9，覆盖 `x * 0.6` 的精确双精度结果）。 */
    private static boolean closeEnough(double a, double b) {
        double scale = Math.max(1.0D, Math.max(Math.abs(a), Math.abs(b)));
        return Math.abs(a - b) <= 1e-9D * scale;
    }

    /** target 到眼睛的距离（服务端 reach 的判据），拿不到时返回 0。 */
    private static double slapReach(Object player, Object target) {
        double[] cone = coneAngle(player, (EntityLivingBase) target);
        return cone == null ? 0.0D : cone[1];
    }

    // ==================================================================
    // 冲刺状态：Grim 的 0.6 分支只认上一 tick 的 lastSprinting
    // ==================================================================

    /**
     * 把冲刺重新打开（原版冲刺攻击后会 `setSprinting(false)`）。
     *
     * <p><b>这一步是能不能过 Grim 的关键</b>（源码依据：
     * {@code common/.../events/packets/PacketPlayerAttack.java}）：
     *
     * <pre>
     * if (player.lastSprinting &amp;&amp; !hasNegativeKB &amp;&amp; sufficientCooldownProgress
     *         || knockbackLevel &gt; 0) {
     *     player.minAttackSlow++;
     *     player.maxAttackSlow++;
     *     if (knockbackLevel == 0) player.maxAttackSlow = player.minAttackSlow = 1;
     * }
     * </pre>
     *
     * <p>1.8 客户端 {@code sufficientCooldownProgress} 恒为 true，所以没有击退附魔时
     * 分支条件就退化成 **`player.lastSprinting`** —— 而它是 Grim 在
     * {@code MovementCheckRunner} **上一 tick 末尾**记录的冲刺状态
     * （`player.lastSprinting = player.isSprinting`）。
     *
     * <p>老实现挨打那一 tick 先 {@code setSprinting(true)}，原版攻击后又把它关掉，
     * tick 结束时的冲刺状态就是 false → **下一 tick 的 0.6 一个分支都没有**。
     * 于是每一次 ×0.6 都是「凭空少 40% 位移」，offset 恰好是 {@code 0.4 × v}：
     * <pre>
     *   .143976 = 0.4 × 0.36   ← 击退那一下 |kb|=0.36
     *   .046243 = 0.4 × 0.1156 ← 窗口第 2 击
     *   .006153 = 0.4 × 0.0154 ← 窗口尾部的残余速度
     * </pre>
     * 和 18:53 的日志逐条对得上。
     *
     * <p>所以攻击之后**立刻把冲刺打开**：客户端这一 tick 的移动仍按冲刺算
     * （{@code jumpMovementFactor 0.026}），服务端那份状态本来就是 true
     * （原版 {@code serverSprintState} 比对认为没变，一条 C0B 都不发），
     * 而 tick 结束时冲刺状态回到 true → 下一发的 0.6 才有分支可落。
     */
    private static void ensureSprinting(Object player) {
        if (player == null || isSprinting(player)) {
            return;
        }
        // 与 EntityPlayerSP.onLivingUpdate 里「重新开冲刺」的条件保持一致：
        // 必须有前进输入（moveForward >= 0.8）。没有前进输入还硬开冲刺就是
        // Grim 的 omni-sprint —— 它 `loopVectors()` 里 `forwardMin=1`，
        // 冲刺时只枚举「向前」的输入，凭空多出来的 0.026 加速度一样是 offset。
        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        double forward = input == null ? 0.0D
                : OpenMaverFeatures.number(input, "moveForward", "field_78900_b");
        if (forward < 0.8D) {
            return;
        }
        OpenMaverFeatures.callVoid(player, "setSprinting", "func_70031_b", null, Boolean.TRUE);
    }

    /**
     * 修补「服务端已知的冲刺状态」与客户端当前状态不一致的情况。
     *
     * <p>1.8.9 的冲刺包是 tick 末尾由 {@code EntityPlayerSP.func_175161_p()}
     * 比对 {@code serverSprintState}（{@code field_175171_bO}）之后才补发的，
     * 所以在 tick 开头改冲刺状态的模块（AutoSprint、Scaffold）会让服务端慢一 tick。
     * 这条路径把缺口在 C03 之前补上；正常情况下（一直保持冲刺）它一条包都不发。
     *
     * @param current 客户端当前冲刺状态
     * @return 同步是否可用（拿不到 C0B 也算可用，只是不修包）
     */
    private static boolean reconcileSprint(Object player, boolean current) {
        if (!slapSprintSync) {
            // 对测用：不发 C0B —— 就是 18:53 那局「冲刺状态只改客户端」的行为。
            return true;
        }
        int mark = current ? 1 : 0;
        if (sprintSynced == mark) {
            return true;
        }
        if (!sendSprintAction(player, current)) {
            return true;
        }
        syncVanillaSprintCache(player, current);
        sprintSynced = mark;
        return true;
    }

    /** 反射构造并发送 {@code C0BPacketEntityAction(player, START/STOP_SPRINTING)}。 */
    private static boolean sendSprintAction(Object player, boolean sprinting) {
        if (!ensureEntityAction()) {
            return false;
        }
        Object sendQueue = OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
        if (sendQueue == null) {
            return false;
        }
        try {
            Object action = sprinting ? actionStartSprint : actionStopSprint;
            Object packet = entityActionCtor.newInstance(player, action);
            return OpenMaverFeatures.callVoid(sendQueue, "addToSendQueue",
                    "func_147297_a", "a", packet);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@code C0BPacketEntityAction} + 两个 Action 枚举的反射查找。 */
    private static boolean ensureEntityAction() {
        if (entityActionCtor != null && actionStartSprint != null && actionStopSprint != null) {
            return true;
        }
        if (entityActionLookupFailed) {
            return false;
        }
        try {
            entityActionType = Class.forName(
                    "net.minecraft.network.play.client.C0BPacketEntityAction$Action");
            actionStartSprint = sprintAction("START_SPRINTING", 3);
            actionStopSprint = sprintAction("STOP_SPRINTING", 4);
            if (actionStartSprint == null || actionStopSprint == null) {
                throw new IllegalStateException("sprint action enum not found");
            }
            Class<?> packetType = Class.forName(
                    "net.minecraft.network.play.client.C0BPacketEntityAction");
            entityActionCtor = packetType.getConstructor(
                    Class.forName("net.minecraft.entity.Entity"), entityActionType);
            return true;
        } catch (Throwable error) {
            entityActionLookupFailed = true;
            Logger.warn(TAG, "C0B 冲刺包不可用，SPRINT SLAP 的冲刺状态无法同步: " + error);
            return false;
        }
    }

    /** 客户端当前是否在冲刺（{@code Entity#isSprinting} → {@code func_70051_ag}）。 */
    private static boolean isSprinting(Object player) {
        return Boolean.TRUE.equals(
                OpenMaverFeatures.call(player, "isSprinting", "func_70051_ag", null));
    }

    /**
     * 让原版「状态变了就补发一条冲刺包」的缓存跟我们一起走，避免重复发一条。
     *
     * <p>1.8.9 的这个字段是 {@code EntityPlayerSP.serverSprintState}
     * （{@code bew/bQ} → {@code field_175171_bO}，已用 forge 的
     * {@code deobfuscation_data-1.8.9.lzma} 核对）。
     */
    private static void syncVanillaSprintCache(Object player, boolean want) {
        try {
            Field field = OpenMaverFeatures.findField(player,
                    new String[]{"serverSprintState", "field_175171_bO"});
            if (field == null || field.getType() != Boolean.TYPE) {
                return;
            }
            field.setAccessible(true);
            field.setBoolean(player, want);
        } catch (Throwable ignored) {
            // 名字对不上就让它多发一条同名包，不影响语义。
        }
    }

    /** 优先按枚举名取；运行期被重映射时退回声明顺序（与 Criticals 同一套兜底）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object sprintAction(String name, int ordinal) {
        try {
            return Enum.valueOf((Class) entityActionType, name);
        } catch (Throwable ignored) {
            // 退回声明顺序
        }
        Object[] constants = entityActionType.getEnumConstants();
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

    /**
     * 选出「准星里的那个实体」。
     *
     * <p>先用 {@code mc.objectMouseOver}（原版准星，每帧由渲染线程算，最多 50ms 旧），
     * 再用当前 rotation 做一次锥形校验，挡掉「帧间隔里视角已经转走」的过期结果。
     * 只打准星内的目标，Grim 的 {@code Hitboxes}（"Tried to hit an entity outside
     * its valid hitbox"）就没有可乘之机 —— 之前 burst 按距离轮转打人，日志里那串
     * {@code Hitboxes (type=player)} 就是这么来的。
     */
    private static Object crosshairTarget(Minecraft mc, Object player) {
        Object mop = OpenMaverFeatures.read(mc, "objectMouseOver", "field_71476_x", null);
        if (mop != null) {
            // 不看 typeOfHit 的枚举名：类被混淆时 toString 可能不是 "ENTITY"，而
            // entityHit 非空本身就说明准星打到了实体（打方块时它是 null）。
            Object hit = OpenMaverFeatures.read(mop, "entityHit", "field_72308_g", null);
            if (hit instanceof EntityLivingBase && hit != player
                    && OpenMaverFeatures.alive((EntityLivingBase) hit)
                    && inAttackCone(player, (EntityLivingBase) hit, 6.0D)) {
                return hit;
            }
        }
        // 兜底：objectMouseOver 是**上一帧**算的，KillAura / Aim 在 tick START 里
        // 刚把 rotation 转过去时它会过期（KillAura 转完，准星还停在旧方向的实体
        // 上，或者干脆是 null）。这里按**当前 rotation** 扫一遍候选，用严格锥
        // （等于碰撞箱半宽，不加余量）—— 只有射线几乎必然打在盒子上才出手。
        return scanFrontTarget(mc, player, 0.0F);
    }

    /** 目标是否在「前方 ±maxYawDelta」的扇形里（0 = 不限制）。 */
    private static boolean withinYaw(Object player, EntityLivingBase target, float maxYawDelta) {
        if (maxYawDelta <= 0.0F) {
            return true;
        }
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        double dx = OpenMaverFeatures.number(target, "posX", "field_70165_t") - px;
        double dz = OpenMaverFeatures.number(target, "posZ", "field_70161_v") - pz;
        if (dx * dx + dz * dz < 1.0E-6D) {
            return false;
        }
        float desired = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        float current = (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z");
        return Math.abs(OpenMaverFeatures.wrapDegrees(desired - current)) <= maxYawDelta;
    }

    /**
     * 把视角转到目标身上（静默 aim：tick END 由 {@link #restoreSlapAim} 恢复相机）。
     *
     * <p>角度公式与 KillAura / Scaffold 保持一致：yaw 连续累加不取模
     * （过 Grim {@code AimModulo360}），pitch 夹在 ±89.5°。
     *
     * @return 是否真的写了视角
     */
    private static boolean aimAtSlapTarget(Object player, EntityLivingBase target) {
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        double dx = OpenMaverFeatures.number(target, "posX", "field_70165_t") - px;
        double dz = OpenMaverFeatures.number(target, "posZ", "field_70161_v") - pz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4D) {
            return false;
        }
        double height = OpenMaverFeatures.number(target, "height", "field_70131_O");
        if (height <= 0.0D) {
            height = 1.8D;
        }
        double targetY = OpenMaverFeatures.number(target, "posY", "field_70163_u") + height * 0.7D;
        double eyeY = OpenMaverFeatures.number(player, "posY", "field_70163_u")
                + OpenMaverFeatures.eyeHeight(player);
        float currentYaw = (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z");
        float currentPitch = (float) OpenMaverFeatures.number(player, "rotationPitch", "field_70125_A");
        float desiredYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        float desiredPitch = (float) (-Math.atan2(targetY - eyeY, horizontal) * 180.0D / Math.PI);
        desiredPitch = Math.max(-89.5F, Math.min(89.5F, desiredPitch));
        float writeYaw = OpenMaverFeatures.continuousYaw(currentYaw, desiredYaw);
        OpenMaverFeatures.setNumber(player, "rotationYaw", "field_70177_z", writeYaw);
        OpenMaverFeatures.setNumber(player, "rotationPitch", "field_70125_A", desiredPitch);
        OpenMaverFeatures.setNumber(player, "prevRotationYaw", "field_70126_B", writeYaw);
        OpenMaverFeatures.setNumber(player, "prevRotationPitch", "field_70127_C", desiredPitch);
        slapCameraYaw = currentYaw;
        slapCameraPitch = currentPitch;
        slapAimDirty = true;
        return true;
    }

    /** tick END：把相机视角恢复回去（C03 已经带着 aim 发出去了）。 */
    private static void restoreSlapAim(Object player) {
        if (!slapAimDirty) {
            return;
        }
        slapAimDirty = false;
        if (player == null) {
            return;
        }
        float yaw = OpenMaverFeatures.continuousYaw(
                (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z"),
                slapCameraYaw);
        OpenMaverFeatures.setNumber(player, "rotationYaw", "field_70177_z", yaw);
        OpenMaverFeatures.setNumber(player, "rotationPitch", "field_70125_A", slapCameraPitch);
        OpenMaverFeatures.setNumber(player, "prevRotationYaw", "field_70126_B", yaw);
        OpenMaverFeatures.setNumber(player, "prevRotationPitch", "field_70127_C", slapCameraPitch);
        OpenMaverFeatures.setNumber(player, "renderArmYaw", "field_71154_f", slapCameraYaw);
        OpenMaverFeatures.setNumber(player, "prevRenderArmYaw", "field_71163_h", slapCameraYaw);
        OpenMaverFeatures.setNumber(player, "renderArmPitch", "field_71155_g", slapCameraPitch);
        OpenMaverFeatures.setNumber(player, "prevRenderArmPitch", "field_71164_i", slapCameraPitch);
    }

    /**
     * 按当前 rotation 扫 {@code loadedEntityList}，返回「射线几乎一定命中碰撞箱」
     * 的最近实体。
     *
     * <p>严格锥：允许角 = {@code atan(0.30 / d)}（0.30 = 玩家半宽），再留 0.5°。
     * 比 {@link #crosshairTarget} 走 MOP 那一路更保守，所以不会把
     * {@code Hitboxes} 喂出来。
     */
    private static Object scanFrontTarget(Minecraft mc, Object player, float maxYawDelta) {
        Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
        if (world == null) {
            return null;
        }
        Object loaded = OpenMaverFeatures.read(world, "loadedEntityList", "field_72996_f", null);
        if (!(loaded instanceof List)) {
            return null;
        }
        List<?> entities = (List<?>) loaded;
        EntityLivingBase best = null;
        double bestAngle = Double.MAX_VALUE;
        for (int i = 0; i < entities.size(); i++) {
            Object candidate = entities.get(i);
            if (!(candidate instanceof EntityLivingBase) || candidate == player) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) candidate;
            if (!OpenMaverFeatures.alive(living)) {
                continue;
            }
            double[] cone = coneAngle(player, living);
            if (cone == null) {
                continue;
            }
            if (!withinYaw(player, living, maxYawDelta)) {
                continue;
            }
            double allowed = maxYawDelta <= 0.0F
                    ? Math.toDegrees(Math.atan2(0.30D, cone[1])) + 0.5D
                    : 180.0D;
            if (cone[0] > allowed) {
                continue;
            }
            if (cone[0] < bestAngle) {
                bestAngle = cone[0];
                best = living;
            }
        }
        return best;
    }

    /**
     * 粗略的「这条射线能不能打到这个实体的碰撞箱」判定。
     *
     * <p>用眼睛到实体中心的夹角和实体半宽算一个允许角：{@code atan(0.35/d) + 6°}。
     * 比真实 rayTrace 保守，宁可少打一次也不喂 Hitboxes。顺带把 reach 卡在 3.4 格内。
     */
    private static boolean inAttackCone(Object player, EntityLivingBase target, double marginDegrees) {
        double[] cone = coneAngle(player, target);
        if (cone == null) {
            return false;
        }
        double allowed = Math.toDegrees(Math.atan2(0.35D, cone[1])) + marginDegrees;
        return cone[0] <= allowed;
    }

    /**
     * 眼睛 -> 实体中心 的夹角与距离。
     *
     * @return {@code [angleDegrees, distance]}；太远（>3.05，服务端 1.8 只认 3.0）
     *         或在身后时返回 {@code null}
     */
    private static double[] coneAngle(Object player, EntityLivingBase target) {
        double px = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        double py = OpenMaverFeatures.number(player, "posY", "field_70163_u")
                + OpenMaverFeatures.eyeHeight(player);
        double pz = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        double yaw = Math.toRadians(OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z"));
        double pitch = Math.toRadians(OpenMaverFeatures.number(player, "rotationPitch", "field_70125_A"));
        double lookX = -Math.sin(yaw) * Math.cos(pitch);
        double lookY = -Math.sin(pitch);
        double lookZ = Math.cos(yaw) * Math.cos(pitch);
        double height = OpenMaverFeatures.number(target, "height", "field_70131_O");
        if (height <= 0.0D) {
            // 字段名对不上时 number() 返回 0：拿脚点当中心会让锥形判定在 3 格外
            // 偏出 17°，等于永远打不到。1.8 的玩家实体高 1.8，直接兜底。
            height = 1.8D;
        }
        double tx = OpenMaverFeatures.number(target, "posX", "field_70165_t") - px;
        double ty = OpenMaverFeatures.number(target, "posY", "field_70163_u")
                + height * 0.5D - py;
        double tz = OpenMaverFeatures.number(target, "posZ", "field_70161_v") - pz;
        double length = Math.sqrt(tx * tx + ty * ty + tz * tz);
        // 客户端准星用的是 getBlockReachDistance()=4.5，服务端 1.8 只认 3.0。
        // 按眼睛到实体中心 3.05 卡住，留 0.05 给碰撞箱半宽 —— 再远就是白送 Reach。
        if (length <= 1.0E-4D || length > 3.05D) {
            return null;
        }
        double dot = (tx * lookX + ty * lookY + tz * lookZ) / length;
        if (dot <= 0.0D) {
            return null;
        }
        double angle = Math.toDegrees(Math.acos(Math.min(1.0D, dot)));
        return new double[]{angle, length};
    }

    /**
     * 找 {@code C08PacketPlayerBlockPlacement(BlockPos, int, ItemStack, float, float, float)}。
     *
     * <p>MCP 与 SRG 的构造签名一致（构造器不参与 reobf），所以按类型数组取即可；
     * 取不到时退回单参构造 {@code (ItemStack)}，那也能让 Grim 拿到放置动作。
     */
    private static java.lang.reflect.Constructor<?> placePacketCtor() throws Exception {
        if (placePacketCtor != null) {
            return placePacketCtor;
        }
        Class<?> type = Class.forName("net.minecraft.network.play.client.C08PacketPlayerBlockPlacement");
        Class<?> posType = Class.forName("net.minecraft.util.BlockPos");
        Class<?> stackType = Class.forName("net.minecraft.item.ItemStack");
        try {
            placePacketCtor = type.getConstructor(posType, int.class, stackType,
                    float.class, float.class, float.class);
        } catch (NoSuchMethodException ignored) {
            placePacketCtor = type.getConstructor(stackType);
        }
        return placePacketCtor;
    }

    /**
     * 记录「服务端此刻认为我们在这里」。
     *
     * <p>憋包期间服务端收不到任何 C03，所以它眼里的坐标整个窗口都停在这个点上。
     * 爆发攻击时 reach / 角度 / 视线遮蔽全按它算，因此它必须在**扣第一个位置包之前**
     * 读一次，并且整个窗口内不再更新。
     */
    private static void recordBlinkOrigin(Object player) {
        blinkOriginX = OpenMaverFeatures.number(player, "posX", "field_70165_t");
        blinkOriginY = OpenMaverFeatures.number(player, "posY", "field_70163_u");
        blinkOriginZ = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
        blinkOriginYaw = (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z");
        blinkOriginPitch = (float) OpenMaverFeatures.number(player, "rotationPitch", "field_70125_A");
        blinkOriginValid = true;
    }

    /** 击退到达即开始憋包：之后的位置包由 Netty 管线的出站分支拦住。 */
    private static void startBlink() {
        if (blinking) {
            return;
        }
        blinking = true;
        blinkStartTick = clientTick;
        long cycles = BLINK_CYCLES.incrementAndGet();
        if (cycles <= 5L || cycles % 20L == 0L) {
            Logger.info(TAG, "blink #" + cycles + " 开始：憋 " + activeBlinkTicks()
                    + " tick（受力 " + Math.round(blinkKeepRatio() * 100.0D) + "%），"
                    + "然后爆发 " + clamp(burstAttacks, 1, 8) + " 条攻击包"
                    + "；服务端坐标=" + coordString());
        }
    }

    /**
     * 当前模式实际使用的憋包 tick 数。
     *
     * <p>UNCERTAINTY 只需要 1~3 tick：它的收益来自容差窗口，不是位置断档长度；
     * 断档越长，`Post` / 位置类检查越显眼。
     */
    private static int activeBlinkTicks() {
        if (effectiveMode() == MODE_UNCERTAINTY) {
            return clamp(uncertaintyTicks, 1, 3);
        }
        return clamp(blinkTicks, 1, 20);
    }

    /** 憋包窗口是否还没结束。 */
    private static boolean blinkWindowOpen() {
        return blinking && (clientTick - blinkStartTick) < activeBlinkTicks();
    }

    /** Blink 状态机：窗口结束时先爆发攻击包，再补发位置包。 */
    private static void tickBlink(Minecraft mc, Object player) {
        int mode = effectiveMode();
        if (mode != MODE_BLINK && mode != MODE_UNCERTAINTY) {
            if (blinking) {
                finishBlink(mc, player, false);
            }
            return;
        }
        if (!blinking) {
            return;
        }
        if (blinkWindowOpen()) {
            return;
        }
        finishBlink(mc, player);
    }

    /**
     * 结束憋包。
     *
     * <p>顺序很关键：
     * <ol>
     *   <li>{@link #syncRotation}：先发一条只改视角的 C05。服务端在结算 C02 时
     *       用的是**它自己那份 yaw/pitch** 做射线，憋了 5 tick 视角早就变了，
     *       不先对齐的话 5 条 C02 全部打在旧方向上（命中判定直接 miss）；</li>
     *   <li>再把 {@code burstAttacks} 条攻击包一次性打出去（C0A -> C02 交替），
     *       此刻服务端用的还是憋包前的旧位置，攻击结算与击退判定都在旧位置上；</li>
     *   <li>最后补发缓存的位置包，让服务端把这几 tick 的位移一次吃掉。</li>
     * </ol>
     */
    private static void finishBlink(Minecraft mc, Object player) {
        finishBlink(mc, player, true);
    }

    /**
     * 结束憋包。
     *
     * @param burst 是否补发攻击包；被动结束（切模式 / 关模块 / 掉线）时传 false，
     *              只把位置补齐，避免在非预期时机多发一轮攻击包
     */
    private static void finishBlink(Minecraft mc, Object player, boolean burst) {
        blinking = false;
        if (player == null) {
            heldPackets.clear();
            blinkOriginValid = false;
            return;
        }
        int held = getHeldCount();
        if (burst) {
            if (burstRotate) {
                syncRotation(player);
            }
            fireBurst(mc, player);
        }
        if (blinkFlush) {
            releaseHeld(null);
        } else if (blinkRewind) {
            dropAndRewind(player);
        } else {
            heldPackets.clear();
        }
        if (burst && held > 0 && (BLINK_CYCLES.get() <= 5L || BLINK_CYCLES.get() % 20L == 0L)) {
            Logger.info(TAG, "blink 结束：补发 " + held + " 条位置包，"
                    + "累计爆发攻击 " + BURST_SENT.get() + " 条"
                    + (blinkFlush ? "（原样补发）" : "（弃包）"));
        }
        blinkOriginValid = false;
    }

    /**
     * 爆发攻击前把服务端视角对齐到客户端当前视角。
     *
     * <p>只用 {@code C05PacketPlayerLook}（yaw + pitch + onGround，**没有坐标**）：
     * 服务端只会更新朝向，不会触发位移校验；Grim 对纯视角包不跑运动预测，
     * 所以这一条不会带出任何 Simulation offset，却能让后面的 C02 命中。
     *
     * <p>此时 {@code blinking} 已经是 false，所以这条包不会被出站分支扣住。
     */
    private static void syncRotation(Object player) {
        Object netHandler = OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
        if (netHandler == null) {
            return;
        }
        try {
            Class<?> type = findLookPacketType();
            if (type == null) {
                return;
            }
            float yaw = (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z");
            float pitch = (float) OpenMaverFeatures.number(player, "rotationPitch", "field_70125_A");
            // 注意：onGround 是 **boolean** 字段，number() 只认 Number，读它永远是 0。
            boolean onGround = OpenMaverFeatures.scaffoldOnGround(player);
            Object packet = type.getConstructor(float.class, float.class, boolean.class)
                    .newInstance(Float.valueOf(yaw), Float.valueOf(pitch), Boolean.valueOf(onGround));
            Object sent = OpenMaverFeatures.call(netHandler, "addToSendQueue", "func_147297_a", "a", packet);
            if (sent != null && ROTATE_SYNCED.incrementAndGet() == 1L) {
                Logger.info(TAG, "爆发前视角已对齐: " + type.getSimpleName()
                        + " yaw=" + round3(yaw) + " pitch=" + round3(pitch));
            }
        } catch (Throwable ignored) {
            // 类名/构造签名对不上时忽略：只是少了视角对齐，不影响位置包补发。
        }
    }

    /**
     * 找 {@code C03PacketPlayer$C05PacketPlayerLook}。
     *
     * <p>先按 MCP 全名找；找不到时扫 {@code C03PacketPlayer} 的所有嵌套类，
     * 认「构造函数是 (float, float, boolean)」那一个——因为不同映射/打包方式下
     * 嵌套类的名字可能不一样，而签名（yaw, pitch, onGround）是稳定的。
     */
    private static Class<?> findLookPacketType() {
        if (lookPacketType != null) {
            return lookPacketType;
        }
        try {
            lookPacketType = Class.forName(
                    "net.minecraft.network.play.client.C03PacketPlayer$C05PacketPlayerLook");
            return lookPacketType;
        } catch (Throwable ignored) {
            // 退化到签名扫描
        }
        try {
            Class<?> base = Class.forName("net.minecraft.network.play.client.C03PacketPlayer");
            Class<?>[] nested = base.getDeclaredClasses();
            for (int i = 0; i < nested.length; i++) {
                try {
                    nested[i].getConstructor(float.class, float.class, boolean.class);
                    lookPacketType = nested[i];
                    return lookPacketType;
                } catch (NoSuchMethodException ignored) {
                    // 不是 look 包
                }
            }
        } catch (Throwable ignored) {
            // 整个类都拿不到：放弃视角对齐
        }
        return null;
    }

    /**
     * 一口气补发攻击包。
     *
     * <p>1.8 服务端对同一实体有 10 tick 的 {@code hurtResistantTime} 无敌帧：
     * 同一个目标连吃 5 条 C02 只有第一条结算伤害，其余仍会被处理（消耗击退、
     * 触发 AntiKB 观察），所以这里按 {@code i % size} 在候选之间轮转——
     * 有多个目标时 5 条包能真的打 5 个人，只有一个目标时是把 5 条全给它。
     */
    private static void fireBurst(Minecraft mc, Object player) {
        if (mc == null) {
            return;
        }
        Object controller = OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        if (controller == null) {
            return;
        }
        List<Object> targets = burstTargets(mc, player);
        if (targets.isEmpty()) {
            long empty = BURST_EMPTY.incrementAndGet();
            if (empty <= 5L || empty % 20L == 0L) {
                Logger.warn(TAG, "burst 无目标：憋包起点 " + coordString()
                        + " 的 " + clamp(burstRange, 2.0F, 6.0F) + " 格内没有可攻击生物"
                        + "（reach 是按服务端认定的坐标算的，被推开后就是会空）");
            }
            return;
        }
        int count = clamp(burstAttacks, 1, 8);
        int sent = 0;
        for (int i = 0; i < count; i++) {
            Object target = targets.get(i % targets.size());
            // 1.8 的 PacketOrderB 要求 ANIMATION 在 INTERACT_ENTITY 之前。
            OpenMaverFeatures.callVoid(player, "swingItem", "func_71038_i", null);
            OpenMaverFeatures.callVoid(controller, "attackEntity", "func_78764_a", "a", player, target);
            sent++;
        }
        BURST_SENT.addAndGet(sent);
    }

    /**
     * 选出 burst 的攻击目标：按距离排序的可碰撞生物。
     *
     * <p><b>距离必须按憋包起点算</b>，不能用客户端当前位置：这 5 tick 里服务端
     * 一直认为我们站在 P0，reach 是拿 P0 到目标的距离判的。用当前位置筛目标，
     * 一旦被推开 2 格就会「客户端觉得在 3 格内、服务端算出 5 格」，
     * 5 条 C02 全部被 reach 丢掉。
     *
     * <p>同一个目标连续吃 C02 时服务端只结算第一条，其余会被受击无敌帧丢掉，
     * 所以 {@link #fireBurst} 按 {@code i % size} 在候选之间轮流打；只有一个目标
     * 时就把 {@code burstAttacks} 条全部打给它（这本来就是“一口气发攻击包”）。
     *
     * <p>这里**不读** {@code hurtResistantTime} 之类的受击字段：1.8.9 里相近的 SRG
     * 名很容易猜错，读错只会让过滤失效、退化成“全打最近那个”，不如按距离轮转可靠。
     */
    private static List<Object> burstTargets(Minecraft mc, Object player) {
        List<Object> result = new ArrayList<Object>();
        Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
        if (world == null) {
            return result;
        }
        Object loaded = OpenMaverFeatures.read(world, "loadedEntityList", "field_72996_f", null);
        if (!(loaded instanceof List)) {
            return result;
        }
        List<?> entities = (List<?>) loaded;
        double range = clamp(burstRange, 2.0F, 6.0F);
        List<Object> candidates = new ArrayList<Object>();
        List<Double> distances = new ArrayList<Double>();
        for (int i = 0; i < entities.size(); i++) {
            Object candidate = entities.get(i);
            if (!(candidate instanceof EntityLivingBase) || candidate == player) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) candidate;
            if (!OpenMaverFeatures.alive(living)) {
                continue;
            }
            if (!Boolean.TRUE.equals(
                    OpenMaverFeatures.call(living, "canBeCollidedWith", "func_70067_L", "ad"))) {
                continue;
            }
            double distance = burstDistance(player, living);
            if (distance > range) {
                continue;
            }
            insertSorted(candidates, distances, living, distance);
        }
        // 最多留 8 个候选，够 burst 轮转即可。
        int limit = 8;
        for (int i = 0; i < candidates.size() && result.size() < limit; i++) {
            result.add(candidates.get(i));
        }
        return result;
    }

    /**
     * burst 用的距离：憋包期间按起点 P0 算（服务端 reach 的判定基准就是 P0），
     * 没有起点记录时退回客户端实时距离。
     */
    private static double burstDistance(Object player, Object target) {
        if (!blinkOriginValid) {
            return entityDistance(player, target);
        }
        double dx = OpenMaverFeatures.number(target, "posX", "field_70165_t") - blinkOriginX;
        double dy = OpenMaverFeatures.number(target, "posY", "field_70163_u") - blinkOriginY;
        double dz = OpenMaverFeatures.number(target, "posZ", "field_70161_v") - blinkOriginZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 弃包 + 回退：把本地坐标拉回憋包起点，服务端与客户端重新对齐。
     *
     * <p>这是唯一能真正做到「完全不吃击退」的路径：5 tick 的位置包一条都不发，
     * 本地位置也回到 P0，服务端眼里我们从头到尾没动过。代价是接下来那个 tick
     * 的位置包会出现一段「本该有的击退位移缺失」，AntiKB / Simulation 一定会
     * 记一次账（大约 1 条 flag，而不是原来每 tick 一条、vl 涨到 78 的那种）。
     * 所以它适合低频使用（被打 → 弃包 → 立刻反打），不适合连续吃击退。
     */
    private static void dropAndRewind(Object player) {
        heldPackets.clear();
        if (!blinkOriginValid) {
            return;
        }
        writeHorizontal(player, 0.0D, 0.0D);
        if (!OpenMaverFeatures.callVoid(player, "setPosition", "func_70107_b", "b",
                blinkOriginX, blinkOriginY, blinkOriginZ)) {
            OpenMaverFeatures.setNumber(player, "posX", "field_70165_t", blinkOriginX);
            OpenMaverFeatures.setNumber(player, "posY", "field_70163_u", blinkOriginY);
            OpenMaverFeatures.setNumber(player, "posZ", "field_70161_v", blinkOriginZ);
        }
    }

    private static String coordString() {
        if (!blinkOriginValid) {
            return "-";
        }
        return "(" + round3(blinkOriginX) + ", " + round3(blinkOriginY)
                + ", " + round3(blinkOriginZ) + ")";
    }

    private static void insertSorted(List<Object> items, List<Double> keys, Object item, double key) {
        int index = 0;
        while (index < keys.size() && keys.get(index) <= key) {
            index++;
        }
        items.add(index, item);
        keys.add(index, Double.valueOf(key));
    }

    private static double entityDistance(Object player, Object target) {
        Object value = OpenMaverFeatures.call(player, "getDistanceSqToEntity",
                "func_70068_e", "e", target);
        if (value instanceof Number) {
            return Math.sqrt(((Number) value).doubleValue());
        }
        return Double.MAX_VALUE;
    }

    // ==================================================================
    // NOXZ（OpenZen AntiKB/NoXZ 移植）
    // ==================================================================

    /** 挂起期间被扣住的入站包（S32 等），Netty 线程写、主线程放。 */
    private static final List<Object> noxzInbound = new ArrayList<Object>();
    /** 挂起期间被扣住的出站 C03。 */
    private static final List<Object> noxzOutbound = new ArrayList<Object>();
    /** 是否处于挂起（击退已吞、位置包冻结）。 */
    private static volatile boolean noxzSuspending;
    /** 被吞掉的那个 S12（落地时补给客户端）。 */
    private static volatile Object noxzKbPacket;
    /** 主线程每 tick 刷新的 onGround 快照（Netty 线程只读）。 */
    private static volatile boolean noxzOnGround;
    /** 本 tick 结束后要把入站队列放出去。 */
    private static volatile boolean noxzFlushPending;
    private static int noxzSuspendTicks;
    private static Object noxzTarget;
    private static int noxzAttacksLeft;
    /** 收到传送后冷却几 tick，避免刚被 setback 就又进挂起。 */
    private static int noxzFlagCooldown;
    /** 安全阀：队列上限，防止异常情况下无限堆积。 */
    private static final int NOXZ_QUEUE_LIMIT = 200;
    private static final int NOXZ_SUSPEND_TIMEOUT = 12;
    /** NOXZ 的攻击次数（每个 1 次 = 一次 ×0.6）。 */
    private static int noxzAttackAmount = 3;
    /**
     * 是否允许 NoXZ 真正去扣 {@code S32PacketConfirmTransaction}。
     *
     * <p>默认 false：18:38/18:48 两局实机都证明这台服的 Grim 带 TransactionOrder
     * 检查（`TransactionOrder (vl:0.0): skipped=2`），扣回执立刻被记；而且落地
     * 放包时补回的是**全额**击退，被武器（击退棒）打时体感就是「Velocity 没生效」。
     * 关掉时 {@link #effectiveMode()} 把 MODE_NOXZ 降级成 SPRINT SLAP，
     * 这样即使配置/GUI 里还留着 mode=8 也不会踩这条已经失效的路。
     */
    private static boolean noxzTx = false;
    private static final java.util.concurrent.atomic.AtomicLong NOXZ_SUSPENDS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong NOXZ_ATTACKS =
            new java.util.concurrent.atomic.AtomicLong();

    private static boolean noxzEnabled() {
        return active && effectiveMode() == MODE_NOXZ;
    }

    /**
     * 入站过滤（Netty 线程）。返回 true = 吞掉。
     *
     * <p>三种包：
     * <ul>
     *   <li>本方向上的 S12：地上 + 有目标 + 冲刺 → 放行（当普通击退处理，
     *       后面靠攻击窗口削）；否则吞掉并进入挂起；</li>
     *   <li>挂起期间的 {@code S32PacketConfirmTransaction}：扣住（击退的
     *       transaction 不确认，Grim 就把这段击退当「可能还没到」）；</li>
     *   <li>挂起期间的 {@code S08PacketPlayerPosLook}：立刻放掉全部队列并退出挂起
     *       （被 setback 了，继续挂只会更糟）。</li>
     * </ul>
     */
    private static boolean noxzInboundFilter(Object msg) {
        String name = msg.getClass().getName();
        if (name.startsWith("net.minecraft.network.play.server.S08PacketPlayerPosLook")) {
            if (noxzSuspending) {
                requestNoXzRelease("收到传送（被 setback）");
            }
            noxzFlagCooldown = 2;
            return false;
        }
        if (name.startsWith("net.minecraft.network.play.server.S12PacketEntityVelocity")) {
            int[] values = intFields(msg);
            if (values == null || values.length < 4) {
                return false;
            }
            if (localEntityId == Integer.MIN_VALUE || values[0] != localEntityId) {
                return false;
            }
            if (values[2] <= 0) {
                // 向下的速度（爆炸/下压）不处理：不是击退。
                return false;
            }
            if (noxzKbPacket != null || noxzSuspending) {
                // 窗口里又来一发：继续吞，避免两段击退叠加。
                return true;
            }
            if (noxzFlagCooldown > 0 || noxzOnGround) {
                // 站地上（或刚被 setback）：这一下原样交给客户端 —— 但它**必须
                // 被记进 pendingKb**，否则 applyPendingKnockback 根本看不到它，
                // MODE_NOXZ 的攻击窗口也就不会开。之前这里直接 return false，
                // 地面挨打连窗口都没有，等于模块彻底没做事。
                pendingKbX = values[1] / 8000.0D;
                pendingKbY = values[2] / 8000.0D;
                pendingKbZ = values[3] / 8000.0D;
                pendingKbRawHorizontal = Math.hypot(values[1], values[3]);
                kbTransactionMark = TRANSACTIONS.get();
                kbArriveNanos = System.nanoTime();
                kbPending = true;
                KB_SEEN.incrementAndGet();
                return false;
            }
            noxzKbPacket = msg;
            noxzSuspending = true;
            noxzSuspendTicks = 0;
            // 记下这次击退的原始水平量：放包那一刻如果没目标可打，会退化成
            // 攻击窗口（beginSlap），窗口长度要用它查表。
            pendingKbRawHorizontal = Math.hypot(values[1], values[3]);
            long n = NOXZ_SUSPENDS.incrementAndGet();
            if (n <= 5L || n % 20L == 0L) {
                Logger.info(TAG, "noxz 挂起 #" + n + "：吞掉击退 + 冻结位置包"
                        + "（v=" + (values[2] / 8000.0D) + "），等落地再放包");
            }
            return true;
        }
        if (noxzSuspending
                && name.startsWith("net.minecraft.network.play.server.S32PacketConfirmTransaction")) {
            synchronized (noxzInbound) {
                if (noxzInbound.size() < NOXZ_QUEUE_LIMIT) {
                    noxzInbound.add(msg);
                    return true;
                }
            }
        }
        return false;
    }

    /** 出站 C03 在挂起期间扣住（走 noxzOutbound，不走 blink 的 heldPackets）。 */
    private static boolean noxzShouldHoldOutbound(Object msg) {
        if (!noxzSuspending) {
            return false;
        }
        String name = msg.getClass().getName();
        if (!name.startsWith("net.minecraft.network.play.client.C03PacketPlayer")) {
            return false;
        }
        if (name.endsWith("$C05PacketPlayerLook")) {
            return false;
        }
        synchronized (noxzOutbound) {
            if (noxzOutbound.size() < NOXZ_QUEUE_LIMIT) {
                noxzOutbound.add(msg);
                return true;
            }
        }
        return false;
    }

    /**
     * 彻底退出 NOXZ：把扣住的包全部放回去（关模块、换世界、摘管线都要走）。
     *
     * <p>位置包丢了 = 服务端眼里我们停在原地，之后必然被 setback。
     */
    private static void stopNoXz(String reason) {
        boolean pending = noxzSuspending || noxzKbPacket != null;
        if (!pending) {
            return;
        }
        noxzSuspending = false;
        noxzSuspendTicks = 0;
        flushNoXzOutbound();
        applyNoXzKnockback(trackedPlayer);
        flushNoXzInbound();
        noxzKbPacket = null;
        Logger.warn(TAG, "noxz 收尾（" + reason + "）：位置包与 transaction 已归还");
    }

    /** 由主线程发起「放队列 + 退出挂起」。 */
    private static void requestNoXzRelease(String reason) {
        noxzSuspending = false;
        noxzSuspendTicks = 0;
        noxzFlushPending = true;
        Logger.warn(TAG, "noxz 释放：" + reason);
    }

    /**
     * 主线程：把扣住的出站 C03 交给管线（在 eventLoop 上执行，避免跨线程直接
     * 跑 Netty 的写路径）。
     */
    private static void flushNoXzOutbound() {
        List<Object> copy;
        synchronized (noxzOutbound) {
            if (noxzOutbound.isEmpty()) {
                return;
            }
            copy = new ArrayList<Object>(noxzOutbound);
            noxzOutbound.clear();
        }
        ChannelHandlerContext ctx = hookContext;
        if (ctx == null) {
            return;
        }
        try {
            ctx.channel().eventLoop().execute(new Runnable() {
                public void run() {
                    for (int i = 0; i < copy.size(); i++) {
                        ctx.write(copy.get(i), ctx.newPromise());
                    }
                    ctx.flush();
                }
            });
        } catch (Throwable ignored) {
            // 连接已断开：丢掉即可。
        }
    }

    /** 主线程：把扣住的入站包（S32）交还给管线。 */
    private static void flushNoXzInbound() {
        List<Object> copy;
        synchronized (noxzInbound) {
            if (noxzInbound.isEmpty()) {
                return;
            }
            copy = new ArrayList<Object>(noxzInbound);
            noxzInbound.clear();
        }
        ChannelHandlerContext ctx = hookContext;
        if (ctx == null) {
            return;
        }
        try {
            ctx.channel().eventLoop().execute(new Runnable() {
                public void run() {
                    for (int i = 0; i < copy.size(); i++) {
                        ctx.fireChannelRead(copy.get(i));
                    }
                }
            });
        } catch (Throwable ignored) {
            // 同上。
        }
    }

    /** 把吞掉的 S12 补给客户端（此刻客户端才真的吃这一下击退）。 */
    private static void applyNoXzKnockback(Object player) {
        Object packet = noxzKbPacket;
        noxzKbPacket = null;
        if (packet == null || player == null) {
            return;
        }
        Object netHandler = OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
        if (netHandler == null) {
            return;
        }
        // Packet#processPacket(INetHandler)（SRG func_148833_a）→
        // NetHandlerPlayClient.handleEntityVelocity → Entity.setVelocity。
        OpenMaverFeatures.call(packet, "processPacket", "func_148833_a", "a", netHandler);
    }

    /** 主线程 tick：挂起状态机 + 攻击序列。 */
    private static void tickNoXz(Minecraft mc, Object player) {
        noxzOnGround = player != null && OpenMaverFeatures.scaffoldOnGround(player);
        if (!noxzEnabled()) {
            if (noxzSuspending || noxzKbPacket != null) {
                noxzSuspending = false;
                flushNoXzOutbound();
                applyNoXzKnockback(player);
                flushNoXzInbound();
                noxzKbPacket = null;
            }
            return;
        }
        // 上一轮排好的入站回放（此时本 tick 的 C03 已经发出去了）。
        if (noxzFlushPending) {
            noxzFlushPending = false;
            flushNoXzInbound();
        }
        if (player == null) {
            return;
        }
        if (noxzFlagCooldown > 0) {
            noxzFlagCooldown--;
            noxzAttacksLeft = 0;
            noxzTarget = null;
            return;
        }
        // 攻击序列：每个 tick 一次
        if (noxzAttacksLeft > 0) {
            if (!noXzAttack(mc, player)) {
                noxzAttacksLeft = 0;
                noxzTarget = null;
            }
        }
        if (!noxzSuspending) {
            return;
        }
        noxzSuspendTicks++;
        if (!noxzOnGround && noxzSuspendTicks < NOXZ_SUSPEND_TIMEOUT) {
            return;
        }
        boolean timeout = !noxzOnGround;
        Object target = mc == null ? null : noXzPickTarget(mc, player);
        // 这里不再要求「正在冲刺」：noXzAttack 会先用 C0B 把冲刺打开再打，
        // 原版 i>0 才会乘 0.6。要求冲刺只会让「站着挨打」这一最常见的场景
        // 直接放包（等于模块没做事）。
        boolean canAttack = target != null;
        if (!timeout && canAttack) {
            // 顺序与 OpenZen 一致：先交位置（不含击退的轨迹）→ 再补击退 → 再打。
            flushNoXzOutbound();
            applyNoXzKnockback(player);
            noxzTarget = target;
            slapTicksLeft = 0;
            noxzAttacksLeft = clamp(noxzAttackAmount, 1, SLAP_MAX_BRANCHES);
            noxzFlushPending = true;
            noxzSuspending = false;
            noxzSuspendTicks = 0;
            if (NOXZ_SUSPENDS.get() <= 5L || NOXZ_SUSPENDS.get() % 20L == 0L) {
                Logger.info(TAG, "noxz 落地：放出位置包 + 补上击退，开始 "
                        + noxzAttacksLeft + " 次攻击（每次 ×0.6）");
            }
            return;
        }
        // 没目标 / 超时：老老实实放包（含击退），并把冲刺停掉（OpenZen 同款）。
        // 停冲刺也要发包（C0B），否则服务端这一 tick 仍按冲刺算加速度。
        flushNoXzOutbound();
        applyNoXzKnockback(player);
        noxzFlushPending = true;
        noxzSuspending = false;
        noxzSuspendTicks = 0;
        // 不要在这里关冲刺：关掉之后 tick 结束时 lastSprinting=false，
        // 下一次 slap 的 0.6 就没有服务端分支，等于白送 0.4·v 的 offset。
        ensureSprinting(player);
        // 这一下是全额吃到的（没目标就没法用 0.6 削），但接下来几 tick 只要
        // 打到人还能把速度乘回去 —— 直接放弃就不是「没目标」而是「没做事」。
        beginSlap();
        Logger.warn(TAG, "noxz 放包：" + (timeout ? "超时" : "落点没有可攻击目标"));
    }

    /** NOXZ 的目标：准星实体（含 MOP 过期兜底）或 KillAura 的当前目标。 */
    private static Object noXzPickTarget(Minecraft mc, Object player) {
        Object crosshair = crosshairTarget(mc, player);
        if (crosshair != null) {
            return crosshair;
        }
        Object kiss = OpenMaverFeatures.read(mc, "objectMouseOver", "field_71476_x", null);
        if (kiss != null) {
            Object hit = OpenMaverFeatures.read(kiss, "entityHit", "field_72308_g", null);
            if (hit instanceof EntityLivingBase && hit != player
                    && OpenMaverFeatures.alive((EntityLivingBase) hit)) {
                return hit;
            }
        }
        return null;
    }

    /**
     * NOXZ 的一次攻击。
     *
     * <p>和 SPRINT SLAP 走同一条路：先发包把冲刺状态同步上去（C0B START），
     * 再让原版 {@code attackTargetEntityWithCurrentItem} 自己乘 0.6 并复位冲刺，
     * 然后立刻补一条 C0B STOP —— 服务端这一 tick 的预测与实际逐位对齐。
     *
     * <p>原版没乘（目标在 10 tick 无敌帧里 / 伤害没结算）时**什么都不做**：
     * 那种情况下服务端没有 0.6 分支，自己补乘一次就是凭空少 40% 位移。
     */
    private static boolean noXzAttack(Minecraft mc, Object player) {
        Object target = noxzTarget;
        if (!(target instanceof EntityLivingBase) || !OpenMaverFeatures.alive((EntityLivingBase) target)) {
            return false;
        }
        Object controller = OpenMaverFeatures.read(mc, "playerController", "field_71442_b", null);
        if (controller == null) {
            return false;
        }
        if (slapReach(player, target) > SLAP_MAX_REACH) {
            return false;
        }
        if (!isSprinting(player) || !sprintAtLastTickEnd) {
            // 同上：0.6 的分支只看上一 tick 的 lastSprinting。
            return false;
        }
        double beforeX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
        double beforeZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
        OpenMaverFeatures.callVoid(player, "swingItem", "func_71038_i", null);
        // 1.8.9 的 attackEntity 是 void，只能按 void 判成败。
        if (!OpenMaverFeatures.callVoid(controller, "attackEntity",
                "func_78764_a", "a", player, target)) {
            return false;
        }
        double afterX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
        double afterZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
        boolean vanillaSlowed = closeEnough(afterX, beforeX * 0.6D)
                && closeEnough(afterZ, beforeZ * 0.6D);
        if (!vanillaSlowed) {
            return false;
        }
        ensureSprinting(player);
        noxzAttacksLeft--;
        long n = NOXZ_ATTACKS.incrementAndGet();
        if (n <= 5L || n % 40L == 0L) {
            Logger.info(TAG, "noxz 攻击 #" + n + " motion=(" + round3(beforeX) + ","
                    + round3(beforeZ) + ")->(" + round3(afterX) + ","
                    + round3(afterZ) + ") 冲刺已同步 剩余=" + noxzAttacksLeft);
        }
        return noxzAttacksLeft > 0;
    }

    // ==================================================================
    // Netty 管线（由 VelocityPacketHook 回调）
    // ==================================================================

    /** 入站 S12：判断是否吞掉，并记录击退向量。 */
    static boolean shouldCancelVelocityPacket(Object msg) {
        if (msg == null || !active) {
            return false;
        }
        String name = msg.getClass().getName();
        // S32 一律计数：slap 窗口靠它判断「Grim 已经把这次击退算进 velocity 了没有」。
        // NoXZ 模式会把这个包扣住，但它是否出现同样算回执推进。
        if (name.startsWith("net.minecraft.network.play.server.S32PacketConfirmTransaction")) {
            TRANSACTIONS.incrementAndGet();
            return effectiveMode() == MODE_NOXZ && noxzInboundFilter(msg);
        }
        // NOXZ 还要处理 S08（被 setback 时立刻放包）。
        if (effectiveMode() == MODE_NOXZ) {
            return noxzInboundFilter(msg);
        }
        if (!name.startsWith("net.minecraft.network.play.server.S12PacketEntityVelocity")) {
            return false;
        }
        int[] values = intFields(msg);
        if (values == null || values.length < 4) {
            return false;
        }
        if (localEntityId == Integer.MIN_VALUE || values[0] != localEntityId) {
            return false;
        }
        int effective = effectiveMode();
        // 比例 1/1 的 MODIFY 等价原版，直接放行，少一层伪造速度。
        if (effective == MODE_MODIFY && horizontal >= 0.999F && vertical >= 0.999F) {
            return false;
        }
        // SPRINT SLAP：记录窗口长度后**原样放行**。
        //
        // 这里是整个模式的命门：客户端必须真的吃到服务端发的那一次击退，
        // 之后才有「×0.6」可削；一旦吞掉，Grim 的 AntiKB（用两个 transaction
        // 把 S12 夹住，再看玩家有没有真的动）立刻记 offset。
        if (effective == MODE_SPRINT_SLAP) {
            pendingKbX = values[1] / 8000.0D;
            pendingKbY = values[2] / 8000.0D;
            pendingKbZ = values[3] / 8000.0D;
            pendingKbRawHorizontal = Math.hypot(values[1], values[3]);
            kbTransactionMark = TRANSACTIONS.get();
            kbArriveNanos = System.nanoTime();
            kbPending = true;
            KB_SEEN.incrementAndGet();
            return false;
        }
        pendingKbX = values[1] / 8000.0D;
        pendingKbY = values[2] / 8000.0D;
        pendingKbZ = values[3] / 8000.0D;
        kbTransactionMark = TRANSACTIONS.get();
        kbArriveNanos = System.nanoTime();
        kbPending = true;
        KB_SEEN.incrementAndGet();
        return true;
    }

    /** 出站：blink 窗口内的位置包先扣住。 */
    static boolean shouldHoldOutbound(Object msg) {
        if (msg == null || !active) {
            return false;
        }
        if (effectiveMode() == MODE_NOXZ) {
            return noxzShouldHoldOutbound(msg);
        }
        if (!blinking) {
            return false;
        }
        String name = msg.getClass().getName();
        if (!name.startsWith("net.minecraft.network.play.client.C03PacketPlayer")) {
            return false;
        }
        // 纯视角包 C05 一条都不能扣：它不带位移，扣了只是白多一条飞包，
        // Grim 的 Timer 会把这几条算进 packet 速率（日志里那串 Timer 就是
        // releaseHeld 一口气补发的 C05 + C03）。C04/C06（带坐标）才需要憋。
        //
        // 骑马时也不扣：载具位置由 S0C/S0D 驱动，扣住位置包只会让服务端
        // 一直等一个不会来的确认（BadPacketsN）。
        if (name.endsWith("$C05PacketPlayerLook")) {
            return false;
        }
        if (riding) {
            return false;
        }
        return true;
    }

    /** 本地玩家是否在载具上（主线程每 tick 刷新，Netty 线程只读）。 */
    private static volatile boolean riding;

    /** 出站旁路统计：这一 tick 又发了一条 C02（由 Netty 线程调用，只碰原子计数）。 */
    static void noteOutbound(Object msg) {
        if (msg == null) {
            return;
        }
        if (msg.getClass().getName().startsWith(
                "net.minecraft.network.play.client.C02PacketUseEntity")) {
            ATTACKS_THIS_TICK.incrementAndGet();
        }
    }

    static void holdOutbound(Object msg) {
        synchronized (heldPackets) {
            if (heldPackets.size() >= HELD_LIMIT) {
                heldPackets.remove(0);
            }
            heldPackets.add(msg);
        }
    }

    /**
     * 补发缓存的位置包。
     *
     * @param ctx 管线上下文；null 时使用注入时记下的那个
     */
    static void releaseHeld(ChannelHandlerContext ctx) {
        ChannelHandlerContext target = (ctx != null) ? ctx : hookContext;
        List<Object> copy;
        synchronized (heldPackets) {
            if (heldPackets.isEmpty()) {
                return;
            }
            copy = new ArrayList<Object>(heldPackets);
            heldPackets.clear();
        }
        if (target == null) {
            return;
        }
        try {
            for (int i = 0; i < copy.size(); i++) {
                target.write(copy.get(i), target.newPromise());
            }
            target.flush();
        } catch (Throwable ignored) {
            // 连接已经断开时补发失败，直接丢掉即可。
        }
    }

    static void attachContext(ChannelHandlerContext ctx) {
        hookContext = ctx;
    }

    static void detachContext(ChannelHandlerContext ctx) {
        if (hookContext == ctx) {
            hookContext = null;
        }
    }

    // ==================================================================
    // 管线注入
    // ==================================================================

    /**
     * 维护包管线注入（每 tick 调用，内部按 {@link #HOOK_RETRY_INTERVAL} 节流）。
     *
     * <p><b>为什么不能只装一次</b>：单人世界 / 服务器 / 重连 / 换大厅都会新建一条
     * {@code NetworkManager + Channel + Pipeline}。如果只在第一次挂上就记住
     * {@code hookInstalled}，换连接后新管线永远不会挂 handler，而嗅探兜底又会被
     * {@code hookInstalled} 挡住 —— 模块会**完全静默失效**（实测就是这么踩的：
     * 在单人世界开启模块，再连服务器，之后没有任何效果）。
     *
     * <p>因此这里用 {@code NetHandlerPlayClient} 实例作为「连接标识」：
     * 它一变就重挂；另外每秒校验一次 handler 是否还在管线里（被其它插件摘掉时自愈）。
     */
    private static void maintainHook(Minecraft mc, Object player) {
        Object netHandler = resolveNetHandler(mc, player);
        if (netHandler != hookNetHandler) {
            detachHook("连接变化");
            hookNetHandler = netHandler;
        }
        if (clientTick - hookAttemptTick < HOOK_RETRY_INTERVAL) {
            return;
        }
        hookAttemptTick = clientTick;
        if (hookInstalled && pipelineHasHook()) {
            return;
        }
        detachHook("管线里已没有 handler");
        if (netHandler != null) {
            attachHook(netHandler);
        }
    }

    /** 取当前连接的 {@code NetHandlerPlayClient}（换连接时对象会变）。 */
    private static Object resolveNetHandler(Minecraft mc, Object player) {
        Object netHandler = OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
        if (netHandler != null) {
            return netHandler;
        }
        return OpenMaverFeatures.call(mc, "getNetHandler", "func_147114_u", null);
    }

    /** 反射链：NetHandler -> NetworkManager -> Channel -> Pipeline，然后挂 handler。 */
    private static void attachHook(Object netHandler) {
        try {
            Object networkManager = fieldByTypeName(netHandler, "net.minecraft.network.NetworkManager");
            if (networkManager == null) {
                warnHookFailure("NetHandlerPlayClient 里找不到 NetworkManager 字段");
                return;
            }
            Object channel = fieldByTypeName(networkManager, "io.netty.channel.Channel");
            if (channel == null) {
                warnHookFailure("NetworkManager 里找不到 Channel 字段");
                return;
            }
            Object pipelineObject = OpenMaverFeatures.call(channel, "pipeline", "pipeline", "pipeline");
            if (!(pipelineObject instanceof ChannelPipeline)) {
                warnHookFailure("Channel.pipeline() 返回的不是 ChannelPipeline");
                return;
            }
            ChannelPipeline pipeline = (ChannelPipeline) pipelineObject;
            List<String> names = pipeline.names();
            if (names != null && names.contains(VelocityPacketHook.NAME)) {
                hookInstalled = true;
                hookPipeline = pipeline;
                return;
            }
            VelocityPacketHook hook = new VelocityPacketHook();
            String base = pickBaseName(names);
            if (base != null) {
                pipeline.addBefore(base, VelocityPacketHook.NAME, hook);
            } else {
                // 兜底：挂在尾部。出站仍然能拿到 Packet（出站从 tail 开始走），
                // 但入站会晚于 packet_handler，退化为「不拦 S12 + 事后修正」。
                pipeline.addLast(VelocityPacketHook.NAME, hook);
            }
            hookInstance = hook;
            hookPipeline = pipeline;
            hookInstalled = true;
            hookWarned = false;
            Logger.info(TAG, "包管线已挂上: addBefore(" + base + ", "
                    + VelocityPacketHook.NAME + ")；当前管线="
                    + (names == null ? "?" : names.toString()));
        } catch (Throwable error) {
            warnHookFailure("管线注入异常: " + error);
        }
    }

    /** 只在状态变化时打日志，避免每秒刷一次。 */
    private static void warnHookFailure(String reason) {
        if (hookWarned) {
            return;
        }
        hookWarned = true;
        Logger.warn(TAG, "包管线不可用，退化为运动嗅探: " + reason);
    }

    private static boolean pipelineHasHook() {
        ChannelPipeline pipeline = hookPipeline;
        if (pipeline == null) {
            return false;
        }
        try {
            List<String> names = pipeline.names();
            return names != null && names.contains(VelocityPacketHook.NAME);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 摘掉旧连接的 handler，并把憋住的位置包尽力补发掉。 */
    private static void detachHook(String reason) {
        stopNoXz(reason);
        ChannelPipeline pipeline = hookPipeline;
        VelocityPacketHook hook = hookInstance;
        releaseHeld(null);
        hookContext = null;
        if (pipeline != null && hook != null) {
            try {
                pipeline.remove(hook);
            } catch (Throwable ignored) {
                // 连接已经关闭 / 管线已销毁。
            }
        }
        if (hookInstalled) {
            Logger.info(TAG, "包管线已摘除: " + reason);
        }
        hookPipeline = null;
        hookInstance = null;
        hookInstalled = false;
    }

    /**
     * 选择插入点。
     *
     * <p>1.8.9 的管线顺序（已用真实 jar 的 {@code NetworkManager$ChannelInitializer}
     * 校验）是：
     * <pre>
     *   timeout -> splitter -> decoder -> prepender -> encoder -> packet_handler
     * </pre>
     * 出站方向是 **从 tail 往 head** 走，所以 {@code packet_handler} 先于
     * {@code encoder} 执行；要让出站还能看到 {@code Packet} 对象（而不是
     * {@code encoder} 序列化后的 ByteBuf），必须插在 {@code encoder} **之后**，
     * 也就是 {@code packet_handler} 之前。这个位置同时也在 {@code decoder} 之后，
     * 入站拿到的同样是 {@code Packet}。插在 {@code encoder} 之前看着「相近」，
     * 实际上出站只会拿到 ByteBuf，blink 会完全失效。
     */
    static String pickBaseName(List<String> names) {
        if (names == null) {
            return null;
        }
        if (names.contains("packet_handler")) {
            return "packet_handler";
        }
        return null;
    }

    /** 按类型名在对象（含父类）的字段里找一个非 null 的值。 */
    private static Object fieldByTypeName(Object target, String typeName) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            Field[] fields = type.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                if (!fields[i].getType().getName().equals(typeName)) {
                    continue;
                }
                try {
                    fields[i].setAccessible(true);
                    Object value = fields[i].get(target);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // 下一个字段
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    // ==================================================================
    // 无管线兜底：运动差分
    // ==================================================================

    /**
     * 没有管线时用「运动突变 + 击退特征」近似识别击退。
     *
     * <p>原版击退会把速度设成水平 0.4（站立）/ 0.6+（冲刺）、垂直 0.36~0.4，并且
     * 与上一 tick 的运动差值远大于正常加速度：
     * <ul>
     *   <li>水平阈值 {@link #SNIFF_MIN_HORIZONTAL}：冲刺跳最多约 0.28，
     *       正常移动 0.1~0.22，击退一定 ≥ 0.4；</li>
     *   <li>同时要求与上一 tick 的快照有明显差值，排除匀速移动；</li>
     *   <li>垂直必须非负（击退不会把人往下压），排除掉落加速度。</li>
     * </ul>
     * 只做近似：可能把爆炸/指令速度也当成击退（这本来就是“被击退”），
     * 但不会在跳/走的时候误触发。
     */
    private static void sniffKnockback(Object player) {
        if (hookInstalled) {
            return;
        }
        double mx = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
        double my = OpenMaverFeatures.number(player, "motionY", "field_70181_x");
        double mz = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
        if (!motionTracked || kbPending || my < 0.0D) {
            return;
        }
        double horizontal = Math.hypot(mx, mz);
        if (horizontal < SNIFF_MIN_HORIZONTAL) {
            return;
        }
        double deltaH = Math.hypot(mx - lastMotionX, mz - lastMotionZ);
        double deltaV = my - lastMotionY;
        if (deltaH <= 0.10D && deltaV <= 0.15D) {
            return;
        }
        pendingKbX = mx;
        pendingKbY = my;
        pendingKbZ = mz;
        kbArriveNanos = System.nanoTime();
        kbPending = true;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 读取本地玩家的实体 id。
     *
     * <p>优先走 {@code Entity#getEntityId}（reobf 表里已验证的 SRG：
     * {@code func_145782_y}），字段名只作为兜底——猜错字段名会让 S12 全部被放行，
     * 模块静默失效，所以这里宁可多一条路径。
     * 两条路径都失败时返回 {@link Integer#MIN_VALUE}：管线不再拦包，行为退化为
     * 「运动差分嗅探 + 事后修正」。
     */
    private static int readLocalEntityId(Object player) {
        Object value = OpenMaverFeatures.call(player, "getEntityId", "func_145782_y", null);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        Object field = OpenMaverFeatures.number(player, "entityId", "field_70157_k");
        int id = (int) ((Number) field).doubleValue();
        if (id != 0) {
            return id;
        }
        if (!idResolveWarned) {
            idResolveWarned = true;
            System.err.println("[OpenMaver] Velocity 无法读取本地实体 id，"
                    + "S12 拦截关闭，退化为运动嗅探");
        }
        return Integer.MIN_VALUE;
    }

    private static void writeMotion(Object player, double x, double y, double z) {
        OpenMaverFeatures.setNumber(player, "motionX", "field_70159_w", x);
        OpenMaverFeatures.setNumber(player, "motionY", "field_70181_x", y);
        OpenMaverFeatures.setNumber(player, "motionZ", "field_70179_y", z);
    }

    /** 只写水平分量，垂直交给 {@link #writeVerticalKb}。 */
    private static void writeHorizontal(Object player, double x, double z) {
        OpenMaverFeatures.setNumber(player, "motionX", "field_70159_w", x);
        OpenMaverFeatures.setNumber(player, "motionZ", "field_70179_y", z);
    }

    /**
     * 写垂直击退分量。
     *
     * <p>站在地上时**不写**：把 {@code motionY} 抬到击退的 0.2~0.36 会让玩家
     * 离地 2~3 tick 再落回来，服务端看到的就是「悬空却说没在地上」——
     * 日志里那串 {@code GroundSpoof (vl:0/1/2/3): claimed false} 正是这么来的。
     * 垂直击退对防御没有任何价值（它只影响自己被击退后的滞空高度），
     * 所以落地状态直接放掉这一段是最划算的。
     */
    private static void writeVerticalKb(Object player, double value) {
        // 同上：boolean 字段必须用 scaffoldOnGround() 读，number() 会恒为 0，
        // 于是「落地时不写垂直」这条守卫实际上从来没生效过（17:48 那串
        // GroundSpoof: claimed false 也有它一份）。
        if (OpenMaverFeatures.scaffoldOnGround(player)) {
            return;
        }
        OpenMaverFeatures.setNumber(player, "motionY", "field_70181_x", value);
    }

    private static int[] intFields(Object packet) {
        Class<?> type = packet.getClass();
        Field[] cached = INT_FIELD_CACHE.get(type);
        if (cached == null) {
            Field[] declared = type.getDeclaredFields();
            List<Field> ints = new ArrayList<Field>();
            for (int i = 0; i < declared.length; i++) {
                if (declared[i].getType() == Integer.TYPE) {
                    declared[i].setAccessible(true);
                    ints.add(declared[i]);
                }
            }
            cached = ints.toArray(new Field[ints.size()]);
            INT_FIELD_CACHE.put(type, cached);
        }
        if (cached.length < 4) {
            return null;
        }
        int[] values = new int[cached.length];
        try {
            for (int i = 0; i < cached.length; i++) {
                values[i] = cached[i].getInt(packet);
            }
        } catch (Throwable ignored) {
            return null;
        }
        return values;
    }

    /**
     * 主动结束憋包并把缓存的位置包补发掉。
     *
     * <p>切模式、关模块、换世界、热重载配置时都要走这条路径：位置包里存的是
     * 具体的 {@code C03PacketPlayer} 实例，留在队列里会永远发不出去，
     * 服务端就会一直停在我们憋包前的坐标。
     */
    private static void stopBlinkAndFlush() {
        blinking = false;
        blinkOriginValid = false;
        releaseHeld(null);
    }

    private static void resetRuntime() {
        kbPending = false;
        reversalPending = false;
        jumpResetCountdown = -1;
        blinking = false;
        blinkOriginValid = false;
        motionTracked = false;
        slapTicksLeft = -1;
        slapTicksTotal = 0;
        slapExpireTick = -1;
        slapStartTick = -1;
        slapAimDirty = false;
        kbArriveTick = -1;
        sprintAtLastTickEnd = false;
        sprintSynced = -1;
        sprintSynced = -1;
        noxzSuspending = false;
        noxzSuspendTicks = 0;
        noxzTarget = null;
        noxzAttacksLeft = 0;
        noxzFlagCooldown = 0;
        noxzFlushPending = false;
        synchronized (noxzInbound) {
            noxzInbound.clear();
        }
        synchronized (noxzOutbound) {
            noxzOutbound.clear();
        }
    }

    private static int effectiveMode() {
        int current = clamp(mode, MODE_MODIFY, MODE_LAST);
        guardOverride = -1;
        if (grimGuardActive()) {
            int safe = grimSafeMode(current);
            if (safe != current) {
                guardOverride = safe;
                current = safe;
            }
        }
        if (current == MODE_NOXZ && !noxzTx) {
            // 见 noxzTx 的注释：扣 transaction 在本服必吃 TransactionOrder，
            // 且放包时补的是全额击退。没有显式打开 noxzTx 就按 SPRINT SLAP 跑。
            return MODE_SPRINT_SLAP;
        }
        if (current == MODE_UNCERTAINTY && isLegitProfile()) {
            return MODE_COUNTER;
        }
        if (current == MODE_BLINK && isLegitProfile()) {
            // LEGIT 档不憋包：位置包断档在 Grim/Martix 的 Post 检查里非常显眼。
            return MODE_JUMP_RESET;
        }
        return current;
    }

    /** 守护是否对当前预设生效：CUSTOM / GRIM 套守护，其余预设尊重用户选择。 */
    private static boolean grimGuardActive() {
        if (!grimGuard) {
            return false;
        }
        return server == SERVER_CUSTOM || server == SERVER_GRIM;
    }

    /**
     * 把一个"会归零速度"的模式换成 Grim 能过的形态。
     *
     * <p>判据只有一条：这个模式会不会让某一 tick 的位移比服务端预测少。
     * 会 → 换成 {@code SPRINT SLAP}（S12 原样放行，靠 sprint-attack 的 ×0.6
     * 削击退；这条 0.6 在 Grim 的 {@code addAttackSlowToPossibilities} 里有对应
     * 分支，所以既不欠位移也不吃 AntiKB）。
     */
    private static int grimSafeMode(int current) {
        switch (current) {
            case MODE_REVERSAL:
                // 先归零再延迟反向：归零那段全是 offset，而且一路欠到底。
                return MODE_SPRINT_SLAP;
            case MODE_UNCERTAINTY:
                // farm 撑不开任何速度容差（UncertaintyHandler 里没有放置相关项），
                // 归零 = 每 tick 一条 AntiKB，憋包再送 Timer/GroundSpoof/BadPacketsN。
                return MODE_SPRINT_SLAP;
            case MODE_BLINK:
                // BLINK 憋包期间受力 100%（否则就不是「憋包」而是「瞬移」），
                // 也就是说它**一点击退都不削**，只是把位置包推迟；在 Grim 上还要
                // 额外吃 Timer / Simulation / BadPacketsN。守护开着时不允许它顶着
                // 一个"防击退"模块的名号空转。
                return MODE_SPRINT_SLAP;
            case MODE_MODIFY:
                // 保留比例太低等价于归零；45/85 那种保留够多的不拦。
                if (horizontal < 0.30F || vertical < 0.30F) {
                    return MODE_SPRINT_SLAP;
                }
                return current;
            default:
                return current;
        }
    }

    private static double hRatio() {
        double value = clamp(horizontal, 0.0F, 1.0F);
        if (grimGuardActive()) {
            value = Math.max(value, 0.45D);
        }
        return isLegitProfile() ? Math.max(value, 0.45D) : value;
    }

    /**
     * blink 窗口内保留的击退比例。LEGIT 档抬到 85% 以上：Grim 的运动预测
     * 里缺一段位移就是一段 offset，少削一点比削干净更安全。
     */
    private static double blinkKeepRatio() {
        double value = clamp(blinkKeep, 0.0F, 1.0F);
        return isLegitProfile() ? Math.max(value, 0.85D) : value;
    }

    /**
     * 垂直保留比例。
     *
     * <p>守护打开时抬到 ≥ 35%：垂直分量被削到接近 0 会让玩家离地 2~3 tick，
     * 服务端立刻拿到 {@code GroundSpoof: claimed false}。垂直击退对防御没有
     * 任何价值（它只决定你被打后的滞空高度），所以这里宁可多留。
     */
    private static double vRatio() {
        double value = clamp(vertical, 0.0F, 1.0F);
        if (grimGuardActive()) {
            value = Math.max(value, 0.35D);
        }
        return isLegitProfile() ? Math.max(value, 0.65D) : value;
    }

    private static boolean isLegitProfile() {
        return OpenMaverFeatures.isBypassEnabled()
                && OpenMaverFeatures.getBypassProfile() == OpenMaverFeatures.PROFILE_LEGIT;
    }

    // ==================================================================
    // 配置读写（与 ScaffoldFeature 一样由 OpenMaverFeatures 统一落盘）
    // ==================================================================

    public static void load(Properties values) {
        stopBlinkAndFlush();
        mode = readInt(values, "velocity.mode", MODE_MODIFY, MODE_LAST);
        server = readInt(values, "velocity.server", SERVER_CUSTOM, SERVER_INTAVE);
        horizontal = clamp(readFloat(values, "velocity.horizontal", 0.0F), 0.0F, 1.0F);
        vertical = clamp(readFloat(values, "velocity.vertical", 0.0F), 0.0F, 1.0F);
        reversalDelayMs = readInt(values, "velocity.reversalDelay", 120, 600);
        reversalStrength = clamp(readFloat(values, "velocity.reversalStrength", 1.0F), 0.2F, 1.5F);
        strafeFactor = clamp(readFloat(values, "velocity.strafeFactor", 1.0F), 0.2F, 1.6F);
        jumpDelayTicks = readInt(values, "velocity.jumpDelay", 0, 3);
        blinkTicks = readInt(values, "velocity.blinkTicks", 5, 20);
        burstAttacks = readInt(values, "velocity.burstAttacks", 5, 8);
        burstRange = clamp(readFloat(values, "velocity.burstRange", 3.0F), 2.0F, 6.0F);
        blinkFlush = readBoolean(values, "velocity.blinkFlush", true);
        blinkKeep = clamp(readFloat(values, "velocity.blinkKeep", 1.0F), 0.0F, 1.0F);
        burstRotate = readBoolean(values, "velocity.burstRotate", true);
        blinkRewind = readBoolean(values, "velocity.blinkRewind", false);
        grimGuard = readBoolean(values, "velocity.grimGuard", true);
        uncertaintyTicks = readInt(values, "velocity.uncertaintyTicks", 2, 3);
        uncertaintyFarm = readBoolean(values, "velocity.uncertaintyFarm", true);
        slapMaxTicks = readInt(values, "velocity.slapMaxTicks", 6, 10);
        slapAim = readBoolean(values, "velocity.slapAim", true);
        slapSprintSync = readBoolean(values, "velocity.slapSprintSync", true);
        noxzAttackAmount = readInt(values, "velocity.noxzAttacks", 3, 8);
        noxzTx = readBoolean(values, "velocity.noxzTx", false);
        resetRuntime();
        Logger.info(TAG, "配置: mode=" + MODE_NAMES[clamp(mode, MODE_MODIFY, MODE_LAST)]
                + " server=" + SERVER_NAMES[clamp(server, SERVER_CUSTOM, SERVER_INTAVE)]
                + " guard=" + (grimGuard ? "on" : "off")
                + " -> 实际=" + MODE_NAMES[effectiveMode()]
                + "（H=" + Math.round(hRatio() * 100.0D) + "% V=" + Math.round(vRatio() * 100.0D)
                + "%）" + (guardOverride >= 0 ? " [守护改写了归零类模式]" : ""));
    }

    public static String save() {
        return "velocity.mode=" + clamp(mode, MODE_MODIFY, MODE_LAST)
                + "\nvelocity.server=" + clamp(server, SERVER_CUSTOM, SERVER_INTAVE)
                + "\nvelocity.horizontal=" + horizontal
                + "\nvelocity.vertical=" + vertical
                + "\nvelocity.reversalDelay=" + reversalDelayMs
                + "\nvelocity.reversalStrength=" + reversalStrength
                + "\nvelocity.strafeFactor=" + strafeFactor
                + "\nvelocity.jumpDelay=" + jumpDelayTicks
                + "\nvelocity.blinkTicks=" + blinkTicks
                + "\nvelocity.burstAttacks=" + burstAttacks
                + "\nvelocity.burstRange=" + burstRange
                + "\nvelocity.blinkFlush=" + blinkFlush
                + "\nvelocity.blinkKeep=" + blinkKeep
                + "\nvelocity.burstRotate=" + burstRotate
                + "\nvelocity.blinkRewind=" + blinkRewind
                + "\nvelocity.grimGuard=" + grimGuard
                + "\nvelocity.uncertaintyTicks=" + uncertaintyTicks
                + "\nvelocity.uncertaintyFarm=" + uncertaintyFarm
                + "\nvelocity.noxzAttacks=" + clamp(noxzAttackAmount, 1, 8)
                + "\nvelocity.noxzTx=" + noxzTx
                + "\nvelocity.slapAim=" + slapAim
                + "\nvelocity.slapSprintSync=" + slapSprintSync
                + "\nvelocity.slapMaxTicks=" + clamp(slapMaxTicks, 1, 10)
                + "\n";
    }

    // ------------------------------------------------------------------
    // ClickGUI / HUD 读写接口
    // ------------------------------------------------------------------

    public static int getMode() {
        return mode;
    }

    public static void setMode(int value) {
        int next = clamp(value, MODE_MODIFY, MODE_LAST);
        if (next != mode) {
            mode = next;
            // 切模式时不能把已经憋住的位置包留在队列里：先补发再复位状态。
            stopBlinkAndFlush();
            resetRuntime();
        }
        OpenMaverFeatures.markDirty();
    }

    public static String getModeName() {
        return MODE_NAMES[clamp(mode, MODE_MODIFY, MODE_LAST)];
    }

    public static int getServer() {
        return server;
    }

    public static void setServer(int value) {
        server = clamp(value, SERVER_CUSTOM, SERVER_INTAVE);
        applyPreset();
        resetRuntime();
        OpenMaverFeatures.markDirty();
    }

    public static String getServerName() {
        return SERVER_NAMES[clamp(server, SERVER_CUSTOM, SERVER_INTAVE)];
    }

    /**
     * 服务器预设：把模式与参数配成一组针对该服务端反作弊观感的组合。
     *
     * <p>预设只是「一次写入」，之后用户在 ClickGUI 里的微调不会被覆盖。
     */
    private static void applyPreset() {
        switch (server) {
            case SERVER_VANILLA:
                // 原版 1.8：击退完全由 S12 决定，直接抹平即可。
                mode = MODE_MODIFY;
                horizontal = 0.0F;
                vertical = 0.0F;
                reversalDelayMs = 0;
                strafeFactor = 1.0F;
                jumpDelayTicks = 0;
                blinkTicks = 5;
                burstAttacks = 5;
                break;
            case SERVER_HYPIXEL:
                // Hypixel 的击退是自研结算（含 “Velocity” 检测）：
                // 保留少量水平、吃掉垂直，并让起跳延后 1 tick 更接近人手。
                mode = MODE_MODIFY;
                horizontal = 0.12F;
                vertical = 0.0F;
                reversalDelayMs = 80;
                strafeFactor = 1.0F;
                jumpDelayTicks = 1;
                blinkTicks = 5;
                burstAttacks = 5;
                break;
            case SERVER_GRIM:
                // Grim 的 AntiKB 是「两个 transaction 把 S12 夹住」的 sandwich：
                // 它靠 transaction 回执判断客户端**什么时候**吃到击退。
                // 所以正确的切入口不是运动本身，而是**回执的时机**：
                //   - 空中挨打时吞掉 S12 + 冻结 C03 + 扣住 S32（回执不发，
                //     Grim 只能把这段击退当成「可能还没到」，预测里同时允许
                //     「吃过」和「没吃」两条分支）；
                //   - 落地那一刻：先交位置包（不含击退的轨迹，落在允许分支上），
                //     再补上击退，然后连打几次（每次 ×0.6）；
                //   - 下一 tick 才回执，Grim 这时把击退算进自己的 velocity，
                //     客户端也是这一刻才吃 —— 两边重新对齐。
                // 即 OpenZen 的 AntiKB/NoXZ。SPRINT SLAP 保留为备选（不打洞，
                // 但只在有目标时生效）。
                // 2026-11-16 18:38 实机：这个 Grim 带 TransactionOrder 检查，
                // 扣住 S32 两 tick 就 `TransactionOrder (vl:0.0): skipped=2`，
                // NoXZ 的「回执不回」前提在这个构建上直接失效。预设改回
                // SPRINT SLAP（每次真·冲刺攻击 ×0.6），NoXZ 仍可在 MODE 里
                // 手动选来做对测。
                mode = MODE_SPRINT_SLAP;
                noxzAttackAmount = 3;
                horizontal = 1.0F;
                vertical = 1.0F;
                reversalDelayMs = 0;
                reversalStrength = 1.0F;
                strafeFactor = 1.0F;
                jumpDelayTicks = 0;
                slapMaxTicks = 6;
                uncertaintyTicks = 2;
                uncertaintyFarm = true;
                blinkTicks = 5;
                burstAttacks = 5;
                blinkKeep = 1.0F;
                burstRotate = false;
                blinkFlush = true;
                blinkRewind = false;
                break;
            case SERVER_INTAVE:
                // Intave 的击退判定有时间窗：延迟反向比直接抹掉更贴合，
                // 垂直保留一部分避免 “Invalid vertical motion”。
                // 注意：这套参数在 Grim 上会被 AntiKB 立刻抓到（水平归零 =
                // 每 tick 都欠它一段位移），对 Grim 请用上面的 GRIM 预设。
                mode = MODE_REVERSAL;
                horizontal = 0.0F;
                vertical = 0.35F;
                reversalDelayMs = 100;
                reversalStrength = 1.0F;
                strafeFactor = 1.0F;
                jumpDelayTicks = 0;
                blinkTicks = 4;
                burstAttacks = 4;
                break;
            case SERVER_CUSTOM:
            default:
                break;
        }
    }

    public static float getHorizontal() {
        return horizontal;
    }

    public static void setHorizontal(float value) {
        horizontal = clamp(value, 0.0F, 1.0F);
        OpenMaverFeatures.markDirty();
    }

    public static float getVertical() {
        return vertical;
    }

    public static void setVertical(float value) {
        vertical = clamp(value, 0.0F, 1.0F);
        OpenMaverFeatures.markDirty();
    }

    public static int getReversalDelay() {
        return reversalDelayMs;
    }

    public static void setReversalDelay(int value) {
        reversalDelayMs = clamp(value, 0, 600);
        OpenMaverFeatures.markDirty();
    }

    public static float getReversalStrength() {
        return reversalStrength;
    }

    public static void setReversalStrength(float value) {
        reversalStrength = clamp(value, 0.2F, 1.5F);
        OpenMaverFeatures.markDirty();
    }

    public static float getStrafeFactor() {
        return strafeFactor;
    }

    public static void setStrafeFactor(float value) {
        strafeFactor = clamp(value, 0.2F, 1.6F);
        OpenMaverFeatures.markDirty();
    }

    public static int getJumpDelay() {
        return jumpDelayTicks;
    }

    public static void setJumpDelay(int value) {
        jumpDelayTicks = clamp(value, 0, 3);
        OpenMaverFeatures.markDirty();
    }

    public static int getBlinkTicks() {
        return blinkTicks;
    }

    public static void setBlinkTicks(int value) {
        blinkTicks = clamp(value, 1, 20);
        OpenMaverFeatures.markDirty();
    }

    public static int getBurstAttacks() {
        return burstAttacks;
    }

    public static void setBurstAttacks(int value) {
        burstAttacks = clamp(value, 1, 8);
        OpenMaverFeatures.markDirty();
    }

    public static float getBurstRange() {
        return burstRange;
    }

    public static void setBurstRange(float value) {
        burstRange = clamp(value, 2.0F, 6.0F);
        OpenMaverFeatures.markDirty();
    }

    public static boolean isBlinkFlush() {
        return blinkFlush;
    }

    public static void setBlinkFlush(boolean value) {
        blinkFlush = value;
        OpenMaverFeatures.markDirty();
    }

    public static float getBlinkKeep() {
        return blinkKeep;
    }

    public static void setBlinkKeep(float value) {
        blinkKeep = clamp(value, 0.0F, 1.0F);
        OpenMaverFeatures.markDirty();
    }

    public static boolean isBurstRotate() {
        return burstRotate;
    }

    public static void setBurstRotate(boolean value) {
        burstRotate = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isBlinkRewind() {
        return blinkRewind;
    }

    public static void setBlinkRewind(boolean value) {
        blinkRewind = value;
        OpenMaverFeatures.markDirty();
    }

    public static int getUncertaintyTicks() {
        return uncertaintyTicks;
    }

    public static void setUncertaintyTicks(int value) {
        uncertaintyTicks = clamp(value, 1, 3);
        OpenMaverFeatures.markDirty();
    }

    public static boolean isUncertaintyFarm() {
        return uncertaintyFarm;
    }

    public static void setUncertaintyFarm(boolean value) {
        uncertaintyFarm = value;
        OpenMaverFeatures.markDirty();
    }

    /** SPRINT SLAP：一个击退窗口最多重复几个 tick（每 tick 一次 ×0.6）。 */
    public static int getSlapMaxTicks() {
        return slapMaxTicks;
    }

    /** NOXZ：一次窗口里打几次（每次一次 ×0.6）。 */
    public static int getNoXzAttacks() {
        return noxzAttackAmount;
    }

    public static void setNoXzAttacks(int value) {
        noxzAttackAmount = clamp(value, 1, 8);
        OpenMaverFeatures.markDirty();
    }

    /** NOXZ：已完成的挂起窗口数（诊断）。 */
    public static long getNoXzSuspends() {
        return NOXZ_SUSPENDS.get();
    }

    /** NOXZ：已打出的攻击次数（诊断）。 */
    public static long getNoXzAttacks2() {
        return NOXZ_ATTACKS.get();
    }

    /** NOXZ：当前是否处于挂起（HUD 状态点）。 */
    public static boolean isNoXzSuspending() {
        return noxzSuspending;
    }

    /** SprintSlap：准星没打到人时是否自己转过去再打。 */
    public static boolean isSlapAim() {
        return slapAim;
    }

    public static void setSlapAim(boolean value) {
        slapAim = value;
        OpenMaverFeatures.markDirty();
    }

    public static void setSlapMaxTicks(int value) {
        slapMaxTicks = clamp(value, 1, 10);
        OpenMaverFeatures.markDirty();
    }

    /**
     * SprintSlap：是否用 {@code C0BPacketEntityAction} 自己同步冲刺状态。
     *
     * <p>关掉就是 18:53 那局的行为（只改客户端状态、不发包），保留下来只为对测：
     * 那局的 {@code Simulation: .006153 x83} 就是这条包没发造成的。
     */
    public static boolean isSlapSprintSync() {
        return slapSprintSync;
    }

    public static void setSlapSprintSync(boolean value) {
        slapSprintSync = value;
        OpenMaverFeatures.markDirty();
    }

    /** 当前击退窗口最早从哪一 tick 出手（HUD 诊断）。 */
    public static int getSlapStartTick() {
        return slapStartTick;
    }

    /** 收到击退的 tick（-1 = 还没有）。 */
    public static int getKbArriveTick() {
        return kbArriveTick;
    }

    /** SPRINT SLAP：窗口内已经打出去的攻击次数（诊断：0 = 一次都没削到）。 */
    public static long getSlapHits() {
        return SLAP_HITS.get();
    }

    /** SPRINT SLAP：已完成窗口数。 */
    public static long getSlapWindows() {
        return SLAP_WINDOWS.get();
    }

    /** SPRINT SLAP：准星里没目标导致的空转次数。 */
    public static long getSlapMisses() {
        return SLAP_MISS.get();
    }

    /** 最近一次没有出手的原因（HUD / 日志诊断）。 */
    public static String getSlapSkip() {
        return slapSkip;
    }

    /** 当前是否有未完成的 slap 窗口（HUD 状态点）。 */
    public static boolean isSlapping() {
        return slapTicksLeft > 0;
    }

    /** uncertainty farm 成功次数（诊断用）。 */
    public static long getFarmOk() {
        return FARM_OK.get();
    }

    /** uncertainty farm 失败次数（空手 / 类名对不上）。 */
    public static long getFarmFail() {
        return FARM_FAIL.get();
    }

    public static boolean isGrimGuard() {
        return grimGuard;
    }

    public static void setGrimGuard(boolean value) {
        grimGuard = value;
        OpenMaverFeatures.markDirty();
    }

    /** 已爆发的攻击包总数（诊断用：0 说明憋包了但没打出去）。 */
    public static long getBurstSent() {
        return BURST_SENT.get();
    }

    /** LEGIT 档下 blink 会被降级成 JumpReset，GUI / HUD 用它提示。 */
    public static boolean isBlinkSuppressedByProfile() {
        return clamp(mode, MODE_MODIFY, MODE_LAST) == MODE_BLINK && isLegitProfile();
    }

    /** 管线是否已经挂上（HUD 用来区分 PACKET / SNIFF 两种数据源）。 */
    public static boolean isPacketHookInstalled() {
        return hookInstalled;
    }

    /** 当前是否正在憋包（HUD 状态点）。 */
    public static boolean isBlinking() {
        return blinking;
    }

    /** 憋包里缓存了多少个位置包。 */
    public static int getHeldCount() {
        synchronized (heldPackets) {
            return heldPackets.size();
        }
    }

    /** 已收到的本方击退包数量（诊断用：为 0 说明服务端没发 S12 或 hook 没生效）。 */
    public static long getKnockbackSeen() {
        return KB_SEEN.get();
    }

    /** 已按模式处理过的击退次数。 */
    public static long getKnockbackApplied() {
        return KB_APPLIED.get();
    }

    /** 已完成的 blink 次数。 */
    public static long getBlinkCycles() {
        return BLINK_CYCLES.get();
    }

    private static String round3(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** ClickGUI / HUD 用的一行摘要。 */
    public static String getSummary() {
        String modeName = MODE_NAMES[effectiveMode()];
        if (effectiveMode() == MODE_NOXZ) {
            return modeName + " x" + clamp(noxzAttackAmount, 1, 8)
                    + (isNoXzSuspending() ? " SUSPEND " + noxzSuspendTicks + "t" : "")
                    + " · " + NOXZ_ATTACKS.get() + "H"
                    + (guardOverride >= 0 ? " GUARD" : "");
        }
        if (effectiveMode() == MODE_SPRINT_SLAP) {
            // 窗口开着时把「上一 tick 为什么没出手」也带上：这个模式不写 motion，
            // 唯一会失效的原因就是打不出去（没目标 / 被 Scaffold 占视角），
            // 所以这一句就是现场诊断。
            return modeName + " " + clamp(slapMaxTicks, 1, 10) + "t"
                    + (slapAim ? " AIM" : "")
                    + (isSlapping() ? " " + slapTicksLeft + "p·" + slapSkip : "")
                    + " · " + SLAP_HITS.get() + "H"
                    + (guardOverride >= 0 ? " GUARD" : "");
        }
        if (effectiveMode() == MODE_UNCERTAINTY) {
            return modeName + " " + activeBlinkTicks() + "t x" + clamp(burstAttacks, 1, 8)
                    + (uncertaintyFarm ? " FARM" : "")
                    + (guardOverride >= 0 ? " GUARD" : "");
        }
        if (effectiveMode() == MODE_COUNTER) {
            return modeName + " x" + clamp(burstAttacks, 1, 8);
        }
        if (effectiveMode() == MODE_BLINK) {
            return modeName + " " + clamp(blinkTicks, 1, 20) + "t x"
                    + clamp(burstAttacks, 1, 8)
                    + " K" + Math.round(clamp(blinkKeep, 0.0F, 1.0F) * 100.0F) + "%"
                    + (guardOverride >= 0 ? " GUARD" : "");
        }
        if (effectiveMode() == MODE_REVERSAL) {
            return modeName + " " + clamp(reversalDelayMs, 0, 600) + "ms";
        }
        if (effectiveMode() == MODE_JUMP_RESET) {
            return modeName + " " + clamp(jumpDelayTicks, 0, 3) + "t";
        }
        if (effectiveMode() == MODE_STRAFE) {
            return modeName + " " + Math.round(clamp(strafeFactor, 0.2F, 1.6F) * 100.0F) + "%";
        }
        return modeName + " " + Math.round(clamp(horizontal, 0.0F, 1.0F) * 100.0F)
                + "/" + Math.round(clamp(vertical, 0.0F, 1.0F) * 100.0F) + "%";
    }

    // ==================================================================
    // 小型工具（与 ScaffoldFeature 保持一致）
    // ==================================================================

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int readInt(Properties values, String key, int fallback, int max) {
        return clamp(readInt(values, key, fallback), 0, max);
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
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
            return false;
        }
        return fallback;
    }
}
