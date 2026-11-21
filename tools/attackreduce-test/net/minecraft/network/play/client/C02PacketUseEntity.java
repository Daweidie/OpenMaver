package net.minecraft.network.play.client;
import net.minecraft.entity.Entity;
public class C02PacketUseEntity {
    public enum Action { INTERACT, ATTACK, INTERACT_AT }
    public final Entity target;
    private final Action action;
    public C02PacketUseEntity(Entity target, Action action) { this.target=target; this.action=action; }
    public Action getAction() { return action; }
}
