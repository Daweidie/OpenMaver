package module.player;

import module.Module;

/**
 * 开箱搬运（Chest Stealer）开关。
 *
 * <p>真正的搬运逻辑在 MC 侧 {@code ChestFeature}：对着箱子按拾取键（或准星停留）后，
 * 客户端替玩家发一次右键开箱包；到达的 {@code GuiChest} 由
 * {@code ChestFeature.onGuiOpen} 记成一次「箱子会话」—— 默认（{@code SHOW CHEST GUI}
 * 开着）界面就留在屏幕上，搬运在这个真界面里用 {@code windowClick} 的 shift-click
 * （mode 1）完成，拿完再发 {@code C0D} 关窗退出；关掉该开关则退回静默开箱（界面同一
 * tick 内收起）。并可继续做「整理」与「存入」。
 *
 * <p>点击前会等服务端的窗口物品包（S30）到过这个 {@code windowId}；默认「一件一件拿」
 * （{@code ONE BY ONE}）：每批只拿一格，等服务端窗口确认（S32）回来、再隔
 * {@code CLICK GAP} 个 tick 才拿下一格 —— 客户端与服务端的容器状态始终一致，
 * 观感也和玩家自己开箱拿东西一样。
 *
 * <p>玩家自己右键箱子、界面正常显示时同样会自动搬运：从第一格开始按槽位顺序
 * （从上到下、从左到右）逐格 shift-click 进玩家背包，默认拾取过滤为 {@code ALL}。
 * core 侧只保留开关与配置路径，避免 core 在 bootstrap 类加载器下引用 {@code net.minecraft.*}。
 *
 * <p>配置路径自动推导为 {@code player.cheststealer}。
 */
public final class ChestStealer extends Module {

    public ChestStealer() {
        super("Chest Stealer");
    }
}
