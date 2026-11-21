import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Reach 聊天调试通道的行为验证：格式、限流、配置持久化与热重载。
 *
 * <p>只驱动 {@link ReachDebug} 与配置读写，不构造 Minecraft 世界；聊天组件在
 * 无客户端环境下会退化为 {@code util.Logger.info}，同样不影响断言。
 */
public final class ReachDebugTest {

    private static int passed;

    public static void main(String[] args) throws Exception {
        format();
        packetOrder();
        gui();
        rateLimit();
        persistence();
        System.out.println("[reach-test] " + passed + " checks passed");
    }

    private static void format() {
        OpenMaverFeatures.setBypassEnabled(true);
        OpenMaverFeatures.setBypassProfile(OpenMaverFeatures.PROFILE_BALANCED);
        OpenMaverFeatures.setReachDistance(3.0F);
        OpenMaverFeatures.setReachChance(100.0F);
        OpenMaverFeatures.setReachDebugChat(true);

        List<String> header = ReachDebug.drain();
        check("enable announces effective cap and profile",
                has(header, "DEBUG ON") && has(header, "cap=2.97m")
                        && has(header, "dist=3.00") && has(header, "profile=BALANCED"));

        ReachDebug.swing(0, 2.97D, "BALANCED");
        ReachDebug.skip("chance 42% >= 55%");
        ReachDebug.skipImportant("hurt Steve");
        ReachDebug.hit("Steve", 2.87D, 2.97D, 0.1D, 12.5F, -4.2F, true);

        List<String> lines = ReachDebug.drain();
        check("swing line carries chance/extra/cap",
                has(lines, "SWING p=0 chance=100% dist=3.00 cap=2.97 BALANCED"));
        check("chance miss is reported", has(lines, "SKIP chance 42% >= 55%"));
        check("hurt wait is reported as important", has(lines, "SKIP hurt Steve"));
        check("hit line carries distance/border/rotation",
                has(lines, "HIT Steve dist=2.87/2.97 border=0.10 yaw=12.5 pitch=-4.2 anim=c0a swing=1"));
        check("history keeps every event without chat throttling",
                has(ReachDebug.history(), "HIT Steve")
                        && has(ReachDebug.history(), "SKIP hurt Steve"));

        ReachDebug.setEnabled(false);
        check("disable stops the channel", !ReachDebug.isEnabled() && ReachDebug.drain().isEmpty());
    }

    /**
     * 1.8 包序结构断言（Grim 2.0 {@code PacketOrderB} / {@code Post}）：
     * <ul>
     *   <li>补刀必须每次先发 C0A 再发 C02 —— 挥击进行中也不能跳过；</li>
     *   <li>整条 Reach 链路必须在 tick START（vanilla C03 之前）执行；</li>
     *   <li>同 tick 已有实体攻击时让位，避免两条 C02。</li>
     * </ul>
     */
    private static void packetOrder() throws Exception {
        String features = readText("mc/OpenMaverFeatures.java");
        String reach = segment(features,
                "private static void attackNearestEntityInReach",
                "private static String f2(");

        check("reach always sends C0A before its own C02",
                reach.contains("if (!callVoid(player, \"swingItem\", \"func_71038_i\", null))")
                        && reach.indexOf("swingItem") < reach.indexOf("\"attackEntity\""));
        check("reach no longer skips the animation while a swing is in progress",
                !reach.contains("boolean animation = alreadySwinging"));
        check("reach yields when the tick attack slot is already used",
                reach.contains("if (entityAttackUsedThisTick)")
                        && reach.contains("entityAttackUsedThisTick = true;"));
        check("reach runs in the START phase, before aim rewrites rotation",
                features.indexOf("updateReach(mc);") < features.indexOf("updateAimTarget(mc);"));
        check("tick END no longer injects attacks after the movement packet",
                count(features, "updateReach(mc);") == 2);
        check("caps stay under Grim maxReach=3.0 on the 0.1-expanded box",
                features.contains("REACH_SAFE_CAP = 2.97D")
                        && features.contains("REACH_AGGRESSIVE_CAP = 2.99D"));
    }

    /** 取包含两个片段之间的源码，避免匹配到别的模块里的同名调用。 */
    private static String segment(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        int end = text.indexOf(endMarker, start + 1);
        if (start < 0 || end < 0) return "";
        return text.substring(start, end);
    }

    private static String readText(String relative) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relative)), StandardCharsets.UTF_8);
    }

    /** ClickGUI 结构：Reach 用 0~max 的实际距离，AutoClicker 用双滑块 CPS 区间。 */
    private static void gui() throws Exception {
        String gui = readText("mc/GuiOpenMaverClickGui.java");
        check("autoclicker exposes one dual-handle CPS range",
                gui.contains("Setting.range(\"CPS\""));
        check("reach exposes a 0..3.0 distance slider instead of EXTRA",
                gui.contains("Setting.slider(\"DISTANCE\", 0.0F, 3.0F, 0.05F, 2, \" m\"")
                        && !gui.contains("Setting.slider(\"EXTRA\""));
        check("range widget keeps the low handle under the high handle",
                gui.contains("binding.set(Math.min(stepped(percent, 0.0D), bindingHigh.get()));"));
        check("range handles are drawn as opposing triangles",
                gui.contains("drawRangeHandle(lowX, trackY + 2, true, accent, hi)")
                        && gui.contains("drawRangeHandle(highX2, trackY + 2, false, accent, hi)"));
    }

    private static void rateLimit() {
        ReachDebug.setEnabled(true);
        ReachDebug.drain(); // 丢掉 DEBUG ON 头
        for (int i = 0; i < 40; i++) {
            ReachDebug.skip("spam " + i);
        }
        List<String> lines = ReachDebug.drain();
        check("chat caps normal lines per second", lines.size() <= 11);
        check("throttled lines are counted", ReachDebug.droppedLines() > 0);
        check("log history keeps all 40 skips", count(ReachDebug.history(), "SKIP spam") == 40);

        for (int i = 0; i < 15; i++) {
            ReachDebug.hit("Bot" + i, 2.5D, 3.0D, 0.1305D, 0.0F, 0.0F, false);
        }
        check("hit lines bypass the chat throttle",
                count(ReachDebug.drain(), "HIT Bot") == 15);
        ReachDebug.setEnabled(false);
    }

    private static void persistence() throws Exception {
        File tmp = File.createTempFile("openmaver-reach-debug", ".properties");
        String previous = System.getProperty("openmaver.features");
        try {
            System.setProperty("openmaver.features", tmp.getAbsolutePath());
            OpenMaverFeatures.setReachDebugChat(true);
            OpenMaverFeatures.flushSettings();
            check("reach.debug=true is persisted", read(tmp).contains("reach.debug=true"));
            check("reach distance is persisted as an absolute value",
                    read(tmp).contains("reach.distance="));
            check("no legacy reach.extra is written back",
                    !read(tmp).contains("reach.extra="));

            // 热重载：把配置换成 false 后调用私有 refreshSettings，验证 debug 通道同步关闭。
            write(tmp, "settings.version=22\nreach.distance=3.0\nreach.chance=100\nreach.debug=false\n");
            reload();
            check("reload applies reach.debug=false",
                    !OpenMaverFeatures.isReachDebugChat() && !ReachDebug.isEnabled());

            write(tmp, "settings.version=22\nreach.distance=2.50\nreach.chance=55\nreach.debug=true\n");
            reload();
            check("reload applies reach.debug=true",
                    OpenMaverFeatures.isReachDebugChat() && ReachDebug.isEnabled());
            check("reload keeps reach tuning next to the debug switch",
                    Math.abs(OpenMaverFeatures.getReachDistance() - 2.5F) < 1.0E-4F
                            && Math.abs(OpenMaverFeatures.getReachChance() - 55.0F) < 1.0E-4F);
            // 旧配置只有 reach.extra：迁移后等价于 3.0 + extra，并被 3.0 截断。
            write(tmp, "settings.version=22\nreach.extra=0.2\nreach.debug=true\n");
            reload();
            check("legacy reach.extra migrates to an absolute distance",
                    Math.abs(OpenMaverFeatures.getReachDistance() - 3.0F) < 1.0E-4F);
            List<String> header = ReachDebug.drain();
            check("reload re-announces with the new cap/chance",
                    has(header, "DEBUG ON") && has(header, "chance=55%"));
        } finally {
            OpenMaverFeatures.setReachDebugChat(false);
            if (previous == null) {
                System.clearProperty("openmaver.features");
            } else {
                System.setProperty("openmaver.features", previous);
            }
            tmp.delete();
        }
    }

    /** 调私有 refreshSettings，并把节流计数清掉，模拟配置文件被外部修改。 */
    private static void reload() throws Exception {
        Field ticks = OpenMaverFeatures.class.getDeclaredField("settingsCheckTicks");
        ticks.setAccessible(true);
        ticks.setInt(null, 20);
        Field modified = OpenMaverFeatures.class.getDeclaredField("settingsModified");
        modified.setAccessible(true);
        modified.setLong(null, Long.MIN_VALUE);
        Method refresh = OpenMaverFeatures.class.getDeclaredMethod("refreshSettings");
        refresh.setAccessible(true);
        refresh.invoke(null);
    }

    private static String read(File file) throws Exception {
        StringBuilder text = new StringBuilder();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), "UTF-8")) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) > 0) {
                text.append(buffer, 0, count);
            }
        }
        return text.toString();
    }

    private static void write(File file, String text) throws Exception {
        try (java.io.Writer writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), "UTF-8")) {
            writer.write(text);
        }
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            total++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return total;
    }

    private static boolean has(List<String> lines, String needle) {
        for (String line : lines) {
            if (line.contains(needle)) return true;
        }
        return false;
    }

    private static int count(List<String> lines, String needle) {
        int total = 0;
        for (String line : lines) {
            if (line.contains(needle)) total++;
        }
        return total;
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            System.out.println("[reach-test] FAIL: " + name);
            System.exit(1);
        }
        passed++;
        System.out.println("[reach-test] ok: " + name);
    }
}
