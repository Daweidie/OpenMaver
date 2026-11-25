import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * 出站事务确认（C0F）的延迟钩子。
 *
 * <p>只拦截 {@code C0FPacketConfirmTransaction}：命中时交给
 * {@link TransactionDelayFeature} 暂存，其余包原样写出。S08/S07 处理前后在
 * 主线程和 Netty 队列中排边界任务，保证传送确认不会越过更早的事务回执。
 */
final class TransactionDelayHook extends ChannelDuplexHandler {
    /** 安装后由 Netty 注入，供卸载时反查 pipeline。 */
    volatile ChannelHandlerContext context;
    /** 仅由该连接的 Netty event loop 读写，可嵌套处理同一批传送。 */
    int correctionDepth;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean correction = TransactionDelayFeature.isCorrection(msg);
        if (correction) TransactionDelayFeature.correctionBoundary(this, ctx, true);
        try {
            ctx.fireChannelRead(msg);
        } finally {
            if (correction) TransactionDelayFeature.correctionBoundary(this, ctx, false);
        }
    }

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
