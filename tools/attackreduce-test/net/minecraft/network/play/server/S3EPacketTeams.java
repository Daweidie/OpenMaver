package net.minecraft.network.play.server;

import java.util.Arrays;
import java.util.Collection;
import net.minecraft.network.NetHandlerPlayClient;

/** SRG-only accessors, as used by the Forge 1.8.9 runtime. */
public final class S3EPacketTeams {
    private final String name;
    private final int action;
    public final String displayName;
    public final Collection<String> players;

    public S3EPacketTeams(String name, int action, String displayName, String... players) {
        this.name = name; this.action = action; this.displayName = displayName;
        this.players = Arrays.asList(players);
    }
    public int func_149307_h() { return action; }
    public String func_149312_c() { return name; }
    public void func_148833_a(NetHandlerPlayClient handler) { handler.process(this); }
}
