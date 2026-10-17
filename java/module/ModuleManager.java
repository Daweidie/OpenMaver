package module;

import config.Config;
import util.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模块注册表与统一驱动器。
 *
 * <p>职责：
 * <ul>
 *   <li>维护全局模块列表（静态注册表）</li>
 *   <li>统一遍历驱动 {@link #onTick()} / {@link #onRender()}，跳过未启用模块</li>
 *   <li>提供 {@link #getModule(String)} 便于调试</li>
 *   <li>周期性检查配置热重载并同步模块开关</li>
 * </ul>
 *
 * <p>线程安全：注册表读写均加锁；tick/render 遍历使用快照，避免并发修改。
 * 在独立运行模式下由驱动线程调用，在 Forge 模式下由事件总线线程调用。
 */
public final class ModuleManager {

    private static final String TAG = "ModuleManager";

    /** 配置热重载检查间隔（tick）。 */
    private static final int RELOAD_INTERVAL_TICKS = 100;

    /** 注册表保护锁。 */
    private static final Object LOCK = new Object();

    /** 全局模块列表。 */
    private static final List<Module> MODULES = new ArrayList<Module>();

    /** tick / render 调用计数，用于链路验证。 */
    private static volatile long tickCount = 0L;
    private static volatile long renderCount = 0L;

    /** 热重载计时器。 */
    private static int reloadTimer = 0;

    private ModuleManager() {
        // 工具类，禁止实例化
    }

    // ==================================================================
    // 注册与查询
    // ==================================================================

    /**
     * 注册一个模块，并立即按配置同步其 enabled 状态。
     *
     * @param module 模块实例（为 null 时忽略）
     */
    public static void register(Module module) {
        if (module == null) {
            return;
        }
        synchronized (LOCK) {
            if (findByNameLocked(module.getName()) != null) {
                Logger.warn(TAG, "模块重复注册，已忽略: " + module.getName());
                return;
            }
            MODULES.add(module);
        }
        // 在锁外调用，避免回调中再次进入本类导致死锁
        module.syncFromConfig();
        Logger.info(TAG, "已注册模块 " + module.getName()
                + " (configKey=" + module.getConfigKey()
                + ", enabled=" + module.isEnabled() + ")");
    }

    /** 按名称查找模块，便于调试。未找到返回 null。 */
    public static Module getModule(String name) {
        synchronized (LOCK) {
            return findByNameLocked(name);
        }
    }

    /** 返回当前所有模块的快照（不可修改）。 */
    public static List<Module> getModules() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<Module>(MODULES));
        }
    }

    // ==================================================================
    // 驱动
    // ==================================================================

    /** 驱动一次逻辑 tick。由游戏主循环 / 独立驱动线程调用。 */
    public static void onTick() {
        tickCount++;

        // 周期性检查配置文件是否被外部修改
        if ((++reloadTimer % RELOAD_INTERVAL_TICKS) == 0) {
            Config config = Config.getDefault();
            if (config != null && config.reloadIfChanged()) {
                syncAll();
                Logger.info(TAG, "配置已热重载，模块状态已同步");
            }
        }

        List<Module> snapshot = getModules();
        for (int i = 0; i < snapshot.size(); i++) {
            Module module = snapshot.get(i);
            if (module.isEnabled()) {
                module.onTick();
            }
        }
    }

    /** 驱动一次渲染帧。由游戏渲染循环 / 独立驱动线程调用。 */
    public static void onRender() {
        renderCount++;

        List<Module> snapshot = getModules();
        for (int i = 0; i < snapshot.size(); i++) {
            Module module = snapshot.get(i);
            if (module.isEnabled()) {
                module.onRender();
            }
        }
    }

    // ==================================================================
    // 批量状态操作
    // ==================================================================

    /** 让所有模块重新从配置同步 enabled 状态（热重载后调用）。 */
    public static void syncAll() {
        List<Module> snapshot = getModules();
        for (int i = 0; i < snapshot.size(); i++) {
            snapshot.get(i).syncFromConfig();
        }
    }

    /** 逆序禁用所有模块（触发 onDisable），用于关闭流程。 */
    public static void disableAll() {
        List<Module> snapshot = getModules();
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            snapshot.get(i).setEnabled(false);
        }
    }

    // ==================================================================
    // 统计
    // ==================================================================

    /** tick 驱动次数（用于验证链路是否跑通）。 */
    public static long getTickCount() {
        return tickCount;
    }

    /** render 驱动次数（用于验证链路是否跑通）。 */
    public static long getRenderCount() {
        return renderCount;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 调用方需持有 LOCK。 */
    private static Module findByNameLocked(String name) {
        if (name == null) {
            return null;
        }
        for (int i = 0; i < MODULES.size(); i++) {
            if (name.equals(MODULES.get(i).getName())) {
                return MODULES.get(i);
            }
        }
        return null;
    }
}
