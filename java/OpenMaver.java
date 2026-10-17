import config.Config;
import module.Module;
import module.ModuleManager;
import module.movement.AutoSprint;
import util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * OpenMaver V2.0 入口 / 核心门面。
 *
 * <p>既可作为独立程序运行（{@link #main(String[])}），也可作为 Minecraft 1.8.9 Forge mod
 * 的核心被 {@code OpenMaverMod} 驱动：
 * <pre>
 *   首次进入世界              -> OpenMaver.start()   （加载本地库、读配置、注册模块）
 *   ClientTickEvent / 驱动线程-> ModuleManager.onTick()
 *   RenderWorldLastEvent/驱动线程 -> ModuleManager.onRender()
 *   游戏退出                 -> OpenMaver.stop()
 * </pre>
 *
 * <p>本类位于默认包（与项目既有结构保持一致），因此 Forge 集成类也放在默认包中。
 */
public final class OpenMaver {

    /** 项目名（日志模块名 / mod 名称）。 */
    public static final String NAME = "OpenMaver";

    /** 版本号，与本地核心库返回的值保持一致。 */
    public static final String VERSION = "2.0.0";

    /** 默认配置路径（相对当前工作目录；在 .minecraft 下即为 .minecraft/config/default.jsonc）。 */
    private static final String DEFAULT_CONFIG_PATH = "config/default.jsonc";

    /** 独立驱动线程的 tick / render 频率。 */
    private static final long TICK_INTERVAL_NANOS = 1_000_000_000L / 20L;   // 20 TPS
    private static final long RENDER_INTERVAL_NANOS = 1_000_000_000L / 60L; // 60 FPS

    /**
     * 加载 JNI 本地核心库：libopenmaver_core.so。
     *
     * <p>解析顺序（标准 mod 分发方式）：
     * <ol>
     *   <li>系统属性 {@code openmaver.core.path} 指定的绝对路径（运行期注入 / 调试用）</li>
     *   <li>{@code java.library.path} 中的 {@code openmaver_core}（开发环境）</li>
     *   <li>从本 jar 内 {@code /native/<os>/<arch>/} 解压到临时文件再 {@link System#load}
     *       —— 发布 mod 时无需再配 {@code -Djava.library.path}</li>
     * </ol>
     * 若本地库已由 JVMTI agent 载入并 RegisterNatives 绑定，此处失败会被吞掉。
     */
    static {
        try {
            loadNativeCore();
        } catch (Throwable t) {
            System.out.println("[OpenMaver] 本地库已由 agent 提供或加载失败: " + t);
        }
    }

    private static void loadNativeCore() throws IOException {
        String explicit = System.getProperty("openmaver.core.path");
        if (explicit != null && !explicit.isEmpty()) {
            System.load(explicit);
            return;
        }
        try {
            System.loadLibrary("openmaver_core");
            return;
        } catch (UnsatisfiedLinkError libraryPathMiss) {
            loadBundledNativeCore();
        }
    }

    /** 从 jar 的 /native/<os>/<arch>/ 解压并加载本地库。 */
    private static void loadBundledNativeCore() throws IOException {
        String libName = System.mapLibraryName("openmaver_core");
        String resource = "/native/" + platformOs() + "/" + platformArch() + "/" + libName;

        InputStream in = OpenMaver.class.getResourceAsStream(resource);
        if (in == null) {
            throw new UnsatisfiedLinkError("jar 内未找到本地库: " + resource);
        }

        int dot = libName.lastIndexOf('.');
        String suffix = (dot >= 0) ? libName.substring(dot) : ".tmp";
        File temp = File.createTempFile("openmaver_core", suffix);
        temp.deleteOnExit();

        OutputStream out = new FileOutputStream(temp);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            try {
                out.close();
            } finally {
                in.close();
            }
        }

        System.load(temp.getAbsolutePath());
    }

    private static String platformOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return os.replaceAll("[^a-z0-9]+", "_");
    }

    private static String platformArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "x64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        if (arch.equals("x86") || arch.equals("i386") || arch.equals("i686")) {
            return "x86";
        }
        return arch.replaceAll("[^a-z0-9]+", "_");
    }

    // ------------------------------------------------------------------
    // native 方法（由 core/jni/bridge.cpp 导出）
    // ------------------------------------------------------------------

    private static native void init();

    private static native void shutdown();

    private static native String getVersion();

    // ------------------------------------------------------------------
    // 运行期状态
    // ------------------------------------------------------------------

    /** 核心是否已启动。 */
    private static volatile boolean started = false;

    /** 是否启用独立驱动线程（仅 main() 独立运行模式打开；Forge 模式由事件驱动）。 */
    private static volatile boolean standaloneDriver = false;

    /** 独立驱动线程的运行标志。 */
    private static volatile boolean driverRunning = false;

    /** 独立驱动线程句柄。 */
    private static Thread driverThread;

    private OpenMaver() {
        // 入口类，禁止实例化
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /**
     * 启动核心：加载本地库 -> 初始化 -> 读取配置 -> 注册模块 -> （可选）启动独立驱动线程。
     * 幂等，重复调用为空操作。
     */
    public static synchronized void start() {
        if (started) {
            return;
        }
        init();
        Logger.info(NAME, "版本 " + getVersion() + " 启动完成");
        loadConfig();
        registerModules();
        started = true;

        // 独立运行模式下自建驱动线程验证链路；Forge 模式由事件总线驱动
        if (standaloneDriver) {
            startDriverLoop();
        }
    }

    /**
     * 启动核心并打开独立驱动线程。
     *
     * <p>供运行期注入使用：目标 JVM 内通常没有 Forge 事件驱动，
     * 由 Java 线程按 20 TPS / 60 FPS 驱动 {@link ModuleManager}。
     */
    public static synchronized void startWithDriver() {
        standaloneDriver = true;
        start();
    }

    /**
     * 在核心已启动后再打开独立驱动线程（幂等）。
     *
     * <p>供 agent 兜底：若 Forge 事件桥接注册失败，则退回线程驱动，
     * 避免核心启动后无人调用 {@link ModuleManager#onTick()}。
     */
    public static synchronized void enableStandaloneDriver() {
        if (!started) {
            return;
        }
        standaloneDriver = true;
        startDriverLoop();
    }

    /** 关闭核心。幂等。 */
    public static synchronized void stop() {
        if (!started) {
            return;
        }
        stopDriverLoop();
        started = false;
        ModuleManager.disableAll();
        shutdown();
        Logger.info(NAME, "已退出");
    }

    /** 核心是否已启动。 */
    public static boolean isStarted() {
        return started;
    }

    /** 获取全局配置（可能为 null）。 */
    public static Config getConfig() {
        return Config.getDefault();
    }

    // ==================================================================
    // 配置
    // ==================================================================

    private static void loadConfig() {
        String configPath = System.getProperty("openmaver.config", DEFAULT_CONFIG_PATH);
        try {
            Config config = new Config(configPath);
            config.load();

            // 设置为全局默认配置，供 Module 基类按约定读取
            Config.setDefault(config);

            // 依据配置调整日志系统
            boolean loggerEnabled = config.getBoolean("tools.logger.enabled", true);
            String logPath = config.getString("tools.logger.path", "./logs/openmaver.log");
            if (loggerEnabled) {
                Logger.configure(logPath, Logger.Level.DEBUG, true);
            } else {
                // 日志模块被关闭时仅保留错误输出
                Logger.configure(logPath, Logger.Level.ERROR, true);
            }

            Logger.info(NAME, "配置加载完成: " + config.getPath().toAbsolutePath());
        } catch (IOException e) {
            Config.setDefault(null);
            Logger.warn(NAME, "配置加载失败，使用默认值: " + e.getMessage());
        }
    }

    // ==================================================================
    // 模块注册
    // ==================================================================

    private static void registerModules() {
        // 示例模块：AutoSprint（配置路径 movement.autosprint）
        ModuleManager.register(new AutoSprint());

        // 后续模块在此继续注册，例如：
        //   ModuleManager.register(new CombatExample());

        // 调试：确认模块确实已进入注册表
        Module debug = ModuleManager.getModule("AutoSprint");
        if (debug != null) {
            Logger.debug(NAME, "调试查询 getModule(\"AutoSprint\") -> "
                    + debug.getConfigKey() + ", enabled=" + debug.isEnabled());
        }
    }

    // ==================================================================
    // 独立驱动线程（仅独立运行模式）
    // ==================================================================

    /** 启动一个守护线程，按 20 TPS / 60 FPS 驱动 tick 与 render。 */
    private static void startDriverLoop() {
        if (driverThread != null) {
            return;
        }
        driverRunning = true;
        driverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                driverRun();
            }
        }, "OpenMaver-Driver");
        driverThread.setDaemon(true);
        driverThread.start();
        Logger.info(NAME, "独立驱动线程已启动 (tick 20 TPS / render 60 FPS)");
    }

    /** 停止驱动线程并等待其退出。 */
    private static void stopDriverLoop() {
        if (driverThread == null) {
            return;
        }
        driverRunning = false;
        try {
            driverThread.join(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        driverThread = null;
    }

    /** 驱动线程主体：简单的定时循环，模拟游戏主循环。 */
    private static void driverRun() {
        long nextTick = System.nanoTime();
        long nextRender = nextTick;

        while (driverRunning && started) {
            long now = System.nanoTime();

            if (now >= nextTick) {
                ModuleManager.onTick();
                nextTick += TICK_INTERVAL_NANOS;
            }
            if (now >= nextRender) {
                ModuleManager.onRender();
                nextRender += RENDER_INTERVAL_NANOS;
            }

            // 睡到下一个最近的触发点，避免空转占满 CPU
            long next = Math.min(nextTick, nextRender);
            long sleepMillis = (next - System.nanoTime()) / 1_000_000L;
            try {
                if (sleepMillis > 0L) {
                    Thread.sleep(sleepMillis);
                } else {
                    Thread.sleep(1L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ==================================================================
    // 独立运行入口
    // ==================================================================

    public static void main(String[] args) {
        // 打开独立驱动线程，用 Java 线程验证整条链路（不依赖 Forge）
        startWithDriver();

        // 让驱动线程跑约 1.1 秒用于观察，然后退出
        try {
            Thread.sleep(1100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Logger.info(NAME, "链路验证统计: tick 循环=" + ModuleManager.getTickCount()
                + " 次, render 循环=" + ModuleManager.getRenderCount() + " 次");
        stop();
    }
}
