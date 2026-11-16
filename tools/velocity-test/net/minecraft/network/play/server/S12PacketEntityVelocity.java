package net.minecraft.network.play.server;

/** 测试替身：字段顺序与真实 1.8.9 S12 一致（entityId, motionX, motionY, motionZ）。 */
public class S12PacketEntityVelocity {
    private int entityId;
    private int motionX;
    private int motionY;
    private int motionZ;

    public S12PacketEntityVelocity(int entityId, double x, double y, double z) {
        this.entityId = entityId;
        this.motionX = (int) (x * 8000.0D);
        this.motionY = (int) (y * 8000.0D);
        this.motionZ = (int) (z * 8000.0D);
    }
    public String toString() { return "S12(" + entityId + ")"; }
}
