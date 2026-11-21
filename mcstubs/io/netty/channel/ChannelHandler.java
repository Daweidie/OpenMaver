package io.netty.channel;

/**
 * 编译期 stub：Netty {@code ChannelHandler}。
 *
 * <p>只有 {@code mc/} 需要 packet pipeline 注入（AttackReduce 的 blink / 抗 Knockback
 * 拦截）时会用到 Netty；仓库内没有真实 Netty 依赖，因此这里放一个最小可编译的
 * 声明。编译产物运行时由 MineCraft 自带的 netty-all 提供同名类，FQN 一致即可
 * 正常链接。**仅编译期使用，不进 jar。**
 */
public interface ChannelHandler {
}
