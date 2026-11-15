import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MovementInputFromOptions;

/**
 * 在原版 {@code updatePlayerMoveState()} 之后插入 Scaffold 的静默转向。
 *
 * <p>Forge 1.8.9 的 ClientTickEvent.START 早于玩家自己的
 * {@code movementInput.updatePlayerMoveState()}，所以在 START 里改
 * movementInput / rotationYaw 都会被原版覆盖。这个子类在按键输入已经
 * 更新、但 {@code moveFlying()} 还没执行的时间点回调 ScaffoldFeature：
 * 先按 aim 与真实相机的差值旋转输入向量，再把 aim 写入 rotationYaw/Pitch。
 * 这样本 tick 的 WASD 仍然按相机方向移动，而 C03 会带上搭路视角。
 *
 * <p>模块关闭时本类只执行 super，不改变任何原版行为。
 */
public final class ScaffoldMovementInput extends MovementInputFromOptions {

    public ScaffoldMovementInput(GameSettings settings) {
        super(settings);
    }

    @Override
    public void updatePlayerMoveState() {
        super.updatePlayerMoveState();
        ScaffoldFeature.afterMovementInput(this);
    }
}
