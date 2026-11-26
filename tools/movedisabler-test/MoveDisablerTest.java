import config.Config;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import module.ModuleManager;
import module.misc.MoveDisabler;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.client.C16PacketClientStatus;

/** 复用 Velocity 的 MC 替身，以真实 Netty 4.0.23 验证包序及两个模块共存。 */
public final class MoveDisablerTest {
    private static final MoveDisabler MODULE = new MoveDisabler();
    private static int passed;

    public static void main(String[] args) throws Exception {
        Config config = new Config("config/default.jsonc");
        config.load();
        check("default config contains disabled module without key",
                config.has("misc.movedisabler.enabled")
                && !config.getBoolean("misc.movedisabler.enabled", true)
                && "".equals(config.getString("misc.movedisabler.key", null)));
        check("module derives config key", "misc.movedisabler".equals(MODULE.getConfigKey()));
        ModuleManager.register(MODULE);
        ModuleManager.register(ZenAntiKBTest.MODULE);
        ModuleManager.register(ZenAntiKBTest.AURA);
        packets();
        lifecycle();
        mappedConnection();
        failure();
        velocity(false);
        velocity(true);
        MODULE.setEnabled(false);
        MoveDisablerFeature.onTick(null);
        System.out.println("MoveDisablerTest[" + System.getProperty("openmaver.test.mapping", "MCP")
                + "]: " + passed + " passed, 0 failed");
    }

    private static ZenAntiKBTest.Fixture fixture() {
        ZenAntiKBTest.Fixture f = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_NOXZ);
        ZenAntiKBTest.MODULE.setEnabled(false);
        AttackReduceFeature.beforeTick(f.mc);
        f.channel.runPendingTasks();
        MODULE.setEnabled(true);
        MoveDisablerFeature.onTick(f.mc);
        f.channel.runPendingTasks();
        check("hook installed independently of Velocity", MoveDisablerFeature.isPacketHookInstalled());
        return f;
    }

    private static void packets() {
        try (ZenAntiKBTest.Fixture f = fixture()) {
            Object click = new C0EPacketClickWindow();
            ChannelPromise promise = f.channel.newPromise();
            f.channel.writeAndFlush(click, promise);
            triple(f, click);
            check("original promise completed", promise.isSuccess());
            Object close = new C0DPacketCloseWindow();
            f.channel.writeOutbound(close);
            triple(f, close);
            check("local sprint bookkeeping unchanged", f.player.sprinting && f.player.serverSprintState);

            Object inherited = new C0EPacketClickWindow() { };
            f.channel.writeOutbound(inherited);
            triple(f, inherited);
            Object move = new C03PacketPlayer(0.0D);
            Object ack = new C0FPacketConfirmTransaction();
            Object sprint = new C0BPacketEntityAction(f.player, C0BPacketEntityAction.Action.START_SPRINTING);
            f.channel.writeOutbound(move, ack, sprint);
            check("movement, transaction and entity-action packets pass unchanged",
                    f.channel.readOutbound() == move && f.channel.readOutbound() == ack
                    && f.channel.readOutbound() == sprint && f.channel.readOutbound() == null);

            f.player.sprinting = false;
            f.channel.writeOutbound(click, close);
            check("walking window operations pass once without C0B",
                    f.channel.readOutbound() == click && f.channel.readOutbound() == close
                    && f.channel.readOutbound() == null);
            f.player.sprinting = true;
            MODULE.setEnabled(false);
            f.channel.writeOutbound(click);
            single(f, click, "disable gates existing hook immediately");
            MoveDisablerFeature.onTick(f.mc);
            check("disable removes hook", !MoveDisablerFeature.isPacketHookInstalled()
                    && f.channel.pipeline().context(MoveDisablerPacketHook.NAME) == null);
        }
    }

    private static void lifecycle() {
        try (ZenAntiKBTest.Fixture f = fixture()) {
            Object click = new C0EPacketClickWindow();
            MoveDisablerFeature.onTick(f.mc);
            f.channel.writeOutbound(click);
            triple(f, click);
            f.channel.pipeline().remove(MoveDisablerPacketHook.NAME);
            MoveDisablerFeature.onTick(f.mc);
            f.channel.runPendingTasks();
            f.channel.writeOutbound(click);
            triple(f, click);

            Object world = f.mc.theWorld;
            f.mc.theWorld = new Object();
            f.channel.writeOutbound(click);
            single(f, click, "stale world passes through before maintenance");
            MoveDisablerFeature.onTick(f.mc);
            f.channel.runPendingTasks();
            f.channel.writeOutbound(click);
            triple(f, click);
            f.mc.theWorld = world;

            Object player = f.mc.thePlayer;
            f.mc.thePlayer = null;
            f.channel.writeOutbound(click);
            single(f, click, "missing player passes through");
            MoveDisablerFeature.onTick(f.mc);
            check("leaving world removes hook", !MoveDisablerFeature.isPacketHookInstalled());
            f.mc.thePlayer = player;
            MoveDisablerFeature.onTick(f.mc);
            f.channel.runPendingTasks();
            f.channel.writeOutbound(click);
            triple(f, click);
        }
        try (ZenAntiKBTest.Fixture f = fixture()) {
            Object close = new C0DPacketCloseWindow();
            f.channel.writeOutbound(close);
            triple(f, close);
            f.channel.close();
            MoveDisablerFeature.onTick(f.mc);
            check("closed connection is detached", !MoveDisablerFeature.isPacketHookInstalled());
        }
    }

    private static void failure() {
        try (ZenAntiKBTest.Fixture f = fixture()) {
            final Object click = new C0EPacketClickWindow();
            final IllegalStateException failure = new IllegalStateException("test write failure");
            f.channel.pipeline().addBefore(MoveDisablerPacketHook.NAME, "test_failure", new ChannelDuplexHandler() {
                @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    if (msg == click) promise.setFailure(failure);
                    else ctx.write(msg, promise);
                }
            });
            ChannelPromise promise = f.channel.newPromise();
            f.channel.writeAndFlush(click, promise);
            check("original promise preserves downstream failure", promise.isDone()
                    && !promise.isSuccess() && promise.cause() == failure);
            action(f.channel.readOutbound(), f, C0BPacketEntityAction.Action.STOP_SPRINTING);
            action(f.channel.readOutbound(), f, C0BPacketEntityAction.Action.START_SPRINTING);
            check("failed original is not resent", f.channel.readOutbound() == null);
        }
    }

    public static final class MappedPlayer extends net.minecraft.entity.Entity {
        public Object field_71174_a;
        public boolean func_70051_ag() { return true; }
    }

    private static final class OpaqueManager {
        private final Channel wire;
        OpaqueManager(Channel wire) { this.wire = wire; }
    }

    private static final class OpaqueHandler {
        private final Object connection;
        OpaqueHandler(Object connection) { this.connection = connection; }
    }

    private static void mappedConnection() {
        try (ZenAntiKBTest.Fixture f = fixture()) {
            MappedPlayer player = new MappedPlayer();
            player.field_71174_a = new OpaqueHandler(new OpaqueManager(f.channel));
            f.mc.thePlayer = player;
            MoveDisablerFeature.onTick(f.mc);
            f.channel.runPendingTasks();
            Object click = new C0EPacketClickWindow();
            f.channel.writeOutbound(click);
            Object stop = f.channel.readOutbound();
            Object original = f.channel.readOutbound();
            Object start = f.channel.readOutbound();
            check("SRG player fields and opaque connection types", stop instanceof C0BPacketEntityAction
                    && ((C0BPacketEntityAction) stop).action == C0BPacketEntityAction.Action.STOP_SPRINTING
                    && ((C0BPacketEntityAction) stop).player == player && original == click
                    && start instanceof C0BPacketEntityAction
                    && ((C0BPacketEntityAction) start).action == C0BPacketEntityAction.Action.START_SPRINTING
                    && ((C0BPacketEntityAction) start).player == player && f.channel.readOutbound() == null);
        }
    }

    private static void velocity(boolean installMoveFirst) {
        try (ZenAntiKBTest.Fixture f = fixture()) {
            if (!installMoveFirst) {
                MODULE.setEnabled(false);
                MoveDisablerFeature.onTick(f.mc);
            }
            ZenAntiKBTest.MODULE.setEnabled(true);
            AttackReduceFeature.setMode(AttackReduceFeature.MODE_NOXZ);
            AttackReduceFeature.beforeTick(f.mc);
            MODULE.setEnabled(true);
            MoveDisablerFeature.onTick(f.mc);
            f.channel.runPendingTasks();
            check("both packet hooks installed", MoveDisablerFeature.isPacketHookInstalled()
                    && AttackReduceFeature.isPacketHookInstalled());
            check("MoveDisabler precedes Velocity in inbound pipeline order",
                    f.channel.pipeline().names().indexOf(MoveDisablerPacketHook.NAME)
                    < f.channel.pipeline().names().indexOf(AttackReducePacketHook.NAME));
            f.holdOutbound();
            Object move = new C03PacketPlayer(0.0D);
            Object click = new C0EPacketClickWindow();
            Object ack = new C0FPacketConfirmTransaction();
            ChannelPromise promise = f.channel.newPromise();
            f.channel.writeOutbound(move);
            f.channel.writeAndFlush(click, promise);
            f.channel.writeOutbound(ack);
            check("Velocity holds window packet and its promise", f.channel.readOutbound() == null && !promise.isDone());
            AttackReduceFeature.sendMovePackets();
            f.channel.runPendingTasks();
            check("queued movement keeps its position", f.channel.readOutbound() == move);
            action(f.channel.readOutbound(), f, C0BPacketEntityAction.Action.STOP_SPRINTING);
            check("queued original sent exactly once", f.channel.readOutbound() == click);
            action(f.channel.readOutbound(), f, C0BPacketEntityAction.Action.START_SPRINTING);
            check("queued transaction keeps its position", f.channel.readOutbound() == ack
                    && f.channel.readOutbound() == null && promise.isSuccess());

            f.holdOutbound();
            Object close = new C0DPacketCloseWindow();
            f.channel.writeOutbound(close);
            f.player.sprinting = false;
            AttackReduceFeature.sendMovePackets();
            f.channel.runPendingTasks();
            single(f, close, "queued operation checks sprint state at playback");

            f.holdOutbound();
            f.player.sprinting = true;
            f.channel.writeOutbound(click);
            MODULE.setEnabled(false);
            MoveDisablerFeature.onTick(f.mc);
            AttackReduceFeature.sendMovePackets();
            f.channel.runPendingTasks();
            single(f, click, "disable while queued preserves original packet");
        }
    }

    private static void triple(ZenAntiKBTest.Fixture f, Object original) {
        action(f.channel.readOutbound(), f, C0BPacketEntityAction.Action.STOP_SPRINTING);
        check("original packet identity preserved", f.channel.readOutbound() == original);
        action(f.channel.readOutbound(), f, C0BPacketEntityAction.Action.START_SPRINTING);
        check("no recursion or duplicate packet", f.channel.readOutbound() == null);
    }

    private static void action(Object packet, ZenAntiKBTest.Fixture f, C0BPacketEntityAction.Action expected) {
        check("correct sprint action and entity: " + expected, packet instanceof C0BPacketEntityAction
                && ((C0BPacketEntityAction) packet).action == expected
                && ((C0BPacketEntityAction) packet).player == f.player);
    }

    private static void single(ZenAntiKBTest.Fixture f, Object packet, String label) {
        check(label, f.channel.readOutbound() == packet && f.channel.readOutbound() == null);
    }

    private static void check(String label, boolean condition) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
}
