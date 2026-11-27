import module.ModuleManager;

import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.MovementInput;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * KeepSprint 运行期行为测试：用替身玩家跑真实的 {@code onPlayerTickEnd} 反射路径。
 *
 * <p>验证的核心是“侧移 / 后退补原版 +30%，前进交给原版、不叠双份”：
 *
 * <ol>
 *   <li>后退：挂上独立 UUID 的 movementSpeed modifier、jumpMovementFactor ×1.3、
 *       强制冲刺，且不额外发包；</li>
 *   <li>前进：移除自己的 modifier（原版的会继续生效），jumpMovementFactor 不动；</li>
 *   <li>攻击：同一个 tick 的 END 补一条 C0B START_SPRINTING；</li>
 *   <li>潜行让位 / 关闭模块：摘掉 modifier，不再改冲刺状态。</li>
 * </ol>
 *
 * <p>用法：{@code tools/keepsprint-test/run.sh}
 */
public final class KeepSprintRuntimeTest {

    static final UUID VANILLA_SPRINT_UUID = UUID.fromString("662a6b8d-da3e-4c1c-8813-96ea6097278d");
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("KeepSprint runtime behaviour suite");
        System.out.println("----------------------------------");

        useLegacyOmni();

        module.movement.KeepSprint module = new module.movement.KeepSprint();
        ModuleManager.register(module);
        module.setEnabled(true);

        FakePlayer player = new FakePlayer();

        // 1) 后退：本地补 +30%，空中因子同步，冲刺状态被强制恢复。
        player.movementInput.moveForward = -0.98F;
        player.sprinting = false;
        player.jumpMovementFactor = 0.02F;
        KeepSprintFeature.onPlayerTickEnd(player);
        check("backwards applies the independent modifier",
                player.attribute.modifier instanceof AttributeModifier
                        && KeepSprintFeature.BOOST_UUID.equals(
                                ((AttributeModifier) player.attribute.modifier).getID()));
        check("backwards forces the sprint flag", player.sprinting);
        check("backwards boosts the air movement factor",
                close(player.jumpMovementFactor, 0.026F));
        check("backwards reports RUN", "RUN".equals(KeepSprintFeature.getStatus()));
        check("backwards sends no packet", player.sendQueue.packets.isEmpty());

        // 2) 后退的第二 tick：原版会重算 jumpMovementFactor，不应复利叠加。
        player.jumpMovementFactor = 0.02F;
        KeepSprintFeature.onPlayerTickEnd(player);
        check("air factor is applied once per tick, not compounded",
                close(player.jumpMovementFactor, 0.026F));

        // 3) 前进：原版自己会保持冲刺，只移除我们的 modifier。
        player.movementInput.moveForward = 0.98F;
        player.jumpMovementFactor = 0.02F;
        KeepSprintFeature.onPlayerTickEnd(player);
        check("forward removes the independent modifier",
                player.attribute.modifier == null);
        check("forward keeps the sprint flag", player.sprinting);
        check("forward does not touch the air factor",
                close(player.jumpMovementFactor, 0.02F));
        check("forward reports PACKET", "PACKET".equals(KeepSprintFeature.getStatus()));

        // 4) 斜向前进保持原始 forward 分量，原版会保持冲刺。
        player.movementInput.moveForward = 0.98F;
        player.movementInput.moveStrafe = 0.98F;
        KeepSprintFeature.onPlayerTickEnd(player);
        check("diagonal forward uses vanilla without an independent modifier",
                player.attribute.modifier == null);
        player.movementInput.moveStrafe = 0.0F;

        // 5) 攻击后补 C0B START_SPRINTING。
        int before = player.sendQueue.packets.size();
        player.movementInput.moveForward = -0.98F;
        KeepSprintFeature.onAttack(player);
        KeepSprintFeature.onPlayerTickEnd(player);
        check("attack queues exactly one C0B packet",
                player.sendQueue.packets.size() == before + 1);
        Object packet = player.sendQueue.packets.get(player.sendQueue.packets.size() - 1);
        check("the queued packet is START_SPRINTING",
                packet instanceof C0BPacketEntityAction
                        && ((C0BPacketEntityAction) packet).action
                                == C0BPacketEntityAction.Action.START_SPRINTING);


        // 6) 潜行让位：摘掉 modifier，但不主动清除玩家的冲刺。
        player.movementInput.sneak = true;
        player.movementInput.moveForward = -0.98F;
        KeepSprintFeature.onPlayerTickEnd(player);
        check("pause sneak removes the independent modifier",
                player.attribute.modifier == null);
        check("pause sneak reports IDLE", "IDLE".equals(KeepSprintFeature.getStatus()));
        check("pause sneak leaves the sprint flag alone", player.sprinting);

        // 7) 使用物品让位。
        player.movementInput.sneak = false;
        player.usingItem = true;
        KeepSprintFeature.onPlayerTickEnd(player);
        check("pause item removes the independent modifier",
                player.attribute.modifier == null);
        check("pause item reports IDLE", "IDLE".equals(KeepSprintFeature.getStatus()));
        player.usingItem = false;

        // 8) 关闭模块：摘掉 modifier，状态回到 OFF。
        player.movementInput.moveForward = -0.98F;
        KeepSprintFeature.onPlayerTickEnd(player);
        check("module on re-applies the modifier", player.attribute.modifier != null);
        module.setEnabled(false);
        KeepSprintFeature.onPlayerTickEnd(player);
        check("disable removes the modifier", player.attribute.modifier == null);
        check("disable reports OFF", "OFF".equals(KeepSprintFeature.getStatus()));

        System.out.println("----------------------------------");
        System.out.println("checks: " + (passed + failed)
                + "  passed: " + passed
                + "  failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < 1.0E-5F;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    private KeepSprintRuntimeTest() {
    }

    /** Exercise the retained OMNI branch explicitly; user configuration always selects silent rotation. */
    static void useLegacyOmni() {
        KeepSprintFeature.reset();
        KeepSprintFeature.load(new java.util.Properties());
        try {
            java.lang.reflect.Field rotate = KeepSprintFeature.class.getDeclaredField("rotate");
            rotate.setAccessible(true);
            rotate.setBoolean(null, false);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    /** 替身玩家：只保留 KeepSprintFeature 反射会碰到的成员。 */
    static final class FakePlayer extends EntityPlayerSP {
        public final MovementInput movementInput = new MovementInput();
        public final FakeQueue sendQueue = new FakeQueue();
        final FakeAttribute attribute = new FakeAttribute();
        final Caps capabilities = new Caps();

        float jumpMovementFactor = 0.02F;
        boolean isCollidedHorizontally;
        boolean sprinting;
        boolean riding;
        boolean usingItem;

        @Override
        public boolean isEntityAlive() {
            return true;
        }

        public boolean isSprinting() {
            return sprinting;
        }

        public void setSprinting(boolean value) {
            this.sprinting = value;
            attribute.vanillaModifier = value
                    ? new AttributeModifier(VANILLA_SPRINT_UUID, "Sprinting speed boost",
                            0.30000001192092896D, 2) : null;
        }

        public boolean isRiding() {
            return riding;
        }

        public boolean isUsingItem() {
            return usingItem;
        }

        public Object getFoodStats() {
            return new FoodStats();
        }

        public Object getEntityAttribute(Object attributeObject) {
            return attribute;
        }

    }

    static final class Caps {
        public boolean isFlying;
        public boolean allowFlying;
    }

    static final class FoodStats {
        public int getFoodLevel() {
            return 20;
        }
    }

    static final class FakeAttribute {
        Object modifier;
        AttributeModifier vanillaModifier;

        public Object getModifier(UUID id) {
            return VANILLA_SPRINT_UUID.equals(id) ? vanillaModifier : modifier;
        }

        public void applyModifier(Object value) {
            modifier = value;
        }

        public void removeModifier(Object value) {
            modifier = null;
        }

        double value() {
            double speed = 0.1D;
            if (vanillaModifier != null) speed *= 1.0D + vanillaModifier.getAmount();
            if (modifier instanceof AttributeModifier) {
                speed *= 1.0D + ((AttributeModifier) modifier).getAmount();
            }
            return speed;
        }
    }

    static final class FakeQueue {
        final List<Object> packets = new ArrayList<Object>();

        public void addToSendQueue(Object packet) {
            packets.add(packet);
        }
    }
}
