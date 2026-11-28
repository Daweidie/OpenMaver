import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import module.Module;
import module.ModuleManager;
import module.player.FastPlace;
import net.minecraft.client.Minecraft;

/** Cooldown behavior and configuration, independent of any real input or graphics. */
public final class FastPlaceTest {
    private static int checks;
    private static FastPlace module;
    public static class Stack {
        public int stackSize = 64;
        public Object item = new net.minecraft.item.ItemBlock();
        public Object getItem() { return item; }
    }
    public static class BlockVariant extends net.minecraft.item.ItemBlock { }
    public static class Player {
        public Stack stack = new Stack();
        public boolean living = true, using;
        public boolean isEntityAlive() { return living; }
        public boolean isUsingItem() { return using; }
        public Stack getHeldItem() { return stack; }
    }
    public static class Key {
        public boolean pressed = true;
        public int pressTime = 6;
    }
    public static class Settings { public Key keyBindUseItem = new Key(); }
    public static class Mcp extends Minecraft {
        public Player thePlayer = new Player();
        public Object theWorld = new Object(), currentScreen;
        public boolean inGameHasFocus = true;
        public Settings gameSettings = new Settings();
        public int rightClickDelayTimer, clicks, observedCooldown;
        public void rightClickMouse() {
            rightClickDelayTimer = 4;
            observedCooldown = rightClickDelayTimer;
            clicks++;
        }
    }
    public static class SrgPlayer {
        public boolean func_70089_S() { return true; }
        public Stack func_70694_bm() { return new Stack(); }
    }
    public static class Srg {
        public SrgPlayer field_71439_g = new SrgPlayer();
        public Object field_71441_e = new Object(), field_71462_r;
        public boolean field_71415_G = true;
    }
    public static class ObfStack {
        public int b = 64;
        public Object b() { return new yo(); }
    }
    public static class ObfPlayer {
        public boolean ai() { return true; }
        public ObfStack bA() { return new ObfStack(); }
    }
    public static class Obf {
        public Object f = new Object(), m;
        public ObfPlayer h = new ObfPlayer();
        public boolean w = true;
    }
    private static void set(String name, Object value) throws Exception {
        Field f = OpenMaverFeatures.class.getDeclaredField(name);
        f.setAccessible(true); f.set(null, value);
    }
    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(name);
        System.out.println("[fastplace-test] PASS " + name);
    }
    private static void paused(String reason, Mcp mc) {
        check(reason + " preserves vanilla cooldown", FastPlaceFeature.useCooldown(4, mc) == 4
                && reason.equals(FastPlaceFeature.getStatus()) && !FastPlaceFeature.isActive(mc));
    }
    private static void config(Path file) throws Exception {
        String legacy = "fastplace.delayTicks=5\nfastplace.cpsMin=60\nfastplace.dcpsMax=20\n"
                + "fastplace.onlyOnClick=false\nfastplace.onlyBlocks=false\nfastplace.jitter=true\n";
        Files.write(file, legacy.getBytes(StandardCharsets.UTF_8));
        OpenMaverFeatures.reloadSettingsNow();
        check("old version migration preserves 5t", OpenMaverFeatures.getFastPlaceDelayTicks() == 5);
        OpenMaverFeatures.flushSettings();
        Properties saved = new Properties();
        try (java.io.InputStream input = Files.newInputStream(file)) { saved.load(input); }
        int count = 0;
        for (String key : saved.stringPropertyNames()) if (key.startsWith("fastplace.")) count++;
        check("only delayTicks is persisted", count == 1 && "5".equals(saved.getProperty("fastplace.delayTicks")));
        for (int ticks = 0; ticks <= 5; ticks++) {
            OpenMaverFeatures.setFastPlaceDelayTicks(ticks);
            OpenMaverFeatures.flushSettings();
            OpenMaverFeatures.setFastPlaceDelayTicks((ticks + 1) % 6);
            OpenMaverFeatures.reloadSettingsNow();
            check("cooldown round-trip " + ticks + "t", OpenMaverFeatures.getFastPlaceDelayTicks() == ticks);
        }
        // Historical migration once rewrote a user's 4t to 1t.
        Files.write(file, "settings.version=20\nfastplace.delayTicks=4\n".getBytes(StandardCharsets.UTF_8));
        OpenMaverFeatures.reloadSettingsNow();
        check("old explicit 4t is preserved", OpenMaverFeatures.getFastPlaceDelayTicks() == 4);
        for (String value : new String[] {"-1", "999", "SAMPLE"}) {
            Files.write(file, ("settings.version=24\nfastplace.delayTicks=" + value + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            OpenMaverFeatures.reloadSettingsNow();
            check("invalid or out-of-range config " + value,
                    OpenMaverFeatures.getFastPlaceDelayTicks() == ("-1".equals(value) ? 0 : "999".equals(value) ? 5 : 1));
        }
        Files.write(file, "settings.version=24\n".getBytes(StandardCharsets.UTF_8));
        OpenMaverFeatures.reloadSettingsNow();
        check("default cooldown is 1t", OpenMaverFeatures.getFastPlaceDelayTicks() == 1);
        OpenMaverFeatures.setFastPlaceDelayTicks(-9);
        check("setter lower bound", OpenMaverFeatures.getFastPlaceDelayTicks() == 0);
        OpenMaverFeatures.setFastPlaceDelayTicks(9);
        check("setter upper bound", OpenMaverFeatures.getFastPlaceDelayTicks() == 5);
    }
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("openmaver-fastplace-", ".properties");
        System.setProperty("openmaver.features", file.toString());
        module = new FastPlace(); ModuleManager.register(module);
        try {
            config(file);
            module.setEnabled(true);
            Mcp mc = new Mcp();
            for (int profile = 0; profile < 3; profile++) {
                set("bypassEnabled", true); set("bypassProfile", profile);
                for (int ticks = 0; ticks <= 5; ticks++) {
                    OpenMaverFeatures.setFastPlaceDelayTicks(ticks);
                    check("profile " + profile + " respects " + ticks + "t",
                            FastPlaceFeature.useCooldown(4, mc) == ticks
                            && (ticks + "t").equals(OpenMaverFeatures.getFastPlaceStatus()));
                }
            }
            set("bypassEnabled", false);
            OpenMaverFeatures.setFastPlaceDelayTicks(2);
            check("bypass off respects cooldown", FastPlaceFeature.useCooldown(4, mc) == 2);
            mc.rightClickDelayTimer = 3;
            for (int i = 0; i < 100; i++) FastPlaceFeature.onTickStart(mc);
            check("ticks never emit clicks or reset remaining cooldown", mc.clicks == 0 && mc.rightClickDelayTimer == 3);
            check("physical input and queued presses are untouched",
                    mc.gameSettings.keyBindUseItem.pressed && mc.gameSettings.keyBindUseItem.pressTime == 6);
            mc.gameSettings.keyBindUseItem.pressed = false;
            check("idle or rebound input is left to vanilla", FastPlaceFeature.useCooldown(4, mc) == 2
                    && !mc.gameSettings.keyBindUseItem.pressed);
            check("timer field is not required by cooldown hook", FastPlaceFeature.useCooldown(4, new Srg()) == 2);
            Obf obf = new Obf();
            check("obfuscated player and block mapping", FastPlaceFeature.useCooldown(4, obf) == 2);
            obf.w = false;
            check("obfuscated focus mapping", FastPlaceFeature.useCooldown(4, obf) == 4
                    && "UNFOCUSED".equals(FastPlaceFeature.getStatus()));
            final byte[] bytes = Files.readAllBytes(Paths.get(args[0], "net/minecraft/item/ItemBlock.class"));
            mc.thePlayer.stack.item = new ClassLoader(null) {
                Class<?> block() { return defineClass("net.minecraft.item.ItemBlock", bytes, 0, bytes.length); }
            }.block().getDeclaredConstructor().newInstance();
            check("isolated game-loader block type", FastPlaceFeature.useCooldown(4, mc) == 2);
            mc.thePlayer.stack.item = new BlockVariant();
            check("block subclasses supported", FastPlaceFeature.useCooldown(4, mc) == 2);
            mc.thePlayer.stack.item = new Object(); paused("NO BLOCK", mc);
            mc.thePlayer.stack = new Stack(); mc.thePlayer.stack.stackSize = 0; paused("NO BLOCK", mc);
            mc.thePlayer.stack = null; paused("NO BLOCK", mc);
            mc.thePlayer.stack = new Stack(); mc.thePlayer.using = true; paused("USING ITEM", mc);
            mc.thePlayer.using = false; mc.currentScreen = new Object(); paused("GUI", mc);
            mc.currentScreen = null; mc.inGameHasFocus = false; paused("UNFOCUSED", mc);
            mc.inGameHasFocus = true; mc.thePlayer.living = false; paused("DEAD", mc);
            mc.thePlayer.living = true; mc.theWorld = null; paused("NO WORLD", mc);
            mc.theWorld = new Object(); mc.thePlayer = null; paused("NO WORLD", mc);
            mc.thePlayer = new Player();
            for (String name : new String[] {"Scaffold", "Telly", "GodBridge", "Scaffold2"}) {
                Module bridge = new Module(name, "movement." + name.toLowerCase()) { };
                ModuleManager.register(bridge); bridge.setEnabled(true); paused("SCAFFOLD", mc); bridge.setEnabled(false);
            }
            FastPlaceFeature.setHooksAvailable(false); paused("NO HOOK", mc);
            FastPlaceFeature.setHooksAvailable(true);
            check("hook recovery resumes cooldown", FastPlaceFeature.useCooldown(4, mc) == 2);
            module.setEnabled(false); paused("OFF", mc);
            check("disable preserves outstanding cooldown and input", mc.rightClickDelayTimer == 3
                    && mc.gameSettings.keyBindUseItem.pressTime == 6);
            module.setEnabled(true);
            check("reenable resumes without input restoration", FastPlaceFeature.useCooldown(4, mc) == 2);
            System.out.println("[fastplace-test] " + checks + " checks passed");
        } finally {
            module.setEnabled(false);
            FastPlaceFeature.setHooksAvailable(true);
            Files.deleteIfExists(file);
        }
    }
}
class yo { }
