import net.minecraft.util.MovementInput;

/** OpenZen NoXZMode：击退挂起 -> 落地攻击 -> Motion PRE 回放其余入站包。 */
final class NoXZMode extends AntiKBMode {
    private Object attackTarget;
    private int attacksRemaining, flagCooldown, suspendTicks, hitCounter;
    private boolean shouldJump, shouldFlushMotion, isInstantAttacking;
    private float instantAttackProgress;
    private AttackReduceFeature.Packet knockbackPacket;

    @Override void onEnable() {
        super.onEnable(); attackTarget = null;
        attacksRemaining = flagCooldown = suspendTicks = hitCounter = 0;
        shouldJump = shouldFlushMotion = isInstantAttacking = false;
        instantAttackProgress = 0; knockbackPacket = null; AttackReduceFeature.restoreTimer();
    }
    @Override void onCorrection() { flagCooldown = 2; }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent event) {
        if (flagCooldown > 0) return;
        AttackReduceFeature.Packet packet = event.packet;
        if (isSuspending) { if (!isAllowedPacket(packet)) delay(event); return; }
        if (!AttackReduceFeature.ownVelocity(packet)) return;
        if (packet.x() != 0 || packet.z() != 0) hitCounter = 2;
        if (packet.y() <= 0) return;
        shouldJump = true;
        Object target = AttackReduceFeature.attackTarget();
        if (AttackReduceFeature.onGround() && AttackReduceFeature.validTarget(target) && AttackReduceFeature.sprinting()) {
            attackTarget = target; attacksRemaining = AttackReduceFeature.getAttackAmount();
            AttackReduceFeature.status("READY");
        } else {
            isSuspending = true; suspendTicks = 0; knockbackPacket = packet;
            AttackReduceFeature.holdMoves(); event.cancel(); AttackReduceFeature.status("ALINK WAIT");
        }
    }
    @Override void onTick() {
        if (hitCounter > 0) hitCounter--;
        if (AttackReduceFeature.ignored()) { attackTarget = null; attacksRemaining = 0; release(); return; }
        if (flagCooldown > 0) { flagCooldown--; attackTarget = null; attacksRemaining = 0; return; }
        if (isSuspending) {
            suspendTicks++;
            boolean instant = AttackReduceFeature.getInstantAttack();
            if (instant && instantAttackProgress < 3.0F) {
                AttackReduceFeature.timer(0.5F);
                instantAttackProgress = Math.min(3.0F, instantAttackProgress + 0.5F);
            } else if (!instant) AttackReduceFeature.restoreTimer();
            boolean ground = AttackReduceFeature.onGround();
            if (!ground && suspendTicks < 12) return;
            AttackReduceFeature.restoreTimer();
            Object target = AttackReduceFeature.attackTarget();
            if (ground && AttackReduceFeature.validTarget(target) && AttackReduceFeature.sprinting()) {
                attackTarget = target; attacksRemaining = AttackReduceFeature.getAttackAmount();
                AttackReduceFeature.sendMovePackets(); applyKnockbackPacket();
                isSuspending = false; suspendTicks = 0; shouldFlushMotion = true;
                if (instant && instantAttackProgress > 0) {
                    attacksRemaining = (int) instantAttackProgress; isInstantAttacking = true;
                    AttackReduceFeature.timer(4.0F);
                } else doAttackSequence();
            } else {
                release();
                if (ground) OpenMaverFeatures.callVoid(AttackReduceFeature.player(), "setSprinting", "func_70031_b", null, Boolean.FALSE);
            }
            return;
        }
        if (isInstantAttacking) {
            instantAttackProgress -= 1.0F;
            if (instantAttackProgress <= 0) { instantAttackProgress = 0; isInstantAttacking = false; AttackReduceFeature.restoreTimer(); }
            else AttackReduceFeature.timer(4.0F);
        }
        if (attacksRemaining > 0 && attackTarget != null) doAttackSequence();
    }
    private void doAttackSequence() {
        if (!AttackReduceFeature.validTarget(attackTarget)) { attackTarget = null; attacksRemaining = 0; return; }
        attacksRemaining--;
        if (!AttackReduceFeature.attack(attackTarget, AttackReduceFeature.getSprintStateCheck())) AttackReduceFeature.status("NOT SPRINTING / ATTACK FAILED");
        if (attacksRemaining == 0) attackTarget = null;
    }
    @Override void onStrafe(MovementInput input) {
        if (hitCounter > 0) input.moveForward = 1;
        if (shouldJump) {
            shouldJump = false;
            if (AttackReduceFeature.onGround() && AttackReduceFeature.sprinting() && !AttackReduceFeature.ignored()
                    && !Boolean.TRUE.equals(AttackReduceFeature.call(AttackReduceFeature.player(), "isPotionActive", "func_82165_m", Integer.valueOf(8)))) input.jump = true;
        }
    }
    @Override void onMotion() { if (shouldFlushMotion) { shouldFlushMotion = false; flushPackets(); } }
    private void applyKnockbackPacket() { if (knockbackPacket != null) { knockbackPacket.handle(); knockbackPacket = null; } }
    @Override int pendingCount() { return super.pendingCount() + (knockbackPacket == null ? 0 : 1); }
    @Override void release() {
        AttackReduceFeature.sendMovePackets(); applyKnockbackPacket();
        isSuspending = false; shouldFlushMotion = !packetQueue.isEmpty();
        suspendTicks = 0; instantAttackProgress = 0; isInstantAttacking = false;
        AttackReduceFeature.restoreTimer(); AttackReduceFeature.status("RELEASED");
    }
    private boolean isAllowedPacket(AttackReduceFeature.Packet p) {
        return p.is("S12PacketEntityVelocity") || p.is("S06PacketUpdateHealth") || p.is("S08PacketPlayerPosLook")
                || p.is("S29PacketSoundEffect") || p.is("S02PacketChat") || p.is("S42PacketCombatEvent")
                || p.is("S2EPacketCloseWindow") || p.is("S19PacketEntityStatus") || p.is("S45PacketTitle") || p.is("S3EPacketTeams") || p.is("S40PacketDisconnect")
                || (p.is("S0BPacketAnimation") && p.id() != AttackReduceFeature.sessionEntityId());
    }
}
