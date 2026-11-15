package module.render;

import module.Module;

/**
 * Motion Blur（运动模糊）模块开关。
 *
 * <p>实际渲染在 MC 侧的 {@code mc/MotionBlur.java}：在 {@code RenderWorldLastEvent}
 * 之后抓取当前帧，沿相机转动 / 移动方向多次采样，做出方向性拖影后叠回主画面。
 * 只使用当前帧、不做历史帧累积，因此不会出现残影或晕糊。
 * 模块只保存 enabled / key 状态，因此核心 jar 不依赖任何 Minecraft 类也能加载。
 *
 * <p>配置路径：{@code render.motionblur}，默认关闭，默认键位 <b>M</b>。
 * 强度滑块写入 {@code config/openmaver.properties} 的 {@code motionblur.strength}。
 */
public final class MotionBlur extends Module {

    public MotionBlur() {
        super("Motion Blur", "render.motionblur");
        // 旧版 default.jsonc 没有 motionblur.key 时也能直接按 M 开关；
        // 配置里显式写 "key": "" 仍会解除绑定。
        setKeybind("M");
    }
}
