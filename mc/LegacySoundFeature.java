import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import util.Logger;
import java.lang.reflect.Field;

/** 旧协议兼容入口（保留已有 hook 名）：声音名称与队伍包保护，独立于 Velocity。 */
public final class LegacySoundFeature {
    static final String NAME = "openmaver_legacy_sound";
    private static ChannelPipeline pipeline;
    private static ChannelDuplexHandler hook;
    private static Object handlerOwner;
    private static int tick, retry;
    private static boolean warned;
    private LegacySoundFeature() { }

    public static void onTick(Minecraft mc) {
        tick++;
        Object player = AttackReduceFeature.read(mc, "thePlayer", "field_71439_g");
        final Object handler = AttackReduceFeature.read(player, "sendQueue", "field_71174_a");
        Object manager = AttackReduceFeature.fieldByType(handler, "net.minecraft.network.NetworkManager");
        Object channel = AttackReduceFeature.fieldByType(manager, "io.netty.channel.Channel");
        ChannelPipeline next = (ChannelPipeline) AttackReduceFeature.call(channel, "pipeline", "pipeline");
        if (next == pipeline && next != null && handler == handlerOwner && next.context(NAME) != null) return;
        if (pipeline != null) {
            try { pipeline.remove(hook); } catch (Exception ignored) { }
            pipeline = null; hook = null; handlerOwner = null;
            retry = tick;
        }
        if (next == null || !next.names().contains("packet_handler") || tick < retry) return;
        retry = tick + 20;
        try {
            if (next.context(NAME) != null) next.remove(NAME);
            ChannelDuplexHandler added = new ChannelDuplexHandler() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object packet) throws Exception {
                    normalize(packet);
                    if (!TeamPacketGuard.capture(mc, handler, ctx, packet)) ctx.fireChannelRead(packet);
                }
            };
            String before = next.context(AttackReducePacketHook.NAME) != null
                    ? AttackReducePacketHook.NAME : "packet_handler";
            next.addBefore(before, NAME, added);
            pipeline = next; hook = added; handlerOwner = handler; warned = false;
        } catch (Exception error) {
            if (!warned) Logger.warn("Protocol 1.8", "Compatibility hook retry: " + error);
            warned = true;
        }
    }

    static void normalize(Object packet) {
        if (packet == null || !"S29PacketSoundEffect".equals(packet.getClass().getSimpleName())) return;
        Field name = OpenMaverFeatures.findField(packet, new String[] {"soundName", "field_149219_a"});
        if (name == null) return;
        try {
            Object value = name.get(packet);
            if ("damage.thorns".equals(value) || "minecraft:damage.thorns".equals(value)) {
                // vanilla 1.8 sounds.json 中的玩家受伤事件，避免 unknown soundEvent 刷屏。
                name.set(packet, "game.player.hurt");
            }
        } catch (IllegalAccessException ignored) { }
    }
}
