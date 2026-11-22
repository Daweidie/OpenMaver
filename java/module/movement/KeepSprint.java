package module.movement;

import module.Module;
import util.Logger;

/**
 * KeepSprint 模块开关。
 *
 * <p>与 {@link AutoSprint} 一样，本类位于 core：core 在运行期注入模式下可能由
 * bootstrap 类加载器加载，看不到 {@code net.minecraft.*}，所以这里只保存开关状态；
 * 真正的全方向疾跑逻辑在 MC 侧的 {@code KeepSprintFeature} 里执行。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.KeepSprint ->
 * "movement.keepsprint"}，对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "keepsprint": { "enabled": false, "key": "" } }
 * </pre>
 */
public final class KeepSprint extends Module {

    public KeepSprint() {
        // 配置路径自动推导为 "movement.keepsprint"
        super("KeepSprint");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "全方向疾跑已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "全方向疾跑已禁用");
    }
}
