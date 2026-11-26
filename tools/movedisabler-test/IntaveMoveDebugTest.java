import module.ModuleManager;
import module.misc.IntaveDebug;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0EPacketClickWindow;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

/** Exercises the production debug handler in an EmbeddedChannel without modifying its packets. */
public final class IntaveMoveDebugTest {
    public static void main(String[] args) throws Exception {
        File log = File.createTempFile("openmaver-intave-debug-", ".log");
        System.setProperty("openmaver.intavedebug.path", log.getAbsolutePath());
        IntaveDebug module = new IntaveDebug();
        ModuleManager.register(module);
        try (ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_NOXZ)) {
            module.setEnabled(true);
            IntaveMoveDebugFeature.onTick(f.mc);
            f.channel.runPendingTasks();
            Object move = new C03PacketPlayer(0.0D);
            Object click = new C0EPacketClickWindow();
            f.channel.writeOutbound(move, click);
            check(f.channel.readOutbound() == move, "movement packet passes unchanged");
            check(f.channel.readOutbound() == click, "window click passes unchanged");
            ZenAntiKBTest.S32PacketConfirmTransaction ack = new ZenAntiKBTest.S32PacketConfirmTransaction();
            f.channel.writeInbound(ack);
            IntaveMoveDebugFeature.onTick(f.mc);
            module.setEnabled(false);
            IntaveMoveDebugFeature.onTick(f.mc);
        }
        String output = new String(Files.readAllBytes(log.toPath()), Charset.forName("UTF-8"));
        check(output.contains("OUT net.minecraft.network.play.client.C03PacketPlayer"), "records actual outbound movement");
        check(output.contains("OUT net.minecraft.network.play.client.C0EPacketClickWindow"), "records actual outbound click");
        check(output.contains("IN ZenAntiKBTest$S32PacketConfirmTransaction"), "records inbound transaction response");
        check(output.contains("no packets modified"), "log identifies observation-only mode");
        if (!log.delete()) throw new AssertionError("could not remove temporary log " + log);
        System.out.println("IntaveMoveDebugTest: 6 passed, 0 failed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("ok   " + message);
    }
}
