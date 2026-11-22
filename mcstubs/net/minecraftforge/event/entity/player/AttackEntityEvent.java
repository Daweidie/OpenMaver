package net.minecraftforge.event.entity.player;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/**
 * 编译期 stub：Forge 1.8.9 的 {@code AttackEntityEvent} 实际继承 PlayerEvent，
 * 运行期由 Forge 提供。这里只需要 {@code entityPlayer} / {@code target} 两个字段，
 * 字段解析会沿真实父类链找到 PlayerEvent 的 {@code entityPlayer}。
 */
public class AttackEntityEvent {
    public final EntityPlayer entityPlayer;
    public final Entity target;

    public AttackEntityEvent(EntityPlayer player, Entity target) {
        this.entityPlayer = player;
        this.target = target;
    }
}
