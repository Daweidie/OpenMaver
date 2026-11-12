package module.movement;

import module.Module;
import util.Logger;

/**
 * AutoSprint 模块开关。
 *
 * <p>本类位于 core，core 在运行期注入模式下可能由 bootstrap 类加载器加载，
 * 看不到 {@code net.minecraft.*}，因此这里只保存开关状态；真正的疾跑逻辑在
 * MC 侧的 {@code OpenMaverFeatures.updateAutoSprint()} 里执行。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.AutoSprint -> "movement.autosprint"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "autosprint": { "enabled": false } }
 * </pre>
 */
public final class AutoSprint extends Module {

    public AutoSprint() {
        // 配置路径自动推导为 "movement.autosprint"
        super("AutoSprint");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "自动疾跑已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "自动疾跑已禁用");
    }
}
