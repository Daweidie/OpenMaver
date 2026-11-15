package module.combat;

import module.Module;
import util.Logger;

/**
 * KillAura（刀暴 / 自动攻击）模块开关。
 *
 * <p>行为参考 {@code OpenMyau-Fix} 的 {@code KillAura}，但按 OpenMaver 的
 * 「core 只保存开关、MC 逻辑放在 {@code mc/OpenMaverFeatures}}」架构重写：
 * 目标选择、平滑转向、CPS 攻击与刀暴衔接全部在 MC 侧完成。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.combat.KillAura -> "combat.killaura"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "combat": { "killaura": { "enabled": false, "key": "" } }
 * </pre>
 *
 * <p>与 {@link AutoClicker} 同时开启时 KillAura 优先，AutoClicker 会主动让位，
 * 避免同一 tick 发出两条 C02。
 */
public final class KillAura extends Module {

    public KillAura() {
        // 配置路径自动推导为 "combat.killaura"
        super("KillAura");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "KillAura 已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "KillAura 已禁用");
    }
}
