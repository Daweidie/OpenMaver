import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;

import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Properties;

/**
 * SafeWalk（方块边缘潜行）——把 Scaffold 里的 {@code eagle} 拆成独立模块。
 *
 * <p>Scaffold（OpenZen 移植版）里这段逻辑只有一行：
 * {@code setKeyPressed(mc, "keyBindSneak", onGround && isOnBlockEdge(player, 0.3F))}，
 * 而且只在 {@code NORMAL} 模式下生效。本模块把它独立出来，任何场景都能用：
 *
 * <ol>
 *   <li><b>探针</b>：沿本 tick 的输入方向（vanilla {@code moveFlying} 的同一个
 *       换算式）把玩家碰撞箱中心前推 {@code distance} 格，在脚下 0.01 格处放一个
 *       半径 {@link #PROBE_RADIUS} 的小盒，用 {@code World.getCollidingBoundingBoxes}
 *       查支撑面。查不到就说明“再走一步就会踏空”。</li>
 *   <li><b>按键</b>：踏空时替玩家按住 {@code keyBindSneak.pressed}。这只改原版
 *       按键状态，本 tick 的 {@code updatePlayerMoveState()} 会把它读成
 *       {@code MovementInput.sneak}，随后原版自己发
 *       C0B 动作包（START/STOP_SNEAKING）。服务端看到的是一次
 *       普通的潜行输入：不写 motion、不改 rotation、不发伪造包。</li>
 *   <li><b>滞回</b>：松开后继续按住 {@code holdTicks} 个 tick，避免贴着边缘走时
 *       逐 tick 抖动开关 Shift（那在服务端就是一串 STOP/START 动作包）。</li>
 *   <li><b>让位</b>：Scaffold 的 {@code eagle} / {@code sneak}、Scaffold2 的按住 S
 *       搭路、GodBridge 的蹲起循环也写同一个 {@code keyBindSneak}。它们活跃时
 *       本模块完全不碰这个键，避免两边互相覆盖。</li>
 * </ol>
 *
 * <p><b>时序</b>：必须在 ClientTickEvent.START 阶段写按键，早于玩家自己的
 * {@code movementInput.updatePlayerMoveState()}；因此由
 * {@code OpenMaverFeatures.onTickStart} 在 Scaffold / GodBridge 之后调用
 * （放在后面才能看到它们本 tick 的接管状态）。
 *
 * <p>行为说明见 {@code design/SAFEWALK_HANDOFF.md}，回归测试见
 * {@code tools/safewalk-test/}。
 */
public final class SafeWalkFeature {

    // ==================================================================
    // 设置（ClickGUI / openmaver.properties 共用）
    // ==================================================================

    /** 探针沿输入方向前伸的距离（格）：越大越早开始潜行。 */
    private static double distance = 0.30D;
    /** 只在地面生效（空中没有“边缘”可言）。 */
    private static boolean onlyGround = true;
    /** 只有按着 WASD 才生效（站着不动时不接管 Shift）。 */
    private static boolean requireMoving = true;
    /** 玩家自己按着潜行键时不接管（原版潜行本来就防边缘）。 */
    private static boolean respectSneak = true;
    /** 松开边缘后继续保持潜行的 tick 数。 */
    private static int holdTicks = 2;

    /** 探针半径：0.12 宽的立方体，略大于一个方块坐标的小数误差。 */
    static final double PROBE_RADIUS = 0.06D;
    /** 探针相对脚底再往下一点点，保证站在方块顶面时能真撞上支撑面。 */
    static final double PROBE_DROP = 0.01D;
    /** distance 的上下限（格）。 */
    static final double DISTANCE_MIN = 0.05D;
    static final double DISTANCE_MAX = 0.60D;
    /** holdTicks 的上下限（tick）。 */
    static final int HOLD_MIN = 0;
    static final int HOLD_MAX = 5;

    // ==================================================================
    // 运行状态
    // ==================================================================

    /** 本 tick 是否按住了潜行。 */
    private static boolean active;
    /** {@code keyBindSneak.pressed} 是否是本模块写上去的（决定释放时要不要还原）。 */
    private static boolean owned;
    /** 反射出错后停用，避免每 tick 刷屏。 */
    private static boolean failed;
    /** 滞回剩余 tick。 */
    private static int holdLeft;
    /** 仅供调试/测试的 tick 计数。 */
    private static int tick;
    /** 状态串：OFF / IDLE / EDGE / HOLD / YIELD / ERROR。 */
    private static String status = "OFF";

    private SafeWalkFeature() {
    }

    // ==================================================================
    // 主 tick
    // ==================================================================

    /**
     * ClientTickEvent.START：vanilla {@code updatePlayerMoveState()} 之前决定本 tick
     * 要不要按住 Shift。
     *
     * <p>由 {@code OpenMaverFeatures.onTickStart} 调用，位置在
     * Scaffold / GodBridge / Scaffold2 之后，这样它们的接管状态已经确定。
     */
    public static void onTickStart(Minecraft mc) {
        tick++;
        Module module = ModuleManager.getModule("SafeWalk");
        Object player = read(mc, "thePlayer", "field_71439_g");
        Object world = read(mc, "theWorld", "field_71441_e");
        Object screen = read(mc, "currentScreen", "field_71462_r");

        if (module == null || !module.isEnabled()) {
            release(mc);
            status = "OFF";
            return;
        }
        if (failed || player == null || world == null || screen != null
                || !OpenMaverFeatures.alive(player) || unavailable(player)) {
            release(mc);
            status = "IDLE";
            return;
        }
        try {
            boolean yielded = yieldsToOther();
            boolean onGround = !onlyGround || OpenMaverFeatures.scaffoldOnGround(player);
            boolean moving = !requireMoving || isMoving(mc);
            boolean selfSneak = respectSneak && sneakKeyHeld(mc);
            boolean edge = !yielded && onGround && moving && onEdge(player, world);

            boolean sneak = decide(true, yielded, onGround, moving, selfSneak, edge, holdLeft);
            holdLeft = sneak ? (edge ? holdTicks : Math.max(0, holdLeft - 1)) : 0;
            active = sneak;
            status = yielded ? "YIELD" : edge ? "EDGE" : sneak ? "HOLD" : "IDLE";
            applySneak(mc, sneak, yielded);
        } catch (Throwable error) {
            failed = true;
            active = false;
            holdLeft = 0;
            release(mc);
            status = "ERROR";
            System.err.println("[OpenMaver] SafeWalk disabled after error: " + error);
        }
    }

    /**
     * 决策表（回归测试直接调它）。
     *
     * <p>不变量：<b>别的模块正在接管 Shift 时一律让位</b>；只有“站在地面上 +
     * 探针判定踏空”才主动按住，滞回期间保持按住但逐 tick 递减。
     *
     * @param enabled  模块开启且场景可用
     * @param yielded  Scaffold / GodBridge 正在写同一个键
     * @param onGround 可站立（关了 {@code onlyGround} 时恒为 true）
     * @param moving   有 WASD 输入（关了 {@code requireMoving} 时恒为 true）
     * @param selfSneak 玩家物理上按着潜行键
     * @param edge     探针判定“再走一步会踏空”
     * @param holdLeft 滞回剩余 tick
     * @return 本 tick 是否按住潜行
     */
    static boolean decide(boolean enabled, boolean yielded, boolean onGround, boolean moving,
            boolean selfSneak, boolean edge, int holdLeft) {
        if (!enabled || yielded || selfSneak || !onGround) {
            return false;
        }
        if (edge) {
            return true;
        }
        return moving && holdLeft > 0;
    }

    /**
     * Scaffold 的 Eagle / Sneak、Scaffold2 的按住 S 搭路、GodBridge 的蹲起循环
     * 正在接管 Shift 时让位。
     */
    static boolean yieldsToOther() {
        return ScaffoldFeature.ownsSneakKey()
                || Scaffold2Feature.ownsSneakKey()
                || GodBridgeFeature.isActive();
    }

    /** 沿输入方向的前伸量 → 探针位移（纯函数，测试直接调它）。 */
    static double[] probeOffset(float yaw, float forward, float strafe, double distance) {
        double[] direction = travelVector(yaw, forward, strafe);
        return new double[]{direction[0] * distance, direction[1] * distance};
    }

    /**
     * 与 1.8.9 {@code Entity.moveFlying} 相同的方向换算，只取水平单位向量。
     *
     * <pre>
     *   sin = sin(yaw), cos = cos(yaw)
     *   x = strafe * cos - forward * sin
     *   z = forward * cos + strafe * sin
     * </pre>
     *
     * <p>没有输入时返回 {@code {0, 0}}（调用方据此判定“没在走路”）。
     */
    static double[] travelVector(float yaw, float forward, float strafe) {
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double x = strafe * cos - forward * sin;
        double z = forward * cos + strafe * sin;
        double length = Math.hypot(x, z);
        if (length < 1.0E-4D) {
            return new double[]{0.0D, 0.0D};
        }
        return new double[]{x / length, z / length};
    }

    /**
     * 探针盒：碰撞箱中心 + 位移，整体留在脚底以下。
     *
     * <p>用中心点而不用整个碰撞箱，是因为原版潜行防边缘本身就是“脚下中心一列还有
     * 没有方块”的语义；整箱判定会让玩家在离边缘还有 0.3 格时永远不触发。
     *
     * <p>竖直方向整块压在 {@code box.minY - PROBE_DROP} 之下，所以它只会撞到
     * “脚下的支撑面”；贴着墙走时不会被脚下高度那一层的方块误判成“不掉”。
     */
    static AxisAlignedBB probeBox(AxisAlignedBB box, double dx, double dz) {
        double centerX = (box.minX + box.maxX) * 0.5D + dx;
        double centerZ = (box.minZ + box.maxZ) * 0.5D + dz;
        double top = box.minY - PROBE_DROP;
        double centerY = top - PROBE_RADIUS;
        return new AxisAlignedBB(
                centerX - PROBE_RADIUS, centerY - PROBE_RADIUS, centerZ - PROBE_RADIUS,
                centerX + PROBE_RADIUS, centerY + PROBE_RADIUS, centerZ + PROBE_RADIUS);
    }

    /** 探针位置的脚下有没有支撑面；查不到 = 再走一步就踏空。 */
    private static boolean onEdge(Object player, Object world) {
        AxisAlignedBB box = OpenMaverFeatures.boundingBox(player);
        if (box == null) {
            return false;
        }
        Object input = read(player, "movementInput", "field_71158_b");
        double forward = OpenMaverFeatures.number(input, "moveForward", "field_78900_b");
        double strafe = OpenMaverFeatures.number(input, "moveStrafe", "field_78902_a");
        if (forward == 0.0D && strafe == 0.0D) {
            return false;
        }
        float yaw = (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z");
        double[] offset = probeOffset(yaw, (float) forward, (float) strafe, distance);
        Object colliding = OpenMaverFeatures.call(world, "getCollidingBoundingBoxes",
                "func_72945_a", null, player, probeBox(box, offset[0], offset[1]));
        return colliding instanceof List && ((List<?>) colliding).isEmpty();
    }

    // ==================================================================
    // 按键接管
    // ==================================================================

    /** 写 {@code keyBindSneak.pressed}；让位时一根手指都不碰。 */
    private static void applySneak(Minecraft mc, boolean sneak, boolean yielded) {
        if (yielded) {
            // Scaffold / GodBridge 本 tick 已经写过这个键，这里不再覆盖。
            owned = false;
            return;
        }
        if (sneak) {
            setPressed(mc, true);
            owned = true;
            return;
        }
        if (owned) {
            restoreSneakToPhysical(mc);
            owned = false;
        }
    }

    /** 关闭模块 / 场景不可用 / 出错时把 Shift 还给玩家。 */
    private static void release(Minecraft mc) {
        boolean canRestore = owned && !yieldsToOther();
        owned = false;
        active = false;
        holdLeft = 0;
        if (canRestore) {
            restoreSneakToPhysical(mc);
        }
    }

    private static void setPressed(Minecraft mc, boolean pressed) {
        Object binding = sneakBinding(mc);
        if (binding != null) {
            setBoolean(binding, "pressed", "field_74513_e", pressed);
        }
    }

    /** 按键还给物理键盘（玩家真按着 Shift 时保持按下）。 */
    private static void restoreSneakToPhysical(Minecraft mc) {
        Object binding = sneakBinding(mc);
        if (binding == null) {
            return;
        }
        Object code = OpenMaverFeatures.call(binding, "getKeyCode", "func_151463_i", null);
        boolean physical = code instanceof Number
                && Keyboard.isKeyDown(((Number) code).intValue());
        setBoolean(binding, "pressed", "field_74513_e", physical);
    }

    private static Object sneakBinding(Minecraft mc) {
        Object settings = read(mc, "gameSettings", "field_71474_y");
        return read(settings, "keyBindSneak", "field_74311_E");
    }

    /** 物理上是否按着潜行键（不能读 {@code pressed}：那是本模块自己写的）。 */
    private static boolean sneakKeyHeld(Minecraft mc) {
        Object binding = sneakBinding(mc);
        if (binding == null) {
            return false;
        }
        Object code = OpenMaverFeatures.call(binding, "getKeyCode", "func_151463_i", null);
        return code instanceof Number && Keyboard.isKeyDown(((Number) code).intValue());
    }

    private static boolean isMoving(Minecraft mc) {
        return keyDown(mc, "keyBindForward") || keyDown(mc, "keyBindBack")
                || keyDown(mc, "keyBindLeft") || keyDown(mc, "keyBindRight");
    }

    private static boolean keyDown(Minecraft mc, String fieldName) {
        Object settings = read(mc, "gameSettings", "field_71474_y");
        Object binding = read(settings, fieldName, keyBindingSrg(fieldName));
        return Boolean.TRUE.equals(read(binding, "pressed", "field_74513_e"));
    }

    private static String keyBindingSrg(String fieldName) {
        if ("keyBindForward".equals(fieldName)) {
            return "field_74351_w";
        }
        if ("keyBindBack".equals(fieldName)) {
            return "field_74368_y";
        }
        if ("keyBindLeft".equals(fieldName)) {
            return "field_74370_x";
        }
        if ("keyBindRight".equals(fieldName)) {
            return "field_74366_z";
        }
        if ("keyBindSneak".equals(fieldName)) {
            return "field_74311_E";
        }
        return fieldName;
    }

    /** 这些状态下没有“边缘潜行”可谈。 */
    private static boolean unavailable(Object player) {
        Object abilities = read(player, "capabilities", "field_71075_bZ");
        return read(player, "ridingEntity", "field_70154_o") != null
                || truth(player, "isSpectator", "func_175149_v")
                || flag(abilities, "isFlying", "field_75100_b")
                || truth(player, "isInWater", "func_70090_H")
                || truth(player, "isInLava", "func_180799_ab")
                || flag(player, "isInWeb", "field_70134_J")
                || truth(player, "isOnLadder", "func_70617_f_")
                || truth(player, "isPlayerSleeping", "func_70608_bn");
    }

    // ==================================================================
    // 配置读写
    // ==================================================================

    public static void load(Properties values) {
        distance = clamp(readDouble(values, "safewalk.distance", 0.30D), DISTANCE_MIN, DISTANCE_MAX);
        onlyGround = readBoolean(values, "safewalk.onlyGround", true);
        requireMoving = readBoolean(values, "safewalk.requireMoving", true);
        respectSneak = readBoolean(values, "safewalk.respectSneak", true);
        holdTicks = clamp(readInt(values, "safewalk.holdTicks", 2), HOLD_MIN, HOLD_MAX);
        reset();
    }

    public static String save() {
        return "safewalk.distance=" + round(distance)
                + "\nsafewalk.onlyGround=" + onlyGround
                + "\nsafewalk.requireMoving=" + requireMoving
                + "\nsafewalk.respectSneak=" + respectSneak
                + "\nsafewalk.holdTicks=" + holdTicks;
    }

    /** 配置重新加载 / 关闭流程：把 Shift 还给玩家并清空状态。 */
    public static void reset() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            release(mc);
        }
        owned = false;
        active = false;
        failed = false;
        holdLeft = 0;
        status = "IDLE";
    }

    static String round(double value) {
        return String.valueOf(Math.round(value * 100.0D) / 100.0D);
    }

    private static double readDouble(Properties values, String key, double fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int readInt(Properties values, String key, int fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(Properties values, String key, boolean fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(raw.trim());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================================================================
    // ClickGUI / HUD 绑定
    // ==================================================================

    public static double getDistance() {
        return distance;
    }

    public static void setDistance(double value) {
        distance = clamp(value, DISTANCE_MIN, DISTANCE_MAX);
        OpenMaverFeatures.markDirty();
    }

    public static boolean isOnlyGround() {
        return onlyGround;
    }

    public static void setOnlyGround(boolean value) {
        onlyGround = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isRequireMoving() {
        return requireMoving;
    }

    public static void setRequireMoving(boolean value) {
        requireMoving = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isRespectSneak() {
        return respectSneak;
    }

    public static void setRespectSneak(boolean value) {
        respectSneak = value;
        OpenMaverFeatures.markDirty();
    }

    public static int getHoldTicks() {
        return holdTicks;
    }

    public static void setHoldTicks(int value) {
        holdTicks = clamp(value, HOLD_MIN, HOLD_MAX);
        OpenMaverFeatures.markDirty();
    }

    /** 本 tick 是否正在按住 Shift。 */
    public static boolean isActive() {
        return active;
    }

    public static String getStatus() {
        return status;
    }

    /** HUD 右侧的参数摘要：踏空时显示 EDGE，否则显示探针距离。 */
    public static String getSummary() {
        return active ? "EDGE" : round(distance) + "b";
    }

    /** 调试用：本类被驱动的 tick 数。 */
    public static int getTickCount() {
        return tick;
    }

    // ==================================================================
    // 反射小工具（与 ScaffoldFeature / GodBridgeFeature 同一套写法）
    // ==================================================================

    private static Object read(Object target, String mcp, String srg) {
        return OpenMaverFeatures.read(target, mcp, srg, null);
    }

    private static boolean truth(Object target, String mcp, String srg) {
        return Boolean.TRUE.equals(OpenMaverFeatures.call(target, mcp, srg, null));
    }

    private static boolean flag(Object target, String mcp, String srg) {
        return Boolean.TRUE.equals(read(target, mcp, srg));
    }

    private static void setBoolean(Object target, String mcp, String srg, boolean value) {
        if (target == null) {
            return;
        }
        Field field = OpenMaverFeatures.findField(target,
                OpenMaverFeatures.runtimeCandidates(mcp, srg));
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            field.setBoolean(target, value);
        } catch (Throwable ignored) {
            // 映射差异时忽略：按键接管失败不能拖垮 tick。
        }
    }
}
