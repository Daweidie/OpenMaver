import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import util.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Intave inventory diagnostic recorder. It observes the production Netty pipeline and client
 * state, but never changes a packet, promise, key binding, or movement input.
 */
public final class IntaveMoveDebugFeature {
    private static final String HANDLER_NAME = "openmaver_intave_inventory_debug";
    private static final File LOG_FILE = new File(System.getProperty(
            "openmaver.intavedebug.path", "logs/intave-movedisabler-debug.log"));
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final ArrayBlockingQueue<String> EVENTS = new ArrayBlockingQueue<String>(32768);
    private static volatile String clientState = "state=unknown";
    private static volatile Session session;
    private static boolean recording;
    private static boolean warned;
    private static String previousState;
    private static BufferedWriter writer;

    private IntaveMoveDebugFeature() { }

    private static final class Session {
        final Minecraft mc;
        final Object player, world, handler;
        final ChannelPipeline pipeline;
        final ChannelDuplexHandler hook = new ChannelDuplexHandler() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                recordPacket("OUT", msg);
                ctx.write(msg, promise);
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                recordPacket("IN", msg);
                ctx.fireChannelRead(msg);
            }
        };

        Session(Minecraft mc, Object player, Object world, Object handler, ChannelPipeline pipeline) {
            this.mc = mc;
            this.player = player;
            this.world = world;
            this.handler = handler;
            this.pipeline = pipeline;
        }

        boolean current() {
            return pipeline.channel().isOpen()
                    && read(mc, "thePlayer", "field_71439_g") == player
                    && read(mc, "theWorld", "field_71441_e") == world
                    && read(player, "sendQueue", "field_71174_a") == handler;
        }
    }

    /** Called from client tick START regardless of whether the recorder is enabled. */
    public static void onTick(Minecraft mc) {
        boolean wanted = enabled();
        if (wanted != recording) {
            if (wanted) startRecording();
            else stopRecording();
        }
        if (!recording) return;

        updateClientState(mc);
        if (!clientState.equals(previousState)) {
            previousState = clientState;
            event("STATE", clientState);
        }

        Object player = read(mc, "thePlayer", "field_71439_g");
        Object world = read(mc, "theWorld", "field_71441_e");
        Object handler = read(player, "sendQueue", "field_71174_a");
        Session current = session;
        if (player == null || world == null || handler == null) {
            detach();
        } else if (current != null && (!current.current()
                || current.pipeline.context(HANDLER_NAME) == null)) {
            detach();
            install(mc, player, world, handler);
        } else if (current == null) {
            install(mc, player, world, handler);
        }
        drainEvents();
    }

    private static void updateClientState(Minecraft mc) {
        Object player = read(mc, "thePlayer", "field_71439_g");
        Object screen = read(mc, "currentScreen", "field_71462_r");
        Object input = read(player, "movementInput", "field_71158_b");
        Object container = read(player, "openContainer", "field_71070_bA");
        Object cursor = read(player, "inventory", "field_71071_by");
        cursor = read(cursor, "itemStack", "field_70457_g");
        clientState = "screen=" + simpleName(screen)
                + " container=" + simpleName(container)
                + " window=" + number(container, "windowId", "field_75152_c", -1)
                + " cursor=" + item(cursor)
                + " input=(f" + decimal(read(input, "moveForward", "field_78900_b"))
                + ",s" + decimal(read(input, "moveStrafe", "field_78902_a"))
                + ",j" + bool(read(input, "jump", "field_78901_c"))
                + ",sn" + bool(read(input, "sneak", "field_78899_d")) + ")"
                + " pos=(" + decimal(read(player, "posX", "field_70165_t"))
                + "," + decimal(read(player, "posY", "field_70163_u"))
                + "," + decimal(read(player, "posZ", "field_70161_v")) + ")"
                + " motion=(" + decimal(read(player, "motionX", "field_70159_w"))
                + "," + decimal(read(player, "motionY", "field_70181_x"))
                + "," + decimal(read(player, "motionZ", "field_70179_y")) + ")"
                + " sprint=" + bool(OpenMaverFeatures.call(player, "isSprinting", "func_70051_ag", null))
                + " target=" + OpenMaverFeatures.getBypassAnticheatName()
                + " bypass=" + OpenMaverFeatures.isBypassEnabled()
                + " movedisabler=" + moduleEnabled("MoveDisabler");
        clientState += " clickGate=" + MoveDisablerFeature.intaveClickStatus();
    }

    private static void install(Minecraft mc, Object player, Object world, Object handler) {
        try {
            Channel channel = channelOf(read(handler, "netManager", "field_147299_f"));
            if (channel == null) {
                for (Class<?> type = handler.getClass(); type != null && channel == null;
                        type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) continue;
                        field.setAccessible(true);
                        channel = channelOf(field.get(handler));
                        if (channel != null) break;
                    }
                }
            }
            if (channel == null || !channel.isOpen()) return;
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.context("packet_handler") == null) return;
            Session s = new Session(mc, player, world, handler, pipeline);
            // Outbound traversal is reversed. Put the recorder before the existing hooks so it
            // observes packets they emit with ctx.write as well as packets they pass through.
            String base = pipeline.context(MoveDisablerPacketHook.NAME) != null
                    ? MoveDisablerPacketHook.NAME
                    : (pipeline.context(AttackReducePacketHook.NAME) != null
                            ? AttackReducePacketHook.NAME : "packet_handler");
            pipeline.addBefore(base, HANDLER_NAME, s.hook);
            session = s;
            event("HOOK", "installed before=" + base + " channel=" + channel.getClass().getSimpleName());
            warned = false;
        } catch (Throwable error) {
            warn("install", error);
        }
    }

    private static void recordPacket(String direction, Object packet) {
        if (!recording || packet == null || !interesting(packet)) return;
        offer("[" + SEQUENCE.incrementAndGet() + "] ts=" + System.currentTimeMillis() + " " + direction + " "
                + packet.getClass().getName() + " " + packetFields(packet) + " | " + clientState);
    }

    private static boolean interesting(Object packet) {
        String name = packet.getClass().getName();
        String[] types = {"C03PacketPlayer", "C0BPacketEntityAction", "C0DPacketCloseWindow",
                "C0EPacketClickWindow", "C0FPacketConfirmTransaction", "C16PacketClientStatus",
                "S08PacketPlayerPosLook", "S2DPacketOpenWindow", "S2EPacketCloseWindow",
                "S2FPacketSetSlot", "S30PacketWindowItems", "S32PacketConfirmTransaction"};
        for (String type : types) if (name.contains(type)) return true;
        // Some LaunchWrapper variants expose obfuscated class names. Keep only play packets
        // whose reflective fields have a window/transaction/movement-shaped payload.
        String simple = packet.getClass().getSimpleName();
        return (simple.startsWith("C") || simple.startsWith("S"))
                && (hasField(packet, "windowId", "field_148908_a")
                    || hasField(packet, "actionNumber", "field_149550_c")
                    || hasField(packet, "onGround", "field_149474_g"));
    }

    private static String packetFields(Object packet) {
        StringBuilder out = new StringBuilder("{");
        int count = 0;
        for (Class<?> type = packet.getClass(); type != null && count < 18; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (count >= 18 || Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(packet);
                    if (value == null || value instanceof Number || value instanceof Boolean
                            || value instanceof Enum<?>) {
                        appendField(out, field.getName(), String.valueOf(value), count++);
                    } else if (value.getClass().getName().contains("ItemStack")) {
                        appendField(out, field.getName(), item(value), count++);
                    } else if (value.getClass().isArray()) {
                        appendField(out, field.getName(), collectionSummary(value), count++);
                    } else if (value instanceof java.util.Collection<?>) {
                        appendField(out, field.getName(), collectionSummary((java.util.Collection<?>) value), count++);
                    }
                } catch (Throwable ignored) { }
            }
        }
        return out.append('}').toString();
    }

    private static void appendField(StringBuilder out, String name, String value, int count) {
        if (count > 0) out.append(',');
        out.append(name).append('=').append(value.length() > 4096 ? value.substring(0, 4096) : value);
    }

    private static boolean hasField(Object target, String... names) {
        for (String name : names) if (read(target, name, name) != null) return true;
        return false;
    }

    private static String collectionSummary(Object collection) {
        int length = collection instanceof java.util.Collection<?> ? ((java.util.Collection<?>) collection).size()
                : java.lang.reflect.Array.getLength(collection);
        StringBuilder out = new StringBuilder("items[").append(length).append(']');
        int shown = 0;
        for (int i = 0; i < length && shown < 45; i++) {
            Object value = collection instanceof java.util.Collection<?> ? collectionAt((java.util.Collection<?>) collection, i)
                    : java.lang.reflect.Array.get(collection, i);
            if (value == null || !value.getClass().getName().contains("ItemStack")) continue;
            String description = item(value);
            if (description.contains(",count=0)")) continue;
            out.append(' ').append(i).append(':').append(description);
            shown++;
        }
        if (length > 45) out.append(" ...");
        return out.toString();
    }

    private static Object collectionAt(java.util.Collection<?> collection, int index) {
        int current = 0;
        for (Object value : collection) if (current++ == index) return value;
        return null;
    }

    private static void startRecording() {
        recording = true;
        warned = false;
        previousState = null;
        EVENTS.clear();
        DROPPED.set(0L);
        try {
            File parent = LOG_FILE.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new java.io.IOException("cannot create " + parent);
            }
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(LOG_FILE, false), UTF8));
            event("START", "client=1.8.9 log=" + LOG_FILE.getPath()
                    + " anticheat=" + OpenMaverFeatures.getBypassAnticheatName()
                    + " (packet/input observation only; no packets modified)");
        } catch (Throwable error) {
            recording = false;
            warn("open log", error);
        }
    }

    private static void stopRecording() {
        event("STOP", "recorder disabled");
        detach();
        drainEvents();
        recording = false;
        if (writer != null) {
            try { writer.flush(); writer.close(); }
            catch (Throwable error) { warn("close log", error); }
            writer = null;
        }
    }

    private static void detach() {
        Session s = session;
        session = null;
        if (s != null) {
            try { s.pipeline.remove(s.hook); }
            catch (Throwable ignored) { }
        }
    }

    private static void drainEvents() {
        BufferedWriter output = writer;
        if (output == null) return;
        try {
            long dropped = DROPPED.getAndSet(0L);
            if (dropped > 0L) {
                output.write("[" + SEQUENCE.incrementAndGet() + "] ts=" + System.currentTimeMillis()
                        + " DROP queue_overflow=" + dropped);
                output.newLine();
            }
            String line;
            while ((line = EVENTS.poll()) != null) {
                output.write(line);
                output.newLine();
            }
            output.flush();
        } catch (Throwable error) {
            warn("write log", error);
            detach();
            recording = false;
        }
    }

    private static void event(String type, String detail) {
        offer("[" + SEQUENCE.incrementAndGet() + "] ts=" + System.currentTimeMillis()
                + " " + type + " " + detail);
    }

    private static void offer(String line) {
        if (!EVENTS.offer(line)) DROPPED.incrementAndGet();
    }

    private static boolean enabled() {
        Module module = ModuleManager.getModule("Intave Debug");
        return module != null && module.isEnabled();
    }

    private static boolean moduleEnabled(String name) {
        Module module = ModuleManager.getModule(name);
        return module != null && module.isEnabled();
    }

    private static Object read(Object target, String mcp, String srg) {
        return OpenMaverFeatures.read(target, mcp, srg, null);
    }

    private static long number(Object target, String mcp, String srg, long fallback) {
        Object value = read(target, mcp, srg);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static String simpleName(Object value) {
        return value == null ? "none" : value.getClass().getSimpleName();
    }

    private static String decimal(Object value) {
        return value instanceof Number ? String.format(java.util.Locale.ROOT, "%.4f", ((Number) value).doubleValue())
                : "?";
    }

    private static String bool(Object value) {
        return value == null ? "?" : String.valueOf(value);
    }

    private static String item(Object stack) {
        if (stack == null) return "empty";
        Object count = read(stack, "stackSize", "field_77994_a");
        Object name = OpenMaverFeatures.call(stack, "getDisplayName", "func_82833_r", null);
        String label = name == null ? simpleName(stack) : String.valueOf(name).replace('\n', ' ');
        return "item(" + (label.length() > 48 ? label.substring(0, 48) : label)
                + ",count=" + (count == null ? "?" : count) + ")";
    }

    private static Channel channelOf(Object manager) throws IllegalAccessException {
        if (manager == null) return null;
        for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Channel.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(manager);
                if (value instanceof Channel) return (Channel) value;
            }
        }
        return null;
    }

    private static void warn(String where, Throwable error) {
        if (warned) return;
        warned = true;
        Logger.warn("Intave Debug", where + ": " + error);
    }
}
