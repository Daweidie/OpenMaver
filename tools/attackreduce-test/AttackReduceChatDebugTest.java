import module.Module;
import module.ModuleManager;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0APacketAnimation;
import org.lwjgl.input.Mouse;

import java.io.StringReader;
import java.util.Locale;
import java.util.Properties;

/** 比较同一攻击尝试前后速度，复现实战按键冲突，并验证聊天回调隔离。 */
public final class AttackReduceChatDebugTest {
    private static int passed;
    private static final String UNCHANGED = "[Maver] Velocity before(x:0.4000 y:0.3600 z:-0.2000) after(x:0.4000 y:0.3600 z:-0.2000)";
    private static final String REDUCED = "[Maver] Velocity before(x:0.4000 y:0.3600 z:-0.2000) after(x:0.2400 y:0.3600 z:-0.1200)";

    public static void main(String[] args) throws Exception {
        ModuleManager.register(AttackReduceSprintHitTest.MODULE);
        Locale original = Locale.getDefault();
        try (AttackReduceSprintHitTest.Fixture f = new AttackReduceSprintHitTest.Fixture()) {
            Locale.setDefault(Locale.GERMANY);
            reset(f, AttackReduceFeature.MODE_SLAP_ATTACK);
            check("debug defaults on for existing settings", AttackReduceFeature.getChatDebug());
            f.arm();
            check("no premature Slap chat on Netty receive or arm callback", f.player.chatMessages.isEmpty());
            f.tick();
            check("same-attempt before/after uses dot decimals even in German locale", REDUCED.equals(line(f, 0)));
            check("success explains mode and result", line(f, 1).contains("mode=SLAP ATTACK reason=HIT"));
            check("chat adds no network packets", f.channel.readOutbound() instanceof C0APacketAnimation
                    && f.channel.readOutbound() instanceof C02PacketUseEntity && f.channel.readOutbound() == null);
            System.out.println("VERIFIED hit: " + line(f, 0));

            reset(f, AttackReduceFeature.MODE_SLAP_ATTACK);
            Mouse.left = true; f.arm(); f.tick();
            check("holding left leaves all motion unchanged", UNCHANGED.equals(line(f, 0)));
            check("holding left is visible beside the actual gate", line(f, 1).contains("reason=PLAYER BUSY")
                    && line(f, 1).contains("left=true") && line(f, 1).contains("sentSprint=true"));
            System.out.println("VERIFIED held-left: " + line(f, 0));
            System.out.println("VERIFIED held-left reason: " + line(f, 1));
            f.tick(); f.tick(); f.tick(); f.tick();
            check("unchanged blocked state does not flood chat every tick", f.player.chatMessages.size() == 2);
            f.tick();
            check("expiry preserves the real blocked reason", f.player.chatMessages.size() == 4
                    && line(f, 3).contains("reason=PLAYER BUSY") && line(f, 3).contains("expired=true"));
            f.tick();
            check("entire held-left window never sends a Velocity attack", f.channel.readOutbound() == null
                    && near(f.player.motionX, .4) && "EXPIRED".equals(AttackReduceFeature.getStatus()));

            reset(f, AttackReduceFeature.MODE_SLAP_ATTACK);
            Mouse.left = true; f.arm(); f.tick(); Mouse.left = false; f.tick();
            check("releasing left within the same window records the later reduction", f.player.chatMessages.size() == 4
                    && REDUCED.equals(line(f, 2)) && line(f, 3).contains("reason=HIT"));

            reset(f, AttackReduceFeature.MODE_SPRINT_HIT); f.arm(); f.tick();
            check("SprintHit reports native slowdown at the correct point", REDUCED.equals(line(f, 0))
                    && line(f, 1).contains("mode=SPRINT HIT reason=HIT"));

            reset(f, AttackReduceFeature.MODE_SIMPLE);
            AttackReduceFeature.setHorizontal(35); AttackReduceFeature.setVertical(70);
            f.receive(7, .4, .36, -.2);
            check("SIMPLE never prints chat from the inbound handler", f.player.chatMessages.isEmpty());
            f.drain();
            check("SIMPLE displays original and rounded scaled S12 values", line(f, 0).equals(
                    "[Maver] Velocity before(x:0.4000 y:0.3600 z:-0.2000) after(x:0.1400 y:0.2519 z:-0.0700)"));
            check("SIMPLE callback runs after vanilla applies the scaled packet", near(f.player.motionX, .14)
                    && near(f.player.motionY, .251875) && line(f, 1).contains("mode=SIMPLE reason=S12 SCALE"));
            check("SIMPLE chat sends no network packets", f.channel.readOutbound() == null);

            reset(f, AttackReduceFeature.MODE_SLAP_ATTACK);
            AttackReduceFeature.setChatDebug(false); f.arm(); f.tick();
            check("disabling chat preserves reduction behavior", f.player.chatMessages.isEmpty() && near(f.player.motionX, .24));
            Properties saved = new Properties(); saved.load(new StringReader(AttackReduceFeature.save()));
            AttackReduceFeature.setChatDebug(true); AttackReduceFeature.load(saved);
            check("debug toggle round-trips in saved settings", !AttackReduceFeature.getChatDebug());

            reset(f, AttackReduceFeature.MODE_SLAP_ATTACK);
            f.receive(8, .4, .36, -.2); f.drain(); f.tick();
            check("other entities never produce local velocity chat", f.player.chatMessages.isEmpty());
            f.receive(7, 0, .36, 0); f.drain(); f.tick();
            check("vertical-only packet explains why no attack window opens", f.player.chatMessages.size() == 2
                    && line(f, 1).contains("reason=NO HORIZONTAL KB"));

            reset(f, AttackReduceFeature.MODE_SIMPLE); f.receive(7, .4, .36, -.2);
            AttackReduceFeature.setMode(AttackReduceFeature.MODE_SLAP_ATTACK); f.drain();
            check("old-mode SIMPLE callbacks do not print misleading chat", f.player.chatMessages.isEmpty());
            reset(f, AttackReduceFeature.MODE_SIMPLE); f.receive(7, .4, .36, -.2);
            f.mc.theWorld = new Object(); f.drain();
            check("old-world SIMPLE callbacks do not print into a new world", f.player.chatMessages.isEmpty());
            reset(f, AttackReduceFeature.MODE_SIMPLE); f.receive(7, .4, .36, -.2);
            AttackReduceSprintHitTest.MODULE.setEnabled(false); f.drain();
            check("disabled module drops queued chat", f.player.chatMessages.isEmpty());

            reset(f, AttackReduceFeature.MODE_SLAP_ATTACK); f.player.failChat = true; f.arm(); f.tick();
            check("chat rendering failure does not disable or alter reduction", near(f.player.motionX, .24)
                    && "HIT".equals(AttackReduceFeature.getStatus()) && f.player.chatMessages.isEmpty());

            Module criticals = new Module("Criticals", "combat.criticals") { };
            ModuleManager.register(criticals);
            reset(f, AttackReduceFeature.MODE_SLAP_ATTACK); criticals.setEnabled(true); f.arm(); f.tick();
            check("conflicting enabled modules appear alongside OTHER ACTION", UNCHANGED.equals(line(f, 0))
                    && line(f, 1).contains("reason=OTHER ACTION") && line(f, 1).contains("modules=Criticals"));
            criticals.setEnabled(false);

            reset(f, AttackReduceFeature.MODE_SLAP_ATTACK);
            f.player.sprinting = false; f.player.field_175171_bO = false;
            f.player.movementInput.moveForward = -1.0F; f.player.movementInput.moveStrafe = 0.5F;
            f.arm(); f.tick();
            check("Velocity locally requests sprint when only-forward AutoSprint would skip backward input",
                    f.player.sprinting && "REQUEST SPRINT SYNC".equals(AttackReduceFeature.getStatus()));
            check("sprint request reports exact movement/config state", line(f, 1).contains("reason=REQUEST SPRINT SYNC")
                    && line(f, 1).contains("forward=-1.00 strafe=0.50")
                    && line(f, 1).contains("autoSprint=false onlyForward=true"));
            check("request waits for vanilla server sprint sync and leaves motion untouched",
                    near(f.player.motionX, .4) && f.channel.readOutbound() == null);
            f.player.field_175171_bO = true; f.tick();
            check("attack proceeds after vanilla reports sprint sync", near(f.player.motionX, .24)
                    && "HIT".equals(AttackReduceFeature.getStatus()));
            check("reported motion reflects only the attack-time reduction", REDUCED.equals(line(f, 2)));

            reset(f, AttackReduceFeature.MODE_SLAP_ATTACK);
            f.player.movementInput.sneak = true; f.player.sprinting = false;
            f.arm(); f.tick();
            check("sneaking does not request sprint", !f.player.sprinting
                    && "SNEAKING".equals(AttackReduceFeature.getStatus())
                    && line(f, 1).contains("sneak=true"));
            f.player.movementInput.sneak = false;
        } finally {
            Locale.setDefault(original);
        }
        System.out.println("AttackReduceChatDebugTest: " + passed + " passed, 0 failed");
    }

    private static void reset(AttackReduceSprintHitTest.Fixture f, int mode) {
        f.reset(); AttackReduceFeature.setMode(mode);
    }
    private static String line(AttackReduceSprintHitTest.Fixture f, int index) {
        return f.player.chatMessages.get(index);
    }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 1E-8; }
    private static void check(String label, boolean condition) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
}
