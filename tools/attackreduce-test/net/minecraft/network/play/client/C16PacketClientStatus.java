package net.minecraft.network.play.client;
public class C16PacketClientStatus {
    public enum EnumState { PERFORM_RESPAWN, REQUEST_STATS, OPEN_INVENTORY_ACHIEVEMENT }
    private final EnumState status;
    public C16PacketClientStatus(EnumState status) { this.status=status; }
    public EnumState getStatus() { return status; }
}
