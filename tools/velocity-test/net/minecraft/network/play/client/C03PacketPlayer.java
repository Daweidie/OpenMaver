package net.minecraft.network.play.client;

/** 测试替身：名字与真实 1.8.9 出站移动包一致，用于验证 blink 憋包。 */
public class C03PacketPlayer {
    public final double x;
    public C03PacketPlayer(double x) { this.x = x; }
    public String toString() { return "C03(" + x + ")"; }
}
