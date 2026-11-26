import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovementInput;
import util.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Grim 默认路径在冲刺时将 C0E / C0D 包裹为 STOP -> 原包 -> START；Intave 目标
 * 使用独立的 window 0 临时窗口策略。两条路径均保留原点击包及其 promise。
 * 核心模块不引用 MC 类型，兼容 mod 与 bootstrap 注入两种类加载方式。
 */
public final class MoveDisablerFeature {
    private static volatile Session session;
    private static int tick, nextAttempt;
    private static Object attemptedHandler;
    private static volatile boolean warned;

    private MoveDisablerFeature() { }

    private static final class Session {
        final Minecraft mc;
        final Object player, world, handler;
        final ChannelPipeline pipeline;
        final MoveDisablerPacketHook hook = new MoveDisablerPacketHook();
        final Class<?> clickType, closeType;
        final Constructor<?> actionConstructor;
        final Object stop, start;
        volatile IntaveInventoryClickSession intave;

        Session(Minecraft mc, Object player, Object world, Object handler,
                ChannelPipeline pipeline) throws ReflectiveOperationException {
            this.mc = mc;
            this.player = player;
            this.world = world;
            this.handler = handler;
            this.pipeline = pipeline;
            clickType = Class.forName("net.minecraft.network.play.client.C0EPacketClickWindow");
            closeType = Class.forName("net.minecraft.network.play.client.C0DPacketCloseWindow");
            Class<?> action = Class.forName("net.minecraft.network.play.client.C0BPacketEntityAction$Action");
            actionConstructor = Class.forName("net.minecraft.network.play.client.C0BPacketEntityAction")
                    .getConstructor(Entity.class, action);
            // Enum 名优先；1.8.9 运行期名字被混淆时按协议声明顺序兜底。
            stop = action(action, "STOP_SPRINTING", 4);
            start = action(action, "START_SPRINTING", 3);
        }

        synchronized void maintainIntave() {
            if (!OpenMaverFeatures.isIntaveBypass()) {
                if (intave != null) intave.retire(!current());
                intave = null;
                return;
            }
            try {
                if (intave == null) intave = new IntaveInventoryClickSession(mc, player, pipeline,
                        () -> session == this && enabled() && current() && OpenMaverFeatures.isIntaveBypass());
                intave.maintain();
            } catch (ReflectiveOperationException error) { warn(error); }
        }

        boolean current() {
            return pipeline.channel().isOpen()
                    && read(mc, "thePlayer", "field_71439_g") == player
                    && read(mc, "theWorld", "field_71441_e") == world
                    && read(player, "sendQueue", "field_71174_a") == handler;
        }
    }

    /** 主线程维护连接；禁用/离开世界时卸载，换服或钩子被移除后重新安装。 */
    public static void onTick(Minecraft mc) {
        InventoryMoveFeature.onTick(mc);
        tick++;
        Object player = read(mc, "thePlayer", "field_71439_g");
        Object world = read(mc, "theWorld", "field_71441_e");
        Object handler = read(player, "sendQueue", "field_71174_a");
        if (!enabled() || player == null || world == null || handler == null) {
            detach();
            nextAttempt = tick;
            attemptedHandler = null;
            return;
        }
        if (handler != attemptedHandler) {
            attemptedHandler = handler;
            nextAttempt = tick;
        }
        Session s = session;
        if (s != null) {
            if (s.current() && isPacketHookInstalled()) { s.maintainIntave(); return; }
            detach();
            nextAttempt = tick;
        }
        if (tick < nextAttempt) return;
        nextAttempt = tick + 20;
        try {
            Channel channel = channelOf(read(handler, "netManager", "field_147299_f"));
            if (channel == null) {
                // NetworkManager 的类名可能仍是混淆名，按其 Channel 字段识别。
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
            if (channel == null) throw new IllegalStateException("Channel missing");
            if (!channel.isOpen()) return;
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.context("packet_handler") == null) throw new IllegalStateException("packet_handler missing");
            if (pipeline.context(MoveDisablerPacketHook.NAME) != null) pipeline.remove(MoveDisablerPacketHook.NAME);
            s = new Session(mc, player, world, handler, pipeline);
            session = s;
            // 出站方向为逆序：让 Velocity 先排队，回放的窗口包再经过本模块。
            String base = pipeline.context(AttackReducePacketHook.NAME) != null
                    ? AttackReducePacketHook.NAME : "packet_handler";
            pipeline.addBefore(base, MoveDisablerPacketHook.NAME, s.hook);
            s.maintainIntave();
            warned = false;
        } catch (Throwable error) {
            detach();
            warn(error);
        }
    }

    static boolean captureOutbound(MoveDisablerPacketHook source, ChannelHandlerContext ctx,
            Object packet, ChannelPromise promise) {
        Session s = session;
        if (s == null || s.hook != source) return false;
        IntaveInventoryClickSession gate;
        synchronized (s) { gate = s.intave; }
        if (!enabled() || !s.current() || !OpenMaverFeatures.isIntaveBypass()) {
            synchronized (s) { if (s.intave == gate) s.intave = null; }
            if (gate != null) gate.retire(!s.current());
            gate = null;
        }
        if (!enabled() || !s.current()) return false;
        if (OpenMaverFeatures.isIntaveBypass()) {
            return gate != null && gate.outbound(ctx, packet, promise);
        }
        if (!(s.clickType.isInstance(packet) || s.closeType.isInstance(packet))
                || !Boolean.TRUE.equals(OpenMaverFeatures.call(s.player,
                        "isSprinting", "func_70051_ag", null))) return false;
        Object stop, start;
        try {
            // 两个包均构造成功后才写入；映射异常时原包按原样发送。
            stop = s.actionConstructor.newInstance(s.player, s.stop);
            start = s.actionConstructor.newInstance(s.player, s.start);
        } catch (ReflectiveOperationException error) {
            warn(error);
            return false;
        }
        ctx.write(stop, ctx.newPromise());
        ctx.write(packet, promise);
        ctx.write(start, ctx.newPromise());
        // flush 沿用原发送路径，保持本组三个包紧邻且不额外触发 flush。
        return true;
    }

    static void captureInbound(MoveDisablerPacketHook source, ChannelHandlerContext ctx, Object packet) {
        Session s = session;
        IntaveInventoryClickSession gate = s != null ? s.intave : null;
        if (s != null && s.hook == source && gate != null) gate.inbound(ctx, packet);
    }

    static void afterMovementInput(MovementInput input) {
        Session s = session;
        IntaveInventoryClickSession gate = s != null ? s.intave : null;
        if (gate != null) gate.afterInput(input);
    }

    static void afterClientTick() {
        Session s = session;
        IntaveInventoryClickSession gate = s != null ? s.intave : null;
        if (gate != null) gate.afterTick();
    }

    static String intaveClickStatus() {
        Session s = session;
        IntaveInventoryClickSession gate = s != null ? s.intave : null;
        return gate != null ? gate.status() : "OFF";
    }

    static void hookRemoved(MoveDisablerPacketHook source) {
        Session s = session;
        if (s != null && s.hook == source) {
            IntaveInventoryClickSession gate;
            synchronized (s) { gate = s.intave; s.intave = null; }
            if (gate != null) gate.retire(!s.current());
        }
    }

    public static boolean isPacketHookInstalled() {
        Session s = session;
        return s != null && s.pipeline.channel().isOpen()
                && s.pipeline.context(MoveDisablerPacketHook.NAME) != null;
    }

    private static boolean enabled() {
        Module module = ModuleManager.getModule("MoveDisabler");
        return module != null && module.isEnabled();
    }

    private static void detach() {
        Session s = session;
        session = null;
        if (s != null) {
            IntaveInventoryClickSession gate = s.intave;
            if (gate != null) gate.retire(!s.current());
            try { s.pipeline.remove(s.hook); }
            catch (Exception ignored) { }
        }
    }

    private static Object read(Object target, String mcp, String srg) {
        return OpenMaverFeatures.read(target, mcp, srg, null);
    }

    private static Channel channelOf(Object manager) throws IllegalAccessException {
        if (manager == null) return null;
        for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !Channel.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(manager);
                if (value instanceof Channel) return (Channel) value;
            }
        }
        return null;
    }

    private static Object action(Class<?> type, String name, int ordinal) {
        return enumValue(type, name, ordinal);
    }

    private static Object enumValue(Class<?> type, String name, int ordinal) {
        Object[] values = type.getEnumConstants();
        if (values != null) {
            for (Object value : values) if (name.equals(((Enum<?>) value).name())) return value;
            if (ordinal < values.length) return values[ordinal];
        }
        throw new IllegalStateException("Sprint action missing: " + name);
    }

    private static void warn(Throwable error) {
        if (!warned) {
            warned = true;
            Logger.warn("MoveDisabler", "Packet hook: " + error);
        }
    }
}
