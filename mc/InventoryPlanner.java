import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 背包 / 快捷栏的整理计划器：纯逻辑，不含任何 MC 类型或反射。
 *
 * <p>输入一份「45 格玩家背包窗口」的快照（{@link State}），输出<b>下一步该做的一件小事</b>
 * （{@link Action}）；每做一步就重新读一次真实背包再算下一步，所以中途被
 * 玩家插手、被服务端回滚也不会跑偏。
 *
 * <h3>槽位编号</h3>
 * 用的是玩家背包窗口（windowId 0）的容器槽号，和原版 {@code windowClick} 完全一致：
 * <pre>
 *   0       合成结果
 *   1..4    合成格
 *   5..8    盔甲：头盔 / 胸甲 / 护腿 / 靴子
 *   9..35   背包主区
 *   36..44  快捷栏 1..9
 * </pre>
 *
 * <h3>每步动作</h3>
 * <ul>
 *   <li>{@link #EQUIP_ARMOR}：背包里更好的盔甲换下身上更差的
 *       （拿起 → 点盔甲槽 → 把旧的放回原槽）</li>
 *   <li>{@link #SWAP_HOTBAR}：用「数字键」动作（mode 2）把某格直接换到快捷栏第 N 格，
 *       一次点击完成，和玩家按 1~9 是同一个包</li>
 *   <li>{@link #MERGE}：同物品的两摞合并（拿起 → 点到另一摞 → 余数放回）</li>
 *   <li>{@link #MOVE}：把快捷栏里的杂物挪进背包空位</li>
 *   <li>{@link #DROP}：丢掉多余的武器 / 装备（mode 4 = 丢弃键）</li>
 * </ul>
 *
 * <h3>快捷栏布局</h3>
 * 固定按需求排：<b>1 武器 → 2 方块 → 3 食物 → 4 工具</b>，后面依次补
 * 方块 / 食物 / 工具的剩余堆叠；杂物（{@code KIND_OTHER}）不进快捷栏。
 */
public final class InventoryPlanner {

    /** 玩家背包窗口的槽位总数。 */
    public static final int SLOTS = 45;

    public static final int ARMOR_FIRST = 5;
    public static final int ARMOR_LAST = 8;
    public static final int MAIN_FIRST = 9;
    public static final int MAIN_LAST = 35;
    public static final int HOTBAR_FIRST = 36;
    public static final int HOTBAR_LAST = 44;
    public static final int HOTBAR_SIZE = 9;

    /** 拿起 → 点盔甲槽 → 旧的放回。 */
    public static final int EQUIP_ARMOR = 0;
    /** mode 2 数字键交换：把 slotA 换进快捷栏第 hotbarIndex 格。 */
    public static final int SWAP_HOTBAR = 1;
    /** 把 slotA 挪进空槽 slotB。 */
    public static final int MOVE = 2;
    /** 两格互换（3 次点击）。 */
    public static final int SWAP = 3;
    /** 合并同物品的两摞。 */
    public static final int MERGE = 4;
    /** 丢掉整摞。 */
    public static final int DROP = 5;

    private InventoryPlanner() {
    }

    // ==================================================================
    // 数据
    // ==================================================================

    /** 一格物品的精简描述：规划只需要这些字段。 */
    public static final class Item {
        public final String key;
        public final String name;
        public final int kind;
        public final int armorType;
        public final int family;
        public final int count;
        public final int maxStack;
        public final double score;
        public final boolean fullBlock;

        public Item(String key, String name, int kind, int armorType, int family, int count,
                int maxStack, double score, boolean fullBlock) {
            this.key = key == null ? "" : key;
            this.name = name == null ? "" : name;
            this.kind = kind;
            this.armorType = armorType;
            this.family = family;
            this.count = count;
            this.maxStack = maxStack <= 0 ? 1 : maxStack;
            this.score = score;
            this.fullBlock = fullBlock;
        }

        /** 同物品、换数量（容器模拟 / 测试用；规划本身只读字段）。 */
        public Item withCount(int newCount) {
            return new Item(key, name, kind, armorType, family, newCount, maxStack, score, fullBlock);
        }

        public boolean sameKind(Item other) {
            return other != null && key.equals(other.key);
        }

        @Override
        public String toString() {
            return name + "x" + count + "@" + Math.round(score);
        }
    }

    /** 一次整理的输入快照。 */
    public static final class State {
        public final Item[] slots = new Item[SLOTS];

        /** 自动穿盔甲（更好的替换更差的）。 */
        public boolean autoArmor = true;
        /** 按 武器 / 方块 / 食物 / 工具 重排快捷栏。 */
        public boolean sortHotbar = true;
        /** 丢掉多余的武器与装备。 */
        public boolean dropExtras = true;
        /** 连多余的工具也丢（同家族只留 keepTools 把）。 */
        public boolean dropTools = false;
        /** 武器保留把数。 */
        public int keepWeapons = 1;
        /** 每种盔甲保留的备用件数（身上的不算）。 */
        public int spareArmor = 1;
        /** 每种工具家族保留把数。 */
        public int keepTools = 1;

        public Item get(int slot) {
            return slot < 0 || slot >= SLOTS ? null : slots[slot];
        }

        public void set(int slot, Item item) {
            if (slot >= 0 && slot < SLOTS) {
                slots[slot] = item;
            }
        }

        public State copy() {
            State copy = new State();
            for (int i = 0; i < SLOTS; i++) {
                copy.slots[i] = slots[i];
            }
            copy.autoArmor = autoArmor;
            copy.sortHotbar = sortHotbar;
            copy.dropExtras = dropExtras;
            copy.dropTools = dropTools;
            copy.keepWeapons = keepWeapons;
            copy.spareArmor = spareArmor;
            copy.keepTools = keepTools;
            return copy;
        }
    }

    /** 下一步动作。 */
    public static final class Action {
        public final int type;
        public final int slotA;
        public final int slotB;
        public final int hotbarIndex;
        /** EQUIP_ARMOR：目标盔甲槽原来有东西（因此需要第三次点击把旧甲放回去）。 */
        public final boolean occupied;
        /** MERGE：合并后光标刚好清空，可以少发一次点击。 */
        public final boolean consumesAll;

        public Action(int type, int slotA, int slotB, int hotbarIndex, boolean occupied,
                boolean consumesAll) {
            this.type = type;
            this.slotA = slotA;
            this.slotB = slotB;
            this.hotbarIndex = hotbarIndex;
            this.occupied = occupied;
            this.consumesAll = consumesAll;
        }

        /** 该动作要发的点击序列：每行 {槽位, 按钮, 模式}。 */
        public int[][] clicks() {
            switch (type) {
                case EQUIP_ARMOR:
                    return occupied
                            ? new int[][]{{slotA, 0, 0}, {slotB, 0, 0}, {slotA, 0, 0}}
                            : new int[][]{{slotA, 0, 0}, {slotB, 0, 0}};
                case SWAP_HOTBAR:
                    return new int[][]{{slotA, hotbarIndex, 2}};
                case MOVE:
                    // 目标格是空的：拿起 → 放下，第二次点击就已落位。
                    return new int[][]{{slotA, 0, 0}, {slotB, 0, 0}};
                case SWAP:
                    return new int[][]{{slotA, 0, 0}, {slotB, 0, 0}, {slotA, 0, 0}};
                case MERGE:
                    return consumesAll
                            ? new int[][]{{slotA, 0, 0}, {slotB, 0, 0}}
                            : new int[][]{{slotA, 0, 0}, {slotB, 0, 0}, {slotA, 0, 0}};
                case DROP:
                    return new int[][]{{slotA, 0, 4}};
                default:
                    return new int[0][];
            }
        }

        public String describe() {
            switch (type) {
                case EQUIP_ARMOR:
                    return "equip " + slotA + "->" + slotB;
                case SWAP_HOTBAR:
                    return "hotbar " + slotA + "->" + (hotbarIndex + 1);
                case MOVE:
                    return "move " + slotA + "->" + slotB;
                case SWAP:
                    return "swap " + slotA + "<->" + slotB;
                case MERGE:
                    return "merge " + slotA + "->" + slotB;
                case DROP:
                    return "drop " + slotA;
                default:
                    return "noop";
            }
        }
    }

    // ==================================================================
    // 规划
    // ==================================================================

    /** 算下一步；背包已经整理好时返回 {@code null}。 */
    public static Action next(State state) {
        if (state == null) {
            return null;
        }
        Action action;
        if (state.autoArmor && (action = armorAction(state)) != null) {
            return action;
        }
        if (state.dropExtras && (action = dropAction(state)) != null) {
            return action;
        }
        if ((action = mergeAction(state)) != null) {
            return action;
        }
        if (state.sortHotbar && (action = hotbarAction(state)) != null) {
            return action;
        }
        return null;
    }

    /** 盔甲：按 头盔/胸甲/护腿/靴子 依次找更好的换上。 */
    private static Action armorAction(State state) {
        for (int type = InventoryRules.ARMOR_HELMET; type <= InventoryRules.ARMOR_BOOTS; type++) {
            int armorSlot = ARMOR_FIRST + type;
            Item equipped = state.slots[armorSlot];
            // 盔甲槽里如果是别的东西（改过数据 / 别的客户端留下的），当它不存在，
            // 让背包里正常的盔甲把这一格换掉。
            boolean valid = equipped != null && equipped.armorType == type;
            double bestScore = valid ? equipped.score : Double.NEGATIVE_INFINITY;
            int bestSlot = -1;
            for (int slot = MAIN_FIRST; slot <= HOTBAR_LAST; slot++) {
                Item item = state.slots[slot];
                if (item == null || item.kind != InventoryRules.KIND_ARMOR || item.armorType != type) {
                    continue;
                }
                if (item.score > bestScore + 0.001D) {
                    bestScore = item.score;
                    bestSlot = slot;
                }
            }
            if (bestSlot >= 0) {
                return new Action(EQUIP_ARMOR, bestSlot, armorSlot, 0, equipped != null, true);
            }
        }
        return null;
    }

    /** 丢弃：武器超出保留把数、盔甲备用超出、工具（可选）超出。 */
    private static Action dropAction(State state) {
        List<Integer> weapons = collectSwords(state);
        if (weapons.size() > state.keepWeapons) {
            Collections.sort(weapons, new ScoreOrder(state, true));
            return new Action(DROP, weapons.get(0).intValue(), 0, 0, false, true);
        }
        for (int type = InventoryRules.ARMOR_HELMET; type <= InventoryRules.ARMOR_BOOTS; type++) {
            List<Integer> pieces = collect(state, InventoryRules.KIND_ARMOR, type);
            if (pieces.size() > Math.max(0, state.spareArmor)) {
                Collections.sort(pieces, new ScoreOrder(state, true));
                return new Action(DROP, pieces.get(0).intValue(), 0, 0, false, true);
            }
        }
        if (state.dropTools) {
            for (int family = 0; family <= InventoryRules.FAMILY_UTILITY; family++) {
                List<Integer> tools = collectFamily(state, family);
                if (tools.size() > Math.max(0, state.keepTools)) {
                    Collections.sort(tools, new ScoreOrder(state, true));
                    return new Action(DROP, tools.get(0).intValue(), 0, 0, false, true);
                }
            }
        }
        return null;
    }

    /** 收集某大类（armorType 为 -1 时不分盔甲槽）在背包 / 快捷栏里的槽位。 */
    private static List<Integer> collect(State state, int kind, int armorType) {
        List<Integer> slots = new ArrayList<Integer>();
        for (int slot = MAIN_FIRST; slot <= HOTBAR_LAST; slot++) {
            Item item = state.slots[slot];
            if (item == null || item.kind != kind) {
                continue;
            }
            if (armorType >= 0 && item.armorType != armorType) {
                continue;
            }
            slots.add(Integer.valueOf(slot));
        }
        return slots;
    }

    /** 只把剑算进「多余武器」：弓是远距离武器，通常另外留一把，不参与丢弃。 */
    private static List<Integer> collectSwords(State state) {
        List<Integer> slots = new ArrayList<Integer>();
        for (int slot = MAIN_FIRST; slot <= HOTBAR_LAST; slot++) {
            Item item = state.slots[slot];
            if (item != null && item.kind == InventoryRules.KIND_WEAPON
                    && item.family == InventoryRules.FAMILY_SWORD) {
                slots.add(Integer.valueOf(slot));
            }
        }
        return slots;
    }

    private static List<Integer> collectFamily(State state, int family) {
        List<Integer> slots = new ArrayList<Integer>();
        for (int slot = MAIN_FIRST; slot <= HOTBAR_LAST; slot++) {
            Item item = state.slots[slot];
            if (item != null && item.kind == InventoryRules.KIND_TOOL && item.family == family) {
                slots.add(Integer.valueOf(slot));
            }
        }
        return slots;
    }

    /** 按评分排序；{@code ascending} 时最差的排在最前（丢弃用）。 */
    private static final class ScoreOrder implements Comparator<Integer> {
        private final State state;
        private final boolean ascending;

        ScoreOrder(State state, boolean ascending) {
            this.state = state;
            this.ascending = ascending;
        }

        public int compare(Integer a, Integer b) {
            Item left = state.slots[a.intValue()];
            Item right = state.slots[b.intValue()];
            double l = left == null ? 0.0D : left.score;
            double r = right == null ? 0.0D : right.score;
            int byScore = Double.compare(l, r);
            if (byScore != 0) {
                return ascending ? byScore : -byScore;
            }
            // 评分相同时按名称定序：丢弃只会在数量上单调减少，不会来回搬。
            String lk = left == null ? "" : left.key;
            String rk = right == null ? "" : right.key;
            return lk.compareTo(rk);
        }
    }

    /** 合并同物品的零散堆叠（只在放得下的前提下合并，避免来回搬）。 */
    private static Action mergeAction(State state) {
        for (int a = MAIN_FIRST; a <= HOTBAR_LAST; a++) {
            Item left = state.slots[a];
            if (left == null || left.maxStack <= 1) {
                continue;
            }
            for (int b = a + 1; b <= HOTBAR_LAST; b++) {
                Item right = state.slots[b];
                if (right == null || !left.key.equals(right.key)) {
                    continue;
                }
                int total = left.count + right.count;
                if (total > left.maxStack) {
                    continue;
                }
                int source = left.count <= right.count ? a : b;
                int target = source == a ? b : a;
                Item sourceItem = state.slots[source];
                Item targetItem = state.slots[target];
                return new Action(MERGE, source, target, 0, false,
                        sourceItem.count + targetItem.count == sourceItem.maxStack);
            }
        }
        return null;
    }

    /**
     * 快捷栏布局：固定四格 + 补位 + 清杂物。
     *
     * <p>前四格按需求钉死：<b>1 武器 / 2 方块 / 3 食物 / 4 工具</b>。某一类没有物品时
     * 那一格就空着（{@link #hotbarOrder} 里用 -1 占位），后面的类不会被顶上来 ——
     * 「武器永远在第 1 格」比「尽量填满」更符合玩家肌肉记忆。
     * 5~9 依次补方块 / 食物 / 工具的剩余堆叠。
     */
    private static Action hotbarAction(State state) {
        List<Item> pool = new ArrayList<Item>();
        List<Integer> poolSlots = new ArrayList<Integer>();
        for (int slot = MAIN_FIRST; slot <= HOTBAR_LAST; slot++) {
            Item item = state.slots[slot];
            if (item == null || !InventoryRules.hotbarWorthy(item.kind)) {
                continue;
            }
            pool.add(item);
            poolSlots.add(Integer.valueOf(slot));
        }
        List<Integer> order = hotbarOrder(pool);
        boolean[] claimed = new boolean[HOTBAR_SIZE];

        // 1) 逐格把目标物品换进快捷栏（数字键动作，一次点击）。
        for (int i = 0; i < order.size() && i < HOTBAR_SIZE; i++) {
            int index = order.get(i).intValue();
            if (index < 0) {
                continue; // 这一类暂时没有物品：这一格留空给杂物清理阶段
            }
            claimed[i] = true;
            Item want = pool.get(index);
            int wantSlot = poolSlots.get(index).intValue();
            int target = HOTBAR_FIRST + i;
            Item current = state.slots[target];
            if (current != null && current.key.equals(want.key)) {
                continue;
            }
            if (wantSlot == target) {
                continue;
            }
            return new Action(SWAP_HOTBAR, wantSlot, 0, i, false, true);
        }

        // 2) 目标格之外还剩在快捷栏里的杂物：挪进背包空位。
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (claimed[i]) {
                continue;
            }
            int slot = HOTBAR_FIRST + i;
            if (state.slots[slot] == null) {
                continue;
            }
            int empty = firstEmptyMain(state);
            if (empty < 0) {
                continue; // 背包没空位，杂物先留在快捷栏
            }
            return new Action(MOVE, slot, empty, 0, false, true);
        }
        return null;
    }

    /**
     * 计算快捷栏的目标顺序（返回 {@code pool} 的下标）。
     *
     * <p>前四项固定是 武器 / 方块 / 食物 / 工具（没有就是 -1 占位），
     * 之后按「武器 → 方块 → 食物 → 工具」+ 评分 + 数量 + 名称排剩余物品。
     * 排序键里带上名称，是为了在评分完全相同时也有一个<b>与槽位无关</b>的稳定次序 ——
     * 否则两件评分相同的物品会随着交换在「第 1 格 / 背包」之间来回跳。
     */
    static List<Integer> hotbarOrder(List<Item> pool) {
        List<Integer> order = new ArrayList<Integer>();
        boolean[] taken = new boolean[pool.size()];
        int[] staple = {InventoryRules.KIND_WEAPON, InventoryRules.KIND_BLOCK,
                InventoryRules.KIND_FOOD, InventoryRules.KIND_TOOL};
        for (int i = 0; i < staple.length; i++) {
            int best = bestOf(pool, taken, staple[i]);
            if (best >= 0) {
                taken[best] = true;
            }
            order.add(Integer.valueOf(best)); // -1 = 这一格留给该类，暂时空着
        }
        List<Integer> rest = new ArrayList<Integer>();
        for (int i = 0; i < pool.size(); i++) {
            if (!taken[i]) {
                rest.add(Integer.valueOf(i));
            }
        }
        Collections.sort(rest, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return compareItems(pool.get(a.intValue()), pool.get(b.intValue()));
            }
        });
        for (int i = 0; i < rest.size() && order.size() < HOTBAR_SIZE; i++) {
            order.add(rest.get(i));
        }
        return order;
    }

    /** 快捷栏排序键：大类 → 评分高优先 → 数量多优先 → 名称稳定。 */
    private static int compareItems(Item left, Item right) {
        int byCategory = InventoryRules.categoryRank(left.kind)
                - InventoryRules.categoryRank(right.kind);
        if (byCategory != 0) {
            return byCategory;
        }
        int byScore = Double.compare(right.score, left.score);
        if (byScore != 0) {
            return byScore;
        }
        int byCount = right.count - left.count;
        if (byCount != 0) {
            return byCount;
        }
        return left.key.compareTo(right.key);
    }

    /** 某大类里最好的一件（{@link #compareItems} 最优）、尚未被占用的下标；没有返回 -1。 */
    private static int bestOf(List<Item> pool, boolean[] taken, int kind) {
        int best = -1;
        for (int i = 0; i < pool.size(); i++) {
            if (taken[i] || pool.get(i).kind != kind) {
                continue;
            }
            if (best < 0 || compareItems(pool.get(i), pool.get(best)) < 0) {
                best = i;
            }
        }
        return best;
    }

    private static int firstEmptyMain(State state) {
        for (int slot = MAIN_FIRST; slot <= MAIN_LAST; slot++) {
            if (state.slots[slot] == null) {
                return slot;
            }
        }
        return -1;
    }

    // ==================================================================
    // 调试 / 测试辅助
    // ==================================================================

    /** 背包是否已经处于目标状态（测试用来断言收敛）。 */
    public static boolean isSettled(State state) {
        return next(state) == null;
    }

    /** 快捷栏第 index 格里的物品 key，空槽返回 null。 */
    public static String hotbarKey(State state, int index) {
        Item item = state.get(HOTBAR_FIRST + index);
        return item == null ? null : item.key;
    }

    /** 当前身上四件盔甲的 key，按 头盔/胸甲/护腿/靴子 顺序。 */
    public static String[] armorKeys(State state) {
        String[] keys = new String[4];
        for (int i = 0; i < 4; i++) {
            Item item = state.get(ARMOR_FIRST + i);
            keys[i] = item == null ? null : item.key;
        }
        return keys;
    }

    /** 统计某大类在背包 + 快捷栏里的件数（不含盔甲槽）。 */
    public static int countKind(State state, int kind) {
        int count = 0;
        for (int slot = MAIN_FIRST; slot <= HOTBAR_LAST; slot++) {
            Item item = state.slots[slot];
            if (item != null && item.kind == kind) {
                count++;
            }
        }
        return count;
    }
}
