package module.combat;

import module.Module;
import util.Logger;

/**
 * Reach 模块开关。
 *
 * <p>与 {@link AutoClicker} 一样，本类位于 core，运行期注入模式下可能由 bootstrap
 * 类加载器加载，看不到 {@code net.minecraft.*}，因此这里只保存开关状态；
 * 真正的距离扩展逻辑在 MC 侧的 {@code OpenMaverFeatures.updateReach()} 里执行。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.combat.Reach -> "combat.reach"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "combat": { "reach": { "enabled": false, "key": "K" } }
 * </pre>
 */
public final class Reach extends Module {

    public Reach() {
        // 配置路径自动推导为 "combat.reach"
        super("Reach");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "Reach 已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "Reach 已禁用");
    }
}
