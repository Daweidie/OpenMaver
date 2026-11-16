package module.movement;

import module.Module;
import util.Logger;

/**
 * Velocity（防击退）模块开关。
 *
 * <p>与 {@link AutoSprint} / {@link Scaffold} 一样，本类位于 core：运行期注入模式下
 * core 可能由 bootstrap 类加载器加载，看不到 {@code net.minecraft.*} / {@code io.netty.*}，
 * 因此这里只保存开关状态。真正的击退处理（入站 S12 拦截、按模式改写速度、
 * blink 憋包与攻击包爆发）全部在 MC 侧的 {@code VelocityFeature} 里完成。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.Velocity -> "movement.velocity"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "velocity": { "enabled": false, "key": "" } }
 * </pre>
 *
 * <p>参数（模式 / 服务器预设 / 水平垂直比例 / blink tick / burst 攻击数）不在
 * JSONC 里，而是与其它功能的滑块一起存在 {@code config/openmaver.properties}
 * （{@code velocity.*}），由 ClickGUI 调整。
 */
public final class Velocity extends Module {

    public Velocity() {
        // 配置路径自动推导为 "movement.velocity"
        super("Velocity");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "Velocity 防击退已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "Velocity 防击退已禁用");
    }
}
