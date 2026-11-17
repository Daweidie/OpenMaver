package net.minecraft.network.play.server;

/**
 * 测试替身：服务端关窗包 {@code S2EPacketCloseWindow}。
 *
 * <p>1.8.9 的 {@code NetHandlerPlayClient.handleCloseWindow} 是<b>无条件</b>
 * {@code closeScreen()}：任何 S2E 都会把玩家当前开着的界面关掉，不管窗口号对不对。
 * 所以 {@code ChestPacketHook} 会把「窗口号不是当前窗口」的过期 S2E 拦下来，
 * 这条替身就是给那个判断做回归用的。
 */
public class S2EPacketCloseWindow {

    /** 与真机一致：第一个 int 字段就是窗口号。 */
    public int windowId;

    public S2EPacketCloseWindow(int windowId) {
        this.windowId = windowId;
    }
}
