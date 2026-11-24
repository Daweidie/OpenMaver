import java.util.Locale;

/**
 * 背包整理用的纯规则表：分类、材质评分、盔甲槽判定。
 *
 * <p>本类<b>不引用任何 {@code net.minecraft.*}</b>，也不做反射：MC 侧的
 * {@link InventoryManagerFeature} 只负责把 {@code ItemStack} 读成
 * {@code (unlocalizedName, damage, count, maxStack, 附魔等级……)} 这些原始数据，
 * 具体的「这是什么、有多好」全部由这里算。这样规则可以在 headless 测试里
 * （{@code tools/inventory-test}）直接跑，不需要启动游戏。
 *
 * <p>分类顺序固定为：盔甲 → 武器 → 工具 → 方块 → 食物 → 其它。
 * 顺序本身有意义：{@code item.pickaxeDiamond} 同时包含 "pickaxe" 和 "axe"，
 * 必须先判工具，否则镐子会被算成斧子（武器）。
 */
public final class InventoryRules {

    // ------------------------------------------------------------------
    // 物品大类
    // ------------------------------------------------------------------

    public static final int KIND_OTHER = 0;
    public static final int KIND_WEAPON = 1;
    public static final int KIND_BLOCK = 2;
    public static final int KIND_FOOD = 3;
    public static final int KIND_TOOL = 4;
    public static final int KIND_ARMOR = 5;

    // ------------------------------------------------------------------
    // 盔甲槽（= 玩家背包容器 slot - 5）
    // ------------------------------------------------------------------

    public static final int ARMOR_NONE = -1;
    public static final int ARMOR_HELMET = 0;
    public static final int ARMOR_CHEST = 1;
    public static final int ARMOR_LEGS = 2;
    public static final int ARMOR_BOOTS = 3;

    // ------------------------------------------------------------------
    // 工具 / 武器家族
    // ------------------------------------------------------------------

    public static final int FAMILY_NONE = -1;
    public static final int FAMILY_SWORD = 0;
    public static final int FAMILY_BOW = 1;
    public static final int FAMILY_PICKAXE = 2;
    public static final int FAMILY_AXE = 3;
    public static final int FAMILY_SHOVEL = 4;
    public static final int FAMILY_HOE = 5;
    public static final int FAMILY_SHEARS = 6;
    public static final int FAMILY_UTILITY = 7;

    private InventoryRules() {
    }

    // ==================================================================
    // 名称
    // ==================================================================

    /**
     * 把 {@code getUnlocalizedName()} 规整成小写、无颜色码、无 {@code item.}/{@code tile.} 前缀的形式。
     *
     * <p>例：{@code "item.swordDiamond"} → {@code "sworddiamond"}，
     * {@code "tile.stone"} → {@code "stone"}。
     */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00A7') { // § 颜色码
                i++;
                continue;
            }
            out.append(c);
        }
        String name = out.toString().trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("item.")) {
            name = name.substring(5);
        } else if (name.startsWith("tile.")) {
            name = name.substring(5);
        }
        return name;
    }

    /** 是否是 {@code tile.xxx}（1.8.9 中方块的 unlocalizedName 前缀）。 */
    public static boolean isTileName(String raw) {
        return raw != null && raw.trim().toLowerCase(Locale.ROOT).startsWith("tile.");
    }

    // ==================================================================
    // 分类
    // ==================================================================

    /** 常见大厅入口物品即使没有自定义名称，也保留原位，不当作建材或杂物。 */
    public static boolean isFunctionalItem(String name) {
        if (name == null || name.isEmpty()) {
            return true; // 无法识别的物品也不能当作空槽或普通物资处理。
        }
        if (name.startsWith("skull")) {
            return true;
        }
        switch (name) {
            case "head":
            case "playerhead":
            case "chest":
            case "chesttrap":
            case "trappedchest":
            case "enderchest":
            case "hopper":
            case "dispenser":
            case "dropper":
            case "compass":
            case "clock":
            case "watch":
            case "netherstar":
            case "paper":
            case "book":
            case "enchantedbook":
            case "writtenbook":
            case "writingbook":
            case "bed":
                return true;
            default:
                return false;
        }
    }

    /**
     * 盔甲槽：0 头盔 / 1 胸甲 / 2 护腿 / 3 靴子，非盔甲返回 {@link #ARMOR_NONE}。
     *
     * <p>1.8.9 的 unlocalizedName 用的是老名字：皮革盔甲是 {@code helmetCloth} /
     * {@code chestplateCloth} / {@code leggingsCloth} / {@code bootsCloth}。
     */
    public static int armorTypeOf(String clean) {
        if (clean == null || clean.isEmpty()) {
            return ARMOR_NONE;
        }
        // 只认「部件名」，不认 "chest" / "boot" 这种方块也能撞上的裸词：
        // 箱子（tile.chest）不是胸甲。
        if (clean.contains("helmet") || clean.contains("helm")) {
            return ARMOR_HELMET;
        }
        if (clean.contains("chestplate") || clean.contains("cuirass")) {
            return ARMOR_CHEST;
        }
        if (clean.contains("leggings") || clean.contains("greaves")
                || clean.contains("legs") || clean.contains("pants")) {
            return ARMOR_LEGS;
        }
        if (clean.contains("boots")) {
            return ARMOR_BOOTS;
        }
        return ARMOR_NONE;
    }

    /** 武器家族：剑 / 弓；其它返回 {@link #FAMILY_NONE}。斧子按工具算（见类注释）。 */
    public static int weaponFamilyOf(String clean) {
        if (clean == null || clean.isEmpty()) {
            return FAMILY_NONE;
        }
        if (clean.contains("sword") || clean.contains("katana") || clean.contains("dagger")) {
            return FAMILY_SWORD;
        }
        // "bowl" 也是 bow 的子串，碗不是武器。
        if (clean.contains("bow") && !clean.contains("bowl")) {
            return FAMILY_BOW;
        }
        return FAMILY_NONE;
    }

    /** 工具家族：镐 / 斧 / 锹 / 锄 / 剪刀 / 打火石。非工具返回 {@link #FAMILY_NONE}。 */
    public static int toolFamilyOf(String clean) {
        if (clean == null || clean.isEmpty()) {
            return FAMILY_NONE;
        }
        // 先判镐：pickaxe 里含 "axe"，顺序反了会被当成斧子。
        if (clean.contains("pickaxe") || clean.contains("pick")) {
            return FAMILY_PICKAXE;
        }
        if (clean.contains("shears") || clean.contains("scissors")) {
            return FAMILY_SHEARS;
        }
        if (clean.contains("shovel") || clean.contains("spade")) {
            return FAMILY_SHOVEL;
        }
        if (clean.contains("axe") || clean.contains("hatchet")) {
            return FAMILY_AXE;
        }
        // "shoe"/"boots" 里也有 "hoe"，所以只认明确的锄头命名。
        if (clean.contains("hoe") && !clean.contains("shoe")) {
            return FAMILY_HOE;
        }
        if (clean.contains("flintandsteel") || clean.contains("firestarter")) {
            return FAMILY_UTILITY;
        }
        return FAMILY_NONE;
    }

    /**
     * 物品大类。
     *
     * @param clean     规整后的名称（见 {@link #clean(String)}）
     * @param blockItem 该物品是不是 {@code ItemBlock}（/ 名字带 {@code tile.}）
     */
    public static int kindOf(String clean, boolean blockItem) {
        if (armorTypeOf(clean) != ARMOR_NONE) {
            return KIND_ARMOR;
        }
        if (weaponFamilyOf(clean) != FAMILY_NONE) {
            return KIND_WEAPON;
        }
        if (toolFamilyOf(clean) != FAMILY_NONE) {
            return KIND_TOOL;
        }
        if (blockItem) {
            return KIND_BLOCK;
        }
        if (isFoodName(clean)) {
            return KIND_FOOD;
        }
        return KIND_OTHER;
    }

    /** 食物关键词表；1.8.9 的 {@code item.appleGold} 是金苹果（damage 1 = 附魔金苹果）。 */
    public static boolean isFoodName(String clean) {
        if (clean == null || clean.isEmpty()) {
            return false;
        }
        String[] keys = {
                "applegold", "goldenapple", "apple", "bread", "beef", "porkchop", "chicken",
                "fish", "salmon", "carrot", "potato", "cookie", "melon", "mutton", "rabbit",
                "stew", "soup", "flesh", "spidereye", "beetroot", "pumpkinpie", "cake"
        };
        for (int i = 0; i < keys.length; i++) {
            if (clean.contains(keys[i])) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 材质
    // ==================================================================

    /** 材质等级：木 1 / 石-金 2 / 铁 3 / 钻石 4 / 其它 2。 */
    public static int materialRank(String clean) {
        if (clean == null) {
            return 2;
        }
        if (clean.contains("diamond")) {
            return 4;
        }
        if (clean.contains("iron")) {
            return 3;
        }
        if (clean.contains("gold")) {
            return 2;
        }
        if (clean.contains("stone")) {
            return 2;
        }
        if (clean.contains("chain")) {
            return 2;
        }
        if (clean.contains("wood") || clean.contains("cloth") || clean.contains("leather")) {
            return 1;
        }
        return 2;
    }

    /** 剑的基础伤害：木 4 / 金 4 / 石 5 / 铁 6 / 钻 7。 */
    public static double swordDamage(String clean) {
        if (clean == null) {
            return 5.0D;
        }
        if (clean.contains("diamond")) {
            return 7.0D;
        }
        if (clean.contains("iron")) {
            return 6.0D;
        }
        if (clean.contains("stone")) {
            return 5.0D;
        }
        if (clean.contains("gold")) {
            return 4.0D;
        }
        if (clean.contains("wood")) {
            return 4.0D;
        }
        return 5.0D;
    }

    /**
     * 盔甲点数：{头盔, 胸甲, 护腿, 靴子}。
     *
     * <p>皮革 1/3/2/1，金 2/5/3/1，锁链 2/5/4/1，铁 2/6/5/2，钻石 3/8/6/3。
     */
    public static int armorPoints(String clean, int armorType) {
        int index = Math.max(0, Math.min(3, armorType));
        int[] leather = {1, 3, 2, 1};
        int[] gold = {2, 5, 3, 1};
        int[] chain = {2, 5, 4, 1};
        int[] iron = {2, 6, 5, 2};
        int[] diamond = {3, 8, 6, 3};
        if (clean == null) {
            return iron[index];
        }
        if (clean.contains("diamond")) {
            return diamond[index];
        }
        if (clean.contains("iron")) {
            return iron[index];
        }
        if (clean.contains("chain")) {
            return chain[index];
        }
        if (clean.contains("gold")) {
            return gold[index];
        }
        if (clean.contains("cloth") || clean.contains("leather")) {
            return leather[index];
        }
        return iron[index];
    }

    // ==================================================================
    // 评分（只在本大类内比较，跨类没有意义）
    // ==================================================================

    /**
     * 综合评分。数值只在同一 {@code kind} 内可比：
     * <ul>
     *   <li>武器：{@code 伤害*10 + 锋利*1.5 + 耐久*0.5}</li>
     *   <li>盔甲：{@code 护甲点*10 + 保护*2 + 耐久*0.5}</li>
     *   <li>工具：{@code (家族*10 + 材质*2)*10 + 效率 + 耐久*0.5}</li>
     *   <li>方块：{@code 完整方块 100 / 其它 20 + min(数量,64)}</li>
     *   <li>食物：见 {@link #foodScore(String, int)}</li>
     * </ul>
     *
     * @param protLevel 保护类附魔等级之和（0..3）
     * @param sharpLevel 锋利 / 亡灵杀手 / 节肢杀手等级之和
     * @param effLevel  效率等级
     * @param unbLevel  耐久等级
     * @param durability 剩余耐久比例（0..1；不可损坏物品传 1）
     */
    public static double scoreOf(String clean, int kind, int armorType, int family, int count,
            boolean fullBlock, int protLevel, int sharpLevel, int effLevel, int unbLevel,
            double durability) {
        double dur = Math.max(0.0D, Math.min(1.0D, durability));
        double unb = unbLevel * 0.2D + dur * 0.5D;
        switch (kind) {
            case KIND_WEAPON:
                if (family == FAMILY_BOW) {
                    return 35.0D + sharpLevel * 1.5D + unb;
                }
                return swordDamage(clean) * 10.0D + sharpLevel * 1.5D + unb;
            case KIND_ARMOR:
                return armorPoints(clean, armorType) * 10.0D + protLevel * 2.0D + unb;
            case KIND_TOOL:
                return (toolFamilyRank(family) * 10 + materialRank(clean) * 2) * 10.0D
                        + effLevel * 1.0D + unb;
            case KIND_BLOCK:
                return (fullBlock ? 100.0D : 20.0D) + Math.min(Math.max(count, 1), 64);
            case KIND_FOOD:
                return foodScore(clean, 0);
            default:
                return 0.0D;
        }
    }

    /** 工具家族优先级：镐 > 锹 > 斧 > 锄 > 剪刀 > 打火石。 */
    public static int toolFamilyRank(int family) {
        switch (family) {
            case FAMILY_PICKAXE:
                return 5;
            case FAMILY_SHOVEL:
                return 4;
            case FAMILY_AXE:
                return 3;
            case FAMILY_HOE:
                return 2;
            case FAMILY_SHEARS:
                return 2;
            case FAMILY_UTILITY:
                return 1;
            default:
                return 0;
        }
    }

    /**
     * 食物评分：附魔金苹果 300 > 金苹果 200 > 金胡萝卜 120 > 熟肉 80 > 面包 70 ……
     *
     * @param damage {@code ItemStack.getItemDamage()}，用来区分金苹果（0）与附魔金苹果（1）
     */
    public static double foodScore(String clean, int damage) {
        if (clean == null) {
            return 10.0D;
        }
        if (clean.contains("applegold") || clean.contains("goldenapple")) {
            return damage == 1 ? 300.0D : 200.0D;
        }
        if (clean.contains("golden") && clean.contains("carrot")) {
            return 120.0D;
        }
        if (clean.contains("stew") || clean.contains("soup")) {
            return 90.0D;
        }
        if (clean.contains("bread")) {
            return 70.0D;
        }
        if (clean.contains("melon")) {
            return 60.0D;
        }
        if (clean.contains("potato") && clean.contains("baked")) {
            return 60.0D;
        }
        if (clean.contains("cookie") || clean.contains("cake") || clean.contains("pumpkinpie")) {
            return 50.0D;
        }
        if (clean.contains("beef") || clean.contains("porkchop") || clean.contains("chicken")
                || clean.contains("fish") || clean.contains("salmon") || clean.contains("mutton")
                || clean.contains("rabbit")) {
            boolean cooked = clean.contains("cooked") || clean.contains("roasted")
                    || clean.contains("grilled") || clean.contains("baked");
            return cooked ? 80.0D : 40.0D;
        }
        if (clean.contains("apple") || clean.contains("carrot") || clean.contains("potato")) {
            return 40.0D;
        }
        if (clean.contains("flesh") || clean.contains("spidereye")) {
            return 5.0D;
        }
        return 30.0D;
    }

    // ==================================================================
    // 布局
    // ==================================================================

    /** 大类的固定顺序：武器 → 方块 → 食物 → 工具 → 其它。值越小越靠前。 */
    public static int categoryRank(int kind) {
        switch (kind) {
            case KIND_WEAPON:
                return 0;
            case KIND_BLOCK:
                return 1;
            case KIND_FOOD:
                return 2;
            case KIND_TOOL:
                return 3;
            case KIND_ARMOR:
                return 9; // 盔甲只穿在身上或留在背包，不进快捷栏
            default:
                return 9; // 其它：不进快捷栏
        }
    }

    /** 该类是否允许放进快捷栏。 */
    public static boolean hotbarWorthy(int kind) {
        return categoryRank(kind) < 9;
    }
}
