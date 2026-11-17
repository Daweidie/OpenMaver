import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Velocity 的 Netty 管线处理器。
 *
 * <p>OpenMaver 没有 Mixin / ASM，但 Forge 1.8.9 的 {@code NetworkManager} 是一条
 * 标准 Netty 管线（{@code timeout -> splitter -> decoder -> prepender -> encoder
 * -> packet_handler}，顺序取自真实 1.8.9 jar 的 ChannelInitializer）。把本 handler
 * 插在 {@code packet_handler} 之前，就能在**同一个位置**同时看到：
 * <ul>
 *   <li>入站：{@code decoder} 已经还原成 {@code Packet} 对象、尚未交给
 *       {@code NetHandlerPlayClient} 的包（S12 击退包）</li>
 *   <li>出站：{@code packet_handler} 交出、尚未被 {@code encoder} 序列化的
 *       {@code Packet} 对象（C03 / C04 / C05 / C06 移动包）</li>
 * </ul>
 * 原因：出站方向从 tail 往 head 走，{@code packet_handler} 先执行，因此只有排在
 * {@code encoder} **之后**的 handler 才能拿到未序列化的 {@code Packet}。
 *
 * <p><b>线程</b>：本类的回调运行在 Netty 的 I/O 线程，绝不允许触碰世界/实体对象。
 * 需要处理的数据只写进 {@link VelocityFeature} 的 volatile 字段，真正的运动修改
 * 由主线程在 tick 开始时完成。
 */
public final class VelocityPacketHook extends ChannelDuplexHandler {

    /** 管线里的实例名，卸载/查重都用它。 */
    static final String NAME = "openmaver_velocity";

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        VelocityFeature.attachContext(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // 被卸载（关闭模块 / 换服务器 / 断线）时把憋住的位置包补发掉，
        // 否则服务端会永远停在我们憋包前的位置。
        VelocityFeature.releaseHeld(ctx);
        VelocityFeature.detachContext(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            // Scaffold 的击退窗口：只读 S12 的四个 int 字段，不碰任何游戏对象，
            // 因此可以安全地在 Netty 线程上调用。
            ScaffoldFeature.noteInboundPacket(msg);
            if (VelocityFeature.shouldCancelVelocityPacket(msg)) {
                // 直接吞掉：NetHandlerPlayClient 收不到 S12，原版就不会给玩家
                // 施加击退，后续由 VelocityFeature 按模式自行回填速度。
                return;
            }
        } catch (Throwable ignored) {
            // 任何反射/解析异常都不能影响网络线程。
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try {
            // 出站旁路统计：slap 出手前要知道这一 tick 有没有别的 C02
            // （Grim 每个 tick 只给一条 0.6 分支）。
            VelocityFeature.noteOutbound(msg);
            if (VelocityFeature.shouldHoldOutbound(msg)) {
                // blink：位置包先存起来，promise 立即标记成功，避免调用方
                // （NetworkManager.dispatchPacket）一直等在未完成的 future 上。
                VelocityFeature.holdOutbound(msg);
                if (promise != null) {
                    promise.setSuccess();
                }
                return;
            }
        } catch (Throwable ignored) {
            // 出错时退回标准路径，宁可漏憋一个包也不能卡住网络。
        }
        ctx.write(msg, promise);
    }
}
