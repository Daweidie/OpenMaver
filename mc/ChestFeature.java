import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import io.netty.channel.ChannelPipeline;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 箱子（存储方块）相关功能的 MC 侧实现：
 *
 * <ol>
 *   <li><b>Chest ESP</b>（{@code render.chestesp}）—— 穿墙线框标出附近的箱子 / 末影箱 /
 *       漏斗 / 机器，按距离和颜色设置绘制。</li>
 *   <li><b>Chest Stealer</b>（{@code player.cheststealer}）—— 对着箱子按拾取键（或准星停留），
 *       客户端替玩家发<b>一次</b>右键开箱包，服务端照常把容器开起来；界面由原版正常挂上
 *       （容器 + {@code windowId} 都是原版写好的），随后通过
 *       {@code PlayerControllerMP.windowClick()} 的 shift-click（mode 1）把箱内物资
 *       整摞搬进背包，搬完再自动关箱退出。支持「忽略垃圾 / 只拿贵重」过滤、开箱延迟、
 *       点击节奏；另外提供一键整理（合并同物品 + 按名称排序）与一键存入（反向 shift-click）。</li>
 * </ol>
 *
 * <p><b>两种开箱方式</b>：
 * <ul>
 *   <li><b>可见（默认，{@code chest.stealer.hideGui=false}）</b>：右键开箱后原版
 *       {@code GuiChest} <b>照常留在屏幕上</b>，我们在这个真界面开着的时候点格子
 *       （等于玩家自己一边看着箱子一边 shift-click），拿完再发 {@code C0D} 关窗 + 关界面。
 *       这是目前的主路径：服务端和反作弊看到的都是「玩家打开了箱子、在界面里拿了东西、
 *       然后关掉界面」，客户端与服务端的容器状态始终一致。</li>
 *   <li><b>静默（{@code chest.stealer.hideGui=true}）</b>：容器挂好之后在<b>同一个 client
 *       tick 内</b>用 {@code Minecraft.displayGuiScreen(null)} 把界面收起来，屏幕上看不到
 *       箱子 GUI，但仍然 shift-click 搬运。这条路径不发关窗包也能点，因此很容易被
 *       Grim 那类「click window」预测检查盯上 —— 只在需要完全隐藏界面时才开。</li>
 * </ul>
 *
 * <p><b>为什么必须先让原版把界面挂上</b>：1.8.9 的
 * {@code NetHandlerPlayClient.handleOpenWindow} 先让 {@code GuiChest} 构造出
 * {@code ContainerChest}，再由 {@code Minecraft.displayGuiScreen} → {@code GuiContainer.initGui}
 * 把容器挂到 {@code player.openContainer}，然后才把服务端的 {@code windowId} 写进这个容器。
 * 所以这里<b>不取消 GuiOpenEvent</b>：让原版把整套容器流程跑完（{@code windowId} 才是对的，
 * S30 槽位内容才会落到我们看得见的容器里）。
 *
 * <p>以前的做法是在事件里 {@code setCanceled(true)} 再自己调 {@code setWorldAndResolution}
 * 抢容器：拿到的是 {@code GuiChest} 自己 new 出来的容器，{@code windowId} 停在 0（背包窗口），
 * 于是服务端发的 S30 窗口内容被整包丢掉、C0E 点击服务端也不认 —— 表现就是
 * 「箱子开了，界面也没弹，但一件都搬不走」。
 *
 * <p><b>一件一件拿 + 人类化节拍</b>（{@code chest.stealer.oneByOne} / {@code humanize}，
 * 默认都开）：每批只 shift-click <b>一格</b>箱子，下一格按<b>随机毫秒</b>节拍
 * （{@code clickDelayMinMs}~{@code clickDelayMaxMs}，默认 100~160ms，硬下限 80ms）发出，
 * 每连续 4 格才有小概率插一段停顿，首格前有随机反应时间，关箱也随机延迟。
 * 客户端掉帧时按「落后了几个节拍」补点，一个 tick 最多 2 格，整箱耗时不再被 tick 粒度拖长。
 *
 * <p><b>包序：所有 windowClick 必须排在本 tick 的 C03 之前</b>。Grim 2.0 的
 * {@code Post} 检查在收到 C03（flying）时清空 {@code post} 队列，之后任何 action 包
 * （含 {@code C0EPacketClickWindow}）都留在队列里，直到下一个 C03；若中间来了一个合法的
 * transaction ack（{@code C0F}，原版在 tick 开头的 scheduled-task 阶段发出），就打出
 * {@code failed Post (vl:x.x): click window v1.8}。本类因此在 tick START 被调用
 * （见 {@link #onTick}），包序与玩家在 {@code GuiContainer} 里点鼠标完全一致：
 * {@code C0F → C0E → C03}。以前挂在 tick END 时每个点击包都跨 tick 边界，
 * 必然被 ack 夹住 —— 本机日志里每条 {@code moved 1 stack} 后面紧跟一条 Post flag
 * 就是这么来的。
 *
 * <p>可见模式下还要先等 {@link ChestPacketHook} 看到服务端的窗口物品包（S30）；
 * 包钩子没看到但本地容器槽位里已经有箱子侧物品时直接放行（内容显然已经同步了），
 * 兜底也只等 4 tick —— 人也是看到箱子内容才点格子的，但不会盯着空箱子干等半秒。
 *
 * <p><b>所有 MC 成员都用反射按 MCP / SRG 双名访问</b>：本类在运行期注入时由
 * LaunchWrapper 直接加载，编译期的 {@code mcstubs/} 只用来通过 javac，不参与运行；
 * 反射路径与 {@code OpenMaverFeatures} 完全一致，因此不需要额外补 reobf 映射。
 */
public final class ChestFeature {

    private ChestFeature() {
    }

    // ==================================================================
    // 模块开关（core 侧模块只存状态，实际工作都在这里）
    // ==================================================================

    private static boolean moduleOn(String name) {
        Module module = ModuleManager.getModule(name);
        return module != null && module.isEnabled();
    }

    public static boolean isEspEnabled() {
        return moduleOn("Chest ESP");
    }

    public static boolean isStealerEnabled() {
        return moduleOn("Chest Stealer");
    }

    // ==================================================================
    // 设置（持久化到 config/openmaver-chest.properties）
    // ==================================================================

    private static final String DEFAULT_SETTINGS_PATH = "config/openmaver-chest.properties";
    private static final int SETTINGS_VERSION = 1;

    private static int settingsCheckTicks;
    private static long settingsModified = Long.MIN_VALUE;
    private static boolean settingsDirty;

    /** Chest ESP：最大绘制距离（方块）。 */
    private static float espRange = 48.0F;
    /** Chest ESP：线宽。 */
    private static float espThickness = 1.5F;
    /** Chest ESP：线框透明度。 */
    private static float espOpacity = 0.85F;
    /** Chest ESP：颜色索引（见 {@link #ESP_COLORS}）。 */
    private static int espColor = 0;
    private static boolean espFullColor;
    private static Object espWorld;
    private static final java.util.Set<String> openedChests = new java.util.HashSet<String>();
    /** Chest ESP：是否同时标记漏斗 / 熔炉 / 发射器等机器容器。 */
    private static boolean espStorageBlocks = true;

    /**
     * 拾取过滤：0 = 全部，1 = 忽略垃圾，2 = 只拿贵重。
     *
     * <p>默认 {@code 0}：玩家右键箱子的预期就是「从上到下全拿进背包」。
     * 选 {@code 1} / {@code 2} 时箱子里可能一件都不匹配，看起来就像「搬运没生效」，
     * 所以过滤掉物品时会打一条明确日志（见 {@link #warnFilterRejected(int)}）。
     */
    private static int takeFilter = 0;
    /** 存入过滤：0 = 全部，1 = 只存垃圾，2 = 只存贵重。 */
    private static int storeFilter = 1;
    /**
     * 一批里最多发几次 windowClick（只在 {@link #oneByOne} 关掉时生效）。
     */
    private static int clicksPerTick = 3;
    /**
     * 一件一件拿：每批只 shift-click 一格箱子，并且等到服务端的窗口确认（S32）
     * 回来才拿下一格。
     *
     * <p>默认开。表现就是人手动开箱时的样子 —— 拿走一摞、确认进背包、再拿下一摞，
     * 而不是一瞬间把整箱东西全塞进背包。关掉才会按 {@link #clicksPerTick} 一 tick 连点。
     */
    private static boolean oneByOne = true;
    /**
     * 两批 windowClick 之间至少隔多少个 tick（0 = 不限速）。
     *
     * <p>可见模式下这是「人的手速」：默认 1 tick。一件一件拿时，服务端的 S32 确认
     * 先到就直接拿下一格（确认本身就是节奏），确认慢了才由这个间隔兜底。
     */
    private static int clickGapTicks = 1;

    // ------------------------------------------------------------------
    // 人类化节拍（反「click window」/ Grim Post 检测）
    // ------------------------------------------------------------------
    /**
     * 人类化节拍总开关。
     *
     * <p>关掉就是旧的机械节奏：{@code clickGapTicks} 个 tick 一格，间隔恒等于
     * 50ms 的整数倍。本机日志里 6 格正好 250ms（每格 50ms 等距）、紧接着
     * {@code [GrimAC] failed Post (vl:0.0): click window v1.8}，就是这种节奏打出来的。
     *
     * <p>打开后每格间隔是随机毫秒（默认 180~380ms），偶尔插一次长停顿，
     * 首格还有额外反应时间，关箱 / 下一个箱子同样随机 —— 和真人开箱的时长分布一致。
     */
    private static boolean humanize = true;
    /**
     * 两格之间的随机间隔下限 / 上限（毫秒）。
     *
     * <p>默认 100~160ms（≈7 CPS 的 shift-click）。这个区间刻意高于「每格 40~50ms」
     * 那种机械/爆发节奏：本机日志里 {@code clickDelayMinMs=clickDelayMaxMs=40} 时
     * 会打出「一串 40ms 连点 + 一段几百毫秒空档」，密度全堆在 burst 头部，
     * 既是最容易被抓的形态，也因为 {@code postGuard} 与 tick 粒度把总时长拖成好几秒。
     */
    private static int clickDelayMinMs = 100;
    private static int clickDelayMaxMs = 160;
    /** 本会话第一格之前的额外反应时间（毫秒，按 tick 取整）。 */
    private static int firstClickDelayMs = 80;
    /** 连续几格之后触发一次「手停一下」的概率（百分比）。 */
    private static int pauseChancePct = 6;
    /** 「手停一下」的随机时长下限 / 上限（毫秒）。 */
    private static int pauseMinMs = 200;
    private static int pauseMaxMs = 380;
    /** 真人偶尔漏拿一格的概率（百分比，0 = 每格都拿）。 */
    private static int skipChancePct = 0;
    /** 搬完后等多少 tick 再关箱（随机区间）。 */
    private static int closeDelayMinTicks = 2;
    private static int closeDelayMaxTicks = 5;

    /** 下一次允许点击的时间点（nanoTime）。 */
    private static long nextClickNanos;
    /** 下一次点击距上一次点击的间隔（纳秒）：tick 掉帧时用它算「落后了几格」。 */
    private static long clickIntervalNanos = 100_000_000L;
    /** 本 tick 还剩几次 windowClick 可以用（tick 开始时按节拍算好）。 */
    private static int clickBudgetThisTick;
    /** 距离上一次长停顿已经连续点了几格。 */
    private static int clickStreak;
    /** 最近一次排程的间隔（毫秒），只用于日志核对节拍。 */
    private static int lastScheduledDelayMs;
    /** 本会话的开箱反应额外 tick（由 {@link #firstClickDelayMs} 折算，带抖动）。 */
    private static int sessionReactionTicks;
    /** 本会话关箱前要等的 tick 数。 */
    private static int sessionCloseTicks = 3;
    /** 本次会话第一格真正点出去的时间（纳秒），0 = 还没点过。 */
    private static long sessionFirstClickNanos;
    /** 本次会话实际发出的 windowClick 次数。 */
    private static int sessionClicks;
    /** 本次会话是否走了「本地容器已有内容」的捷径（包钩子晚到 / S30 没看到）。 */
    private static boolean sessionContentShortcut;
    /** 上一次主动关窗的时间（nanoTime）：关窗后一小段时间内不再开新箱子。 */
    private static long lastCloseNanos;
    /**
     * 客户端当前挂着的窗口号（主线程每 tick 更新，Netty 线程只读）。
     *
     * <p>1.8.9 的 {@code NetHandlerPlayClient.handleCloseWindow} 是<b>无条件</b>
     * {@code closeScreen()}：只要收到一个 S2E，不管窗口号对不对都把当前界面关掉。
     * 快速连开箱子时，服务端对旧窗口补发的 S2E 经常会晚到一两个 tick，
     * 于是刚打开的 GuiChest 被一个过期关窗包带走 —— 表现就是「点开箱子界面一闪就没了」。
     * 入站钩子拿这个字段判断 S2E 是否属于过期窗口，过期的不往下传。
     */
    static volatile int clientWindowId;

    /** 容器内容同步完成后等多少 tick 再动手（等服务端把 S30 窗口内容发完）。 */
    private static int openDelayTicks = 1;
    private static boolean autoLoot = true;
    private static boolean autoSort;
    /**
     * 搬完是否自动关箱退出。
     *
     * <p>默认 {@code true}：自动扫箱是一个接一个跑箱子的，界面不关就没法继续。
     * 玩家自己开的箱子也按这个开关处理（拿完后自动退出界面）。
     */
    private static boolean autoClose = true;
    /** 看着箱子按拾取键时，是否自动发右键开箱包。 */
    private static boolean autoOpen = true;
    /** 自动扫箱：准星在箱子上停一小会儿就自动开箱并搬空，不用按键。 */
    private static boolean autoSweep = true;
    /**
     * 自动扫箱的冷却（毫秒）：搬完一个箱子到对下一个箱子动手之间至少隔这么久。
     *
     * <p>以前写死 1.2 秒，一个接一个跑箱子时大半时间都在干等。默认 500ms，
     * 既避免对着刚搬空的同一个箱子反复开，又不会拖慢连续扫箱。
     */
    private static int sweepCooldownMs = 450;
    /**
     * 开箱后是否把原版箱子界面收起来（静默开箱）。
     *
     * <p>默认 {@code false}：界面照常留在屏幕上，搬运就在这个真界面里完成，
     * 拿完发 {@code C0D} 关窗退出。静默开箱（{@code true}）虽然看不见界面，
     * 但「没有界面却在点窗口」正是 Grim 类反作弊最容易盯上的形态。
     */
    private static boolean hideGui;

    /** 快捷操作键位（可在配置文件里改）。 */
    private static String lootKey = "L";
    private static String sortKey = "G";
    private static String storeKey = "O";

    private static void refreshSettings() {
        if (settingsCheckTicks++ < 20) {
            return;
        }
        settingsCheckTicks = 0;
        if (settingsDirty) {
            settingsDirty = false;
            saveSettings();
            return;
        }
        File file = new File(settingsPath());
        long modified = file.isFile() ? file.lastModified() : -1L;
        if (modified == settingsModified) {
            return;
        }
        settingsModified = modified;
        Properties values = new Properties();
        if (file.isFile()) {
            try (FileInputStream input = new FileInputStream(file)) {
                values.load(input);
            } catch (Exception error) {
                System.err.println("[OpenMaver] Cannot read chest settings: " + error);
                return;
            }
        }
        espRange = clamp(readFloat(values, "chest.esp.range", 48.0F), 8.0F, 128.0F);
        espThickness = clamp(readFloat(values, "chest.esp.thickness", 1.5F), 0.5F, 4.0F);
        espOpacity = clamp(readFloat(values, "chest.esp.opacity", 0.85F), 0.15F, 1.0F);
        espColor = clamp(readInt(values, "chest.esp.color", 0), 0, ESP_COLORS.length - 1);
        espFullColor = readBoolean(values, "chest.esp.fullColor", false);
        espStorageBlocks = readBoolean(values, "chest.esp.storageBlocks", true);
        takeFilter = clamp(readInt(values, "chest.stealer.takeFilter", 0), 0, 2);
        storeFilter = clamp(readInt(values, "chest.stealer.storeFilter", 1), 0, 2);
        clicksPerTick = clamp(readInt(values, "chest.stealer.clicksPerTick", 3), 1, 9);
        oneByOne = readBoolean(values, "chest.stealer.oneByOne", true);
        clickGapTicks = clamp(readInt(values, "chest.stealer.clickGapTicks", 1), 0, 20);
        humanize = readBoolean(values, "chest.stealer.humanize", true);
        clickDelayMinMs = clampInt(readInt(values, "chest.stealer.clickDelayMinMs", 100),
                MIN_CLICK_INTERVAL_MS, 2000);
        clickDelayMaxMs = clampInt(readInt(values, "chest.stealer.clickDelayMaxMs", 160),
                MIN_CLICK_INTERVAL_MS, 3000);
        if (clickDelayMaxMs < clickDelayMinMs) {
            int swap = clickDelayMinMs;
            clickDelayMinMs = clickDelayMaxMs;
            clickDelayMaxMs = swap;
        }
        firstClickDelayMs = clamp(readInt(values, "chest.stealer.firstClickDelayMs", 80), 0, 3000);
        pauseChancePct = clamp(readInt(values, "chest.stealer.pauseChancePct", 6), 0, 100);
        pauseMinMs = clamp(readInt(values, "chest.stealer.pauseMinMs", 200), 0, 5000);
        pauseMaxMs = clamp(readInt(values, "chest.stealer.pauseMaxMs", 380), 0, 8000);
        if (pauseMaxMs < pauseMinMs) {
            int swap = pauseMinMs;
            pauseMinMs = pauseMaxMs;
            pauseMaxMs = swap;
        }
        skipChancePct = clamp(readInt(values, "chest.stealer.skipChancePct", 0), 0, 50);
        closeDelayMinTicks = clamp(readInt(values, "chest.stealer.closeDelayMinTicks", 2), 1, 20);
        closeDelayMaxTicks = clamp(readInt(values, "chest.stealer.closeDelayMaxTicks", 5), 1, 40);
        if (closeDelayMaxTicks < closeDelayMinTicks) {
            int swap = closeDelayMinTicks;
            closeDelayMinTicks = closeDelayMaxTicks;
            closeDelayMaxTicks = swap;
        }
        sweepCooldownMs = clamp(readInt(values, "chest.stealer.sweepCooldownMs", 450), 100, 5000);
        openDelayTicks = clamp(readInt(values, "chest.stealer.openDelayTicks", 1), 0, 20);
        autoLoot = readBoolean(values, "chest.stealer.autoLoot", true);
        autoSort = readBoolean(values, "chest.stealer.autoSort", false);
        autoClose = readBoolean(values, "chest.stealer.autoClose", true);
        autoOpen = readBoolean(values, "chest.stealer.autoOpen", true);
        autoSweep = readBoolean(values, "chest.stealer.autoSweep", true);
        hideGui = readBoolean(values, "chest.stealer.hideGui", false);
        lootKey = readKey(values, "chest.key.loot", "L");
        sortKey = readKey(values, "chest.key.sort", "G");
        storeKey = readKey(values, "chest.key.store", "O");
    }

    private static void saveSettings() {
        File file = new File(settingsPath());
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(("chest.version=" + SETTINGS_VERSION
                    + "\nchest.esp.range=" + espRange
                    + "\nchest.esp.thickness=" + espThickness
                    + "\nchest.esp.opacity=" + espOpacity
                    + "\nchest.esp.color=" + espColor
                    + "\nchest.esp.fullColor=" + espFullColor
                    + "\nchest.esp.storageBlocks=" + espStorageBlocks
                    + "\nchest.stealer.takeFilter=" + takeFilter
                    + "\nchest.stealer.storeFilter=" + storeFilter
                    + "\nchest.stealer.clicksPerTick=" + clicksPerTick
                    + "\nchest.stealer.oneByOne=" + oneByOne
                    + "\nchest.stealer.clickGapTicks=" + clickGapTicks
                    + "\nchest.stealer.humanize=" + humanize
                    + "\nchest.stealer.clickDelayMinMs=" + clickDelayMinMs
                    + "\nchest.stealer.clickDelayMaxMs=" + clickDelayMaxMs
                    + "\nchest.stealer.firstClickDelayMs=" + firstClickDelayMs
                    + "\nchest.stealer.pauseChancePct=" + pauseChancePct
                    + "\nchest.stealer.pauseMinMs=" + pauseMinMs
                    + "\nchest.stealer.pauseMaxMs=" + pauseMaxMs
                    + "\nchest.stealer.skipChancePct=" + skipChancePct
                    + "\nchest.stealer.closeDelayMinTicks=" + closeDelayMinTicks
                    + "\nchest.stealer.closeDelayMaxTicks=" + closeDelayMaxTicks
                    + "\nchest.stealer.sweepCooldownMs=" + sweepCooldownMs
                    + "\nchest.stealer.openDelayTicks=" + openDelayTicks
                    + "\nchest.stealer.autoLoot=" + autoLoot
                    + "\nchest.stealer.autoSort=" + autoSort
                    + "\nchest.stealer.autoClose=" + autoClose
                    + "\nchest.stealer.autoOpen=" + autoOpen
                    + "\nchest.stealer.autoSweep=" + autoSweep
                    + "\nchest.stealer.hideGui=" + hideGui
                    + "\nchest.key.loot=" + lootKey
                    + "\nchest.key.sort=" + sortKey
                    + "\nchest.key.store=" + storeKey
                    + "\n").getBytes("UTF-8"));
        } catch (Exception error) {
            System.err.println("[OpenMaver] Cannot save chest settings: " + error);
            return;
        }
        settingsDirty = false;
        settingsModified = file.lastModified();
    }

    private static String settingsPath() {
        return System.getProperty("openmaver.chest", DEFAULT_SETTINGS_PATH);
    }

    public static void markDirty() {
        settingsDirty = true;
    }

    // ---- ClickGUI 用的读写接口 ----

    public static float getEspRange() {
        return espRange;
    }

    public static void setEspRange(float value) {
        espRange = clamp(value, 8.0F, 128.0F);
        markDirty();
    }

    public static float getEspThickness() {
        return espThickness;
    }

    public static void setEspThickness(float value) {
        espThickness = clamp(value, 0.5F, 4.0F);
        markDirty();
    }

    public static float getEspOpacity() {
        return espOpacity;
    }

    public static void setEspOpacity(float value) {
        espOpacity = clamp(value, 0.15F, 1.0F);
        markDirty();
    }

    public static int getEspColor() {
        return espColor;
    }

    public static boolean isEspFullColor() { return espFullColor; }
    public static void setEspFullColor(boolean value) {
        espFullColor = value;
        markDirty();
    }

    public static void setEspColor(int value) {
        espColor = clamp(value, 0, ESP_COLORS.length - 1);
        markDirty();
    }

    public static boolean isEspStorageBlocks() {
        return espStorageBlocks;
    }

    public static void setEspStorageBlocks(boolean value) {
        espStorageBlocks = value;
        markDirty();
    }

    public static int getTakeFilter() {
        return takeFilter;
    }

    public static void setTakeFilter(int value) {
        takeFilter = clamp(value, 0, 2);
        markDirty();
    }

    public static int getStoreFilter() {
        return storeFilter;
    }

    public static void setStoreFilter(int value) {
        storeFilter = clamp(value, 0, 2);
        markDirty();
    }

    public static int getClicksPerTick() {
        return clicksPerTick;
    }

    public static void setClicksPerTick(int value) {
        clicksPerTick = clamp(value, 1, 9);
        markDirty();
    }

    public static boolean isOneByOne() {
        return oneByOne;
    }

    public static int getSweepCooldownMs() {
        return sweepCooldownMs;
    }

    public static void setSweepCooldownMs(int value) {
        sweepCooldownMs = clamp(value, 100, 5000);
        markDirty();
    }

    public static void setOneByOne(boolean value) {
        oneByOne = value;
        markDirty();
    }

    public static int getClickGapTicks() {
        return clickGapTicks;
    }

    public static void setClickGapTicks(int value) {
        clickGapTicks = clamp(value, 0, 20);
        markDirty();
    }

    public static boolean isHideGui() {
        return hideGui;
    }

    public static void setHideGui(boolean value) {
        hideGui = value;
        markDirty();
    }

    public static boolean isHumanize() {
        return humanize;
    }

    public static void setHumanize(boolean value) {
        humanize = value;
        markDirty();
    }

    public static int getClickDelayMinMs() {
        return clickDelayMinMs;
    }

    public static void setClickDelayMinMs(int value) {
        clickDelayMinMs = clampInt(value, MIN_CLICK_INTERVAL_MS, 2000);
        if (clickDelayMaxMs < clickDelayMinMs) {
            clickDelayMaxMs = clickDelayMinMs;
        }
        markDirty();
    }

    public static int getClickDelayMaxMs() {
        return clickDelayMaxMs;
    }

    public static void setClickDelayMaxMs(int value) {
        clickDelayMaxMs = clampInt(value, MIN_CLICK_INTERVAL_MS, 3000);
        if (clickDelayMaxMs < clickDelayMinMs) {
            clickDelayMinMs = clickDelayMaxMs;
        }
        markDirty();
    }

    public static int getFirstClickDelayMs() {
        return firstClickDelayMs;
    }

    public static void setFirstClickDelayMs(int value) {
        firstClickDelayMs = clamp(value, 0, 3000);
        markDirty();
    }

    public static int getPauseChancePct() {
        return pauseChancePct;
    }

    public static void setPauseChancePct(int value) {
        pauseChancePct = clamp(value, 0, 100);
        markDirty();
    }

    public static int getSkipChancePct() {
        return skipChancePct;
    }

    public static void setSkipChancePct(int value) {
        skipChancePct = clamp(value, 0, 50);
        markDirty();
    }

    public static int getCloseDelayMinTicks() {
        return closeDelayMinTicks;
    }

    public static void setCloseDelayMinTicks(int value) {
        closeDelayMinTicks = clamp(value, 1, 20);
        if (closeDelayMaxTicks < closeDelayMinTicks) {
            closeDelayMaxTicks = closeDelayMinTicks;
        }
        markDirty();
    }

    public static int getCloseDelayMaxTicks() {
        return closeDelayMaxTicks;
    }

    public static void setCloseDelayMaxTicks(int value) {
        closeDelayMaxTicks = clamp(value, 1, 40);
        if (closeDelayMaxTicks < closeDelayMinTicks) {
            closeDelayMinTicks = closeDelayMaxTicks;
        }
        markDirty();
    }

    public static int getOpenDelayTicks() {
        return openDelayTicks;
    }

    public static void setOpenDelayTicks(int value) {
        openDelayTicks = clamp(value, 0, 20);
        markDirty();
    }

    public static boolean isAutoLoot() {
        return autoLoot;
    }

    public static void setAutoLoot(boolean value) {
        autoLoot = value;
        markDirty();
    }

    public static boolean isAutoSort() {
        return autoSort;
    }

    public static void setAutoSort(boolean value) {
        autoSort = value;
        markDirty();
    }

    public static boolean isAutoClose() {
        return autoClose;
    }

    public static void setAutoClose(boolean value) {
        autoClose = value;
        markDirty();
    }

    public static boolean isAutoOpen() {
        return autoOpen;
    }

    public static void setAutoOpen(boolean value) {
        autoOpen = value;
        markDirty();
    }

    public static boolean isAutoSweep() {
        return autoSweep;
    }

    public static void setAutoSweep(boolean value) {
        autoSweep = value;
        markDirty();
    }

    public static String getLootKey() {
        return lootKey;
    }

    public static String getSortKey() {
        return sortKey;
    }

    public static String getStoreKey() {
        return storeKey;
    }

    /** 拾取过滤的短名（HUD / 功能栏摘要用）。 */
    public static String getTakeFilterName() {
        return TAKE_FILTER_NAMES[clamp(takeFilter, 0, 2)];
    }

    /** 存入过滤的短名。 */
    public static String getStoreFilterName() {
        return STORE_FILTER_NAMES[clamp(storeFilter, 0, 2)];
    }

    // ==================================================================
    // 常量
    // ==================================================================

    private static final String[] TAKE_FILTER_NAMES = {"ALL", "NO JUNK", "VALUABLE"};
    private static final String[] STORE_FILTER_NAMES = {"ALL", "JUNK", "VALUABLE"};

    private static final int KIND_CHEST = 0;
    private static final int KIND_ENDER = 1;
    private static final int KIND_HOPPER = 2;
    private static final int KIND_MACHINE = 3;

    /** 开箱请求发出后多久之内到达的箱子界面才算「我们开的」。 */
    private static final long ARM_WINDOW_NANOS = 2_000_000_000L;
    /** 两次发开箱包之间的最小间隔。 */
    private static final long OPEN_SPACING_NANOS = 500_000_000L;
    /** 按了键却一直开不了箱（领地保护 / 距离太远）时的放弃时间。 */
    private static final long OPEN_TIMEOUT_NANOS = 1_600_000_000L;
    /**
     * 主动关窗之后的沉降时间：这段时间内不自动开新箱子。
     *
     * <p>服务端处理 C0D 有延迟，紧接着发右键包会让「旧窗口关窗包」和「新窗口开窗包」
     * 交错到达，1.8 客户端会被过期 S2E 关掉新界面。
     */
    private static final long CLOSE_SETTLE_NANOS = 450_000_000L;
    /** 自动扫箱：准星要在同一个箱子上停留多少 tick 才动手（防扫过误开）。 */
    private static final int SWEEP_DWELL_TICKS = 4;
    /** 自动扫箱：两次自动开箱之间的冷却（配置没写时的默认值）。 */
    private static final long SWEEP_COOLDOWN_NANOS = 500_000_000L;
    /**
     * 刚被搬空的箱子坐标在多长时间内不再自动扫箱。
     *
     * <p>本机日志里搬完关箱 0.66s 就又把同一个箱子开了一次
     * （{@code auto sweep at 194,64,203} → 窗口里 0 件物品 → 再关），
     * 白花一次开箱往返，还会让服务端的开箱统计多记一次空箱。
     */
    private static final long SWEEP_REVISIT_NANOS = 1_500_000_000L;
    /**
     * 同一个 client tick 里最多允许发出几个 {@code windowClick} 包。
     *
     * <p>正常 20 TPS 时按节拍一格一格发，永远用不到这个上限；只有在客户端掉帧
     * （实测日志里出现过 200ms/tick）时才会一次补两格，免得整箱耗时被 tick 粒度拖长。
     */
    private static final int MAX_CLICKS_PER_TICK = 2;
    /**
     * 两格之间的硬下限（毫秒）。
     *
     * <p>本机日志里把 {@code CLICK MS / CLICK MS+} 都设成 40 之后，日志变成了
     * 「40ms 连点 5 格 + 500~1100ms 空档」的爆发形态 —— 密度全挤在 burst 头部，
     * 既是最典型的自动搬运指纹，也让服务端的「开箱耗时 / 间隔」统计很难看。
     * 配置里低于这个值的会被夹上来。
     */
    private static final int MIN_CLICK_INTERVAL_MS = 80;
    /**
     * 一次都没搬动时，先连续尝试多少 tick 再判定「箱子是空的」。
     *
     * <p>服务端把箱子内容放在 S30 窗口物品包里，包可能比 S2D 开窗包晚到几个 tick；
     * 以前只搬一次就放弃（{@code loot: nothing matched ... silent close after moving 0}），
     * 内容晚到时就是「箱子开了、一件都没拿」。6 tick ≈ 300ms 足够覆盖正常延迟。
     */
    private static final int SYNC_EMPTY_PASSES = 6;
    /**
     * 包钩子不可用（或没看到 S30）时，最多等多少 tick 就按「内容已经到齐」放行。
     *
     * <p>正常情况靠 {@link ChestPacketHook} 看到窗口物品包再动手，或者本地容器里
     * 已经有箱子侧物品（说明内容早就落到容器里了）就直接放行；这条兜底只是防止
     * 某个客户端改动让 S30 识别不到时整个搬运卡死。以前是 12 tick（≈600ms）——
     * 包钩子没挂上时「打开箱子一秒才动手」就是它。
     */
    private static final int WINDOW_SYNC_FALLBACK_TICKS = 4;
    /**
     * S30 窗口物品包允许比「会话开始」早到多少（纳秒）。
     *
     * <p>1.8 服务端把 S2D 与 S30 放在同一次 flush 里，Netty 线程常常先处理完 S30、
     * 主线程下一 tick 才跑到 {@code onGuiOpen} 建会话。以前严格要求
     * {@code netItemsNanos >= sessionStartNanos}，这种正常的同批到达会被判成
     * 「内容还没同步」，白等 {@link #WINDOW_SYNC_FALLBACK_TICKS} tick（≈600ms）——
     * 「打开箱子一两秒才动手」有一半来自这里。
     */
    private static final long WINDOW_SYNC_TOLERANCE_NANOS = 400_000_000L;
    /**
     * 一件一件拿时，等服务端确认（S32）最多等多少 tick。
     *
     * <p>正常情况确认 1~2 tick 就回来；包钩子不可用 / 服务端不回确认时，等这么久就放行，
     * 免得整箱东西因为收不到确认而卡住（8 tick ≈ 400ms）。
     */
    private static final int CONFIRM_FALLBACK_TICKS = 8;

    private static final float[][] ESP_COLORS = {
            {1.00F, 0.76F, 0.36F},   // gold
            {0.42F, 0.86F, 1.00F},   // cyan
            {0.78F, 0.62F, 1.00F},   // violet
            {1.00F, 0.45F, 0.45F},   // red
            {0.93F, 0.95F, 0.98F}    // white
    };

    private static final Map<Class<?>, Integer> KIND_CACHE = new HashMap<Class<?>, Integer>();

    // ==================================================================
    // 运行时状态
    // ==================================================================

    private static boolean espFailed;
    /** 出错后跳过若干 tick 再试，绝不让一次异常永久废掉整个功能。 */
    private static int stealerErrorTicks;
    private static int stealerErrorCount;
    /** windowClick 不可用只提示一次，避免每 tick 刷日志。 */
    private static boolean clickWarned;
    /** 自动扫箱：准星在同一个箱子上停留的 tick 数 / 上次自动开箱时间 / 上次瞄准坐标。 */
    private static int sweepTicks;
    private static long lastSweepNanos;
    private static int sweepX = Integer.MIN_VALUE;
    private static int sweepY;
    private static int sweepZ;
    /** 刚被搬空的箱子坐标 / 时间：{@link #SWEEP_REVISIT_NANOS} 内不再自动扫它。 */
    private static int lootedX = Integer.MIN_VALUE;
    private static int lootedY;
    private static int lootedZ;
    private static long lootedNanos;

    private static int guiTicks;
    private static int idleTicks;
    private static boolean lootRequested;
    private static boolean sortRequested;
    private static boolean storeRequested;

    private static long lastOpenNanos;
    /** 上一次按下操作键的时间：开箱请求超时后不再继续尝试。 */
    private static long lootRequestNanos;

    /** 已经发出开箱包，正在等服务端的 S2D 窗口包。 */
    private static boolean openArmed;
    private static long openArmedNanos;

    /**
     * 已经发过开箱包、还在等 S2D 的窗口包。
     *
     * <p>以前每一 tick 都可能重发一次右键包（日志里一秒能出现三四条
     * {@code send open packet}），服务端就会反复关掉旧容器再开新容器；我们手上那个
     * 「已经挂好」的容器随即作废，槽位内容再也对不上，搬运动作全部落空。
     */
    private static boolean openPacketPending;

    /**
     * 容器会话开着：{@code player.openContainer} 是箱子侧容器。
     *
     * <p>可见模式（默认）下界面留在屏幕上，静默模式下界面被收起，两种情况都算「会话开着」。
     */
    private static boolean chestSession;
    /** 这次会话是<b>我们</b>替玩家发的右键（玩家自己开的箱子不算）。 */
    private static boolean sessionOwned;
    /** 原版箱子界面此刻是否还挂在屏幕上（静默模式下收起之后为 false）。 */
    private static boolean sessionVisible;

    /** 原版刚把容器挂好、等着在同一个 tick 内收起来的箱子界面（只有静默模式用）。 */
    private static Object pendingGui;
    /** 有界面等着收起来（{@link #pendingGui}）。 */
    private static boolean hidePending;
    /** 原版真正写进容器里的服务端 windowId；没有时为 -1。 */
    private static int sessionWindowId = -1;
    /** 客户端 tick 计数（点击节奏用）。 */
    private static int clientTicks;
    /** 上一批 windowClick 发生在哪个 client tick。 */
    private static int lastClickTick = -100000;
    /** 本次箱子会话开始的时间（纳秒）：用来忽略上一个窗口残留的包。 */
    private static long sessionStartNanos;
    /** 上一批点击发出的时间：一件一件拿时要等这个时间之后的 S32 确认。 */
    private static long lastClickNanos;
    /** 本次会话搬走的摞数：0 表示一次都没搬成（自动关界面要靠它判断）。 */
    private static int sessionMoved;
    /** 连续「一件都没搬动」的搬运尝试次数：用来区分「还在等内容」和「箱子真的空」。 */
    private static int emptyPasses;
    /** 上一轮搬运在箱子侧看到的有物品格子数（区分「容器是空的」和「被过滤筛掉」）。 */
    private static int seenLastPass;
    /** 上一次搬运被拾取过滤筛掉的物品件数。 */
    private static int rejectedLastPass;
    /** 已经为哪个窗口打过「过滤把物品全筛掉」的提示（窗口号），避免每 tick 刷日志。 */
    private static int filterWarnWindow = Integer.MIN_VALUE;

    // ---- 网络线程写、主线程读的窗口记账（见 ChestPacketHook） ----
    /** 服务端 S2D 开窗包里最后一个窗口号。 */
    private static volatile int netOpenedWindow = Integer.MIN_VALUE;
    /** 服务端 S30 窗口物品包里最后一个窗口号：它的内容客户端已经收到了。 */
    private static volatile int netItemsWindow = Integer.MIN_VALUE;
    /** 服务端 S2E 关窗包里最后一个窗口号。 */
    private static volatile int netClosedWindow = Integer.MIN_VALUE;
    /** 服务端 S32 窗口确认包（S32PacketConfirmTransaction）里最后一个窗口号。 */
    private static volatile int netConfirmWindow = Integer.MIN_VALUE;
    /** 上面三个包各自的到达时间：窗口号在 1..100 之间循环复用，必须按时间先后判断。 */
    private static volatile long netItemsNanos;
    private static volatile long netClosedNanos;
    private static volatile long netConfirmNanos;

    // ---- 包管线钩子（入站只看窗口类包，不做任何修改） ----
    private static ChestPacketHook chestHook;
    private static Object chestHookNetHandler;
    private static ChannelPipeline chestHookPipeline;
    private static boolean chestHookInstalled;
    private static boolean chestHookWarned;
    private static int chestHookTick;

    private static boolean lootKeyDown;
    private static boolean sortKeyDown;
    private static boolean storeKeyDown;

    // ==================================================================
    // tick 入口
    // ==================================================================

    /**
     * 每个 client tick（<b>START 阶段</b>）调用一次，必须早于原版发送 C03。
     *
     * <p>为什么必须是 START：Grim 的 {@code Post} 检查在收到 C03（flying）之后清空
     * {@code post} 队列，之后任何一个 action 包（含 {@code C0EPacketClickWindow}）都会留在队列里，
     * 直到下一个 C03。如果 transaction ack（{@code C0F}，由原版在 tick 开头的
     * scheduled-task 阶段发出）落在这两者之间，就会打出
     * {@code failed Post (vl:x.x): click window v1.8}。
     *
     * <p>tick END 发包时每个点击包都跨了 tick 边界：ack 一定落在
     * 「END 的 C0E」和「下一个 C03」之间 —— 本机日志里每条 {@code moved 1 stack}
     * 后面紧跟一条 Post flag 就是这么来的。改到 START 之后包序变成
     * {@code C0F → C0E → C03}，和玩家自己在界面里点鼠标完全一致。
     */
    public static void onTick(Minecraft mc) {
        if (mc == null) {
            return;
        }
        clientTicks++;
        updateEspWorld(read(mc, "theWorld", "field_71441_e", null));
        try {
            refreshSettings();
        } catch (Throwable ignored) {
            // Keep settings I/O failures out of the tick loop.
        }
        if (!isStealerEnabled()) {
            // 模块被关掉时，开着的容器要替玩家关掉，不能把服务端容器挂在那里。
            if (chestSession) {
                closeChestSession(mc, read(mc, "thePlayer", "field_71439_g", null), true);
            }
            resetSession();
            return;
        }
        try {
            pollActionKeys();
        } catch (Throwable ignored) {
            // 键位读取失败不能影响游戏循环。
        }
        if (stealerErrorTicks > 0) {
            stealerErrorTicks--;
            return;
        }
        try {
            runStealer(mc);
        } catch (Throwable t) {
            // 一次异常（字段名对不上 / 服务端关闭容器时的竞争）只跳过 4 tick（≈200ms）。
            // 之前这里是永久停用；后来改成 20 tick（1 秒）——在「一格 700ms」的日志里
            // 每跳过一次就是整箱慢半拍，所以再收到 4 tick：够让竞争状态过去，
            // 又不至于把整场拖长。
            stealerErrorTicks = 4;
            if (stealerErrorCount++ < 5) {
                debug("stealer error: " + t);
            }
        }
    }

    /** 关闭 / Panic 时清空运行时状态。 */
    public static void reset() {
        resetSession();
    }

    private static void resetSession() {
        endSessionState();
        lootRequested = false;
        sortRequested = false;
        storeRequested = false;
        lootRequestNanos = 0L;
        lootKeyDown = false;
        sortKeyDown = false;
        storeKeyDown = false;
        seenLastPass = 0;
        filterWarnWindow = Integer.MIN_VALUE;
        sweepTicks = 0;
        sweepX = Integer.MIN_VALUE;
        lastSweepNanos = 0L;
        lootedX = Integer.MIN_VALUE;
        lootedNanos = 0L;
        lastOpenNanos = 0L;
        lastCloseNanos = 0L;
        lastClickTick = -100000;
        clickIntervalNanos = 100_000_000L;
        clickBudgetThisTick = 0;
        clientWindowId = 0;
        stealerErrorTicks = 0;
    }

    /** 只复位「当前这一次开箱会话」的状态，不碰键位与扫箱冷却。 */
    private static void endSessionState() {
        chestSession = false;
        sessionOwned = false;
        sessionVisible = false;
        openArmed = false;
        openArmedNanos = 0L;
        openPacketPending = false;
        pendingGui = null;
        hidePending = false;
        sessionWindowId = -1;
        guiTicks = 0;
        idleTicks = 0;
        sessionMoved = 0;
        emptyPasses = 0;
        rejectedLastPass = 0;
        lastClickNanos = 0L;
        sessionFirstClickNanos = 0L;
        sessionClicks = 0;
        sessionContentShortcut = false;
        clickBudgetThisTick = 0;
    }

    /** 手动触发一次「全拿」（由 HUD 快捷键或外部按钮调用）。 */
    public static void triggerLoot() {
        lootRequested = true;
        lootRequestNanos = System.nanoTime();
        idleTicks = 0;
    }

    /** 手动触发一次「整理」。 */
    public static void triggerSort() {
        sortRequested = true;
        lootRequestNanos = System.nanoTime();
        idleTicks = 0;
    }

    /** 手动触发一次「存入」。 */
    public static void triggerStore() {
        storeRequested = true;
        lootRequestNanos = System.nanoTime();
        idleTicks = 0;
    }

    private static void pollActionKeys() {
        if (!isStealerEnabled()) {
            lootKeyDown = false;
            sortKeyDown = false;
            storeKeyDown = false;
            lootRequested = false;
            sortRequested = false;
            storeRequested = false;
            return;
        }
        boolean loot = keyDown(lootKey);
        if (loot && !lootKeyDown) {
            triggerLoot();
        }
        lootKeyDown = loot;

        boolean sort = keyDown(sortKey);
        if (sort && !sortKeyDown) {
            triggerSort();
        }
        sortKeyDown = sort;

        boolean store = keyDown(storeKey);
        if (store && !storeKeyDown) {
            triggerStore();
        }
        storeKeyDown = store;
    }

    private static boolean keyDown(String name) {
        int code = keyCode(name);
        return code > 0 && Keyboard.isKeyDown(code);
    }

    private static int keyCode(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        try {
            return Keyboard.getKeyIndex(name.toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    // ==================================================================
    // 开箱：让原版挂好容器 + windowId（可见模式留界面，静默模式同一 tick 收起）
    // ==================================================================

    /**
     * 由 {@code OpenMaverClientHandler.onGuiOpen} 在 {@code GuiOpenEvent} 里调用。
     *
     * <p>三个分支：
     * <ol>
     *   <li>是我们刚发出去的那次开箱请求对应的箱子界面 —— <b>不取消事件</b>：让原版
     *       {@code displayGuiScreen} 把 {@code GuiContainer.initGui} 跑完（容器挂到
     *       {@code player.openContainer}）、让 {@code NetHandlerPlayClient} 把服务端
     *       {@code windowId} 写进去。可见模式（默认）下界面就留在屏幕上；
     *       只有 {@code chest.stealer.hideGui=true} 才会在本 tick 末尾用
     *       {@link #hidePendingGui(Minecraft)} 把界面收起来。</li>
     *   <li>容器还开着，玩家又打开了别的界面（背包 / 创造物品栏 / ClickGUI / 聊天）——
     *       先把容器关掉，免得服务端容器和本地状态对不上。</li>
     *   <li>其它情况 —— 什么都不做，原版界面照常打开。</li>
     * </ol>
     *
     * <p><b>为什么不取消事件</b>：1.8.9 里 {@code windowId} 是原版在
     * {@code displayGuiScreen} 之后写到 {@code player.openContainer} 上的。以前在事件里
     * 取消，再自己调 {@code setWorldAndResolution} 抢容器，拿到的是 {@code GuiChest}
     * 自己 new 出来的那个容器，{@code windowId} 停在 0（背包窗口），于是：
     * 服务端后来发的 S30 槽位内容因为窗口号对不上被整包丢掉（箱子看着是空的）、
     * 我们发的 C0E 点击服务端也不认（一件都搬不走）。让原版把流程跑完就都不存在了。
     */
    public static void onGuiOpen(GuiOpenEvent event) {
        if (event == null || event.gui == null) {
            return;
        }
        if (!event.isCanceled()) {
            try {
                trackOpenedChest(Minecraft.getMinecraft(), event.gui);
            } catch (Throwable ignored) {
                // Optional ESP bookkeeping must not interfere with opening the container.
            }
        }
        if (!isStealerEnabled()) {
            openArmed = false;
            openPacketPending = false;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        Object player = read(mc, "thePlayer", "field_71439_g", null);
        if (player == null) {
            resetSession();
            return;
        }
        Object playerInv = playerInventoryOf(player);
        Object own = playerInventoryContainerOf(player);
        boolean chestGui = isChestScreen(event.gui, playerInv, own);

        if (!chestGui) {
            // 别的界面已经开起来了：开箱包不用再等回包了。
            openPacketPending = false;
            // 马上要开别的界面：只发关窗包 + 换回背包容器，别再动 currentScreen。
            if (chestSession || hidePending) {
                pendingGui = null;
                closeChestSession(mc, player, false);
            }
            return;
        }

        long now = System.nanoTime();
        boolean expired = openArmedNanos > 0L && now - openArmedNanos > ARM_WINDOW_NANOS;
        boolean armed = openArmed && !expired && player != null;
        openArmed = false;
        openArmedNanos = 0L;
        openPacketPending = false;

        if (isChestGuiStrict(event.gui, playerInv, own)) {
            // 只要是箱子界面就接管成一次新会话 —— 不管是我们的开箱包引出来的（armed），
            // 还是玩家自己右键开的（!armed）。
            //
            // 以前 armed=false 时会把「上一个会话还挂着」的容器关掉（发 C0D + 把
            // player.openContainer 换回背包）。在快速连开 / 服务端关窗包晚到的情况下，
            // 关掉的是**刚打开的那个新 GuiChest**：界面还在屏幕上，但 openContainer
            // 已经变成背包容器（windowId=0）→ 主循环一句 windowId<=0 直接 return，
            // 表现就是「点开箱子直接不拿」，玩家一关界面又打一条
            // chest session gone。这就是下面这段被替换掉的写法。
            startChestSession(event.gui, armed);
            debug((hideGui ? "silent" : "visible") + " open accepted ("
                    + event.gui.getClass().getSimpleName() + ")"
                    + (armed ? "" : ", manual"));
            // 注意：这里必须 return，但绝不能 setCanceled。
            return;
        }

        debug("chest gui arrived without arm: " + event.gui.getClass().getSimpleName());
        // 不是严格的箱子界面（模组页签 / 创造物品栏之类）：让原版照常打开，
        // 但把可能挂着的旧容器收掉，避免两个容器状态对不上。
        lastSweepNanos = now;
        if (chestSession || hidePending) {
            pendingGui = null;
            closeChestSession(mc, player, false);
        }
    }

    /**
     * 记下「一次箱子会话开始了」。
     *
     * <p>可见模式（默认）下界面就是原版那个 {@code GuiChest}，我们什么都不做，
     * 后面直接在界面上点格子；静默模式才需要 {@link #hidePending} 在 tick 末尾
     * 把界面收起来。
     */
    private static void startChestSession(Object gui, boolean owned) {
        chestSession = true;
        sessionOwned = owned;
        sessionVisible = !hideGui;
        pendingGui = gui;
        hidePending = hideGui;
        sessionWindowId = -1;
        sessionMoved = 0;
        emptyPasses = 0;
        rejectedLastPass = 0;
        guiTicks = 0;
        idleTicks = 0;
        // 新箱子从「上一批点击很久以前」开始算，免得被上一个箱子的节奏卡住。
        lastClickTick = -100000;
        lastClickNanos = 0L;
        sessionStartNanos = System.nanoTime();
        // 每个箱子重新抽一次「开箱反应时间」和「关箱前等多久」：
        // 固定值本身就是指纹，随机才是人的表现。
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // 反应时间最多 2 tick：以前 firstClickDelayMs/50 是精确换算，配大了就会
        // 整箱都慢一截；这里保留随机抖动（固定值才是真正的指纹）。
        int reactionTicks = clamp(firstClickDelayMs / 50, 0, 2);
        sessionReactionTicks = reactionTicks + random.nextInt(0, 3);
        sessionCloseTicks = closeDelayMinTicks
                + random.nextInt(0, Math.max(1, closeDelayMaxTicks - closeDelayMinTicks + 1));
        nextClickNanos = sessionStartNanos + (long) (openDelayTicks + sessionReactionTicks)
                * 50_000_000L;
        clickIntervalNanos = Math.max(MIN_CLICK_INTERVAL_MS, clickDelayMinMs) * 1_000_000L;
        clickStreak = 0;
        clickBudgetThisTick = 0;
        sessionFirstClickNanos = 0L;
        sessionClicks = 0;
        sessionContentShortcut = false;
    }

    /**
     * 渲染帧开始时的兜底：万一开箱包在「tick 结束」和「这一帧渲染」之间才处理完，
     * 界面仍然会被收掉，不会闪一帧。
     */
    public static void onRenderTickStart(Minecraft mc) {
        if (!hidePending) {
            return;
        }
        try {
            hidePendingGui(mc);
        } catch (Throwable t) {
            hidePending = false;
            pendingGui = null;
            debug("hide silent gui failed: " + t);
        }
    }

    /**
     * 把「静默开箱」的那个箱子界面收起来（只有 {@code chest.stealer.hideGui=true} 会走到这里）。
     *
     * <p>{@code Minecraft.displayGuiScreen(null)} 只做三件事：关掉当前界面、恢复声音、
     * 重新锁定鼠标 —— 它<b>不会</b>发 C0D，也<b>不会</b>动 {@code openContainer}，
     * 所以服务端容器和槽位内容都还在，接下来就能照常 shift-click 搬运。
     * 界面从挂上到收起都在同一个 client tick 里，玩家看不到闪一下。
     */
    public static void hidePendingGui(Minecraft mc) {
        if (!hidePending || mc == null) {
            return;
        }
        Object player = read(mc, "thePlayer", "field_71439_g", null);
        Object screen = read(mc, "currentScreen", "field_71462_r", null);
        hidePending = false;
        if (player == null) {
            endSessionState();
            return;
        }
        if (screen == null) {
            // 原版没把界面挂上（服务端拒绝开箱 / 窗口被别的包顶掉）：这次开箱作废。
            endSessionState();
            debug("silent open dropped: no screen was shown");
            return;
        }
        if (pendingGui != null && screen != pendingGui) {
            // 同一 tick 里玩家已经切到别的界面了：按「玩家自己开了别的界面」处理。
            pendingGui = null;
            closeChestSession(mc, player, false);
            return;
        }
        pendingGui = null;
        mc.displayGuiScreen(null);
        sessionVisible = false;

        Object container = openContainerOf(player);
        Object playerInv = playerInventoryOf(player);
        Object own = playerInventoryContainerOf(player);
        Object slotList = container == null ? null : slotListOf(container);
        List<?> slots = slotList instanceof List ? (List<?>) slotList : null;
        if (!isChestContainer(container, slots, playerInv, own)) {
            debug("silent open dropped: openContainer is not a chest container ("
                    + (container == null ? "null" : container.getClass().getSimpleName()) + ")");
            closeChestSession(mc, player, true);
            return;
        }
        int windowId = intOf(read(container, "windowId", "field_75152_c", null));
        if (windowId <= 0) {
            // windowId 还是 0 说明原版没来得及写（或者写到了别的容器上）：
            // 这种窗口点下去服务端一定不认，直接放弃，别让玩家以为「搬了但没搬动」。
            debug("silent open dropped: container windowId is still " + windowId);
            closeChestSession(mc, player, true);
            return;
        }
        chestSession = true;
        sessionWindowId = windowId;
        debug("silent open ok (" + screen.getClass().getSimpleName()
                + ", windowId=" + windowId + ", slots=" + (slots == null ? 0 : slots.size()) + ")");
    }

    /**
     * 关掉当前箱子会话，回到「什么都没开」的状态。
     *
     * <p>顺序就是人关箱子的顺序：{@code EntityPlayerSP.closeScreen()} 先把这个窗口的
     * {@code C0D} 关窗包发出去、清掉光标上的物品、把 {@code openContainer} 换回背包容器，
     * 然后 {@code Minecraft.displayGuiScreen(null)} 让界面消失。服务端看到的就是
     * 「玩家拿完东西、关了箱子」，而不是「窗口莫名其妙消失」。
     *
     * @param closeGui true：界面是这次会话留下的，要跟玩家按 E 一样关掉；
     *                 false：马上要打开别的界面，只发关窗包、换回背包容器，
     *                 不碰 {@code currentScreen}
     */
    private static void closeChestSession(Minecraft mc, Object player, boolean closeGui) {
        int windowId = sessionWindowId;
        boolean wasVisible = sessionVisible;
        long openedAt = sessionStartNanos;
        long firstClickAt = sessionFirstClickNanos;
        long lastClickAt = lastClickNanos;
        int movedStacks = sessionMoved;
        int clicks = sessionClicks;
        if (movedStacks > 0 && sweepX != Integer.MIN_VALUE) {
            // 刚搬空的箱子：短时间内不再自动扫它。本机日志里关箱 0.66s 之后又对
            // 同一个箱子发了开箱包（窗口里 0 件物品），白花一次往返。
            lootedX = sweepX;
            lootedY = sweepY;
            lootedZ = sweepZ;
            lootedNanos = System.nanoTime();
        }
        endSessionState();
        if (clicks > 0) {
            long now = System.nanoTime();
            // 每箱一行速度小结：open→first / first→last 是核对节拍有没有被拖慢的
            // 唯一可靠数据（服务端的「耗时 / 间隔」统计是另一套，含真实 RTT）。
            debug("session done: " + movedStacks + " stack(s) / " + clicks + " click(s)"
                    + ", open→first " + millisBetween(firstClickAt, openedAt) + "ms"
                    + ", first→last " + millisBetween(lastClickAt, firstClickAt) + "ms"
                    + ", total " + millisBetween(now, openedAt) + "ms");
        }
        // 关窗时间戳：服务端处理 C0D 有延迟，这之后的一小段时间内不要再开新箱子，
        // 否则旧窗口的关窗包会把新窗口一起带走（1.8 的 handleCloseWindow 是无条件关界面）。
        lastCloseNanos = System.nanoTime();
        if (player == null) {
            return;
        }
        Object own = playerInventoryContainerOf(player);
        Object container = openContainerOf(player);
        if (windowId <= 0 && container != null) {
            windowId = intOf(read(container, "windowId", "field_75152_c", null));
        }
        if (closeGui) {
            // 原版关箱子：C0D + 光标物品落地 + openContainer 复位。
            callVoid(player, "closeScreen", "func_71053_j", null);
        }
        Object after = openContainerOf(player);
        if (after != null && after != own) {
            // closeScreen 不可用 / 没生效时的兜底：手工发关窗包并把容器换回背包。
            // windowId 0 是背包窗口，绝不能拿它去发 C0D。
            if (windowId > 0) {
                sendCloseWindow(player, windowId);
            }
            if (own != null) {
                writeField(player, "openContainer", "field_71070_bA", own);
            }
        }
        if (closeGui && wasVisible && mc != null
                && read(mc, "currentScreen", "field_71462_r", null) != null) {
            mc.displayGuiScreen(null);
        }
    }

    /** 手动发一个 {@code C0DPacketCloseWindow(windowId)}。 */
    private static void sendCloseWindow(Object player, int windowId) {
        try {
            Class<?> type = Class.forName("net.minecraft.network.play.client.C0DPacketCloseWindow");
            Object packet = type.getConstructor(Integer.TYPE)
                    .newInstance(Integer.valueOf(windowId));
            Object netHandler = read(player, "sendQueue", "field_71174_a", null);
            if (netHandler != null) {
                callVoid(netHandler, "addToSendQueue", "func_147297_a", "a", packet);
            }
        } catch (Throwable ignored) {
            // 发不出去只影响这一轮关窗，主线程的状态复位照常进行。
        }
    }

    // ==================================================================
    // 入站窗口包记账（由 ChestPacketHook 在网络线程回调）
    // ==================================================================

    /**
     * 入站 S2E 是不是「过期窗口」的关窗包。
     *
     * <p>返回 true 时调用方（{@link ChestPacketHook}）应当丢弃它、不要往下传：
     * 1.8.9 的 {@code NetHandlerPlayClient.handleCloseWindow} 不检查窗口号，
     * 任何 S2E 都会把玩家当前开着的界面 {@code closeScreen()} 掉。快速连开箱子时，
     * 服务端替旧窗口补发的 S2E 往往晚一两个 tick 才到，正好把刚打开的新 GuiChest 带走 ——
     * 界面上看是「一闪就没了」，搬运侧就是「点开箱子直接不拿」。
     *
     * <p>窗口号对得上（或我们还不知道当前窗口号）时返回 false，照常放行，
     * 服务端真的要关窗的行为不受影响。
     */
    public static boolean isStaleCloseWindow(Object packet) {
        if (packet == null) {
            return false;
        }
        String name = packet.getClass().getSimpleName();
        if (!name.contains("CloseWindow") || name.contains("OpenWindow")) {
            return false;
        }
        int windowId = firstIntField(packet);
        if (windowId <= 0) {
            return false;
        }
        int current = clientWindowId;
        return current > 0 && windowId != current;
    }

    /**
     * 记下服务端开窗（S2D）/ 窗口物品（S30）/ 关窗（S2E）的窗口号。
     *
     * <p>只写 volatile 字段，绝不读世界或实体 —— 反序列化刚结束时主线程可能正握着它们。
     * 主线程在 {@link #runStealer} 里消费：S30 到过才动手点格子，S2E 到过就收摊。
     */
    public static void noteInboundPacket(Object packet) {
        if (packet == null) {
            return;
        }
        String name = packet.getClass().getSimpleName();
        boolean items = name.contains("WindowItems");
        boolean confirm = name.contains("ConfirmTransaction");
        boolean open = !items && !confirm && name.contains("OpenWindow");
        boolean close = !items && !confirm && !open && name.contains("CloseWindow");
        if (!open && !items && !close && !confirm) {
            return;
        }
        int windowId = firstIntField(packet);
        if (windowId < 0) {
            return;
        }
        long now = System.nanoTime();
        if (items) {
            netItemsWindow = windowId;
            netItemsNanos = now;
        } else if (confirm) {
            netConfirmWindow = windowId;
            netConfirmNanos = now;
        } else if (open) {
            netOpenedWindow = windowId;
        } else {
            netClosedWindow = windowId;
            netClosedNanos = now;
        }
    }

    /** 包里的第一个 {@code int} 字段（S2D / S30 / S2E 的窗口号都是它）。 */
    private static int firstIntField(Object packet) {
        for (Class<?> type = packet.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].getType() != Integer.TYPE) {
                    continue;
                }
                try {
                    fields[i].setAccessible(true);
                    return fields[i].getInt(packet);
                } catch (Throwable ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * 上一批点击是否已经被服务端确认（S32）。
     *
     * <p>一件一件拿时，拿下一格前要等这个信号：服务端认可了「上一摞真的进背包了」，
     * 才轮到下一摞 —— 和玩家自己动手一样。确认包收不到时按
     * {@link #CONFIRM_FALLBACK_TICKS} 兜底放行，不会卡死。
     */
    private static boolean confirmSettled(int windowId) {
        return netConfirmWindow == windowId && netConfirmNanos >= lastClickNanos;
    }

    /**
     * 服务端的窗口物品包（S30）是不是已经到过这个 windowId。
     *
     * <p>窗口号在 1..100 之间循环复用，所以还要比时间：只有<b>本次会话开始之后</b>
     * 收到的那个包才算数，否则上一个箱子残留的记账会让新箱子提前被放行。
     */
    private static boolean contentSynced(int windowId) {
        return windowId > 0 && netItemsWindow == windowId
                && netItemsNanos + WINDOW_SYNC_TOLERANCE_NANOS >= sessionStartNanos;
    }

    /**
     * 本地容器里是不是已经有「箱子侧」物品。
     *
     * <p>S30 没被包钩子看到（钩子晚挂 / 版本改包名）时用它判断「内容其实已经到了」：
     * 槽位里有东西就说明内容落在容器里了，没必要再干等。
     */
    private static boolean chestSideHasItems(List<?> slots, Object playerInv) {
        if (slots == null || playerInv == null) {
            return false;
        }
        for (int i = 0; i < slots.size(); i++) {
            Object slot = slots.get(i);
            if (slot == null) {
                continue;
            }
            Object inv = read(slot, "inventory", "field_75224_c", null);
            if (inv == null || inv == playerInv) {
                continue;
            }
            if (call(slot, "getStack", "func_75211_c", null) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本 tick 允许发几个 {@code windowClick}。
     *
     * <p>按「距上次点击过去了几个节拍」算：正常 20 TPS 时永远是 0 或 1；
     * 客户端掉帧（本机日志里出现过 200ms/tick，甚至更糟）时一次补到
     * {@link #MAX_CLICKS_PER_TICK} 格，整箱耗时不再被 tick 粒度拖成好几秒。
     */
    private static int clicksDueNow(long now) {
        if (lastClickNanos <= 0L) {
            return nextClickNanos <= 0L || now >= nextClickNanos ? 1 : 0;
        }
        long interval = clickIntervalNanos > 0L
                ? clickIntervalNanos : (long) MIN_CLICK_INTERVAL_MS * 1_000_000L;
        long elapsed = now - lastClickNanos;
        if (elapsed < interval) {
            return 0;
        }
        int due = 1 + (int) ((elapsed - interval) / interval);
        return Math.min(due, MAX_CLICKS_PER_TICK);
    }

    private static long millisBetween(long later, long earlier) {
        if (later <= 0L || earlier <= 0L) {
            return 0L;
        }
        return (later - earlier) / 1_000_000L;
    }

    // ==================================================================
    // 入站窗口包观察者（Netty 管线）
    // ==================================================================

    /**
     * 保证 {@link ChestPacketHook} 挂在当前连接上。
     *
     * <p>换服 / 重连 / 单人世界都会重建 {@code NetworkManager + Channel + Pipeline}，
     * 所以这里用 {@code NetHandlerPlayClient} 实例当「连接标识」，变了就重挂；
     * 另外定期校验 handler 是否还在管线里（被别的插件摘掉时自愈）。
     */
    private static void maintainPacketHook(Minecraft mc) {
        chestHookTick++;
        boolean installed = chestHookInstalled && pipelineHasChestHook();
        // 没挂上时每 2 tick 重试一次（以前是 40 tick = 2 秒）。进服 / 重连之后的
        // 第一次开箱常常正好落在这段窗口里：S30 的记账整场丢失，主循环只能走
        // 「12 tick 后按内容到齐放行」的兜底 —— 本机日志里「箱子开了 0.9s 才点第一格」
        // 就是包钩子在开箱之后才 attach 造成的。
        if (chestHookTick % (installed ? 20 : 2) != 0) {
            return;
        }
        Object netHandler = resolveNetHandler(mc);
        if (netHandler != chestHookNetHandler) {
            detachChestHook();
            chestHookNetHandler = netHandler;
        }
        if (chestHookInstalled && pipelineHasChestHook()) {
            return;
        }
        detachChestHook();
        if (netHandler != null) {
            attachChestHook(netHandler);
        }
    }

    private static Object resolveNetHandler(Minecraft mc) {
        Object player = read(mc, "thePlayer", "field_71439_g", null);
        Object netHandler = player == null ? null : read(player, "sendQueue", "field_71174_a", null);
        if (netHandler != null) {
            return netHandler;
        }
        return call(mc, "getNetHandler", "func_147114_u", null);
    }

    /** 反射链：NetHandler -> NetworkManager -> Channel -> Pipeline，然后挂 handler。 */
    private static void attachChestHook(Object netHandler) {
        try {
            Object networkManager = networkManagerOf(netHandler);
            if (networkManager == null) {
                warnChestHook("NetHandlerPlayClient 里找不到 NetworkManager 字段");
                return;
            }
            Object channel = channelOf(networkManager);
            if (channel == null) {
                warnChestHook("NetworkManager 里找不到 Channel 字段");
                return;
            }
            Object pipelineObject = call(channel, "pipeline", "pipeline", "pipeline");
            if (!(pipelineObject instanceof ChannelPipeline)) {
                warnChestHook("Channel.pipeline() 返回的不是 ChannelPipeline");
                return;
            }
            ChannelPipeline pipeline = (ChannelPipeline) pipelineObject;
            List<String> names = pipeline.names();
            if (names != null && names.contains(ChestPacketHook.NAME)) {
                chestHookInstalled = true;
                chestHookPipeline = pipeline;
                return;
            }
            ChestPacketHook hook = new ChestPacketHook();
            if (names != null && names.contains("packet_handler")) {
                // 插在 packet_handler 之前：入站拿到的是解码好、还没交给 NetHandler 的包。
                pipeline.addBefore("packet_handler", ChestPacketHook.NAME, hook);
            } else {
                pipeline.addLast(ChestPacketHook.NAME, hook);
            }
            chestHook = hook;
            chestHookPipeline = pipeline;
            chestHookInstalled = true;
            chestHookWarned = false;
            debug("packet hook attached (" + ChestPacketHook.NAME + ")");
        } catch (Throwable error) {
            warnChestHook("管线注入异常: " + error);
        }
    }

    private static void detachChestHook() {
        ChannelPipeline pipeline = chestHookPipeline;
        ChestPacketHook hook = chestHook;
        if (pipeline != null && hook != null) {
            try {
                pipeline.remove(hook);
            } catch (Throwable ignored) {
                // 连接已经关闭 / 管线已销毁。
            }
        }
        chestHook = null;
        chestHookPipeline = null;
        chestHookInstalled = false;
    }

    private static boolean pipelineHasChestHook() {
        ChannelPipeline pipeline = chestHookPipeline;
        if (pipeline == null) {
            return false;
        }
        try {
            List<String> names = pipeline.names();
            return names != null && names.contains(ChestPacketHook.NAME);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void warnChestHook(String reason) {
        if (chestHookWarned) {
            return;
        }
        chestHookWarned = true;
        debug("packet hook unavailable: " + reason + " — 退回按 tick 估算内容同步");
    }

    // ==================================================================
    // 搬运流程
    // ==================================================================

    private static void runStealer(Minecraft mc) {
        // 本 tick 的点击配额由节拍重新算；这里先清零，任何早退路径都不会留下残留配额。
        clickBudgetThisTick = 0;
        // 入站窗口包记账要跟着连接走：换服 / 重连都会重建管线。
        maintainPacketHook(mc);
        // 静默模式才用得到：把「原版刚挂好、等我们收起来」的那个箱子界面收起来。
        hidePendingGui(mc);

        Object player = read(mc, "thePlayer", "field_71439_g", null);
        if (player == null) {
            resetSession();
            return;
        }
        Object playerInv = playerInventoryOf(player);
        Object own = playerInventoryContainerOf(player);
        Object screen = read(mc, "currentScreen", "field_71462_r", null);
        boolean chestScreen = isChestScreen(screen, playerInv, own);

        Object container = openContainerOf(player);
        Object slotList = container == null ? null : slotListOf(container);
        List<?> slots = slotList instanceof List && !((List<?>) slotList).isEmpty()
                ? (List<?>) slotList : null;

        // 静默容器没有界面，所以不能靠 chestScreen 判断：只要 openContainer 是箱子侧容器就搬。
        boolean chestContainer = !chestScreen
                && isChestContainer(container, slots, playerInv, own);

        if (!chestScreen && !chestContainer) {
            guiTicks = 0;
            idleTicks = 0;
            sessionMoved = 0;
            emptyPasses = 0;
            rejectedLastPass = 0;
            if (chestSession) {
                // 服务端可能已经自己关了容器（走远 / 死亡 / 换维度）。
                endSessionState();
                debug("chest session gone, session reset");
            }
            if (lootRequested || sortRequested || storeRequested) {
                maybeAutoOpen(mc, player, screen);
            } else {
                maybeAutoSweep(mc, player, screen);
            }
            return;
        }
        if (chestScreen) {
            // 界面还在屏幕上：这就是可见会话（我们开的、玩家自己开的都算）。
            if (!chestSession) {
                sessionStartNanos = System.nanoTime();
            }
            chestSession = true;
            sessionVisible = true;
        } else if (sessionVisible && chestSession) {
            // 界面被关掉了（玩家按 E / 别的界面顶掉），但服务端容器还挂着：
            // 立刻收摊。界面都没了还继续点格子，正是反作弊最容易抓的形态。
            debug("chest gui closed mid-session, session reset");
            closeChestSession(mc, player, false);
            return;
        } else if (!chestSession) {
            // 没有界面但容器确实开着：可能是静默会话的标志丢了，也可能是服务端早就关掉的
            // 死容器。只有「服务端确实开过这个窗口」时才接管，否则把容器换回背包，
            // 免得对着一个已经不存在的窗口点格子。
            int adopted = intOf(read(container, "windowId", "field_75152_c", null));
            boolean fresh = !chestHookInstalled
                    || netOpenedWindow == adopted || netItemsWindow == adopted;
            if (adopted > 0 && fresh) {
                chestSession = true;
                sessionVisible = false;
                sessionWindowId = adopted;
                sessionStartNanos = System.nanoTime();
            } else {
                Object ownInv = playerInventoryContainerOf(player);
                if (ownInv != null) {
                    debug("stale chest container (windowId=" + adopted + "), container reset");
                    writeField(player, "openContainer", "field_71070_bA", ownInv);
                }
                return;
            }
        }

        int windowId = intOf(read(container, "windowId", "field_75152_c", null));
        if (windowId <= 0 && sessionWindowId > 0) {
            windowId = sessionWindowId;
        }
        // 界面还开着但 openContainer 已经被换成背包（windowId=0）：重启之前的连开逻辑
        // 会把新 GuiChest 的容器 C0D 掉并复位成背包，于是这里一路 return、一件都不拿。
        // 现在从界面自己的 inventorySlots 把容器恢复回来，让搬运自愈。
        if (windowId <= 0 && chestScreen) {
            Object guiContainer = read(screen, "inventorySlots", "field_147002_h", null);
            if (guiContainer != null && guiContainer != own) {
                Object guiSlots = slotListOf(guiContainer);
                if (guiSlots instanceof List && !((List<?>) guiSlots).isEmpty()
                        && hasChestSide((List<?>) guiSlots, playerInv)) {
                    int recovered = intOf(read(guiContainer, "windowId", "field_75152_c", null));
                    if (recovered > 0) {
                        writeField(player, "openContainer", "field_71070_bA", guiContainer);
                        container = guiContainer;
                        slots = (List<?>) guiSlots;
                        windowId = recovered;
                        sessionWindowId = recovered;
                        debug("recovered chest container from gui (windowId=" + recovered + ")");
                    }
                }
            }
        }
        if (windowId <= 0 || slots == null || playerInv == null) {
            // windowId 还是 0（背包窗口）时点下去服务端一定不认：与其空点，不如等下一 tick。
            return;
        }
        // 给入站钩子用：判断「这个 S2E 是不是已经过期的旧窗口关窗包」。
        clientWindowId = windowId;
        // 服务端自己关了这个窗口（走远 / 死亡 / 管理员指令）：会话立刻作废，
        // 别再对着一个已经不存在的窗口点格子（那正是「click window」类检查的靶子）。
        if (netClosedWindow == windowId && netClosedNanos >= sessionStartNanos) {
            debug("server closed window " + windowId + ", session reset");
            endSessionState();
            // 服务端已经不认这个窗口了：本地容器也换回背包装，别再拿它去点。
            Object ownInv = playerInventoryContainerOf(player);
            if (ownInv != null) {
                writeField(player, "openContainer", "field_71070_bA", ownInv);
            }
            return;
        }

        if (guiTicks < 10000) {
            guiTicks++;
        }
        // 1) 开窗后的延迟：界面挂上 + 真人「看清箱子里的东西」的反应时间。
        //    reaction tick 每个箱子随机，写死 2 tick 是机器味最重的地方之一。
        if (guiTicks <= openDelayTicks + (humanize ? sessionReactionTicks : 0)) {
            return;
        }
        // 2) 内容同步门：等服务端的窗口物品包（S30）真的到过这个 windowId。
        //    人也是看到箱子里的东西才去点格子的。
        //    但「包钩子没看到 S30」不等于「内容没到」——本地容器槽位里已经有箱子侧
        //    物品时，内容显然已经落到容器里了，直接放行（本机日志里 0.6~1.4s 的
        //    「开箱后干等」就是包钩子晚挂导致这里白等 12 tick）。兜底也从 12 tick
        //    收到 4 tick：真拿不到内容时早一点收摊，别把整场拖长。
        boolean localContent = !contentSynced(windowId) && chestSideHasItems(slots, playerInv);
        if (!contentSynced(windowId) && !localContent
                && guiTicks <= openDelayTicks + WINDOW_SYNC_FALLBACK_TICKS) {
            return;
        }
        if (!contentSynced(windowId) && localContent && !sessionContentShortcut) {
            sessionContentShortcut = true;
            debug("content gate: local container already has items, skip S30 wait (window "
                    + windowId + ")");
        }
        // 3) 点击节奏：人类化时按「随机毫秒」节拍走（默认 100~160ms 一格，偶尔停一下），
        //    绝不使用「每 tick 一格」这种 50ms 等距的机械节奏。
        //
        //    这里用「距上次点击过了几个间隔」算本 tick 能发几格：客户端掉帧
        //    （日志里出现过 200ms/tick）时一次补到 2 格，正常 20 TPS 时永远是 1 格。
        //    一次补两格仍然是真人能打出来的形态，而且两个包都在本 tick 的 C03 之前。
        if (humanize) {
            int due = clicksDueNow(System.nanoTime());
            if (due <= 0) {
                return;
            }
            clickBudgetThisTick = due;
        } else {
            if (lastClickNanos > 0L) {
                boolean waitedGap = clickGapTicks <= 0
                        || clientTicks - lastClickTick >= clickGapTicks;
                boolean fallback = clientTicks - lastClickTick >= CONFIRM_FALLBACK_TICKS;
                if (oneByOne ? (!confirmSettled(windowId) && !waitedGap && !fallback)
                        : !waitedGap) {
                    return;
                }
            }
            clickBudgetThisTick = oneByOne ? 1 : Math.max(1, clicksPerTick);
        }

        // 静默模式（界面被收起）、我们开的可见界面、玩家自己开的界面，都走同一条搬运路径。
        if (lootRequested || autoLoot) {
            // 一件一件拿：每批只点一格；关掉 ONE BY ONE 才按 CLICKS/TICK 连续拿。
            // humanize 时无论 ONE BY ONE 怎么设，都强制一格一格走 ——
            // 一 tick 多个 click window 包是真人做不到的形态。
            int budget = Math.max(1, clickBudgetThisTick);
            int moved = lootPass(mc, player, windowId, slots, playerInv, budget);
            if (moved > 0) {
                // 记账与下一格节拍在 click() 里统一做：这里的 lastClickNanos
                // 只用于日志/兜底，不要覆盖 click() 算好的 nextClickNanos。
                lastClickTick = clientTicks;
                sessionMoved += moved;
                lootRequested = false;
                idleTicks = 0;
                emptyPasses = 0;
                debug("loot: moved " + moved + " stack(s) in window " + windowId
                        + " (filter=" + getTakeFilterName() + ", free slots="
                        + playerFreeSlots(playerInv) + (chestScreen ? ", visible" : ", silent")
                        + ", next in " + lastScheduledDelayMs + "ms" + ")");
                return;
            }
            // 这一轮一件都没搬动。三种可能：内容还没同步（S30 晚到）、拾取过滤把物品全筛掉、
            // 背包满了。这时绝不能立刻关：内容可能还在路上，下一 tick 接着搬。
            emptyPasses++;
            if (rejectedLastPass > 0) {
                warnFilterRejected(windowId);
            }
            if (lootRequested) {
                lootRequested = false;
                debug("loot: nothing matched in window " + windowId
                        + " (filter=" + getTakeFilterName() + ", free slots="
                        + playerFreeSlots(playerInv) + ", chest items=" + seenLastPass
                        + ", empty passes=" + emptyPasses
                        + (chestScreen ? ", visible" : ", silent") + ")");
            }
        }

        boolean sortIdle = true;
        if (sortRequested || autoSort) {
            sortIdle = !sortPass(mc, player, windowId, slots, playerInv);
            if (sortIdle) {
                sortRequested = false;
            } else {
                lastClickTick = clientTicks;
            }
        }
        if (!sortIdle) {
            idleTicks = 0;
            return;
        }

        boolean storeIdle = true;
        if (storeRequested) {
            storeIdle = !storePass(mc, player, windowId, slots, playerInv);
            if (storeIdle) {
                storeRequested = false;
            } else {
                lastClickTick = clientTicks;
            }
        }
        if (!storeIdle) {
            idleTicks = 0;
            return;
        }

        // ---- 收尾：搬完就关箱退出 ----
        // 静默会话没有可见界面，玩家也没法手动关，一定要替玩家关掉；
        // 可见会话按 AUTO CLOSE（默认开）处理，我们替玩家开的箱子无论如何都要退出，
        // 否则自动扫箱没法接着跑下一个箱子。
        boolean silentSession = !sessionVisible;
        if (!silentSession && !autoClose && !sessionOwned) {
            idleTicks = 0;
            return;
        }
        if (sessionMoved == 0 && emptyPasses <= SYNC_EMPTY_PASSES
                && playerFreeSlots(playerInv) > 0) {
            // 还没搬动过、内容可能仍在路上：界面先留着，别把「空箱子」判得太早。
            return;
        }
        // 已经没有任何动作可做：等几 tick 让服务端把最后一次点击结算完再关。
        // humanize 时是 3~9 tick（150~450ms），不是固定 100ms —— 真人关箱也有延迟。
        int closeTicks = humanize ? sessionCloseTicks : 2;
        if (++idleTicks > closeTicks) {
            idleTicks = 0;
            debug((silentSession ? "silent" : "visible") + " close after moving "
                    + sessionMoved + " stack(s), wait " + closeTicks + " tick(s)");
            closeChestSession(mc, player, true);
        }
    }

    /**
     * 准星对着箱子按快捷键时，替玩家发一次右键开箱包。
     *
     * <p>1.8.9 的箱子界面由 {@code NetHandlerPlayClient.handleOpenWindow} 打开，这里只负责
     * 把交互包发出去；到达的箱子界面由 {@link #onGuiOpen(GuiOpenEvent)} 记成一次会话
     * （可见模式下界面照常留在屏幕上，静默模式才收起），容器照常挂好并同步内容。
     */
    private static void maybeAutoOpen(Minecraft mc, Object player, Object screen) {
        if (!autoOpen || screen != null || chestSession) {
            return;
        }
        long now = System.nanoTime();
        // 已经发过开箱包、还在等 S2D：期间绝对不要再发。
        // 以前每 tick 都会重发一次右键包，服务端反复关旧容器开新容器，
        // 我们挂着的那个容器随即作废，搬运全部落空（日志里一秒三条 send open packet）。
        if (openPacketPending && now - lastOpenNanos <= OPEN_TIMEOUT_NANOS) {
            return;
        }
        if (openPacketPending) {
            openPacketPending = false;
            openArmed = false;
            openArmedNanos = 0L;
        }
        // 按了键但一直没开成箱（保护地皮 / 距离太远）：超时后放弃，
        // 免得之后看着别的箱子还会自己开。
        if (lootRequestNanos > 0L && now - lootRequestNanos > OPEN_TIMEOUT_NANOS) {
            lootRequested = false;
            sortRequested = false;
            storeRequested = false;
            lootRequestNanos = 0L;
            openArmed = false;
            openArmedNanos = 0L;
            return;
        }
        if (now - lastOpenNanos < OPEN_SPACING_NANOS) {
            return;
        }
        if (lastCloseNanos > 0L && now - lastCloseNanos < CLOSE_SETTLE_NANOS) {
            return;
        }
        Object hit = read(mc, "objectMouseOver", "field_71476_x", null);
        if (hit == null) {
            return;
        }
        Object blockPos = read(hit, "blockPos", "field_178783_e", null);
        Object side = read(hit, "sideHit", "field_178784_b", null);
        Object hitVec = read(hit, "hitVec", "field_72307_f", null);
        Object world = read(mc, "theWorld", "field_71441_e", null);
        Object controller = read(mc, "playerController", "field_71442_b", null);
        if (blockPos == null || side == null || world == null || controller == null) {
            return;
        }
        Object tile = tileEntityAt(world, blockPos);
        if (tile == null) {
            return;
        }
        int kind = kindOf(tile);
        if (kind != KIND_CHEST && kind != KIND_ENDER) {
            return;
        }
        Object held = call(player, "getHeldItem", "func_70694_bm", null);
        lastOpenNanos = now;
        if (lootRequestNanos <= 0L) {
            lootRequestNanos = now;
        }
        openArmed = true;
        openArmedNanos = now;
        openPacketPending = true;
        debug("send open packet (kind=" + kind + ")");
        callVoid(controller, "onPlayerRightClick", "func_78760_a", null,
                player, world, held, blockPos, side, hitVec);
    }

    /**
     * 自动扫箱：准星在一个箱子上停留 {@link #SWEEP_DWELL_TICKS} tick 就自动开箱并搬空。
     *
     * <p>不需要按键，也不用先把界面打开 —— 这是「箱子一开就自己拿」的主路径；
     * 键位触发（L / G / O）仍然可用，两者共用同一套搬运逻辑。
     */
    private static void maybeAutoSweep(Minecraft mc, Object player, Object screen) {
        if (!autoSweep || !autoOpen || screen != null || chestSession) {
            sweepTicks = 0;
            return;
        }
        long now = System.nanoTime();
        long cooldown = sweepCooldownMs > 0
                ? (long) sweepCooldownMs * 1_000_000L : SWEEP_COOLDOWN_NANOS;
        if (humanize) {
            // 关掉一个箱子到对下一个箱子动手之间，真人要转身 / 走近 / 对准，
            // 不是 500ms 精确重开。随机加 0~1300ms，两箱之间 0.5~1.8s。
            cooldown += ThreadLocalRandom.current().nextInt(0, 900) * 1_000_000L;
        }
        if (now - lastSweepNanos < cooldown) {
            return;
        }
        if (lastCloseNanos > 0L && now - lastCloseNanos < CLOSE_SETTLE_NANOS) {
            return;
        }
        Object blockPos = crosshairBlockPos(mc);
        int[] pos = blockPosCoords(blockPos);
        if (pos == null) {
            sweepTicks = 0;
            sweepX = Integer.MIN_VALUE;
            return;
        }
        if (pos[0] == sweepX && pos[1] == sweepY && pos[2] == sweepZ) {
            sweepTicks++;
        } else {
            sweepX = pos[0];
            sweepY = pos[1];
            sweepZ = pos[2];
            sweepTicks = 1;
        }
        // 刚搬空的同一个箱子：短时间内不再开。本机日志里关箱 0.66s 之后又对
        // 同一个箱子发了开箱包（窗口里 0 件物品），白花一次往返 + 一条空箱统计。
        if (lootedX != Integer.MIN_VALUE && pos[0] == lootedX && pos[1] == lootedY
                && pos[2] == lootedZ && now - lootedNanos < SWEEP_REVISIT_NANOS) {
            sweepTicks = 0;
            return;
        }
        if (sweepTicks < SWEEP_DWELL_TICKS) {
            return;
        }
        int kind = crosshairChestKind(mc, blockPos);
        if (kind != KIND_CHEST && kind != KIND_ENDER) {
            sweepTicks = 0;
            return;
        }
        lastSweepNanos = now;
        sweepTicks = 0;
        debug("auto sweep at " + pos[0] + "," + pos[1] + "," + pos[2]);
        triggerLoot();
        maybeAutoOpen(mc, player, screen);
    }

    private static int crosshairChestKind(Minecraft mc, Object blockPos) {
        Object world = read(mc, "theWorld", "field_71441_e", null);
        if (world == null || blockPos == null) {
            return -1;
        }
        Object tile = tileEntityAt(world, blockPos);
        return tile == null ? -1 : kindOf(tile);
    }

    /**
     * 当前界面是不是箱子类容器界面。
     *
     * <p>三级兜底：{@code GuiChest.lowerChestInventory} → 类名 → GUI 自己的容器槽位
     * 里是否有箱子侧。以前只看第一个字段，字段名/结构一变就整个功能静默失效。
     *
     * <p><b>背包 / 创造物品栏永远不算箱子</b>：创造模式的物品栏是
     * {@code GuiContainerCreative} + {@code ContainerCreative}，它有 45 个页签槽位，
     * 这些槽位的 {@code inventory} 是 {@code InventoryCreative}（不是玩家背包），
     * 于是「槽位 > 36 且有箱子侧」这条兜底以前会把它判成箱子。结果按 E 打开物品栏
     * 时会被静默开箱逻辑接管：容器挂上、界面被取消，玩家看到的就是「物品栏打不开」。
     */
    private static boolean isChestScreen(Object screen, Object playerInv, Object own) {
        if (screen == null) {
            return false;
        }
        String className = screen.getClass().getName().toLowerCase(Locale.ROOT);
        if (className.contains("guiinventory") || className.contains("creative")) {
            return false;
        }
        if (chestInventoryOf(screen) != null) {
            return true;
        }
        if (screenLooksLikeChest(className)) {
            return true;
        }
        Object guiContainer = read(screen, "inventorySlots", "field_147002_h", null);
        if (guiContainer == null || playerInv == null) {
            return false;
        }
        if (own != null && guiContainer == own) {
            return false;
        }
        if (isPlayerLikeContainer(guiContainer)) {
            return false;
        }
        Object guiSlots = slotListOf(guiContainer);
        return guiSlots instanceof List
                && hasChestSide((List<?>) guiSlots, playerInv)
                && ((List<?>) guiSlots).size() > 36;
    }

    /**
     * 「接管静默开箱」用的更严格判定：容器的类名必须真是箱子类（或界面带
     * {@code lowerChestInventory}）。避免任何「看起来像箱子」的界面（创造物品栏、
     * 各种模组的页签界面）被我们的静默逻辑吞掉。
     */
    private static boolean isChestGuiStrict(Object gui, Object playerInv, Object own) {
        if (gui == null) {
            return false;
        }
        Object container = read(gui, "inventorySlots", "field_147002_h", null);
        if (container == null || (own != null && container == own)) {
            return false;
        }
        // 先看「像不像箱子」：模组容器的类名常常带 Inventory（如 ContainerInventoryChest），
        // 顺序反了就会把它们和背包一起排除掉。
        String name = container.getClass().getName().toLowerCase(Locale.ROOT);
        if (name.contains("chest") || name.contains("hopper") || name.contains("shulker")
                || name.contains("dispenser") || name.contains("dropper")
                || name.contains("furnace") || name.contains("brewing")
                || name.contains("beacon")) {
            return true;
        }
        if (chestInventoryOf(gui) != null) {
            return true;
        }
        if (isPlayerLikeContainer(container)) {
            return false;
        }
        Object slots = slotListOf(container);
        return slots instanceof List
                && ((List<?>) slots).size() > 36
                && playerInv != null
                && hasChestSide((List<?>) slots, playerInv);
    }

    /** 玩家背包 / 创造物品栏这类「玩家自己的容器」——它们不是箱子。 */
    private static boolean isPlayerLikeContainer(Object container) {
        if (container == null) {
            return true;
        }
        String name = container.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return name.contains("player") || name.contains("creative") || name.contains("inventory");
    }

    /** 玩家身上挂着的容器是不是箱子侧容器（背包容器 / 合成容器一律排除）。 */
    private static boolean isChestContainer(Object container, List<?> slots,
            Object playerInv, Object own) {
        if (container == null || slots == null || playerInv == null) {
            return false;
        }
        if (own != null && container == own) {
            return false;
        }
        if (screenLooksLikeChest(container.getClass().getName())
                && !container.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("creative")) {
            return true;
        }
        if (isPlayerLikeContainer(container)) {
            return false;
        }
        return slots.size() > 36 && hasChestSide(slots, playerInv);
    }

    private static boolean screenLooksLikeChest(String className) {
        if (className == null) {
            return false;
        }
        String key = className.toLowerCase(Locale.ROOT);
        return key.contains("chest") || key.contains("hopper") || key.contains("shulker")
                || key.contains("dispenser") || key.contains("dropper")
                || key.contains("furnace") || key.contains("brewing")
                || key.contains("anvil") || key.contains("beacon");
    }

    /** 日志时间戳格式（线程安全，供 Netty / 主线程共用）。 */
    private static final java.time.format.DateTimeFormatter LOG_CLOCK =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** 诊断日志：写 stderr + /tmp/openmaver-chest.log，方便实测时定位卡在哪一步。 */
    /**
     * 复用的日志流。
     *
     * <p>以前每次 {@code debug()} 都 {@code new FileOutputStream(..., true)} + close：
     * 一串搬运（每格一行）就是几十次 open/close，主线程上白花 I/O。这里开一次、
     * 每行 flush，语义不变（崩溃前的内容照样落盘）。
     */
    private static java.io.PrintWriter logWriter;
    private static boolean logWriterFailed;

    static void debug(String message) {
        // 带毫秒时间戳：核对「开箱到第一格」「两格之间」的真实耗时全靠它。
        String line = "[OpenMaver] chest ["
                + java.time.LocalTime.now().format(LOG_CLOCK) + "] " + message;
        System.err.println(line);
        if (logWriterFailed) {
            return;
        }
        synchronized (LOG_CLOCK) {
            try {
                if (logWriter == null) {
                    logWriter = new java.io.PrintWriter(new java.io.BufferedWriter(
                            new java.io.OutputStreamWriter(
                                    new java.io.FileOutputStream("/tmp/openmaver-chest.log", true),
                                    "UTF-8")));
                }
                logWriter.println(line);
                logWriter.flush();
            } catch (Throwable ignored) {
                // 诊断日志写不进去不能影响游戏：关掉文件日志，stderr 照常。
                logWriterFailed = true;
                if (logWriter != null) {
                    logWriter.close();
                    logWriter = null;
                }
            }
        }
    }

    /**
     * 把箱子里的物品<b>从上到下</b>整摞搬进玩家背包（每一格 shift-click / mode 1）。
     *
     * <p>容器槽位顺序就是箱子格子的显示顺序（{@code ContainerChest} 先按 0..n-1 加箱子格，
     * 再加 36 格背包），所以从 {@code i = 0} 往后遍历就是玩家说的「依次从上到下」。
     *
     * <p><b>一件一件拿</b>：默认 {@code budget = 1}，一批只拿一格，拿完等服务端的窗口确认
     * （S32）回来、再隔 {@code click.gapTicks} 个 tick 才拿下一格 —— 就是人手动开箱的样子。
     * 只有关掉 ONE BY ONE 才会一批连拿 {@code clicksPerTick} 格。
     *
     * @return 本批发出的点击次数（0 表示这一轮没有任何可搬的格子）
     */
    private static int lootPass(Minecraft mc, Object player, int windowId,
            List<?> slots, Object playerInv, int budget) {
        int clicks = 0;
        rejectedLastPass = 0;
        seenLastPass = 0;
        if (playerFreeSlots(playerInv) <= 0) {
            return 0;
        }
        for (int i = 0; i < slots.size() && clicks < budget; i++) {
            Object slot = slots.get(i);
            if (slot == null) {
                continue;
            }
            Object inv = read(slot, "inventory", "field_75224_c", null);
            if (inv == null || inv == playerInv) {
                continue;
            }
            Object stack = call(slot, "getStack", "func_75211_c", null);
            if (stack == null) {
                continue;
            }
            seenLastPass++;
            if (!matchesFilter(itemKey(stack), takeFilter)) {
                rejectedLastPass++;
                continue;
            }
            // 真人偶尔会漏拿一格（手滑 / 没看见），默认 0 不启用；
            // 长时间连续搬箱时打开一点能明显打散「每格必拿」的机器统计特征。
            if (humanize && skipChancePct > 0
                    && ThreadLocalRandom.current().nextInt(100) < skipChancePct) {
                continue;
            }
            if (playerFreeSlots(playerInv) <= 0) {
                break;
            }
            int slotId = slotIdOf(slot);
            if (slotId < 0 || slotId >= slots.size()) {
                // 读不到 slotNumber 时按容器顺序兜底：原版 addSlot 就是按这个顺序编号的。
                slotId = i;
            }
            if (click(mc, player, windowId, slotId, 0, 1)) {
                clicks++;
            }
        }
        return clicks;
    }

    /**
     * 拾取过滤把箱内物品全筛掉时打一条明确日志。
     *
     * <p>以前这种情况完全静默：{@code takeFilter=2}（只拿贵重）遇上普通物资，表现就是
     * 「右键箱子，界面开着，一件都不进背包」，看起来像搬运坏了。
     */
    private static void warnFilterRejected(int windowId) {
        if (filterWarnWindow == windowId) {
            return;
        }
        filterWarnWindow = windowId;
        debug("loot: takeFilter=" + getTakeFilterName() + " 把箱子侧 " + rejectedLastPass
                + " 格物品全过滤掉了（窗口 " + windowId
                + "）；想全拿就把 chest.stealer.takeFilter 改成 0");
    }

    /** 一键存入：把背包里符合过滤条件的物品 shift-click 进箱子（同样一件一件来）。 */
    private static boolean storePass(Minecraft mc, Object player, int windowId,
            List<?> slots, Object playerInv) {
        // 存入和搬运共用本 tick 的点击配额：humanize 时一 tick 一格，掉帧时最多两格。
        int budget = clampInt(clickBudgetThisTick, 1, MAX_CLICKS_PER_TICK);
        int clicks = 0;
        if (chestFreeSlots(slots, playerInv) <= 0) {
            return false;
        }
        for (int i = 0; i < slots.size() && clicks < budget; i++) {
            Object slot = slots.get(i);
            if (slot == null) {
                continue;
            }
            Object inv = read(slot, "inventory", "field_75224_c", null);
            if (inv != playerInv) {
                continue;
            }
            Object stack = call(slot, "getStack", "func_75211_c", null);
            if (stack == null) {
                continue;
            }
            if (!matchesFilter(itemKey(stack), storeFilter)) {
                continue;
            }
            if (chestFreeSlots(slots, playerInv) <= 0) {
                break;
            }
            int slotId = slotIdOf(slot);
            if (slotId < 0 || slotId >= slots.size()) {
                continue;
            }
            if (click(mc, player, windowId, slotId, 0, 1)) {
                clicks++;
            }
        }
        return clicks > 0;
    }

    /** 整理：先合并同类堆叠，再按名称 / 数量排序。每次最多一组交换（3 次点击）。 */
    private static boolean sortPass(Minecraft mc, Object player, int windowId,
            List<?> slots, Object playerInv) {
        int cursor = releaseCursor(mc, player, windowId, slots, playerInv);
        if (cursor == 0) {
            return true;
        }
        if (cursor < 0) {
            // 背包和箱子都没有空槽，鼠标上的物品放不下去：这一轮整理直接放弃，
            // 不然会一直占着「正在整理」的状态，连自动关界面都等不到。
            sortRequested = false;
            return false;
        }
        List<SlotStack> chest = collectChestStacks(slots, playerInv);
        if (chest.size() < 2) {
            return false;
        }

        // 1) 合并同物品的零散堆叠。
        //    只在「一次点击就能并完」时动手（count 之和不超过最大堆叠数），
        //    否则合并完还留半摞，下一轮又会命中同一对、来回交换不收敛。
        for (int i = 0; i < chest.size(); i++) {
            SlotStack target = chest.get(i);
            if (target.maxStack <= 1 || target.count >= target.maxStack) {
                continue;
            }
            for (int j = i + 1; j < chest.size(); j++) {
                SlotStack source = chest.get(j);
                if (!target.key.equals(source.key)) {
                    continue;
                }
                if (target.count + source.count > target.maxStack) {
                    continue;
                }
                return swapStacks(mc, player, windowId, source.slotId, target.slotId);
            }
        }

        // 2) 按名称 + 数量排序
        List<SlotStack> desired = new ArrayList<SlotStack>(chest);
        Collections.sort(desired, SORT_ORDER);
        for (int i = 0; i < chest.size(); i++) {
            SlotStack current = chest.get(i);
            if (current.key.equals(desired.get(i).key)) {
                continue;
            }
            for (int j = i + 1; j < chest.size(); j++) {
                if (!chest.get(j).key.equals(desired.get(i).key)) {
                    continue;
                }
                return swapStacks(mc, player, windowId, chest.get(j).slotId, current.slotId);
            }
        }
        return false;
    }

    /** 交换两个箱子槽位：拿起 → 放下 → 把原槽位剩下的放回去。 */
    private static boolean swapStacks(Minecraft mc, Object player, int windowId,
            int from, int to) {
        if (!click(mc, player, windowId, from, 0, 0)) {
            return false;
        }
        if (!click(mc, player, windowId, to, 0, 0)) {
            return false;
        }
        return click(mc, player, windowId, from, 0, 0);
    }

    /**
     * 鼠标上如果拿着东西，先放进背包空槽（背包满了再退回箱子空槽），
     * 避免整理过程中把物品留在光标上、关界面时掉在地上。
     *
     * @return {@code 1} 光标已空，可以继续整理；{@code 0} 本 tick 已经点了一次；
     *         {@code -1} 没有任何空槽，放不下鼠标上的物品
     */
    private static int releaseCursor(Minecraft mc, Object player, int windowId,
            List<?> slots, Object playerInv) {
        Object cursor = call(playerInv, "getItemStack", "func_70445_o", null);
        if (cursor == null) {
            return 1;
        }
        int target = firstEmptySlot(slots, playerInv, playerInv);
        if (target < 0) {
            target = firstEmptySlot(slots, playerInv, null);
        }
        if (target < 0) {
            return -1;
        }
        click(mc, player, windowId, target, 0, 0);
        return 0;
    }

    /**
     * 找一个空槽的 container slot id。
     *
     * @param owner 传入 {@code playerInv} 表示只找背包侧，传 null 表示只找箱子侧
     */
    private static int firstEmptySlot(List<?> slots, Object playerInv, Object owner) {
        for (int i = 0; i < slots.size(); i++) {
            Object slot = slots.get(i);
            if (slot == null) {
                continue;
            }
            Object inv = read(slot, "inventory", "field_75224_c", null);
            if (inv == null) {
                continue;
            }
            if (owner == null ? (inv == playerInv) : (inv != owner)) {
                continue;
            }
            if (call(slot, "getStack", "func_75211_c", null) != null) {
                continue;
            }
            int slotId = slotIdOf(slot);
            if (slotId >= 0 && slotId < slots.size()) {
                return slotId;
            }
        }
        return -1;
    }

    private static final class SlotStack {
        final int slotId;
        final String key;
        final int count;
        /** 该物品的最大堆叠数；读不到时为 -1（不参与合并）。 */
        final int maxStack;

        SlotStack(int slotId, String key, int count, int maxStack) {
            this.slotId = slotId;
            this.key = key;
            this.count = count;
            this.maxStack = maxStack;
        }
    }

    private static final Comparator<SlotStack> SORT_ORDER = new Comparator<SlotStack>() {
        public int compare(SlotStack a, SlotStack b) {
            int byKey = a.key.compareToIgnoreCase(b.key);
            if (byKey != 0) {
                return byKey;
            }
            return b.count - a.count;
        }
    };

    private static List<SlotStack> collectChestStacks(List<?> slots, Object playerInv) {
        List<SlotStack> chest = new ArrayList<SlotStack>();
        for (int i = 0; i < slots.size(); i++) {
            Object slot = slots.get(i);
            if (slot == null) {
                continue;
            }
            Object inv = read(slot, "inventory", "field_75224_c", null);
            if (inv == null || inv == playerInv) {
                continue;
            }
            Object stack = call(slot, "getStack", "func_75211_c", null);
            if (stack == null) {
                continue;
            }
            int count = intOf(read(stack, "stackSize", "field_77994_a", null));
            if (count <= 0) {
                continue;
            }
            // key 里带上伤害值：两把耐久不同的剑不是同一种「可合并物品」，
            // 否则合并阶段会来回交换、永远收敛不了。
            String base = itemKey(stack);
            int damage = intOf(call(stack, "getItemDamage", "func_77960_j", null));
            String key = (base == null ? "" : base) + "#" + damage;
            int maxStack = intOf(call(stack, "getMaxStackSize", "func_77976_d", null));
            chest.add(new SlotStack(slotIdOf(slot), key, count, maxStack));
        }
        Collections.sort(chest, new Comparator<SlotStack>() {
            public int compare(SlotStack a, SlotStack b) {
                return a.slotId - b.slotId;
            }
        });
        return chest;
    }

    private static int slotIdOf(Object slot) {
        return intOf(read(slot, "slotNumber", "field_75238_b", null));
    }

    /** 一次 windowClick：mode 0 = 普通点击，mode 1 = shift-click（整摞搬运）。 */
    private static boolean click(Minecraft mc, Object player, int windowId,
            int slotId, int button, int mode) {
        // 本 tick 的点击配额在 tick 开头按节拍算好（见 clicksDueNow）：同一 tick 里
        // 绝不偷偷多点，掉帧时最多补到 MAX_CLICKS_PER_TICK 个。
        if (clickBudgetThisTick <= 0) {
            return false;
        }
        Object controller = read(mc, "playerController", "field_71442_b", null);
        if (controller == null) {
            return false;
        }
        boolean ok = callVoid(controller, "windowClick", "func_78753_a", null,
                Integer.valueOf(windowId), Integer.valueOf(slotId),
                Integer.valueOf(button), Integer.valueOf(mode), player);
        if (!ok && !clickWarned) {
            clickWarned = true;
            debug("windowClick unavailable on " + controller.getClass().getName()
                    + " — 搬运无法进行（反射签名对不上）");
        }
        if (ok) {
            clickBudgetThisTick--;
            lastClickTick = clientTicks;
            lastClickNanos = System.nanoTime();
            sessionClicks++;
            if (sessionFirstClickNanos == 0L) {
                sessionFirstClickNanos = lastClickNanos;
            }
            scheduleNextClick(lastClickNanos);
        }
        return ok;
    }

    /**
     * 排下一次点击的节拍。
     *
     * <p>humanize：随机 100~160ms 一格（{@code clickDelayMinMs/MaxMs}，硬下限
     * {@link #MIN_CLICK_INTERVAL_MS}），每连续 4 格之后才有 {@code pauseChancePct}%
     * 概率插一段 200~380ms 的停顿（「手停一下」）。间隔刻意不落在 50ms 的整数倍上，
     * 所以每次点击落在哪个 tick 都不固定，既打散「每 tick 一格」的等距指纹，
     * 也不会出现「一串 40ms 连点」那种爆发形态。
     *
     * <p>关掉 humanize：恢复旧的 tick 节奏（{@code clickGapTicks} 控制）。
     */
    private static void scheduleNextClick(long clickedAt) {
        if (!humanize) {
            clickIntervalNanos = 0L;
            nextClickNanos = clickedAt;
            lastScheduledDelayMs = 0;
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int span = Math.max(1, clickDelayMaxMs - clickDelayMinMs + 1);
        int delay = Math.max(MIN_CLICK_INTERVAL_MS, clickDelayMinMs + random.nextInt(0, span));
        clickStreak++;
        if (clickStreak >= 4 && random.nextInt(100) < pauseChancePct) {
            int pauseSpan = Math.max(1, pauseMaxMs - pauseMinMs + 1);
            delay += pauseMinMs + random.nextInt(0, pauseSpan);
            clickStreak = 0;
        }
        clickIntervalNanos = (long) delay * 1_000_000L;
        nextClickNanos = clickedAt + clickIntervalNanos;
        lastScheduledDelayMs = delay;
    }

    private static int playerFreeSlots(Object playerInv) {
        Object array = read(playerInv, "mainInventory", "field_70462_a", null);
        if (array instanceof Object[]) {
            Object[] items = (Object[]) array;
            int free = 0;
            for (int i = 0; i < items.length; i++) {
                if (items[i] == null) {
                    free++;
                }
            }
            return free;
        }
        int size = Math.min(36, intOf(call(playerInv, "getSizeInventory", "func_70302_i_", null)));
        int free = 0;
        for (int i = 0; i < size; i++) {
            if (call(playerInv, "getStackInSlot", "func_70301_a", null, Integer.valueOf(i)) == null) {
                free++;
            }
        }
        return free;
    }

    private static int chestFreeSlots(List<?> slots, Object playerInv) {
        int free = 0;
        for (int i = 0; i < slots.size(); i++) {
            Object slot = slots.get(i);
            if (slot == null) {
                continue;
            }
            Object inv = read(slot, "inventory", "field_75224_c", null);
            if (inv == null || inv == playerInv) {
                continue;
            }
            if (call(slot, "getStack", "func_75211_c", null) == null) {
                free++;
            }
        }
        return free;
    }

    /** 容器的槽位里是否存在「不是玩家背包」的一侧（箱子侧）。 */
    private static boolean hasChestSide(List<?> slots, Object playerInv) {
        for (int i = 0; i < slots.size(); i++) {
            Object slot = slots.get(i);
            if (slot == null) {
                continue;
            }
            Object inv = read(slot, "inventory", "field_75224_c", null);
            if (inv != null && inv != playerInv) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 物品过滤
    // ==================================================================

    /** 垃圾物品（按 unlocalizedName 片段匹配）。 */
    private static final String[] JUNK = {
            "tile.dirt", "tile.stone", "tile.cobblestone", "tile.gravel", "tile.sand",
            "tile.clay", "tile.snow", "tile.sandStone", "tile.hardenedClay",
            "tile.stainedHardenedClay", "tile.grass", "tile.mycelium", "tile.sapling",
            "tile.leaves", "tile.tallgrass", "tile.deadbush", "tile.flower", "tile.plant",
            "tile.vine", "tile.web", "tile.wood", "tile.log", "tile.planks",
            "tile.stonebrick", "tile.mossy", "tile.netherrack", "tile.end_stone",
            "tile.oreCoal", "tile.oreIron", "tile.oreGold", "tile.oreRedstone",
            "item.flint", "item.stick", "item.paper", "item.sugar", "item.egg",
            "item.seeds", "item.wheat", "item.bone", "item.string", "item.spiderEye",
            "item.rottenFlesh", "item.feather", "item.leather", "item.snowball",
            "item.slimeBall", "item.gunpowder", "item.dyePowder", "item.clay"
    };

    /** 贵重物品（按 unlocalizedName 片段匹配）。 */
    private static final String[] VALUABLE = {
            "diamond", "emerald", "goldIngot", "goldNugget", "ironIngot", "iron_block",
            "lapis", "quartz", "obsidian", "enderpearl", "enderPearl", "netherStar",
            "enchant", "enchantedBook", "potion", "arrow", "tnt", "anvil", "shulker",
            "totem", "expBottle", "blazeRod", "glowstone", "ghastTear", "prismarine"
    };

    private static boolean matchesFilter(String unlocalized, int filter) {
        String key = unlocalized == null ? "" : unlocalized.toLowerCase(Locale.ROOT);
        if (filter == 1) {
            return !containsAny(key, JUNK);
        }
        if (filter == 2) {
            return containsAny(key, VALUABLE);
        }
        return true;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (int i = 0; i < needles.length; i++) {
            if (haystack.contains(needles[i].toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String itemKey(Object stack) {
        return stripColors(asString(call(stack, "getUnlocalizedName", "func_77977_a", null)));
    }

    // ==================================================================
    // 存储方块 ESP
    // ==================================================================

    private static void updateEspWorld(Object world) {
        if (world != espWorld) {
            espWorld = world;
            openedChests.clear();
        }
    }

    private static String chestKey(int[] pos) {
        return pos[0] + ":" + pos[1] + ":" + pos[2];
    }

    private static boolean markOpenedInventory(Object inventory, int depth) {
        if (inventory == null || depth > 2) return false;
        int[] pos = positionOf(inventory);
        if (pos != null && kindOf(inventory) >= 0) {
            openedChests.add(chestKey(pos));
            return true;
        }
        // InventoryLargeChest wraps both halves; mark each tile, including the second half.
        boolean found = false;
        for (String[] names : new String[][] {{"upperChest", "field_70477_b"}, {"lowerChest", "field_70478_c"}}) {
            found |= markOpenedInventory(read(inventory, names[0], names[1], null), depth + 1);
        }
        return found;
    }

    private static void trackOpenedChest(Minecraft mc, Object gui) {
        if (mc == null) return;
        Object world = read(mc, "theWorld", "field_71441_e", null);
        updateEspWorld(world);
        if (world == null) return;
        Object inventory = chestInventoryOf(gui);
        if (inventory == null) return;
        if (markOpenedInventory(inventory, 0)) return;
        // Ender chest inventories have no position; resolve the block being opened.
        Object hit = read(mc, "objectMouseOver", "field_71476_x", null);
        Object blockPos = call(hit, "getBlockPos", "func_178782_a", null);
        if (blockPos == null) blockPos = read(hit, "blockPos", "field_178783_e", null);
        Object tile = tileEntityAt(world, blockPos);
        int kind = kindOf(tile);
        int[] pos = positionOf(tile);
        if (pos != null && (kind == KIND_CHEST || kind == KIND_ENDER)) openedChests.add(chestKey(pos));
    }

    /** World-space wireframes or blue translucent fills for unopened storage. */
    public static void onRenderWorld(RenderWorldLastEvent event) {
        if (espFailed || !isEspEnabled()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            Object world = read(mc, "theWorld", "field_71441_e", null);
            Object player = read(mc, "thePlayer", "field_71439_g", null);
            if (world == null || player == null) {
                return;
            }
            updateEspWorld(world);
            List<?> tiles = worldTileEntities(world);
            if (tiles == null || tiles.isEmpty()) {
                return;
            }
            Object renderManager = read(mc, "renderManager", "field_175616_W", null);
            double cameraX = number(renderManager, "viewerPosX", "field_78725_b");
            double cameraY = number(renderManager, "viewerPosY", "field_78726_c");
            double cameraZ = number(renderManager, "viewerPosZ", "field_78723_d");
            double playerX = number(player, "posX", "field_70165_t");
            double playerY = number(player, "posY", "field_70163_u");
            double playerZ = number(player, "posZ", "field_70161_v");
            double rangeSq = (double) espRange * (double) espRange;
            float[] rgb = ESP_COLORS[clamp(espColor, 0, ESP_COLORS.length - 1)];

            GlColor.restore();
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT
                    | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(espThickness);
            GlColor.set(rgb[0], rgb[1], rgb[2], espOpacity);

            if (!espFullColor) GL11.glBegin(GL11.GL_LINES);
            for (int i = 0; i < tiles.size(); i++) {
                Object tile = tiles.get(i);
                if (tile == null) {
                    continue;
                }
                int kind = kindOf(tile);
                if (kind < 0 || (!espStorageBlocks && kind >= KIND_HOPPER)) {
                    continue;
                }
                int[] pos = positionOf(tile);
                if (pos == null) {
                    continue;
                }
                if ((kind == KIND_CHEST || kind == KIND_ENDER)
                        && (number(tile, "lidAngle", "field_145989_m") > 0
                        || number(tile, "numPlayersUsing", "field_145987_o") > 0)) {
                    openedChests.add(chestKey(pos));
                }
                double dx = pos[0] + 0.5D - playerX;
                double dy = pos[1] + 0.5D - playerY;
                double dz = pos[2] + 0.5D - playerZ;
                if (dx * dx + dy * dy + dz * dz > rangeSq) {
                    continue;
                }
                if (espFullColor) {
                    if (openedChests.contains(chestKey(pos))) continue;
                    double inset = kind == KIND_CHEST || kind == KIND_ENDER ? 0.0625D : 0;
                    FilledBox.draw(pos[0] + inset - cameraX, pos[1] - cameraY, pos[2] + inset - cameraZ,
                            pos[0] + 1 - inset - cameraX, pos[1] + 1 - inset - cameraY,
                            pos[2] + 1 - inset - cameraZ, 0.08F, 0.48F, 1.0F, espOpacity * 0.45F, true);
                } else {
                    boxLines(pos[0] - cameraX, pos[1] - cameraY, pos[2] - cameraZ,
                            pos[0] + 1 - cameraX, pos[1] + 1 - cameraY, pos[2] + 1 - cameraZ);
                }
            }
            if (!espFullColor) GL11.glEnd();

            GL11.glPopAttrib();
            GlColor.restore();
        } catch (Throwable t) {
            espFailed = true;
            System.err.println("[OpenMaver] Chest ESP disabled: " + t);
        }
    }

    private static void boxLines(double x1, double y1, double z1,
            double x2, double y2, double z2) {
        line(x1, y1, z1, x2, y1, z1);
        line(x2, y1, z1, x2, y1, z2);
        line(x2, y1, z2, x1, y1, z2);
        line(x1, y1, z2, x1, y1, z1);
        line(x1, y2, z1, x2, y2, z1);
        line(x2, y2, z1, x2, y2, z2);
        line(x2, y2, z2, x1, y2, z2);
        line(x1, y2, z2, x1, y2, z1);
        line(x1, y1, z1, x1, y2, z1);
        line(x2, y1, z1, x2, y2, z1);
        line(x2, y1, z2, x2, y2, z2);
        line(x1, y1, z2, x1, y2, z2);
    }

    private static void line(double x1, double y1, double z1,
            double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
    }

    // ==================================================================
    // 反射读取 / 写入
    // ==================================================================

    /**
     * 字段名兜底：直接按「字段类型名」找。
     *
     * <p>Forge 1.8.9 运行期用 SRG 名，这里再退一层类型名（{@code Container} /
     * {@code IInventory} / {@code BlockPos}），即使某个版本的成员名对不上，
     * 功能也只是降级而不会整体失效。
     */
    /**
     * 从 {@code NetHandlerPlayClient} 里找 {@code NetworkManager}。
     *
     * <p>运行期 {@code NetworkManager} 是 SRG 混淆名（1.8.9 里叫 {@code ek} 一类），
     * 所以不能按类名匹配 —— 以前的 {@code fieldByTypeName(netHandler,
     * "net.minecraft.network.NetworkManager")} 比的是 {@code getSimpleName()}，
     * 永远匹配不上，日志里那句
     * {@code packet hook unavailable: NetHandlerPlayClient ... 找不到 NetworkManager 字段}
     * 就是这么来的；包钩子一挂不上，S30 / S32 记账全部失效，搬运只能按 tick 估算。
     *
     * <p>这里先按 MCP / SRG 字段名直取，再退化为「哪个字段的对象身上挂着
     * {@code io.netty.channel.Channel}」——{@code Channel} 是 netty 的类，
     * 不会被 Forge 的程序混淆碰到，这条兜底在任何 1.8.9 客户端上都成立。
     */
    private static Object networkManagerOf(Object netHandler) {
        if (netHandler == null) {
            return null;
        }
        Object direct = read(netHandler, "netManager", "field_147299_f", null);
        if (direct != null && channelOf(direct) != null) {
            return direct;
        }
        for (Class<?> type = netHandler.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (int i = 0; i < fields.length; i++) {
                try {
                    fields[i].setAccessible(true);
                    Object value = fields[i].get(netHandler);
                    if (value != null && channelOf(value) != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // 静态字段 / 不可访问字段直接跳过。
                }
            }
        }
        return null;
    }

    /** 对象身上挂着的 {@code io.netty.channel.Channel}（{@code NetworkManager.channel}）。 */
    private static Object channelOf(Object target) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].getType() != io.netty.channel.Channel.class) {
                    continue;
                }
                try {
                    fields[i].setAccessible(true);
                    Object value = fields[i].get(target);
                    if (value instanceof io.netty.channel.Channel) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // 继续找下一个 Channel 字段。
                }
            }
        }
        return null;
    }

    private static Object fieldByTypeName(Object target, String simpleTypeName) {
        if (target == null || simpleTypeName == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (int i = 0; i < fields.length; i++) {
                if (!simpleTypeName.equals(fields[i].getType().getSimpleName())) {
                    continue;
                }
                try {
                    fields[i].setAccessible(true);
                    Object value = fields[i].get(target);
                    if (value != null) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // 继续找下一个同类型字段。
                }
            }
        }
        return null;
    }

    /** 按 MCP / SRG 双名写一个字段（找不到就返回 false，绝不抛异常）。 */
    private static boolean writeField(Object target, String mcp, String srg, Object value) {
        if (target == null) {
            return false;
        }
        String[] names = {mcp, srg};
        for (int n = 0; n < names.length; n++) {
            String name = names[n];
            if (name == null || name.isEmpty()) {
                continue;
            }
            for (Class<?> type = target.getClass(); type != null && type != Object.class;
                    type = type.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(target, value);
                    return true;
                } catch (NoSuchFieldException ignored) {
                    // 继续往父类 / 下一个名字找。
                } catch (Throwable ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    /** 箱子界面下方那个箱子容器（{@code GuiChest.lowerChestInventory}）。 */
    private static Object chestInventoryOf(Object screen) {
        Object lower = read(screen, "lowerChestInventory", "field_147015_w", null);
        if (lower != null) {
            return lower;
        }
        return fieldByTypeName(screen, "IInventory");
    }

    /** 玩家背包（{@code EntityPlayer.inventory}），字段名对不上时按类型名兜底。 */
    private static Object playerInventoryOf(Object player) {
        Object inventory = read(player, "inventory", "field_71071_by", null);
        if (inventory != null) {
            return inventory;
        }
        return fieldByTypeName(player, "InventoryPlayer");
    }

    /**
     * 玩家自己的背包容器（{@code EntityPlayer.inventoryContainer}）。
     *
     * <p>兜底时必须排除 {@code openContainer}，否则两者会指向同一个对象，
     * 让「箱子容器」判定永远成立不了（正是「箱子开了却不搬」的一种成因）。
     */
    private static Object playerInventoryContainerOf(Object player) {
        if (player == null) {
            return null;
        }
        Object container = read(player, "inventoryContainer", "field_71069_bz", null);
        if (container != null) {
            return container;
        }
        Object open = read(player, "openContainer", "field_71070_bA", null);
        for (Class<?> type = player.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (int i = 0; i < fields.length; i++) {
                if (!"Container".equals(fields[i].getType().getSimpleName())) {
                    continue;
                }
                try {
                    fields[i].setAccessible(true);
                    Object value = fields[i].get(player);
                    if (value != null && value != open) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // 继续找下一个 Container 字段。
                }
            }
        }
        return null;
    }

    /** 容器的槽位列表（{@code Container.inventorySlots}），按元素类型名兜底。 */
    private static Object slotListOf(Object container) {
        Object slots = read(container, "inventorySlots", "field_75151_b", List.class);
        if (slots instanceof List) {
            return slots;
        }
        for (Class<?> type = container.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (int i = 0; i < fields.length; i++) {
                if (!List.class.isAssignableFrom(fields[i].getType())) {
                    continue;
                }
                try {
                    fields[i].setAccessible(true);
                    Object value = fields[i].get(container);
                    if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
                        continue;
                    }
                    Object first = ((List<?>) value).get(0);
                    if (first != null && first.getClass().getSimpleName().contains("Slot")) {
                        return value;
                    }
                } catch (Throwable ignored) {
                    // 继续找下一个 List 字段。
                }
            }
        }
        return null;
    }

    /** 玩家当前打开的容器（{@code EntityPlayer.openContainer}）。 */
    private static Object openContainerOf(Object player) {
        Object container = read(player, "openContainer", "field_71070_bA", null);
        if (container != null) {
            return container;
        }
        return fieldByTypeName(player, "Container");
    }

    /** 世界里的 tile entity 列表（{@code World.loadedTileEntityList}）。 */
    private static List<?> worldTileEntities(Object world) {
        Object list = read(world, "loadedTileEntityList", "field_147482_g", List.class);
        if (list instanceof List) {
            return (List<?>) list;
        }
        for (Class<?> type = world.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (int i = 0; i < fields.length; i++) {
                if (!List.class.isAssignableFrom(fields[i].getType())) {
                    continue;
                }
                try {
                    fields[i].setAccessible(true);
                    Object value = fields[i].get(world);
                    if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
                        continue;
                    }
                    Object first = ((List<?>) value).get(0);
                    if (first != null && first.getClass().getName().contains("TileEntity")) {
                        return (List<?>) value;
                    }
                } catch (Throwable ignored) {
                    // 继续找下一个 List 字段。
                }
            }
        }
        return null;
    }

    /** 某个方块坐标上的 tile entity；{@code getTileEntity} 不可用时退化为扫列表比对。 */
    private static Object tileEntityAt(Object world, Object blockPos) {
        Object direct = call(world, "getTileEntity", "func_175625_s", null, blockPos);
        if (direct != null) {
            return direct;
        }
        int[] target = blockPosCoords(blockPos);
        if (target == null) {
            return null;
        }
        List<?> tiles = worldTileEntities(world);
        if (tiles == null) {
            return null;
        }
        for (int i = 0; i < tiles.size(); i++) {
            Object tile = tiles.get(i);
            if (tile == null) {
                continue;
            }
            int[] pos = positionOf(tile);
            if (pos != null && pos[0] == target[0] && pos[1] == target[1] && pos[2] == target[2]) {
                return tile;
            }
        }
        return null;
    }

    private static Object read(Object target, String mcp, String srg, Class<?> expected) {
        return OpenMaverFeatures.read(target, mcp, srg, expected);
    }

    private static Object call(Object target, String mcp, String srg, String obf,
            Object... args) {
        return OpenMaverFeatures.call(target, mcp, srg, obf, args);
    }

    private static boolean callVoid(Object target, String mcp, String srg, String obf,
            Object... args) {
        return OpenMaverFeatures.callVoid(target, mcp, srg, obf, args);
    }

    private static double number(Object target, String mcp, String srg) {
        return OpenMaverFeatures.number(target, mcp, srg);
    }

    private static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    // ==================================================================
    // 容器 / 方块工具
    // ==================================================================

    /** 按 IInventory.getName() 返回的 "container.chest" 这类键判断存储类型。 */
    private static int kindOf(Object tileEntity) {
        Class<?> type = tileEntity.getClass();
        Integer cached = KIND_CACHE.get(type);
        if (cached != null) {
            return cached.intValue();
        }
        int kind = -1;
        String name = inventoryName(tileEntity);
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String simple = type.getSimpleName().toLowerCase(Locale.ROOT);
        if (key.contains("ender") || simple.contains("enderchest")) {
            kind = KIND_ENDER;
        } else if (key.contains("chest") || simple.contains("chest")) {
            kind = KIND_CHEST;
        } else if (key.contains("hopper") || simple.contains("hopper")) {
            kind = KIND_HOPPER;
        } else if (key.contains("furnace") || key.contains("dispenser")
                || key.contains("dropper") || key.contains("brewing")
                || simple.contains("furnace") || simple.contains("dispenser")
                || simple.contains("dropper") || simple.contains("brewing")) {
            kind = KIND_MACHINE;
        }
        KIND_CACHE.put(type, Integer.valueOf(kind));
        return kind;
    }

    private static String inventoryName(Object inventory) {
        Object value = call(inventory, "getName", "func_70313_b", null);
        return asString(value);
    }

    /** TileEntity.getPos() → 方块坐标；大箱子（InventoryLargeChest）会向下拆一层。 */
    private static int[] positionOf(Object tileEntity) {
        Object pos = call(tileEntity, "getPos", "func_174877_v", null);
        if (pos == null) {
            pos = fieldByTypeName(tileEntity, "BlockPos");
        }
        return blockPosCoords(pos);
    }

    private static int[] blockPosCoords(Object blockPos) {
        if (blockPos == null) {
            return null;
        }
        int x = intOf(call(blockPos, "getX", "func_177958_n", null));
        int y = intOf(call(blockPos, "getY", "func_177956_o", null));
        int z = intOf(call(blockPos, "getZ", "func_177952_p", null));
        if (x == -1 && y == -1 && z == -1) {
            return null;
        }
        return new int[]{x, y, z};
    }

    /** 准星命中的方块坐标。 */
    private static Object crosshairBlockPos(Minecraft mc) {
        Object hit = read(mc, "objectMouseOver", "field_71476_x", null);
        if (hit == null) {
            return null;
        }
        Object type = read(hit, "typeOfHit", "field_72313_a", null);
        if (type == null || !"BLOCK".equals(String.valueOf(type))) {
            return null;
        }
        return read(hit, "blockPos", "field_178783_e", null);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static String stripColors(String text) {
        if (text == null) {
            return null;
        }
        if (text.indexOf('\u00A7') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7') {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean readBoolean(Properties values, String key, boolean fallback) {
        String raw = values.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static float readFloat(Properties values, String key, float fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
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

    private static String readKey(Properties values, String key, String fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        String cleaned = raw.trim();
        if (cleaned.length() > 1) {
            cleaned = cleaned.substring(0, 1);
        }
        return cleaned.isEmpty() ? "" : cleaned.toUpperCase(Locale.ROOT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
