import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** 真实 Netty pipeline 上验证安装位置、连接更换与外部移除后的自愈。 */
public final class AttackReduceInstallTest {
    public static final class Player {
        public Object field_71174_a;
        public int getEntityId() { return 7; }
    }
    public static final class Recorder extends ChannelDuplexHandler { }
    private static int passed;

    public static void main(String[] args) throws Exception {
        Channel first=channel();
        Player player=new Player();
        player.field_71174_a=handler(first);
        set("hookAttemptTick",-20);
        maintain(player);
        check("installed before packet_handler",ordered(first.pipeline()));
        check("observer handler is present",first.pipeline().names().contains(AttackReducePacketHook.NAME));

        first.pipeline().remove(AttackReducePacketHook.NAME);
        set("hookAttemptTick",-20);
        maintain(player);
        check("external removal self heals",first.pipeline().names().contains(AttackReducePacketHook.NAME));

        Channel second=channel();
        player.field_71174_a=handler(second);
        set("hookAttemptTick",-20);
        maintain(player);
        check("new connection is hooked",ordered(second.pipeline()));
        check("old connection is detached",!first.pipeline().names().contains(AttackReducePacketHook.NAME));
        invoke("detachHook",new Class<?>[]{String.class},"test complete");
        first.close();second.close();
        System.out.println("AttackReduceInstallTest: " + passed + " passed, 0 failed");
    }

    private static Channel channel() {
        Channel channel=new EmbeddedChannel(new Recorder());
        ChannelPipeline p=channel.pipeline();
        p.addLast("timeout",new Recorder());p.addLast("splitter",new Recorder());
        p.addLast("decoder",new Recorder());p.addLast("prepender",new Recorder());
        p.addLast("encoder",new Recorder());p.addLast("packet_handler",new Recorder());
        return channel;
    }
    private static NetHandlerPlayClient handler(Channel channel) {
        NetworkManager manager=new NetworkManager();manager.setChannel(channel);
        return new NetHandlerPlayClient(manager);
    }
    private static boolean ordered(ChannelPipeline pipeline) {
        List<String> names=pipeline.names();
        return names.indexOf("encoder") < names.indexOf(AttackReducePacketHook.NAME)
                && names.indexOf(AttackReducePacketHook.NAME) < names.indexOf("packet_handler");
    }
    private static void maintain(Player p) throws Exception {
        invoke("maintainHook",new Class<?>[]{Class.forName("net.minecraft.client.Minecraft"),Object.class},null,p);
    }
    private static void set(String name,Object value) throws Exception {
        Field f=AttackReduceFeature.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);
    }
    private static void invoke(String name,Class<?>[] types,Object... args) throws Exception {
        Method m=AttackReduceFeature.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(null,args);
    }
    private static void check(String name,boolean ok) {
        if(!ok)throw new AssertionError(name);passed++;
    }
}
