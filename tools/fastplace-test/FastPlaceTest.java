import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import module.ModuleManager;
import module.player.FastPlace;
import net.minecraft.client.Minecraft;

/** Exercise the real FastPlace implementation through MCP, SRG and obf fixtures. */
public final class FastPlaceTest {
    private static int passed;
    private static int failed;
    private static final Method UPDATE;
    static {
        try {
            UPDATE = OpenMaverFeatures.class.getDeclaredMethod("updateFastPlace", Minecraft.class);
            UPDATE.setAccessible(true);
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    public static class Stack {
        public int stackSize = 64;
        public Object item = new net.minecraft.item.ItemBlock();
        public Object getItem() { return item; }
    }
    public static class BlockVariant extends net.minecraft.item.ItemBlock {}
    public static class Player {
        public Stack stack = new Stack();
        public boolean living = true;
        public boolean isEntityAlive() { return living; }
        public Stack getHeldItem() { return stack; }
    }
    public static class Mcp extends Minecraft {
        public Player thePlayer = new Player();
        public Object theWorld = new Object();
        public Object currentScreen;
        public int rightClickDelayTimer = 4;
    }
    public static class SrgStack {
        public int field_77994_a = 64;
        public Object func_77973_b() { return new net.minecraft.item.ItemBlock(); }
    }
    public static class SrgPlayer {
        public boolean func_70089_S() { return true; }
        public SrgStack func_70694_bm() { return new SrgStack(); }
    }
    public static class Srg extends Minecraft {
        public SrgPlayer field_71439_g = new SrgPlayer();
        public Object field_71441_e = new Object();
        public Object field_71462_r;
        public int field_71467_ac = 4;
    }
    // Names below come from Forge's deobfuscation_data-1.8.9.lzma.
    public static class ObfStack {
        public int b = 64;
        public Object b() { return new yo(); }
    }
    public static class ObfPlayer {
        public boolean ai() { return true; }
        public ObfStack bA() { return new ObfStack(); }
    }
    public static class Obf extends Minecraft {
        public Object f = new Object();
        public ObfPlayer h = new ObfPlayer();
        public Object i = new Object(); // renderViewEntity, not thePlayer
        public Object m; // currentScreen, not h
        public int ap = 4;
    }

    private static void set(String name, Object value) throws Exception {
        Field field = OpenMaverFeatures.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
    private static void configure(int profile, int delay) throws Exception {
        configure(profile, delay, false);
    }
    private static void configure(int profile, int delay, boolean jitter) throws Exception {
        set("bypassEnabled", true);
        set("bypassProfile", profile);
        set("fastPlaceDelayTicks", delay);
        set("fastPlaceOnlyBlocks", true);
        set("fastPlaceJitter", jitter);
        set("fastPlaceJitterTick", 0);
        set("fastPlaceWasActive", false);
        set("fastPlaceFailed", false);
        set("fastPlaceLastEnabled", false);
    }
    private static void update(Minecraft mc) throws Exception { UPDATE.invoke(null, mc); }
    private static void check(String name, boolean ok) {
        System.out.println("[fastplace-test] " + (ok ? "PASS " : "FAIL ") + name);
        if (ok) passed++; else failed++;
    }

    public static void main(String[] args) throws Exception {
        Path settings = Files.createTempFile("openmaver-fastplace-test-", ".properties");
        System.setProperty("openmaver.features", settings.toString());
        FastPlace module = new FastPlace();
        ModuleManager.register(module);
        module.setEnabled(true);
        try {
            configure(2, 1);
            Mcp mcp = new Mcp();
            update(mcp);
            check("MCP aggressive reduces 4 -> 1", mcp.rightClickDelayTimer == 1);
            mcp.thePlayer.stack.item = new BlockVariant();
            mcp.rightClickDelayTimer = 4;
            update(mcp);
            check("ItemBlock subclasses are recognized", mcp.rightClickDelayTimer == 1);
            final byte[] blockBytes = Files.readAllBytes(java.nio.file.Paths.get(args[0],
                    "net/minecraft/item/ItemBlock.class"));
            Class<?> isolatedBlock = new ClassLoader(null) {
                Class<?> block() {
                    return defineClass("net.minecraft.item.ItemBlock", blockBytes, 0, blockBytes.length);
                }
            }.block();
            mcp.thePlayer.stack.item = isolatedBlock.getDeclaredConstructor().newInstance();
            mcp.rightClickDelayTimer = 4;
            update(mcp);
            check("block from isolated game loader reduces 4 -> 1", mcp.rightClickDelayTimer == 1);
            mcp.thePlayer.stack.item = new net.minecraft.item.ItemBlock();
            configure(2, 0);
            mcp.rightClickDelayTimer = 4;
            update(mcp);
            check("MCP aggressive permits zero delay", mcp.rightClickDelayTimer == 0);
            check("HUD reports the effective delay", "0t".equals(OpenMaverFeatures.getFastPlaceStatus()));
            mcp.currentScreen = new Object();
            update(mcp);
            check("opening GUI restores vanilla cooldown", mcp.rightClickDelayTimer == 4);
            check("GUI suspension is visible", "GUI".equals(OpenMaverFeatures.getFastPlaceStatus()));
            mcp.currentScreen = null;
            mcp.thePlayer.stack.item = new Object();
            update(mcp);
            check("ONLY BLOCKS excludes non-block items", mcp.rightClickDelayTimer == 4);
            check("block filter suspension is visible", "NO BLOCK".equals(OpenMaverFeatures.getFastPlaceStatus()));
            mcp.thePlayer.stack.item = new net.minecraft.item.ItemBlock();
            mcp.thePlayer.stack.stackSize = 0;
            update(mcp);
            check("empty block stack stays inactive", mcp.rightClickDelayTimer == 4);
            mcp.thePlayer.stack.stackSize = 64;
            mcp.thePlayer.living = false;
            update(mcp);
            check("dead player stays inactive", mcp.rightClickDelayTimer == 4);
            mcp.thePlayer.living = true;
            configure(1, 0);
            mcp.rightClickDelayTimer = 4;
            update(mcp);
            check("balanced floors configured 0 at 2 ticks", mcp.rightClickDelayTimer == 2);
            check("balanced reports the effective delay", "2t".equals(OpenMaverFeatures.getFastPlaceStatus()));
            configure(1, 4);
            mcp.rightClickDelayTimer = 4;
            update(mcp);
            check("balanced honors an explicit vanilla delay", mcp.rightClickDelayTimer == 4);
            check("balanced still reports vanilla speed when asked", "VANILLA 4t".equals(OpenMaverFeatures.getFastPlaceStatus()));
            configure(1, 2);
            mcp.rightClickDelayTimer = 4;
            update(mcp);
            check("balanced honors configured 2 ticks", mcp.rightClickDelayTimer == 2);
            configure(1, 0, true);
            int[] jittered = new int[8];
            for (int i = 0; i < jittered.length; i++) {
                mcp.rightClickDelayTimer = 4;
                update(mcp);
                jittered[i] = mcp.rightClickDelayTimer;
            }
            boolean sawTwo = false;
            boolean sawThree = false;
            boolean inRange = true;
            for (int value : jittered) {
                if (value == 2) sawTwo = true;
                else if (value == 3) sawThree = true;
                else inRange = false;
            }
            check("jitter alternates 2/3 ticks", sawTwo && sawThree && inRange);
            check("jitter keeps the reported base delay stable", "2t".equals(OpenMaverFeatures.getFastPlaceStatus()));
            configure(0, 0);
            mcp.rightClickDelayTimer = 4;
            update(mcp);
            check("legit preserves vanilla delay", mcp.rightClickDelayTimer == 4);
            check("legit suspension is visible", "LEGIT".equals(OpenMaverFeatures.getFastPlaceStatus()));
            set("bypassEnabled", false);
            mcp.rightClickDelayTimer = 4;
            update(mcp);
            check("disabled bypass honors DELAY even in legit", mcp.rightClickDelayTimer == 0);
            configure(0, 0);
            set("fastPlaceFailed", true);
            module.setEnabled(false);
            update(mcp);
            check("toggling off clears the permanent error latch", !OpenMaverFeatures.getFastPlaceStatus().equals("ERROR"));
            module.setEnabled(true);
            mcp.rightClickDelayTimer = 4;
            update(mcp);
            check("re-enabling re-arms FastPlace after an error", mcp.rightClickDelayTimer == 4);
            check("re-armed FastPlace leaves the legit profile alone", "LEGIT".equals(OpenMaverFeatures.getFastPlaceStatus()));
            module.setEnabled(false);
            update(mcp);
            check("disabling module restores vanilla cooldown", mcp.rightClickDelayTimer == 4);
            module.setEnabled(true);
            configure(2, 1);
            Srg srg = new Srg();
            update(srg);
            check("SRG aggressive reduces 4 -> 1", srg.field_71467_ac == 1);
            srg.field_71467_ac = 0;
            update(srg);
            check("active FastPlace never raises a ready timer", srg.field_71467_ac == 0);
            configure(2, 1);
            Obf obf = new Obf();
            update(obf);
            check("obf aggressive reduces 4 -> 1", obf.ap == 1);
            check("obf local player resolves to h", OpenMaverFeatures.read(obf,
                    "thePlayer", "field_71439_g", null) == obf.h);
            check("obf currentScreen resolves to m", OpenMaverFeatures.read(obf,
                    "currentScreen", "field_71462_r", null) == null);
            check("obf alive uses ai", OpenMaverFeatures.alive(obf.h));
            check("obf held block is recognized", OpenMaverFeatures.holdingBlock(obf.h));
            System.out.println("[fastplace-test] checks=" + (passed + failed)
                    + " passed=" + passed + " failed=" + failed);
        } finally {
            module.setEnabled(false);
            Files.deleteIfExists(settings);
        }
        if (failed != 0) System.exit(1);
    }
}

/** ItemBlock's verified 1.8.9 obfuscated class name. */
class yo {}
