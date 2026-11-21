import io.netty.channel.embedded.EmbeddedChannel;
import module.movement.AttackReduce;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

import java.lang.reflect.Field;
import java.util.Properties;

/** 真实 Netty 上验证 SIMPLE 的百分比缩放与配置；默认模式单独验证。 */
public final class AttackReduceHookTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        AttackReduce module = new AttackReduce();
        check("visible module name", "Velocity".equals(module.getName()));
        check("existing config and keybind preserved", "movement.attackreduce".equals(module.getConfigKey()));
        module.setEnabled(true);
        set("activeModule", module);
        set("active", true);
        set("localEntityId", 7);
        AttackReduceFeature.load(new Properties());
        check("default is slap attack, not raw scaling", AttackReduceFeature.getMode() == AttackReduceFeature.MODE_SLAP_ATTACK);
        AttackReduceFeature.setMode(AttackReduceFeature.MODE_SIMPLE);
        EmbeddedChannel channel = new EmbeddedChannel(new AttackReducePacketHook());

        S12PacketEntityVelocity packet = s12(7);
        channel.writeInbound(packet);
        check("S12 delivered unchanged as object", channel.readInbound() == packet);
        check("default horizontal eliminated", packet.getMotionX() == 0 && packet.getMotionZ() == 0);
        check("default vertical retained", packet.getMotionY() == 2880);

        int[] percentages = {0, 25, 50, 75, 100};
        for (int horizontal : percentages) {
            for (int vertical : percentages) {
                AttackReduceFeature.setHorizontal(horizontal);
                AttackReduceFeature.setVertical(vertical);
                packet = s12(7);
                channel.writeInbound(packet);
                check("delivered at " + horizontal + "/" + vertical, channel.readInbound() == packet);
                check("positive X scaling", packet.getMotionX() == 3200 * horizontal / 100);
                check("negative Z scaling", packet.getMotionZ() == -1600 * horizontal / 100);
                check("Y scaling", packet.getMotionY() == 2880 * vertical / 100);
            }
        }
        AttackReduceFeature.setHorizontal(0);
        AttackReduceFeature.setVertical(0);
        packet = s12(8);
        channel.writeInbound(packet);channel.readInbound();
        check("other entity unchanged", packet.getMotionX() == 3200 && packet.getMotionY() == 2880 && packet.getMotionZ() == -1600);
        module.setEnabled(false);
        packet = s12(7);
        channel.writeInbound(packet);channel.readInbound();
        check("toggle off takes effect before next tick", packet.getMotionX() == 3200 && packet.getMotionY() == 2880);
        module.setEnabled(true);
        set("active", false);
        packet = s12(7);
        channel.writeInbound(packet);channel.readInbound();
        check("disconnected feature leaves packet alone", packet.getMotionX() == 3200);
        set("active", true);set("localEntityId", Integer.MIN_VALUE);
        packet = s12(7);
        channel.writeInbound(packet);channel.readInbound();
        check("unknown local id leaves packet alone", packet.getMotionX() == 3200);

        Object transaction = new S32PacketConfirmTransaction();
        channel.writeInbound(transaction);
        check("transactions pass through", channel.readInbound() == transaction);
        Object movement = new C03PacketPlayer(1);
        channel.writeOutbound(movement);
        check("movement is never held or replaced", channel.readOutbound() == movement);
        check("no attack packets generated", channel.readOutbound() == null);
        channel.close();
        testSettings();
        System.out.println("AttackReduceHookTest: " + passed + " passed, 0 failed");
    }

    private static void testSettings() {
        Properties values = new Properties();
        values.setProperty("attackreduce.mode", "7");
        AttackReduceFeature.load(values);
        check("old mode defaults to slap attack", AttackReduceFeature.getMode() == AttackReduceFeature.MODE_SLAP_ATTACK);
        check("simple percentages retained", AttackReduceFeature.getHorizontal() == 0 && AttackReduceFeature.getVertical() == 100);
        AttackReduceFeature.setMode(AttackReduceFeature.MODE_SIMPLE);
        AttackReduceFeature.setHorizontal(35);
        AttackReduceFeature.setVertical(70);
        String saved = AttackReduceFeature.save();
        check("mode and percentages persist", saved.equals("velocity.mode=1\nvelocity.horizontal=35.0\nvelocity.vertical=70.0\nvelocity.slapExact=true\nvelocity.slapCrosshair=true\nvelocity.chatDebug=true\n"));
        values.setProperty("velocity.mode", "1");
        values.setProperty("velocity.horizontal", "35.0");values.setProperty("velocity.vertical", "70.0");
        AttackReduceFeature.load(values);
        check("percentage reload", AttackReduceFeature.getHorizontal() == 35 && AttackReduceFeature.getVertical() == 70);
        check("mode reload", AttackReduceFeature.getMode() == AttackReduceFeature.MODE_SIMPLE);
        values.setProperty("velocity.mode", "999");
        AttackReduceFeature.load(values);
        check("unknown mode defaults to slap attack", AttackReduceFeature.getMode() == AttackReduceFeature.MODE_SLAP_ATTACK);
        values.setProperty("velocity.mode", "NaN");
        AttackReduceFeature.load(values);
        check("malformed mode defaults to slap attack", AttackReduceFeature.getMode() == AttackReduceFeature.MODE_SLAP_ATTACK);
        values.setProperty("velocity.horizontal", "NaN");values.setProperty("velocity.vertical", "Infinity");
        AttackReduceFeature.load(values);
        check("invalid floating point uses defaults", AttackReduceFeature.getHorizontal() == 0 && AttackReduceFeature.getVertical() == 100);
        values.setProperty("velocity.horizontal", "-5");values.setProperty("velocity.vertical", "105");
        AttackReduceFeature.load(values);
        check("percentages clamped", AttackReduceFeature.getHorizontal() == 0 && AttackReduceFeature.getVertical() == 100);
    }

    private static S12PacketEntityVelocity s12(int id) {
        return new S12PacketEntityVelocity(id, 0.4, 0.36, -0.2);
    }
    private static void set(String name, Object value) throws Exception {
        Field f = AttackReduceFeature.class.getDeclaredField(name);f.setAccessible(true);f.set(null, value);
    }
    private static void check(String label, boolean condition) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
}
