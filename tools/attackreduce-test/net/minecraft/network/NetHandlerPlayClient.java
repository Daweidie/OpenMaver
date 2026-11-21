package net.minecraft.network;

/** 测试替身：与真实 1.8.9 NetHandlerPlayClient 一样持有 netManager 字段。 */
public class NetHandlerPlayClient {
    private final NetworkManager netManager;
    public java.util.function.Consumer<Object> outbound;
    public int failAt, sent;

    public NetHandlerPlayClient(NetworkManager netManager) {
        this.netManager = netManager;
    }

    public void addToSendQueue(Object packet) {
        if (++sent == failAt) throw new IllegalStateException("simulated send failure");
        if (outbound != null) outbound.accept(packet);
    }
}
