package module.movement;

import module.Module;
import util.Logger;

/**
 * Scaffold（自动搭路）模块开关。
 *
 * <p>本类位于 core，core 在运行期注入模式下可能由 bootstrap 类加载器加载，
 * 看不到 {@code net.minecraft.*}，因此这里只保存开关状态；真正的搭路逻辑后续放在
 * MC 侧的 {@code OpenMaverFeatures.updateScaffold()} 里执行。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.Scaffold -> "movement.scaffold"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "scaffold": { "enabled": false, "key": "G" } }
 * </pre>
 *
 * <p>MC 侧实现位于 {@code OpenMaverFeatures.updateScaffold()}，设置项通过
 * {@code OpenMaverFeatures} 的 scaffold.* getter/setter 暴露给 ClickGUI。
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
