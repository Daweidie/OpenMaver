import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

/** Runs real attribution/queue/settings code; outgoing chat is recorded locally. */
public final class TauntTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        MaverPersonalization.load(new Properties());
        check("taunt defaults off with editable gg text", !MaverPersonalization.isTauntEnabled()
                && MaverPersonalization.getTauntText().equals("gg"));
        Fixture f = new Fixture();
        f.chat(new Translation("death.attack.player", new Text("Enemy"), new Text("Local")));
        check("chat callback only queues, never sends synchronously", f.local.sent.isEmpty());
        f.tick();
        check("local attributed player kill sends configured chat", f.local.sent.equals(Arrays.asList("gg")));
        f.chat(new Translation("death.attack.player.item", new Text("Enemy"), new Text("Local"), new Text("Sword")));
        f.advance(25);
        check("duplicate death announcements do not send again", f.local.sent.size() == 1);

        for (String[] message : new String[][] {
                {"Enemy was slain by Other.", "another player's kill"},
                {"Local was slain by Enemy.", "own death"},
                {"Zombie was slain by Local.", "mob or unknown victim"},
                {"<Other> Enemy was slain by Local.", "ordinary chat spoof"},
                {"[VIP] Other: Enemy was slain by Local.", "ranked chat spoof"},
                {"Enemy was slain by LocalExtra.", "partial attacker name"},
                {"Enemy left the game", "despawn or disconnect"},
                {"Local killed Enemy?", "ambiguous text"} }) {
            f = new Fixture();
            f.chat(new Text(message[0])); f.tick();
            check("ignore " + message[1], f.local.sent.isEmpty());
        }
        f = new Fixture();
        f.chat(new Translation("chat.type.text", new Text("Enemy"), new Text("Local")));
        f.tick();
        check("ordinary translated player chat cannot trigger", f.local.sent.isEmpty());
        f.chat(new Translation("death.attack.player", new Text("Enemy"), new Text("Other")));
        f.tick();
        check("translated other-player attribution cannot trigger", f.local.sent.isEmpty());

        String[] recognized = {"Enemy was slain by Local", "Enemy was killed by Local.",
                "Enemy was shot by Local using [Bow].", "Enemy was thrown into the void by Local. FINAL KILL!",
                "Enemy 被 Local 击杀！", "Enemy被Local推入虚空", "§cEnemy§r was killed by §aLocal§r."};
        for (String message : recognized) {
            f = new Fixture();
            f.chat(new Text(message)); f.tick();
            check("recognize server kill announcement " + message, f.local.sent.size() == 1);
        }

        f = new Fixture();
        MaverPersonalization.setTauntText("再见 {target}!");
        f.chat(new Text("Enemy was killed by Local.")); f.tick();
        check("message supports Unicode and target placeholder", f.local.sent.equals(Arrays.asList("再见 Enemy!")));

        f = new Fixture();
        f.chat(new Text("Enemy was killed by Local."));
        f.chat(new Text("Other was killed by Local."));
        f.tick();
        check("multiple kills are queued without a chat burst", f.local.sent.size() == 1);
        f.advance(20);
        check("each distinct kill eventually sends once", f.local.sent.size() == 2);
        f.advance(20);
        f.chat(new Text("Enemy was killed by Local.")); f.tick();
        check("later kills of the same player may taunt again", f.local.sent.size() == 3);

        f = new Fixture();
        Chat canceled = new Chat(new Text("Enemy was killed by Local.")); canceled.canceled = true;
        TauntFeature.onChat(f.mc, canceled); f.tick();
        check("canceled chat is ignored", f.local.sent.isEmpty());
        Chat actionBar = new Chat(new Text("Enemy was killed by Local.")); actionBar.type = 2;
        TauntFeature.onChat(f.mc, actionBar); f.tick();
        check("action bar text is ignored", f.local.sent.isEmpty());

        f = new Fixture();
        f.chat(new Text("Enemy was killed by Local."));
        f.mc.theWorld = new TestWorld(); f.tick();
        check("world changes discard queued chat", f.local.sent.isEmpty());
        f = new Fixture();
        f.chat(new Text("Enemy was killed by Local."));
        f.mc.thePlayer = null; f.tick();
        f.mc.thePlayer = f.local; f.tick();
        check("disconnect and respawn discard queued chat", f.local.sent.isEmpty());
        f = new Fixture();
        f.chat(new Text("Enemy was killed by Local."));
        MaverPersonalization.setTauntEnabled(false); f.tick();
        MaverPersonalization.setTauntEnabled(true); f.tick();
        check("disabling discards queued messages", f.local.sent.isEmpty());
        f = new Fixture();
        MaverPersonalization.setTauntText("  ");
        f.chat(new Text("Enemy was killed by Local.")); f.tick();
        check("empty message sends nothing", f.local.sent.isEmpty());

        f = new Fixture();
        f.world.playerEntities.clear();
        // Cache is bounded, rather than retaining all past server players forever.
        f.advance(201);
        f.chat(new Text("Enemy was killed by Local.")); f.tick();
        check("expired victim names cannot masquerade as players", f.local.sent.isEmpty());

        MaverPersonalization.setTauntText(" 你好\\{target}=!: # 测试");
        String saved = MaverPersonalization.save();
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(saved.getBytes("ISO-8859-1")));
        MaverPersonalization.load(properties);
        check("settings preserve Unicode, slashes and leading spaces", MaverPersonalization.getTauntText()
                .equals(" 你好\\{target}=!: # 测试") && MaverPersonalization.isTauntEnabled());
        MaverPersonalization.setTauntText("a\nb\rc\td§e");
        check("strip controls and formatting marker", MaverPersonalization.getTauntText().equals("abcde"));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 60; i++) longText.appendCodePoint(0x1F600);
        MaverPersonalization.setTauntText(longText.toString());
        String limited = MaverPersonalization.getTauntText();
        check("respect 1.8 chat length without splitting surrogate pairs", limited.length() == 100
                && limited.codePointCount(0, limited.length()) == 50);
        System.out.println("taunt-test: " + passed + " passed");
    }

    private static void check(String message, boolean ok) {
        if (!ok) throw new AssertionError(message);
        passed++;
    }

    private static final class Fixture {
        final Minecraft mc = new Minecraft();
        final Player local = new Player("Local");
        final TestWorld world = new TestWorld();
        Fixture() {
            MaverPersonalization.load(new Properties());
            MaverPersonalization.setTauntEnabled(true);
            mc.thePlayer = local; mc.theWorld = world;
            world.playerEntities.add(local);
            world.playerEntities.add(new NamedPlayer("Enemy"));
            world.playerEntities.add(new NamedPlayer("Other"));
            tick();
        }
        void chat(Object component) { TauntFeature.onChat(mc, new Chat(component)); }
        void tick() { TauntFeature.onTick(mc); }
        void advance(int ticks) { for (int i = 0; i < ticks; i++) tick(); }
    }
    public static final class TestWorld extends World {
        public final List<EntityPlayer> playerEntities = new ArrayList<EntityPlayer>();
    }
    public static final class Player extends EntityPlayerSP {
        final String name;
        final List<String> sent = new ArrayList<String>();
        Player(String name) { this.name = name; }
        @Override public String getName() { return name; }
        public void func_71165_d(String message) { sent.add(message); }
    }
    public static final class NamedPlayer extends EntityPlayer {
        final String name;
        NamedPlayer(String name) { this.name = name; }
        @Override public String getName() { return name; }
    }
    public static class Text {
        final String value;
        Text(String value) { this.value = value; }
        public String func_150260_c() { return value; }
    }
    public static final class Translation extends Text {
        final String key;
        final Object[] args;
        Translation(String key, Object... args) { super(""); this.key = key; this.args = args; }
        public String func_150268_i() { return key; }
        public Object[] func_150271_j() { return args; }
    }
    public static final class Chat extends ClientChatReceivedEvent {
        public final Object message;
        public byte type = 1;
        boolean canceled;
        Chat(Object message) { this.message = message; }
        public boolean isCanceled() { return canceled; }
    }
}
