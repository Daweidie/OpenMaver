package io.netty.channel;

/**
 * 编译期 stub：Netty {@code ChannelHandlerContext}。
 *
 * <p>声明 {@code AttackReducePacketHook} 用到的几个方法：入站传递、出站写入、
 * 刷新与新 promise 创建。签名按 **netty 4.0.23.Final**（MC 1.8.9 自带版本）
 * 声明——注意 {@link #write(Object, ChannelPromise)} 返回
 * {@link ChannelFuture} 而不是 {@code ChannelHandlerContext}（4.1 才改的），
 * 用错版本会在运行期抛 {@code NoSuchMethodError}。
 */
public interface ChannelHandlerContext {

    Channel channel();

    ChannelPipeline pipeline();

    String name();

    ChannelHandlerContext fireChannelRead(Object msg);

    ChannelHandlerContext fireChannelInactive();

    ChannelFuture write(Object msg, ChannelPromise promise);

    ChannelHandlerContext flush();

    ChannelPromise newPromise();
}
