package module.combat;

import module.Module;
import util.Logger;

/**
 * AutoClicker 模块开关。
 *
 * <p>本类位于 core，core 在运行期注入模式下可能由 bootstrap 类加载器加载，
 * 看不到 {@code net.minecraft.*}，因此这里只保存开关状态；真正的鼠标点击逻辑在
 * MC 侧的 {@code OpenMaverFeatures.updateAutoClicker()} 里执行。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.combat.AutoClicker -> "combat.autoclicker"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "combat": { "autoclicker": { "enabled": false, "key": "R" } }
 * </pre>
 */
public final class AutoClicker extends Module {

    public AutoClicker() {
        // 配置路径自动推导为 "combat.autoclicker"
        super("AutoClicker");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "自动点击器已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "自动点击器已禁用");
    }
}
