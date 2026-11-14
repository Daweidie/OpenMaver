package module.movement;

import module.Module;
import util.Logger;

/**
 * GodBridge（神桥）模块开关。
 *
 * <p>本类位于 core，运行期注入模式下可能由 bootstrap 类加载器加载，
 * 看不到 {@code net.minecraft.*}，所以这里只保存开关状态；
 * 真正的神桥触发与放置逻辑在 MC 侧的 {@code OpenMaverFeatures.updateGodBridge()}。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.GodBridge -> "movement.godbridge"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "godbridge": { "enabled": false, "key": "B" } }
 * </pre>
 */
public final class GodBridge extends Module {

    public GodBridge() {
        // 配置路径自动推导为 "movement.godbridge"
        super("GodBridge");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "神桥已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "神桥已禁用");
    }
}
