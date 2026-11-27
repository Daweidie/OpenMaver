import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import util.Logger;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** 1.8 队伍包兼容：与原版队列保序，在主线程检查实际 scoreboard。 */
final class TeamPacketGuard {
    private TeamPacketGuard() { }

    /** 独立于 Velocity 拦截 S3E；不能在 Netty 线程提前查询尚未创建的队伍。 */
    static boolean capture(final Minecraft mc, final Object handler,
            final ChannelHandlerContext ctx, final Object packet) {
        if (!isTeams(packet)) return false;
        final Method process = AttackReduceFeature.packetMethod(packet, handler);
        if (process == null) return false;
        return OpenMaverFeatures.callVoid(mc, "addScheduledTask", "func_152344_a", null, new Runnable() {
            public void run() {
                // 重生可以替换 player/world，连接身份只由 handler 和 channel 决定。
                Object player = AttackReduceFeature.read(mc, "thePlayer", "field_71439_g");
                if (!ctx.channel().isOpen()
                        || AttackReduceFeature.read(player, "sendQueue", "field_71174_a") != handler) return;
                try {
                    if (prepare(packet, handler)) process.invoke(packet, handler);
                } catch (Exception error) {
                    Throwable cause = error instanceof InvocationTargetException
                            && error.getCause() != null ? error.getCause() : error;
                    Logger.warn("Protocol 1.8", "Team packet handling failed: " + cause);
                }
            }
        });
    }

    /** 原版更新/删除直接解引用 getTeam 的结果；不存在的队伍操作已失效。 */
    static boolean prepare(Object packet, Object handler) {
        if (!isTeams(packet)) return true;
        Object world = AttackReduceFeature.read(handler, "clientWorldController", "field_147300_g");
        Object scoreboard = AttackReduceFeature.call(world, "getScoreboard", "func_96441_U");
        if (scoreboard == null) return false;
        Object action = AttackReduceFeature.call(packet, "getAction", "func_149307_h");
        Object name = AttackReduceFeature.call(packet, "getName", "func_149312_c");
        if (!(action instanceof Number) || !(name instanceof String)) return true;
        if (((Number) action).intValue() == 0) return true;
        return AttackReduceFeature.call(scoreboard, "getTeam", "func_96508_e", name) != null;
    }

    private static boolean isTeams(Object packet) {
        return packet != null && "S3EPacketTeams".equals(packet.getClass().getSimpleName());
    }
}
