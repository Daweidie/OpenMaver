package net.minecraft.network.play.server;

public class S2FPacketSetSlot {
    private final int windowId;
    private final int slot;
    public S2FPacketSetSlot(int windowId, int slot) {
        this.windowId = windowId;
        this.slot = slot;
    }
}
