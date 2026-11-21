import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/** Zen ReceivePacketEvent 主线程入口，以及 NoXZ 出站移动包队列。 */
public final class AttackReducePacketHook extends ChannelDuplexHandler {
    static final String NAME = "openmaver_attackreduce";
    volatile ChannelHandlerContext context;
    @Override public void handlerAdded(ChannelHandlerContext ctx) { context = ctx; }
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try { ScaffoldFeature.noteInboundPacket(msg); } catch (Throwable ignored) { }
        if (!AttackReduceFeature.captureInbound(this, msg)) ctx.fireChannelRead(msg);
    }
    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!AttackReduceFeature.captureOutbound(this, ctx, msg, promise)) ctx.write(msg, promise);
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        AttackReduceFeature.connectionClosed(this);
        super.channelInactive(ctx);
    }
}
