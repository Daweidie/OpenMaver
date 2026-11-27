package module.movement;

import module.Module;
import util.Logger;

/**
 * SafeWalk（方块边缘潜行）模块开关。
 *
 * <p>独立的边缘潜行功能，任何手持物品和移动场景都能用，不必先开 Scaffold。
 * Scaffold / Normal 默认不接管潜行，可按需组合本模块。
 *
 * <p>完整行为在 MC 侧的 {@code SafeWalkFeature}：站在方块边缘时替玩家按住
 * {@code keyBindSneak}（原版潜行键），由原版自己发 C0B START/STOP_SNEAKING，
 * 因此服务端看到的就是一次普通的潜行输入，不写 motion、不改 rotation、不发任何
 * 伪造包。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.SafeWalk -> "movement.safewalk"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "safewalk": { "enabled": false, "key": "" } }
 * </pre>
 *
 * <p>模块自身的设置（探针前伸距离 / 只在地面 / 需要输入 / 尊重玩家潜行 / 滞回）
 * 由 {@code SafeWalkFeature} 持有，写入 {@code config/openmaver.properties} 的
 * {@code safewalk.*}，在 ClickGUI 里展开 SafeWalk 直接调。
 */
public final class SafeWalk extends Module {

    public SafeWalk() {
        // 配置路径自动推导为 "movement.safewalk"
        super("SafeWalk");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "方块边缘潜行已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "方块边缘潜行已禁用");
    }
}
