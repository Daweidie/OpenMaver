import net.minecraft.util.MovementInput;

/** NoXZ：击退立即应用，仅等待落地后的攻击机会，不缓存入站或出站包。 */
final class NoXZMode extends AntiKBMode {
    private static final int MAX_LANDING_TICKS = 12;
    private Object attackTarget;
    private int attacksRemaining, flagCooldown, landingTicks, hitCounter;
    private boolean waitingForLanding;

    @Override void onEnable() {
        super.onEnable(); attackTarget = null;
        attacksRemaining = flagCooldown = landingTicks = hitCounter = 0;
        waitingForLanding = false; AttackReduceFeature.restoreTimer();
    }
    @Override void onCorrection() { flagCooldown = 2; }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent event) {
        AttackReduceFeature.Packet packet = event.packet;
        if (!AttackReduceFeature.ownVelocity(packet)) return;
        // 新速度是新的物理边界；即使 Y <= 0，也不能继续上一次受击的补刀。
        clearAttacks();
        if (flagCooldown > 0) { VelocityDebug.knockbackIn(packet, "PASS COOLDOWN"); return; }
        if (packet.x() != 0 || packet.z() != 0) hitCounter = 2;
        if (packet.y() <= 0) { VelocityDebug.knockbackIn(packet, false); return; }
        Object target = AttackReduceFeature.attackTarget();
        boolean ground = AttackReduceFeature.onGround();
        boolean hasTarget = AttackReduceFeature.validTarget(target);
        boolean sprinting = AttackReduceFeature.sprinting();
        boolean ready = ground && hasTarget && sprinting;
        // 地面和空中都不取消 S12：服务端从发包当拍就开始预测击退。
        // 空中只保留落地补刀意图，不能用挂起/回放延后物理或挤出移动包簇。
        String tag = ready ? "PASS READY" : !ground ? "PASS AIR / LANDING WAIT"
                : !hasTarget ? "PASS NO TARGET" : !sprinting ? "PASS NOT SPRINTING" : "PASS";
        VelocityDebug.knockbackIn(packet, tag);
        if (ready) {
            attackTarget = target; attacksRemaining = AttackReduceFeature.getAttackAmount();
            AttackReduceFeature.status("READY");
        } else if (!ground) {
            waitingForLanding = true; AttackReduceFeature.status("LANDING WAIT");
        } else {
            AttackReduceFeature.status("PASS GROUND");
        }
    }
    @Override void onTick() {
        if (hitCounter > 0) hitCounter--;
        if (AttackReduceFeature.ignored()) { release(); return; }
        if (flagCooldown > 0) { flagCooldown--; clearAttacks(); return; }
        if (waitingForLanding) {
            landingTicks++;
            boolean ground = AttackReduceFeature.onGround();
            if (!ground && landingTicks < MAX_LANDING_TICKS) return;
            Object target = AttackReduceFeature.attackTarget();
            // AutoSprint 在准备阶段之后更新；实际疾跑检查放到 onSprint 的攻击入口。
            if (ground && AttackReduceFeature.validTarget(target)) {
                attackTarget = target;
                // 保留旧 Instant Attack 配置的补刀数量，每正常 tick 最多一次，不改 Timer。
                attacksRemaining = AttackReduceFeature.getAttackAmount()
                        + (AttackReduceFeature.getInstantAttack() ? Math.min(3, landingTicks / 2) : 0);
                waitingForLanding = false; landingTicks = 0;
                AttackReduceFeature.status("LANDING READY");
            } else {
                clearAttacks();
                AttackReduceFeature.status(ground ? "PASS NO TARGET" : "PASS AIR TIMEOUT");
            }
            return;
        }
    }
    @Override void onSprint() {
        if (attacksRemaining > 0 && attackTarget != null) doAttackSequence();
    }
    private void doAttackSequence() {
        if (!AttackReduceFeature.validTarget(attackTarget)) { attackTarget = null; attacksRemaining = 0; return; }
        if (OpenMaverFeatures.hasEntityAttackThisTick()) return;
        attacksRemaining--;
        if (!AttackReduceFeature.attack(attackTarget, AttackReduceFeature.getSprintStateCheck())) AttackReduceFeature.status("NOT SPRINTING / ATTACK FAILED");
        if (attacksRemaining == 0) attackTarget = null;
    }
    @Override void onStrafe(MovementInput input) {
        if (hitCounter > 0) {
            AttackReduceFeature.claimMovementInput(input);
            input.moveForward = 1;
        }
    }
    @Override void release() {
        clearAttacks(); hitCounter = 0;
        AttackReduceFeature.restoreTimer(); AttackReduceFeature.status("RELEASED");
    }
    private void clearAttacks() {
        attackTarget = null; attacksRemaining = landingTicks = 0; waitingForLanding = false;
    }
}
