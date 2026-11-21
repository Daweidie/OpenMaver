import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MovementInputFromOptions;

/**
 * 在原版 {@code updatePlayerMoveState()} 之后插入 Scaffold 的静默转向与 MoveFix。
 *
 * <p>Forge 1.8.9 的 ClientTickEvent.START 早于玩家自己的
 * {@code movementInput.updatePlayerMoveState()}，所以在 START 里改
 * movementInput / rotationYaw 都会被原版覆盖。这个子类在按键输入已经更新、
 * 但 {@code moveFlying()} 还没执行的时间点回调 {@link ScaffoldFeature}：
 *
 * <ol>
 *   <li>把输入向量从“相机坐标系”换算到“aim 坐标系”，再吸附到 8 个整数方向之一
 *       （{@code ../OpenZen} 的 {@code MovementUtil.handleStrafe} 等价物）——
 *       服务端只会用 |-1/0/1| 的输入做预测，连续旋转会直接变成位移误差；</li>
 *   <li>把 aim 写进 {@code rotationYaw/rotationPitch}，本 tick 的 C03 自然带上它；</li>
 *   <li>ClientTickEvent.END（渲染之前）再把 rotation 恢复成相机角度。</li>
 * </ol>
 *
 * <p>模块关闭时本类只执行 {@code super}，不改变任何原版行为。
 */
public final class ScaffoldMovementInput extends MovementInputFromOptions {

    public ScaffoldMovementInput(GameSettings settings) {
        super(settings);
    }

    @Override
    public void updatePlayerMoveState() {
        super.updatePlayerMoveState();
        // 容器移动必须在原版清零之后采样，再交给后续 MoveFix / Velocity。
        InventoryMoveFeature.afterMovementInput(this);
        ScaffoldFeature.afterMovementInput(this);
        // 神桥复用同一个 hook：它的 aim 与 MoveFix 也必须落在 moveFlying() 之前。
        GodBridgeFeature.afterMovementInput(this);
        AttackReduceFeature.afterMovementInput(this);
    }
}
