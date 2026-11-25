import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;

import net.minecraft.client.Minecraft;
import module.Module;
import module.ModuleManager;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Ping spoof：把出站的事务确认（{@code C0FPacketConfirmTransaction}）压后发出，
 * 用来拉长服务端「目标可能在哪」的不确定窗口。
 *
 * <p><b>为什么这能换成距离</b>：Grim 的 {@code combat/Reach} 不用目标的单一坐标，
 * 而是用 {@code PacketEntity.getPossibleCollisionBoxes()}：
 * <pre>
 *   onFirstTransaction(x,y,z)  // 服务端发出 S32 事务
 *   onSecondTransaction()      // 收到客户端 C0F 回应，丢弃旧可能性
 * </pre>
 * 在「事务发出 → 收到确认」这段时间里，盒子取的是
 * {@code combineCollisionBox(oldPossible, newPossible)}，也就是这段时间内目标可能
 * 走过的整段并集；判据再取所有盒子里最近的距离。于是确认回得越晚，
 * 盒子在目标移动方向上越长，Grim 量出来的距离越短。
 *
 * <p>收益 = 目标在「真实 RTT + 额外延迟」窗口里的位移（贴身走位时约 0.2~0.3 格/tick），
 * 对朝你远离/横移的目标最明显；站桩不动的目标没有收益。
 *
 * <p>代价与风险：这是真的在制造 RTT 抖动，Grim 的 Transaction/Ping 面会看到更长的
 * 事务间隔。延迟保持有界（{@link #MAX_HOLD_NANOS}）、FIFO 顺序不变、断线立即清空，
 * 传送/重生期间先释放旧回执，再由原版处理包，避免 C06 越过对应的 C0F。
 */
public final class TransactionDelayFeature {

    /** Netty 处理器名字，和 AttackReduce 的钩子区分开。 */
    static final String HOOK_NAME = "openmaver_txdelay";
    /** 暂存队列上限：超过就直接把最老的写出，避免内存/延迟失控。 */
    private static final int MAX_HELD = 96;
    /** 单个包最长压后时间，超过就立即放行。 */
    private static final long MAX_HOLD_NANOS = 1_500_000_000L;

    private static final class Held {
        final ChannelHandlerContext ctx;
        final Object message;
        final ChannelPromise promise;
        final long dueNanos;

        Held(ChannelHandlerContext ctx, Object message, ChannelPromise promise, long dueNanos) {
            this.ctx = ctx;
            this.message = message;
            this.promise = promise;
            this.dueNanos = dueNanos;
        }
    }

    private static final Deque<Held> held = new ArrayDeque<Held>();
    private static final Random RANDOM = new Random();
    private static volatile TransactionDelayHook hook;
    private static Object boundHandler;
    private static int installFailures;
    private static long spoofedTotal;

    private TransactionDelayFeature() {
    }

    /** 主线程每 tick 调用：维护钩子、放行到期包。 */
    static void tick(Minecraft mc) {
        int delay = OpenMaverFeatures.getReachPingSpoof();
        if (!enabled() || delay <= 0) {
            flush(true);
            uninstall();
            return;
        }
        Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        Object handler = player == null ? null
                : OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
        if (handler == null) {
            flush(true);
            uninstall();
            return;
        }
        if (hook == null || boundHandler != handler) {
            install(handler);
        }
        flush(false);
    }

    static boolean isActive() {
        return hook != null;
    }

    /** 测试用：把钩子挂到给定 pipeline，等价于 install() 的 Netty 部分。 */
    static TransactionDelayHook installForTest(ChannelPipeline pipeline) {
        flush(true);
        uninstall();
        TransactionDelayHook created = new TransactionDelayHook();
        addHook(pipeline, created);
        hook = created;
        boundHandler = new Object();
        return created;
    }

    /** 测试用：放行已到期的包。 */
    static void pumpForTest() {
        flush(false);
    }

    /** 测试用：立即放行全部。 */
    static void flushForTest() {
        flush(true);
    }

    /** 测试用：当前暂存数量。 */
    static int heldForTest() {
        synchronized (held) {
            return held.size();
        }
    }

    /** 测试用：卸载。 */
    static void uninstallForTest() {
        flush(true);
        uninstall();
    }

    static long spoofedCount() {
        return spoofedTotal;
    }

    /** 出站包入口；返回 true 表示已被接管（调用方不要再写出）。 */
    static boolean capture(TransactionDelayHook source, ChannelHandlerContext ctx,
            Object message, ChannelPromise promise) {
        if (source == null || source != hook || message == null) return false;
        if (!isConfirmTransaction(message)) return false;
        int base = OpenMaverFeatures.getReachPingSpoof();
        if (!enabled() || base <= 0 || source.correctionDepth > 0) {
            // 开关改变不能让新回执越过队列里尚未到期的旧回执。
            flush(true);
            return false;
        }
        int jitter = OpenMaverFeatures.getReachPingJitter();
        long delayMs = base + (jitter > 0 ? RANDOM.nextInt(jitter + 1) : 0);
        long due = System.nanoTime() + Math.min(delayMs * 1_000_000L, MAX_HOLD_NANOS);
        synchronized (held) {
            if (held.size() >= MAX_HELD) {
                flushLocked(System.nanoTime(), true);
            }
            held.add(new Held(ctx, message, promise, due));
        }
        spoofedTotal++;
        return true;
    }

    static void connectionClosed(TransactionDelayHook source) {
        if (source != hook) return;
        flush(true);
        uninstall();
    }

    private static boolean enabled() {
        Module reach = ModuleManager.getModule("Reach");
        return reach != null && reach.isEnabled();
    }

    static boolean isCorrection(Object message) {
        if (message == null) return false;
        String name = message.getClass().getName();
        return name.endsWith("S08PacketPlayerPosLook") || name.endsWith("S07PacketRespawn");
    }

    /**
     * 与原版 PacketThreadUtil 的主线程任务保持同序，然后再排回连接的 event loop。
     * 前边界释放旧 C0F，后边界必须排在原版 C06 的异步 write 之后才能恢复延迟。
     * Netty 收到 S08 时直接 flush 会打断当前正在运行的 action -> C03 序列。
     */
    static void correctionBoundary(final TransactionDelayHook source,
            final ChannelHandlerContext ctx, final boolean begin) {
        if (source != hook) return;
        Runnable boundary = new Runnable() {
            @Override public void run() {
                ctx.channel().eventLoop().execute(new Runnable() {
                    @Override public void run() {
                        if (source != hook) return;
                        if (begin) {
                            source.correctionDepth++;
                            flush(true);
                        } else {
                            source.correctionDepth = Math.max(0, source.correctionDepth - 1);
                        }
                    }
                });
            }
        };
        if (!OpenMaverFeatures.callVoid(Minecraft.getMinecraft(),
                "addScheduledTask", "func_152344_a", null, boundary)) {
            // 无法建立主线程边界时暂停本连接的延迟，仍让原版处理入站包。
            source.correctionDepth++;
            warnOnce("correction task unavailable; transaction delay paused");
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static boolean isConfirmTransaction(Object message) {
        // Forge 1.8.9 运行期是 MCP 类名，注入模式可能是 SRG/压缩名，
        // 这里按后缀匹配，不依赖具体映射。
        String name = message.getClass().getName();
        return name.endsWith("C0FPacketConfirmTransaction")
                || name.endsWith("CPacketConfirmTransaction")
                || name.endsWith("ConfirmTransaction");
    }

    private static void install(Object handler) {
        flush(true);
        uninstall();
        try {
            Object manager = fieldByType(handler, "net.minecraft.network.NetworkManager");
            Object channel = fieldByType(manager, "io.netty.channel.Channel");
            if (channel == null) {
                warnOnce("channel unavailable");
                return;
            }
            ChannelPipeline pipeline = (ChannelPipeline) OpenMaverFeatures.call(
                    channel, "pipeline", "pipeline", null);
            if (pipeline == null) {
                warnOnce("pipeline unavailable");
                return;
            }
            TransactionDelayHook created = new TransactionDelayHook();
            addHook(pipeline, created);
            hook = created;
            boundHandler = handler;
            installFailures = 0;
        } catch (Throwable error) {
            if (++installFailures <= 3) {
                util.Logger.warn("Reach", "transaction delay hook failed: " + error);
            }
        }
    }

    private static void addHook(ChannelPipeline pipeline, TransactionDelayHook created) {
        // Velocity 会吞下入站包并自行排主线程任务，事务边界必须先看见 S08/S07。
        // 出站方向相反，Velocity 挂起的整段包序会先回放，再经过事务钩子。
        String before = pipeline.names().contains(AttackReducePacketHook.NAME)
                ? AttackReducePacketHook.NAME : "packet_handler";
        pipeline.addBefore(before, HOOK_NAME, created);
    }

    private static void uninstall() {
        TransactionDelayHook current = hook;
        hook = null;
        boundHandler = null;
        if (current == null) return;
        try {
            ChannelHandlerContext ctx = current.context;
            if (ctx != null && ctx.pipeline().names().contains(HOOK_NAME)) {
                ctx.pipeline().remove(HOOK_NAME);
            }
        } catch (Throwable ignored) {
            // 连接已经关闭时 remove 会抛，忽略。
        }
    }

    private static void flush(boolean force) {
        long now = System.nanoTime();
        boolean released;
        synchronized (held) {
            released = flushLocked(now, force);
        }
        if (!released) return;
        // 1.8 的 NetworkManager 只在有包出站时 flush；补发的包必须自己触发一次，
        // 否则会一直躺在 Netty 的出站缓冲里直到下一条包。
        TransactionDelayHook current = hook;
        if (current != null && current.context != null) {
            try {
                current.context.flush();
            } catch (Throwable ignored) {
                // 连接关闭时忽略。
            }
        }
    }

    private static boolean flushLocked(long now, boolean force) {
        boolean released = false;
        Held next;
        while ((next = held.peek()) != null && (force || next.dueNanos - now <= 0L)) {
            held.poll();
            try {
                next.ctx.write(next.message, next.promise);
                released = true;
            } catch (Throwable ignored) {
                // 连接已断，丢弃即可。
            }
        }
        return released;
    }

    private static Object fieldByType(Object target, String typeName) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().getName().equals(typeName)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null) return value;
                } catch (Throwable ignored) {
                    // 继续找下一个候选字段。
                }
            }
        }
        return null;
    }

    private static boolean warned;

    private static void warnOnce(String reason) {
        if (warned) return;
        warned = true;
        util.Logger.warn("Reach", "transaction delay unavailable: " + reason);
    }
}
