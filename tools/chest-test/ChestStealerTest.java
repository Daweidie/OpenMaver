import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiChestCompat;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerCreative;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.client.event.GuiOpenEvent;

import module.ModuleManager;
import module.player.ChestStealer;

import java.util.List;

/**
 * 箱子搬运回归测试（无需启动游戏）。
 *
 * <p>验证的不变量：
 * <ol>
 *   <li><b>可见模式（默认）</b>：自动扫箱 / 拾取键开箱后，原版 {@code GuiChest}
 *       <b>留在屏幕上</b>，搬运就在这个界面上完成，拿完再发 {@code C0D} 关窗退出；</li>
 *   <li>要等服务端的窗口物品包（S30）到过这个 {@code windowId} 才动手点格子；</li>
 *   <li>两批 windowClick 之间按 {@code CLICK GAP} 隔开，不在一 tick 里连点一串；</li>
 *   <li>界面被玩家关掉（服务端容器还挂着）时立刻发 {@code C0D} 收摊，不再盲点；</li>
 *   <li><b>静默模式</b>（{@code SHOW CHEST GUI=off}）：界面在同一 tick 内被收起，
 *       容器 / {@code windowId} 照常可用，搬完自动发 {@code C0D}；</li>
 *   <li>对着箱子按拾取键 → 只发一次右键开箱包（等窗口期间不重发）；</li>
 *   <li>搬运按 TAKE 过滤只拿想要的，垃圾留在箱子里；点击用的必须是服务端的 windowId；</li>
 *   <li>玩家自己开的箱子界面（autoLoot）照常搬运；AUTO CLOSE 关掉时没搬成东西也不会被关；</li>
 *   <li>容器开着时打开别的界面，会先把容器关掉；</li>
 *   <li>创造模式物品栏（{@code GuiContainerCreative}）绝不能被当成箱子接管。</li>
 * </ol>
 */
public final class ChestStealerTest {

    private static int passed;

    public static void main(String[] args) {
        System.setProperty("openmaver.chest", System.getProperty("java.io.tmpdir")
                + "/openmaver-chest-test-" + System.nanoTime() + ".properties");

        ChestStealer stealer = new ChestStealer();
        ModuleManager.register(stealer);
        stealer.setEnabled(true);
        // 老断言按 tick 数推进（不睡眠），所以先在机械节奏下跑；
        // 人类化节拍单独在 humanizedPacingAvoidsMachineRhythm() 里用真实时间验证。
        ChestFeature.setHumanize(false);

        try {
            disconnectedGuiWithNoPlayer();
            visibleSweepKeepsGuiThenCloses();
            windowItemsPacketGatesClicks();
            localContentSkipsMissingS30();
            clickIntervalFloorIsEnforced();
            lootedChestIsNotSweptAgainImmediately();
            clicksAreSpacedByGap();
            takesOneStackPerBatch();
            lootsOneStackPerTickWhenConfirmed();
            closingGuiMidwayStopsTheStealer();
            silentLootNeverShowsGui();
            silentLootWorksWithoutPreviewField();
            sleepsWithoutResendingOpenPacket();
            manualChestGuiStillLoots();
            secondChestIsTakenOverInsteadOfClosed();
            staleCloseWindowIsFiltered();
            junkOnlyChestIsNotAutoClosed();
            visibleGuiWithoutPreviewFieldStillLoots();
            silentSweepOpensAndLootsWithoutKey();
            visibleChestEmptiesTopToBottom();
            lateContentsAreStillTaken();
            filteredChestStillClosesSilentContainer();
            creativeInventoryIsNotHijacked();
            otherScreenClosesSilentContainer();
            humanizedPacingAvoidsMachineRhythm();
            System.out.println("[chest-test] OK (" + passed + " 项断言)");
        } catch (AssertionError error) {
            System.err.println("[chest-test] FAIL: " + error.getMessage());
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void disconnectedGuiWithNoPlayer() {
        Fixture f = new Fixture();
        f.mc.thePlayer = null;
        f.mc.theWorld = null;
        GuiScreen disconnected = new GuiScreen();
        f.mc.displayGuiScreen(disconnected);
        check("断线后空 player 的 GUI 正常打开", f.mc.currentScreen == disconnected);
        check("断线 GUI 没有触发容器点击", f.controller.clicks == 0);
        ChestFeature.reset();
    }

    // ------------------------------------------------------------------
    // 默认路径：可见界面
    // ------------------------------------------------------------------

    /**
     * 主路径：自动扫箱 → 原版箱子界面留在屏幕上 → 在里面 shift-click 搬完 → 关箱退出。
     *
     * <p>这就是玩家要的形态：右键打开箱子，界面照常显示，东西一格一格进背包，
     * 拿完像按 E 一样退出。反作弊看到的也是「玩家开着箱子在拿东西」，
     * 而不是「没有界面却在点窗口」。
     */
    private static void visibleSweepKeepsGuiThenCloses() {
        configure(false, true, 2, 2);
        sleepQuietly(700L);
        Fixture f = new Fixture();
        f.chest.slots[0] = new ItemStack("item.diamond", 3);
        f.chest.slots[4] = new ItemStack("item.emerald", 1);
        f.installServerOpenSynced();

        // 准星停留 SWEEP_DWELL_TICKS 后自动开箱
        for (int i = 0; i < 5; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("自动扫箱发出了右键开箱包", f.controller.openCalls == 1);
        check("原版箱子界面留在屏幕上（没有被收起）", f.mc.currentScreen instanceof GuiChest);
        check("界面挂的是服务端那个容器", f.player.openContainer == f.container);

        // 开窗后的固定延迟（OPEN DELAY）里一格都不点：界面得先挂上、玩家先「看到」箱子
        ChestFeature.onTick(f.mc);
        check("开窗延迟里不点格子", f.controller.clicks == 0);
        check("界面一直开着", f.mc.currentScreen instanceof GuiChest);

        // 延迟走完 + 内容已同步 → 开始搬
        ChestFeature.onTick(f.mc);
        check("延迟走完后开始搬", f.controller.clicks > 0);
        check("搬运时原版箱子界面还开着", f.mc.currentScreen instanceof GuiChest);
        check("点击打在服务端的 windowId 上",
                f.controller.clickWindowIds.contains(Integer.valueOf(f.container.windowId)));
        check("确实用 shift-click 模式搬运", !f.controller.movedItems.isEmpty());

        for (int i = 0; i < 20; i++) {
            ChestFeature.onTick(f.mc);
            ChestFeature.noteInboundPacket(new S32PacketConfirmTransaction(f.container.windowId));
        }
        check("东西搬走了", contains(f.player.inventory.mainInventory, "item.diamond")
                && contains(f.player.inventory.mainInventory, "item.emerald"));
        check("箱子空了", f.chest.slots[0] == null && f.chest.slots[4] == null);
        check("拿完界面关掉（退出箱子）", f.mc.currentScreen == null);
        check("关窗包用的是服务端 windowId", f.closeWindowId() == f.container.windowId);
        check("openContainer 换回背包容器", f.player.openContainer == f.player.inventoryContainer);
        ChestFeature.reset();
    }

    /** 内容同步门：S30 没到就不点；到了才点（不靠「猜」等几个 tick）。 */
    private static void windowItemsPacketGatesClicks() {
        configure(false, true, 3, 2);
        // 把上一个用例的窗口号清掉：用一个绝不会命中的窗口号占位。
        ChestFeature.noteInboundPacket(new S30PacketWindowItems(9999));
        sleepQuietly(700L);
        Fixture f = new Fixture();
        f.installServerOpen();

        for (int i = 0; i < 5; i++) {
            ChestFeature.onTick(f.mc);
        }
        for (int i = 0; i < 8; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("S30 之前不点格子", f.controller.clicks == 0);
        check("S30 之前箱子界面照常开着", f.mc.currentScreen instanceof GuiChest);

        // 真实客户端里箱子内容只随 S30 落到容器（箱子方块本身不同步内容）。
        f.chest.slots[1] = new ItemStack("item.diamond", 1);
        ChestFeature.noteInboundPacket(new S30PacketWindowItems(f.container.windowId));
        ChestFeature.onTick(f.mc);
        check("S30 到达后立刻开始搬", f.controller.clicks > 0);

        for (int i = 0; i < 25; i++) {
            ChestFeature.onTick(f.mc);
            ChestFeature.noteInboundPacket(new S32PacketConfirmTransaction(f.container.windowId));
        }
        check("搬完照常退出", f.mc.currentScreen == null
                && contains(f.player.inventory.mainInventory, "item.diamond"));
        ChestFeature.reset();
    }

    /**
     * 包钩子晚挂 / S30 没被记账时：本地容器里已经有箱子侧物品就直接开搬。
     *
     * <p>以前不管内容到没到，只要没看到 S30 就白等 {@code WINDOW_SYNC_FALLBACK_TICKS}
     * （12 tick ≈ 600ms）。本机日志里「开箱 0.9s 才点第一格」就是包钩子在开箱之后
     * 才 attach、这条兜底硬等出来的。
     */
    private static void localContentSkipsMissingS30() {
        configure(false, true, 3, 2);
        ChestFeature.setOpenDelayTicks(1);
        ChestFeature.noteInboundPacket(new S30PacketWindowItems(9999));
        sleepQuietly(700L);
        Fixture f = new Fixture();
        f.chest.slots[2] = new ItemStack("item.diamond", 1);
        f.installServerOpen();       // 只开窗，故意不发 S30

        int ticks = 0;
        while (ticks < 12 && f.controller.clicks == 0) {
            ChestFeature.onTick(f.mc);
            ticks++;
        }
        check("S30 缺失但本地容器已有内容：很快就开始搬", f.controller.clicks > 0);

        for (int i = 0; i < 20; i++) {
            ChestFeature.onTick(f.mc);
            ChestFeature.noteInboundPacket(new S32PacketConfirmTransaction(f.container.windowId));
        }
        check("走本地内容捷径也能把东西搬完",
                contains(f.player.inventory.mainInventory, "item.diamond"));
        ChestFeature.reset();
    }

    /**
     * CLICK MS 的硬下限：配置里写 40ms 会被夹到 80ms 以上。
     *
     * <p>本机日志里 {@code clickDelayMinMs=clickDelayMaxMs=40} 打出来的是
     * 「40ms 连点 5 格 + 500~1100ms 空档」的爆发形态：密度全挤在 burst 头部，
     * 既是最典型的自动搬运指纹，也把整箱耗时拖到 6 秒以上。
     */
    private static void clickIntervalFloorIsEnforced() {
        ChestFeature.setClickDelayMinMs(40);
        ChestFeature.setClickDelayMaxMs(40);
        check("CLICK MS 低于硬下限时被夹到 80ms 以上",
                ChestFeature.getClickDelayMinMs() >= 80
                        && ChestFeature.getClickDelayMaxMs() >= 80);
        ChestFeature.setClickDelayMinMs(100);
        ChestFeature.setClickDelayMaxMs(160);
    }

    /**
     * 刚搬空的箱子不会被立刻重新扫一次。
     *
     * <p>本机日志：关箱 0.66s 之后又对同一个箱子发了开箱包，窗口里 0 件物品，
     * 白花一次往返，还让服务端的开箱统计多记一条空箱。
     */
    private static void lootedChestIsNotSweptAgainImmediately() {
        configure(false, true, 1, 1);
        sleepQuietly(700L);
        Fixture f = new Fixture();
        f.chest.slots[0] = new ItemStack("item.diamond", 1);
        f.installServerOpenSynced();

        for (int i = 0; i < 40; i++) {
            ChestFeature.onTick(f.mc);
            ChestFeature.noteInboundPacket(new S32PacketConfirmTransaction(f.container == null
                    ? 42 : f.container.windowId));
        }
        check("第一个箱子搬完并关掉", f.mc.currentScreen == null
                && contains(f.player.inventory.mainInventory, "item.diamond"));
        int opensAfterLoot = f.controller.openCalls;

        // 关窗沉降、扫箱冷却和 4 tick 停留都过掉了，但仍在「刚搬空」窗口期内：
        // 这时再自动开同一个箱子就是白跑一趟。
        sleepQuietly(600L);
        for (int i = 0; i < 10; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("刚搬空的同一个箱子不会被立刻重开", f.controller.openCalls == opensAfterLoot);
        ChestFeature.reset();
    }

    /** CLICK GAP：两批 windowClick 之间要隔开，不能一 tick 里连点一串。 */
    private static void clicksAreSpacedByGap() {
        configure(false, true, 1, 3);
        sleepQuietly(700L);
        Fixture f = new Fixture();
        for (int i = 0; i < 9; i++) {
            f.chest.slots[i] = new ItemStack("item.diamond", 1);
        }
        f.installServerOpenSynced();

        for (int i = 0; i < 5; i++) {
            ChestFeature.onTick(f.mc);
        }
        ChestFeature.noteInboundPacket(new S30PacketWindowItems(f.container.windowId));
        for (int i = 0; i < 10 && f.controller.clicks == 0; i++) {
            ChestFeature.onTick(f.mc);
        }
        int afterFirst = f.controller.clicks;
        check("同步后开始点（一 tick 只点一次）", afterFirst == 1);
        // 没有服务端确认时，必须等够 CLICK GAP（3 tick）才拿下一格
        ChestFeature.onTick(f.mc);
        ChestFeature.onTick(f.mc);
        check("确认没回来时要等够 CLICK GAP", f.controller.clicks == afterFirst);
        // 确认一到，不用等满间隔就能接着拿
        ChestFeature.noteInboundPacket(new S32PacketConfirmTransaction(f.container.windowId));
        ChestFeature.onTick(f.mc);
        check("确认一到就接着拿下一格", f.controller.clicks == afterFirst + 1);
        check("界面一直开着（没有中途被关掉）", f.mc.currentScreen instanceof GuiChest);
        ChestFeature.reset();
    }

    /**
     * 一件一件拿：CLICKS/TICK 设成 9 也不许一批拿多格；
     * 上一格要等服务端确认（S32）回来，才拿下一格。
     */
    private static void takesOneStackPerBatch() {
        configure(false, true, 9, 4);   // 一批允许 9 次点击也不该一次拿多格；间隔拉长才能看出确认门
        sleepQuietly(700L);
        Fixture f = new Fixture();
        for (int i = 0; i < 6; i++) {
            f.chest.slots[i] = new ItemStack("item.diamond", 1);
        }
        f.installServerOpenSynced();

        for (int i = 0; i < 5; i++) {
            ChestFeature.onTick(f.mc);
        }
        for (int i = 0; i < 6 && f.controller.movedItems.isEmpty(); i++) {
            ChestFeature.onTick(f.mc);
        }
        check("一批只拿一格", f.controller.movedItems.size() == 1);

        for (int i = 0; i < 3; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("服务端确认没回来、间隔也没到时不拿下一格", f.controller.movedItems.size() == 1);
        check("界面一直开着", f.mc.currentScreen instanceof GuiChest);

        ChestFeature.noteInboundPacket(new S32PacketConfirmTransaction(f.container.windowId));
        for (int i = 0; i < 3 && f.controller.movedItems.size() == 1; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("确认回来后才拿下一格", f.controller.movedItems.size() == 2);

        // 剩下的一格一格拿完并退出
        for (int i = 0; i < 30; i++) {
            ChestFeature.onTick(f.mc);
            ChestFeature.noteInboundPacket(new S32PacketConfirmTransaction(f.container.windowId));
        }
        check("按格子顺序一格一格搬完", f.controller.movedItems.size() == 6
                && f.chest.slots[0] == null && f.chest.slots[5] == null);
        check("搬完退出箱子", f.mc.currentScreen == null);
        ChestFeature.reset();
    }

    /**
     * 速度上限：服务端确认及时时，一件一件拿也能做到「一格一 tick」。
     *
     * <p>9 格满箱必须在 16 tick（≈0.8 秒）内搬完并退出 —— 这是一件一件拿的速度基线，
     * 避免以后又把节奏加回「每格等好几 tick」。
     */
    private static void lootsOneStackPerTickWhenConfirmed() {
        configure(false, true, 9, 1);
        sleepQuietly(700L);
        Fixture f = new Fixture();
        for (int i = 0; i < 9; i++) {
            f.chest.slots[i] = new ItemStack("item.diamond", 1);
        }
        f.installServerOpenSynced();

        for (int i = 0; i < 5; i++) {
            ChestFeature.onTick(f.mc);
        }
        int ticks = 0;
        while (ticks < 40 && f.mc.currentScreen != null) {
            ChestFeature.onTick(f.mc);
            ChestFeature.noteInboundPacket(new S32PacketConfirmTransaction(f.container.windowId));
            ticks++;
        }
        check("9 格在 16 tick 内搬完并退出（一格一 tick）", ticks <= 16);
        check("9 格全部搬进背包", f.controller.movedItems.size() == 9);
        ChestFeature.reset();
    }

    /**
     * 界面被关掉（玩家按 E）但服务端容器还挂着：立刻收摊，不再对着没界面的窗口点。
     *
     * <p>「界面都没了还在点窗口」正是 Grim 那类 click window 检查最敏感的形态，
     * 所以这里的行为必须是「发现界面没了就关窗退出」，而不是继续搬完整箱。
     */
    private static void closingGuiMidwayStopsTheStealer() {
        configure(false, true, 1, 2);
        sleepQuietly(700L);
        Fixture f = new Fixture();
        for (int i = 0; i < 9; i++) {
            f.chest.slots[i] = new ItemStack("item.diamond", 1);
        }
        f.installServerOpenSynced();

        for (int i = 0; i < 5; i++) {
            ChestFeature.onTick(f.mc);
        }
        for (int i = 0; i < 4; i++) {
            ChestFeature.onTick(f.mc);
        }
        int clicksBefore = f.controller.clicks;
        check("界面开着时在搬", clicksBefore > 0);
        check("界面还开着", f.mc.currentScreen instanceof GuiChest);

        // 玩家自己按 E 把界面关掉（服务端那边容器还开着）
        f.mc.displayGuiScreen(null);
        ChestFeature.onTick(f.mc);
        check("界面关掉后立刻发 C0D 关窗", f.closeWindowId() == f.container.windowId);
        check("容器换回背包容器", f.player.openContainer == f.player.inventoryContainer);

        for (int i = 0; i < 10; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("界面关掉后一格都不再点", f.controller.clicks == clicksBefore);
        ChestFeature.reset();
    }

    // ------------------------------------------------------------------
    // 静默模式（SHOW CHEST GUI=off）
    // ------------------------------------------------------------------

    private static void silentLootNeverShowsGui() {
        configure(true, true, 3, 0);
        ChestFeature.setTakeFilter(1);   // NO JUNK：本用例专门验证过滤
        Fixture f = new Fixture();
        f.chest.slots[0] = new ItemStack("tile.stone", 12);
        f.chest.slots[5] = new ItemStack("item.diamond", 3);
        f.installServerOpen();

        ChestFeature.triggerLoot();
        for (int i = 0; i < 40; i++) {
            ChestFeature.onTick(f.mc);
        }

        check("只发了一次右键开箱包", f.controller.openCalls == 1);
        check("箱子界面没有留在屏幕上（同一 tick 内被收起）",
                f.mc.currentScreen == null && f.mc.shownScreens.size() <= 1);
        check("窗口内容同步后容器仍挂在玩家身上 / 已按 windowId 关闭",
                f.player.openContainer == f.player.inventoryContainer);
        check("windowId 写入过（点击确实发生在 42 号窗口）", f.container.windowId == 42);
        check("点击全部打在 42 号窗口上", f.controller.clickWindowIds.contains(Integer.valueOf(42)));
        check("只搬走贵重物品：钻石进背包", contains(f.player.inventory.mainInventory, "item.diamond"));
        check("垃圾留在箱子里", f.chest.slots[0] != null && f.chest.slots[5] == null);
        check("搬完自动发 C0D 关窗", f.closeWindowId() == 42);
        check("确实用 shift-click 模式搬运", f.controller.movedItems.contains("item.diamond"));
        ChestFeature.reset();
    }

    /** 真实环境里 GuiChest 字段名对不上时的兜底：靠类名也能静默开箱并搬空。 */
    private static void silentLootWorksWithoutPreviewField() {
        configure(true, true, 3, 0);
        sleepQuietly(700L);
        Fixture f = new Fixture();
        f.chest.slots[3] = new ItemStack("item.diamond", 2);
        f.installServerOpenCompat();

        ChestFeature.triggerLoot();
        for (int i = 0; i < 40; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("没有 lowerChestInventory 字段也能静默搬完",
                f.mc.currentScreen == null && contains(f.player.inventory.mainInventory, "item.diamond"));
        check("兜底路径同样搬走物资", contains(f.player.inventory.mainInventory, "item.diamond"));
        check("兜底路径同样自动关窗", f.player.openContainer == f.player.inventoryContainer);
        ChestFeature.reset();
    }

    /** 等窗口包期间绝不能再发右键包：以前一秒能发三四次，容器反复重建。 */
    private static void sleepsWithoutResendingOpenPacket() {
        configure(false, true, 3, 0);
        sleepQuietly(700L);
        Fixture f = new Fixture();
        f.chest.slots[2] = new ItemStack("item.diamond", 1);
        // 服务端故意不回窗口包：这时每一 tick 都不该重发。
        PlayerControllerMP.rightClickHandler = null;

        ChestFeature.triggerLoot();
        for (int i = 0; i < 30; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("等服务端窗口包期间只发一次右键包", f.controller.openCalls == 1);
        ChestFeature.reset();
    }

    /**
     * 连续开箱：上一个会话还没收摊时又来一个箱子界面（不是我们的开箱包引出来的），
     * 必须接管成新会话，而不是像以前那样把「新界面背后的容器」C0D 掉再换回背包 ——
     * 那种情况下界面还开着、openContainer 却是背包（windowId=0），一件都点不出去，
     * 玩家看到的就是「点开箱子直接不拿」。
     */
    private static void secondChestIsTakenOverInsteadOfClosed() {
        configure(false, true, 1, 1);
        Fixture f = new Fixture();
        f.chest.slots[0] = new ItemStack("item.diamond", 1);
        f.installServerOpenSynced();
        for (int i = 0; i < 5; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("第二个箱子用例：第一个箱子已接管", f.player.openContainer == f.container);

        f.chest.slots[0] = new ItemStack("item.diamond", 1);
        ContainerChest second = new ContainerChest(f.player.inventory, f.chest);
        second.windowId = 43;
        f.mc.displayGuiScreen(new GuiChest(f.chest, second));
        // 原版顺序：displayGuiScreen 先挂容器，随后 NetHandlerPlayClient 写 windowId
        f.player.openContainer = second;
        ChestFeature.onTick(f.mc);

        check("新箱子被接管（容器没有被换回背包）", f.player.openContainer == second);
        check("没有把刚打开的新窗口 C0D 掉", !f.hasCloseFor(43));

        int clicksBefore = f.controller.clicks;
        for (int i = 0; i < 20; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("新箱子照常搬得动", f.controller.clicks > clicksBefore);
        ChestFeature.reset();
    }

    /**
     * 过期关窗包过滤：只有「窗口号等于当前窗口」的 S2E 才往下传。
     *
     * <p>连开箱子时服务端替旧窗口补发的 S2E 会晚到，1.8 客户端不检查窗口号、
     * 直接 closeScreen() —— 新开的界面就这么没了。钩子里拦下过期包才是根治办法。
     */
    private static void staleCloseWindowIsFiltered() {
        configure(false, true, 1, 1);
        Fixture f = new Fixture();
        f.chest.slots[0] = new ItemStack("item.diamond", 1);
        f.installServerOpenSynced();
        for (int i = 0; i < 5; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("过期包用例：当前窗口号被记下", f.controller.clickWindowIds.contains(Integer.valueOf(42))
                || f.player.openContainer != null);

        check("过期窗口的 S2E 被拦下",
                ChestFeature.isStaleCloseWindow(new S2EPacketCloseWindow(7)));
        check("当前窗口的 S2E 照常放行",
                !ChestFeature.isStaleCloseWindow(new S2EPacketCloseWindow(42)));
        check("非关窗包不受影响",
                !ChestFeature.isStaleCloseWindow(new S30PacketWindowItems(42)));
        ChestFeature.reset();
    }

    /** 玩家自己开的箱子界面（字段读不到）也要能 AUTO LOOT。 */
    private static void visibleGuiWithoutPreviewFieldStillLoots() {
        configure(false, false, 3, 2);
        Fixture f = new Fixture();
        f.chest.slots[4] = new ItemStack("item.emerald", 1);
        ContainerChest container = new ContainerChest(f.player.inventory, f.chest);
        container.windowId = 9;
        GuiChestCompat gui = new GuiChestCompat(container);
        f.mc.displayGuiScreen(gui);

        for (int i = 0; i < 20; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("字段缺失时可见界面仍能搬", contains(f.player.inventory.mainInventory, "item.emerald"));
        f.mc.displayGuiScreen(null);
        ChestFeature.reset();
    }

    /** 箱子里只有过滤掉的垃圾、而且 AUTO CLOSE 关着时：界面不能被偷偷关掉。 */
    private static void junkOnlyChestIsNotAutoClosed() {
        configure(false, false, 3, 2);
        ChestFeature.setTakeFilter(1);   // NO JUNK：垃圾被过滤掉，界面不能被自动关
        Fixture f = new Fixture();
        f.chest.slots[0] = new ItemStack("tile.stone", 12);
        f.chest.slots[1] = new ItemStack("tile.dirt", 5);
        ContainerChest container = new ContainerChest(f.player.inventory, f.chest);
        container.windowId = 11;
        GuiChest gui = new GuiChest(f.chest, container);
        f.mc.displayGuiScreen(gui);

        check("玩家自己开的箱子界面正常显示", f.mc.currentScreen == gui);
        for (int i = 0; i < 30; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("一件都没搬成（全是垃圾）",
                f.player.inventory.mainInventory[0] == null && f.chest.slots[0] != null);
        check("没搬成东西时界面保持打开，不会一闪就关", f.mc.currentScreen == gui);
        check("也没发关窗包", f.closeWindowId() == -1);
        f.mc.displayGuiScreen(null);
        ChestFeature.reset();
    }

    private static void manualChestGuiStillLoots() {
        configure(false, true, 3, 2);
        Fixture f = new Fixture();
        f.chest.slots[2] = new ItemStack("item.emerald", 1);
        // 模拟玩家自己右键开箱：原版界面正常显示、容器已挂上。
        ContainerChest container = new ContainerChest(f.player.inventory, f.chest);
        container.windowId = 7;
        GuiChest gui = new GuiChest(f.chest, container);
        f.mc.displayGuiScreen(gui);

        check("玩家自己开的箱子界面正常显示", f.mc.currentScreen == gui);
        check("界面挂上了箱子容器", f.player.openContainer == container);

        for (int i = 0; i < 20; i++) {
            ChestFeature.onTick(f.mc);
            ChestFeature.noteInboundPacket(new S32PacketConfirmTransaction(7));
        }
        check("可见界面下 AUTO LOOT 照常搬运", contains(f.player.inventory.mainInventory, "item.emerald"));
        check("点击打在 7 号窗口上", f.controller.clickWindowIds.contains(Integer.valueOf(7)));
        check("AUTO CLOSE 开着：拿完自己退出箱子", f.mc.currentScreen == null);
        ChestFeature.reset();
    }

    /** AUTO SWEEP（静默模式）：不用按键，准星在箱子上停几 tick 就自动开箱 + 搬空。 */
    private static void silentSweepOpensAndLootsWithoutKey() {
        configure(true, true, 3, 0);
        sleepQuietly(700L);
        Fixture f = new Fixture();
        f.chest.slots[6] = new ItemStack("item.diamond", 1);
        f.installServerOpen();

        for (int i = 0; i < 40; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("不用按键：准星停留后自动开箱", f.controller.openCalls == 1);
        check("不用按键：自动搬走物资", contains(f.player.inventory.mainInventory, "item.diamond"));
        check("静默模式：界面没留在屏幕上且自动关窗",
                f.mc.currentScreen == null && f.player.openContainer == f.player.inventoryContainer);
        ChestFeature.reset();
    }

    /**
     * 玩家自己右键箱子（界面正常显示）→ 按格子顺序从上到下把箱内物品搬进背包。
     *
     * <p>这是玩家最直接的预期：右键箱子，里面的东西一格一格进背包。
     * 旧行为在 {@code takeFilter=2} 时会把普通物资全筛掉、并且一声不吭 ——
     * 表现就是「箱子开着，一件都不拿」。
     */
    private static void visibleChestEmptiesTopToBottom() {
        configure(false, false, 9, 2);
        ChestFeature.setTakeFilter(0);
        Fixture f = new Fixture();
        f.chest.slots[0] = new ItemStack("tile.stone", 12);
        f.chest.slots[3] = new ItemStack("item.diamond", 3);
        f.chest.slots[5] = new ItemStack("tile.dirt", 64);
        f.chest.slots[26] = new ItemStack("item.apple", 5);
        ContainerChest container = new ContainerChest(f.player.inventory, f.chest);
        container.windowId = 21;
        GuiChest gui = new GuiChest(f.chest, container);
        f.mc.displayGuiScreen(gui);

        for (int i = 0; i < 30; i++) {
            ChestFeature.onTick(f.mc);
            ChestFeature.noteInboundPacket(new S32PacketConfirmTransaction(21));
        }
        check("右键开箱：普通方块也照拿（不再被过滤静默吞掉）",
                contains(f.player.inventory.mainInventory, "tile.stone")
                        && contains(f.player.inventory.mainInventory, "tile.dirt"));
        check("右键开箱：贵重物品照拿", contains(f.player.inventory.mainInventory, "item.diamond"));
        check("右键开箱：最后一格的物品也拿了",
                contains(f.player.inventory.mainInventory, "item.apple"));
        check("箱子被搬空", f.chest.slots[0] == null && f.chest.slots[3] == null
                && f.chest.slots[5] == null && f.chest.slots[26] == null);
        check("按从上到下的格子顺序搬", f.controller.movedItems.size() >= 4
                && "tile.stone".equals(f.controller.movedItems.get(0))
                && "item.diamond".equals(f.controller.movedItems.get(1))
                && "tile.dirt".equals(f.controller.movedItems.get(2))
                && "item.apple".equals(f.controller.movedItems.get(3)));
        check("点击全打在 21 号窗口", f.controller.clickWindowIds.contains(Integer.valueOf(21)));
        f.mc.displayGuiScreen(null);
        ChestFeature.reset();
    }

    /**
     * 服务端的窗口物品包（S30）晚到几个 tick：不能在第一轮「没东西」就关容器。
     *
     * <p>真实场景里内容包比开窗包晚到很常见。旧实现在第一轮搬不到东西时立刻
     * {@code silent close after moving 0 stack(s)}，玩家看到的就是「箱子开了一下、空的」。
     */
    private static void lateContentsAreStillTaken() {
        configure(true, true, 3, 0);
        ChestFeature.setTakeFilter(0);
        sleepQuietly(700L);
        Fixture f = new Fixture();
        f.installServerOpen();

        ChestFeature.triggerLoot();
        ChestFeature.onTick(f.mc);   // 开箱 + 原版挂容器
        ChestFeature.onTick(f.mc);   // 收起界面
        ChestFeature.onTick(f.mc);   // 第一轮搬运：还没内容
        ChestFeature.onTick(f.mc);
        check("内容没到时容器不关", f.player.openContainer == f.container);

        f.chest.slots[2] = new ItemStack("item.diamond", 4);   // 内容晚到
        for (int i = 0; i < 40; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("晚到的内容照样搬进背包",
                contains(f.player.inventory.mainInventory, "item.diamond"));
        check("搬完仍然自动关窗", f.closeWindowId() == 42);
        ChestFeature.reset();
    }

    /** 拾取过滤把物品全筛掉时：打日志 + 关容器，不能把容器一直挂着。 */
    private static void filteredChestStillClosesSilentContainer() {
        configure(true, true, 3, 0);
        ChestFeature.setTakeFilter(2);   // VALUABLE：石头不匹配
        sleepQuietly(700L);
        Fixture f = new Fixture();
        f.chest.slots[0] = new ItemStack("tile.stone", 5);
        f.installServerOpen();

        ChestFeature.triggerLoot();
        for (int i = 0; i < 40; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("被过滤的物品留在箱子里", f.chest.slots[0] != null);
        check("背包里没多东西", f.player.inventory.mainInventory[0] == null);
        check("过滤掉一切时容器仍会关掉", f.closeWindowId() == 42);
        ChestFeature.reset();
    }

    /**
     * 创造物品栏：45 个页签槽位 + 36 个背包槽位，看起来「像」箱子。
     *
     * <p>旧判定（槽位 > 36 且有箱子侧）会把它当成箱子：按 E 打开物品栏时被静默开箱逻辑
     * 接管 —— 容器被换成 {@code ContainerCreative}、界面被取消，玩家看到的就是
     * 「物品栏打不开 / 一打开就没了」。
     */
    private static void creativeInventoryIsNotHijacked() {
        configure(false, true, 3, 0);
        sleepQuietly(700L);
        Fixture f = new Fixture();
        final ContainerCreative creative = new ContainerCreative(f.player.inventory);
        final GuiContainerCreative gui = new GuiContainerCreative(creative);
        // 服务端这回不送箱子界面，只把创造物品栏显示出来（玩家按 E）。
        PlayerControllerMP.rightClickHandler = new PlayerControllerMP.RightClickHandler() {
            public void onRightClick(Object world, Object blockPos) {
                Minecraft.getMinecraft().displayGuiScreen(gui);
            }
        };

        ChestFeature.triggerLoot();
        ChestFeature.onTick(f.mc);           // 开箱包发出去，进入「等窗口」状态
        ChestFeature.onTick(f.mc);           // 创造物品栏到达
        check("创造物品栏照常显示，没被静默逻辑接管", f.mc.currentScreen == gui);
        check("创造物品栏没被换成背包容器", f.player.openContainer == creative);
        check("没有偷偷关掉玩家界面", f.closeWindowId() == -1);
        check("也没把界面收起来（没有待隐藏状态）", f.mc.currentScreen == gui);
        f.mc.displayGuiScreen(null);
        ChestFeature.reset();
    }

    private static void otherScreenClosesSilentContainer() {
        configure(true, true, 3, 0);
        Fixture f = new Fixture();
        f.chest.slots[1] = new ItemStack("item.diamond", 1);
        f.installServerOpen();

        // 两次开箱包之间至少有 400ms 间隔（防刷包），等过前一个场景的冷却。
        sleepQuietly(700L);
        ChestFeature.triggerLoot();
        ChestFeature.onTick(f.mc);   // 开箱 + 原版挂容器
        check("静默容器已开", f.player.openContainer == f.container);
        ChestFeature.onTick(f.mc);   // 收起界面 + 开始搬
        check("静默界面已收起", f.mc.currentScreen == null);

        GuiScreen other = new GuiScreen();
        f.mc.displayGuiScreen(other);
        check("打开别的界面后静默容器被关掉", f.player.openContainer == f.player.inventoryContainer);
        check("关窗包带着静默容器的 windowId", f.closeWindowId() == 42);
        check("别的界面照常显示", f.mc.currentScreen == other);
        f.mc.displayGuiScreen(null);
        ChestFeature.reset();
    }

    // ------------------------------------------------------------------
    // 人类化节拍（反 Grim click window / Post 检测）
    // ------------------------------------------------------------------

    /**
     * 人类化节拍的回归：每格间隔按真实时间随机（这里把区间钉成 200ms 便于断言），
     * 6 格总时长必须远超「6 格 250ms」那种机械节奏，且相邻两格绝不落在同一 tick 里。
     *
     * <p>本机日志的失败形态：6 次 click window 全部落在 12:55:23、总时长 250ms，
     * 紧接着 {@code [GrimAC] failed Post (vl:0.0): click window v1.8}。
     */
    private static void humanizedPacingAvoidsMachineRhythm() {
        configure(false, true, 1, 0);
        ChestFeature.setHumanize(true);
        ChestFeature.setClickDelayMinMs(200);
        ChestFeature.setClickDelayMaxMs(200);
        ChestFeature.setFirstClickDelayMs(0);
        ChestFeature.setPauseChancePct(0);
        ChestFeature.setSkipChancePct(0);
        ChestFeature.setCloseDelayMinTicks(2);
        ChestFeature.setCloseDelayMaxTicks(2);

        Fixture f = new Fixture();
        for (int i = 0; i < 6; i++) {
            f.chest.slots[i] = new ItemStack("item.diamond", 1);
        }
        f.installServerOpenSynced();
        for (int i = 0; i < 5; i++) {
            ChestFeature.onTick(f.mc);
        }
        check("humanize: 扫箱照常开箱", f.controller.openCalls == 1);

        long deadline = System.nanoTime() + 8_000_000_000L;
        while (f.controller.clicks < 6 && System.nanoTime() < deadline) {
            ChestFeature.onTick(f.mc);
            sleepQuietly(20L);
        }
        check("humanize: 6 格全部搬进背包",
                f.controller.clicks >= 6 && f.controller.movedItems.size() >= 6);

        List<Long> times = f.controller.clickNanos;
        long spanMs = (times.get(5).longValue() - times.get(0).longValue()) / 1_000_000L;
        check("humanize: 6 格总时长 >= 900ms（不是 250ms 的机器节奏）", spanMs >= 900L);
        boolean spaced = true;
        for (int i = 1; i < 6; i++) {
            long gapMs = (times.get(i).longValue() - times.get(i - 1).longValue()) / 1_000_000L;
            if (gapMs < 150L) {
                spaced = false;
            }
        }
        check("humanize: 相邻两格至少隔 150ms（同一 tick 不会出现两个包）", spaced);

        long closeDeadline = System.nanoTime() + 4_000_000_000L;
        while (f.mc.currentScreen != null && System.nanoTime() < closeDeadline) {
            ChestFeature.onTick(f.mc);
            sleepQuietly(20L);
        }
        check("humanize: 搬完照常关箱退出", f.mc.currentScreen == null);

        ChestFeature.reset();
        ChestFeature.setHumanize(false);
        ChestFeature.setClickDelayMinMs(180);
        ChestFeature.setClickDelayMaxMs(380);
        ChestFeature.setFirstClickDelayMs(280);
        ChestFeature.setPauseChancePct(22);
        ChestFeature.setCloseDelayMinTicks(3);
        ChestFeature.setCloseDelayMaxTicks(9);
    }

    // ------------------------------------------------------------------

    /** 每个用例先把设置恢复成已知状态（设置会持久化到临时 properties 文件）。 */
    private static void configure(boolean hideGui, boolean autoClose, int clicksPerTick, int gap) {
        ChestFeature.setHideGui(hideGui);
        ChestFeature.setAutoClose(autoClose);
        ChestFeature.setClicksPerTick(clicksPerTick);
        ChestFeature.setOneByOne(true);
        ChestFeature.setClickGapTicks(gap);
        ChestFeature.setOpenDelayTicks(2);
        ChestFeature.setTakeFilter(0);
        ChestFeature.setStoreFilter(1);
        ChestFeature.setAutoLoot(true);
        ChestFeature.setAutoSort(false);
        ChestFeature.setAutoOpen(true);
        ChestFeature.setAutoSweep(true);
    }

    /** 一套最小可用的「游戏现场」。 */
    private static final class Fixture {
        final Minecraft mc = new Minecraft();
        final World world = new World();
        final TileEntityChest chest;
        final EntityPlayer player = new EntityPlayer();
        final PlayerControllerMP controller = new PlayerControllerMP();
        ContainerChest container;

        Fixture() {
            chest = new TileEntityChest(new BlockPos(3, 64, -2));
            world.add(chest);
            mc.theWorld = world;
            mc.thePlayer = player;
            mc.playerController = controller;
            mc.objectMouseOver = new MovingObjectPosition(chest.getPos());
            Minecraft.hook = new Minecraft.GuiOpenHook() {
                public boolean onOpen(GuiOpenEvent event) {
                    ChestFeature.onGuiOpen(event);
                    return event.isCanceled();
                }
            };
        }

        /** 假服务端：右键 → 建容器 → displayGuiScreen（原版挂容器）→ 写 windowId。 */
        void installServerOpen() {
            installServerOpen(false);
        }

        /** 同上，并且立刻补一个窗口物品包（模拟带宽很好的服务器：内容跟开窗一起到）。 */
        void installServerOpenSynced() {
            installServerOpen(true);
        }

        private void installServerOpen(final boolean syncContents) {
            final Fixture self = this;
            PlayerControllerMP.rightClickHandler = new PlayerControllerMP.RightClickHandler() {
                public void onRightClick(Object world, Object blockPos) {
                    ContainerChest created = new ContainerChest(self.player.inventory, self.chest);
                    self.container = created;
                    self.mc.displayGuiScreen(new GuiChest(self.chest, created));
                    // NetHandlerPlayClient.handleOpenWindow 在 displayGui 之后写 windowId
                    self.player.openContainer.windowId = 42;
                    if (syncContents) {
                        ChestFeature.noteInboundPacket(new S30PacketWindowItems(42));
                    }
                }
            };
        }

        void installServerOpenCompat() {
            final Fixture self = this;
            PlayerControllerMP.rightClickHandler = new PlayerControllerMP.RightClickHandler() {
                public void onRightClick(Object world, Object blockPos) {
                    ContainerChest created = new ContainerChest(self.player.inventory, self.chest);
                    self.container = created;
                    self.mc.displayGuiScreen(new GuiChestCompat(created));
                    self.player.openContainer.windowId = 42;
                }
            };
        }

        boolean hasCloseFor(int windowId) {
            for (int i = 0; i < player.sendQueue.sent.size(); i++) {
                Object packet = player.sendQueue.sent.get(i);
                if (packet instanceof C0DPacketCloseWindow
                        && ((C0DPacketCloseWindow) packet).windowId == windowId) {
                    return true;
                }
            }
            return false;
        }

        int closeWindowId() {
            for (int i = 0; i < player.sendQueue.sent.size(); i++) {
                Object packet = player.sendQueue.sent.get(i);
                if (packet instanceof C0DPacketCloseWindow) {
                    return ((C0DPacketCloseWindow) packet).windowId;
                }
            }
            return -1;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean contains(ItemStack[] items, String unlocalizedName) {
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && unlocalizedName.equals(items[i].unlocalizedName)) {
                return true;
            }
        }
        return false;
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            throw new AssertionError(what);
        }
        passed++;
        System.out.println("[chest-test] ok  " + what);
    }
}
