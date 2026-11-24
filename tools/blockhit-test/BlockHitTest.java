import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import module.ModuleManager;
import module.combat.BlockHit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import org.lwjgl.input.Mouse;

/** Exercise the actual Feature with a controller recording use/release calls. */
public final class BlockHitTest {
    private static final BlockHit module = new BlockHit();
    private static int passed;

    public static void main(String[] args) {
        ModuleManager.register(module);
        module.setEnabled(true);
        Fixture f = new Fixture();
        f.attack();
        check("attack callback does not use an item inside the attack", f.mc.playerController.calls.isEmpty());
        f.finishStart();
        check("controller use starts a real client use state", f.player.using == f.player.held && f.key.pressed);
        check("controller receives the current player, world and stack", f.mc.playerController.calls.equals(Arrays.asList("C08")));
        f.start();
        check("block remains held for the configured duration", f.player.using != null && f.key.pressed);
        f.start();
        check("expiry sends release and clears local use and key", f.player.using == null && !f.key.pressed
                && f.mc.playerController.calls.equals(Arrays.asList("C08", "C07")));
        f.start();
        check("idle ticks do not spam use/release", f.mc.playerController.calls.size() == 2);
        f.enemy.isSwingInProgress = false; f.start();
        f.enemy.isSwingInProgress = true;
        f.attack(); f.finishStart();
        check("the following attack blocks only after the prior release",
                f.mc.playerController.calls.equals(Arrays.asList("C08", "C07", "C08")));

        // Manual attacks occur later in vanilla runTick; their use starts next START.
        f = new Fixture();
        f.attack();
        f.start();
        check("manual attack is consumed on the following tick START", f.player.using == f.player.held);

        f = new Fixture();
        f.enemy.isSwingInProgress = false;
        f.attack(); f.finishStart();
        check("an outgoing attack alone never blocks", f.mc.playerController.calls.isEmpty());
        f.enemy.isSwingInProgress = true;
        f.start();
        check("a later enemy counterattack can start a block", f.player.using == f.player.held);
        f.enemy.swingProgressInt = 3;
        f.start();
        check("the end of the threat releases before maximum hold duration", f.player.using == null);

        f = new Fixture();
        f.attack(); f.finishStart();
        for (int i = 0; i < 5; i++) f.start();
        f.attack(); f.finishStart();
        check("one enemy swing cannot retrigger block even after another click", f.mc.playerController.calls.size() == 2);

        f = new Fixture();
        f.enemy.isSwingInProgress = false;
        f.attack(); f.finishStart();
        f.enemy.hurtTime = 10; f.enemy.hp = 16; f.start();
        f.enemy.isSwingInProgress = true;
        f.start();
        check("confirmed hit and enemy hurt animation preserve offensive movement", f.mc.playerController.calls.isEmpty());
        f.enemy.hurtTime = 0; f.enemy.isSwingInProgress = false;
        for (int i = 0; i < 10; i++) f.start();
        f.attack(); f.finishStart();
        f.enemy.isSwingInProgress = true;
        f.start();
        check("next attack is protected while waiting for its hit acknowledgment", f.mc.playerController.calls.isEmpty());
        f.enemy.hurtTime = 10; f.enemy.hp = 12; f.start();
        f.enemy.hurtTime = 0;
        for (int i = 0; i < 7; i++) f.start();
        check("two confirmed unanswered hits suppress block throughout a combo", f.mc.playerController.calls.isEmpty());
        f.player.hurtTime = 10; f.player.hurtResistantTime = 20; f.start();
        check("return damage does not block during own immunity", f.mc.playerController.calls.isEmpty());
        f.player.hurtTime = 0; f.player.hurtResistantTime = 10;
        f.enemy.isSwingInProgress = false; f.start();
        f.enemy.isSwingInProgress = true; f.start();
        check("return damage ends combo protection and a fresh threat may block", f.player.using == f.player.held);

        f = new Fixture();
        f.enemy.isSwingInProgress = false;
        for (int i = 0; i < 4; i++) { f.attack(); f.start(); }
        f.enemy.isSwingInProgress = true; f.start();
        check("missed clicks do not count as confirmed combo hits", f.player.using == f.player.held);

        for (int condition = 0; condition < 7; condition++) {
            f = new Fixture();
            if (condition == 0) f.enemy.rotationYaw = 0;
            if (condition == 1) f.enemy.posZ = 3.31;
            if (condition == 2) f.enemy.visible = false;
            if (condition == 3) f.enemy.swingProgressInt = 3;
            if (condition == 4) f.player.hurtTime = 1;
            if (condition == 5) f.player.hurtResistantTime = 11;
            if (condition == 6) f.enemy.posY = 5;
            f.attack(); f.finishStart();
            check("reject missing counterattack condition " + condition, f.mc.playerController.calls.isEmpty());
        }

        f = blocking();
        f.enemy.hurtTime = 10;
        f.start();
        check("a hit acknowledgment immediately releases an owned block", f.player.using == null && !f.key.pressed);

        f = new Fixture();
        f.enemy.isSwingInProgress = false; f.attack(); f.finishStart();
        for (int i = 0; i < 21; i++) f.start();
        f.enemy.isSwingInProgress = true; f.start();
        check("an old opponent cannot cause unrelated later blocks", f.mc.playerController.calls.isEmpty());

        f = new Fixture();
        CancellableAttack canceled = f.attack();
        canceled.canceled = true;
        f.finishStart();
        check("a later Forge cancellation discards the queued block", f.mc.playerController.calls.isEmpty());

        f = new Fixture();
        f.player.held = new Stack(new Object());
        f.attack(); f.finishStart();
        check("food/tools/empty hands do not trigger block", f.mc.playerController.calls.isEmpty());

        f = blocking();
        module.setEnabled(false);
        f.start();
        check("disabling releases owned use", !f.key.pressed && f.player.using == null && f.mc.playerController.calls.size() == 2);
        module.setEnabled(true);

        f = blocking();
        f.mc.currentScreen = new GuiScreen();
        f.start();
        check("opening a GUI releases block", !f.key.pressed && f.player.using == null);

        f = blocking();
        f.mc.inGameHasFocus = false;
        f.start();
        check("losing focus releases block", !f.key.pressed && f.player.using == null);

        f = blocking();
        f.player.held = new Stack(new ItemSword());
        f.start();
        check("switching stacks releases the old sword", !f.key.pressed && f.player.using == null);

        f = new Fixture();
        f.attack();
        f.player.held = new Stack(new ItemSword());
        f.finishStart();
        check("a queued attack does not block with a different sword", f.mc.playerController.calls.isEmpty());

        f = blocking();
        f.player.using = new Stack(new Object());
        f.start();
        check("cleanup does not stop a different item in use", f.player.using != null && f.mc.playerController.calls.size() == 1);

        f = blocking();
        f.mc.theWorld = new World();
        f.start();
        check("world changes clear local use without sending into the new connection",
                !f.key.pressed && f.player.using == null && f.mc.playerController.calls.size() == 1);

        f = new Fixture();
        f.player.using = f.player.held;
        f.key.pressed = true;
        f.attack(); f.finishStart();
        check("manual item use remains untouched", f.player.using != null && f.key.pressed && f.mc.playerController.calls.isEmpty());

        f = blocking();
        Mouse.down = true;
        f.start();
        check("pressing the real use key takes over without an unwanted C07",
                f.player.using != null && f.key.pressed && f.mc.playerController.calls.size() == 1);
        Mouse.down = false;
        f.player.using = null; f.key.pressed = false;

        f = new Fixture();
        f.mc.playerController.reject = true;
        f.attack(); f.finishStart();
        check("failed use does not leave the key stuck", f.player.using == null && !f.key.pressed);

        Properties values = new Properties();
        values.setProperty("blockhit.holdTicks", "99");
        BlockHitFeature.load(values);
        check("saved duration is bounded", BlockHitFeature.getHoldTicks() == 5);
        values.setProperty("blockhit.holdTicks", "invalid");
        BlockHitFeature.load(values);
        check("invalid duration restores the default", BlockHitFeature.getHoldTicks() == 2);
        check("duration persists with other feature settings", BlockHitFeature.save().equals("blockhit.holdTicks=2\n"));
        System.out.println("blockhit-test: " + passed + " passed");
    }

    private static Fixture blocking() {
        Fixture f = new Fixture();
        f.attack(); f.finishStart();
        return f;
    }

    private static void check(String message, boolean ok) {
        if (!ok) throw new AssertionError(message);
        passed++;
    }

    private static final class Fixture {
        final TestMinecraft mc = new TestMinecraft();
        final Player player = new Player();
        final Enemy enemy = new Enemy();
        final Key key = new Key();
        Fixture() {
            // Discard ownership and pending work left by the previous fixture.
            module.setEnabled(false);
            BlockHitFeature.beforeTick(mc);
            module.setEnabled(true);
            mc.thePlayer = player;
            mc.theWorld = new World();
            Settings settings = new Settings();
            settings.keyBindUseItem = key;
            mc.gameSettings = settings;
            start();
        }
        CancellableAttack attack() {
            CancellableAttack event = new CancellableAttack(player, enemy);
            BlockHitFeature.onAttack(mc, event);
            return event;
        }
        void start() { BlockHitFeature.beforeTick(mc); finishStart(); }
        void finishStart() { BlockHitFeature.afterTickStart(mc); }
    }

    public static final class TestMinecraft extends Minecraft {
        public Controller playerController = new Controller();
        public boolean inGameHasFocus = true;
    }
    public static final class Settings extends GameSettings {
        public Key keyBindUseItem;
    }
    public static final class Key {
        public boolean pressed;
        public int func_151463_i() { return -99; }
    }
    public static final class Stack extends ItemStack {
        final Object item;
        Stack(Object item) { this.item = item; }
        public Object func_77973_b() { return item; }
    }
    public static final class Player extends EntityPlayerSP {
        public int hurtTime, hurtResistantTime;
        ItemStack held = new Stack(new ItemSword());
        ItemStack using;
        @Override public ItemStack getHeldItem() { return held; }
        public boolean func_71039_bw() { return using != null; }
        public ItemStack func_71011_bu() { return using; }
        public void func_71034_by() { using = null; }
        @Override public AxisAlignedBB getEntityBoundingBox() { return new AxisAlignedBB(-0.3, 0, -0.3, 0.3, 1.8, 0.3); }
    }
    public static final class Enemy extends EntityLivingBase {
        public int hurtTime, swingProgressInt;
        public boolean isSwingInProgress = true;
        boolean visible = true;
        float hp = 20;
        Enemy() { posZ = 2.5; rotationYaw = 180; }
        @Override public float getEyeHeight() { return 1.62F; }
        @Override public float getHealth() { return hp; }
        @Override public boolean canEntityBeSeen(net.minecraft.entity.Entity entity) { return visible; }
    }
    public static final class Controller {
        final List<String> calls = new ArrayList<String>();
        boolean reject;
        public boolean func_78769_a(Player player, World world, ItemStack stack) {
            if (reject) return false;
            if (world == null || stack != player.held) throw new AssertionError("wrong use context");
            calls.add("C08");
            player.using = stack;
            // Vanilla's sendUseItem returns false for an unchanged sword stack,
            // despite sending C08 and setting the player's use state.
            return false;
        }
        public void func_78766_c(Player player) {
            calls.add("C07");
            player.using = null;
        }
    }
    public static final class CancellableAttack extends AttackEntityEvent {
        boolean canceled;
        CancellableAttack(Player player, EntityLivingBase target) { super(player, target); }
        public boolean isCanceled() { return canceled; }
    }
}
