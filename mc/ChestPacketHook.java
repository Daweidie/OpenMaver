import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

/**
 * 箱子相关的<b>入站窗口包观察者</b>（Netty 管线，插在 {@code packet_handler} 之前）。
 *
 * <p>只做一件事：把服务端的
 * <ul>
 *   <li>{@code S2DPacketOpenWindow} —— 服务端把箱子开起来了；</li>
 *   <li>{@code S30PacketWindowItems} —— 这个窗口的物品内容已经发到客户端；</li>
 *   <li>{@code S2EPacketCloseWindow} —— 服务端自己把窗口关了（走远 / 死亡 / 指令）</li>
 * </ul>
 * 的窗口号记进 {@link ChestFeature}。搬运逻辑靠它们对齐「客户端真的收到了内容」和
 * 「服务端还认为这个窗口开着」，而不是靠 {@code Thread.sleep} 式的估算。
 *
 * <p><b>不改包、不拦截</b>：入站原样 {@code fireChannelRead}，出站完全不碰
 * （出站由 {@code AttackReducePacketHook} 负责，两者互不干扰）。
 *
 * <p><b>线程</b>：回调运行在 Netty 的 I/O 线程，因此这里绝不读世界 / 实体 / 容器，
 * 只把包对象交给 {@link ChestFeature#noteInboundPacket(Object)} 写两个 volatile 字段。
 */
public final class ChestPacketHook extends ChannelDuplexHandler {

    /** 管线里的实例名：注入 / 查重 / 卸载都用它。 */
    static final String NAME = "openmaver_chest";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean staleClose = false;
        try {
            staleClose = ChestFeature.isStaleCloseWindow(msg);
            ChestFeature.noteInboundPacket(msg);
        } catch (Throwable ignored) {
            // 任何反射 / 解析异常都不能影响网络线程：包照常往下传。
        }
        if (staleClose) {
            // 过期窗口的 S2E：1.8.9 的 handleCloseWindow 是无条件 closeScreen()，
            // 放过去就会把刚打开的新箱子界面一起关掉（「点开箱子直接不拿」）。
            // 包对象不是 ByteBuf，不涉及引用计数，直接回收即可。
            return;
        }
        ctx.fireChannelRead(msg);
    }
}
