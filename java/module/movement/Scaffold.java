package module.movement;

import module.Module;
import module.ModuleManager;
import util.Logger;

/**
 * Scaffold（自动搭路 / 搭桥）模块开关。
 *
 * <p>Normal 使用 MC 侧独立的 AugustusScaffold 控制器，参考 ../OpenAugustus-Maver。
 * Simple / Old Telly / Keep Y 保留 OpenZen 行为；ScaffoldFeature 负责模式分派、
 * 快捷栏与静默旋转的放置时序。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.Scaffold -> "movement.scaffold"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "scaffold": { "enabled": false, "key": "B" } }
 * </pre>
 *
 * <p>模块自身的设置（模式、Normal 旋转区间与放置参数、其他模式 Sneak/Clutch、物品伪装/HUD）
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
        Module telly = ModuleManager.getModule("Telly");
        if (telly != null) telly.setEnabled(false);
        Logger.info(getName(), "自动搭路已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "自动搭路已禁用");
    }
}
