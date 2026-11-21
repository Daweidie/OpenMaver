import net.minecraft.util.MovementInput;

/** OpenZen JumpResetMode AIR/GROUND 状态机；原始速度包总是回放。 */
final class JumpResetMode extends AntiKBMode {
    private enum Phase { IDLE, AIR, GROUND }
    private Phase phase = Phase.IDLE;
    private int delayTicks, jumpTicks, rotationHeldTicks;
    private AttackReduceFeature.Packet knockbackPacket;
    @Override void onEnable() {
        super.onEnable(); phase = Phase.IDLE; delayTicks = jumpTicks = rotationHeldTicks = 0;
        knockbackPacket = null; AttackReduceFeature.rotationYaw = null;
    }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent event) {
        if (suspended()) { release(); onEnable(); return; }
        AttackReduceFeature.Packet packet = event.packet;
        if (isSuspending && !packet.is("S02PacketChat") && !packet.is("S03PacketTimeUpdate")) { delay(event); return; }
        if (!AttackReduceFeature.ownVelocity(packet)) return;
        knockbackPacket = packet; isSuspending = true;
        phase = AttackReduceFeature.onGround() ? Phase.GROUND : Phase.AIR;
        delayTicks = phase == Phase.GROUND ? 10 : 20;
        if (phase == Phase.AIR && wantsRotation()) AttackReduceFeature.setRotation(packet);
        delay(event); AttackReduceFeature.status(phase == Phase.GROUND ? "GROUND WAIT" : "AIR WAIT");
    }
    @Override void onTick() {
        if (AttackReduceFeature.ignored() || suspended()) { release(); onEnable(); return; }
        if (isSuspending) {
            if (phase == Phase.AIR && AttackReduceFeature.onGround()) { release(); phase = Phase.IDLE; }
            else if (delayTicks > 0) delayTicks--;
            else {
                Phase previous = phase; release(); phase = Phase.IDLE;
                if (previous == Phase.GROUND) {
                    if (wantsRotation() && knockbackPacket != null) AttackReduceFeature.setRotation(knockbackPacket);
                    rotationHeldTicks = 0; jumpTicks = 1;
                }
            }
        }
        if (AttackReduceFeature.rotationYaw != null) rotationHeldTicks++;
        if (AttackReduceFeature.number(AttackReduceFeature.player(), "hurtTime", "field_70737_aN") == 0
                || rotationHeldTicks > AttackReduceFeature.getRotateTicks() || !wantsRotation()) {
            AttackReduceFeature.rotationYaw = null; rotationHeldTicks = 0;
        }
    }
    @Override void onStrafe(MovementInput input) {
        if (suspended()) { release(); onEnable(); return; }
        if (jumpTicks > 0 && !AttackReduceFeature.moduleEnabled("Scaffold")) { input.jump = true; jumpTicks--; }
        if (AttackReduceFeature.getFollowDirection() && AttackReduceFeature.rotationYaw != null) { input.moveForward = 1; input.moveStrafe = 0; }
    }
    private boolean wantsRotation() { return AttackReduceFeature.getRotate() || AttackReduceFeature.getFollowDirection(); }
    private boolean suspended() { return AttackReduceFeature.jumpResetSuspended(); }
}
