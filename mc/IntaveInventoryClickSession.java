import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MovementInput;
import util.Logger;

import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Experimental window-0 transaction gate. Mutable packet state belongs to the channel
 * event loop; inventory/position snapshots and input changes belong to the client thread.
 * No synthetic ticks, velocity resets, transaction renumbering, or click reconstruction.
 */
final class IntaveInventoryClickSession {
    static final int MAX_CLICKS = 128, MAX_TAIL = 128;
    static final long ACK_TIMEOUT_MS = 400;
    private final Minecraft mc;
    private final Object player;
    private final ChannelPipeline pipeline;
    private final BooleanSupplier current;
    private final Class<?> clickType, closeType, statusType, moveType;
    private final Constructor<?> openConstructor, closeConstructor, positionConstructor, positionLookConstructor;
    private final Object openState;
    private final Deque<Write> clicks = new ArrayDeque<Write>();
    private final Deque<Write> tail = new ArrayDeque<Write>();
    private final Set<Short> awaiting = new HashSet<Short>();
    private volatile boolean needsFrame;
    private volatile String status = "IDLE";
    // Main-thread fields: a tick owns at most one neutral-input marker.
    private boolean coastTick;
    private boolean coastJump;
    private MovementInput coastInput;
    // Event-loop fields below.
    private boolean armed, opened, retired;
    private int batchSize;
    private Write frame;
    private LocalState baseline, batchState;
    private long deadline;
    private ChannelHandlerContext lastContext;

    IntaveInventoryClickSession(Minecraft mc, Object player, ChannelPipeline pipeline,
            BooleanSupplier current) throws ReflectiveOperationException {
        this.mc = mc;
        this.player = player;
        this.pipeline = pipeline;
        this.current = current;
        clickType = Class.forName("net.minecraft.network.play.client.C0EPacketClickWindow");
        closeType = Class.forName("net.minecraft.network.play.client.C0DPacketCloseWindow");
        statusType = Class.forName("net.minecraft.network.play.client.C16PacketClientStatus");
        moveType = Class.forName("net.minecraft.network.play.client.C03PacketPlayer");
        Class<?> stateType = Class.forName("net.minecraft.network.play.client.C16PacketClientStatus$EnumState");
        Object selected = null;
        for (Object value : stateType.getEnumConstants()) {
            if ("OPEN_INVENTORY_ACHIEVEMENT".equals(((Enum<?>) value).name())) selected = value;
        }
        openState = selected != null ? selected : stateType.getEnumConstants()[2];
        openConstructor = statusType.getConstructor(stateType);
        closeConstructor = closeType.getConstructor(int.class);
        positionConstructor = Class.forName(
                "net.minecraft.network.play.client.C03PacketPlayer$C04PacketPlayerPosition")
                .getConstructor(double.class, double.class, double.class, boolean.class);
        positionLookConstructor = Class.forName(
                "net.minecraft.network.play.client.C03PacketPlayer$C06PacketPlayerPosLook")
                .getConstructor(double.class, double.class, double.class, float.class, float.class, boolean.class);
    }

    String status() { return status; }

    /** FIFO marker precedes this tick's mouse/keyboard-generated window writes. */
    void maintain() {
        final LocalState snapshot = LocalState.capture(mc, player);
        pipeline.channel().eventLoop().execute(new Runnable() {
            public void run() {
                if (!valid()) { abort("inactive", !current.getAsBoolean()); return; }
                if (expire()) return;
                if (snapshot == null) { abort("container changed", false); return; }
                if (clicks.isEmpty() && awaiting.isEmpty() && !armed) baseline = snapshot;
            }
        });
    }

    /** Last input callback, AFTER the other modules, BEFORE vanilla movement physics. */
    void afterInput(MovementInput input) {
        coastTick = false;
        if (!needsFrame || !current.getAsBoolean() || AttackReduceFeature.isOutboundHeld()
                || read(player, "movementInput", "field_71158_b") != input) return;
        final LocalState snapshot = LocalState.capture(mc, player);
        // A pickup or drag is only predicted locally until the cursor is empty again.
        // Sending C0D with a carried stack would drop that stack on vanilla 1.8.
        if (snapshot == null || snapshot.cursor != null || snapshot.dragging) return;
        input.moveForward = 0;
        input.moveStrafe = 0;
        // Intave's legacy inventory-open physics path cannot infer a jump key from 1.8
        // movement packets. Do not let a held jump produce an unannounced +0.42 Y impulse
        // in the same real movement frame that temporarily opens the inventory.
        coastInput = input;
        coastJump = input.jump;
        input.jump = false;
        OpenMaverFeatures.callVoid(player, "setSprinting", "func_70031_b", null, false);
        coastTick = true;
        needsFrame = false;
        pipeline.channel().eventLoop().execute(new Runnable() {
            public void run() {
                if (!valid() || !awaiting.isEmpty() || clicks.isEmpty() || armed) return;
                armed = true;
                batchSize = clicks.size();
                batchState = snapshot;
                needsFrame = false;
                status = "COAST";
            }
        });
    }

    /** ClientTick END: vanilla has emitted its one C03 and finished its actual movement. */
    void afterTick() {
        if (!coastTick) return;
        coastTick = false;
        if (coastInput != null) {
            coastInput.jump = coastJump;
            coastInput = null;
        }
        final double x = number(player, "posX", "field_70165_t");
        Object bounds = OpenMaverFeatures.call(player, "getEntityBoundingBox", "func_174813_aQ", null);
        Object minY = read(bounds, "minY", "field_72338_b");
        final double y = minY instanceof Number ? ((Number) minY).doubleValue()
                : number(player, "posY", "field_70163_u");
        final double z = number(player, "posZ", "field_70161_v");
        final boolean ground = Boolean.TRUE.equals(read(player, "onGround", "field_70122_E"));
        pipeline.channel().eventLoop().execute(new Runnable() {
            public void run() {
                if (!valid()) { abort("inactive", true); return; }
                if (!armed) return;
                if (frame == null) {
                    armed = false;
                    needsFrame = !clicks.isEmpty();
                    status = "WAIT_FRAME";
                    return;
                }
                sendBatch(x, y, z, ground);
            }
        });
    }

    boolean outbound(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) {
        if (!valid()) return false;
        lastContext = ctx;
        expire();
        if (statusType.isInstance(packet) && read(packet, "status", "field_149437_a") == openState) {
            promise.trySuccess();
            return true;
        }
        if (closeType.isInstance(packet) && id(packet, true) == 0) {
            // User cancellation never replays a partially predicted cursor gesture.
            if (!awaiting.isEmpty()) {
                Write click;
                while ((click = clicks.pollFirst()) != null) consume(click);
                needsFrame = false;
                status = "WAIT_ACK_CLOSE";
                if (batchState != null) {
                    final LocalState restore = batchState;
                    OpenMaverFeatures.callVoid(mc, "addScheduledTask", "func_152344_a", null,
                            new Runnable() { public void run() { restore.restore(mc, player); } });
                }
            } else abort("local close", false);
            promise.trySuccess();
            return true;
        }
        if (clickType.isInstance(packet) && id(packet, false) == 0) {
            if (baseline == null || clicks.size() >= MAX_CLICKS) {
                abort("click queue unavailable/full", false);
                consume(new Write(ctx, packet, promise));
                return true;
            }
            clicks.addLast(new Write(ctx, packet, promise));
            if (awaiting.isEmpty() && !armed) {
                needsFrame = true;
                status = "WAIT_CURSOR";
            }
            return true;
        }
        if (armed && frame == null && moveType.isInstance(packet)) {
            frame = new Write(ctx, packet, promise);
            return true;
        }
        if ((frame != null || !awaiting.isEmpty()) && !isReceipt(packet)) {
            tail.addLast(new Write(ctx, packet, promise));
            if (tail.size() >= MAX_TAIL) abort("movement queue full", false);
            return true;
        }
        return false;
    }

    /** Observe before Velocity can delay delivery of the receipt to Minecraft. */
    void inbound(ChannelHandlerContext ctx, Object packet) {
        lastContext = ctx;
        if (!valid()) { abort("inactive", true); return; }
        expire();
        String name = packet.getClass().getSimpleName();
        if (name.equals("S32PacketConfirmTransaction") && !awaiting.isEmpty()) {
            Object window = read(packet, "windowId", "field_148894_a");
            Object action = read(packet, "actionNumber", "field_148892_b");
            if (!(window instanceof Number) || ((Number) window).intValue() != 0
                    || !(action instanceof Number) || !awaiting.remove(((Number) action).shortValue())) return;
            if (!Boolean.TRUE.equals(read(packet, "accepted", "field_148893_c"))) {
                abort("transaction rejected", false);
            } else if (awaiting.isEmpty()) {
                cancelTimeout();
                close(ctx);
                baseline = batchState;
                batchState = null;
                flushTail(false);
                needsFrame = !clicks.isEmpty();
                status = needsFrame ? "WAIT_CURSOR" : "IDLE";
                ctx.flush();
            }
        } else if (name.equals("S08PacketPlayerPosLook") || name.equals("S07PacketRespawn")
                || name.equals("S40PacketDisconnect") || name.equals("S2DPacketOpenWindow")
                || name.equals("S2EPacketCloseWindow")) {
            abort("server " + name, true, name.equals("S08PacketPlayerPosLook"));
        } else if ((!clicks.isEmpty() || armed) && awaiting.isEmpty()
                && updatesPlayerInventory(packet, name)) {
            // Roll back local prediction before vanilla applies this authoritative update.
            abort("inventory resync", false);
        }
    }

    void retire(final boolean dropMovement) {
        Runnable task = new Runnable() {
            public void run() { abort("session ended", dropMovement); retired = true; }
        };
        if (pipeline.channel().eventLoop().inEventLoop()) task.run();
        else pipeline.channel().eventLoop().execute(task);
    }

    private boolean valid() { return !retired && current.getAsBoolean(); }

    private void sendBatch(double x, double y, double z, boolean ground) {
        Write movement = frame;
        try {
            // Only a full position frame commits inferred keys reliably. Upgrade an empty
            // native tick to its ACTUAL post-physics position; never add another tick.
            Object realMove = movement.packet;
            if (!Boolean.TRUE.equals(read(realMove, "moving", "field_149480_h"))) {
                if (Boolean.TRUE.equals(read(realMove, "rotating", "field_149481_i"))) {
                    Object yaw = read(realMove, "yaw", "field_149476_e");
                    Object pitch = read(realMove, "pitch", "field_149473_f");
                    if (!(yaw instanceof Number) || !(pitch instanceof Number)) {
                        throw new IllegalStateException("C03 rotation missing");
                    }
                    realMove = positionLookConstructor.newInstance(x, y, z,
                            ((Number) yaw).floatValue(), ((Number) pitch).floatValue(), ground);
                } else realMove = positionConstructor.newInstance(x, y, z, ground);
            }
            Object open = openConstructor.newInstance(openState);
            for (Write click : clicks) {
                if (click.promise.isDone()) throw new IllegalStateException("C0E already cancelled/completed");
                Object action = read(click.packet, "actionNumber", "field_149550_d");
                if (!(action instanceof Number)) throw new IllegalStateException("C0E action missing");
                if (!awaiting.add(((Number) action).shortValue())) {
                    throw new IllegalStateException("Duplicate C0E action");
                }
                if (awaiting.size() == batchSize) break;
            }
            ChannelHandlerContext ctx = movement.ctx;
            ctx.write(open, ctx.newPromise());
            opened = true;
            ctx.write(realMove, movement.promise);
            if (realMove != movement.packet) ReferenceCountUtil.release(movement.packet);
            frame = null;
            armed = false;
            // The snapshot covers exactly the clicks that preceded the input FIFO marker.
            for (int i = 0; i < batchSize; i++) {
                Write click = clicks.removeFirst();
                ctx.write(click.packet, click.promise);
            }
            status = "WAIT_ACK";
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ACK_TIMEOUT_MS);
            ctx.flush();
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.warn("MoveDisabler", "Intave click gate: " + error);
            abort("batch write failed", false);
        }
    }

    private void abort(String reason, boolean dropMovement) {
        abort(reason, dropMovement, !dropMovement);
    }

    private void abort(String reason, boolean dropMovement, boolean closeServer) {
        boolean pending = !clicks.isEmpty() || armed || !awaiting.isEmpty() || opened;
        cancelTimeout();
        if (pending) {
            final LocalState restore = opened && batchState != null ? batchState : baseline;
            if (restore != null) OpenMaverFeatures.callVoid(mc, "addScheduledTask", "func_152344_a", null,
                    new Runnable() { public void run() { restore.restore(mc, player); } });
        }
        ChannelHandlerContext ctx = lastContext;
        if (opened && ctx != null && ctx.channel().isOpen() && closeServer) close(ctx);
        opened = false;
        Write click;
        while ((click = clicks.pollFirst()) != null) consume(click);
        if (frame != null) {
            if (dropMovement) consume(frame);
            else frame.ctx.write(frame.packet, frame.promise);
            frame = null;
        }
        flushTail(dropMovement);
        awaiting.clear();
        armed = false;
        needsFrame = false;
        batchState = null;
        if (ctx != null) ctx.flush();
        if (pending) Logger.warn("MoveDisabler", "Intave click gate reset: " + reason);
        status = pending ? "RESET: " + reason : "IDLE";
    }

    private void close(ChannelHandlerContext ctx) {
        if (!opened) return;
        try { ctx.write(closeConstructor.newInstance(0), ctx.newPromise()); }
        catch (ReflectiveOperationException error) { Logger.warn("MoveDisabler", error.toString()); }
        opened = false;
    }

    private void cancelTimeout() {
        deadline = 0;
    }

    private boolean expire() {
        if (deadline == 0 || System.nanoTime() - deadline < 0) return false;
        abort("transaction timeout", false);
        return true;
    }

    private void flushTail(boolean drop) {
        Write write;
        while ((write = tail.pollFirst()) != null) {
            if (drop || !write.ctx.channel().isOpen()) consume(write);
            else write.ctx.write(write.packet, write.promise);
        }
    }

    private static void consume(Write write) {
        // Cancellation here is a deliberate packet decision, not a failed transport.
        // NetworkManager's FIRE_EXCEPTION_ON_FAILURE listener disconnects on failure.
        // trySuccess also leaves any existing downstream failure/cancellation intact.
        write.promise.trySuccess();
        ReferenceCountUtil.release(write.packet);
    }

    private static boolean updatesPlayerInventory(Object packet, String name) {
        if (name.equals("S30PacketWindowItems")) {
            Object window = read(packet, "windowId", "field_148914_a");
            return window instanceof Number && ((Number) window).intValue() == 0;
        }
        if (name.equals("S2FPacketSetSlot")) {
            Object window = read(packet, "windowId", "field_149179_a");
            if (!(window instanceof Number)) return false;
            int id = ((Number) window).intValue();
            // -1 is the carried stack; -2 directly addresses the player's inventory.
            return id == 0 || id == -1 || id == -2;
        }
        return false;
    }

    private static boolean isReceipt(Object packet) {
        String name = packet.getClass().getSimpleName();
        return name.equals("C00PacketKeepAlive") || name.equals("C0FPacketConfirmTransaction");
    }

    private static int id(Object packet, boolean close) {
        Object value = close ? read(packet, "windowId", "field_149556_a")
                : read(packet, "windowId", "field_149554_a");
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private static Object read(Object target, String mcp, String srg) {
        return OpenMaverFeatures.read(target, mcp, srg, null);
    }

    private static double number(Object target, String mcp, String srg) {
        Object value = read(target, mcp, srg);
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }

    private static final class Write {
        final ChannelHandlerContext ctx;
        final Object packet;
        final ChannelPromise promise;
        Write(ChannelHandlerContext ctx, Object packet, ChannelPromise promise) {
            this.ctx = ctx; this.packet = packet; this.promise = promise;
        }
    }

    /** Client-thread-only copies; transaction ids intentionally remain monotonic. */
    private static final class LocalState {
        final Object world, container, inventory, cursor;
        final List<?> slots;
        final Object[] stacks;
        final boolean dragging;
        LocalState(Minecraft mc, Object player, Object container, List<?> slots) {
            this.world = read(mc, "theWorld", "field_71441_e");
            this.container = container;
            this.inventory = read(player, "inventory", "field_71071_by");
            this.slots = new ArrayList<Object>(slots);
            stacks = new Object[slots.size()];
            for (int i = 0; i < stacks.length; i++) stacks[i] = copy(OpenMaverFeatures.call(
                    slots.get(i), "getStack", "func_75211_c", null));
            cursor = copy(OpenMaverFeatures.call(inventory, "getItemStack", "func_70445_o", null));
            Object screen = read(mc, "currentScreen", "field_71462_r");
            dragging = Boolean.TRUE.equals(read(screen, "dragSplitting", "field_147007_t"))
                    || number(container, "dragEvent", "field_94536_g") != 0;
        }
        static LocalState capture(Minecraft mc, Object player) {
            if (Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isRiding", "func_70115_ae", null))
                    || Boolean.FALSE.equals(OpenMaverFeatures.call(player, "isEntityAlive", "func_70089_S", null))) return null;
            Object container = read(player, "openContainer", "field_71070_bA");
            if (container == null || container != read(player, "inventoryContainer", "field_71069_bz")
                    || number(container, "windowId", "field_75152_c") != 0) return null;
            Object slots = read(container, "inventorySlots", "field_75151_b");
            if (!(slots instanceof List) || read(player, "inventory", "field_71071_by") == null) return null;
            try { return new LocalState(mc, player, container, (List<?>) slots); }
            catch (RuntimeException error) { return null; }
        }
        void restore(Minecraft mc, Object player) {
            if (read(mc, "thePlayer", "field_71439_g") != player
                    || read(mc, "theWorld", "field_71441_e") != world
                    || read(player, "inventoryContainer", "field_71069_bz") != container) return;
            for (int i = 0; i < stacks.length; i++) OpenMaverFeatures.callVoid(
                    slots.get(i), "putStack", "func_75215_d", null, copy(stacks[i]));
            OpenMaverFeatures.callVoid(inventory, "setItemStack", "func_70437_b", null, copy(cursor));
            // Drop client drag state together with the cancelled gesture, without sending
            // another close packet back through this handler.
            if (read(player, "openContainer", "field_71070_bA") == container) {
                OpenMaverFeatures.callVoid(mc, "displayGuiScreen", "func_147108_a", null, (Object) null);
            }
        }
        static Object copy(Object stack) {
            if (stack == null) return null;
            Object copy = OpenMaverFeatures.call(stack, "copy", "func_77946_l", null);
            if (copy == null) throw new IllegalStateException("ItemStack.copy missing");
            return copy;
        }
    }
}
