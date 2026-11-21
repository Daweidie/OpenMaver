package module.player;

import module.Module;

/**
 * 自动整理背包（Inventory Manager）开关。
 *
 * <p>真正的逻辑在 MC 侧 {@code InventoryManagerFeature}，规则表在
 * {@code InventoryRules}（纯计算，无 MC 依赖），点击计划在
 * {@code InventoryPlanner}：
 *
 * <ul>
 *   <li><b>自动穿盔甲</b>：背包里更好的头盔 / 胸甲 / 护腿 / 靴子自动换到身上，
 *       差的放回原格；评分 = 护甲点 + 保护类附魔 + 剩余耐久。</li>
 *   <li><b>快捷栏布局</b>：1 武器 → 2 方块 → 3 食物（金苹果优先）→ 4 工具（镐优先），
 *       5~9 补方块 / 食物 / 工具的剩余堆叠，杂物挪回背包。</li>
 *   <li><b>丢多余</b>：剑超出保留把数、某种盔甲备用件超出保留数（工具可选）时，
 *       丢掉评分最低的那些。</li>
 * </ul>
 *
 * <p>点击全部走玩家背包窗口（windowId 0）的 {@code windowClick}：换快捷栏用
 * 数字键动作（mode 2），换盔甲 / 合并用拿起放下（mode 0），丢东西用丢弃键（mode 4）。
 * 只在当前窗口就是玩家背包时动手，开着箱子时交给 {@code ChestFeature}。
 *
 * <p>配置路径自动推导为 {@code player.inventorymanager}；参数写入
 * {@code config/openmaver-inventory.properties}（`inventory.*`）。
 */
public final class InventoryManager extends Module {

    public InventoryManager() {
        // 显式给出配置路径：模块名里的空格不能带进 key（"inventory manager" 对不上
        // default.jsonc 的 "player.inventorymanager"）。
        super("Inventory Manager", "player.inventorymanager");
    }
}
