package io.netty.channel;

/**
 * 编译期 stub：Netty {@code ChannelFuture}。
 *
 * <p><b>注意版本差异</b>：MC 1.8.9 自带 netty-all 4.0.23.Final，其中
 * {@code ChannelHandlerContext.write(Object, ChannelPromise)} 返回的是
 * {@code ChannelFuture}（4.1 起改成返回 {@code ChannelHandlerContext}）。
 * 本工程的管线注入只面向 1.8.9，因此按 4.0 的签名声明。
 */
public interface ChannelFuture {

    boolean isSuccess();

    boolean isDone();

    Throwable cause();
}
