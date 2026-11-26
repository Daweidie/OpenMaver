package net.minecraft.network.play.client;
public class C0EPacketClickWindow {
    private final int windowId;
    private final short actionNumber;
    public C0EPacketClickWindow() { this(0); }
    public C0EPacketClickWindow(int windowId) { this(windowId, (short) 1); }
    public C0EPacketClickWindow(int windowId, short action) {
        this.windowId = windowId; this.actionNumber = action;
    }
}
