import net.minecraft.util.MovementInput;

/** OpenZen MixMode：选择性入站队列、受伤跳跃、反向输入与 Aura 双击。 */
final class MixMode extends AntiKBMode {
    private AttackReduceFeature.Packet knockbackPacket;
    private boolean shouldAttack, wasSprinting;
    private int airTicks, sprintTick = -1;
    @Override void onEnable() {
        super.onEnable(); knockbackPacket = null; shouldAttack = wasSprinting = false;
        airTicks = 0; sprintTick = -1; AttackReduceFeature.rotationYaw = null;
    }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent event) {
        AttackReduceFeature.Packet packet = event.packet;
        if (AttackReduceFeature.ownVelocity(packet)) {
            knockbackPacket = null; AttackReduceFeature.rotationYaw = null;
            if (packet.y() > 0) {
                sprintTick = 0;
                if (AttackReduceFeature.getTryAttack() && OpenMaverFeatures.velocityAuraTarget() != null) {
                    shouldAttack = true; wasSprinting = AttackReduceFeature.sprinting();
                }
                else if (AttackReduceFeature.getRotate()) AttackReduceFeature.setRotation(packet);
                knockbackPacket = packet; isSuspending = true; delay(event); AttackReduceFeature.status("MIX WAIT");
            }
        } else if (isSuspending && (packet.is("S14PacketEntity") || packet.is("S32PacketConfirmTransaction") || packet.is("S18PacketEntityTeleport"))) delay(event);
    }
    @Override void onTick() {
        if (AttackReduceFeature.ignored()) { release(); onEnable(); return; }
        airTicks = AttackReduceFeature.onGround() ? 0 : airTicks + 1;
    }
    @Override void onSprint() {
        if (!isSuspending) AttackReduceFeature.rotationYaw = null;
        Object p = AttackReduceFeature.player();
        if (knockbackPacket != null && knockbackPacket.y() > 0 && AttackReduceFeature.number(p, "hurtTime", "field_70737_aN") > 0 && shouldAttack) {
            Object target = OpenMaverFeatures.velocityAuraTarget();
            if (target != null) {
                shouldAttack = false;
                AttackReduceFeature.attackMix(target, wasSprinting); AttackReduceFeature.attackMix(target, wasSprinting);
            }
        }
        if (sprintTick >= 0) sprintTick++;
    }
    @Override void onMotion() {
        if (isSuspending && (AttackReduceFeature.onGround() || airTicks >= 24)) { release(); shouldAttack = false; }
    }
    @Override void onStrafe(MovementInput input) {
        if (knockbackPacket == null) return;
        if (AttackReduceFeature.getMovementOverride()) {
            if (sprintTick >= 1 && sprintTick <= 2 && AttackReduceFeature.onGround()) input.jump = true;
            if (sprintTick >= 1 && sprintTick <= 3) {
                double yaw = Math.toDegrees(Math.atan2(knockbackPacket.x(), -knockbackPacket.z()));
                double delta = Math.toRadians(yaw - AttackReduceFeature.number(AttackReduceFeature.player(), "rotationYaw", "field_70177_z"));
                double cos = Math.cos(delta), sin = Math.sin(delta);
                input.moveForward = cos > 0.5 ? 1 : cos < -0.5 ? -1 : 0;
                input.moveStrafe = sin > 0.5 ? -1 : sin < -0.5 ? 1 : 0;
            } else if (sprintTick >= 4 && sprintTick <= 10) input.jump = false;
            if (sprintTick >= 10) sprintTick = -1;
        } else if (AttackReduceFeature.number(AttackReduceFeature.player(), "hurtTime", "field_70737_aN") > 6 && !AttackReduceFeature.moduleEnabled("Scaffold")) input.jump = true;
    }
}
