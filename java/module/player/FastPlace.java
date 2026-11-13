package module.player;

import module.Module;
import util.Logger;

/**
 * FastPlace 模块开关。
 *
 * <p>本类位于 core，core 在运行期注入模式下可能由 bootstrap 类加载器加载，
 * 看不到 {@code net.minecraft.*}，因此这里只保存开关状态；真正的右键延迟逻辑在
 * MC 侧的 {@code OpenMaverFeatures.updateFastPlace()} 里执行。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.player.FastPlace -> "player.fastplace"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "player": { "fastplace": { "enabled": false, "key": "F" } }
 * </pre>
 */
public final class FastPlace extends Module {

    public FastPlace() {
        // 配置路径自动推导为 "player.fastplace"
        super("FastPlace");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "快速右键已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "快速右键已禁用");
    }
}
