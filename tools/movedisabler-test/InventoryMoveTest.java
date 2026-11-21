import module.ModuleManager;
import module.misc.MoveDisabler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/** Drives the actual shared input hook after a vanilla-style input reset. */
public final class InventoryMoveTest {
    private static final MoveDisabler MODULE = new MoveDisabler();
    private static int passed;

    public static final class Binding {
        public int code;
        public boolean pressed;
        Binding(int code) { this.code = code; }
        public int getKeyCode() { return code; }
        public boolean isKeyDown() { return pressed; }
    }
    public static final class Settings extends GameSettings {
        public final Binding keyBindForward = new Binding(17), keyBindBack = new Binding(31);
        public final Binding keyBindLeft = new Binding(30), keyBindRight = new Binding(32);
        public final Binding keyBindJump = new Binding(57), keyBindSneak = new Binding(42);
    }
    public static final class SrgBinding {
        private final int code;
        SrgBinding(int code) { this.code = code; }
        public int func_151463_i() { return code; }
        public boolean func_151470_d() { return false; }
    }
    public static final class SrgSettings extends GameSettings {
        public final SrgBinding field_74351_w = new SrgBinding(200), field_74368_y = new SrgBinding(208);
        public final SrgBinding field_74370_x = new SrgBinding(203), field_74366_z = new SrgBinding(205);
        public final SrgBinding field_74314_A = new SrgBinding(28), field_74311_E = new SrgBinding(54);
    }
    public static final class Player {
        public MovementInput movementInput;
        Player(GameSettings settings) { movementInput = new MovementInputFromOptions(settings); }
    }
    public static class Inventory extends GuiContainer { }
    public static final class Chest extends GuiContainer { }
    public static final class TextField extends GuiTextField {
        public boolean focused = true;
        @Override public boolean isFocused() { return focused; }
    }
    public static final class SearchInventory extends Inventory {
        private final TextField search = new TextField();
    }
    private static final class Fixture {
        final Minecraft mc = new Minecraft();
        final Settings settings = new Settings();
        final Player player = new Player(settings);
        Fixture() {
            Keyboard.reset(); Display.active = true; Mouse.left = false; Mouse.right = false;
            mc.thePlayer = player; mc.theWorld = new Object(); mc.gameSettings = settings;
            mc.currentScreen = new Inventory();
            MODULE.setEnabled(true);
            // No sendQueue/NetworkManager: movement must work independently of packet setup.
            MoveDisablerFeature.onTick(mc);
            check("shared movement hook installed without packet connection",
                    player.movementInput instanceof ScaffoldMovementInput && !MoveDisablerFeature.isPacketHookInstalled());
        }
        void update() { player.movementInput.updatePlayerMoveState(); }
        void expect(float forward, float strafe, boolean jump, boolean sneak, String label) {
            MovementInput input = player.movementInput;
            check(label, Math.abs(input.moveForward-forward) < 0.0001F
                    && Math.abs(input.moveStrafe-strafe) < 0.0001F && input.jump == jump && input.sneak == sneak);
        }
    }

    public static void main(String[] args) {
        ModuleManager.register(MODULE);
        containers(); screens(); lifecycle(); remapping();
        MODULE.setEnabled(false); MoveDisablerFeature.onTick(null);
        System.out.println("InventoryMoveTest: " + passed + " passed, 0 failed");
    }

    private static void containers() {
        Fixture f = new Fixture();
        Keyboard.setKeyDown(17, true); f.update();
        f.expect(1, 0, false, false, "inventory forward survives vanilla clearing KeyBinding inputs");
        check("movement leaves KeyBinding state unchanged", !f.settings.keyBindForward.pressed);
        Keyboard.setKeyDown(32, true); Keyboard.setKeyDown(57, true); f.update();
        f.expect(1, -1, true, false, "inventory diagonal and jump");
        f.mc.currentScreen = new Chest(); f.update();
        f.expect(1, -1, true, false, "chest movement uses same input hook");
        Keyboard.setKeyDown(42, true); f.update();
        f.expect(.3F, -.3F, true, true, "sneak applies vanilla movement scale");
        Keyboard.reset(); Keyboard.setKeyDown(31, true); Keyboard.setKeyDown(30, true); f.update();
        f.expect(-1, 1, false, false, "backwards and left movement");
        Keyboard.setKeyDown(17, true); Keyboard.setKeyDown(32, true); f.update();
        f.expect(0, 0, false, false, "opposing directions cancel");
        Keyboard.reset(); f.update();
        f.expect(0, 0, false, false, "release clears movement in open container");
    }

    private static void screens() {
        Fixture f = new Fixture(); Keyboard.setKeyDown(17, true);
        Object[] excluded = {new GuiScreen(), new GuiOpenMaverClickGui(null), null};
        for (Object screen : excluded) {
            f.mc.currentScreen = screen; f.update();
            f.expect(0, 0, false, false, "menu, ClickGUI and no screen keep vanilla input");
        }
        f.mc.currentScreen = new SearchInventory(); f.update();
        f.expect(0, 0, false, false, "focused container search blocks movement");
        ((SearchInventory) f.mc.currentScreen).search.focused = false; f.update();
        f.expect(1, 0, false, false, "unfocused text field allows movement");
        Display.active = false; f.update();
        f.expect(0, 0, false, false, "window focus loss clears previous movement");
        Display.active = true; f.update();
        f.expect(1, 0, false, false, "window focus regain resumes held movement");
        f.mc.currentScreen = null; f.settings.keyBindForward.pressed = true; f.update();
        f.expect(1, 0, false, false, "ordinary gameplay uses vanilla KeyBinding input");
    }

    private static void lifecycle() {
        Fixture f = new Fixture(); Keyboard.setKeyDown(17, true); f.update();
        MODULE.setEnabled(false); f.update();
        f.expect(0, 0, false, false, "disable stops container movement on next input update");
        MODULE.setEnabled(true); f.update();
        f.expect(1, 0, false, false, "reenable resumes without replacing shared input again");
        MovementInput hook = f.player.movementInput;
        InventoryMoveFeature.onTick(f.mc);
        check("existing shared input is reused", hook == f.player.movementInput);
        f.mc.theWorld = null; f.update();
        f.expect(0, 0, false, false, "unloaded world suppresses movement");
        f.mc.theWorld = new Object();
        Player next = new Player(f.settings); f.mc.thePlayer = next;
        InventoryMoveFeature.onTick(f.mc); hook.updatePlayerMoveState();
        check("old player input remains inactive", hook.moveForward == 0);
        next.movementInput.updatePlayerMoveState();
        check("respawn installs hook on new player", next.movementInput instanceof ScaffoldMovementInput
                && next.movementInput.moveForward == 1);
        next.movementInput = new MovementInput() { };
        InventoryMoveFeature.onTick(f.mc);
        check("custom mod input is preserved", !(next.movementInput instanceof ScaffoldMovementInput));
    }

    private static void remapping() {
        Fixture f = new Fixture();
        f.settings.keyBindForward.code = 200;
        Keyboard.setKeyDown(17, true); f.update();
        f.expect(0, 0, false, false, "old default key has no effect after rebinding");
        Keyboard.setKeyDown(200, true); f.update();
        f.expect(1, 0, false, false, "rebound movement key is sampled");
        Keyboard.reset(); f.settings.keyBindJump.code = -100; Mouse.left = true; f.update();
        f.expect(0, 0, true, false, "mouse-bound jump is sampled without consuming clicks");
        Mouse.left = false; f.settings.keyBindForward.code = 0; Keyboard.setKeyDown(17, true); f.update();
        f.expect(0, 0, false, false, "unbound key is ignored");
        Keyboard.reset(); f.mc.gameSettings = new SrgSettings();
        Keyboard.setKeyDown(200, true); Keyboard.setKeyDown(205, true); Keyboard.setKeyDown(28, true); f.update();
        f.expect(1, -1, true, false, "SRG settings fields and getKeyCode mapping");
    }

    private static void check(String label, boolean condition) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
}
