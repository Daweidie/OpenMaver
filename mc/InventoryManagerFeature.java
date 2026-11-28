import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import util.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * 自动整理背包：穿盔甲、按 武器 / 方块 / 食物 / 工具 排快捷栏、丢掉多余装备。
 *
 * <h3>做什么</h3>
 * <ol>
 *   <li><b>自动穿盔甲</b>：背包里如果有更好的头盔 / 胸甲 / 护腿 / 靴子，
 *       就把它换到身上，差的放回原格。评分按护甲点 + 保护类附魔 + 剩余耐久，
 *       见 {@link InventoryRules#scoreOf}。</li>
 *   <li><b>快捷栏布局</b>：固定顺序
 *       <b>1 武器 → 2 方块 → 3 食物（金苹果优先）→ 4 工具（镐优先）</b>，
 *       5~9 依次补方块 / 食物 / 工具的剩余堆叠；杂物挪回背包。</li>
 *   <li><b>丢多余</b>：剑超过保留把数、某种盔甲备用件超过保留数（可选工具同理）
 *       时，丢掉评分最低的那些。</li>
 * </ol>
 *
 * <h3>怎么点</h3>
 * 全部走玩家背包窗口（windowId 0）的 {@code PlayerControllerMP.windowClick}
 * （{@code func_78753_a}），和玩家自己开背包点格子完全相同：
 * <ul>
 *   <li>换快捷栏用 <b>mode 2</b>（数字键动作），一次点击完成，等价于玩家按 1~9；</li>
 *   <li>换盔甲 / 合并 / 挪位用 <b>mode 0</b>（拿起 → 放下 → 余数放回）；</li>
 *   <li>丢东西用 <b>mode 4</b>（丢弃键）。</li>
 * </ul>
 * 一步动作之间隔 {@code CLICK GAP} 个 tick（开 HUMANIZE 时为 90~200ms 随机），
 * 每个 tick 最多 3 次点击，和真人开背包整理的手速一致。
 *
 * <h3>边界</h3>
 * 只在「当前窗口就是玩家背包」时动手：开着箱子 / 漏斗 / 创造物品栏时完全不碰，
 * 那段时间交给 {@code ChestFeature}。背包界面开着时点的是同一个窗口；
 * 界面关掉时服务端的 {@code openContainer} 仍然是背包（windowId 0），
 * 默认两种情况都会自动整理；开启 ONLY INVENTORY 后仅在背包界面打开时整理。
 * 头颅、箱子等功能物品以及带自定义名称 / Lore 的物品
 * 始终保留原位，快捷栏排序也避开其槽位。核心模块不引用 MC 类型，mod 与 bootstrap
 * 注入两种类加载方式都能用，因此所有 MC 成员都按 MCP / SRG 双名反射访问。
 */
public final class InventoryManagerFeature {

    private static final String TAG = "Inventory Manager";
    private static final String MODULE_NAME = "Inventory Manager";
    private static final int WINDOW_ID = 0;

    private static final String DEFAULT_SETTINGS_PATH = "config/openmaver-inventory.properties";
    private static final int SETTINGS_VERSION = 1;

    // ==================================================================
    // 设置
    // ==================================================================

    private static boolean onlyInventoryOpen = false;
    private static boolean autoArmor = true;
    private static boolean autoSort = true;
    private static boolean dropExtras = true;
    private static boolean dropTools = false;
    private static int keepWeapons = 1;
    private static int spareArmor = 0;
    private static int keepTools = 1;
    private static boolean humanize = true;
    private static int clickGapTicks = 2;
    private static int actionDelayMinMs = 90;
    private static int actionDelayMaxMs = 200;
    private static int sortIntervalTicks = 40;

    private static long settingsModified = Long.MIN_VALUE;
    private static boolean settingsDirty;
    private static int settingsCheckTicks;

    // ==================================================================
    // 运行时状态
    // ==================================================================

    private static int tick;
    private static long nextActionNanos;
    private static boolean clickWarned;
    private static boolean failed;
    private static boolean warnedNoWindow;
    private static String lastActionKey;
    private static int repeatCount;
    private static int actions;
    private static int lastArmorCount;
    private static final java.util.Random RANDOM = new java.util.Random();

    private InventoryManagerFeature() {
    }

    // ==================================================================
    // 入口：每个 client tick 的 START（必须早于原版发 C03）
    // ==================================================================

    /**
     * 客户端 tick 开始：读背包 → 规划 → 发一次点击。
     *
     * <p>必须挂在 {@code TickEvent.Phase.START}：{@code windowClick}（C0E）要排在
     * 本 tick 的 C03 之前，否则服务端的 transaction ack（C0F）会夹在两者中间，
     * 和 {@code ChestFeature} 里记录的那条 Grim {@code click window} 告警是同一个原因。
     */
    public static void onTickStart(Minecraft mc) {
        if (mc == null || failed) {
            return;
        }
        tick++;
        if (!enabled()) {
            reset();
            return;
        }
        try {
            refreshSettings();
        } catch (Throwable ignored) {
            // 配置读写问题不能影响游戏循环。
        }
        Object player = read(mc, "thePlayer", "field_71439_g", null);
        Object world = read(mc, "theWorld", "field_71441_e", null);
        Object controller = read(mc, "playerController", "field_71442_b", null);
        if (player == null || world == null || controller == null) {
            reset();
            return;
        }
        if (!ownsPlayerWindow(player)) {
            // 开着别的容器（箱子 / 漏斗 / 创造物品栏）：窗口号不是 0，碰了就错位。
            return;
        }
        Object screen = read(mc, "currentScreen", "field_71462_r", null);
        if (onlyInventoryOpen && screen == null) {
            return;
        }
        if (screen != null && !isInventoryScreen(screen)) {
            // 聊天 / 设置 / 其它界面：不偷偷点格子。
            return;
        }
        Object inv = read(player, "inventory", "field_71071_by", null);
        if (inv == null) {
            return;
        }
        // getItemStack = 光标上拿着的那一摞（func_70445_o）；
        // func_70448_g 是 getCurrentItem（手上拿的快捷栏物品），别用错。
        Object cursor = call(inv, "getItemStack", "func_70445_o", null);
        if (cursor != null) {
            // 光标上还拿着东西（玩家正在拖 / 上一次被打断）：让玩家自己放完再做。
            return;
        }
        long now = System.nanoTime();
        if (now < nextActionNanos) {
            return;
        }
        Object[] main = arrayOf(read(inv, "mainInventory", "field_70462_a", null));
        Object[] armor = arrayOf(read(inv, "armorInventory", "field_70460_b", null));
        if (main == null || main.length < 36) {
            return;
        }
        InventoryPlanner.State state = buildState(main, armor,
                intOf(read(inv, "currentItem", "field_70461_c", null)));
        lastArmorCount = armorCount(state);
        InventoryPlanner.Action action = InventoryPlanner.next(state);
        if (action == null) {
            // 已经整理好：隔一段时间再扫一次，避免每 tick 重算整背包。
            lastActionKey = null;
            repeatCount = 0;
            nextActionNanos = now + intervalNanos();
            return;
        }
        String key = action.type + ":" + action.slotA + ":" + action.slotB + ":" + action.hotbarIndex;
        if (key.equals(lastActionKey)) {
            repeatCount++;
        } else {
            lastActionKey = key;
            repeatCount = 1;
        }
        if (repeatCount > 8) {
            // 同一动作连续 8 次没生效（服务端拒绝 / 窗口错位）：先停一秒再说。
            lastActionKey = null;
            repeatCount = 0;
            nextActionNanos = now + 1_000_000_000L;
            Logger.debug(TAG, "整理动作未生效，暂停 1 秒: " + action.describe());
            return;
        }
        if (!execute(controller, player, action)) {
            return;
        }
        actions++;
        // 日志别刷屏：换甲 / 丢弃这两类「有后果」的动作每次都记，
        // 挪格子的动作每 16 步记一次。
        if (action.type == InventoryPlanner.EQUIP_ARMOR
                || action.type == InventoryPlanner.DROP
                || actions % 16 == 0) {
            Logger.debug(TAG, "整理: " + action.describe()
                    + "（第 " + actions + " 步，快捷栏 武器/方块/食物/工具 = "
                    + summaryOf(state) + "）");
        }
        nextActionNanos = now + gapNanos();
    }

    private static void reset() {
        lastActionKey = null;
        repeatCount = 0;
        nextActionNanos = 0L;
    }

    private static boolean enabled() {
        Module module = ModuleManager.getModule(MODULE_NAME);
        return module != null && module.isEnabled();
    }

    // ==================================================================
    // 背包快照
    // ==================================================================

    private static InventoryPlanner.State buildState(Object[] main, Object[] armor,
            int selectedHotbarIndex) {
        InventoryPlanner.State state = new InventoryPlanner.State();
        state.autoArmor = autoArmor;
        state.sortHotbar = autoSort;
        state.dropExtras = dropExtras;
        state.dropTools = dropTools;
        state.keepWeapons = Math.max(1, keepWeapons);
        state.selectedHotbarIndex = selectedHotbarIndex;
        state.spareArmor = Math.max(0, spareArmor);
        state.keepBlockStacks = 2;
        state.keepTools = Math.max(1, keepTools);
        // InventoryPlayer.mainInventory：0..8 快捷栏，9..35 背包主区。
        for (int i = 0; i < 36; i++) {
            int slot = i < 9 ? InventoryPlanner.HOTBAR_FIRST + i : i;
            state.set(slot, itemOf(i < main.length ? main[i] : null));
        }
        if (armor != null) {
            // InventoryPlayer.armorInventory：0 靴子 / 1 护腿 / 2 胸甲 / 3 头盔。
            for (int i = 0; i < 4 && i < armor.length; i++) {
                state.set(InventoryPlanner.ARMOR_LAST - i, itemOf(armor[i]));
            }
        }
        return state;
    }

    /** 把 {@code ItemStack} 读成规划器要的纯数据；不能识别的非空物品保留原位。 */
    private static InventoryPlanner.Item itemOf(Object stack) {
        if (stack == null) {
            return null;
        }
        int count = intOf(read(stack, "stackSize", "field_77994_a", null));
        if (count <= 0) {
            return null;
        }
        Object item = call(stack, "getItem", "func_77973_b", null);
        String raw = asString(call(stack, "getUnlocalizedName", "func_77977_a", null));
        String name = InventoryRules.clean(raw);
        if (name.isEmpty() && item != null) {
            name = InventoryRules.clean(asString(call(item, "getUnlocalizedName", "func_77658_a", null)));
        }
        Object nbt = call(stack, "getTagCompound", "func_77978_p", null);
        boolean protectedItem = hasProtectedMetadata(stack, nbt);
        int damage = intOf(call(stack, "getItemDamage", "func_77960_j", null));
        if (damage < 0) {
            damage = 0;
        }
        int maxStack = intOf(call(stack, "getMaxStackSize", "func_77976_d", null));
        if (maxStack <= 0) {
            maxStack = 1;
        }
        int maxDamage = intOf(call(stack, "getMaxDamage", "func_77958_k", null));
        if (maxDamage < 0) {
            maxDamage = 0;
        }
        boolean blockItem = InventoryRules.isTileName(raw) || isBlockItem(item);
        boolean fullBlock = blockFullCube(item);
        int[] levels = enchantLevels(stack);
        double durability = maxDamage > 0
                ? Math.max(0.0D, Math.min(1.0D, (maxDamage - damage) / (double) maxDamage))
                : 1.0D;
        int armorType = InventoryRules.armorTypeOf(name);
        int kind = InventoryRules.kindOf(name, blockItem);
        int family = InventoryRules.FAMILY_NONE;
        if (kind == InventoryRules.KIND_WEAPON) {
            family = InventoryRules.weaponFamilyOf(name);
        } else if (kind == InventoryRules.KIND_TOOL) {
            family = InventoryRules.toolFamilyOf(name);
        }
        double score;
        if (kind == InventoryRules.KIND_FOOD) {
            // 食物按饱食度/价值排，金苹果的 damage 用来区分附魔金苹果。
            score = InventoryRules.foodScore(name, damage);
        } else {
            score = InventoryRules.scoreOf(name, kind, armorType, family, count, fullBlock,
                    levels[0], levels[1], levels[2], levels[3], durability);
        }
        // 服务端自定义 NBT、药水效果等不同的物品不能被误认为同一摞。
        String key = name + "#" + damage + (nbt == null ? "" : "#" + nbt.toString());
        return new InventoryPlanner.Item(key, name, kind, armorType, family,
                count, maxStack, score, fullBlock, protectedItem);
    }

    private static boolean hasProtectedMetadata(Object stack, Object nbt) {
        if (Boolean.TRUE.equals(call(stack, "hasDisplayName", "func_82837_s", null))) {
            return true;
        }
        if (nbt == null) {
            return false;
        }
        Object display = call(nbt, "getCompoundTag", "func_74775_l", null, "display");
        return hasTag(display, "Name", 8) || hasTag(display, "Lore", 9)
                || hasTag(nbt, "SkullOwner", 8) || hasTag(nbt, "SkullOwner", 10);
    }

    private static boolean hasTag(Object nbt, String key, int type) {
        return Boolean.TRUE.equals(call(nbt, "hasKey", "func_150297_b", null,
                key, Integer.valueOf(type)));
    }

    private static int armorCount(InventoryPlanner.State state) {
        int count = 0;
        for (int i = 0; i < 4; i++) {
            if (state.get(InventoryPlanner.ARMOR_FIRST + i) != null) {
                count++;
            }
        }
        return count;
    }

    private static String summaryOf(InventoryPlanner.State state) {
        int weapon = 0;
        int block = 0;
        int food = 0;
        int tool = 0;
        for (int i = 0; i < InventoryPlanner.HOTBAR_SIZE; i++) {
            InventoryPlanner.Item item = state.get(InventoryPlanner.HOTBAR_FIRST + i);
            if (item == null) {
                continue;
            }
            switch (item.kind) {
                case InventoryRules.KIND_WEAPON:
                    weapon++;
                    break;
                case InventoryRules.KIND_BLOCK:
                    block++;
                    break;
                case InventoryRules.KIND_FOOD:
                    food++;
                    break;
                case InventoryRules.KIND_TOOL:
                    tool++;
                    break;
                default:
                    break;
            }
        }
        return weapon + "/" + block + "/" + food + "/" + tool;
    }

    private static boolean isBlockItem(Object item) {
        if (item == null) {
            return false;
        }
        Class<?> itemBlock = classOrNull("net.minecraft.item.ItemBlock");
        if (itemBlock != null && itemBlock.isInstance(item)) {
            return true;
        }
        return read(item, "block", "field_150939_a", null) != null;
    }

    /** 方块是否完整方块（火把 / 台阶 / 栏杆之类排优先级时靠后）。 */
    private static boolean blockFullCube(Object item) {
        if (item == null) {
            return true;
        }
        Object block = read(item, "block", "field_150939_a", null);
        if (block == null) {
            return true;
        }
        Object value = call(block, "isFullCube", "func_149686_d", null);
        return !(value instanceof Boolean) || ((Boolean) value).booleanValue();
    }

    /**
     * 附魔等级汇总：{保护类, 伤害类, 效率, 耐久}。
     *
     * <p>NBT 方法名在 SRG 环境里同样是 {@code func_*}，所以按「短的/长的」
     * 顺序多试几个名字；全失败时返回 0，评分退化成只看材质与耐久，不影响整理。
     */
    private static int[] enchantLevels(Object stack) {
        int[] levels = new int[4];
        Object list = call(stack, "getEnchantmentTagList", "func_77986_q", null);
        if (list == null) {
            Object nbt = call(stack, "getTagCompound", "func_77978_p", null);
            if (nbt != null) {
                list = call(nbt, "getTagList", "func_150295_c", null, "ench", Integer.valueOf(10));
            }
        }
        if (list == null) {
            return levels;
        }
        int size = intOf(call(list, "tagCount", "func_74745_c", null));
        for (int i = 0; i < size; i++) {
            Object tag = call(list, "getCompoundTagAt", "func_150305_b", null, Integer.valueOf(i));
            if (tag == null) {
                continue;
            }
            int id = nbtInt(tag, "id");
            int level = nbtInt(tag, "lvl");
            if (id < 0 || level <= 0) {
                continue;
            }
            if (id <= 3) {
                levels[0] += level;          // 保护 / 火焰保护 / 摔落保护 / 爆炸保护
            } else if (id >= 16 && id <= 18) {
                levels[1] += level;          // 锋利 / 亡灵杀手 / 节肢杀手
            } else if (id == 32) {
                levels[2] += level;          // 效率
            } else if (id == 34) {
                levels[3] += level;          // 耐久
            }
        }
        return levels;
    }

    private static int nbtInt(Object tag, String key) {
        String[] mcp = {"getShort", "getByte", "getInteger"};
        String[] srg = {"func_74765_d", "func_150290_f", "func_74762_e"};
        for (int i = 0; i < mcp.length; i++) {
            Object value = call(tag, mcp[i], srg[i], null, key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }
        return -1;
    }

    // ==================================================================
    // 点击
    // ==================================================================

    private static boolean execute(Object controller, Object player, InventoryPlanner.Action action) {
        int[][] clicks = action.clicks();
        boolean all = clicks.length > 0;
        for (int i = 0; i < clicks.length; i++) {
            all &= click(controller, player, clicks[i][0], clicks[i][1], clicks[i][2]);
        }
        return all;
    }

    /** 一次 {@code windowClick}：mode 0 普通点击 / 1 shift 点击 / 2 数字键 / 4 丢弃。 */
    private static boolean click(Object controller, Object player, int slot, int button, int mode) {
        if (slot < 0 || slot >= InventoryPlanner.SLOTS) {
            return false;
        }
        boolean ok = callVoid(controller, "windowClick", "func_78753_a", null,
                Integer.valueOf(WINDOW_ID), Integer.valueOf(slot),
                Integer.valueOf(button), Integer.valueOf(mode), player);
        if (!ok && !clickWarned) {
            clickWarned = true;
            Logger.warn(TAG, "windowClick 不可用（" + controller.getClass().getName()
                    + "）：自动整理无法进行");
        }
        return ok;
    }

    // ==================================================================
    // 窗口判定
    // ==================================================================

    /** 当前打开的容器是否就是玩家背包本身（windowId 0 才允许点）。 */
    private static boolean ownsPlayerWindow(Object player) {
        Object open = read(player, "openContainer", "field_71070_bA", null);
        Object own = read(player, "inventoryContainer", "field_71069_bz", null);
        if (open == null || own == null) {
            if (!warnedNoWindow) {
                warnedNoWindow = true;
                Logger.debug(TAG, "读不到 openContainer / inventoryContainer，暂不整理");
            }
            return false;
        }
        return open == own;
    }

    /** 玩家背包界面（{@code GuiInventory}）；mod 化后类名不变，用名字判断最稳。 */
    private static boolean isInventoryScreen(Object screen) {
        String name = screen.getClass().getName();
        return name.endsWith("GuiInventory") || name.endsWith("GuiInventoryCreative");
    }

    // ==================================================================
    // 节拍
    // ==================================================================

    private static long gapNanos() {
        if (!humanize) {
            return Math.max(1, clickGapTicks) * 50_000_000L;
        }
        int min = Math.max(20, Math.min(actionDelayMinMs, actionDelayMaxMs));
        int max = Math.max(min, actionDelayMaxMs);
        int delay = min + (max > min ? RANDOM.nextInt(max - min + 1) : 0);
        delay = Math.max(delay, Math.max(1, clickGapTicks) * 50);
        return delay * 1_000_000L;
    }

    private static long intervalNanos() {
        return Math.max(5, sortIntervalTicks) * 50_000_000L;
    }

    // ==================================================================
    // 设置持久化（config/openmaver-inventory.properties）
    // ==================================================================

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
                return;
            }
        }
        onlyInventoryOpen = readBoolean(values, "inventory.onlyInventoryOpen", false);
        autoArmor = readBoolean(values, "inventory.autoArmor", true);
        autoSort = readBoolean(values, "inventory.autoSort", true);
        dropExtras = readBoolean(values, "inventory.dropExtras", true);
        dropTools = readBoolean(values, "inventory.dropTools", false);
        keepWeapons = clamp(readInt(values, "inventory.keepWeapons", 1), 1, 4);
        spareArmor = clamp(readInt(values, "inventory.spareArmor", 0), 0, 3);
        keepTools = clamp(readInt(values, "inventory.keepTools", 1), 1, 3);
        humanize = readBoolean(values, "inventory.humanize", true);
        clickGapTicks = clamp(readInt(values, "inventory.clickGapTicks", 2), 1, 10);
        actionDelayMinMs = clamp(readInt(values, "inventory.actionDelayMinMs", 90), 20, 2000);
        actionDelayMaxMs = clamp(readInt(values, "inventory.actionDelayMaxMs", 200), 20, 3000);
        if (actionDelayMaxMs < actionDelayMinMs) {
            int swap = actionDelayMinMs;
            actionDelayMinMs = actionDelayMaxMs;
            actionDelayMaxMs = swap;
        }
        sortIntervalTicks = clamp(readInt(values, "inventory.sortIntervalTicks", 40), 5, 200);
    }

    private static void saveSettings() {
        File file = new File(settingsPath());
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(("inventory.version=" + SETTINGS_VERSION
                    + "\ninventory.onlyInventoryOpen=" + onlyInventoryOpen
                    + "\ninventory.autoArmor=" + autoArmor
                    + "\ninventory.autoSort=" + autoSort
                    + "\ninventory.dropExtras=" + dropExtras
                    + "\ninventory.dropTools=" + dropTools
                    + "\ninventory.keepWeapons=" + keepWeapons
                    + "\ninventory.spareArmor=" + spareArmor
                    + "\ninventory.keepTools=" + keepTools
                    + "\ninventory.humanize=" + humanize
                    + "\ninventory.clickGapTicks=" + clickGapTicks
                    + "\ninventory.actionDelayMinMs=" + actionDelayMinMs
                    + "\ninventory.actionDelayMaxMs=" + actionDelayMaxMs
                    + "\ninventory.sortIntervalTicks=" + sortIntervalTicks
                    + "\n").getBytes("UTF-8"));
        } catch (Exception error) {
            Logger.warn(TAG, "保存设置失败: " + error);
            return;
        }
        settingsDirty = false;
        settingsModified = file.lastModified();
    }

    private static String settingsPath() {
        return System.getProperty("openmaver.inventory", DEFAULT_SETTINGS_PATH);
    }

    public static void markDirty() {
        settingsDirty = true;
    }

    private static boolean readBoolean(Properties values, String key, boolean fallback) {
        String value = values.getProperty(key);
        if (value == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    private static int readInt(Properties values, String key, int fallback) {
        String value = values.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================================================================
    // ClickGUI / HUD 用的读写接口
    // ==================================================================

    public static boolean isOnlyInventoryOpen() {
        return onlyInventoryOpen;
    }

    public static void setOnlyInventoryOpen(boolean value) {
        onlyInventoryOpen = value;
        markDirty();
    }

    public static boolean isAutoArmor() {
        return autoArmor;
    }

    public static void setAutoArmor(boolean value) {
        autoArmor = value;
        markDirty();
    }

    public static boolean isAutoSort() {
        return autoSort;
    }

    public static void setAutoSort(boolean value) {
        autoSort = value;
        markDirty();
    }

    public static boolean isDropExtras() {
        return dropExtras;
    }

    public static void setDropExtras(boolean value) {
        dropExtras = value;
        markDirty();
    }

    public static boolean isDropTools() {
        return dropTools;
    }

    public static void setDropTools(boolean value) {
        dropTools = value;
        markDirty();
    }

    public static int getKeepWeapons() {
        return keepWeapons;
    }

    public static void setKeepWeapons(int value) {
        keepWeapons = clamp(value, 1, 4);
        markDirty();
    }

    public static int getSpareArmor() {
        return spareArmor;
    }

    public static void setSpareArmor(int value) {
        spareArmor = clamp(value, 0, 3);
        markDirty();
    }

    public static int getKeepTools() {
        return keepTools;
    }

    public static void setKeepTools(int value) {
        keepTools = clamp(value, 1, 3);
        markDirty();
    }

    public static boolean isHumanize() {
        return humanize;
    }

    public static void setHumanize(boolean value) {
        humanize = value;
        markDirty();
    }

    public static int getClickGapTicks() {
        return clickGapTicks;
    }

    public static void setClickGapTicks(int value) {
        clickGapTicks = clamp(value, 1, 10);
        markDirty();
    }

    public static int getActionDelayMinMs() {
        return actionDelayMinMs;
    }

    public static void setActionDelayMinMs(int value) {
        actionDelayMinMs = clamp(value, 20, 2000);
        if (actionDelayMaxMs < actionDelayMinMs) {
            actionDelayMaxMs = actionDelayMinMs;
        }
        markDirty();
    }

    public static int getActionDelayMaxMs() {
        return actionDelayMaxMs;
    }

    public static void setActionDelayMaxMs(int value) {
        actionDelayMaxMs = clamp(value, 20, 3000);
        if (actionDelayMaxMs < actionDelayMinMs) {
            actionDelayMinMs = actionDelayMaxMs;
        }
        markDirty();
    }

    public static int getSortIntervalTicks() {
        return sortIntervalTicks;
    }

    public static void setSortIntervalTicks(int value) {
        sortIntervalTicks = clamp(value, 5, 200);
        markDirty();
    }

    /** HUD 上显示的状态摘要：已穿盔甲件数。 */
    public static String getSummary() {
        return lastArmorCount + "/4";
    }

    public static int getActions() {
        return actions;
    }

    public static int getTick() {
        return tick;
    }

    // ==================================================================
    // 反射小工具
    // ==================================================================

    private static Object read(Object target, String mcp, String srg, Class<?> expected) {
        return OpenMaverFeatures.read(target, mcp, srg, expected);
    }

    private static Object call(Object target, String mcp, String srg, String obf, Object... args) {
        return OpenMaverFeatures.call(target, mcp, srg, obf, args);
    }

    private static boolean callVoid(Object target, String mcp, String srg, String obf, Object... args) {
        return OpenMaverFeatures.callVoid(target, mcp, srg, obf, args);
    }

    private static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Object[] arrayOf(Object value) {
        return value instanceof Object[] ? (Object[]) value : null;
    }

    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
