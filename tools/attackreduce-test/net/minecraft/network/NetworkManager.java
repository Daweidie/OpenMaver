package net.minecraft.network;

import io.netty.channel.Channel;

/** 测试替身：字段名/类型与真实 1.8.9 NetworkManager 一致（channel 字段给管线注入用）。 */
public class NetworkManager {
    private Channel channel;

    public void setChannel(Channel channel) {
        this.channel = channel;
    }
}
