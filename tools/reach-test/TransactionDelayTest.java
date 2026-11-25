import io.netty.channel.embedded.EmbeddedChannel;
import module.ModuleManager;
import module.combat.Reach;

/**
 * 事务延迟（PING SPOOF）的队列回归测试。
 *
 * <p>只验证本仓库自己那层：确认包被吞下、到期后按 FIFO 写出、关闭后立即放行、
 * 其它包不受影响。真实连接上的反射安装由游戏内验证。
 *
 * <p>需要真实 netty（`EmbeddedChannel`），由 `run.sh` 把 Prism 的 netty-all 放在
 * stub 之前；找不到 netty 时该测试跳过。
 */
public final class TransactionDelayTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        defaults();
        ModuleManager.register(new Reach());
        ModuleManager.getModule("Reach").setEnabled(true);
        holdAndRelease();
        ordering();
        nonTransactionPasses();
        disabledPasses();
        disablingReachReleasesInOrder();
        disablingDelayReleasesInOrder();
        disabledTickDetaches();
        System.out.println("[transaction-delay-test] " + passed + " checks passed");
    }

    private static void defaults() {
        check("spoof is off by default", OpenMaverFeatures.getReachPingSpoof() == 0);
    }

    private static void holdAndRelease() throws Exception {
        OpenMaverFeatures.setReachPingSpoof(120);
        OpenMaverFeatures.setReachPingJitter(0);
        EmbeddedChannel channel = newChannel();
        TransactionDelayFeature.installForTest(channel.pipeline());
        try {
            channel.writeOutbound(new C0FPacketConfirmTransaction(1));
            check("confirm transaction is held back",
                    channel.outboundMessages().isEmpty() && TransactionDelayFeature.heldForTest() == 1);
            TransactionDelayFeature.pumpForTest();
            check("not released before the deadline", channel.outboundMessages().isEmpty());
            Thread.sleep(200L);
            TransactionDelayFeature.pumpForTest();
            Object released = channel.outboundMessages().poll();
            check("released after the delay", released instanceof C0FPacketConfirmTransaction);
            check("queue is empty after release", TransactionDelayFeature.heldForTest() == 0);
        } finally {
            TransactionDelayFeature.uninstallForTest();
            channel.finish();
        }
    }

    private static void ordering() throws Exception {
        OpenMaverFeatures.setReachPingSpoof(60);
        OpenMaverFeatures.setReachPingJitter(0);
        EmbeddedChannel channel = newChannel();
        TransactionDelayFeature.installForTest(channel.pipeline());
        try {
            channel.writeOutbound(new C0FPacketConfirmTransaction(1));
            channel.writeOutbound(new C0FPacketConfirmTransaction(2));
            channel.writeOutbound(new C0FPacketConfirmTransaction(3));
            check("all three are held", TransactionDelayFeature.heldForTest() == 3);
            Thread.sleep(120L);
            TransactionDelayFeature.pumpForTest();
            check("FIFO order is preserved",
                    ((C0FPacketConfirmTransaction) channel.outboundMessages().poll()).action == 1
                            && ((C0FPacketConfirmTransaction) channel.outboundMessages().poll()).action == 2
                            && ((C0FPacketConfirmTransaction) channel.outboundMessages().poll()).action == 3);
        } finally {
            TransactionDelayFeature.uninstallForTest();
            channel.finish();
        }
    }

    private static void nonTransactionPasses() {
        OpenMaverFeatures.setReachPingSpoof(200);
        EmbeddedChannel channel = newChannel();
        TransactionDelayFeature.installForTest(channel.pipeline());
        try {
            channel.writeOutbound(new PositionPacket(1.0D));
            check("other packets are written straight through",
                    channel.outboundMessages().size() == 1
                            && TransactionDelayFeature.heldForTest() == 0);
        } finally {
            TransactionDelayFeature.uninstallForTest();
            channel.finish();
        }
    }

    private static void disabledPasses() {
        OpenMaverFeatures.setReachPingSpoof(0);
        EmbeddedChannel channel = newChannel();
        TransactionDelayFeature.installForTest(channel.pipeline());
        try {
            channel.writeOutbound(new C0FPacketConfirmTransaction(9));
            check("spoof off writes the confirm packet immediately",
                    channel.outboundMessages().size() == 1
                            && TransactionDelayFeature.heldForTest() == 0);
        } finally {
            TransactionDelayFeature.uninstallForTest();
            OpenMaverFeatures.setReachPingJitter(40);
            channel.finish();
        }
    }

    private static void disablingReachReleasesInOrder() {
        OpenMaverFeatures.setReachPingSpoof(400);
        EmbeddedChannel channel = newChannel();
        TransactionDelayFeature.installForTest(channel.pipeline());
        try {
            channel.writeOutbound(new C0FPacketConfirmTransaction(1));
            ModuleManager.getModule("Reach").setEnabled(false);
            channel.writeOutbound(new C0FPacketConfirmTransaction(2));
            check("Reach off releases the old confirmation before the new one",
                    channel.outboundMessages().size() == 2
                    && ((C0FPacketConfirmTransaction) channel.readOutbound()).action == 1
                    && ((C0FPacketConfirmTransaction) channel.readOutbound()).action == 2
                    && TransactionDelayFeature.heldForTest() == 0);
        } finally {
            TransactionDelayFeature.uninstallForTest(); channel.finish();
            ModuleManager.getModule("Reach").setEnabled(true);
        }
    }

    private static void disablingDelayReleasesInOrder() {
        OpenMaverFeatures.setReachPingSpoof(400);
        EmbeddedChannel channel = newChannel();
        TransactionDelayFeature.installForTest(channel.pipeline());
        try {
            channel.writeOutbound(new C0FPacketConfirmTransaction(3));
            OpenMaverFeatures.setReachPingSpoof(0);
            channel.writeOutbound(new C0FPacketConfirmTransaction(4));
            check("zero delay releases pending confirmations before passing new ones",
                    channel.outboundMessages().size() == 2
                    && ((C0FPacketConfirmTransaction) channel.readOutbound()).action == 3
                    && ((C0FPacketConfirmTransaction) channel.readOutbound()).action == 4);
        } finally { TransactionDelayFeature.uninstallForTest(); channel.finish(); }
    }

    private static void disabledTickDetaches() {
        OpenMaverFeatures.setReachPingSpoof(400);
        EmbeddedChannel channel = newChannel();
        TransactionDelayFeature.installForTest(channel.pipeline());
        try {
            channel.writeOutbound(new C0FPacketConfirmTransaction(5));
            ModuleManager.getModule("Reach").setEnabled(false);
            TransactionDelayFeature.tick(null);
            check("Reach off detaches the hook and flushes without waiting for another packet",
                    !TransactionDelayFeature.isActive()
                    && channel.readOutbound() instanceof C0FPacketConfirmTransaction
                    && TransactionDelayFeature.heldForTest() == 0);
        } finally {
            TransactionDelayFeature.uninstallForTest(); channel.finish();
            ModuleManager.getModule("Reach").setEnabled(true);
            OpenMaverFeatures.setReachPingSpoof(0);
        }
    }

    /** netty 4.0 的 EmbeddedChannel 不接受空 handler 列表。 */
    private static EmbeddedChannel newChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("packet_handler", new io.netty.channel.ChannelInboundHandlerAdapter());
        return channel;
    }

    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("[transaction-delay-test] ok: " + name);
    }
}

/** 与 1.8 出站确认包同名后缀，用来命中 {@code TransactionDelayFeature} 的匹配规则。 */
final class C0FPacketConfirmTransaction {
    final int action;

    C0FPacketConfirmTransaction(int action) {
        this.action = action;
    }

    @Override public String toString() { return "C0F(" + action + ")"; }
}

/** 对照组：名字不匹配的普通包。 */
final class PositionPacket {
    final double x;

    PositionPacket(double x) {
        this.x = x;
    }
}
