import net.minecraft.network.play.server.S3EPacketTeams;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reproduces vanilla's null-team dereference through the real Netty/main-thread path. */
final class TeamPacketGuardTest {
    public static final class World {
        final Scoreboard scoreboard = new Scoreboard();
        public Scoreboard func_96441_U() { return scoreboard; }
    }
    public static final class Scoreboard {
        final Map<String, Team> teams = new HashMap<String, Team>();
        public Team func_96508_e(String name) { return teams.get(name); }
    }
    static final class Team {
        String displayName;
        final Set<String> players = new HashSet<String>();
    }
    static final class Handler {
        final int[] attempts = {0};
        Handler(final ZenAntiKBTest.Fixture f) {
            f.player.sendQueue.field_147300_g = new World();
            final java.util.function.Consumer<Object> original = f.player.sendQueue.inbound;
            f.player.sendQueue.inbound = packet -> {
                ZenAntiKBTest.check("team state stays on main thread", Thread.currentThread() == f.main);
                if (packet instanceof S3EPacketTeams) {
                    attempts[0]++;
                    apply((S3EPacketTeams) packet, world(f).scoreboard);
                }
                original.accept(packet);
            };
        }
    }
    static World world(ZenAntiKBTest.Fixture f) { return (World) f.player.sendQueue.field_147300_g; }
    static void apply(S3EPacketTeams packet, Scoreboard scoreboard) {
        int action = packet.func_149307_h();
        String name = packet.func_149312_c();
        Team team = scoreboard.func_96508_e(name);
        if (action == 0) {
            if (team != null) throw new IllegalArgumentException("Team already exists");
            team = new Team(); scoreboard.teams.put(name, team);
        }
        // Vanilla handleTeams dereferences a missing team for updates/removal.
        if (action == 0 || action == 2) team.displayName = packet.displayName;
        if (action == 0 || action == 3) team.players.addAll(packet.players);
        if (action == 4) team.players.removeAll(packet.players);
        if (action == 1) {
            team.players.clear(); scoreboard.teams.remove(name);
        }
    }
    static void batch(final ZenAntiKBTest.Fixture f, final Object... packets) throws Exception {
        Thread network = new Thread(() -> {
            for (Object packet : packets) f.channel.writeInbound(packet);
        });
        network.start(); network.join();
    }
    static void removeCompatibilityHook(ZenAntiKBTest.Fixture f) {
        // Packets captured before the always-on hook is installed must also be protected.
        f.channel.pipeline().remove(LegacySoundFeature.NAME);
    }
    static void noReplayFailure() throws Exception {
        Field failure = AttackReduceFeature.class.getDeclaredField("lastReplayFailure");
        failure.setAccessible(true);
        ZenAntiKBTest.check("team packets produce no swallowed replay exceptions", failure.get(null) == null);
    }
    static void run() throws Exception {
        for (int mode = 0; mode < 3; mode++) for (boolean enabled : new boolean[] {false, true})
            for (boolean replay : new boolean[] {false, true}) {
                if (!enabled && replay) continue;
                try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(mode)) {
                    Handler handler = new Handler(f);
                    if (replay) removeCompatibilityHook(f);
                    if (!enabled) {
                        ZenAntiKBTest.MODULE.setEnabled(false); AttackReduceFeature.beforeTick(f.mc);
                    }
                    List<Object> stale = new ArrayList<Object>();
                    for (int action = 1; action <= 4; action++) stale.add(new S3EPacketTeams("gone", action, "", "player"));
                    batch(f, stale.toArray());
                    ZenAntiKBTest.check("Netty does not query or mutate scoreboard", handler.attempts[0] == 0);
                    f.drain();
                    ZenAntiKBTest.check("missing-team operations never reach vanilla: mode=" + mode
                            + " enabled=" + enabled + " replay=" + replay,
                            handler.attempts[0] == 0 && f.applied.isEmpty() && world(f).scoreboard.teams.isEmpty());
                    S3EPacketTeams create = new S3EPacketTeams("live", 0, "original", "a");
                    S3EPacketTeams update = new S3EPacketTeams("live", 2, "updated");
                    S3EPacketTeams add = new S3EPacketTeams("live", 3, "", "b");
                    S3EPacketTeams leave = new S3EPacketTeams("live", 4, "", "a");
                    batch(f, create, update, add, leave);
                    ZenAntiKBTest.check("queued create does not run on Netty", world(f).scoreboard.teams.isEmpty());
                    f.drain();
                    Team team = world(f).scoreboard.func_96508_e("live");
                    ZenAntiKBTest.check("create/update/membership preserve FIFO and original packets",
                            handler.attempts[0] == 4 && f.applied.equals(Arrays.asList(create, update, add, leave))
                            && team != null && "updated".equals(team.displayName)
                            && team.players.equals(new HashSet<String>(Arrays.asList("b"))));
                    S3EPacketTeams remove = new S3EPacketTeams("live", 1, "");
                    batch(f, remove, remove, update); f.drain();
                    ZenAntiKBTest.check("repeated delete and late update are harmless",
                            handler.attempts[0] == 5 && world(f).scoreboard.teams.isEmpty());
                    noReplayFailure();
                }
            }
        transitions(); staleConnections(); handlerReplacement();
    }
    static void transitions() throws Exception {
        for (boolean join : new boolean[] {false, true}) for (boolean replay : new boolean[] {false, true})
            try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_MIX)) {
                new Handler(f);
                if (replay) removeCompatibilityHook(f);
                final java.util.function.Consumer<Object> original = f.player.sendQueue.inbound;
                Object transition = join ? new ZenAntiKBTest.S01PacketJoinGame() : new ZenAntiKBTest.S07PacketRespawn();
                f.player.sendQueue.inbound = packet -> {
                    original.accept(packet);
                    if (packet == transition) {
                        f.mc.theWorld = new World(); f.player.sendQueue.field_147300_g = f.mc.theWorld;
                        ZenAntiKBTest.Player replacement = new ZenAntiKBTest.Player();
                        replacement.sendQueue = f.player.sendQueue; replacement.channel = f.channel;
                        replacement.setEntityId(7); f.mc.thePlayer = replacement;
                    }
                };
                S3EPacketTeams create = new S3EPacketTeams("new-world", 0, "new");
                S3EPacketTeams update = new S3EPacketTeams("new-world", 2, "newer");
                ZenAntiKBTest.S00PacketKeepAlive following = new ZenAntiKBTest.S00PacketKeepAlive();
                batch(f, transition, create, update, following); f.drain();
                ZenAntiKBTest.check("same-connection S01/S07 preserves new-world packets: join=" + join,
                        f.applied.equals(Arrays.asList(transition, create, update, following))
                        && "newer".equals(world(f).scoreboard.func_96508_e("new-world").displayName));
                noReplayFailure();
            }
    }
    static void staleConnections() throws Exception {
        for (boolean disconnect : new boolean[] {false, true})
            try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
                Handler handler = new Handler(f);
                batch(f, new S3EPacketTeams("stale", 0, ""));
                if (disconnect) f.channel.close();
                else {
                    ZenAntiKBTest.Player replacement = new ZenAntiKBTest.Player();
                    replacement.sendQueue = new net.minecraft.network.NetHandlerPlayClient(new net.minecraft.network.NetworkManager());
                    f.mc.thePlayer = replacement; f.mc.theWorld = new World();
                }
                f.drain();
                ZenAntiKBTest.check("queued team packet cannot affect a replacement/closed connection",
                        handler.attempts[0] == 0 && f.applied.isEmpty());
            }
    }
    static void handlerReplacement() throws Exception {
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_JUMP_RESET)) {
            Handler old = new Handler(f);
            ZenAntiKBTest.MODULE.setEnabled(false); AttackReduceFeature.beforeTick(f.mc);
            batch(f, new S3EPacketTeams("old", 0, ""));
            net.minecraft.network.NetworkManager manager = new net.minecraft.network.NetworkManager();
            manager.setChannel(f.channel);
            net.minecraft.network.NetHandlerPlayClient replacement = new net.minecraft.network.NetHandlerPlayClient(manager);
            replacement.field_147300_g = new World();
            replacement.inbound = packet -> {
                apply((S3EPacketTeams) packet, ((World) replacement.field_147300_g).scoreboard);
                f.applied.add(packet);
            };
            f.player.sendQueue = replacement;
            LegacySoundFeature.onTick(f.mc);
            S3EPacketTeams fresh = new S3EPacketTeams("fresh", 0, "");
            batch(f, fresh); f.drain();
            ZenAntiKBTest.check("same-channel handler replacement rebinds compatibility and drops queued old teams",
                    old.attempts[0] == 0 && f.applied.equals(Arrays.asList(fresh))
                    && world(f).scoreboard.teams.size() == 1 && world(f).scoreboard.func_96508_e("fresh") != null);
        }
    }
    public static void main(String[] args) throws Exception {
        module.ModuleManager.register(ZenAntiKBTest.MODULE);
        module.ModuleManager.register(ZenAntiKBTest.AURA);
        run();
        System.out.println("TeamPacketGuardTest: " + ZenAntiKBTest.passed + " passed, 0 failed");
    }
}
