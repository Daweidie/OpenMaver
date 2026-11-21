import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Velocity 调试输出：把「发出去的包」「预计被打到哪里」「反击退后在哪里」发到聊天框。
 *
 * <p>三类事件：
 * <ul>
 *   <li>{@code OUT ...} 出站包经过 Netty 钩子时的去向（HOLD = 被挂起，SEND = 真正写出）；
 *       移动包额外带 {@code +Nms} —— 距离上一个移动包的真实间隔，Grim 的 Timer 就是看它</li>
 *   <li>{@code KB in ...} 收到自己的 S12 击退：速度向量、1 tick 后位置、预计落点</li>
 *   <li>{@code KB release ...} 反击退释放：实际位置与「如果客户端立刻吃下击退」的
 *       服务端预计位置之间的偏移，也就是 Grim 的 Simulation / AntiKB 会看到的差值</li>
 * </ul>
 *
 * <p>聊天框只报事件与异常间隔（&lt;30ms / &gt;90ms / 被挂起），并每秒给一行移动包统计；
 * 每个包仍然完整写入 {@code logs/openmaver.log}，避免聊天框被 20 包/秒的流水刷爆。
 * 事件可能在 Netty 线程产生，先进入带限流的队列，主线程 {@link #flush(Minecraft)} 每 tick
 * 取出写聊天框；聊天组件用反射构造，避免 MCP → SRG reobf 的构造器映射问题。
 */
final class VelocityDebug {
    /** 聊天框每秒最多输出的行数。 */
    private static final int MAX_LINES_PER_SECOND = 10;
    /** 队列上限，防止断线/卡顿时堆积。 */
    private static final int MAX_QUEUE = 256;
    /** 最近输出行快照上限。 */
    private static final int MAX_HISTORY = 96;
    /** 移动包间隔异常阈值（ms）。 */
    private static final long FAST_MS = 30L;
    private static final long SLOW_MS = 90L;
    /** 每秒最多报几条间隔异常，其余交给 1s 统计行。 */
    private static final int MAX_TIMER_LINES_PER_SECOND = 2;
    /** 聊天前缀。 */
    private static final String PREFIX = "\u00A78[\u00A7bKB\u00A78] \u00A77";

    private static final Object LOCK = new Object();
    private static final Deque<String> pending = new ArrayDeque<String>();
    private static final Deque<String> history = new ArrayDeque<String>();
    private static long windowStart = System.currentTimeMillis();
    private static int windowUsed;
    private static int dropped;
    private static int historyDropped;

    private static volatile boolean enabled;

    /** 移动包间隔统计（每秒重置）。 */
    private static long lastMoveAt;
    private static long statsSecond;
    private static long gapMin = Long.MAX_VALUE, gapMax, gapSum;
    private static int moveCount;
    private static int timerLines;

    /** ChatComponentText(String) 构造函数；null 表示尚未解析。 */
    private static Constructor<?> componentCtor;
    private static boolean componentBroken;

    private VelocityDebug() { }

    static void setEnabled(boolean value) {
        enabled = value;
        synchronized (LOCK) {
            pending.clear();
            history.clear();
            historyDropped = 0;
            windowUsed = 0;
            dropped = 0;
        }
        resetStats();
    }

    static boolean isEnabled() { return enabled; }

    // ------------------------------------------------------------------
    // 事件入口（任意线程）
    // ------------------------------------------------------------------

    /** 出站包：{@code hold} 为 true 表示还在 Session.moves 队列里没发出去。 */
    static void outbound(Object packet, boolean hold, int queued) {
        if (!enabled || packet == null) return;
        boolean move = isMovePacket(packet);
        long now = System.currentTimeMillis();
        long gap = move && lastMoveAt > 0L ? now - lastMoveAt : -1L;
        if (move) {
            lastMoveAt = now;
            account(now, gap);
        }
        String line = describe(packet, move, hold, queued, gap);
        logLine(line);
        if (!move) {
            // 非移动包（C0F/C00/动作包）只写日志；仅在被挂起时提示一次，避免刷屏。
            if (hold) chat(line, false);
            return;
        }
        boolean anomaly = gap >= 0L && (gap < FAST_MS || gap > SLOW_MS);
        if (hold) { chat(line, true); return; }
        if (!anomaly) return;
        synchronized (LOCK) {
            if (timerLines >= MAX_TIMER_LINES_PER_SECOND) return;
            timerLines++;
        }
        chat("TIMER? " + line, false);
    }

    /** 出站队列被一次性回放：这是服务端看到的时间空档之后的那一批包。 */
    static void flushed(int packets) {
        if (!enabled || packets <= 0) return;
        chat("FLUSH " + packets + " 包一次性写出", true);
    }

    /** 自己的 S12 速度包到达：打印预测轨迹，返回跟踪对象供释放时对比。 */
    static Trace knockbackIn(AttackReduceFeature.Packet packet, boolean suspend) {
        return knockbackIn(packet, suspend ? "SUSPEND" : "PASS");
    }

    /** {@code tag} 说明击退去向；NoXZ 为 PASS READY / PASS AIR / PASS NOT SPRINTING / PASS NO TARGET。 */
    static Trace knockbackIn(AttackReduceFeature.Packet packet, String tag) {
        double[] pos = playerPosition();
        if (pos == null) return null;
        double vx = packet.x() / 8000.0D, vy = packet.y() / 8000.0D, vz = packet.z() / 8000.0D;
        Trace trace = new Trace(pos[0], pos[1], pos[2], vx, vy, vz);
        if (!enabled) return trace;
        double[] one = trace.at(1);
        double[] land = trace.at(trace.landingTicks());
        emit("KB in " + tag
                + " v=(" + fmt2(vx) + ", " + fmt2(vy) + ", " + fmt2(vz) + ")"
                + " at=(" + fmt2(pos[0]) + ", " + fmt2(pos[1]) + ", " + fmt2(pos[2]) + ")"
                + " 1t=(" + fmt2(one[0]) + ", " + fmt2(one[1]) + ", " + fmt2(one[2]) + ")"
                + " 落点=(" + fmt2(land[0]) + ", " + fmt2(land[1]) + ", " + fmt2(land[2]) + ")"
                + " +" + trace.landingTicks() + "t timer=" + fmt2(AttackReduceFeature.timerSpeed()), true);
        return trace;
    }

    /** 挂起期间的进度：实际位置 vs 服务端预计位置。 */
    static void suspendTick(Trace trace, int ticks) {
        if (!enabled || trace == null) return;
        double[] pos = playerPosition();
        if (pos == null) return;
        double[] expect = trace.at(ticks);
        emit("SUSPEND t=" + ticks
                + " 实际=(" + fmt2(pos[0]) + ", " + fmt2(pos[1]) + ", " + fmt2(pos[2]) + ")"
                + " 预计=(" + fmt2(expect[0]) + ", " + fmt2(expect[1]) + ", " + fmt2(expect[2]) + ")"
                + " 偏移=" + fmt2(distance(pos, expect)) + "格", false);
    }

    /** 释放：实际位置与服务端预计位置的最终偏移，即 Grim 看到的差值。 */
    static void release(Trace trace, int heldTicks, int queuedPackets) {
        if (!enabled || trace == null) return;
        double[] pos = playerPosition();
        if (pos == null) return;
        double[] expect = trace.at(Math.max(0, heldTicks));
        emit("KB release 挂起=" + heldTicks + "t 队列=" + queuedPackets + "包"
                + " 实际=(" + fmt2(pos[0]) + ", " + fmt2(pos[1]) + ", " + fmt2(pos[2]) + ")"
                + " 服务端预计=(" + fmt2(expect[0]) + ", " + fmt2(expect[1]) + ", " + fmt2(expect[2]) + ")"
                + " 偏移=(" + fmt2(pos[0] - expect[0]) + ", " + fmt2(pos[1] - expect[1])
                + ", " + fmt2(pos[2] - expect[2]) + ") = " + fmt2(distance(pos, expect)) + "格"
                + " timer=" + fmt2(AttackReduceFeature.timerSpeed()), true);
    }

    // ------------------------------------------------------------------
    // 主线程：写入聊天框
    // ------------------------------------------------------------------

    static void flush(Minecraft mc) {
        if (!enabled) {
            synchronized (LOCK) { pending.clear(); }
            resetStats();
            return;
        }
        List<String> lines = drain();
        if (!lines.isEmpty()) {
            Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
            for (int i = 0; i < lines.size(); i++) print(player, lines.get(i));
        }
        chatStats();
    }

    /** 每秒一行移动包间隔统计，替代逐包刷屏。 */
    private static void chatStats() {
        long second = System.currentTimeMillis() / 1000L;
        String summary;
        synchronized (LOCK) {
            if (second == statsSecond || moveCount == 0) return;
            summary = "1s 移动包 " + moveCount + "，间隔 "
                    + (gapMin == Long.MAX_VALUE ? 0L : gapMin) + "~" + gapMax + " ms";
            statsSecond = second;
        }
        chat(summary, false);
        resetStats();
    }

    private static void resetStats() {
        synchronized (LOCK) {
            lastMoveAt = 0L;
            gapMin = Long.MAX_VALUE;
            gapMax = 0L;
            gapSum = 0L;
            moveCount = 0;
            timerLines = 0;
            statsSecond = System.currentTimeMillis() / 1000L;
        }
    }

    private static void account(long now, long gap) {
        synchronized (LOCK) {
            moveCount++;
            if (gap >= 0L) {
                if (gap < gapMin) gapMin = gap;
                if (gap > gapMax) gapMax = gap;
                gapSum += gap;
            }
            if (statsSecond == 0L) statsSecond = now / 1000L;
        }
    }

    /** 打印到聊天框；组件不可用时退回日志。 */
    private static void print(Object player, String text) {
        Object component = component(text);
        if (player != null && component != null
                && OpenMaverFeatures.callVoid(player, "addChatMessage", "func_145747_a", "a", component)) {
            return;
        }
        util.Logger.info("Velocity", text);
    }

    /**
     * 反射构造 ChatComponentText。类名在 MCP/SRG 下一致，构造器在运行期仍是
     * {@code <init>}，所以这里不能走 reobf 的成员映射，只能直接反射。
     */
    private static Object component(String text) {
        if (componentBroken) return null;
        try {
            if (componentCtor == null) {
                Class<?> type = Class.forName("net.minecraft.util.ChatComponentText");
                componentCtor = type.getConstructor(String.class);
                componentCtor.setAccessible(true);
            }
            return componentCtor.newInstance(PREFIX + text);
        } catch (Throwable error) {
            componentBroken = true;
            util.Logger.warn("Velocity", "ChatComponentText 不可用，调试输出只写日志: " + error);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 事件行：日志 + 限流后的聊天框。 */
    private static void emit(String line, boolean important) {
        if (!enabled || line == null) return;
        logLine(line);
        chat(line, important);
    }

    /** 只写日志（每个包都记，聊天框另行过滤）。 */
    private static void logLine(String line) {
        if (!enabled || line == null) return;
        util.Logger.debug("Velocity", line);
        synchronized (LOCK) {
            if (history.size() >= MAX_HISTORY) {
                history.poll();
                historyDropped++;
            }
            history.add(line);
        }
    }

    private static void chat(String line, boolean important) {
        if (!enabled || line == null) return;
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - windowStart >= 1000L) {
                windowStart = now;
                windowUsed = 0;
                timerLines = 0;
            }
            if (!important && windowUsed >= MAX_LINES_PER_SECOND) {
                dropped++;
                return;
            }
            windowUsed++;
            if (dropped > 0) {
                pending.add("… " + dropped + " 行被限流");
                dropped = 0;
            }
            if (pending.size() >= MAX_QUEUE) pending.poll();
            pending.add(line);
        }
    }

    /** 取走当前排队的调试行；主线程 flush 与测试共用。 */
    static List<String> drain() {
        synchronized (LOCK) {
            if (pending.isEmpty()) return new ArrayList<String>();
            List<String> lines = new ArrayList<String>(pending);
            pending.clear();
            return lines;
        }
    }

    /** 最近输出的调试行快照（不含聊天限流丢弃），最新的在末尾。 */
    static List<String> history() {
        synchronized (LOCK) { return new ArrayList<String>(history); }
    }

    static int droppedLines() {
        synchronized (LOCK) { return historyDropped; }
    }

    private static String describe(Object packet, boolean move, boolean hold, int queued, long gap) {
        StringBuilder line = new StringBuilder(96);
        line.append("OUT ").append(hold ? "HOLD" : "SEND").append(' ').append(shortName(packet));
        if (move) {
            double[] pos = packetPosition(packet);
            if (pos != null) {
                line.append(" (").append(fmt2(pos[0])).append(", ").append(fmt2(pos[1]))
                        .append(", ").append(fmt2(pos[2])).append(')');
            }
            Object ground = OpenMaverFeatures.read(packet, "onGround", "field_149474_g", null);
            if (ground instanceof Boolean) line.append(" ground=").append(ground);
            if (gap >= 0L) line.append(" +").append(gap).append("ms");
        }
        if (hold) line.append(" q=").append(queued);
        return line.toString();
    }

    /** C03PacketPlayer 及其 C04/C05/C06 子类才是移动/视角包。 */
    private static boolean isMovePacket(Object packet) {
        for (Class<?> type = packet.getClass(); type != null; type = type.getSuperclass()) {
            if ("C03PacketPlayer".equals(type.getSimpleName())) return true;
        }
        return false;
    }

    /** 只有 moving=true 的包（C04/C06）才带真实坐标；C03 基类与 C05 打印出来是 0。 */
    private static double[] packetPosition(Object packet) {
        Object moving = OpenMaverFeatures.read(packet, "moving", "field_149480_h", null);
        if (moving instanceof Boolean && !((Boolean) moving)) return null;
        Double x = num(packet, "x", "field_149479_a");
        Double y = num(packet, "y", "field_149477_b");
        Double z = num(packet, "z", "field_149478_c");
        if (x == null || y == null || z == null) return null;
        return new double[] {x, y, z};
    }

    private static double[] playerPosition() {
        Object player = AttackReduceFeature.player();
        if (player == null) return null;
        return new double[] {
                AttackReduceFeature.number(player, "posX", "field_70165_t"),
                AttackReduceFeature.number(player, "posY", "field_70163_u"),
                AttackReduceFeature.number(player, "posZ", "field_70161_v")};
    }

    private static Double num(Object target, String... names) {
        Field field = OpenMaverFeatures.findField(target, names);
        if (field == null) return null;
        try {
            return field.getDouble(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String shortName(Object packet) {
        String name = packet.getClass().getSimpleName();
        if (name == null || name.isEmpty()) return packet.getClass().getName();
        return name.startsWith("C03PacketPlayer") ? "C03" : name;
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String fmt2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * 一次击退的服务端视角轨迹：从收到 S12 时的客户端位置出发，按原版玩家物理
     * （重力 0.08、垂直阻尼 0.98、水平阻尼 0.91、无输入）逐 tick 推演。
     */
    static final class Trace {
        final double x, y, z, vx, vy, vz;

        Trace(double x, double y, double z, double vx, double vy, double vz) {
            this.x = x; this.y = y; this.z = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
        }

        /** {@code ticks} 个 tick 之后的位置。 */
        double[] at(int ticks) {
            double px = x, py = y, pz = z, mx = vx, my = vy, mz = vz;
            for (int i = 0; i < ticks; i++) {
                my -= 0.08D;
                px += mx; py += my; pz += mz;
                my *= 0.98D; mx *= 0.91D; mz *= 0.91D;
            }
            return new double[] {px, py, pz};
        }

        /** 回到起跳高度所需的 tick（最多 60），用于「预计被打到哪里」。 */
        int landingTicks() {
            double py = y, my = vy;
            for (int i = 1; i <= 60; i++) {
                my -= 0.08D;
                py += my;
                my *= 0.98D;
                if (my < 0 && py <= y) return i;
            }
            return 60;
        }
    }
}
