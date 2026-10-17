package net.minecraftforge.fml.common.gameevent;
public class TickEvent {
    public enum Phase { START, END }
    public final Phase phase;
    public TickEvent(Phase phase) { this.phase = phase; }
    public static class ClientTickEvent extends TickEvent {
        public ClientTickEvent(Phase phase) { super(phase); }
    }
}
