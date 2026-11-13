import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 低开销、默认关闭的对测观测器。
 *
 * <p>它只记录 OpenMaver 自己产生的行为（aim rotation、C02、C08、点击间隔、
 * 被抑制的 C05、每 100 tick 统计），不注入游戏网络层，因此不会改变任何发包。
 * 其中 C03 只按客户端 tick 节奏统计（1.8.9 正常情况下每 tick 一条 C03/C04）。
 *
 * <p>所有 IO 失败只记录一次并停用 debug，不影响游戏主循环。
 */
public final class OpenMaverDebug {

    private static final String PATH = System.getProperty(
            "openmaver.debug", "logs/openmaver-debug.log");

    private static final Set<String> NOTICES = new HashSet<String>();

    private static volatile boolean enabled;
    private static boolean failed;

    private static long tickCounter;
    private static long attackCount;
    private static long placeCount;
    private static long clickCount;
    private static long rotationCount;
    private static long suppressedCount;

    private OpenMaverDebug() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (value) {
            failed = false;
            log("INIT", "debug enabled, path=" + PATH);
        }
    }

    public static boolean isEnabled() {
        return enabled && !failed;
    }

    /** 同一条提示只记录一次，避免每个 tick 刷屏。 */
    public static void noticeOnce(String key, String message) {
        if (!enabled || failed) {
            return;
        }
        synchronized (NOTICES) {
            if (!NOTICES.add(key)) {
                return;
            }
        }
        log("NOTICE", message);
    }

    public static void rotation(String source, float oldYaw, float oldPitch,
            float newYaw, float newPitch, double gcd) {
        if (!enabled || failed) {
            return;
        }
        rotationCount++;
        log("C03", source
                + " yaw " + f(oldYaw) + " -> " + f(newYaw)
                + " (d=" + f(wrap(newYaw - oldYaw)) + ")"
                + " pitch " + f(oldPitch) + " -> " + f(newPitch)
                + " gcd=" + f(gcd));
    }

    public static void attack(String target, double distance, double maxDistance,
            float yaw, float pitch, boolean swing) {
        if (!enabled || failed) {
            return;
        }
        attackCount++;
        log("C02", "attack target=" + target
                + " dist=" + f(distance) + "/" + f(maxDistance)
                + " yaw=" + f(yaw) + " pitch=" + f(pitch)
                + " animation=" + (swing ? "already-swinging" : "forced-swing"));
    }

    public static void place(int x, int y, int z, String face, float yaw, float pitch,
            boolean legitAim, boolean packetRotation, int intervalTicks) {
        if (!enabled || failed) {
            return;
        }
        placeCount++;
        log("C08", "place pos=" + x + "," + y + "," + z
                + " face=" + face
                + " yaw=" + f(yaw) + " pitch=" + f(pitch)
                + " aim=" + (legitAim ? "legit" : "rotated")
                + " c05=" + packetRotation
                + " interval=" + intervalTicks + "t");
    }

    public static void click(String button, long intervalMillis, int cps,
            boolean holding, int missStreak) {
        if (!enabled || failed) {
            return;
        }
        clickCount++;
        log("CLICK", button
                + " interval=" + intervalMillis + "ms"
                + " targetCps=" + cps
                + " holding=" + holding
                + " missStreak=" + missStreak);
    }

    public static void suppressed(String what) {
        if (!enabled || failed) {
            return;
        }
        suppressedCount++;
        log("SUPPRESS", what);
    }

    /** 每个客户端 tick 调一次，用于输出低频统计。 */
    public static void tickSummary() {
        if (!enabled || failed) {
            return;
        }
        tickCounter++;
        if ((tickCounter % 100L) != 0L) {
            return;
        }
        log("STATS", "ticks=" + tickCounter
                + " C02=" + attackCount
                + " C08=" + placeCount
                + " clicks=" + clickCount
                + " aimUpdates=" + rotationCount
                + " suppressed=" + suppressedCount);
    }

    private static void log(String tag, String message) {
        if (!enabled || failed) {
            return;
        }
        String line = "[" + System.currentTimeMillis() + "] [" + tag + "] " + message
                + System.lineSeparator();
        try {
            File file = new File(PATH);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            FileWriter writer = new FileWriter(file, true);
            try {
                writer.write(line);
            } finally {
                writer.close();
            }
        } catch (IOException error) {
            failed = true;
            System.err.println("[OpenMaver] debug disabled after IO error: " + error);
        } catch (Throwable error) {
            failed = true;
            System.err.println("[OpenMaver] debug disabled after error: " + error);
        }
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private static float wrap(float angle) {
        angle %= 360.0F;
        if (angle >= 180.0F) {
            angle -= 360.0F;
        }
        if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }
}
