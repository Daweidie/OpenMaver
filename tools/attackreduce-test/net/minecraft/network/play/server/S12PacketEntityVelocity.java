package net.minecraft.network.play.server;

/** 测试替身：字段名/顺序与真实 1.8.9 SRG S12 一致（field_149417_a, field_149415_b, field_149416_c, field_149414_d）。 */
public class S12PacketEntityVelocity {
    private int field_149417_a;
    private int field_149415_b;
    private int field_149416_c;
    private int field_149414_d;

    public S12PacketEntityVelocity(int field_149417_a, double x, double y, double z) {
        this.field_149417_a = field_149417_a;
        this.field_149415_b = (int) (x * 8000.0D);
        this.field_149416_c = (int) (y * 8000.0D);
        this.field_149414_d = (int) (z * 8000.0D);
    }
    public int getEntityID() { return field_149417_a; }
    public int getMotionX() { return field_149415_b; }
    public int getMotionY() { return field_149416_c; }
    public int getMotionZ() { return field_149414_d; }
    public String toString() { return "S12(" + field_149417_a + ")"; }
}
