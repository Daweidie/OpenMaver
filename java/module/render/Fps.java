package module.render;

import module.Module;

/**
 * FPS counter switch.
 *
 * <p>The actual overlay is drawn on the Minecraft side by {@code StatsHud}; this module only
 * stores the enabled flag and key bind so it integrates with the module list, ClickGUI and
 * configuration hot-reload like every other feature.
 */
public final class Fps extends Module {

    public Fps() {
        super("FPS", "render.fps");
        // 左上角统计开箱可见；配置里显式写 enabled: false 仍然可以关闭。
        this.defaultEnabled = true;
    }
}
