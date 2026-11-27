import module.ModuleManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;

/**
 * KeepSprint（全方向疾跑，纯发包、不转头）回归测试。
 *
 * <p>覆盖四类不变量：
 *
 * <ol>
 *   <li><b>决策表</b>：只有“模块开 + 存活 + 有方向输入 + 非骑乘/飞行”才接管；
 *       潜行 / 使用物品按设置让位；只有原版冲刺一定会被取消的方向
 *       （后退、纯侧移、小幅前进、撞墙、饱食度不足）才挂自己的 +30% modifier，
 *       前进时交给原版，避免 1.3 × 1.3 叠加；攻击记号只在开启 HIT RESEND 时补包。</li>
 *   <li><b>速度表</b>：本地补偿 = 原版 “Sprinting speed boost” 的 +30%（operation 2），
 *       UUID 与<strong>原版不同</strong>，否则原版 {@code setSprinting(false)} 会把它删掉。</li>
 *   <li><b>配置 / 接线</b>：{@code movement.keepsprint} 配置路径、properties 往返、
 *       OpenMaverFeatures 的 load/save/reset、ClickGUI / HUD、START + END 两段接线。</li>
 *   <li><b>包与视角</b>：只补 C0B START_SPRINTING，不构造 C03、不写 motion、
 *       不写 rotationYaw / rotationPitch —— 也就是“纯发包、无转头”。</li>
 * </ol>
 *
 * <p>用法：{@code tools/keepsprint-test/run.sh}
 */
public final class KeepSprintTest {

    private static final String VANILLA_SPRINT_UUID =
            "662A6B8D-DA3E-4C1C-8813-96EA6097278D";

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("KeepSprint (omni-directional sprint) regression suite");
        System.out.println("----------------------------------------------------");

        decisionTable();
        speedMath();
        settingsRoundTrip();
        moduleIdentity();
        sourceStructure();

        System.out.println("----------------------------------------------------");
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

    /** 基础调用：前进 0.98、有输入、不潜行/不用物品、非骑乘飞行、存活、未撞墙、饱食度够。 */
    private static KeepSprintFeature.Plan plan(double forward, boolean speedBoost,
            boolean attacked, boolean attackResend) {
        return KeepSprintFeature.plan(true, forward, 0.0D,
                false, true, false, true, false, false, true, false, true,
                speedBoost, attacked, attackResend);
    }

    private static void decisionTable() {
        KeepSprintFeature.Plan run = plan(-0.98D, true, false, true);
        check("walking backwards activates the module", run.active);
        check("backwards needs the local speed modifier", run.ownBoost);
        check("plain movement does not resend a sprint packet", !run.resendSprint);

        check("pure strafe activates the module",
                KeepSprintFeature.plan(true, 0.0D, 0.98D, false, true, false, true,
                        false, false, true, false, true, true, false, true).active);
        check("pure strafe needs the local speed modifier",
                KeepSprintFeature.plan(true, 0.0D, 0.98D, false, true, false, true,
                        false, false, true, false, true, true, false, true).ownBoost);

        check("forward sprint is left to vanilla",
                !plan(0.98D, true, false, true).ownBoost);
        check("diagonal forward keeps raw components and uses vanilla sprint",
                !KeepSprintFeature.plan(true, 0.98D, 0.98D, false, true, false, true,
                        false, false, true, false, true, true, false, true).ownBoost);
        check("a reduced forward component needs compensation",
                KeepSprintFeature.plan(true, 0.7071D, 0.0D, false, true, false, true,
                        false, false, true, false, true, true, false, true).ownBoost);
        check("colliding with a wall needs the modifier",
                KeepSprintFeature.plan(true, 0.98D, 0.0D, false, true, false, true,
                        false, false, true, true, true, true, false, true).ownBoost);
        check("low food needs the modifier (vanilla would cancel)",
                KeepSprintFeature.plan(true, 0.98D, 0.0D, false, true, false, true,
                        false, false, true, false, false, true, false, true).ownBoost);

        check("disabled module is inactive",
                !KeepSprintFeature.plan(false, -0.98D, 0.0D, false, true, false, true,
                        false, false, true, false, true, true, true, true).active);
        check("standing still is inactive",
                !KeepSprintFeature.plan(true, 0.0D, 0.0D, false, true, false, true,
                        false, false, true, false, true, true, false, true).active);
        check("dead player is inactive",
                !KeepSprintFeature.plan(true, -0.98D, 0.0D, false, true, false, true,
                        false, false, false, false, true, true, false, true).active);
        check("riding is inactive",
                !KeepSprintFeature.plan(true, -0.98D, 0.0D, false, true, false, true,
                        true, false, true, false, true, true, false, true).active);
        check("creative/spectator flight is inactive",
                !KeepSprintFeature.plan(true, -0.98D, 0.0D, false, true, false, true,
                        false, true, true, false, true, true, false, true).active);

        check("sneaking yields when PAUSE SNEAK is on",
                !KeepSprintFeature.plan(true, -0.98D, 0.0D, true, true, false, true,
                        false, false, true, false, true, true, false, true).active);
        check("sneaking keeps sprinting when PAUSE SNEAK is off",
                KeepSprintFeature.plan(true, -0.98D, 0.0D, true, false, false, true,
                        false, false, true, false, true, true, false, true).active);
        check("using an item yields when PAUSE ITEM is on",
                !KeepSprintFeature.plan(true, -0.98D, 0.0D, false, true, true, true,
                        false, false, true, false, true, true, false, true).active);
        check("using an item keeps sprinting when PAUSE ITEM is off",
                KeepSprintFeature.plan(true, -0.98D, 0.0D, false, true, true, false,
                        false, false, true, false, true, true, false, true).active);

        KeepSprintFeature.Plan packetOnly = plan(-0.98D, false, false, true);
        check("SPEED BOOST off still keeps the sprint state", packetOnly.active);
        check("SPEED BOOST off leaves the local speed untouched", !packetOnly.ownBoost);

        check("attack with HIT RESEND queues C0B START_SPRINTING",
                plan(-0.98D, true, true, true).resendSprint);
        check("attack with HIT RESEND off sends nothing extra",
                !plan(-0.98D, true, true, false).resendSprint);
        check("attack while inactive is ignored",
                !KeepSprintFeature.plan(true, 0.0D, 0.0D, false, true, false, true,
                        false, false, true, false, true, true, true, true).resendSprint);
    }

    // ------------------------------------------------------------------
    // 2) 速度
    // ------------------------------------------------------------------

    private static void speedMath() {
        check("walk speed 0.1 becomes sprint speed 0.13",
                close(KeepSprintFeature.boostedSpeed(0.1D), 0.13D));
        check("speed compensation is exactly +30%",
                close(KeepSprintFeature.boostedSpeed(0.2D), 0.26D));
        check("zero input speed stays zero",
                close(KeepSprintFeature.boostedSpeed(0.0D), 0.0D));
        check("modifier amount matches vanilla's sprint modifier",
                close(KeepSprintFeature.BOOST_AMOUNT, 0.30000001192092896D));
        check("modifier operation is multiply-total (2)",
                KeepSprintFeature.BOOST_OPERATION == 2);
        check("modifier UUID is distinct from vanilla's sprint modifier",
                !VANILLA_SPRINT_UUID.equalsIgnoreCase(KeepSprintFeature.BOOST_UUID.toString()));

        check("forward + no wall + food keeps vanilla sprint",
                KeepSprintFeature.vanillaHoldsSprint(0.98D, false, true));
        check("backwards never keeps vanilla sprint",
                !KeepSprintFeature.vanillaHoldsSprint(-0.98D, false, true));
        check("diagonal forward keeps vanilla sprint",
                KeepSprintFeature.vanillaHoldsSprint(0.98D, false, true));
        check("wall contact cancels vanilla sprint",
                !KeepSprintFeature.vanillaHoldsSprint(0.98D, true, true));
        check("low food cancels vanilla sprint",
                !KeepSprintFeature.vanillaHoldsSprint(0.98D, false, false));

    }

    // ------------------------------------------------------------------
    // 3) 配置
    // ------------------------------------------------------------------

    private static void settingsRoundTrip() {
        Properties values = new Properties();
        values.setProperty("keepSprint.rotate", "false");
        values.setProperty("keepSprint.pauseSneak", "false");
        values.setProperty("keepSprint.pauseItem", "false");
        values.setProperty("keepSprint.speed", "false");
        values.setProperty("keepSprint.attackResend", "false");
        KeepSprintFeature.load(values);

        check("legacy rotate=false keeps silent rotation enabled", KeepSprintFeature.isRotate());
        check("load reads pauseSneak", !KeepSprintFeature.isPauseSneak());
        check("load reads pauseItem", !KeepSprintFeature.isPauseItem());
        check("load reads speed", !KeepSprintFeature.isSpeedBoost());
        check("load reads attackResend", !KeepSprintFeature.isAttackResend());

        String saved = KeepSprintFeature.save();
        check("save writes the fixed rotation mode", saved.contains("keepSprint.rotate=true"));
        check("save writes pauseSneak", saved.contains("keepSprint.pauseSneak=false"));
        check("save writes pauseItem", saved.contains("keepSprint.pauseItem=false"));
        check("save writes speed", saved.contains("keepSprint.speed=false"));
        check("save writes attackResend", saved.contains("keepSprint.attackResend=false"));

        KeepSprintFeature.load(new Properties());
        check("missing keys fall back to the defaults",
                KeepSprintFeature.isRotate() && KeepSprintFeature.isPauseSneak()
                        && KeepSprintFeature.isPauseItem()
                        && KeepSprintFeature.isSpeedBoost()
                        && KeepSprintFeature.isAttackResend());

        KeepSprintFeature.setSpeedBoost(false);
        check("setter flips the speed switch", !KeepSprintFeature.isSpeedBoost());
        KeepSprintFeature.setSpeedBoost(true);
        check("setter restores the speed switch", KeepSprintFeature.isSpeedBoost());
    }

    private static void moduleIdentity() {
        module.movement.KeepSprint keepSprint = new module.movement.KeepSprint();
        check("config path derives movement.keepsprint",
                "movement.keepsprint".equals(keepSprint.getConfigKey()));
        check("module is registered in OpenMaver.java",
                javaContains("java/OpenMaver.java", "ModuleManager.register(new KeepSprint())"));
        check("default.jsonc ships the module switch",
                javaContains("config/default.jsonc", "\"keepsprint\": {"));
        check("default.jsonc keeps it disabled with no hotkey",
                javaContains("config/default.jsonc",
                        "\"keepsprint\": {\n      \"enabled\": false"));

        ModuleManager.register(keepSprint);
        check("value in default.jsonc leaves the module off",
                !keepSprint.isEnabled()
                        && "movement.keepsprint".equals(keepSprint.getConfigKey()));
        check("summary is OFF while the module is disabled",
                "OFF".equals(KeepSprintFeature.getSummary()));
        check("UUID constant round-trips", UUID.fromString(KeepSprintFeature.BOOST_UUID.toString())
                .equals(KeepSprintFeature.BOOST_UUID));
    }

    // ------------------------------------------------------------------
    // 4) 源码结构（纯发包 / 无转头）
    // ------------------------------------------------------------------

    private static void sourceStructure() throws Exception {
        String feature = readText("mc/KeepSprintFeature.java");

        check("sprint uses the vanilla state setter",
                feature.contains("setSprinting\", \"func_70031_b\"")
                        && feature.contains("Boolean.TRUE"));
        check("the only fabricated packet is C0B START_SPRINTING",
                feature.contains("C0BPacketEntityAction")
                        && feature.contains("\"START_SPRINTING\"")
                        && !feature.contains("\"STOP_SPRINTING\""));
        check("no movement packet is fabricated",
                !feature.contains("C03PacketPlayer")
                        && !feature.contains("C04PacketPlayerPosition")
                        && !feature.contains("C05PacketPlayerLook")
                        && !feature.contains("C06PacketPlayerPosLook"));
        check("no motion is ever written",
                !feature.contains("motionX") && !feature.contains("motionY")
                        && !feature.contains("motionZ"));
        check("yaw is applied in the movement hook; pitch is untouched",
                feature.contains("static void afterMovementInput(MovementInput input)")
                        && feature.contains("\"rotationYaw\"")
                        && !feature.contains("\"rotationPitch\""));
        check("camera restoration is wired after native movement packets",
                readText("mc/OpenMaverFeatures.java").contains("KeepSprintFeature.onTickEnd(mc)")
                        && readText("mc/ScaffoldMovementInput.java").contains("KeepSprintFeature.afterMovementInput(this)"));
        check("the speed boost uses the vanilla movementSpeed attribute",
                feature.contains("SharedMonsterAttributes")
                        && feature.contains("field_111263_d")
                        && feature.contains("AttributeModifier")
                        && feature.contains("func_111121_a")
                        && feature.contains("func_111124_b"));
        check("air speed uses jumpMovementFactor",
                feature.contains("jumpMovementFactor")
                        && feature.contains("field_70747_aH"));

        String handler = readText("mc/OpenMaverClientHandler.java");
        check("the tick hook covers PlayerTickEvent START and END",
                handler.contains("TickEvent.PlayerTickEvent event")
                        && handler.contains("TickEvent.Phase.START")
                        && handler.contains("TickEvent.Phase.END"));
        check("START predicts input, END applies the packet state",
                handler.contains("KeepSprintFeature.onPlayerTickStart(mc, event.player)")
                        && handler.contains("KeepSprintFeature.onPlayerTickEnd(event.player)"));
        check("the attack hook records a resend marker",
                handler.contains("AttackEntityEvent event")
                        && handler.contains("KeepSprintFeature.onAttack(event.entityPlayer)"));

        String features = readText("mc/OpenMaverFeatures.java");
        check("settings are loaded and saved with the other features",
                features.contains("KeepSprintFeature.load(values)")
                        && features.contains("KeepSprintFeature.save()"));
        check("reset is called on reload and shutdown",
                count(features, "KeepSprintFeature.reset();") >= 2);
        check("no sprint forcing in the client tick START path",
                !features.contains("KeepSprintFeature.onPlayerTickEnd")
                        && !features.contains("KeepSprintFeature.onTickStart"));

        String gui = readText("mc/GuiOpenMaverClickGui.java");
        check("ClickGUI has a KeepSprint settings panel",
                gui.contains("\"KeepSprint\".equals(module.getName())")
                        && gui.contains("private void addKeepSprintSettings(Entry entry)"));
        check("ClickGUI exposes sneak, item and hit-resend switches",
                gui.contains("Setting.check(\"PAUSE SNEAK\"")
                        && gui.contains("Setting.check(\"PAUSE ITEM\"")
                        && gui.contains("Setting.check(\"HIT RESEND\""));
        check("ClickGUI shows the live KeepSprint summary",
                gui.contains("return KeepSprintFeature.getSummary();"));
        check("HUD omits the KeepSprint parameter preview",
                !readText("mc/OpenMaverHud.java").contains("KeepSprintFeature.getSummary()"));

        String module = readText("java/module/movement/KeepSprint.java");
        check("the core module stays MC-free",
                !module.contains("import net.minecraft") && module.contains("extends Module"));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < 1.0E-6D;
    }

    private static String readText(String relative) throws Exception {
        Path path = Paths.get(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static boolean javaContains(String relative, String needle) {
        try {
            return readText(relative).contains(needle);
        } catch (Exception error) {
            return false;
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

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    private KeepSprintTest() {
    }
}
