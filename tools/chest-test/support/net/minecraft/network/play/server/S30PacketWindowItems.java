package net.minecraft.network.play.server;

import net.minecraft.item.ItemStack;

/**
 * 测试替身：服务端窗口物品包 {@code S30PacketWindowItems}。
 *
 * <p>{@code ChestFeature.noteInboundPacket()} 只按<b>类名</b>识别包，再读它的第一个
 * {@code int} 字段作为窗口号 —— 真机上这个字段就是 {@code windowId}。
 */
public class S30PacketWindowItems {

    /** 与真机一致：第一个 int 字段就是窗口号。 */
    public int windowId;
    public ItemStack[] itemStacks;

    public S30PacketWindowItems(int windowId) {
        this.windowId = windowId;
        this.itemStacks = new ItemStack[63];
    }
}
