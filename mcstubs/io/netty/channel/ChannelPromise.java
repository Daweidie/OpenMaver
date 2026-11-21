package io.netty.channel;

/**
 * 编译期 stub：Netty {@code ChannelPromise}。
 *
 * <p>签名按 netty 4.0.23.Final 声明：{@code setSuccess()} 返回
 * {@code ChannelPromise}（不是 boolean），描述符写成 {@code ()Z} 会在运行期
 * 抛 {@code NoSuchMethodError}。
 */
public interface ChannelPromise {

    ChannelPromise setSuccess();

    ChannelPromise setFailure(Throwable cause);

    boolean tryFailure(Throwable cause);

    boolean isDone();

    boolean isSuccess();
}
