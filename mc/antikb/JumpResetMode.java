import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.util.MovementInput;

/** 原始击退直通；地面使用跳跃输入，空中仅通过原版疾跑攻击产生水平减速。 */
final class JumpResetMode extends AntiKBMode {
    private static final int SPRINT_TICKS = 2;
    private boolean pendingReset, pendingAirborne, airReset, sprintOwned;
    private int readyTick, expiresTick, sprintUntilTick;
    private Object sprintPlayer, airTarget;
    private MovementInput overriddenInput;
    private float forwardBefore, strafeBefore;
    private boolean jumpBefore, wroteMovement, wroteJump;

    @Override void onEnable() { clear(); super.onEnable(); }
    @Override void onDisable() { clear(); super.onDisable(); }
    @Override void release() { clear(); super.release(); }

    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent event) {
        AttackReduceFeature.Packet packet = event.packet;
        if (!AttackReduceFeature.ownVelocity(packet)) return;
        // 每个新 S12 都终止旧意图；零速度/下落速度也不能留下旧跳跃。
        clear();
        String tag = "PASS RESET SKIPPED";
        if (packet.y() > 0 && (packet.x() != 0 || packet.z() != 0)
                && !blocked()
                && ThreadLocalRandom.current().nextInt(100) < AttackReduceFeature.getChance()) {
            // Accuracy 为准时响应的概率；失准只推迟输入 1–2 tick，绝不推迟 S12。
            int delay = ThreadLocalRandom.current().nextInt(100) < AttackReduceFeature.getAccuracy()
                    ? 0 : ThreadLocalRandom.current().nextInt(1, 3);
            readyTick = AttackReduceFeature.tickId() + delay;
            expiresTick = readyTick + 3;
            pendingReset = true;
            pendingAirborne = !AttackReduceFeature.onGround();
            tag = pendingAirborne ? "PASS AIR RESET READY" : "PASS JUMPRESET READY";
        }
        AttackReduceFeature.status(tag);
        VelocityDebug.knockbackIn(packet, tag);
    }

    @Override void onTick() {
        restoreInput();
        if (blocked() || invalidReset() || airReset && AttackReduceFeature.onGround()) { clear(); return; }
        if (pendingReset && !AttackReduceFeature.onGround()) pendingAirborne = true;
        if (sprintPlayer != null && AttackReduceFeature.tickId() >= sprintUntilTick) {
            clear(); AttackReduceFeature.status("RESET DONE");
        }
    }

    @Override void onStrafe(MovementInput input) {
        if (blocked() || input.sneak || invalidReset() || airReset && AttackReduceFeature.onGround()) { clear(); return; }
        if (pendingReset && !AttackReduceFeature.onGround()) pendingAirborne = true;
        int tick = AttackReduceFeature.tickId();
        boolean reset = pendingReset && tick >= readyTick;
        boolean jump = reset && AttackReduceFeature.onGround();
        if (reset) {
            pendingReset = pendingAirborne = false;
            sprintPlayer = AttackReduceFeature.player();
            sprintOwned = !AttackReduceFeature.sprinting();
            sprintUntilTick = tick + SPRINT_TICKS;
            airReset = !jump;
            airTarget = airReset ? AttackReduceFeature.attackTarget() : null;
        }
        boolean sprint = sprintPlayer != null && tick < sprintUntilTick;
        if (!reset && !sprint) return;
        overriddenInput = input;
        forwardBefore = input.moveForward; strafeBefore = input.moveStrafe; jumpBefore = input.jump;
        wroteMovement = sprint; wroteJump = jump;
        if (sprint) {
            input.moveForward = 1; input.moveStrafe = 0;
            OpenMaverFeatures.callVoid(sprintPlayer, "setSprinting", "func_70031_b", null, Boolean.TRUE);
        }
        if (jump) input.jump = true;
        if (reset) {
            String action = !airReset ? "JUMPRESET" : airTarget == null ? "AIR MOVE / NO TARGET" : "AIR MOVE";
            AttackReduceFeature.status(action); VelocityDebug.resetAction(action);
        }
        tryAirAttack();
    }

    private void tryAirAttack() {
        if (airTarget == null) return;
        if (!AttackReduceFeature.validTarget(airTarget)) {
            airTarget = null;
            AttackReduceFeature.status("AIR MOVE / NO TARGET");
            VelocityDebug.resetAction("AIR MOVE / NO TARGET"); return;
        }
        // 和 AutoClicker/Aura 共用名额；忙时仅在当前 2 tick 输入窗口内等待。
        if (OpenMaverFeatures.hasEntityAttackThisTick()) {
            AttackReduceFeature.status("AIR ATTACK WAIT"); return;
        }
        Object target = airTarget;
        airTarget = null; // 至多一次攻击尝试；失败也不补写速度或反复攻击。
        boolean attacked = AttackReduceFeature.attack(target, true);
        String action = attacked ? "AIR ATTACK" : "AIR MOVE / ATTACK FAILED";
        AttackReduceFeature.status(action); VelocityDebug.resetAction(action);
    }

    private boolean invalidReset() {
        return pendingReset && (AttackReduceFeature.tickId() >= expiresTick
                || pendingAirborne && AttackReduceFeature.onGround());
    }

    private boolean blocked() {
        return AttackReduceFeature.ignored() || AttackReduceFeature.jumpResetSuspended()
                || AttackReduceFeature.moduleEnabled("Scaffold") || AttackReduceFeature.moduleEnabled("GodBridge")
                || AttackReduceFeature.read(AttackReduceFeature.mc(), "currentScreen", "field_71462_r") != null
                || AttackReduceFeature.truth(AttackReduceFeature.player(), "isUsingItem", "func_71039_bw")
                || AttackReduceFeature.truth(AttackReduceFeature.player(), "isSneaking", "func_70093_af")
                || AttackReduceFeature.getOnlyWhenTargeting()
                    && !AttackReduceFeature.validTarget(AttackReduceFeature.attackTarget());
    }

    private void restoreInput() {
        if (overriddenInput != null) {
            if (wroteMovement) {
                if (overriddenInput.moveForward == 1) overriddenInput.moveForward = forwardBefore;
                if (overriddenInput.moveStrafe == 0) overriddenInput.moveStrafe = strafeBefore;
            }
            if (wroteJump && overriddenInput.jump) overriddenInput.jump = jumpBefore;
        }
        overriddenInput = null; wroteMovement = wroteJump = false;
    }

    private void restoreSprint() {
        if (sprintOwned) OpenMaverFeatures.callVoid(sprintPlayer, "setSprinting", "func_70031_b", null, Boolean.FALSE);
        sprintPlayer = null; sprintOwned = false; sprintUntilTick = 0;
    }

    private void clear() {
        restoreInput(); restoreSprint(); pendingReset = pendingAirborne = airReset = false;
        airTarget = null; readyTick = expiresTick = 0;
    }
}
