package module.movement;

import module.Module;
import util.Logger;

/**
 * Scaffold（自动搭路 / 神桥）模块开关。
 *
 * <p>由原 {@code GodBridge} 重命名而来，行为参考 {@code OpenMyau-Fix} 的
 * {@code Scaffold}：开启后根据移动方向自动在脚下/前方放置方块，支持自动转向、
 * 自动跳跃与快速右键。真正的放置逻辑在 MC 侧的
 * {@code ScaffoldFeature}（ClientTickEvent + ScaffoldMovementInput）。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.Scaffold -> "movement.scaffold"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "scaffold": { "enabled": false, "key": "B" } }
 * </pre>
 */
public final class Scaffold extends Module {

    public Scaffold() {
        // 配置路径自动推导为 "movement.scaffold"
        super("Scaffold");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "自动搭路已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "自动搭路已禁用");
    }
}
