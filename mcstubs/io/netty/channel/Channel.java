package io.netty.channel;

/**
 * 编译期 stub：Netty {@code Channel}。
 *
 * <p>运行期注入时通过 {@code NetworkManager.channel} 反射拿到真实 Channel，
 * 这里只需要 {@link #pipeline()} 与几个状态查询。
 */
public interface Channel {

    ChannelPipeline pipeline();

    boolean isOpen();

    boolean isActive();

    /** 该 channel 绑定的 I/O 线程（主线程排任务回 Netty 时用）。 */
    EventLoop eventLoop();
}
