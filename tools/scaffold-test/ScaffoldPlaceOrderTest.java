import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Scaffold 放置时序（Grim {@code RotationPlace}）的回归测试，不需要启动 Minecraft。
 *
 * <p>要锁住的不变量只有一条：
 *
 * <blockquote>C08 之后的那条飞包，必须携带与这条 C08 相同的角度。</blockquote>
 *
 * <p>这条不变量来自 Grim 1.8 的真实时序（{@code CheckManagerListener}）：
 *
 * <ol>
 *   <li>C08 到达时先进 {@code placeUseItemPackets} 队列，并立即跑一次
 *       {@code onBlockPlace}（pre 分支，只有 {@code flagBuffer > 0} 时才记账）；</li>
 *   <li><b>下一条飞包</b>到达时 {@code handleQueuedPlaces} 才派发
 *       {@code onPostFlyingBlockPlace}；1.8 走的分支无条件把
 *       {@code player.yaw/pitch} 换成<b>这条飞包</b>的 rotation；</li>
 *   <li>位置被设成 {@code lastClaimedPosition}（C08 之前那条飞包的位置，
 *       也就是发包时的当前位置）。</li>
 * </ol>
 *
 * <p>也就是说：判定 C08 用的是「当前眼睛 + 后一条飞包的 rotation」，而
 * {@code didRayTraceHit} 的 pitch 只有这一个来源（1.8 没有 lastPitch 回退）。
 * 所以「先把 aim 发出去、下一 tick 再放」只对了一半 —— aim 必须跟着<b>同一条
 * C08</b> 写进本 tick 的飞包（{@code ScaffoldFeature.holdPlaceAim}）。
 *
 * <p>测试分三层（都不需要游戏）：
 *
 * <ol>
 *   <li><b>决策表</b>：反射调用 {@code ScaffoldFeature.pendingAction}，
 *       断言只有 {@code sent && rayHit} 才可能 PLACE；</li>
 *   <li><b>时序模拟</b>：按上面的包序规则驱动 pending 生命周期，断言
 *       「C08 之后紧跟同角度飞包」「一 tick 至多一个 C08」「放着的那一 tick
 *       不再规划新目标」「重试有上限」；</li>
 *   <li><b>结构断言</b>：从源码里切出 {@code flushPendingPlace} / {@code tick()} /
 *       {@code place()}，断言 hold 链路没有被绕过。</li>
 * </ol>
 */
public final class ScaffoldPlaceOrderTest {

    private static int passed;
    private static int failed;

    private static Class<?> feature;
    private static Method pendingAction;
    private static Class<?> actionType;

    public static void main(String[] args) throws Exception {
        feature = Class.forName("ScaffoldFeature");
        pendingAction = feature.getDeclaredMethod("pendingAction",
                boolean.class, boolean.class, int.class);
        pendingAction.setAccessible(true);
        actionType = Class.forName("ScaffoldFeature$PendingAction");

        int maxRetries = readMaxRetries();

        decisionTable(maxRetries);
        pendingLifecycle(maxRetries);
        structure();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1. 决策表
    // ------------------------------------------------------------------

    private static void decisionTable(int maxRetries) throws Exception {
        System.out.println("[1/3] pending 决策表");
        // 角度没进过 C03：永远不许 PLACE，最多重新发 aim。
        for (int retries = 0; retries <= maxRetries + 1; retries++) {
            String action = action(false, false, retries).toString();
            boolean allowed = retries >= maxRetries
                    ? "DROP".equals(action)
                    : "RESEND_AIM".equals(action);
            check("unsent aim, retries=" + retries + " -> " + action,
                    allowed && !"PLACE".equals(action));
        }
        // aim 已发出、ray trace 对上：这才允许 PLACE。
        check("sent + rayHit -> PLACE", "PLACE".equals(action(true, true, 0).toString()));
        check("sent + rayHit after retries -> PLACE",
                "PLACE".equals(action(true, true, maxRetries).toString()));
        // 打不到原面：重算角度进 C03，绝不立即放。
        for (int retries = 0; retries < maxRetries; retries++) {
            String value = action(true, false, retries).toString();
            check("sent + miss, retries=" + retries + " -> REQUEUE", "REQUEUE".equals(value));
        }
        check("sent + miss at the retry cap -> DROP",
                "DROP".equals(action(true, false, maxRetries).toString()));
        check("retry cap is small enough to not pin the module (<= 4)", maxRetries <= 4);
    }

    // ------------------------------------------------------------------
    // 2. 时序模拟
    // ------------------------------------------------------------------

    /**
     * 最小 pending 生命周期模拟。
     *
     * <p>建模的是**包序**，不是几何：
     * <ul>
     *   <li>flush 时 pending 已 sent 且 ray 命中 → 发 C08（角度 = 校验过的角度），
     *       并把本 tick 的飞包锁成同一个角度（hold）→ 本 tick 不再规划新目标；</li>
     *   <li>flush 命中失败 → REQUEUE（新角度进本 tick 的飞包，tick +1）或到顶 DROP；</li>
     *   <li>没有 hold 的 tick 才规划新目标：aim 进本 tick 的飞包。</li>
     * </ul>
     */
    private static void pendingLifecycle(int maxRetries) throws Exception {
        System.out.println("[2/3] C08 之后必须紧跟同角度飞包");

        // --- 2a. 正常路径 ---
        Sim sim = new Sim(true, 0);
        sim.queue(12.5F, 40.0F);
        sim.tick();          // 规划段：aim 进飞包
        sim.tick();          // flush 命中 -> C08 + 同 tick 飞包（hold）
        check("happy path emits exactly one C08", sim.c08 == 1);
        check("happy path sends the C08 angle in the SAME tick's flying packet",
                sim.everyC08HasSameTickPacket());
        check("happy path does not plan a new target on the placing tick",
                sim.tickOfC08.size() == 1 && !sim.plannedAfterC08);
        check("happy path orders C08 before the flying packet",
                sim.lastC08BeforeSameTickPacket());

        // --- 2b. 上一 tick 的角度打不到原面：重算角度，下一 tick 再放 ---
        sim = new Sim(true, 1);
        sim.queue(12.5F, 40.0F);
        sim.tick();          // 规划段：aim 进飞包
        sim.tick();          // flush 失败 -> REQUEUE，新角度进本 tick 飞包
        check("requeue does not place on the same tick", sim.c08 == 0);
        sim.tick();          // flush 命中 -> C08 + hold
        check("requeued placement still emits one C08", sim.c08 == 1);
        check("requeued C08 uses its own tick's packet", sim.everyC08HasSameTickPacket());
        check("no C08 ever used an angle the following packet did not carry",
                sim.noMismatchedC08());

        // --- 2c. 一直打不到：重试到上限后丢弃，不会无限排队 ---
        sim = new Sim(false, Integer.MAX_VALUE);
        sim.queue(0.0F, 90.0F);
        for (int i = 0; i < 20; i++) {
            sim.tick();
        }
        check("hopeless target is dropped, not retried forever", sim.c08 == 0);
        check("retry budget is bounded (" + sim.requeues + " requeues <= " + maxRetries + ")",
                sim.requeues <= maxRetries);
        check("dropped target stops sending flying packets for that aim", !sim.pending);

        // --- 2d. 飞包没真正发出去（输入 hook 没跑）：不许放置 ---
        sim = new Sim(true, 0);
        sim.queue(45.0F, 30.0F);
        sim.dropPacket = true;
        sim.tick();
        sim.tick();
        check("aim that never reached a flying packet is never placed", sim.c08 == 0);

        // --- 2e. 每 tick 至多一个 C08（MultiPlace） ---
        sim = new Sim(true, 0);
        sim.queue(0.0F, 80.0F);
        sim.tick();
        sim.placeTwiceSameTick = true;
        sim.tick();
        check("at most one C08 per tick", sim.maxC08PerTick <= 1);
    }

    /**
     * 包序模拟器：只记录「哪个角度在哪一 tick 的哪个包里」。
     *
     * <p>关键规则（与 Grim 1.8 一致）：C08 的角度由**同一 tick 的飞包**判定，
     * 所以放置成功的那一 tick 必须把飞包锁成同一个角度；下一 tick 才规划新目标。
     */
    private static final class Sim {
        private final boolean rayHits;
        private int missesLeft;
        private int retries;
        private boolean sent;
        private boolean pending;
        private boolean holding;
        private float aimYaw;
        private float aimPitch;
        private float packetYaw;
        private float packetPitch;
        private boolean dropPacket;
        private boolean placeTwiceSameTick;
        private int tick;
        private int c03;
        private int c08;
        private int requeues;
        private int maxC08PerTick;
        private int c08ThisTick;
        private boolean plannedAfterC08;
        private final List<String> log = new ArrayList<String>();
        private final List<Integer> tickOfC08 = new ArrayList<Integer>();
        private float lastC08Yaw;
        private float lastC08Pitch;
        private int lastC08Tick = -1;
        private boolean lastC08HadSameTickPacket;

        private Sim(boolean rayHits, int missesLeft) {
            this.rayHits = rayHits;
            this.missesLeft = missesLeft;
        }

        private void queue(float yaw, float pitch) {
            aimYaw = yaw;
            aimPitch = pitch;
            sent = false;
            retries = 0;
            pending = true;
        }

        private void tick() throws Exception {
            tick++;
            c08ThisTick = 0;
            lastC08HadSameTickPacket = false;

            if (pending && !holding) {
                boolean rayHit = sent && simRayHit();
                String action = action(sent, rayHit, retries).toString();
                if ("PLACE".equals(action)) {
                    float yaw = packetYaw + 0.005F;   // flush 里那个 ±0.005° 的新角度
                    float pitch = packetPitch + 0.005F;
                    emitC08(yaw, pitch);
                    if (placeTwiceSameTick) {
                        emitC08(yaw + 1.0F, pitch);   // 生产代码里被 placedThisTick() 挡掉
                    }
                    pending = false;
                    holding = true;
                    packetYaw = yaw;                  // ← hold：本 tick 飞包必须同角度
                    packetPitch = pitch;
                } else if ("REQUEUE".equals(action)) {
                    retries++;
                    requeues++;
                    sent = false;
                    aimPitch = aimPitch + 1.0F;
                } else if ("DROP".equals(action)) {
                    pending = false;
                }
            } else if (!pending && !holding && aimYaw != 0.0F && aimPitch != 0.0F) {
                // 没有待放置目标：什么也不做
            }

            // 本 tick 的飞包
            if (!dropPacket && (pending || holding)) {
                if (!holding) {
                    packetYaw = aimYaw;
                    packetPitch = aimPitch;
                }
                sent = pending && !holding;
                c03++;
                log.add(tick + " PACKET " + packetYaw + "/" + packetPitch);
                if (lastC08Tick == tick) {
                    lastC08HadSameTickPacket = packetYaw == lastC08Yaw
                            && packetPitch == lastC08Pitch;
                }
            }

            if (holding) {
                holding = false;   // 下一 tick 才能规划新目标
            } else if (pending && !sent && aimYaw == 0.0F) {
                // 规划段：aim 进本 tick 的飞包（sent 已由上面置位）
            }
            if (c08ThisTick > maxC08PerTick) {
                maxC08PerTick = c08ThisTick;
            }
        }

        private boolean simRayHit() {
            if (missesLeft == Integer.MAX_VALUE) {
                return false;
            }
            if (missesLeft > 0) {
                missesLeft--;
                return false;
            }
            return rayHits;
        }

        private void emitC08(float yaw, float pitch) {
            if (c08ThisTick >= 1) {
                return;   // placedThisTick()
            }
            c08++;
            c08ThisTick++;
            lastC08Yaw = yaw;
            lastC08Pitch = pitch;
            lastC08Tick = tick;
            tickOfC08.add(tick);
            log.add(tick + " C08 " + yaw + "/" + pitch);
        }

        /** 每条 C08 之后都必须有一条同 tick、同角度的飞包。 */
        private boolean everyC08HasSameTickPacket() {
            for (int i = 0; i < log.size(); i++) {
                String entry = log.get(i);
                if (!entry.contains(" C08 ")) {
                    continue;
                }
                String tickId = entry.substring(0, entry.indexOf(' '));
                String angle = entry.substring(entry.indexOf(" C08 ") + 5);
                String wanted = tickId + " PACKET " + angle;
                boolean found = false;
                for (int j = i + 1; j < log.size(); j++) {
                    if (log.get(j).equals(wanted)) {
                        found = true;
                        break;
                    }
                    if (!log.get(j).startsWith(tickId + " ")) {
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }

        /** C08 之前/之后的飞包都不能与它角度不符（没有任何包承载它的角度即失败）。 */
        private boolean noMismatchedC08() {
            return everyC08HasSameTickPacket();
        }

        private boolean lastC08BeforeSameTickPacket() {
            if (log.isEmpty()) {
                return false;
            }
            int c08Index = -1;
            for (int i = 0; i < log.size(); i++) {
                if (log.get(i).contains(" C08 ")) {
                    c08Index = i;
                }
            }
            return c08Index >= 0 && c08Index + 1 < log.size()
                    && log.get(c08Index + 1).startsWith(log.get(c08Index)
                            .substring(0, log.get(c08Index).indexOf(' ')) + " PACKET");
        }
    }

    // ------------------------------------------------------------------
    // 3. 结构断言（防止修复被"改回去"）
    // ------------------------------------------------------------------

    private static void structure() throws IOException {
        System.out.println("[3/3] flushPendingPlace 结构");
        Path source = Paths.get("mc/ScaffoldFeature.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        String flush = slice(text, "private static void flushPendingPlace(",
                "    /**\n     * flush 时对一个 pending 目标的决策。");
        check("flushPendingPlace is present", flush != null && flush.length() > 200);
        if (flush == null) {
            return;
        }
        check("flushPendingPlace has exactly one place(...) call site",
                count(flush, "place(mc, player, world") == 1);
        check("flushPendingPlace has no 'recompute angle then place' non-strict branch",
                !flush.contains("!grimStrict()"));
        check("flushPendingPlace routes misses through REQUEUE", flush.contains("REQUEUE"));
        check("flushPendingPlace re-aims instead of dropping outright",
                flush.contains("reaimPending"));
        check("flushPendingPlace tracks the angle that actually reached a C03",
                flush.contains("pending.sentYaw"));
        check("flushPendingPlace validates the very angle it will send",
                flush.contains("placeYaw") && flush.contains("placePitch"));
        check("flushPendingPlace asks the decision table first",
                count(flush, "pendingAction(") == 2);
        check("flushPendingPlace refuses to place while Velocity is blinking",
                flush.contains("VelocityFeature.isBlinking()")
                        && flush.contains("pendingPlace = pending;"));
        String policy = slice(text, "static PendingAction pendingAction(",
                "    /** {@link #pendingAction} 的结果。 */");
        check("pendingAction is bounded by MAX_PENDING_RETRIES",
                policy != null && count(policy, "MAX_PENDING_RETRIES") == 2);
        check("pendingAction never PLACEs an angle that missed",
                policy != null && policy.indexOf("PLACE") > policy.indexOf("rayHit"));

        String tick = slice(text, "private static void tick(Minecraft mc)",
                "private static void updateRotation(");
        check("tick() does not place directly from the immediate branch",
                tick != null && !tick.contains("placement.immediate && !grimStrict()"));
        check("tick() queues every placement", tick != null
                && count(tick, "place(mc, player, world") == 0
                && tick.contains("queuePlacement(placement)"));
        check("tick() plans nothing while the placed aim is held",
                tick != null && tick.contains("holdPlaceAim || pendingPlace != null"));
        check("tick() applies the held aim right after flush",
                tick != null && count(tick, "applyHoldAim()") >= 2);
        String hold = slice(text, "private static void applyHoldAim()",
                "/** tower / keepY 的路径");
        check("applyHoldAim writes the held aim verbatim (no noise)",
                hold != null && hold.contains("tickAimYaw = holdPlaceYaw")
                        && hold.contains("tickAimPitch = holdPlacePitch")
                        && !hold.contains("aimMicroNoise"));
        check("tick() freezes targetFacing / keepY while holding",
                tick != null && count(tick, "!holdPlaceAim &&") >= 2);

        String place = slice(text, "private static boolean place(Minecraft mc, Object player",
                "/**\n     * 把本地玩家的 MovementInput");
        check("place() refuses a second C08 in the same tick",
                place != null && place.contains("placedThisTick()"));
        check("place() logs the MultiPlace suppression", place != null
                && place.contains("scaffold.place.multi"));
        check("place() shares one C08 budget with Velocity's farm",
                place != null && place.contains("claimPlacement()"));
        check("place() records the aim the next flying packet must carry",
                place != null && place.contains("holdPlaceAim = true")
                        && place.contains("holdPlaceYaw = aim.yaw"));

        String hook = slice(text, "if (holdPlaceAim) {", "// 连续角写包");
        check("the packet-write hook prefers the held aim over the pending aim",
                hook != null && hook.contains("tickAimYaw = holdPlaceYaw"));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static int readMaxRetries() throws Exception {
        Field field = feature.getDeclaredField("MAX_PENDING_RETRIES");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static Object action(boolean sent, boolean rayHit, int retries) throws Exception {
        return pendingAction.invoke(null, Boolean.valueOf(sent), Boolean.valueOf(rayHit),
                Integer.valueOf(retries));
    }

    private static String slice(String text, String from, String to) {
        int start = text.indexOf(from);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(to, start + from.length());
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end);
    }

    private static int count(String text, String needle) {
        int found = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            found++;
            index += needle.length();
        }
        return found;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
