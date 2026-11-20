import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * GodBridge（神桥 / 蹲起搭路）回归测试。
 *
 * <p>教材给了三个硬指标和一条操作循环，测试就按这两块拆：
 *
 * <ol>
 *   <li><b>速度</b>：12 / 10 / 7 tick 一方块 → 1.67 / 2.00 / 2.86 方块每秒；</li>
 *   <li><b>方向</b>：S = 相机 +180°，S+D = 相机 +225°（等价于视角与桥线成 45°），
 *       并且 {@code suggestedCameraYaw} 能反推回同一个方向；</li>
 *   <li><b>循环</b>：放置 → RELEASE（松 Shift）→ RESNEAK（贴边按回）→ WAIT_EDGE
 *       （潜行到边缘）→ 放置；紧急余量/离地必须提前按回 Shift；</li>
 *   <li><b>结构</b>：只有一处 {@code onPlayerRightClick}、aim 只在 movementInput
 *       hook 里写、C09 同步、Scaffold 让位、配置与 ClickGUI/HUD 都接上了。</li>
 * </ol>
 *
 * <p>用法：{@code tools/godbridge-test/run.sh}
 */
public final class GodBridgeCycleTest {

    private static int passed;
    private static int failed;

    private static final int SPEED = GodBridgeFeature.MODE_SPEEDBRIDGE;
    private static final int DIAGONAL = GodBridgeFeature.MODE_DIAGONAL;
    private static final int SNEAK = GodBridgeFeature.MODE_SNEAK_TOGGLE;

    public static void main(String[] args) throws Exception {
        System.out.println("GodBridge (sneak-toggle) regression suite");
        System.out.println("-----------------------------------------");

        tutorialSpeeds();
        travelDirection();
        sneakCycle();
        placementGate();
        aimMath();
        edgeMath();
        sourceStructure();
        wiring();

        System.out.println("-----------------------------------------");
        System.out.println("checks: " + (passed + failed)
                + "  passed: " + passed
                + "  failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1) 教材速度：1.67 / 2 / 3 方块每秒
    // ------------------------------------------------------------------

    private static void tutorialSpeeds() {
        check("Speed Bridge = 12 ticks per block", GodBridgeFeature.ticksPerBlock(SPEED) == 12);
        check("Diagonal 45 = 10 ticks per block", GodBridgeFeature.ticksPerBlock(DIAGONAL) == 10);
        check("Sneak Toggle = 7 ticks per block", GodBridgeFeature.ticksPerBlock(SNEAK) == 7);

        check("Speed Bridge ~ 1.67 blocks/s",
                close(GodBridgeFeature.targetBps(SPEED), 1.67D, 0.01D));
        check("Diagonal 45 = 2.00 blocks/s",
                close(GodBridgeFeature.targetBps(DIAGONAL), 2.00D, 0.01D));
        check("Sneak Toggle ~ 3 blocks/s",
                close(GodBridgeFeature.targetBps(SNEAK), 3.00D, 0.15D));
        check("sneak toggle is the fastest of the three",
                GodBridgeFeature.targetBps(SNEAK) > GodBridgeFeature.targetBps(DIAGONAL)
                        && GodBridgeFeature.targetBps(DIAGONAL) > GodBridgeFeature.targetBps(SPEED));

        check("placement cooldown stays below one block interval",
                GodBridgeFeature.minPlaceInterval(SNEAK) < GodBridgeFeature.ticksPerBlock(SNEAK));
        check("cooldown keeps at least 5 ticks between C08s",
                GodBridgeFeature.minPlaceInterval(SNEAK) >= 5);

        check("release budget clamps to 1..4",
                GodBridgeFeature.sneakReleaseBudget(SNEAK, 0) == 1
                        && GodBridgeFeature.sneakReleaseBudget(SNEAK, 9) == 4
                        && GodBridgeFeature.sneakReleaseBudget(SNEAK, 3) == 3);
        check("only sneak toggle releases Shift",
                GodBridgeFeature.sneakReleaseBudget(SPEED, 3) == 0
                        && GodBridgeFeature.sneakReleaseBudget(DIAGONAL, 3) == 0);
    }

    // ------------------------------------------------------------------
    // 2) 教材方向：S 正后、S+D 45°
    // ------------------------------------------------------------------

    private static void travelDirection() {
        check("S walks straight away from the camera",
                close(GodBridgeFeature.travelYaw(0.0F, -1, 0), 180.0F, 0.01F));
        check("S+D turns 225 degrees (45 degrees off the bridge line)",
                close(GodBridgeFeature.travelYaw(0.0F, -1, 1), -135.0F, 0.01F));
        check("S+A mirrors S+D",
                close(GodBridgeFeature.travelYaw(0.0F, -1, -1), 135.0F, 0.01F));

        // 反推：视角与桥线 45° 时按住 S+D，行进方向应该正好是桥的方向。
        float bridge = 37.0F;
        float camera = GodBridgeFeature.suggestedCameraYaw(bridge, DIAGONAL);
        check("suggested camera yaw is 45 degrees off the bridge line",
                close(Math.abs(GodBridgeFeature.wrapDegrees(camera - bridge)), 135.0F, 0.01F));
        check("diagonal round-trip: camera + S+D = bridge direction",
                close(GodBridgeFeature.travelYaw(camera, -1, 1), bridge, 0.01F));

        float straightCamera = GodBridgeFeature.suggestedCameraYaw(bridge, SPEED);
        check("speed bridge looks straight down the bridge",
                close(straightCamera, GodBridgeFeature.wrapDegrees(bridge + 180.0F), 0.01F));
        check("speed bridge round-trip: camera + S = bridge direction",
                close(GodBridgeFeature.travelYaw(straightCamera, -1, 0), bridge, 0.01F));
        check("no input keeps the camera direction",
                close(GodBridgeFeature.travelYaw(123.0F, 0, 0), 123.0F, 0.01F));
    }

    // ------------------------------------------------------------------
    // 3) 蹲起循环：放 → 松 → 按回 → 贴边
    // ------------------------------------------------------------------

    private static void sneakCycle() {
        int release = GodBridgeFeature.PHASE_RELEASE;
        int resneak = GodBridgeFeature.PHASE_RESNEAK;
        int wait = GodBridgeFeature.PHASE_WAIT_EDGE;

        check("placing with room to walk -> RELEASE",
                GodBridgeFeature.stepPhase(GodBridgeFeature.PHASE_IDLE,
                        true, true, 0, 3, false, true) == release);
        check("placing while hugging the edge -> stay on EDGE, no release",
                GodBridgeFeature.stepPhase(GodBridgeFeature.PHASE_IDLE,
                        true, false, 0, 3, false, true) == wait);
        check("release keeps going while inside the budget",
                GodBridgeFeature.stepPhase(release, false, true, 1, 3, false, true) == release);
        check("release stops when the budget is spent",
                GodBridgeFeature.stepPhase(release, false, true, 3, 3, false, true) == resneak);
        check("emergency clearance re-sneaks immediately",
                GodBridgeFeature.stepPhase(release, false, true, 1, 3, true, true) == resneak);
        check("leaving the ground re-sneaks immediately",
                GodBridgeFeature.stepPhase(release, false, true, 1, 3, false, false) == resneak);
        check("RESNEAK hands over to the edge walk",
                GodBridgeFeature.stepPhase(resneak, false, true, 1, 3, false, true) == wait);
        check("EDGE stays EDGE while walking to the lip",
                GodBridgeFeature.stepPhase(wait, false, true, 4, 3, false, true) == wait);
        check("a fresh placement always restarts the cycle",
                GodBridgeFeature.stepPhase(wait, true, true, 0, 3, false, true) == release);

        check("Shift is held except during RELEASE",
                !GodBridgeFeature.sneakForPhase(release)
                        && GodBridgeFeature.sneakForPhase(resneak)
                        && GodBridgeFeature.sneakForPhase(wait)
                        && GodBridgeFeature.sneakForPhase(GodBridgeFeature.PHASE_IDLE));
        check("sneak toggle performs two Shift transitions per block",
                GodBridgeFeature.sneakForPhase(release) != GodBridgeFeature.sneakForPhase(resneak));
    }

    // ------------------------------------------------------------------
    // 4) 放置闸门
    // ------------------------------------------------------------------

    private static void placementGate() {
        int wait = GodBridgeFeature.PHASE_WAIT_EDGE;
        int release = GodBridgeFeature.PHASE_RELEASE;
        check("at the edge + cooldown + target -> place",
                GodBridgeFeature.mayPlace(wait, true, 7, 6, true));
        check("not at the edge -> no place", !GodBridgeFeature.mayPlace(wait, false, 7, 6, true));
        check("cooldown not elapsed -> no place",
                !GodBridgeFeature.mayPlace(wait, true, 5, 6, true));
        check("no target -> no place", !GodBridgeFeature.mayPlace(wait, true, 7, 6, false));
        check("RELEASE never places", !GodBridgeFeature.mayPlace(release, true, 7, 6, true));
        check("cooldown boundary is inclusive",
                GodBridgeFeature.mayPlace(wait, true, 6, 6, true));
    }

    // ------------------------------------------------------------------
    // 5) aim 数学
    // ------------------------------------------------------------------

    private static void aimMath() {
        float[] straight = GodBridgeFeature.rotationTo(0.0D, 2.0D, 0.0D, 0.0D, 2.0D, 5.0D);
        check("looking straight +Z gives yaw 0 / pitch 0",
                close(straight[0], 0.0F, 0.01F) && close(straight[1], 0.0F, 0.01F));

        float[] downRight = GodBridgeFeature.rotationTo(0.0D, 2.0D, 0.0D, 0.5D, 0.5D, 0.5D);
        check("block edge below-right: yaw -45", close(downRight[0], -45.0F, 0.01F));
        check("block edge below-right: pitch ~64.8", close(downRight[1], 64.76F, 0.05F));

        float[] behind = GodBridgeFeature.rotationTo(0.0D, 2.0D, 0.0D, 0.0D, 2.0D, -5.0D);
        check("looking -Z gives yaw 180", close(Math.abs(behind[0]), 180.0F, 0.01F));

        float[] straightDown = GodBridgeFeature.rotationTo(0.0D, 2.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        check("pitch is clamped at 89.5", straightDown[1] <= 89.5F && straightDown[1] >= 89.0F);

        check("yaw stays inside (-180, 180]",
                GodBridgeFeature.wrapDegrees(270.0F) == -90.0F
                        && GodBridgeFeature.wrapDegrees(-270.0F) == 90.0F);
    }

    // ------------------------------------------------------------------
    // 6) 贴边阈值
    // ------------------------------------------------------------------

    private static void edgeMath() {
        check("clearance just under the margin is at the edge",
                GodBridgeFeature.atEdge(0.29D, 0.30D));
        check("clearance exactly on the margin is at the edge",
                GodBridgeFeature.atEdge(0.30D, 0.30D));
        check("clearance above the margin is not at the edge",
                !GodBridgeFeature.atEdge(0.31D, 0.30D));
        check("already off the block counts as the edge",
                GodBridgeFeature.atEdge(0.0D, 0.30D));
        check("negative clearance (falling) is not a valid edge",
                !GodBridgeFeature.atEdge(-0.05D, 0.30D));
    }

    // ------------------------------------------------------------------
    // 7) 源码结构
    // ------------------------------------------------------------------

    private static void sourceStructure() throws Exception {
        String feature = read("mc/GodBridgeFeature.java");
        String hook = read("mc/ScaffoldMovementInput.java");
        String scaffold = read("mc/ScaffoldFeature.java");

        check("exactly one right-click call site", count(feature, "onPlayerRightClick") == 1);
        check("placement takes the shared C08 slot", feature.contains("claimPlacement()"));
        check("hotbar switch leaves C09 to vanilla sync",
                !feature.contains("C09PacketHeldItemChange")
                        && feature.contains("applyHeldSlot"));
        check("Shift is driven through KeyBinding.pressed",
                feature.contains("setKeyPressed(mc, \"keyBindSneak\""));
        check("edge detection uses collide boxes",
                feature.contains("getCollidingBoundingBoxes"));

        int aimWrite = feature.indexOf("setFloat(player, \"rotationYaw\"");
        int hookStart = feature.indexOf("static void afterMovementInput");
        int restoreStart = feature.indexOf("private static void restoreCameraView");
        check("aim is written inside the movementInput hook",
                hookStart > 0 && aimWrite > hookStart && aimWrite < restoreStart);
        check("the hook restores the camera before rendering",
                feature.contains("continuousYaw(playerYaw(player), cameraYaw)"));
        check("move fix scales diagonal input to keep sprint",
                feature.contains("0.8F / forward"));

        check("no jump spam: god bridge never touches jumpTicks",
                !feature.contains("jumpTicks") && !feature.contains("setJumpDelay"));
        check("no telly-style pitch table", !feature.contains("75.5"));
        check("C08 waits for the aim sent in the previous tick",
                feature.contains("pendingSent") && feature.contains("sentYaw"));
        check("fall back placement exists", feature.contains("findFallbackPlacement"));

        check("movement hook feeds GodBridge too",
                hook.contains("GodBridgeFeature.afterMovementInput(this)"));
        check("Scaffold yields while GodBridge is active",
                scaffold.contains("GodBridgeFeature.isActive()"));
    }

    // ------------------------------------------------------------------
    // 8) 接线：注册 / 配置 / GUI / HUD / 设置读写
    // ------------------------------------------------------------------

    private static void wiring() throws Exception {
        String core = read("java/OpenMaver.java");
        String config = read("config/default.jsonc");
        String features = read("mc/OpenMaverFeatures.java");
        String gui = read("mc/GuiOpenMaverClickGui.java");
        String hud = read("mc/OpenMaverHud.java");
        String module = read("java/module/movement/GodBridge.java");
        String bridgeHud = read("mc/GodBridgeHud.java");

        check("module is registered", core.contains("new GodBridge()"));
        check("config path is movement.godbridge", config.contains("\"godbridge\""));
        check("module keybind is N", config.contains("\"key\": \"N\""));
        check("settings load/save wired",
                features.contains("GodBridgeFeature.load(values)")
                        && features.contains("GodBridgeFeature.save()"));
        check("tick hooks wired",
                features.contains("GodBridgeFeature.onTickStart(mc)")
                        && features.contains("GodBridgeFeature.onTickEnd(mc)"));
        check("panic resets the state machine", features.contains("GodBridgeFeature.reset()"));
        check("ClickGUI exposes the tutorial settings",
                gui.contains("addGodBridgeSettings") && gui.contains("SNEAK WINDOW")
                        && gui.contains("EDGE MARGIN"));
        check("HUD is rendered", hud.contains("GodBridgeHud.render"));
        check("HUD reads the measured blocks/s", bridgeHud.contains("hudBlocksPerSecond"));

        check("enabling god bridge disables scaffold",
                module.contains("ModuleManager.getModule(\"Scaffold\")")
                        && module.contains("setEnabled(false)"));
        check("config key derives to movement.godbridge",
                module.contains("super(\"GodBridge\")"));

        check("module table names are stable",
                GodBridgeFeature.MODE_NAMES.length == 3
                        && GodBridgeFeature.PHASE_NAMES.length == 4);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String read(String path) throws Exception {
        Path file = Paths.get(path);
        if (!Files.exists(file)) {
            file = Paths.get("..", path);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            total++;
            index += needle.length();
        }
        return total;
    }

    private static boolean close(float value, float expected, float epsilon) {
        return Math.abs(value - expected) <= epsilon;
    }

    private static boolean close(double value, double expected, double epsilon) {
        return Math.abs(value - expected) <= epsilon;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
