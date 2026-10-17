package module;

import config.Config;
import util.Logger;

import java.util.Locale;

/**
 * 所有功能模块的抽象基类。
 *
 * <p>生命周期：
 * <pre>
 *   new Module(...)  ->  syncFromConfig()  ->  onEnable() / onDisable()
 *                                          ->  onTick() / onRender()（每帧）
 * </pre>
 *
 * <h3>配置路径约定</h3>
 * 子类只需 {@code super("AutoSprint")}，基类会自动推导配置路径：
 * <pre>
 *   配置路径 = &lt;子类所在包去掉根包 "module"&gt; + "." + name 小写
 *
 *   module.movement.AutoSprint  ->  "movement.autosprint"
 *   module.combat.AimAssist     ->  "combat.aimassist"
 *   module.render.Esp           ->  "render.esp"
 *   module.tools.Logger         ->  "tools.logger"
 * </pre>
 * 该路径对应 default.jsonc 里的嵌套结构，enable 状态读取 {@code <路径>.enabled}。
 * 如需自定义，可用 {@link #Module(String, String)} 显式指定。
 */
public abstract class Module {

    /** 模块名（用于日志与显示）。 */
    protected final String name;

    /** 本模块在配置中的 key 前缀，例如 "movement.autosprint"。 */
    protected final String configKey;

    /** 关联的配置对象（来自全局默认配置，可能为 null）。 */
    protected final Config config;

    /** 当前是否启用。 */
    protected volatile boolean enabled;

    /**
     * 按“包名 + 模块名”约定自动推导配置路径。
     *
     * @param name 模块名，例如 "AutoSprint"
     */
    protected Module(String name) {
        this(name, null);
    }

    /**
     * 显式指定配置路径。
     *
     * @param name      模块名
     * @param configKey 配置 key 前缀；为 null 时按约定推导
     */
    protected Module(String name, String configKey) {
        this.name = (name == null || name.isEmpty()) ? "Unnamed" : name;
        this.config = Config.getDefault();
        // getClass() 返回真实的子类，据此推导包名
        this.configKey = (configKey != null) ? configKey : deriveConfigKey(getClass(), this.name);
        this.enabled = false;
    }

    /**
     * 推导配置路径：跳过根包 "module"，其余包名逐级拼接，最后接 name 的小写形式。
     * 例如 {@code module.movement.AutoSprint} -> {@code movement.autosprint}。
     */
    private static String deriveConfigKey(Class<?> type, String name) {
        String leaf = name.toLowerCase(Locale.ROOT);
        Package pkg = (type == null) ? null : type.getPackage();
        if (pkg == null || pkg.getName().isEmpty()) {
            return leaf;
        }
        String[] parts = pkg.getName().split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i == 0 && "module".equals(parts[i])) {
                continue; // 跳过根包
            }
            sb.append(parts[i]).append('.');
        }
        sb.append(leaf);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public final String getName() {
        return name;
    }

    /** 本模块的配置 key 前缀，便于调试。 */
    public final String getConfigKey() {
        return configKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 从配置同步 enabled 状态。若状态发生变化会触发 onEnable / onDisable。
     * 注册时以及配置热重载后由 {@code ModuleManager} 调用。
     */
    public final void syncFromConfig() {
        boolean wanted = (config != null) && config.getBoolean(configKey + ".enabled", false);
        setEnabled(wanted);
    }

    /**
     * 设置启用状态。仅在状态真正改变时触发回调。
     *
     * @param value 目标状态
     */
    public final void setEnabled(boolean value) {
        if (this.enabled == value) {
            return;
        }
        this.enabled = value;
        if (value) {
            Logger.info(name, "模块已启用 (" + configKey + ")");
            onEnable();
        } else {
            Logger.info(name, "模块已禁用 (" + configKey + ")");
            onDisable();
        }
    }

    // ------------------------------------------------------------------
    // 可重写回调（默认空实现）
    // ------------------------------------------------------------------

    /** 模块启用时调用。 */
    public void onEnable() {
    }

    /** 模块禁用时调用。 */
    public void onDisable() {
    }

    /** 每个逻辑 tick 调用一次（仅当 enabled 为 true 时由 ModuleManager 触发）。 */
    public void onTick() {
    }

    /** 每帧渲染调用一次（仅当 enabled 为 true 时由 ModuleManager 触发）。 */
    public void onRender() {
    }
}
