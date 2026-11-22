package net.minecraftforge.fml.common.gameevent;

import net.minecraft.entity.player.EntityPlayer;

public class TickEvent {
    public enum Phase { START, END }
    public final Phase phase;
    public TickEvent(Phase phase) { this.phase = phase; }
    public static class ClientTickEvent extends TickEvent {
        public ClientTickEvent(Phase phase) { super(phase); }
    }
    public static class PlayerTickEvent extends TickEvent {
        public final EntityPlayer player;
        public PlayerTickEvent(Phase phase, EntityPlayer player) {
            super(phase);
            this.player = player;
        }
    }
    public static class RenderTickEvent extends TickEvent {
        public final float renderTickTime;
        public RenderTickEvent(Phase phase, float renderTickTime) {
            super(phase);
            this.renderTickTime = renderTickTime;
        }
    }
}
