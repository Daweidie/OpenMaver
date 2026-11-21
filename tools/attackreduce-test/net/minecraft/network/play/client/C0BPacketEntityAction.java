package net.minecraft.network.play.client;

import net.minecraft.entity.Entity;

/** 与 1.8.9 的反射构造签名和 Action 顺序一致。 */
public final class C0BPacketEntityAction {
    public enum Action { START_SNEAKING, STOP_SNEAKING, STOP_SLEEPING, START_SPRINTING, STOP_SPRINTING }
    public final Entity player;
    public final Action action;
    public C0BPacketEntityAction(Entity player, Action action) {
        this.player = player; this.action = action;
    }
}
