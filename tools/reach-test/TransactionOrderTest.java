import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import module.ModuleManager;
import module.combat.Reach;
import net.minecraft.client.Minecraft;

/** Reproduces S08 -> scheduled vanilla C06 -> asynchronous Netty write. */
public final class TransactionOrderTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        ModuleManager.register(new Reach());
        ModuleManager.getModule("Reach").setEnabled(true);
        OpenMaverFeatures.setReachPingSpoof(400);
        OpenMaverFeatures.setReachPingJitter(0);
        correctionBetweenActionsAndMovement();
        consecutiveCorrections();
        interceptedCorrection();
        respawnAndConnectionReplacement();
        tickReleasesBeforeActions();
        System.out.println("[transaction-order-test] " + checks + " checks passed");
    }

    private static EmbeddedChannel channel(final Minecraft mc) {
        return channel(mc, false);
    }

    private static EmbeddedChannel channel(final Minecraft mc, boolean intercept) {
        final EmbeddedChannel[] channel = new EmbeddedChannel[1];
        channel[0] = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        String terminal = channel[0].pipeline().firstContext().name();
        channel[0].pipeline().addBefore(terminal, intercept ? AttackReducePacketHook.NAME : "packet_handler", new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                if (message instanceof S08PacketPlayerPosLook) {
                    final int id = ((S08PacketPlayerPosLook) message).id;
                    mc.addScheduledTask(new Runnable() {
                        @Override public void run() {
                            // NetworkManager enqueues the C06 write on the event loop.
                            channel[0].eventLoop().execute(new Runnable() {
                                @Override public void run() { channel[0].writeOutbound("C06(" + id + ")"); }
                            });
                        }
                    });
                }
            }
        });
        // Velocity consumes the inbound correction and processes it in its own main task.
        if (intercept) {
            channel[0].pipeline().addBefore(terminal, "packet_handler", new ChannelInboundHandlerAdapter());
        }
        TransactionDelayFeature.installForTest(channel[0].pipeline());
        return channel[0];
    }

    private static void correctionBetweenActionsAndMovement() {
        Minecraft mc = new Minecraft();
        EmbeddedChannel channel = channel(mc);
        try {
            channel.writeOutbound("C03(previous)");
            channel.writeOutbound(new C0FPacketConfirmTransaction(1));
            channel.writeOutbound("C08");
            channel.writeInbound(new S08PacketPlayerPosLook(1));
            channel.writeOutbound("C03(current)");
            check("receiving S08 does not inject a C0F between the current action and flying",
                    drain(channel).equals(Arrays.asList("C03(previous)", "C08", "C03(current)")));
            runMainTasks(mc);
            channel.runPendingTasks();
            check("scheduled teleport acceptance is preceded by the held confirmation",
                    drain(channel).equals(Arrays.asList("C0F(1)", "C06(1)")));
            channel.writeOutbound(new C0FPacketConfirmTransaction(2));
            check("delay resumes after the asynchronous acknowledgement write",
                    TransactionDelayFeature.heldForTest() == 1 && drain(channel).isEmpty());
        } finally { TransactionDelayFeature.uninstallForTest(); channel.finish(); }
    }

    private static void consecutiveCorrections() {
        Minecraft mc = new Minecraft();
        EmbeddedChannel channel = channel(mc);
        try {
            channel.writeOutbound(new C0FPacketConfirmTransaction(3));
            channel.writeInbound(new S08PacketPlayerPosLook(2));
            channel.writeInbound(new S08PacketPlayerPosLook(3));
            runMainTasks(mc);
            channel.runPendingTasks();
            check("multiple corrections in one frame preserve both acknowledgements",
                    drain(channel).equals(Arrays.asList("C0F(3)", "C06(2)", "C06(3)")));
        } finally { TransactionDelayFeature.uninstallForTest(); channel.finish(); }
    }

    private static void tickReleasesBeforeActions() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("mc/OpenMaverFeatures.java")), StandardCharsets.UTF_8);
        int start = source.indexOf("public static void prepareTick(Minecraft mc)");
        int end = source.indexOf("public static boolean claimPlacement()", start);
        String tick = source.substring(start, end);
        check("standalone entry prepares transactions before normal and fallback actions",
                tick.indexOf("TransactionDelayFeature.tick(mc);") < tick.indexOf("onTickStartPrepared(mc);")
                && tick.indexOf("prepareTick(mc);") < tick.indexOf("onTickStartPrepared(mc);"));
        String handler = new String(Files.readAllBytes(Paths.get("mc/OpenMaverClientHandler.java")), StandardCharsets.UTF_8);
        int prepare = handler.indexOf("OpenMaverFeatures.prepareTick(mc);");
        for (String action : new String[] {"tickBlockHit(mc, true);", "MoveDisablerFeature.onTick(mc);",
                "AttackReduceFeature.beforeTick(mc);", "InventoryManagerFeature.onTickStart(mc);",
                "ChestFeature.onTick(mc);", "OpenMaverFeatures.onTickStartPrepared(mc);"}) {
            check("Forge entry prepares transactions before " + action,
                    prepare >= 0 && prepare < handler.indexOf(action));
        }
        check("action phase never releases transactions a second time",
                !tick.substring(tick.indexOf("public static void onTickStartPrepared")).contains("TransactionDelayFeature.tick(mc)"));
    }

    private static void interceptedCorrection() {
        Minecraft mc = new Minecraft();
        EmbeddedChannel channel = channel(mc, true);
        try {
            channel.writeOutbound(new C0FPacketConfirmTransaction(4));
            channel.writeInbound(new S08PacketPlayerPosLook(4));
            runMainTasks(mc);
            channel.runPendingTasks();
            check("transaction boundary precedes a consuming Velocity-style inbound handler",
                    drain(channel).equals(Arrays.asList("C0F(4)", "C06(4)")));
        } finally { TransactionDelayFeature.uninstallForTest(); channel.finish(); }
    }

    private static void respawnAndConnectionReplacement() {
        Minecraft mc = new Minecraft();
        EmbeddedChannel channel = channel(mc);
        try {
            channel.writeOutbound(new C0FPacketConfirmTransaction(5));
            channel.writeInbound(new S07PacketRespawn());
            runMainTasks(mc);
            channel.runPendingTasks();
            check("respawn releases old confirmations and restores the delay boundary",
                    drain(channel).equals(Arrays.asList("C0F(5)")));
            TransactionDelayFeature.uninstallForTest();
            check("uninstall removes the actual Netty handler",
                    channel.pipeline().context(TransactionDelayFeature.HOOK_NAME) == null);
            TransactionDelayFeature.installForTest(channel.pipeline());
            channel.writeOutbound(new C0FPacketConfirmTransaction(6));
            channel.writeInbound(new S08PacketPlayerPosLook(5));
            TransactionDelayFeature.uninstallForTest();
            check("uninstall writes held confirmations before removing the hook",
                    drain(channel).equals(Arrays.asList("C0F(6)")));
            EmbeddedChannel replacement = channel(mc);
            try {
                replacement.writeOutbound(new C0FPacketConfirmTransaction(7));
                runMainTasks(mc);
                channel.runPendingTasks();
                replacement.runPendingTasks();
                check("old correction tasks do not flush or pause the replacement connection",
                        drain(channel).equals(Arrays.asList("C06(5)")) && drain(replacement).isEmpty()
                        && TransactionDelayFeature.heldForTest() == 1);
                replacement.writeOutbound(new C0FPacketConfirmTransaction(8));
                check("replacement still delays new confirmations", TransactionDelayFeature.heldForTest() == 2);
            } finally { TransactionDelayFeature.uninstallForTest(); replacement.finish(); }
        } finally { TransactionDelayFeature.uninstallForTest(); channel.finish(); }
    }

    private static void runMainTasks(Minecraft mc) {
        Runnable task;
        while ((task = mc.tasks.poll()) != null) task.run();
    }
    private static List<String> drain(EmbeddedChannel channel) {
        List<String> messages = new ArrayList<String>();
        Object packet;
        while ((packet = channel.readOutbound()) != null) messages.add(packet.toString());
        return messages;
    }
    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        checks++;
        System.out.println("[transaction-order-test] ok: " + name);
    }
    private static final class S08PacketPlayerPosLook {
        final int id;
        S08PacketPlayerPosLook(int id) { this.id = id; }
    }
    private static final class S07PacketRespawn { }
}
