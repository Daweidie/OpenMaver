package net.minecraft.network.play.client;
/** C04/C05/C06 的继承结构与 1.8.9 一致。 */
public class C03PacketPlayer {
    public final double x;
    public boolean moving;
    public C03PacketPlayer(double x) { this.x=x; }
    public static class C04PacketPlayerPosition extends C03PacketPlayer {
        public C04PacketPlayerPosition() { super(0); moving = true; }
    }
    public static class C05PacketPlayerLook extends C03PacketPlayer {
        public C05PacketPlayerLook() { super(0); }
    }
    public static class C06PacketPlayerPosLook extends C03PacketPlayer {
        public C06PacketPlayerPosLook() { super(0); moving = true; }
    }
}
