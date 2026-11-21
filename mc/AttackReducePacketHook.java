import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/** S12 应用后开窗；SLAP ATTACK 同时观察真实出站动作。 */
public final class AttackReducePacketHook extends ChannelDuplexHandler {
    // 保留旧 handler 名，便于连接维护和历史实例清理。
    static final String NAME = "openmaver_attackreduce";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            ScaffoldFeature.noteInboundPacket(msg);
        } catch (Throwable ignored) { }
        Runnable afterVanilla = AttackReduceFeature.acceptsHook(this) ? AttackReduceFeature.beforeInbound(this, msg) : null;
        ctx.fireChannelRead(msg);
        AttackReduceFeature.afterInbound(afterVanilla);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try { AttackReduceFeature.noteOutbound(this, msg); } catch (Throwable ignored) { }
        ctx.write(msg, promise);
    }
}
