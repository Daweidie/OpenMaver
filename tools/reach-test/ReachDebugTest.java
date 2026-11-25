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
 * Reach 距离、攻击路径、聊天调试、配置持久化与热重载验证。
 *
 * <p>用最小世界与实体 fixture 驱动真实攻击路径，无需启动游戏客户端。
 */
public final class ReachDebugTest {

    private static int passed;

    public static void main(String[] args) throws Exception {
        distanceRange();
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
        OpenMaverFeatures.setReachDistance(5.0F);
        OpenMaverFeatures.setReachChance(100.0F);
        OpenMaverFeatures.setReachDebugChat(true);

        List<String> header = ReachDebug.drain();
        check("enable announces effective cap and profile",
                has(header, "DEBUG ON") && has(header, "cap=5.00m")
                        && has(header, "dist=5.00") && has(header, "profile=BALANCED"));

        ReachDebug.swing(0, 5.0D, 5.0D, "BALANCED");
        ReachDebug.skip("chance 42% >= 55%");
        ReachDebug.skipImportant("hurt Steve");
        ReachDebug.hit("Steve", 4.87D, 5.0D, 4.72D, 5.0D, 0.1D, 12.5F, -4.2F, true);

        List<String> lines = ReachDebug.drain();
        check("swing line carries chance/distance/camera/server caps",
                has(lines, "SWING p=0 chance=100% dist=5.00 cam=5.00 srv=5.00 BALANCED"));
        check("chance miss is reported", has(lines, "SKIP chance 42% >= 55%"));
        check("hurt wait is reported as important", has(lines, "SKIP hurt Steve"));
        check("hit line carries distance/border/rotation",
                has(lines, "HIT Steve cam=4.87/5.00 srv=4.72/5.00 border=0.10 yaw=12.5 pitch=-4.2 anim=c0a swing=1"));
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
    }

    private static void distanceRange() throws Exception {
        OpenMaverFeatures.setReachGrimCheck(false);
        Method auraRange = OpenMaverFeatures.class.getDeclaredMethod("effectiveKillAuraRange");
        auraRange.setAccessible(true);
        for (boolean bypass : new boolean[] {true, false}) {
            OpenMaverFeatures.setBypassEnabled(bypass);
            for (int profile = 0; profile <= 2; profile++) {
                OpenMaverFeatures.setBypassProfile(profile);
                String mode = " bypass=" + bypass + " profile=" + profile;
                float[] inputs = {-1.0F, 0.0F, 2.5F, 3.5F, 5.0F, 8.0F};
                float[] expected = {0.0F, 0.0F, 2.5F, 3.5F, 5.0F, 5.0F};
                for (int i = 0; i < inputs.length; i++) {
                    OpenMaverFeatures.setReachDistance(inputs[i]);
                    check("distance " + inputs[i] + " clamps to " + expected[i] + mode,
                            OpenMaverFeatures.getReachDistance() == expected[i]
                                    && OpenMaverFeatures.getEffectiveReachDistance() == expected[i]);
                }
                check("5m reach attacks a target at 4.5m" + mode, attacks(5.0F, 4.5D, true) == 1);
                check("5m reach includes its endpoint" + mode, attacks(5.0F, 5.0D, true) == 1);
                check("5m reach excludes targets beyond 5m" + mode, attacks(5.0F, 5.01D, true) == 0);
                check("shorter distance limits the attack ray" + mode, attacks(3.5F, 4.5D, true) == 0);
                check("zero disables extended attacks" + mode, attacks(0.0F, 1.0D, true) == 0);
                check("extended reach still requires visibility" + mode, attacks(5.0F, 4.5D, false) == 0);
                double expectedAura = bypass ? (profile == 2 ? 2.99D : 2.97D) : 3.0D;
                check("KillAura profile distance is preserved" + mode,
                        Math.abs(((Number) auraRange.invoke(null)).doubleValue() - expectedAura) < 1.0E-6D);
            }
        }
    }

    /** 调用实际的实体筛选/射线/挥击/攻击链路，controller 只记录调用。 */
    private static int attacks(float distance, double targetDistance, boolean visible) throws Exception {
        ReachMinecraft mc = new ReachMinecraft();
        ReachPlayer player = new ReachPlayer();
        player.visible = visible;
        net.minecraft.world.World world = new net.minecraft.world.World();
        world.loadedEntityList.add(new ReachTarget(targetDistance));
        OpenMaverFeatures.setReachDistance(distance);
        Field used = OpenMaverFeatures.class.getDeclaredField("entityAttackUsedThisTick");
        used.setAccessible(true);
        used.setBoolean(null, false);
        Method attack = OpenMaverFeatures.class.getDeclaredMethod("attackNearestEntityInReach",
                net.minecraft.client.Minecraft.class, Object.class, Object.class);
        attack.setAccessible(true);
        attack.invoke(null, mc, player, world);
        return mc.playerController.attacks;
    }

    public static final class ReachMinecraft extends net.minecraft.client.Minecraft {
        public final ReachController playerController = new ReachController();
    }

    public static final class ReachController {
        int attacks;

        public void attackEntity(ReachPlayer player, net.minecraft.entity.Entity target) {
            if (player.swings != attacks + 1) throw new AssertionError("attack needs a preceding swing");
            attacks++;
        }
    }

    public static class ReachPlayer extends net.minecraft.entity.EntityLivingBase {
        boolean visible;
        int swings;

        public void swingItem() { swings++; }
        @Override public float getEyeHeight() { return 1.6F; }
        @Override public boolean canEntityBeSeen(net.minecraft.entity.Entity target) { return visible; }
        @Override public double getDistanceSqToEntity(net.minecraft.entity.Entity target) {
            return target.posX * target.posX + target.posY * target.posY + target.posZ * target.posZ;
        }
    }

    public static final class ReachTarget extends net.minecraft.entity.EntityLivingBase {
        private final net.minecraft.util.AxisAlignedBB box;

        ReachTarget(double distance) {
            posZ = distance + 0.4D;
            box = new net.minecraft.util.AxisAlignedBB(-0.3D, 0.0D, distance + 0.1D,
                    0.3D, 1.8D, distance + 0.7D);
        }

        public boolean canBeCollidedWith() { return true; }
        public float getCollisionBorderSize() { return 0.1F; }
        @Override public net.minecraft.util.AxisAlignedBB getEntityBoundingBox() { return box; }
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
        check("reach exposes a 0..5.0 distance slider instead of EXTRA",
                gui.contains("Setting.slider(\"DISTANCE\", 0.0F, 5.0F, 0.05F, 2, \" m\"")
                        && !gui.contains("Setting.slider(\"EXTRA\""));
        check("range widget keeps the low handle under the high handle",
                gui.contains("binding.set(Math.min(stepped(percent, 0.0D), bindingHigh.get()));"));
        check("range handles are drawn as opposing triangles",
                gui.contains("fillTriangle(lowX + 4, trackY + 1, lowX, trackY - 2, lowX, trackY + 4,")
                        && gui.contains("fillTriangle(highX - 4, trackY + 1, highX, trackY - 2, highX, trackY + 4,"));
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
            ReachDebug.hit("Bot" + i, 2.5D, 3.0D, 2.48D, 3.0D, 0.1305D, 0.0F, 0.0F, false);
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
            OpenMaverFeatures.setReachDistance(5.0F);
            OpenMaverFeatures.setReachGrimCheck(false);
            OpenMaverFeatures.setReachDebugChat(true);
            OpenMaverFeatures.flushSettings();
            check("reach.debug=true is persisted", read(tmp).contains("reach.debug=true"));
            check("5m reach distance is persisted as an absolute value",
                    read(tmp).contains("reach.distance=5.0"));
            check("raw distance mode is persisted", read(tmp).contains("reach.grimCheck=false"));
            check("no legacy reach.extra is written back",
                    !read(tmp).contains("reach.extra="));

            OpenMaverFeatures.setReachPingSpoof(120);
            OpenMaverFeatures.setReachPingJitter(60);
            OpenMaverFeatures.flushSettings();
            check("ping spoof is persisted",
                    read(tmp).contains("reach.pingSpoof=120")
                            && read(tmp).contains("reach.pingJitter=60"));

            // 热重载：把配置换成 false 后调用私有 refreshSettings，验证 debug 通道同步关闭。
            write(tmp, "settings.version=23\nreach.distance=3.0\nreach.chance=100\nreach.debug=false\n");
            reload();
            check("reload applies reach.debug=false",
                    !OpenMaverFeatures.isReachDebugChat() && !ReachDebug.isEnabled());

            write(tmp, "settings.version=23\nreach.distance=2.50\nreach.chance=55\nreach.debug=true\n");
            reload();
            check("reload applies reach.debug=true",
                    OpenMaverFeatures.isReachDebugChat() && ReachDebug.isEnabled());
            check("reload keeps reach tuning next to the debug switch",
                    Math.abs(OpenMaverFeatures.getReachDistance() - 2.5F) < 1.0E-4F
                            && Math.abs(OpenMaverFeatures.getReachChance() - 55.0F) < 1.0E-4F);
            List<String> header = ReachDebug.drain();
            check("reload re-announces with the new cap/chance",
                    has(header, "DEBUG ON") && has(header, "cap=2.50m") && has(header, "chance=55%"));
            write(tmp, "settings.version=23\nreach.distance=5.0\nreach.grimCheck=false\n");
            reload();
            check("reload preserves the 5m endpoint", OpenMaverFeatures.getEffectiveReachDistance() == 5.0F);
            write(tmp, "settings.version=23\nreach.distance=5.0\nreach.pingSpoof=250\nreach.pingJitter=0\n");
            reload();
            check("reload applies ping spoof", OpenMaverFeatures.getReachPingSpoof() == 250
                    && OpenMaverFeatures.getReachPingJitter() == 0);
            write(tmp, "settings.version=23\nreach.distance=5.0\nreach.pingSpoof=900\nreach.pingJitter=900\n");
            reload();
            check("ping spoof clamps to 400/150", OpenMaverFeatures.getReachPingSpoof() == 400
                    && OpenMaverFeatures.getReachPingJitter() == 150);
            OpenMaverFeatures.setReachPingSpoof(0);
            OpenMaverFeatures.setReachPingJitter(40);
            write(tmp, "settings.version=23\nreach.distance=8.0\n");
            reload();
            check("reload clamps distances above 5m", OpenMaverFeatures.getReachDistance() == 5.0F);
            write(tmp, "settings.version=23\nreach.distance=-1.0\n");
            reload();
            check("reload clamps negative distances to zero", OpenMaverFeatures.getReachDistance() == 0.0F);
            // 旧配置只有 reach.extra：迁移后等价于 3.0 + extra，上限为 5 米。
            write(tmp, "settings.version=23\nreach.extra=0.2\nreach.debug=true\n");
            reload();
            check("legacy reach.extra migrates to an absolute distance",
                    Math.abs(OpenMaverFeatures.getReachDistance() - 3.2F) < 1.0E-4F);
            write(tmp, "settings.version=23\nreach.extra=4.0\n");
            reload();
            check("legacy migration respects the 5m limit", OpenMaverFeatures.getReachDistance() == 5.0F);
            write(tmp, "settings.version=23\nreach.distance=4.5\nreach.extra=0.2\n");
            reload();
            check("absolute distance takes precedence over legacy extra", OpenMaverFeatures.getReachDistance() == 4.5F);
            write(tmp, "settings.version=23\n");
            reload();
            check("missing distance retains the 3m default", OpenMaverFeatures.getReachDistance() == 3.0F);
            check("missing grimCheck defaults to off", !OpenMaverFeatures.isReachGrimCheck());
            OpenMaverFeatures.setReachGrimCheck(true);
            OpenMaverFeatures.markDirty();
            OpenMaverFeatures.flushSettings();
            check("Grim cap mode is persisted", read(tmp).contains("reach.grimCheck=true"));
            write(tmp, "settings.version=23\nreach.distance=4.2\nreach.grimCheck=true\nbypass.enabled=false\n");
            reload();
            check("Grim cap limits the distance to 3.00m with global bypass off",
                    OpenMaverFeatures.getReachDistance() == 4.2F
                            && OpenMaverFeatures.getEffectiveReachDistance() == 3.0F);
            // v22 及更早把 GRIM CHECK 默认写成 true 且硬压 2.97 米：升级时必须
            // 自动解开，否则 DISTANCE 滑块依旧是摆设。
            write(tmp, "settings.version=22\nreach.distance=4.2\nreach.grimCheck=true\n");
            reload();
            check("legacy grimCheck=true is migrated to off",
                    !OpenMaverFeatures.isReachGrimCheck()
                            && OpenMaverFeatures.getEffectiveReachDistance() == 4.2F);
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
        // 迁移会置 settingsDirty；先清掉，确保每次 reload 都真的读文件。
        Field dirty = OpenMaverFeatures.class.getDeclaredField("settingsDirty");
        dirty.setAccessible(true);
        dirty.setBoolean(null, false);
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
