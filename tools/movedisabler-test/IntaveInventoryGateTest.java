import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import module.ModuleManager;
import module.misc.MoveDisabler;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.network.play.server.S2FPacketSetSlot;

import java.util.ArrayList;
import java.util.List;

/** Production gate on real Netty. This is NOT an Intave/Bukkit integration test. */
public final class IntaveInventoryGateTest {
    private static final MoveDisabler MODULE = new MoveDisabler();
    private static int passed;
    public static final class Stack {
        final int count;
        Stack(int count) { this.count = count; }
        public Stack copy() { return new Stack(count); }
    }
    public static final class Slot {
        Stack stack;
        Slot(Stack stack) { this.stack = stack; }
        public Stack getStack() { return stack; }
        public void putStack(Stack value) { stack = value; }
    }
    public static final class Container {
        public int windowId, dragEvent;
        public final List<Slot> inventorySlots = new ArrayList<Slot>();
        Container() {
            inventorySlots.add(new Slot(new Stack(64)));
            inventorySlots.add(new Slot(null));
        }
    }
    public static final class Inventory {
        Stack cursor;
        public Stack getItemStack() { return cursor; }
        public void setItemStack(Stack value) { cursor = value; }
    }
    public static final class Player extends ZenAntiKBTest.Player {
        public final Container inventoryContainer = new Container();
        public Object openContainer = inventoryContainer;
        public final Inventory inventory = new Inventory();
    }
    public static final class Screen extends GuiContainer { public boolean dragSplitting; }
    public static final class S08PacketPlayerPosLook { }
    public static final class S2DPacketOpenWindow { }
    public static final class S2EPacketCloseWindow { }
    public static final class C00PacketKeepAlive { }

    private static final class Fixture implements AutoCloseable {
        final ZenAntiKBTest.Fixture base = new ZenAntiKBTest.Fixture(AttackReduceFeature.MODE_NOXZ);
        final Player player = new Player();
        final Screen screen = new Screen();
        final List<Throwable> networkErrors = new ArrayList<Throwable>();
        short action;
        Fixture() {
            ZenAntiKBTest.MODULE.setEnabled(false);
            AttackReduceFeature.beforeTick(base.mc);
            base.channel.runPendingTasks();
            player.sendQueue = base.player.sendQueue;
            player.channel = base.channel;
            player.motionZ = .28;
            base.mc.thePlayer = player;
            base.mc.currentScreen = screen;
            MODULE.setEnabled(true);
            OpenMaverFeatures.setBypassEnabled(true);
            OpenMaverFeatures.setBypassAnticheat(OpenMaverFeatures.BYPASS_INTAVE);
            MoveDisablerFeature.onTick(base.mc);
            base.channel.runPendingTasks();
            check("gate installed", MoveDisablerFeature.isPacketHookInstalled());
            base.channel.pipeline().addBefore("packet_handler", "network_failure_observer", new ChannelDuplexHandler() {
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    networkErrors.add(cause);
                }
            });
        }
        C0EPacketClickWindow click() {
            return new C0EPacketClickWindow(0, ++action);
        }
        ChannelPromise send(Object packet) {
            ChannelPromise promise = base.channel.newPromise();
            // Minecraft NetworkManager attaches this listener to outbound writes.
            promise.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            base.channel.writeAndFlush(packet, promise);
            return promise;
        }
        C03PacketPlayer coast(boolean empty) {
            player.movementInput.moveForward = 1;
            player.movementInput.moveStrafe = -1;
            player.movementInput.jump = true;
            double motion = player.motionZ;
            MoveDisablerFeature.afterMovementInput(player.movementInput);
            base.channel.runPendingTasks();
            check("coast consumes horizontal input", player.movementInput.moveForward == 0
                    && player.movementInput.moveStrafe == 0);
            check("coast suppresses jump during the inventory frame", !player.movementInput.jump);
            check("coast preserves real velocity", player.motionZ == motion && player.motionZ != 0);
            check("coast uses actual nonsprinting physics", !player.sprinting);
            // One ordinary vanilla-style inertial tick. No packet synthesizes this movement.
            player.posZ += player.motionZ;
            player.motionZ *= .546;
            C03PacketPlayer frame = empty ? new C03PacketPlayer(player.onGround)
                    : new C03PacketPlayer.C04PacketPlayerPosition(player.posX, player.posY,
                            player.posZ, player.onGround);
            send(frame);
            check("native coast frame waits for post-physics snapshot", out() == null);
            MoveDisablerFeature.afterClientTick();
            base.channel.runPendingTasks();
            check("coast restores held jump after the native frame", player.movementInput.jump);
            return frame;
        }
        Object out() { return base.channel.readOutbound(); }
        void ack(short id, boolean accepted) {
            base.channel.writeInbound(new S32PacketConfirmTransaction((short) 0, id, accepted));
            base.channel.runPendingTasks();
        }
        void quiet(String message) { check(message, out() == null); }
        void expectBatch(C03PacketPlayer frame, Object... clicks) {
            Object open = out();
            check("batch opens native window", open instanceof C16PacketClientStatus
                    && ((C16PacketClientStatus) open).getStatus()
                        == C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT);
            Object actual = out();
            check("batch contains its actual native position frame", actual == frame);
            for (Object click : clicks) check("batch keeps native click identity/order", out() == click);
            quiet("no close or second movement tick before receipts");
        }
        public void close() {
            MODULE.setEnabled(false);
            MoveDisablerFeature.onTick(base.mc);
            base.channel.runPendingTasks();
            base.close();
            check("intentional consumption never fires a network exception", networkErrors.isEmpty());
            OpenMaverFeatures.setBypassEnabled(false);
            OpenMaverFeatures.setBypassAnticheat(OpenMaverFeatures.BYPASS_GRIM);
        }
    }

    public static void main(String[] args) throws Exception {
        ModuleManager.register(MODULE);
        ModuleManager.register(ZenAntiKBTest.MODULE);
        ModuleManager.register(ZenAntiKBTest.AURA);
        resyncDoesNotDisconnect();
        suppression(); movingClick(); cursorBatch(); drag(); emptyTick(); lookTick(); nextBatch();
        cancellation(); invalidation(); resyncScope(); fifo(); limits(); timeout(); velocity(); downstreamFailure();
        System.out.println("IntaveInventoryGateTest["
                + System.getProperty("openmaver.test.mapping", "MCP") + "]: " + passed + " passed, 0 failed");
    }

    private static void resyncDoesNotDisconnect() {
        try (Fixture f = new Fixture()) {
            ChannelPromise pending = f.send(f.click());
            f.base.channel.writeInbound(new S30PacketWindowItems());
            f.base.channel.runPendingTasks();
            check("inventory resync does not reach Forge failure listener", f.networkErrors.isEmpty());
            check("resync consumes the cancelled local click normally", pending.isSuccess());
            check("resync keeps connection open", f.base.channel.isOpen());
            f.quiet("resync never sends obsolete prediction");
        }
    }

    private static void suppression() {
        try (Fixture f = new Fixture()) {
            Object open = new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT) { };
            check("suppress inherited native open", f.send(open).isSuccess());
            check("suppress inherited native close", f.send(new C0DPacketCloseWindow(0) { }).isSuccess());
            f.quiet("GUI lifetime stays local");
            for (C16PacketClientStatus.EnumState state : new C16PacketClientStatus.EnumState[] {
                    C16PacketClientStatus.EnumState.PERFORM_RESPAWN, C16PacketClientStatus.EnumState.REQUEST_STATS}) {
                Object packet = new C16PacketClientStatus(state);
                f.send(packet); check("preserve unrelated status", f.out() == packet);
            }
            Object chest = new C0EPacketClickWindow(1), close = new C0DPacketCloseWindow(1);
            Object move = new C03PacketPlayer.C04PacketPlayerPosition();
            f.send(chest); f.send(close); f.send(move);
            check("nonzero containers and normal movement pass unchanged", f.out() == chest
                    && f.out() == close && f.out() == move && f.out() == null);
        }
    }

    private static void movingClick() {
        try (Fixture f = new Fixture()) {
            Object click = f.click();
            ChannelPromise promise = f.send(click);
            check("click waits without completing its promise", !promise.isDone());
            f.quiet("no old empty-frame wrapper");
            C03PacketPlayer frame = f.coast(false);
            f.expectBatch(frame, click);
            check("original click promise completes on actual write", promise.isSuccess());
            Object move = new C03PacketPlayer.C04PacketPlayerPosition();
            Object action = new Object();
            ChannelPromise movePromise = f.send(move), actionPromise = f.send(action);
            f.quiet("later motion/actions fenced from Bukkit click");
            Object receipt = new C0FPacketConfirmTransaction(), keepAlive = new C00PacketKeepAlive();
            f.send(receipt); f.send(keepAlive);
            check("receipts and keepalives bypass fence", f.out() == receipt && f.out() == keepAlive);
            f.ack((short) 99, true);
            f.quiet("unrelated transaction does not release fence");
            f.base.channel.writeInbound(new S32PacketConfirmTransaction((short) 1, (short) 1));
            f.quiet("wrong window does not release fence");
            f.ack((short) 1, true);
            check("matching ack closes before native tail resumes", f.out() instanceof C0DPacketCloseWindow
                    && f.out() == move && f.out() == action && f.out() == null);
            check("tail promises complete", movePromise.isSuccess() && actionPromise.isSuccess());
            f.player.movementInput.moveForward = 1;
            MoveDisablerFeature.afterMovementInput(f.player.movementInput);
            check("next tick resumes held movement input", f.player.movementInput.moveForward == 1);
        }
    }

    private static void cursorBatch() {
        try (Fixture f = new Fixture()) {
            f.player.inventory.cursor = f.player.inventoryContainer.inventorySlots.get(0).stack;
            f.player.inventoryContainer.inventorySlots.get(0).stack = null;
            Object pickup = f.click(); f.send(pickup);
            f.player.movementInput.moveForward = 1;
            MoveDisablerFeature.afterMovementInput(f.player.movementInput);
            check("carried cursor does not interrupt movement", f.player.movementInput.moveForward == 1);
            f.quiet("partial pickup never opens/closes server inventory");
            f.player.inventoryContainer.inventorySlots.get(1).stack = f.player.inventory.cursor;
            f.player.inventory.cursor = null;
            Object place = f.click(); f.send(place);
            C03PacketPlayer frame = f.coast(false);
            f.expectBatch(frame, pickup, place);
            f.ack((short) 1, true);
            f.quiet("first receipt keeps batch fence");
            f.ack((short) 2, true);
            check("cursor-empty batch closes once", f.out() instanceof C0DPacketCloseWindow && f.out() == null);
        }
    }

    private static void drag() {
        try (Fixture f = new Fixture()) {
            Object start = f.click(); f.send(start);
            f.screen.dragSplitting = true;
            f.player.movementInput.moveForward = 1;
            MoveDisablerFeature.afterMovementInput(f.player.movementInput);
            check("GUI drag defers whole group", f.player.movementInput.moveForward == 1);
            f.screen.dragSplitting = false;
            f.player.inventoryContainer.dragEvent = 1;
            MoveDisablerFeature.afterMovementInput(f.player.movementInput);
            f.quiet("container drag stage also defers group");
            f.player.inventoryContainer.dragEvent = 0;
            Object end = f.click(); f.send(end);
            f.expectBatch(f.coast(false), start, end);
            f.ack((short) 1, true); f.ack((short) 2, true);
            check("finished drag closes once", f.out() instanceof C0DPacketCloseWindow);
        }
    }

    private static void emptyTick() {
        try (Fixture f = new Fixture()) {
            f.player.onGround = false;
            Object click = f.click(); f.send(click);
            f.coast(true);
            check("empty native tick opens window", f.out() instanceof C16PacketClientStatus);
            Object frame = f.out();
            check("empty native tick upgraded to actual post-physics position",
                    frame instanceof C03PacketPlayer.C04PacketPlayerPosition
                    && Boolean.TRUE.equals(OpenMaverFeatures.read(frame, "moving", "field_149480_h", null))
                    && !((C03PacketPlayer) frame).onGround
                    && ((C03PacketPlayer) frame).z == f.player.posZ);
            check("upgraded tick still followed by original click", f.out() == click);
            f.quiet("upgrade adds no second movement tick");
            f.ack((short) 1, true);
        }
    }

    private static void nextBatch() {
        try (Fixture f = new Fixture()) {
            Object a = f.click(); f.send(a); f.expectBatch(f.coast(false), a);
            Object b = f.click(); ChannelPromise next = f.send(b);
            f.quiet("clicks after snapshot belong to next batch");
            f.ack((short) 1, true);
            check("first batch closes", f.out() instanceof C0DPacketCloseWindow && !next.isDone());
            f.expectBatch(f.coast(false), b);
            f.ack((short) 2, true);
            check("next transaction completes separately", next.isSuccess() && f.out() instanceof C0DPacketCloseWindow);
        }
    }

    private static void lookTick() {
        try (Fixture f = new Fixture()) {
            Object click = f.click(); f.send(click);
            f.player.movementInput.moveForward = 1;
            MoveDisablerFeature.afterMovementInput(f.player.movementInput);
            f.base.channel.runPendingTasks();
            f.player.posZ += f.player.motionZ;
            f.send(new C03PacketPlayer.C05PacketPlayerLook(137.5f, -28.25f, true));
            MoveDisablerFeature.afterClientTick(); f.base.channel.runPendingTasks();
            check("look-only coast frame opens window", f.out() instanceof C16PacketClientStatus);
            Object frame = f.out();
            check("look-only frame upgrades to native position-and-look", frame instanceof C03PacketPlayer.C06PacketPlayerPosLook);
            check("rotation and actual coast position survive upgrade",
                    ((Number) OpenMaverFeatures.read(frame, "yaw", "field_149476_e", null)).floatValue() == 137.5f
                    && ((Number) OpenMaverFeatures.read(frame, "pitch", "field_149473_f", null)).floatValue() == -28.25f
                    && ((C03PacketPlayer) frame).z == f.player.posZ && ((C03PacketPlayer) frame).onGround);
            check("look upgrade retains original click", f.out() == click);
            f.quiet("look upgrade adds no extra tick");
            f.ack((short) 1, true);
            check("look batch closes on receipt", f.out() instanceof C0DPacketCloseWindow);
        }
    }

    private static void cancellation() {
        for (int mode = 0; mode < 5; mode++) {
            try (Fixture f = new Fixture()) {
                f.player.inventoryContainer.inventorySlots.get(0).stack = null;
                f.player.inventory.cursor = new Stack(64);
                ChannelPromise pending = f.send(f.click());
                if (mode == 0) f.send(new C0DPacketCloseWindow(0));
                if (mode == 1) { MODULE.setEnabled(false); MoveDisablerFeature.onTick(f.base.mc); }
                if (mode == 2) { OpenMaverFeatures.setBypassEnabled(false); MoveDisablerFeature.onTick(f.base.mc); }
                if (mode == 3) { OpenMaverFeatures.setBypassAnticheat(OpenMaverFeatures.BYPASS_GRIM); MoveDisablerFeature.onTick(f.base.mc); }
                if (mode == 4) f.base.channel.pipeline().remove(MoveDisablerPacketHook.NAME);
                f.base.channel.runPendingTasks(); f.base.drain();
                check("cancel consumes unsent promise normally: " + mode, pending.isSuccess());
                check("cancel restores pre-gesture slots and cursor: " + mode,
                        f.player.inventoryContainer.inventorySlots.get(0).stack.count == 64
                        && f.player.inventory.cursor == null);
                f.quiet("cancel never late-replays click: " + mode);
            }
        }
        try (Fixture f = new Fixture()) {
            Object click = f.click(); f.send(click); f.expectBatch(f.coast(false), click);
            Object move = new C03PacketPlayer.C04PacketPlayerPosition(); f.send(move);
            ChannelPromise later = f.send(f.click());
            f.send(new C0DPacketCloseWindow(0));
            check("closing GUI consumes future predicted transactions", later.isSuccess());
            f.quiet("closing GUI retains in-flight Bukkit snapshot until receipt");
            f.ack((short) 1, true);
            check("closed GUI releases movement only after receipt", f.out() instanceof C0DPacketCloseWindow
                    && f.out() == move && f.out() == null);
        }
    }

    private static void invalidation() {
        for (int mode = 0; mode < 6; mode++) {
            try (Fixture f = new Fixture()) {
                Object click = f.click(); f.send(click); f.expectBatch(f.coast(false), click);
                ChannelPromise move = f.send(new C03PacketPlayer.C04PacketPlayerPosition());
                ChannelPromise pending = f.send(f.click());
                if (mode == 0) f.base.channel.writeInbound(new S08PacketPlayerPosLook());
                if (mode == 1) f.base.channel.writeInbound(new S2DPacketOpenWindow());
                if (mode == 2) f.base.channel.writeInbound(new S2EPacketCloseWindow());
                if (mode == 3) { f.base.mc.theWorld = new Object(); MoveDisablerFeature.onTick(f.base.mc); }
                if (mode == 4) { f.base.mc.thePlayer = null; MoveDisablerFeature.onTick(f.base.mc); }
                if (mode == 5) f.base.channel.close();
                f.base.channel.runPendingTasks();
                if (mode == 0) check("correction also clears temporary server open", f.out() instanceof C0DPacketCloseWindow);
                check("server/lifecycle invalidation drops stale tail: " + mode,
                        move.isSuccess() && pending.isSuccess());
                f.quiet("no stale move/click replay after invalidation: " + mode);
            }
        }
        try (Fixture f = new Fixture()) {
            ChannelPromise click = f.send(f.click());
            f.base.channel.writeInbound(new S30PacketWindowItems());
            check("authoritative resync consumes unsent prediction", click.isSuccess());
            f.quiet("resync sends no stale click");
        }
        try (Fixture f = new Fixture()) {
            Object click = f.click(); f.send(click); f.expectBatch(f.coast(false), click);
            ChannelPromise later = f.send(f.click());
            Object move = new C03PacketPlayer.C04PacketPlayerPosition(); f.send(move);
            f.ack((short) 1, false);
            check("rejection consumes future predicted clicks", later.isSuccess());
            check("rejection closes before real movement resumes", f.out() instanceof C0DPacketCloseWindow
                    && f.out() == move && f.out() == null);
        }
    }

    private static void resyncScope() {
        try (Fixture f = new Fixture()) {
            Object click = f.click(); ChannelPromise pending = f.send(click);
            f.base.channel.writeInbound(new S30PacketWindowItems(7));
            f.base.channel.writeInbound(new S2FPacketSetSlot(7, 0));
            check("unrelated container updates retain local click", !pending.isDone());
            f.expectBatch(f.coast(false), click);
            f.ack((short) 1, true);
            check("unrelated updates do not reset valid batch", pending.isSuccess()
                    && f.out() instanceof C0DPacketCloseWindow);
        }
        for (int window : new int[] {0, -1, -2}) {
            try (Fixture f = new Fixture()) {
                ChannelPromise click = f.send(f.click());
                f.base.channel.writeInbound(new S2FPacketSetSlot(window, 0));
                check("player slot/cursor resync consumes prediction: " + window, click.isSuccess());
                f.quiet("related resync never replays obsolete click: " + window);
            }
        }
    }

    private static void fifo() {
        try (Fixture f = new Fixture()) {
            Object click = f.click(); f.send(click);
            final Object earlier = new C03PacketPlayer.C04PacketPlayerPosition();
            f.base.channel.eventLoop().execute(() -> f.send(earlier));
            f.player.movementInput.moveForward = 1;
            MoveDisablerFeature.afterMovementInput(f.player.movementInput);
            f.base.channel.runPendingTasks();
            check("already queued movement precedes input marker", f.out() == earlier);
            Object real = new C03PacketPlayer.C04PacketPlayerPosition(); f.send(real);
            MoveDisablerFeature.afterClientTick(); f.base.channel.runPendingTasks();
            f.expectBatch((C03PacketPlayer) real, click);
            f.ack((short) 1, true);
        }
        try (Fixture f = new Fixture()) {
            Object click = f.click(); f.send(click);
            f.player.movementInput.moveForward = 1;
            MoveDisablerFeature.afterMovementInput(f.player.movementInput);
            f.base.channel.runPendingTasks();
            MoveDisablerFeature.afterClientTick(); f.base.channel.runPendingTasks();
            f.quiet("no native frame means no artificial tick/click");
            f.expectBatch(f.coast(false), click);
            f.ack((short) 1, true);
        }
    }

    private static void limits() {
        try (Fixture f = new Fixture()) {
            List<ChannelPromise> promises = new ArrayList<ChannelPromise>();
            f.player.inventory.cursor = new Stack(1);
            for (int i = 0; i <= IntaveInventoryClickSession.MAX_CLICKS; i++) promises.add(f.send(f.click()));
            for (ChannelPromise promise : promises) check("bounded cursor queue consumes all promises", promise.isSuccess());
            f.quiet("queue overflow never emits stale gesture");
        }
    }

    private static void timeout() throws InterruptedException {
        try (Fixture f = new Fixture()) {
            Object click = f.click(); f.send(click); f.expectBatch(f.coast(false), click);
            Object move = new C03PacketPlayer.C04PacketPlayerPosition();
            ChannelPromise pending = f.send(f.click()); f.send(move);
            Thread.sleep(IntaveInventoryClickSession.ACK_TIMEOUT_MS + 30);
            MoveDisablerFeature.onTick(f.base.mc);
            f.base.channel.runPendingTasks();
            check("timeout consumes unsent transactions", pending.isSuccess());
            check("timeout releases bounded movement behind close", f.out() instanceof C0DPacketCloseWindow
                    && f.out() == move && f.out() == null);
            f.ack((short) 1, true);
            f.quiet("late receipt cannot resurrect old batch");
        }
    }

    private static void velocity() {
        try (Fixture f = new Fixture()) {
            ZenAntiKBTest.MODULE.setEnabled(true);
            for (int i = 0; i < 21 && !AttackReduceFeature.isPacketHookInstalled(); i++) {
                AttackReduceFeature.beforeTick(f.base.mc); f.base.channel.runPendingTasks();
            }
            check("Velocity hook really reattached", AttackReduceFeature.isPacketHookInstalled());
            Object click = f.click();
            f.base.holdOutbound();
            check("Velocity actually holds outbound", AttackReduceFeature.isOutboundHeld());
            ChannelPromise pending = f.send(click);
            f.quiet("Velocity buffers original window click first");
            AttackReduceFeature.sendMovePackets(); f.base.channel.runPendingTasks();
            f.quiet("Velocity replay enters gate exactly once");
            check("replayed click still waits for real coast", !pending.isDone());
            f.base.holdOutbound();
            check("Velocity holds movement before input callback", AttackReduceFeature.isOutboundHeld());
            f.player.movementInput.moveForward = 1;
            MoveDisablerFeature.afterMovementInput(f.player.movementInput);
            check("Velocity holding movement does not force repeated coast ticks",
                    f.player.movementInput.moveForward == 1);
            AttackReduceFeature.sendMovePackets(); f.base.channel.runPendingTasks();
            f.expectBatch(f.coast(false), click);
            f.ack((short) 1, true);
            check("Velocity coexistence preserves original promise", pending.isSuccess());
        }
    }

    private static void downstreamFailure() {
        try (Fixture f = new Fixture()) {
            final Object click = f.click();
            final IllegalStateException error = new IllegalStateException("fixture write failure");
            f.base.channel.pipeline().addBefore(MoveDisablerPacketHook.NAME, "gate_failure", new ChannelDuplexHandler() {
                public void write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) {
                    if (packet == click) promise.setFailure(error);
                    else ctx.write(packet, promise);
                }
            });
            ChannelPromise promise = f.send(click); f.coast(false);
            check("native promise preserves downstream cause", promise.cause() == error);
            check("real write failure still reaches network failure listener",
                    f.networkErrors.size() == 1 && f.networkErrors.get(0) == error);
            f.networkErrors.clear();
        }
    }

    private static void check(String label, boolean result) {
        if (!result) throw new AssertionError(label);
        passed++;
    }
}
