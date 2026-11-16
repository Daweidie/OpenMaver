package io.netty.channel;

/**
 * 编译期 stub：Netty {@code ChannelDuplexHandler}。
 *
 * <p>MC 1.8.9 的 {@code NetworkManager} 管线里插入自定义 handler 时必须继承
 * 真实的 {@code ChannelDuplexHandler}。本 stub 让 {@code mc/} 在**没有 Netty
 * 依赖**的情况下也能编译；运行时由游戏内的 netty-all 提供同名父类。
 *
 * <p>方法签名与 netty 4.0.x 完全一致：{@code channelRead(ChannelHandlerContext, Object)}
 * 与 {@code write(ChannelHandlerContext, Object, ChannelPromise)} 都必须
 * {@code throws Exception}，否则覆写会因抛异常范围变窄而链接失败。
 */
public class ChannelDuplexHandler implements ChannelHandler {

    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    }

    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    }

    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    }

    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    }

    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ctx.fireChannelRead(msg);
    }

    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ctx.write(msg, promise);
    }
}
