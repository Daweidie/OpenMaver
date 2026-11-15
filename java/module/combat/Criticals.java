package module.combat;

import module.Module;
import util.Logger;

/**
 * Criticals 模块开关（刀暴 / 强制暴击）。
 *
 * <p>默认 ALWAYS 模式：无论玩家站在地面还是空中，攻击前都会先补发一组伪造下落
 * 移动包，让服务端在结算 C02 时判定为暴击。与 {@link AutoClicker} / {@link Reach}
 * 一样，本类位于 core，运行期注入模式下可能由 bootstrap 类加载器加载，看不到
 * {@code net.minecraft.*}，因此这里只保存开关状态；真正的伪造移动包逻辑在 MC 侧的
 * {@code OpenMaverFeatures.applyCriticals()} 里执行。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.combat.Criticals -> "combat.criticals"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "combat": { "criticals": { "enabled": false, "key": "" } }
 * </pre>
 */
public final class Criticals extends Module {

    public Criticals() {
        // 配置路径自动推导为 "combat.criticals"
        super("Criticals");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "Packet 刀暴已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "Packet 刀暴已禁用");
    }
}
