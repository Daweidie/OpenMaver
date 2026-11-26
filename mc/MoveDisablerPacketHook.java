import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/** 出站直接使用 ctx.write，原包只经过一次管线，补发的 C0B 也不重入当前钩子。 */
public final class MoveDisablerPacketHook extends ChannelDuplexHandler {
    static final String NAME = "openmaver_movedisabler";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        MoveDisablerFeature.captureInbound(this, ctx, msg);
        ctx.fireChannelRead(msg);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        MoveDisablerFeature.hookRemoved(this);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        MoveDisablerFeature.hookRemoved(this);
        ctx.fireChannelInactive();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!MoveDisablerFeature.captureOutbound(this, ctx, msg, promise)) {
            ctx.write(msg, promise);
        }
    }
}
