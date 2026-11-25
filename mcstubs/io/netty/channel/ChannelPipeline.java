package io.netty.channel;

import java.util.List;

/**
 * 编译期 stub：Netty {@code ChannelPipeline}。
 *
 * <p>只保留注入/卸载 handler 与枚举管线名称所需的方法。
 */
public interface ChannelPipeline {

    ChannelPipeline addBefore(String baseName, String name, ChannelHandler handler);

    ChannelPipeline addLast(String name, ChannelHandler handler);

    ChannelPipeline addFirst(String name, ChannelHandler handler);

    ChannelHandler remove(String name);

    ChannelPipeline remove(ChannelHandler handler);

    ChannelHandlerContext context(String name);

    List<String> names();

    Channel channel();
}
