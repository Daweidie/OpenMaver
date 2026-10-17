package util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * OpenMaver 日志系统。
 *
 * <p>特性：
 * <ul>
 *   <li>同时输出到控制台与文件（默认 ./logs/openmaver.log）</li>
 *   <li>格式：[时间] [级别] [模块] 消息</li>
 *   <li>级别：DEBUG / INFO / WARN / ERROR</li>
 *   <li>线程安全（内部加锁）</li>
 * </ul>
 *
 * <p>仅使用 Java 8 自带 API，不依赖任何第三方库。
 */
public final class Logger {

    /** 日志级别，顺序即严重程度（用于过滤）。 */
    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    /** 全局锁：保证多线程写入不会交错。 */
    private static final Object LOCK = new Object();

    /** 时间格式化器（SimpleDateFormat 非线程安全，使用时需在 LOCK 内）。 */
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    /** 日志文件路径，默认 ./logs/openmaver.log。 */
    private static volatile Path logFile = Paths.get("./logs", "openmaver.log");

    /** 最低输出级别，低于该级别的日志被丢弃。 */
    private static volatile Level minLevel = Level.DEBUG;

    /** 是否输出到控制台。 */
    private static volatile boolean consoleEnabled = true;

    /** 是否已确保日志目录存在。 */
    private static volatile boolean prepared = false;

    private Logger() {
        // 工具类，禁止实例化
    }

    /**
     * 重新配置日志系统（通常在读取配置后调用）。
     *
     * @param path           日志文件路径，null 表示保持原值
     * @param level          最低输出级别，null 表示保持原值
     * @param consoleEnabled 是否输出到控制台
     */
    public static void configure(String path, Level level, boolean consoleEnabled) {
        synchronized (LOCK) {
            if (path != null && !path.isEmpty()) {
                logFile = Paths.get(path);
                prepared = false; // 路径变化后需要在下次写入时重新创建目录
            }
            if (level != null) {
                minLevel = level;
            }
            Logger.consoleEnabled = consoleEnabled;
        }
    }

    /** 获取当前日志文件路径。 */
    public static Path getLogFile() {
        return logFile;
    }

    // ------------------------------------------------------------------
    // 便捷方法
    // ------------------------------------------------------------------

    public static void debug(String module, String message) {
        log(Level.DEBUG, module, message);
    }

    public static void info(String module, String message) {
        log(Level.INFO, module, message);
    }

    public static void warn(String module, String message) {
        log(Level.WARN, module, message);
    }

    public static void error(String module, String message) {
        log(Level.ERROR, module, message);
    }

    /** 记录异常堆栈（以 ERROR 级别输出到控制台，简化为一句话写入文件）。 */
    public static void error(String module, String message, Throwable throwable) {
        String suffix = throwable == null ? "" : (" | " + throwable.getClass().getSimpleName()
                + ": " + throwable.getMessage());
        log(Level.ERROR, module, message + suffix);
    }

    // ------------------------------------------------------------------
    // 核心实现
    // ------------------------------------------------------------------

    /**
     * 写入一条日志。
     *
     * @param level   日志级别
     * @param module  模块名（可空，空则记为 Core）
     * @param message 消息内容
     */
    public static void log(Level level, String module, String message) {
        if (level.ordinal() < minLevel.ordinal()) {
            return; // 被级别过滤
        }

        String line = format(level, module, message);

        synchronized (LOCK) {
            if (consoleEnabled) {
                System.out.println(line);
            }
            writeToFile(line);
        }
    }

    /** 按 [时间] [级别] [模块] 消息 组装一行。 */
    private static String format(Level level, String module, String message) {
        final String time;
        synchronized (TIME_FORMAT) {
            time = TIME_FORMAT.format(new Date());
        }
        String mod = (module == null || module.isEmpty()) ? "Core" : module;
        String msg = (message == null) ? "" : message;
        return "[" + time + "] [" + level.name() + "] [" + mod + "] " + msg;
    }

    /** 追加写入日志文件，失败时降级到标准错误，绝不抛出异常。 */
    private static void writeToFile(String line) {
        try {
            if (!prepared) {
                Path parent = logFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                prepared = true;
            }
            byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            Files.write(logFile, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 日志系统自身不能因为写文件失败而中断主流程
            System.err.println("[Logger] 写入日志文件失败: " + e.getMessage());
        }
    }
}
