import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Velocity 管线注入的**全链路**测试（真实 netty 通道，不是 EmbeddedChannel）。
 *
 * <p>EmbeddedChannel 会把入站终点 handler 拍在构造函数末尾，之后再 addLast 的
 * handler 收不到入站消息，无法模拟运行期注入；这里用注册过的
 * {@link NioSocketChannel}（真实 {@code DefaultChannelPipeline}，链表派发），
 * 按 1.8.9 的真实顺序建出
 * {@code timeout -> splitter -> decoder -> prepender -> encoder -> packet_handler}，
 * 然后走 {@code VelocityFeature.installHook()} 的完整反射路径：
 *
 * <pre>
 *   EntityPlayerSP.sendQueue(field_71174_a)
 *     -> NetHandlerPlayClient.netManager (按类型找)
 *     -> NetworkManager.channel       (按类型找)
 *     -> Channel.pipeline()           (反射调用)
 *     -> addBefore("packet_handler", "openmaver_velocity", hook)
 * </pre>
 *
 * <p>任何一步返回 null 都会让模块静默失效，所以这层必须单独验证。
 */
public final class VelocityInstallTest {

    /** 测试替身玩家：sendQueue 字段名与 SRG 一致。 */
    public static final class FakePlayer {
        public Object field_71174_a;
        public int func_145782_y_id = 7;

        public int getEntityId() {
            return func_145782_y_id;
        }
    }

    /** 记录型 handler：入站/出站都记一笔再放行。 */
    public static final class Recorder extends ChannelDuplexHandler {
        final String label;
        final List<Object> inbound = new ArrayList<Object>();
        final List<Object> outbound = new ArrayList<Object>();

        Recorder(String label) {
            this.label = label;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            inbound.add(msg);
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            outbound.add(msg);
            ctx.write(msg, promise);
        }
    }

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        VelocityFeature.load(new Properties());
        VelocityFeature.setMode(VelocityFeature.MODE_MODIFY);
        VelocityFeature.setHorizontal(0.0F);
        VelocityFeature.setVertical(0.0F);
        setStatic("active", Boolean.TRUE);

        // ---- 构造与 1.8.9 完全一致的管线（真实注册通道）----
        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel channel = new NioSocketChannel();
        group.register(channel).sync();

        ChannelPipeline pipeline = channel.pipeline();
        Recorder timeout = new Recorder("timeout");
        pipeline.addLast("timeout", timeout);
        pipeline.addLast("splitter", new Recorder("splitter"));
        pipeline.addLast("decoder", new Recorder("decoder"));
        pipeline.addLast("prepender", new Recorder("prepender"));
        Recorder encoder = new Recorder("encoder");
        pipeline.addLast("encoder", encoder);
        Recorder packetHandler = new Recorder("packet_handler");
        pipeline.addLast("packet_handler", packetHandler);
        check("pipeline built like 1.8.9", pipeline.names().contains("packet_handler"));

        // ---- 伪造 sendQueue -> netManager -> channel 的持有链 ----
        Object networkManager = newNetworkManager(channel);
        Object netHandler = newNetHandler(networkManager);
        FakePlayer player = new FakePlayer();
        player.field_71174_a = netHandler;

        // ---- 走真实反射注入路径 ----
        invokeStatic("maintainHook",
                new Class<?>[] {Class.forName("net.minecraft.client.Minecraft"), Object.class},
                null, player);
        check("installHook resolved the chain", (Boolean) getStatic("hookInstalled"));
        check("hook present in pipeline", pipeline.names().contains("openmaver_velocity"));

        String base = VelocityFeature.pickBaseName(pipeline.names());
        check("base name is packet_handler", "packet_handler".equals(base));

        // 位置正确：hook 必须排在 encoder 之后、packet_handler 之前
        List<String> names = pipeline.names();
        check("hook index between encoder and packet_handler",
                names.indexOf("openmaver_velocity") > names.indexOf("encoder")
                        && names.indexOf("openmaver_velocity") < names.indexOf("packet_handler"));

        // ---- 入站：S12 必须被 hook 吞掉，走不到 packet_handler ----
        // 默认预设（GRIM/CUSTOM + 守护）现在是 SPRINT SLAP：它**故意**放行 S12
        // （客户端必须真的吃到击退，减伤靠之后的 sprint-attack ×0.6）。
        // 这里显式切回归零类模式，验证吞包通道本身仍然工作。
        VelocityFeature.setGrimGuard(false);
        VelocityFeature.setMode(VelocityFeature.MODE_UNCERTAINTY);
        setStatic("localEntityId", Integer.valueOf(7));
        Object s12 = newS12(7, 0.4D, 0.3608D, -0.4D);
        fireInbound(channel, pipeline, s12);
        check("S12 cancelled before packet_handler", packetHandler.inbound.isEmpty());
        check("knockback vector captured", Math.abs(getDouble("pendingKbX") - 0.4D) < 1e-3D);

        // ---- 出站：blink 期间 C03 被扣住，encoder 收不到 ----
        VelocityFeature.setMode(VelocityFeature.MODE_BLINK);
        invokeStatic("startBlink", new Class<?>[0]);
        // 出站要从 hook 的**下游**（tail 方向）发起，ctx.write 是从自身往 head 走，
        // 用 hook 自己的 ctx 会绕过它自己。
        ChannelHandlerContext tailCtx = pipeline.context("packet_handler");
        final Object move = newMove(1.0D);
        writeFrom(channel, tailCtx, move);
        check("C03 held before encoder", encoder.outbound.isEmpty()
                && VelocityFeature.getHeldCount() == 1);
        invokeStatic("releaseHeld", new Class<?>[] {ChannelHandlerContext.class}, new Object[] {null});
        awaitEventLoop();
        check("C03 reaches encoder after release", encoder.outbound.size() == 1
                && encoder.outbound.get(0) == move);

        // 说明：releaseHeld 从调用线程直接 ctx.write，netty 会把它排到 eventLoop，
        // 因此这里保留一个短暂的等待；上面的扣包断言则通过 writeFrom 同步执行。

        // ---- 卸载：handler 必须被摘掉 ----
        invokeStatic("detachHook", new Class<?>[] {String.class}, "test");
        check("hook removed from pipeline", !pipeline.names().contains("openmaver_velocity"));
        check("hookInstalled flag cleared", !((Boolean) getStatic("hookInstalled")).booleanValue());

        // ---- 重连：换成新的 NetworkManager/Channel，必须自动重挂 ----
        // 这就是实测踩到的坑：在单人世界开启模块后连服务器，旧连接留下的
        // hookInstalled=true 会挡住新管线的注入 -> 模块完全无效。
        Channel channel2 = new NioSocketChannel();
        group.register(channel2).sync();
        ChannelPipeline pipeline2 = channel2.pipeline();
        pipeline2.addLast("timeout", new Recorder("timeout"));
        pipeline2.addLast("splitter", new Recorder("splitter"));
        pipeline2.addLast("decoder", new Recorder("decoder"));
        pipeline2.addLast("prepender", new Recorder("prepender"));
        Recorder encoder2 = new Recorder("encoder");
        pipeline2.addLast("encoder", encoder2);
        Recorder packetHandler2 = new Recorder("packet_handler");
        pipeline2.addLast("packet_handler", packetHandler2);

        Object networkManager2 = newNetworkManager(channel2);
        Object netHandler2 = newNetHandler(networkManager2);
        player.field_71174_a = netHandler2;                                // 客户端换了连接
        setStatic("hookAttemptTick", Integer.valueOf(-1000));              // 越过 1 秒节流
        invokeStatic("maintainHook",
                new Class<?>[] {Class.forName("net.minecraft.client.Minecraft"), Object.class},
                null, player);
        check("hook re-attached on the new connection",
                (Boolean) getStatic("hookInstalled")
                        && pipeline2.names().contains("openmaver_velocity"));
        check("old pipeline no longer hooked",
                !pipeline.names().contains("openmaver_velocity"));

        VelocityFeature.setGrimGuard(false);
        VelocityFeature.setMode(VelocityFeature.MODE_MODIFY);
        VelocityFeature.setHorizontal(0.0F);
        VelocityFeature.setVertical(0.0F);
        fireInbound(channel2, pipeline2, newS12(7, 0.4D, 0.3608D, -0.4D));
        check("S12 cancelled on the new connection", packetHandler2.inbound.isEmpty());
        check("knockback captured on new connection",
                Math.abs(getDouble("pendingKbX") - 0.4D) < 1e-3D);

        // ---- 被别的插件摘掉 handler 之后自愈 ----
        pipeline2.remove("openmaver_velocity");
        setStatic("hookAttemptTick", Integer.valueOf(-1000));
        invokeStatic("maintainHook",
                new Class<?>[] {Class.forName("net.minecraft.client.Minecraft"), Object.class},
                null, player);
        check("hook self-heals after external removal",
                pipeline2.names().contains("openmaver_velocity"));

        channel.close().sync();
        channel2.close().sync();
        group.shutdownGracefully(0L, 100L, java.util.concurrent.TimeUnit.MILLISECONDS).sync();

        System.out.println("---- " + passed + " passed, " + failed + " failed ----");
        // netty 的事件循环线程是守护/非守护不一，显式退出避免测试进程挂住。
        System.exit(failed > 0 ? 1 : 0);
    }

    private static Object newNetworkManager(Channel channel) throws Exception {
        Class<?> type = Class.forName("net.minecraft.network.NetworkManager");
        Object instance = type.getConstructor().newInstance();
        type.getMethod("setChannel", Channel.class).invoke(instance, channel);
        return instance;
    }

    private static Object newNetHandler(Object networkManager) throws Exception {
        Class<?> nm = Class.forName("net.minecraft.network.NetworkManager");
        Class<?> type = Class.forName("net.minecraft.network.NetHandlerPlayClient");
        return type.getConstructor(nm).newInstance(networkManager);
    }

    private static Object newS12(int entityId, double x, double y, double z) throws Exception {
        Class<?> type = Class.forName("net.minecraft.network.play.server.S12PacketEntityVelocity");
        return type.getConstructor(int.class, double.class, double.class, double.class)
                .newInstance(Integer.valueOf(entityId), Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
    }

    private static Object newMove(double x) throws Exception {
        Class<?> type = Class.forName("net.minecraft.network.play.client.C03PacketPlayer");
        return type.getConstructor(double.class).newInstance(Double.valueOf(x));
    }

    /** 在 eventLoop 线程上执行并等待完成：这样 handler 是同步派发的，断言确定。 */
    private static void runOnLoop(Channel channel, Runnable task) throws Exception {
        channel.eventLoop().submit(task).sync();
    }

    /** 入站：在 eventLoop 上 fireChannelRead。 */
    private static void fireInbound(Channel channel, final ChannelPipeline pipeline,
            final Object msg) throws Exception {
        runOnLoop(channel, new Runnable() {
            public void run() {
                pipeline.fireChannelRead(msg);
            }
        });
    }

    /** 出站：在 eventLoop 上从指定 ctx 往 head 方向写（会经过该 ctx 之前的 handler）。 */
    private static void writeFrom(Channel channel, final ChannelHandlerContext ctx,
            final Object msg) throws Exception {
        runOnLoop(channel, new Runnable() {
            public void run() {
                ctx.write(msg, ctx.newPromise());
            }
        });
    }

    private static void awaitEventLoop() throws Exception {
        Thread.sleep(120L);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = VelocityFeature.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Object getStatic(String name) throws Exception {
        Field field = VelocityFeature.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static double getDouble(String name) throws Exception {
        return ((Number) getStatic(name)).doubleValue();
    }

    private static void invokeStatic(String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = VelocityFeature.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try {
            method.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw new RuntimeException(name + " threw " + cause, cause);
        }
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + label);
        } else {
            failed++;
            System.out.println("  FAIL " + label);
        }
    }
}
