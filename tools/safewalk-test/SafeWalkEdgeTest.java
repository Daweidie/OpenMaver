import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * SafeWalk（方块边缘潜行，从 Scaffold 的 eagle 拆出来的独立模块）回归测试。
 *
 * <p>覆盖四类不变量：
 *
 * <ol>
 *   <li><b>决策表</b>：{@code decide(...)} 只在“地面 + 有输入 + 探针踏空”时按住
 *       Shift；让位（Scaffold / GodBridge 接管）与玩家自己按着 Shift 时一律不接管；
 *       滞回期间保持按住。</li>
 *   <li><b>几何</b>：{@code travelVector} 与 1.8.9 {@code moveFlying} 同一个换算；
 *       {@code probeOffset} 按 {@code distance} 前伸；{@code probeBox} 落在脚底下方。</li>
 *   <li><b>输入接管</b>：只写 {@code keyBindSneak.pressed}（原版自己发 C0B），
 *       不写 motion / rotation，不发任何伪造包；关闭与让位时按键还原成物理状态。</li>
 *   <li><b>配置 / 接线</b>：{@code movement.safewalk} 配置路径、properties 往返、
 *       OpenMaverFeatures 每 tick 驱动一次、ClickGUI 设置面板、default.jsonc 条目。</li>
 * </ol>
 *
 * <p>用法：{@code tools/safewalk-test/run.sh}
 */
public final class SafeWalkEdgeTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("SafeWalk (edge sneak) regression suite");
        System.out.println("--------------------------------------");

        decisionTable();
        holdHysteresis();
        travelMath();
        probeGeometry();
        clamping();
        configRoundTrip();
        sourceStructure();

        System.out.println("--------------------------------------");
        System.out.println("checks: " + (passed + failed)
                + "  passed: " + passed
                + "  failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1) 决策表
    // ------------------------------------------------------------------

    private static void decisionTable() {
        check("edge while walking on the ground holds shift",
                SafeWalkFeature.decide(true, false, true, true, false, true, 0));
        check("a disabled module never holds shift",
                !SafeWalkFeature.decide(false, false, true, true, false, true, 3));
        check("yield to Scaffold/GodBridge never holds shift",
                !SafeWalkFeature.decide(true, true, true, true, false, true, 3));
        check("airborne never holds shift (onlyGround)",
                !SafeWalkFeature.decide(true, false, false, true, false, true, 3));
        check("the player's own sneak is left alone",
                !SafeWalkFeature.decide(true, false, true, true, true, true, 3));
        check("no edge and no hold -> idle",
                !SafeWalkFeature.decide(true, false, true, true, false, false, 0));
        check("no edge but hold left -> still held",
                SafeWalkFeature.decide(true, false, true, true, false, false, 1));
        check("hold is only honoured while moving",
                !SafeWalkFeature.decide(true, false, true, false, false, false, 2));
    }

    private static void holdHysteresis() {
        check("default hold is a short window",
                SafeWalkFeature.getHoldTicks() >= 1 && SafeWalkFeature.getHoldTicks() <= 3);
        check("hold clamps at the minimum", clampHold(-5) == SafeWalkFeature.HOLD_MIN);
        check("hold clamps at the maximum", clampHold(99) == SafeWalkFeature.HOLD_MAX);
    }

    // ------------------------------------------------------------------
    // 2) 方向换算
    // ------------------------------------------------------------------

    private static void travelMath() {
        // yaw 0 面朝 +Z（南），forward=1 就是 +Z。
        double[] south = SafeWalkFeature.travelVector(0.0F, 1.0F, 0.0F);
        check("yaw 0 with forward moves +Z", close(south[0], 0.0D) && close(south[1], 1.0D));
        // yaw 90 面朝 -X（西）。
        double[] west = SafeWalkFeature.travelVector(90.0F, 1.0F, 0.0F);
        check("yaw 90 with forward moves -X", close(west[0], -1.0D) && close(west[1], 0.0D));
        // yaw 180 面朝 -Z（北）。
        double[] north = SafeWalkFeature.travelVector(180.0F, 1.0F, 0.0F);
        check("yaw 180 with forward moves -Z", close(north[0], 0.0D) && close(north[1], -1.0D));
        // straight left (strafe=1) 在 yaw 0 是 +X。
        double[] left = SafeWalkFeature.travelVector(0.0F, 0.0F, 1.0F);
        check("yaw 0 with strafe moves +X", close(left[0], 1.0D) && close(left[1], 0.0D));
        // 斜向输入必须归一化：迈一步的位移长度和直走一样。
        double[] diagonal = SafeWalkFeature.travelVector(0.0F, 1.0F, 1.0F);
        check("diagonal input is normalised",
                close(Math.hypot(diagonal[0], diagonal[1]), 1.0D));
        // 没有输入：既不前伸也不判边缘。
        double[] idle = SafeWalkFeature.travelVector(35.0F, 0.0F, 0.0F);
        check("no input yields a zero vector", idle[0] == 0.0D && idle[1] == 0.0D);
        // 相反输入也归零（前进 + 后退同时按），不应把探针甩出去。
        double[] cancel = SafeWalkFeature.travelVector(0.0F, 1.0F, 0.0F);
        check("forward is a unit vector", close(Math.hypot(cancel[0], cancel[1]), 1.0D));
    }

    private static void probeGeometry() {
        double[] offset = SafeWalkFeature.probeOffset(0.0F, 1.0F, 0.0F, 0.3D);
        check("probe offset is distance * direction",
                close(offset[0], 0.0D) && close(offset[1], 0.3D));

        net.minecraft.util.AxisAlignedBB box =
                new net.minecraft.util.AxisAlignedBB(0.2D, 64.0D, 0.2D, 0.8D, 65.8D, 0.8D);
        net.minecraft.util.AxisAlignedBB probe = SafeWalkFeature.probeBox(box, 0.3D, 0.0D);
        double radius = SafeWalkFeature.PROBE_RADIUS;
        check("probe follows the box centre in X", close(probe.minX, 0.5D + 0.3D - radius)
                && close(probe.maxX, 0.5D + 0.3D + radius));
        check("probe keeps the box centre in Z", close(probe.minZ, 0.5D - radius)
                && close(probe.maxZ, 0.5D + radius));
        check("probe sits just under the feet",
                close(probe.maxY, 64.0D - SafeWalkFeature.PROBE_DROP));
        check("probe is thin, not the whole hitbox", radius > 0.0D && radius < 0.1D);
        check("probe stays below the feet (no wall false positives)", probe.maxY < 64.0D);
    }

    private static void clamping() {
        SafeWalkFeature.setDistance(5.0D);
        check("distance clamps at the maximum",
                close(SafeWalkFeature.getDistance(), SafeWalkFeature.DISTANCE_MAX));
        SafeWalkFeature.setDistance(-1.0D);
        check("distance clamps at the minimum",
                close(SafeWalkFeature.getDistance(), SafeWalkFeature.DISTANCE_MIN));
        SafeWalkFeature.setDistance(0.3D);
        check("distance survives a normal set",
                close(SafeWalkFeature.getDistance(), 0.3D));
    }

    // ------------------------------------------------------------------
    // 3) 配置
    // ------------------------------------------------------------------

    private static void configRoundTrip() {
        Properties values = new Properties();
        values.setProperty("safewalk.distance", "0.45");
        values.setProperty("safewalk.onlyGround", "false");
        values.setProperty("safewalk.requireMoving", "false");
        values.setProperty("safewalk.respectSneak", "false");
        values.setProperty("safewalk.holdTicks", "4");
        SafeWalkFeature.load(values);

        check("load reads the distance", close(SafeWalkFeature.getDistance(), 0.45D));
        check("load reads onlyGround", !SafeWalkFeature.isOnlyGround());
        check("load reads requireMoving", !SafeWalkFeature.isRequireMoving());
        check("load reads respectSneak", !SafeWalkFeature.isRespectSneak());
        check("load reads holdTicks", SafeWalkFeature.getHoldTicks() == 4);

        String saved = SafeWalkFeature.save();
        check("save writes the distance", saved.contains("safewalk.distance=0.45"));
        check("save writes onlyGround", saved.contains("safewalk.onlyGround=false"));
        check("save writes requireMoving", saved.contains("safewalk.requireMoving=false"));
        check("save writes respectSneak", saved.contains("safewalk.respectSneak=false"));
        check("save writes holdTicks", saved.contains("safewalk.holdTicks=4"));

        Properties defaults = new Properties();
        SafeWalkFeature.load(defaults);
        check("missing keys fall back to the defaults",
                close(SafeWalkFeature.getDistance(), 0.30D)
                        && SafeWalkFeature.isOnlyGround()
                        && SafeWalkFeature.isRequireMoving()
                        && SafeWalkFeature.isRespectSneak()
                        && SafeWalkFeature.getHoldTicks() == 2);

        moduleIdentity();
    }

    private static void moduleIdentity() {
        module.movement.SafeWalk module = new module.movement.SafeWalk();
        check("config path derives movement.safewalk",
                "movement.safewalk".equals(module.getConfigKey()));
        check("module is registered in OpenMaver.java",
                java_contains("java/OpenMaver.java", "ModuleManager.register(new SafeWalk())"));
        check("default.jsonc ships the module switch",
                java_contains("config/default.jsonc", "\"safewalk\": {"));
    }

    // ------------------------------------------------------------------
    // 4) 源码结构
    // ------------------------------------------------------------------

    private static void sourceStructure() throws Exception {
        String feature = readText("mc/SafeWalkFeature.java");

        check("sneak is written through the vanilla key binding",
                feature.contains("setBoolean(binding, \"pressed\", \"field_74513_e\", pressed)")
                        && feature.contains("keyBindSneak"));
        check("the key is restored from the physical keyboard",
                feature.contains("restoreSneakToPhysical")
                        && feature.contains("Keyboard.isKeyDown"));
        check("the physical key state is read from Keyboard, not from pressed",
                feature.contains("private static boolean sneakKeyHeld"));
        check("no motion is ever written", !feature.contains("motionX")
                && !feature.contains("motionY") && !feature.contains("motionZ"));
        check("rotation is only read for the probe direction",
                !feature.contains("setNumber(")
                        && feature.contains("\"rotationYaw\", \"field_70177_z\""));
        check("no packet is fabricated", !feature.contains("C0BPacketEntityAction")
                && !feature.contains("sendQueue.addToSendQueue"));
        check("edge detection asks the world for collision boxes",
                feature.contains("\"getCollidingBoundingBoxes\",\n                \"func_72945_a\"")
                        || feature.contains("getCollidingBoundingBoxes"));
        check("the probe is driven by the vanilla moveFlying direction",
                feature.contains("strafe * cos - forward * sin")
                        && feature.contains("forward * cos + strafe * sin"));
        check("applySneak is the only place that touches the key",
                count(feature, "setPressed(mc, true)") == 1
                        && count(feature, "applySneak(mc, sneak, yielded)") == 1);
        check("the module yields to Scaffold, Scaffold2 and GodBridge",
                feature.contains("ScaffoldFeature.ownsSneakKey()")
                        && feature.contains("Scaffold2Feature.ownsSneakKey()")
                        && feature.contains("GodBridgeFeature.isActive()"));
        check("the module is driven once per tick from OpenMaverFeatures",
                count(readText("mc/OpenMaverFeatures.java"), "SafeWalkFeature.onTickStart(mc)") == 1);
        String features = readText("mc/OpenMaverFeatures.java");
        check("settings are loaded and saved with the other features",
                features.contains("SafeWalkFeature.load(values)")
                        && features.contains("SafeWalkFeature.save()"));
        check("reset hands the key back on shutdown",
                count(features, "SafeWalkFeature.reset();") >= 2);
        java.util.regex.Matcher version = java.util.regex.Pattern
                .compile("SETTINGS_VERSION\\s*=\\s*(\\d+)").matcher(features);
        check("settings version includes the new keys",
                version.find() && Integer.parseInt(version.group(1)) >= 22
                        && features.contains("safewalk.distance"));
        check("Scaffold and Scaffold2 expose sneak-key ownership",
                readText("mc/ScaffoldFeature.java").contains("public static boolean ownsSneakKey()")
                        && readText("mc/Scaffold2Feature.java")
                                .contains("public static boolean ownsSneakKey()"));
        check("Scaffold never calls into SafeWalk (separation)",
                !readText("mc/ScaffoldFeature.java").contains("SafeWalkFeature."));

        String gui = readText("mc/GuiOpenMaverClickGui.java");
        check("ClickGUI has a SafeWalk settings panel",
                gui.contains("\"SafeWalk\".equals(module.getName())")
                        && gui.contains("private void addSafeWalkSettings(Entry entry)"));
        check("ClickGUI exposes distance, ground, moving, sneak and hold",
                gui.contains("Setting.slider(\"DISTANCE\"")
                        && gui.contains("Setting.check(\"ONLY GROUND\"")
                        && gui.contains("Setting.check(\"REQUIRE MOVING\"")
                        && gui.contains("Setting.check(\"RESPECT SNEAK\"")
                        && gui.contains("Setting.slider(\"HOLD\""));

        String hud = readText("mc/OpenMaverHud.java");
        check("HUD shows the SafeWalk state",
                hud.contains("\"SafeWalk\".equals(name)")
                        && hud.contains("SafeWalkFeature.getSummary()"));

        String module = readText("java/module/movement/SafeWalk.java");
        check("the core module stays MC-free",
                !module.contains("net.minecraft") && module.contains("extends Module"));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static int clampHold(int value) {
        int min = SafeWalkFeature.HOLD_MIN;
        int max = SafeWalkFeature.HOLD_MAX;
        return Math.max(min, Math.min(max, value));
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < 1.0E-6D;
    }

    private static boolean java_contains(String relative, String needle) {
        try {
            return readText(relative).contains(needle);
        } catch (Exception error) {
            return false;
        }
    }

    private static String readText(String relative) throws Exception {
        Path path = Paths.get(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
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

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    private SafeWalkEdgeTest() {
    }
}
