package net.minecraft.network.play.client;

import net.minecraft.entity.Entity;

/** 测试替身：C0B 实体动作包，枚举顺序与 1.8.9 协议一致。 */
public final class C0BPacketEntityAction {
    public enum Action {
        START_SNEAKING, STOP_SNEAKING, STOP_SLEEPING,
        START_SPRINTING, STOP_SPRINTING, RIDING_JUMP, OPEN_INVENTORY
    }

    public final Entity entity;
    public final Action action;

    public C0BPacketEntityAction(Entity entity, Action action) {
        this.entity = entity;
        this.action = action;
    }
}
