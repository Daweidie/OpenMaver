package module.render;

import module.Module;

/**
 * FullBright（全亮 / 夜视）模块开关。
 *
 * <p>开启后由 MC 侧的 {@code OpenMaverFeatures.updateFullBright()} 给本地玩家补一个
 * 持续刷新的夜视药水效果，因此洞穴、下界等暗处会像喝了夜视药水一样清晰可见。
 * 关闭时只移除本模块自己添加的效果；玩家原本拥有的夜视药水不会被误删。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.render.FullBright -> "render.fullbright"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "render": { "fullbright": { "enabled": false, "key": "Y" } }
 * </pre>
 *
 * <p><b>仅客户端</b>：效果只作用于本地玩家，不会发送任何数据包。
 */
public final class FullBright extends Module {

    public FullBright() {
        // 配置路径自动推导为 "render.fullbright"
        super("FullBright");
    }
}
