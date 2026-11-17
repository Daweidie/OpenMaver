package module.render;

import module.Module;

/**
 * Storage / Chest ESP switch.
 *
 * <p>方框实际由 MC 侧的 {@code ChestFeature.onRenderWorld()} 在
 * {@code RenderWorldLastEvent} 里绘制（穿墙线框，按距离过滤）；本模块只保存开关、
 * 键位与配置路径，因此可以和其它模块一样出现在功能栏、ClickGUI 与配置热重载里。
 */
public final class ChestEsp extends Module {

    public ChestEsp() {
        super("Chest ESP", "render.chestesp");
    }
}
