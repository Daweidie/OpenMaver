import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 验证真实 ItemStack 适配器的 MCP / SRG 元数据读取，不需要启动游戏。 */
public final class InventoryFeatureTest {
    private static int passed;
    private static Method itemOf;

    public static void main(String[] args) throws Exception {
        itemOf = InventoryManagerFeature.class.getDeclaredMethod("itemOf", Object.class);
        itemOf.setAccessible(true);

        check("普通武器仍可整理", !read(new McpStack("item.swordIron", null, false)).protectedItem);
        check("hasDisplayName 识别自定义物品",
                read(new McpStack("item.swordIron", null, true)).protectedItem);
        check("NBT 自定义名称也受保护", read(new McpStack("item.appleGold",
                tag("display", tag("Name", "游戏菜单")), false)).protectedItem);
        check("只有 Lore 的物品也受保护", read(new McpStack("tile.stone",
                tag("display", tag("Lore", Arrays.asList("右键打开菜单"))), false)).protectedItem);
        check("SkullOwner 元数据受保护", read(new McpStack("item.apple",
                tag("SkullOwner", tag("Name", "Player")), false)).protectedItem);
        check("普通附魔装备仍可整理", !read(new McpStack("item.swordIron",
                tag("ench", Arrays.asList(tag("id", Integer.valueOf(16)))), false)).protectedItem);
        check("普通染色盔甲仍可整理", !read(new McpStack("item.chestplateCloth",
                tag("display", tag("color", Integer.valueOf(12345))), false)).protectedItem);
        check("无名称的玩家头颅受保护", read(new McpStack("item.skull.player", null, false)).protectedItem);
        check("无名称的箱子受保护", read(new McpStack("tile.chest", null, false)).protectedItem);
        check("无法识别的非空物品保留原槽", read(new McpStack(null, null, false)).protectedItem);

        check("SRG hasDisplayName 兼容运行期映射", read(new SrgStack(null, true)).protectedItem);
        SrgTag lore = new SrgTag("display", new SrgTag("Lore", Arrays.asList("右键使用")));
        check("SRG Lore 读取兼容运行期映射", read(new SrgStack(lore, false)).protectedItem);
        check("SRG 普通物品仍可整理", !read(new SrgStack(null, false)).protectedItem);

        InventoryPlanner.Item first = read(new McpStack("item.potion", tag("effect", "speed"), false));
        InventoryPlanner.Item second = read(new McpStack("item.potion", tag("effect", "healing"), false));
        check("不同 NBT 的同种物品不会合并", !first.sameKind(second));
        InventoryPlanner.State state = new InventoryPlanner.State();
        state.set(20, first);
        state.set(21, second);
        state.sortHotbar = false;
        check("不同 NBT 不会产生无效合并动作", InventoryPlanner.next(state) == null);
        InventoryPlanner.Item identical = read(new McpStack("item.potion", tag("effect", "speed"), false));
        check("相同 NBT 的物品仍可合并", first.sameKind(identical));

        System.out.println("inventory-feature-test: " + passed + " passed");
    }

    private static InventoryPlanner.Item read(Object stack) throws Exception {
        return (InventoryPlanner.Item) itemOf.invoke(null, stack);
    }

    private static void check(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
        passed++;
        System.out.println("  ok   " + message);
    }

    private static McpTag tag(String key, Object value) {
        return new McpTag(key, value);
    }

    private static boolean typed(Object value, int type) {
        return type == 8 && value instanceof String
                || type == 9 && value instanceof java.util.List
                || type == 10 && (value instanceof McpTag || value instanceof SrgTag);
    }

    public static final class McpTag {
        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        McpTag(String key, Object value) { values.put(key, value); }
        public Object getCompoundTag(String key) { return values.get(key); }
        public boolean hasKey(String key, int type) { return typed(values.get(key), type); }
        @Override public String toString() { return values.toString(); }
    }

    public static final class SrgTag {
        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        SrgTag(String key, Object value) { values.put(key, value); }
        public Object func_74775_l(String key) { return values.get(key); }
        public boolean func_150297_b(String key, int type) { return typed(values.get(key), type); }
        @Override public String toString() { return values.toString(); }
    }

    public static final class McpStack {
        public int stackSize = 1;
        private final String name;
        private final Object nbt;
        private final boolean customName;

        McpStack(String name, Object nbt, boolean customName) {
            this.name = name;
            this.nbt = nbt;
            this.customName = customName;
        }

        public String getUnlocalizedName() { return name; }
        public Object getTagCompound() { return nbt; }
        public boolean hasDisplayName() { return customName; }
        public int getItemDamage() { return 0; }
        public int getMaxDamage() { return 0; }
        public int getMaxStackSize() { return 64; }
    }

    public static final class SrgStack {
        public int field_77994_a = 1;
        private final Object nbt;
        private final boolean customName;

        SrgStack(Object nbt, boolean customName) {
            this.nbt = nbt;
            this.customName = customName;
        }

        public String func_77977_a() { return "item.apple"; }
        public Object func_77978_p() { return nbt; }
        public boolean func_82837_s() { return customName; }
        public int func_77960_j() { return 0; }
        public int func_77958_k() { return 0; }
        public int func_77976_d() { return 64; }
    }
}
