import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import module.Module;
import module.ModuleManager;
import module.movement.AttackReduce;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Mouse;

import java.util.Properties;
import java.util.function.Consumer;

/** 模拟 vanilla 主线程队列和原版攻击，真实 Netty 验证时序与失败条件。不是 Grim 实服测试。 */
public final class AttackReduceSprintHitTest {
    private static int passed;
    static final AttackReduce MODULE = new AttackReduce();

    public static final class Movement {
        public float moveForward = 1.0F, moveStrafe;
        public boolean sneak;
    }

    public static final class Player extends EntityPlayer {
        public NetHandlerPlayClient sendQueue;
        public boolean field_175171_bO; // 测 SRG serverSprintState，而非按类型猜一个 boolean。
        public boolean sprinting, usingItem, visible = true;
        public boolean isInWeb, water, lava;
        public Movement movementInput = new Movement();
        public EmbeddedChannel channel;
        public final java.util.List<String> chatMessages = new java.util.ArrayList<String>();
        public boolean failChat;
        public void func_145747_a(net.minecraft.util.ChatComponentText message) {
            if (failChat) throw new IllegalStateException("test chat failure");
            chatMessages.add(message.getUnformattedText());
        }
        public boolean func_70051_ag() { return sprinting; }
        public void func_70031_b(boolean value) { sprinting = value; }
        public boolean isInWater() { return water; }
        public boolean isInLava() { return lava; }
        public boolean isUsingItem() { return usingItem; }
        public float getEyeHeight() { return 1.62F; }
        public boolean canEntityBeSeen(net.minecraft.entity.Entity target) { return visible; }
        public void swingItem() { channel.writeOutbound(new C0APacketAnimation()); }
    }

    public static final class Target extends EntityLivingBase {
        public int hurtTime;
        public boolean alive = true;
        public AxisAlignedBB box = new AxisAlignedBB(-0.3, 0, 2.2, 0.3, 1.8, 2.8);
        public AxisAlignedBB getEntityBoundingBox() { return box; }
        public boolean isEntityAlive() { return alive; }
    }

    public static final class Controller {
        public int attacks;
        public boolean applyVanillaSlowdown = true;
        public void attackEntity(Player player, EntityLivingBase target) {
            attacks++;
            player.channel.writeOutbound(new C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK));
            // 对应已核对的 1.8.9 wn.f(pk) 分支；feature 本身没有 motion 写入。
            if (applyVanillaSlowdown && player.sprinting) {
                player.motionX *= 0.6D;
                player.motionZ *= 0.6D;
                player.sprinting = false;
            }
        }
    }

    static final class Fixture implements AutoCloseable {
        final Minecraft mc = new Minecraft();
        final Player player = new Player();
        final Target target = new Target();
        final Controller controller = new Controller();
        final MovingObjectPosition hit = new MovingObjectPosition();
        final EmbeddedChannel channel;
        final Object world = new Object();

        Fixture() {
            // 模拟 PacketThreadUtil：入站先把 vanilla 的 S12 应用任务放入队列。
            ChannelDuplexHandler vanilla = new ChannelDuplexHandler() {
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (msg instanceof S12PacketEntityVelocity) {
                        final S12PacketEntityVelocity packet = (S12PacketEntityVelocity) msg;
                        mc.addScheduledTask(new Runnable() {
                            public void run() {
                                if (packet.getEntityID() == player.getEntityId()) {
                                    player.motionX = packet.getMotionX() / 8000.0D;
                                    player.motionY = packet.getMotionY() / 8000.0D;
                                    player.motionZ = packet.getMotionZ() / 8000.0D;
                                }
                            }
                        });
                    }
                    ctx.fireChannelRead(msg);
                }
            };
            // Netty 4.0 的 EmbeddedChannel 在构造时放入 LastInboundHandler；
            // 必须把测试处理器传给构造器，之后 addLast 会排到收集器后面而收不到包。
            channel = new EmbeddedChannel(vanilla);
            channel.pipeline().addBefore(channel.pipeline().context(vanilla).name(),
                    "packet_handler", new ChannelDuplexHandler());
            player.channel = channel;
            player.setEntityId(7);
            NetworkManager manager = new NetworkManager();
            manager.setChannel(channel);
            player.sendQueue = new NetHandlerPlayClient(manager);
            player.sendQueue.outbound = packet -> channel.writeOutbound(packet);
            reset();
        }

        void reset() {
            Mouse.left = Mouse.right = false;
            player.chatMessages.clear();
            player.failChat = false;
            mc.tasks.clear();
            while (channel.readInbound() != null) { }
            while (channel.readOutbound() != null) { }
            MODULE.setEnabled(true);
            AttackReduceFeature.load(new Properties());
            AttackReduceFeature.setMode(AttackReduceFeature.MODE_SPRINT_HIT);
            mc.thePlayer = player; mc.theWorld = world;
            mc.currentScreen = null; mc.playerController = controller; mc.objectMouseOver = hit;
            hit.typeOfHit = MovingObjectPosition.MovingObjectType.ENTITY; hit.entityHit = target;
            target.box = new AxisAlignedBB(-0.3, 0, 2.2, 0.3, 1.8, 2.8);
            target.alive = true; target.hurtTime = 0;
            player.motionX = player.motionY = player.motionZ = 0;
            player.rotationYaw = player.rotationPitch = 0;
            player.sprinting = player.field_175171_bO = player.visible = true;
            player.usingItem = false;
            player.isInWeb = player.water = player.lava = false;
            player.movementInput.moveForward = 1.0F;
            player.movementInput.moveStrafe = 0.0F;
            player.sendQueue.failAt = player.sendQueue.sent = 0;
            controller.attacks = 0; controller.applyVanillaSlowdown = true;
            tick();
        }

        S12PacketEntityVelocity receive(int id, double x, double y, double z) {
            S12PacketEntityVelocity packet = new S12PacketEntityVelocity(id, x, y, z);
            channel.writeInbound(packet);
            check("S12 forwarded as original object", channel.readInbound() == packet);
            return packet;
        }

        void arm() { receive(7, 0.4, 0.36, -0.2); drain(); }
        void drain() { Runnable task; while ((task = mc.tasks.poll()) != null) task.run(); }
        void tick() { AttackReduceFeature.onTickStart(mc); }
        public void close() {
            MODULE.setEnabled(false); tick(); channel.finish();
        }
    }

    public static void main(String[] args) throws Exception {
        ModuleManager.register(MODULE);
        try (Fixture f = new Fixture()) {
            S12PacketEntityVelocity packet = f.receive(7, 0.4, 0.36, -0.2);
            check("default never rewrites horizontal or vertical", packet.getMotionX() == 3200
                    && packet.getMotionY() == 2880 && packet.getMotionZ() == -1600);
            check("vanilla application then observer are queued (actual=" + f.mc.tasks.size()
                    + ", KB=" + AttackReduceFeature.getKnockbackApplied() + ")", f.mc.tasks.size() == 2);
            f.tick();
            check("no premature attack before vanilla queued task", f.controller.attacks == 0);
            f.mc.tasks.poll().run();
            f.tick();
            check("still waits for after-vanilla marker", f.controller.attacks == 0);
            f.mc.tasks.poll().run();
            f.tick();
            check("one native attack after application", f.controller.attacks == 1);
            check("horizontal slowdown comes from native attack", near(f.player.motionX, 0.24) && near(f.player.motionZ, -0.12));
            check("vertical never changed", near(f.player.motionY, 0.36));
            check("native sprint reset preserved", !f.player.sprinting && f.player.field_175171_bO);
            check("animation precedes attack", f.channel.readOutbound() instanceof C0APacketAnimation
                    && f.channel.readOutbound() instanceof C02PacketUseEntity);
            C03PacketPlayer movement = new C03PacketPlayer(1);
            f.channel.writeOutbound(movement);
            check("movement passes straight through", f.channel.readOutbound() == movement);
            f.player.sprinting = true;
            f.tick();
            check("one attack maximum per S12", f.controller.attacks == 1 && f.channel.readOutbound() == null);
            check("status confirms observed slowdown", "HIT".equals(AttackReduceFeature.getStatus()));

            blocked(f, "local sprint not synced", x -> x.player.field_175171_bO = false);
            blocked(f, "not sprinting", x -> x.player.sprinting = false);
            blocked(f, "GUI open", x -> x.mc.currentScreen = new Object());
            blocked(f, "using item", x -> x.player.usingItem = true);
            blocked(f, "manual left click", x -> Mouse.left = true);
            blocked(f, "manual right click", x -> Mouse.right = true);
            blocked(f, "no crosshair hit", x -> x.mc.objectMouseOver = null);
            blocked(f, "block hit", x -> x.hit.typeOfHit = MovingObjectPosition.MovingObjectType.BLOCK);
            blocked(f, "dead target", x -> x.target.alive = false);
            blocked(f, "occluded target", x -> x.player.visible = false);
            blocked(f, "target in hurt window", x -> x.target.hurtTime = 5);
            blocked(f, "stale ray after turning", x -> x.player.rotationYaw = 90);
            blocked(f, "target outside three blocks", x -> x.target.box = new AxisAlignedBB(-0.3, 0, 3.01, 0.3, 1.8, 3.6));
            blocked(f, "missing controller", x -> x.mc.playerController = null);

            f.reset(); f.arm(); f.player.field_175171_bO = false; f.tick();
            f.player.field_175171_bO = true; f.tick();
            check("waits then attacks once natural sprint sync arrives", f.controller.attacks == 1);

            f.reset(); f.arm(); f.hit.entityHit = null;
            for (int i = 0; i < 6; i++) f.tick();
            f.hit.entityHit = f.target; f.tick();
            check("expired window cannot attack later", f.controller.attacks == 0 && "EXPIRED".equals(AttackReduceFeature.getStatus()));
            f.reset(); f.arm(); f.hit.entityHit = null;
            for (int i = 0; i < 5; i++) f.tick();
            f.hit.entityHit = f.target; f.tick();
            check("sixth tick is included", f.controller.attacks == 1);

            f.reset(); f.controller.applyVanillaSlowdown = false; f.arm(); f.tick();
            check("no local multiplier if native attack did not slow", near(f.player.motionX, 0.4) && near(f.player.motionZ, -0.2));
            check("failed slowdown reported", "NO SLOWDOWN".equals(AttackReduceFeature.getStatus()));

            f.reset(); f.receive(8, 0.4, 0.36, -0.2); f.drain(); f.tick();
            check("other entity does not arm window", f.controller.attacks == 0);
            f.reset(); f.receive(7, 0, 0.36, 0); f.drain(); f.tick();
            check("vertical-only S12 does not attack", f.controller.attacks == 0);
            f.reset(); f.receive(7, 0.4, 0.36, -0.2);
            MODULE.setEnabled(false); f.drain(); f.tick();
            check("disabled module drops pending callback", f.controller.attacks == 0);
            f.reset(); f.receive(7, 0.4, 0.36, -0.2);
            f.mc.theWorld = new Object(); f.drain(); f.tick();
            check("changed world drops pending callback", f.controller.attacks == 0);
            f.reset(); f.receive(7, 0.4, 0.36, -0.2);
            AttackReduceFeature.setMode(AttackReduceFeature.MODE_SIMPLE);
            AttackReduceFeature.setMode(AttackReduceFeature.MODE_SPRINT_HIT);
            f.drain(); f.tick();
            check("mode toggle invalidates old callback", f.controller.attacks == 0);
            f.reset(); f.receive(7, 0.4, 0.36, -0.2);
            MODULE.setEnabled(false); f.tick(); MODULE.setEnabled(true); f.tick();
            f.drain(); f.tick();
            check("disable and re-enable invalidates old callback", f.controller.attacks == 0);

            Module other = new Module("KillAura", "combat.killaura") { };
            ModuleManager.register(other);
            f.reset(); other.setEnabled(true); f.arm(); f.tick();
            check("yields to existing combat module", f.controller.attacks == 0);
            other.setEnabled(false);
        }
        System.out.println("AttackReduceSprintHitTest: " + passed + " passed, 0 failed");
    }

    private static void blocked(Fixture f, String label, Consumer<Fixture> change) {
        f.reset(); f.arm(); change.accept(f); f.tick();
        check(label + " leaves vanilla motion", f.controller.attacks == 0
                && near(f.player.motionX, 0.4) && near(f.player.motionY, 0.36) && near(f.player.motionZ, -0.2)
                && f.channel.readOutbound() == null);
    }
    private static boolean near(double a, double b) { return Math.abs(a - b) < 1.0E-8; }
    private static void check(String label, boolean condition) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
}
