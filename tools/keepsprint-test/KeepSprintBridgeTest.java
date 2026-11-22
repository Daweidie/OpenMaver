import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Runs the packaged SRG event bridge against the real local-player field type. */
public final class KeepSprintBridgeTest {
    private static int checks;

    public static void main(String[] args) {
        KeepSprintFeature.reset();
        java.util.Properties legacy = new java.util.Properties();
        legacy.setProperty("keepSprint.rotate", "false");
        KeepSprintFeature.load(legacy);
        module.movement.KeepSprint module = new module.movement.KeepSprint();
        ModuleManager.register(module);
        module.setEnabled(true);

        Minecraft mc = new Minecraft();
        Minecraft.instance = mc;
        KeepSprintRuntimeTest.FakePlayer player = new KeepSprintRuntimeTest.FakePlayer();
        mc.field_71439_g = player;
        OpenMaverClientHandler handler = new OpenMaverClientHandler();

        // START must reach the feature before movement, even with only SRG fields.
        player.attribute.modifier = new Object();
        tick(handler, player, TickEvent.Phase.START);
        check("START reaches the feature through EntityPlayerSP field",
                player.attribute.modifier == null);

        player.movementInput.moveForward = -0.98F;
        tick(handler, player, TickEvent.Phase.END);
        check("END restores local sprint", player.sprinting);
        check("END applies the backward speed modifier", player.attribute.modifier != null);

        // Only the local client player may affect feature state.
        KeepSprintRuntimeTest.FakePlayer remote = new KeepSprintRuntimeTest.FakePlayer();
        remote.attribute.modifier = new Object();
        tick(handler, remote, TickEvent.Phase.START);
        check("remote START is ignored", remote.attribute.modifier != null);
        remote.movementInput.moveForward = -0.98F;
        tick(handler, remote, TickEvent.Phase.END);
        check("remote END is ignored", !remote.sprinting);
        handler.onAttackEntity(new AttackEntityEvent(remote, player));
        tick(handler, player, TickEvent.Phase.END);
        check("remote attack does not schedule a sprint packet", player.sendQueue.packets.isEmpty());

        handler.onAttackEntity(new AttackEntityEvent(player, remote));
        player.sprinting = false;
        tick(handler, player, TickEvent.Phase.END);
        check("local attack restores sprint", player.sprinting);
        check("local attack queues one sprint packet", player.sendQueue.packets.size() == 1);
        C0BPacketEntityAction packet = (C0BPacketEntityAction) player.sendQueue.packets.get(0);
        check("local attack packet is START_SPRINTING",
                packet.action == C0BPacketEntityAction.Action.START_SPRINTING);
        tick(handler, player, TickEvent.Phase.END);
        check("attack marker is consumed once", player.sendQueue.packets.size() == 1);

        module.setEnabled(false);
        tick(handler, player, TickEvent.Phase.START);
        tick(handler, player, TickEvent.Phase.END);
        check("disable cleans the modifier via the bridge", player.attribute.modifier == null);
        check("disable reports OFF via the bridge", "OFF".equals(KeepSprintFeature.getStatus()));

        handler.onPlayerTick(null);
        handler.onAttackEntity(null);
        tick(handler, null, TickEvent.Phase.END);
        mc.field_71439_g = null;
        tick(handler, player, TickEvent.Phase.END);
        handler.onAttackEntity(new AttackEntityEvent(player, remote));
        Minecraft.instance = null;
        tick(handler, player, TickEvent.Phase.END);
        handler.onAttackEntity(new AttackEntityEvent(player, remote));
        check("missing world/player leaves the feature OFF", "OFF".equals(KeepSprintFeature.getStatus()));

        System.out.println("KeepSprint SRG bridge: " + checks + " checks passed");
    }

    private static void tick(OpenMaverClientHandler handler, EntityPlayer player, TickEvent.Phase phase) {
        handler.onPlayerTick(new TickEvent.PlayerTickEvent(phase, player));
    }

    private static void check(String name, boolean result) {
        if (!result) throw new AssertionError(name);
        checks++;
        System.out.println("[PASS] " + name);
    }
}
