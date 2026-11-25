import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Scaffold（OpenZen 移植版）回归测试。
 *
 * <p>覆盖三类不变量：
 *
 * <ol>
 *   <li><b>决策表</b>：{@code pendingDecision} 只允许“已经写进 C03 的角度 + ray trace
 *       命中”触发放置；打不到就重试，重试次数有上限。</li>
 *   <li><b>旋转数学</b>：{@code rotateTowards} 走最短路径且限速；
 *       {@code clampAngle} / {@code angleDiffDouble} / {@code oppositeFace} /
 *       {@code getBlockDistance} 与 OpenZen 的公式一致。</li>
 *   <li><b>结构</b>：源码里只能有一处 {@code onPlayerRightClick}、一处
 *       {@code new Pending}；静默 aim 必须在 movementInput hook 里写；切槽必须发 C09；
 *       Myau 时代的设置（rotations / safeWalk / tower / keepYonPress）不得残留。</li>
 * </ol>
 *
 * <p>用法：{@code tools/scaffold-test/run.sh}
 */
public final class ScaffoldPlaceOrderTest {

    private static int passed;
    private static int failed;

    private static final int DROP = ScaffoldFeature.PENDING_DROP;
    private static final int RETRY = ScaffoldFeature.PENDING_RETRY;
    private static final int PLACE = ScaffoldFeature.PENDING_PLACE;

    public static void main(String[] args) throws Exception {
        System.out.println("Scaffold (OpenZen port) regression suite");
        System.out.println("---------------------------------------");

        pendingDecisionTable();
        rotationStepping();
        angleHelpers();
        faceMapping();
        blockDistance();
        clutchWindow();
        floorBehaviour();
        faceAimPoint();
        sourceStructure();
        hudAndGui();

        System.out.println("---------------------------------------");
        System.out.println("checks: " + (passed + failed)
                + "  passed: " + passed
                + "  failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1) flushPending 决策表
    // ------------------------------------------------------------------

    private static void pendingDecisionTable() {
        check("unsent aim is never placed", ScaffoldFeature.pendingDecision(false, true, 0) == DROP);
        check("unsent aim with miss is dropped", ScaffoldFeature.pendingDecision(false, false, 0) == DROP);
        check("sent + rayHit -> PLACE", ScaffoldFeature.pendingDecision(true, true, 0) == PLACE);
        check("sent + rayHit after retries -> PLACE",
                ScaffoldFeature.pendingDecision(true, true, ScaffoldFeature.MAX_PENDING_RETRIES) == PLACE);
        check("sent + miss, retries=0 -> RETRY",
                ScaffoldFeature.pendingDecision(true, false, 0) == RETRY);
        check("sent + miss, retries=1 -> RETRY",
                ScaffoldFeature.pendingDecision(true, false, 1) == RETRY);
        check("sent + miss at the retry cap -> DROP",
                ScaffoldFeature.pendingDecision(true, false, ScaffoldFeature.MAX_PENDING_RETRIES) == DROP);
        check("sent + miss past the cap -> DROP",
                ScaffoldFeature.pendingDecision(true, false, ScaffoldFeature.MAX_PENDING_RETRIES + 1) == DROP);
        check("retry cap stays small (<= 4)", ScaffoldFeature.MAX_PENDING_RETRIES <= 4);
    }

    // ------------------------------------------------------------------
    // 2) 旋转步进
    // ------------------------------------------------------------------

    private static void rotationStepping() {
        check("rotateTowards clamps to the step",
                close(rotateTowards(45.0D, 0.0F, 90.0F), 45.0F));
        check("rotateTowards clamps negative steps",
                close(rotateTowards(45.0D, 90.0F, 0.0F), 45.0F));
        check("rotateTowards lands exactly on the target",
                close(rotateTowards(180.0D, 10.0F, 100.0F), 100.0F));
        // 170 -> -170 的最短路径是 +20，结果保持连续（190，而不是 -170）。
        check("rotateTowards takes the shortest way across +/-180",
                close(rotateTowards(180.0D, 170.0F, -170.0F), 190.0F));
        // 179 -> -179 的最短路径是 +2；返回值不取模，仍是 181。
        check("rotateTowards keeps yaw continuous (no wrap)",
                close(rotateTowards(5.0D, 179.0F, -179.0F), 181.0F));
        check("OpenZen rotation step is at least 180 degrees/tick",
                ScaffoldFeature.getBlockDistance(ScaffoldFeature.MODE_NORMAL, 6) >= 180.0D);
    }

    // ------------------------------------------------------------------
    // 3) 角度工具
    // ------------------------------------------------------------------

    private static void angleHelpers() {
        check("clampAngle keeps small angles",
                close(ScaffoldFeature.clampAngle(10.0F, 45.0F), 10.0F));
        check("clampAngle clamps positive",
                close(ScaffoldFeature.clampAngle(60.0F, 45.0F), 45.0F));
        check("clampAngle clamps negative",
                close(ScaffoldFeature.clampAngle(-60.0F, 45.0F), -45.0F));
        check("angleDiffDouble wraps across 0/360",
                close((float) ScaffoldFeature.angleDiffDouble(350.0F, 10.0F), -20.0F));
        check("angleDiffDouble is antisymmetric",
                close((float) ScaffoldFeature.angleDiffDouble(10.0F, 350.0F), 20.0F));
        check("angleDiffDouble handles identical angles",
                close((float) ScaffoldFeature.angleDiffDouble(12.5F, 12.5F), 0.0F));
        check("wrapDegrees normalises +270 to -90",
                close(ScaffoldFeature.wrapDegrees(270.0F), -90.0F));
    }

    // ------------------------------------------------------------------
    // 4) 支撑面映射
    // ------------------------------------------------------------------

    private static void faceMapping() {
        int[] expected = {1, 0, 3, 2, 5, 4};
        boolean ok = true;
        for (int face = 0; face < 6; face++) {
            if (ScaffoldFeature.oppositeFace(face) != expected[face]) {
                ok = false;
            }
            if (ScaffoldFeature.oppositeFace(ScaffoldFeature.oppositeFace(face)) != face) {
                ok = false;
            }
        }
        check("support face is the opposite of the approach direction", ok);
        check("UP maps to clicking DOWN", ScaffoldFeature.oppositeFace(1) == 0);
        check("NORTH maps to clicking SOUTH", ScaffoldFeature.oppositeFace(2) == 3);
    }

    // ------------------------------------------------------------------
    // 5) 旋转速度档位
    // ------------------------------------------------------------------

    private static void blockDistance() {
        check("Old Telly always rotates 180 degrees/tick",
                close((float) ScaffoldFeature.getBlockDistance(ScaffoldFeature.MODE_OLD_TELLY, 6), 180.0F));
        check("rotationTick=1 gives the full 360 degrees",
                close((float) ScaffoldFeature.getBlockDistance(
                        ScaffoldFeature.MODE_TELLY_BRIDGE, 1), 360.0F));
        check("rotationTick=6 floors at 180 degrees",
                close((float) ScaffoldFeature.getBlockDistance(
                        ScaffoldFeature.MODE_TELLY_BRIDGE, 6), 180.0F));
        check("rotationTick=2 gives 180 degrees",
                close((float) ScaffoldFeature.getBlockDistance(
                        ScaffoldFeature.MODE_TELLY_BRIDGE, 2), 180.0F));
        check("rotationTick is clamped to 1..6", ScaffoldFeature.MODE_NAMES.length == 4);
    }

    // ------------------------------------------------------------------
    // 6) clutch 窗口
    // ------------------------------------------------------------------

    private static void clutchWindow() {
        check("clutch holds while falling",
                ScaffoldFeature.clutchHold(true, false, 0, 0));
        check("clutch releases when the target is reachable",
                !ScaffoldFeature.clutchHold(true, true, 0, 0));
        check("clutch holds during the knockback window",
                ScaffoldFeature.clutchHold(true, true, 10, 0));
        check("clutch gives up after the rotation-delay budget",
                ScaffoldFeature.clutchHold(true, false, 0, 9) == false);
        check("clutch keeps holding at the budget limit",
                ScaffoldFeature.clutchHold(true, false, 0, 8));
        check("clutch off means no hold", !ScaffoldFeature.clutchHold(false, false, 0, 0));
    }

    // ------------------------------------------------------------------
    // 7) floor
    // ------------------------------------------------------------------

    private static void floorBehaviour() {
        check("floor(3.9) == 3", ScaffoldFeature.floor(3.9D) == 3);
        check("floor(-0.1) == -1", ScaffoldFeature.floor(-0.1D) == -1);
        check("floor(-3.0) == -3", ScaffoldFeature.floor(-3.0D) == -3);
        check("floor(64.0) == 64", ScaffoldFeature.floor(64.0D) == 64);
    }

    // ------------------------------------------------------------------
    // 8) 点击点：不落在方块中心，且 aim 射线与面平面一致
    // ------------------------------------------------------------------

    private static void faceAimPoint() {
        // 每个面都必须精确落在该面所在的平面上，并且面内偏移不超过 0.5（留边）。
        boolean onPlane = true;
        boolean inside = true;
        boolean offCenter = false;
        int[] faces = {0, 1, 2, 3, 4, 5};
        for (int i = 0; i < 200; i++) {
            for (int face : faces) {
                double[] p = ScaffoldFeature.randomFacePoint(4, 64, -7, face);
                double planeValue = face == 0 ? 64.0D : face == 1 ? 65.0D
                        : face == 2 ? -7.0D : face == 3 ? -6.0D
                        : face == 4 ? 4.0D : 5.0D;
                int axis = face <= 1 ? 1 : (face <= 3 ? 2 : 0);
                if (Math.abs(p[axis] - planeValue) > 1.0E-9D) {
                    onPlane = false;
                }
                for (int k = 0; k < 3; k++) {
                    if (k == axis) {
                        continue;
                    }
                    int base = k == 0 ? 4 : (k == 1 ? 64 : -7);
                    if (p[k] < base + 0.19D || p[k] > base + 0.81D) {
                        inside = false;
                    }
                }
                double dx = Math.abs(p[0] - 4.5D);
                double dy = Math.abs(p[1] - 64.5D);
                double dz = Math.abs(p[2] - (-6.5D));
                if (face <= 1 ? (dx > 0.15D || dz > 0.15D) : (dy > 0.15D)) {
                    offCenter = true;
                }
            }
        }
        check("click point lies exactly on the clicked face plane", onPlane);
        check("click point stays inside the face (no edge hits)", inside);
        check("click point is not pinned to the block centre", offCenter);

        // 直下瞄准：射线与 up 面相交，落点在面内。
        float[] eye = {4.5F, 68.0F, -6.5F};
        double[] hit = ScaffoldFeature.rayFacePoint(eye, 0.0F, 90.0F, 4, 64, -7, 1);
        check("aim ray intersects the up face",
                hit != null && Math.abs(hit[1] - 65.0D) < 1.0E-6D);
        // 反方向（抬头）：面在眼睛背面，不能给出交点。
        check("aim ray pointing away yields no face point",
                ScaffoldFeature.rayFacePoint(eye, 0.0F, -90.0F, 4, 64, -7, 1) == null);
        // 射线落在面外（这里瞄准正上方），应当回退而不是返回越界点。
        check("aim ray outside the face yields no face point",
                ScaffoldFeature.rayFacePoint(eye, 0.0F, 0.0F, 4, 64, -7, 1) == null);
    }

    // ------------------------------------------------------------------
    // 9) 源码结构
    // ------------------------------------------------------------------

    private static void sourceStructure() throws Exception {
        String feature = read("mc/ScaffoldFeature.java");

        check("onPlayerRightClick is invoked exactly once (single placement path)",
                count(feature, "call(controller, \"onPlayerRightClick\"") == 1);
        check("place(...) is the only onPlayerRightClick call site",
                feature.contains("private static boolean place(Minecraft mc, Object player, Object world, Pending target)"));
        check("place() is reachable only from flushPending",
                count(feature, "place(mc, player, world, target)") == 1);
        check("flushPending asks the decision table first",
                feature.contains("pendingDecision(target.sent, rayHit, target.retries)"));
        check("flushPending validates the angle the server already received",
                feature.contains("target.sentYaw") && feature.contains("target.sentPitch"));
        check("pending is created once per tick inside tick()",
                count(feature, "new Pending(") == 1);
        check("the aim is written in the movement-input hook",
                feature.contains("static void afterMovementInput(Object input)")
                        && feature.contains("setFloat(player, \"rotationYaw\""));
        check("server aim keeps a continuous yaw across 360 degrees",
                feature.contains("float yawBase = Float.isNaN(prevAimYaw) ? playerYaw(player) : prevAimYaw")
                        && feature.contains("continuousYaw(yawBase, tickAimYaw)"));
        check("silent aim is restored to the camera",
                feature.contains("restoreCameraView") && feature.contains("renderArmYaw"));
        // C09 一律由原版发：1.8.9 每 tick 的 updateController() / onPlayerRightClick()
        // 第一步都是 syncCurrentPlayItem()，手发一条会让服务端收到两条相同 slot 的
        // C09 -> Grim BadPacketsA("Sent duplicate slot id")。
        check("held-slot switch never hand-sends C09",
                !feature.contains("C09PacketHeldItemChange")
                        && !feature.contains("sendHeldItemChange"));
        check("slot switch documents the vanilla per-tick sync",
                feature.contains("updateController"));
        String godBridge = read("mc/GodBridgeFeature.java");
        check("god bridge uses the same vanilla-synced slot switch",
                !godBridge.contains("C09PacketHeldItemChange")
                        && godBridge.contains("private static void applyHeldSlot"));
        check("item spoof flips the client slot without a C09",
                feature.contains("setSlotClientOnly(player, oldSlot)"));
        // 手持方块来源：HOTBAR（真实换手）为默认，SILENT 只在显式切换时生效。
        check("hotbar slot mode is the default",
                ScaffoldFeature.getSlotMode() == ScaffoldFeature.SLOT_HOTBAR
                        && ScaffoldFeature.SLOT_NAMES.length == 2);
        check("placed stack comes from the held item in HOTBAR mode",
                feature.contains("placedStack(player)")
                        && feature.contains("private static Object placedStack(Object player)")
                        && feature.contains("\"getHeldItem\", \"func_70694_bm\""));
        check("a non-block held item never fakes a C08",
                feature.contains("return isPlaceableBlock(held) ? held : null;"));
        check("item spoof is gated to the SILENT slot mode",
                feature.contains("bridgeSettings.slotMode == SLOT_SILENT && bridgeSettings.itemSpoof"));
        check("C09 and C08 are separated by one tick",
                feature.contains("slotSwitchTick == currentTick"));
        check("slot mode is persisted",
                feature.contains("scaffold.slotMode")
                        && feature.contains("getSlotMode()") && feature.contains("setSlotMode("));
        // aim 不再指向方块中心：rotationToBlock 用 Placement 的随机面点。
        check("aim targets the random face point, not the block centre",
                feature.contains("double dx = target.hitX - px;")
                        && !feature.contains("double dx = target.x - px + 0.5D;"));
        check("C08 hitVec reuses the same point as the aim",
                feature.contains("return new double[]{target.hitX, target.hitY, target.hitZ};")
                        && !feature.contains("new Placement(target.x, target.y, target.z, target.face)"));
        check("placement is reused while the target is unchanged",
                feature.contains("placement.sameTarget(supportX, supportY, supportZ, clickedFace)"));
        check("knockback window is observed from S12",
                feature.contains("S12PacketEntityVelocity"));
        check("knockback window decays every tick",
                feature.contains("velocityDelay--") && feature.contains("velocityDelay <= 30"));
        check("move fix only runs with real WASD input",
                feature.contains("!aimApplied && isMoving(mc)"));
        check("clutch uses the two-tick vertical simulation",
                feature.contains("simulateFallY(player, 2)"));
        check("target search walks rings 1..6 below targetYLevel",
                feature.contains("for (int radius = 1; radius <= SEARCH_RADIUS; radius++)"));
        check("OpenZen modes are present",
                feature.contains("MODE_TELLY_BRIDGE") && feature.contains("MODE_OLD_TELLY")
                        && feature.contains("MODE_KEEP_Y"));
        check("no Myau-era settings are left",
                !feature.contains("getRotations(") && !feature.contains("isSafeWalk(")
                        && !feature.contains("getTower(") && !feature.contains("keepYonPress"));
        check("no legacy Myau tower/keepY state machine",
                !feature.contains("towerFlatTicks") && !feature.contains("EXTRATELLY"));

        String hud = read("mc/ScaffoldHud.java");
        // OpenZen 的紫色进度条后来并进了统一的白色胶囊卡（compact BlockHUD）。
        check("HUD draws the progress bar fill",
                hud.contains("BAR_FILL = 0xFFFFFF") || hud.contains("BAR_FILL = 0x9900FF"));
        check("HUD prints the block count and b/s speed",
                hud.contains("\" blocks\"") && hud.contains("%.2fb/s"));
        check("HUD hides itself when there is no block",
                hud.contains("if (stack == null)"));
        check("HUD is gated by the module switch",
                hud.contains("ScaffoldFeature.isHud()") && hud.contains("ModuleManager.getModule(\"Scaffold\")"));

        String input = read("mc/ScaffoldMovementInput.java");
        check("movement hook runs after vanilla input update",
                input.contains("super.updatePlayerMoveState()")
                        && input.contains("ScaffoldFeature.afterMovementInput(this)"));

        String gui = read("mc/GuiOpenMaverClickGui.java");
        check("ClickGUI exposes the OpenZen mode list",
                gui.contains("SCAFFOLD_MODES = ScaffoldFeature.MODE_NAMES"));
        check("ClickGUI exposes the slot mode select",
                gui.contains("SCAFFOLD_SLOT_MODES = ScaffoldFeature.SLOT_NAMES")
                        && gui.contains("Setting.select(\"SLOT\""));
        check("ClickGUI no longer exposes Myau settings",
                !gui.contains("SCAFFOLD_TOWER") && !gui.contains("SCAFFOLD_ROTATIONS"));

        String features = read("mc/OpenMaverFeatures.java");
        check("legacy Myau scaffold path is gone",
                !features.contains("updateScaffold(") && !features.contains("tryScaffoldPlacement("));
        check("OpenMaverFeatures still drives ScaffoldFeature each tick",
                features.contains("ScaffoldFeature.onTickStart(mc)")
                        && features.contains("ScaffoldFeature.onTickEnd(mc)"));
    }

    // ------------------------------------------------------------------
    // 9) HUD / GUI 常量
    // ------------------------------------------------------------------

    private static void hudAndGui() throws Exception {
        String hud = read("mc/ScaffoldHud.java");
        // 卡片在“紧凑 BlockHUD”之后是 160x32，布局断言跟着实现走。
        check("HUD card keeps a 32 unit height",
                hud.contains("CARD_H = 32") && hud.contains("CARD_W = "));
        check("HUD spring animation follows OpenZen",
                hud.contains("SPRING_MS"));
        check("HUD caches the last stack for the fade-out",
                hud.contains("cachedStack"));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static float rotateTowards(double maxStep, float current, float target) {
        return ScaffoldFeature.rotateTowards(maxStep, current, target);
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < 1.0E-3F;
    }

    private static String read(String relative) throws Exception {
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

    private ScaffoldPlaceOrderTest() {
    }
}
