package net.minecraft.network.play.server;

/**
 * 测试替身：{@code S32PacketConfirmTransaction}。
 *
 * <p>NOXZ 用它做「击退 sandwich 的回执」：挂起期间这条包被扣住，Grim 才会把
 * 这段击退当成「可能还没到」。这里只需要一个可识别类名的实例。
 */
public class S32PacketConfirmTransaction {
    private final short windowId;
    private final short actionNumber;

    public S32PacketConfirmTransaction() {
        this((short) 1, (short) 1);
    }

    public S32PacketConfirmTransaction(short windowId, short actionNumber) {
        this.windowId = windowId;
        this.actionNumber = actionNumber;
    }

    public short func_148887_f() {
        return this.actionNumber;
    }

    public String toString() {
        return "S32(" + this.windowId + "," + this.actionNumber + ")";
    }
}
