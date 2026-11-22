import net.minecraft.client.Minecraft;

import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Reach 的聊天框调试输出。
 *
 * <p>只观测 Reach 自己的决策链，不改变任何发包与随机数消耗顺序：
 * <ul>
 *   <li>{@code SWING ...} 识别到一次新的挥击：挥击进度、CHANCE、DISTANCE、上限与 profile</li>
 *   <li>{@code SKIP ...} 本次挥击没有补刀的原因（概率未命中 / 原版已命中 / 无敌帧 /
 *       没有目标 / 补 animation 失败 / Criticals 延迟）</li>
 *   <li>{@code HIT ...} 本地 controller 攻击调用成功：目标名、射线距离/上限、
 *       AABB 扩张量、攻击瞬间的 yaw/pitch、本次是否本来就处于挥击动画中
 *       （补刀前先发 C0A，{@code anim=c0a}；服务端伤害结算另行确认）</li>
 *   <li>{@code 1s ...} 每秒一行汇总，避免 10+ CPS 时逐条刷屏</li>
 * </ul>
 *
 * <p>聊天框限流（默认每秒 10 行，HIT / 关键跳过不限流），完整事件仍然写
 * {@code logs/openmaver.log}（{@code util.Logger.debug}）。事件发生在客户端 tick，
 * 统一入队后由主线程 {@link #flush(Minecraft)} 每 tick 取出写聊天框；聊天组件用反射
 * 构造，避免 MCP → SRG reobf 的构造器映射问题。
 */
final class ReachDebug {
    /** 聊天框每秒最多输出行数（统计行与关键行除外）。 */
    private static final int MAX_LINES_PER_SECOND = 10;
    /** 队列上限，防止掉帧/断线时堆积。 */
    private static final int MAX_QUEUE = 256;
    /** 最近输出行快照上限（测试与回看用）。 */
    private static final int MAX_HISTORY = 128;
    /** 聊天前缀。 */
    private static final String PREFIX = "\u00A78[\u00A7aRCH\u00A78] \u00A77";

    private static final Object LOCK = new Object();
    private static final Deque<String> pending = new ArrayDeque<String>();
    private static final Deque<String> history = new ArrayDeque<String>();
    private static long windowStart = System.currentTimeMillis();
    private static int windowUsed;
    private static int dropped;
    private static int historyDropped;

    private static volatile boolean enabled;

    /** 每秒统计（跨秒清零）。 */
    private static long statsSecond = System.currentTimeMillis() / 1000L;
    private static int swings;
    private static int hits;
    private static int skips;

    /** ChatComponentText(String) 构造函数；null 表示尚未解析。 */
    private static Constructor<?> componentCtor;
    private static boolean componentBroken;

    private ReachDebug() {
    }

    static void setEnabled(boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        synchronized (LOCK) {
            pending.clear();
            history.clear();
            historyDropped = 0;
            windowUsed = 0;
            dropped = 0;
        }
        resetStats();
        if (value) {
            chat("DEBUG ON cap=" + f2(OpenMaverFeatures.getEffectiveReachDistance()) + "m"
                    + " dist=" + f2(OpenMaverFeatures.getReachDistance()) + "m"
                    + " chance=" + Math.round(OpenMaverFeatures.getReachChance()) + "%"
                    + " grim=" + (OpenMaverFeatures.isReachGrimCheck()
                            ? "CAP " + f2(OpenMaverFeatures.getReachGrimCap()) : "OFF")
                    + " spoof=" + OpenMaverFeatures.getReachPingSpoof() + "ms"
                    + (OpenMaverFeatures.getReachPingSpoof() > 0
                            ? "(+" + OpenMaverFeatures.getReachPingJitter() + ")" : "")
                    + " profile=" + (OpenMaverFeatures.isBypassEnabled()
                            ? OpenMaverFeatures.getBypassProfileName() : "OFF"), true);
        }
    }

    static boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------
    // 事件入口
    // ------------------------------------------------------------------

    /** 一次新的挥击被 Reach 识别：随后才掷 CHANCE 并尝试自定义射线。 */
    static void swing(int progress, double serverCap, double cameraCap, String profile) {
        if (!enabled) return;
        swings++;
        emit("SWING p=" + progress
                + " chance=" + Math.round(OpenMaverFeatures.getReachChance()) + "%"
                + " dist=" + f2(OpenMaverFeatures.getReachDistance())
                + " cam=" + f2(cameraCap)
                + " srv=" + f2(serverCap)
                + " " + profile, false);
    }

    /** 普通跳过原因：与下一次挥击之间的流水，受聊天限流。 */
    static void skip(String reason) {
        if (!enabled) return;
        skips++;
        emit("SKIP " + reason, false);
    }

    /** 关键跳过（无敌帧 / 补 animation 失败）：优先写聊天框。 */
    static void skipImportant(String reason) {
        if (!enabled) return;
        skips++;
        emit("SKIP " + reason, true);
    }

    /**
     * controller 攻击调用成功；HIT 仅记录本地发送尝试，服务端伤害结算另行确认。
     *
     * <p>{@code cam} 是本地准星射线距离与 DISTANCE 上限，{@code srv} 是服务端
     * 结算这条 C02 时实际会量到的距离（上一条 tick 包的姿态）与它的上限。
     * {@code srv <= 上限} 才可能不触发 Grim 的 Reach 判定。
     */
    static void hit(String target, double distance, double cameraCap,
            double serverDistance, double serverCap, double border,
            float yaw, float pitch, boolean alreadySwinging) {
        if (!enabled) return;
        hits++;
        emit("HIT " + target
                + " cam=" + f2(distance) + "/" + f2(cameraCap)
                + " srv=" + f2(serverDistance) + "/" + f2(serverCap)
                + " border=" + f2(border)
                + " yaw=" + f1(yaw) + " pitch=" + f1(pitch)
                + " anim=c0a swing=" + (alreadySwinging ? 1 : 0), true);
    }

    // ------------------------------------------------------------------
    // 主线程：写入聊天框
    // ------------------------------------------------------------------

    /** 每个客户端 tick 调一次：先出统计行，再把排队的调试行写进聊天框。 */
    static void flush(Minecraft mc) {
        if (!enabled) {
            synchronized (LOCK) { pending.clear(); }
            resetStats();
            return;
        }
        chatStats();
        List<String> lines = drain();
        if (lines.isEmpty()) return;
        Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        for (int i = 0; i < lines.size(); i++) {
            print(player, lines.get(i));
        }
    }

    /** 每秒一行 Reach 决策统计，替代逐次挥击的刷屏。 */
    private static void chatStats() {
        long second = System.currentTimeMillis() / 1000L;
        String summary;
        synchronized (LOCK) {
            if (second == statsSecond) return;
            if (swings == 0 && hits == 0 && skips == 0) {
                statsSecond = second;
                return;
            }
            summary = "1s swing=" + swings + " hit=" + hits + " skip=" + skips;
            statsSecond = second;
        }
        chat(summary, false);
        resetStats();
    }

    private static void resetStats() {
        synchronized (LOCK) {
            swings = 0;
            hits = 0;
            skips = 0;
            statsSecond = System.currentTimeMillis() / 1000L;
        }
    }

    /** 打印到聊天框；组件不可用时退回日志。 */
    private static void print(Object player, String text) {
        Object component = component(text);
        if (player != null && component != null
                && OpenMaverFeatures.callVoid(player, "addChatMessage", "func_145747_a", "a", component)) {
            return;
        }
        util.Logger.info("Reach", text);
    }

    /**
     * 反射构造 ChatComponentText。类名在 MCP/SRG 下一致，构造器在运行期仍是
     * {@code <init>}，因此不能走 reobf 的成员映射。
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
            util.Logger.warn("Reach", "ChatComponentText 不可用，调试输出只写日志: " + error);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 事件行：完整写日志，聊天框按重要度限流。 */
    private static void emit(String line, boolean important) {
        if (!enabled || line == null) return;
        logLine(line);
        chat(line, important);
    }

    /** 只写日志（每个事件都记，聊天框另行过滤）。 */
    private static void logLine(String line) {
        if (!enabled || line == null) return;
        util.Logger.debug("Reach", line);
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
        synchronized (LOCK) { return dropped; }
    }

    private static String f1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String f2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
