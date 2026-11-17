package module.movement;

import module.Module;
import util.Logger;

/**
 * Scaffold（自动搭路 / 搭桥）模块开关。
 *
 * <p>完整行为在 MC 侧的 {@code ScaffoldFeature}，按 {@code ../OpenZen} 的
 * {@code shit.zen.modules.impl.movement.Scaffold} 重写：
 * {@code Normal / Telly Bridge / Old Telly / Keep Y} 四种模式、Eagle/Sneak、
 * Snap、Clutch、Rotation Tick 与 S12 击退抑制。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.Scaffold -> "movement.scaffold"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "scaffold": { "enabled": false, "key": "B" } }
 * </pre>
 *
 * <p>模块自身的设置（模式、Eagle/Sneak/Snap/Clutch/旋转速度/物品伪装/HUD）
 * 由 {@code ScaffoldFeature} 持有，写入 {@code config/openmaver.properties} 的
 * {@code scaffold.*}，在 ClickGUI 里展开 Scaffold 直接调。
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
