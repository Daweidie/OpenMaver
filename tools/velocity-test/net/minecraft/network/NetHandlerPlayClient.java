package net.minecraft.network;

/** 测试替身：与真实 1.8.9 NetHandlerPlayClient 一样持有 netManager 字段。 */
public class NetHandlerPlayClient {
    private final NetworkManager netManager;

    public NetHandlerPlayClient(NetworkManager netManager) {
        this.netManager = netManager;
    }
}
