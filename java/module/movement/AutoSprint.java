package module.movement;

import module.Module;
import util.Logger;

/**
 * 示例模块：AutoSprint。
 *
 * <p>本模块的作用是**链路验证器**：用于确认
 * JNI 加载 → 事件总线触发 → Config 读取 → Module 基类驱动 这一整条链路是否打通。
 * 因此这里只写日志，不接入 Forge，也不做任何真实功能。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.movement.AutoSprint -> "movement.autosprint"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "movement": { "autosprint": { "enabled": false } }
 * </pre>
 */
public final class AutoSprint extends Module {

    /** tick 计数，仅用于日志展示链路是否在持续推进。 */
    private long tickCount = 0L;

    public AutoSprint() {
        // 配置路径自动推导为 "movement.autosprint"
        super("AutoSprint");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "自动疾跑已启用（示例模块，链路验证用）");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "自动疾跑已禁用");
    }

    @Override
    public void onTick() {
        // TODO: 接入 Minecraft 1.8.9 / Forge 后，在此实现真实逻辑。
        //  当前阶段只写 debug 日志，验证 ModuleManager -> Module.onTick 链路。
        tickCount++;
        Logger.debug(getName(), "tick #" + tickCount + "（链路验证，尚未接入 Forge）");
    }

    // 说明：本示例不重写 onRender()，基类默认实现为空，
    //      渲染链路的验证通过 ModuleManager.getRenderCount() 的计数即可确认。
}
