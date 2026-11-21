import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.util.MovementInput;

/** 输入式重置：原始击退始终直通，只短暂接管输入，不缓存包或改写速度。 */
final class JumpResetMode extends AntiKBMode {
    private static final int SPRINT_TICKS = 2;
    private final boolean sprintReset;
    private boolean pendingJump, sprintOwned;
    private int readyTick, expiresTick, sprintUntilTick;
    private Object sprintPlayer;
    private MovementInput overriddenInput;
    private float forwardBefore, strafeBefore;
    private boolean jumpBefore, wroteMovement, wroteJump;

    JumpResetMode(boolean sprintReset) { this.sprintReset = sprintReset; }

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
                && AttackReduceFeature.onGround() && !blocked()
                && ThreadLocalRandom.current().nextInt(100) < AttackReduceFeature.getChance()) {
            // Accuracy 为准时响应的概率；失准只推迟输入 1–2 tick，绝不推迟 S12。
            int delay = ThreadLocalRandom.current().nextInt(100) < AttackReduceFeature.getAccuracy()
                    ? 0 : ThreadLocalRandom.current().nextInt(1, 3);
            readyTick = AttackReduceFeature.tickId() + delay;
            expiresTick = readyTick + 3;
            pendingJump = true;
            tag = sprintReset ? "PASS SPRINT RESET READY" : "PASS JUMP RESET READY";
        }
        AttackReduceFeature.status(tag);
        VelocityDebug.knockbackIn(packet, tag);
    }

    @Override void onTick() {
        restoreInput();
        if (blocked() || pendingJump && (!AttackReduceFeature.onGround()
                || AttackReduceFeature.tickId() >= expiresTick)) { clear(); return; }
        if (sprintPlayer != null && AttackReduceFeature.tickId() >= sprintUntilTick) {
            restoreSprint(); AttackReduceFeature.status("RESET DONE");
        }
    }

    @Override void onStrafe(MovementInput input) {
        if (blocked() || input.sneak) { clear(); return; }
        int tick = AttackReduceFeature.tickId();
        boolean jump = pendingJump && tick >= readyTick;
        if (jump) {
            pendingJump = false;
            if (!AttackReduceFeature.onGround() || tick >= expiresTick) { clear(); return; }
            if (sprintReset) {
                sprintPlayer = AttackReduceFeature.player();
                sprintOwned = !AttackReduceFeature.sprinting();
                sprintUntilTick = tick + SPRINT_TICKS;
            }
        }
        boolean sprint = sprintPlayer != null && tick < sprintUntilTick;
        if (!jump && !sprint) return;
        overriddenInput = input;
        forwardBefore = input.moveForward; strafeBefore = input.moveStrafe; jumpBefore = input.jump;
        wroteMovement = sprint; wroteJump = jump;
        if (sprint) {
            input.moveForward = 1; input.moveStrafe = 0;
            OpenMaverFeatures.callVoid(sprintPlayer, "setSprinting", "func_70031_b", null, Boolean.TRUE);
        }
        if (jump) input.jump = true;
        AttackReduceFeature.status(sprint ? "SPRINT RESET" : "JUMP RESET");
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
        restoreInput(); restoreSprint(); pendingJump = false; readyTick = expiresTick = 0;
    }
}
