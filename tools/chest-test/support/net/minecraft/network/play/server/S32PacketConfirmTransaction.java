package net.minecraft.network.play.server;

/**
 * 测试替身：服务端窗口确认包 {@code S32PacketConfirmTransaction}。
 *
 * <p>真机上客户端每发一次 {@code windowClick}，服务端就回一个 S32 表示「这次点击我认了」。
 * {@code ChestFeature.noteInboundPacket()} 按类名识别它，并读第一个 {@code int} 字段
 * （{@code windowId}）。一件一件拿时，拿下一格要等这个确认。
 */
public class S32PacketConfirmTransaction {

    /** 与真机一致：第一个 int 字段就是窗口号。 */
    public int windowId;
    public short actionNumber;
    public boolean accepted;

    public S32PacketConfirmTransaction(int windowId) {
        this.windowId = windowId;
        this.accepted = true;
    }
}
