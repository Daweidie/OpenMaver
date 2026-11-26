package net.minecraft.network.play.client;
/** C04/C05/C06 的继承结构与 1.8.9 一致。 */
public class C03PacketPlayer {
    public final double x;
    public double y, z;
    public final boolean onGround;
    public boolean moving;
    public float yaw, pitch;
    public boolean rotating;
    public C03PacketPlayer(double x) { this.x=x; this.onGround=false; }
    public C03PacketPlayer(boolean onGround) { this.x=0; this.onGround=onGround; }
    public static class C04PacketPlayerPosition extends C03PacketPlayer {
        public C04PacketPlayerPosition() { super(0); moving = true; }
        public C04PacketPlayerPosition(double x, double y, double z, boolean ground) {
            super(x, ground); this.y=y; this.z=z; moving=true;
        }
    }
    public static class C05PacketPlayerLook extends C03PacketPlayer {
        public C05PacketPlayerLook() { this(0, 0, false); }
        public C05PacketPlayerLook(float yaw, float pitch, boolean ground) {
            super(ground); this.yaw=yaw; this.pitch=pitch; rotating=true;
        }
    }
    public static class C06PacketPlayerPosLook extends C03PacketPlayer {
        public C06PacketPlayerPosLook() { this(0, 0, 0, 0, 0, false); }
        public C06PacketPlayerPosLook(double x, double y, double z, float yaw, float pitch, boolean ground) {
            super(x, ground); this.y=y; this.z=z; this.yaw=yaw; this.pitch=pitch;
            moving=true; rotating=true;
        }
    }
    private C03PacketPlayer(double x, boolean ground) { this.x=x; this.onGround=ground; }
}
