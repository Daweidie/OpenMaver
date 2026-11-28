import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 验证物品适配、背包界面限制与配置持久化，不需要启动游戏。 */
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

        testOnlyInventoryOpen();
        testOnlyInventoryOpenPersistence();

        System.out.println("inventory-feature-test: " + passed + " passed");
    }

    private static void testOnlyInventoryOpen() throws Exception {
        check("仅打开背包整理默认关闭", !InventoryManagerFeature.isOnlyInventoryOpen());
        module.player.InventoryManager inventoryManager = new module.player.InventoryManager();
        module.ModuleManager.register(inventoryManager);
        inventoryManager.setEnabled(true);
        setFeatureField("settingsCheckTicks", 0);
        TestMinecraft mc = new TestMinecraft();
        try {
            checkTick(mc, null, 1, "默认关闭界面仍会整理");
            InventoryManagerFeature.setOnlyInventoryOpen(true);
            checkTick(mc, null, 0, "开启限制后关闭背包不点击");
            checkTick(mc, new GuiInventory(), 1, "开启背包后正常整理");
            checkTick(mc, null, 0, "关闭背包立即暂停");
            checkTick(mc, new GuiInventory(), 1, "再次打开背包恢复整理");
            checkTick(mc, new net.minecraft.client.gui.GuiScreen(), 0, "其它界面不整理");
            mc.thePlayer.openContainer = new Object();
            checkTick(mc, new GuiInventory(), 0, "其它容器不整理");
            mc.thePlayer.openContainer = mc.thePlayer.inventoryContainer;
            InventoryManagerFeature.setOnlyInventoryOpen(false);
            checkTick(mc, null, 1, "关闭限制恢复后台整理");
            checkTick(mc, new net.minecraft.client.gui.GuiScreen(), 0, "关闭限制仍跳过其它界面");
        } finally {
            inventoryManager.setEnabled(false);
            InventoryManagerFeature.onTickStart(mc);
            InventoryManagerFeature.setOnlyInventoryOpen(false);
        }
    }

    private static void checkTick(TestMinecraft mc, net.minecraft.client.gui.GuiScreen screen,
            int expectedClicks, String message) throws Exception {
        mc.currentScreen = screen;
        setFeatureField("nextActionNanos", 0L);
        int before = mc.playerController.clicks;
        InventoryManagerFeature.onTickStart(mc);
        check(message, mc.playerController.clicks - before == expectedClicks);
    }

    private static void testOnlyInventoryOpenPersistence() throws Exception {
        Path settings = Files.createTempFile("openmaver-inventory-", ".properties");
        String previous = System.getProperty("openmaver.inventory");
        System.setProperty("openmaver.inventory", settings.toString());
        try {
            Files.write(settings, "inventory.autoSort=true\n".getBytes(StandardCharsets.UTF_8));
            InventoryManagerFeature.setOnlyInventoryOpen(true);
            reloadSettings();
            check("旧配置缺少选项时保持默认行为", !InventoryManagerFeature.isOnlyInventoryOpen());
            for (boolean enabled : new boolean[] {true, false}) {
                InventoryManagerFeature.setOnlyInventoryOpen(enabled);
                Method save = InventoryManagerFeature.class.getDeclaredMethod("saveSettings");
                save.setAccessible(true);
                save.invoke(null);
                check("配置保存 " + enabled, new String(Files.readAllBytes(settings), StandardCharsets.UTF_8)
                        .contains("inventory.onlyInventoryOpen=" + enabled + "\n"));
                InventoryManagerFeature.setOnlyInventoryOpen(!enabled);
                reloadSettings();
                check("配置恢复 " + enabled, InventoryManagerFeature.isOnlyInventoryOpen() == enabled);
            }
        } finally {
            if (previous == null) System.clearProperty("openmaver.inventory");
            else System.setProperty("openmaver.inventory", previous);
            Files.deleteIfExists(settings);
        }
    }

    private static void reloadSettings() throws Exception {
        setFeatureField("settingsDirty", false);
        setFeatureField("settingsModified", Long.MIN_VALUE);
        setFeatureField("settingsCheckTicks", 20);
        Method refresh = InventoryManagerFeature.class.getDeclaredMethod("refreshSettings");
        refresh.setAccessible(true);
        refresh.invoke(null);
    }

    private static void setFeatureField(String name, Object value) throws Exception {
        Field field = InventoryManagerFeature.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    public static final class TestMinecraft extends net.minecraft.client.Minecraft {
        public final Object theWorld = new Object();
        public final TestPlayer thePlayer = new TestPlayer();
        public final TestController playerController = new TestController();
    }

    public static final class TestPlayer {
        public final Object inventoryContainer = new Object();
        public Object openContainer = inventoryContainer;
        public final TestInventory inventory = new TestInventory();
    }

    public static final class TestInventory {
        public final Object[] mainInventory = new Object[36];
        public final Object[] armorInventory = new Object[4];
        public int currentItem;
        TestInventory() { mainInventory[9] = new McpStack("item.swordIron", null, false); }
        public Object getItemStack() { return null; }
    }

    public static final class TestController {
        int clicks;
        public void windowClick(int window, int slot, int button, int mode, Object player) { clicks++; }
    }

    public static final class GuiInventory extends net.minecraft.client.gui.GuiScreen { }

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
