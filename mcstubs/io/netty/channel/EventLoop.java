package io.netty.channel;

/**
 * 编译期 stub：Netty {@code EventLoop}。
 *
 * <p>只用于「把工作排回 I/O 线程」这一件事：主线程要放行被扣住的入站包时，
 * 必须回到 channel 自己的 eventLoop 上执行，否则 {@code fireChannelRead}
 * 会在主线程里跑 MC 的包处理器。
 */
public interface EventLoop {

    void execute(Runnable task);
}
