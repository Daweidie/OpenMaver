package module.movement;

import module.Module;
import module.ModuleManager;
import util.Logger;

/**
 * GodBridge（神桥 / 蹲起搭路）模块开关。
 *
 * <p>本模块按“手动搭桥教材”重写，和 {@link Scaffold} 是两套独立的实现：
 *
 * <ul>
 *   <li><b>SPEED BRIDGE</b>：Shift + S，Shift 一直按住，1.67 方块/秒；</li>
 *   <li><b>DIAGONAL 45</b>：Shift + S + D，视角与桥成 45°，约 2 方块/秒；</li>
 *   <li><b>SNEAK TOGGLE</b>：蹲起搭路，放块后立刻松 Shift、贴边再按回去，
 *       平均约 3 方块/秒。</li>
 * </ul>
 *
 * <p>完整行为在 MC 侧的 {@code GodBridgeFeature}：静默转向点方块边缘、贴边判定、
 * 松/按 Shift 的节拍、C08 迟一 tick 校验、HUD 实测 b/s。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.GodBridge ->
 * "movement.godbridge"}，对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "godbridge": { "enabled": false, "key": "N" } }
 * </pre>
 */
public final class GodBridge extends Module {

    public GodBridge() {
        super("GodBridge");
    }

    @Override
    public void onEnable() {
        Module telly = ModuleManager.getModule("Telly");
        if (telly != null) telly.setEnabled(false);
        // 两套搭桥同时开会让两条状态机抢同一个 C08 名额与 Shift 键。
        // 神桥优先：开启时把旧的 Scaffold 关掉（配置里仍可单独再开回来）。
        Module scaffold = ModuleManager.getModule("Scaffold");
        if (scaffold != null && scaffold.isEnabled()) {
            scaffold.setEnabled(false);
            Logger.info(getName(), "已关闭 Scaffold（神桥接管搭桥）");
        }
        Logger.info(getName(), "神桥已启用（蹲起搭路）");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "神桥已禁用");
    }
}
