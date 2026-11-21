import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.util.MovementInput;

/** OpenZen AntiKBMode 的 Java 8 适配；所有模式回调运行于 Minecraft 主线程。 */
abstract class AntiKBMode {
    final Deque<AttackReduceFeature.Packet> packetQueue = new ArrayDeque<AttackReduceFeature.Packet>();
    boolean isSuspending;
    void onEnable() { packetQueue.clear(); isSuspending = false; }
    void onDisable() { onEnable(); }
    abstract void onReceivePacket(AttackReduceFeature.ReceivePacketEvent event);
    void onTick() { }
    void onSprint() { }
    void onStrafe(MovementInput input) { }
    void onMotion() { }
    void onCorrection() { }
    int pendingCount() { return packetQueue.size(); }
    final void flushPackets() {
        AttackReduceFeature.Packet p;
        while ((p = packetQueue.poll()) != null) p.handle();
    }
    void release() {
        AttackReduceFeature.sendMovePackets(); flushPackets(); isSuspending = false;
        AttackReduceFeature.restoreTimer(); AttackReduceFeature.status("RELEASED");
    }
    final void delay(AttackReduceFeature.ReceivePacketEvent event) { packetQueue.add(event.packet); event.cancel(); }
}
