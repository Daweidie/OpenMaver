import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * 出站事务确认（C0F）的延迟钩子。
 *
 * <p>只拦截 {@code C0FPacketConfirmTransaction}：命中时交给
 * {@link TransactionDelayFeature} 暂存，其余包原样写出。入站包完全不看，
 * 避免和 AttackReduce / Scaffold 的入站钩子抢顺序。
 */
final class TransactionDelayHook extends ChannelDuplexHandler {
    /** 安装后由 Netty 注入，供卸载时反查 pipeline。 */
    volatile ChannelHandlerContext context;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        context = ctx;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!TransactionDelayFeature.capture(this, ctx, msg, promise)) {
            ctx.write(msg, promise);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        TransactionDelayFeature.connectionClosed(this);
        super.channelInactive(ctx);
    }
}
