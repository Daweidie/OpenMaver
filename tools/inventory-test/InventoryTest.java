import java.util.ArrayList;
import java.util.List;

/**
 * 背包整理（Inventory Manager）的 headless 测试。
 *
 * <p>只编译 {@code mc/InventoryRules.java} + {@code mc/InventoryPlanner.java}
 * 两个纯逻辑类，不需要 MC、不需要 Forge：用一个「假容器」按原版
 * {@code windowClick} 的语义执行规划器给出的点击（mode 0 拿起 / 放下 / 交换、
 * mode 2 数字键、mode 4 丢弃），跑到规划器说「已经整理好」为止，
 * 再断言快捷栏布局、盔甲替换、多余装备丢弃、以及最终的稳定性。
 */
public final class InventoryTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        layoutPutsWeaponBlockFoodToolInOrder();
        armorUpgradeKeepsTheBetterPiece();
        protectionEnchantBeatsPlainDurability();
        wrongItemInArmorSlotIsReplaced();
        extrasAreDroppedRespectingKeepCount();
        blockDropUsesWholeStackClick();
        armorAndBlocksUseCompactDefaults();
        dropToolsKeepsOnePerFamily();
        tidyInventoryIsLeftAlone();
        fullInventoryDoesNotLoop();
        hotbarOrderIsDeterministic();
        lobbyItemsStayInTheirOriginalSlots();
        protectedItemsAreExcludedFromCleanup();
        protectedArmorStaysEquipped();
        reservedHotbarSlotsAreSkipped();
        fullyReservedHotbarIsLeftAlone();
        randomInventoriesConverge();
        randomInventoriesWithProtectedItemsConverge();
        System.out.println();
        System.out.println("inventory-test: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================================================================
    // 用例
    // ==================================================================

    private static void layoutPutsWeaponBlockFoodToolInOrder() {
        Fake inv = new Fake();
        inv.put(20, item("item.swordDiamond", 0, 1));
        inv.put(21, item("tile.cobblestone", 0, 64));
        inv.put(22, item("tile.cobblestone", 0, 32));
        inv.put(23, item("item.appleGold", 0, 8));
        inv.put(24, item("item.pickaxeDiamond", 0, 1));
        inv.put(25, item("item.shovelIron", 0, 1));
        inv.put(26, item("item.enderPearl", 0, 5)); // 杂物：不进快捷栏
        inv.put(38, item("item.enderPearl", 0, 2)); // 已经占着快捷栏第 3 格的杂物

        run(inv);

        assertEquals("快捷栏 1 = 武器", "sworddiamond#0", inv.hotbarKey(0));
        assertEquals("快捷栏 2 = 方块", "cobblestone#0", inv.hotbarKey(1));
        assertEquals("快捷栏 3 = 食物", "applegold#0", inv.hotbarKey(2));
        assertEquals("快捷栏 4 = 工具（镐优先）", "pickaxediamond#0", inv.hotbarKey(3));
        assertEquals("快捷栏 5 = 第二摞方块", "cobblestone#0", inv.hotbarKey(4));
        assertEquals("快捷栏 6 = 第二把工具", "shoveliron#0", inv.hotbarKey(5));
        assertEquals("快捷栏 7 空出来", null, inv.hotbarKey(6));
        assertEquals("两摞杂物合并成一摞、没被丢掉", 1, countName(inv, "enderpearl"));
        assertEquals("两摞方块都留着", 2, countKey(inv, "cobblestone#0"));
        assertTrue("盔甲槽没被动", inv.slot(5) == null && inv.slot(8) == null);
        assertTrue("光标空", inv.cursor == null);
    }

    private static void armorUpgradeKeepsTheBetterPiece() {
        Fake inv = new Fake();
        inv.put(5, item("item.helmetIron", 0, 1));        // 身上：铁头盔
        inv.put(6, item("item.chestplateIron", 0, 1));    // 身上：铁胸甲
        inv.put(7, item("item.leggingsIron", 0, 1));      // 身上：铁护腿
        inv.put(20, item("item.helmetDiamond", 0, 1));    // 背包：更好的钻石头盔
        inv.put(21, item("item.chestplateCloth", 0, 1));  // 背包：更差的皮革胸甲
        inv.put(22, item("item.bootsDiamond", 0, 1));     // 背包：钻石靴子（原本空着）

        run(inv);

        assertEquals("头盔换成钻石", "helmetdiamond", inv.nameAt(5));
        assertEquals("胸甲没有被换差", "chestplateiron", inv.nameAt(6));
        assertEquals("护腿保持铁", "leggingsiron", inv.nameAt(7));
        assertEquals("靴子穿上钻石", "bootsdiamond", inv.nameAt(8));
        assertEquals("多余旧铁头盔被丢弃", 0, countName(inv, "helmetiron"));
        assertEquals("多余皮革胸甲被丢弃", 0, countName(inv, "chestplatecloth"));
        assertTrue("光标空", inv.cursor == null);
    }

    private static void protectionEnchantBeatsPlainDurability() {
        Fake inv = new Fake();
        // 身上：用过 20 点耐久的皮革头盔（无附魔）；背包：满耐久 + 保护 IV
        inv.put(5, item("item.helmetLeather", 20, 1, 0));
        inv.put(20, item("item.helmetLeather", 0, 1, 4));

        run(inv);

        assertEquals("换上附魔那件（damage 0）", "helmetleather#0", inv.key(5));
        assertEquals("默认不保留多余备用盔甲", 1, countName(inv, "helmetleather"));
    }

    private static void wrongItemInArmorSlotIsReplaced() {
        Fake inv = new Fake();
        inv.put(5, item("item.swordIron", 0, 1));      // 头盔槽里塞了把剑（异常数据）
        inv.put(20, item("item.helmetDiamond", 0, 1)); // 背包里的正常头盔

        run(inv);

        assertEquals("异常的头盔槽被正常头盔换掉", "helmetdiamond", inv.nameAt(5));
        assertEquals("被挤出来的剑回到背包", 1, countName(inv, "swordiron"));
        assertEquals("剑进了快捷栏 1", "swordiron#0", inv.hotbarKey(0));
    }

    private static void extrasAreDroppedRespectingKeepCount() {
        Fake inv = new Fake();
        inv.put(20, item("item.swordStone", 0, 1));
        inv.put(21, item("item.swordDiamond", 0, 1));
        inv.put(22, item("item.swordIron", 0, 1));

        run(inv);
        assertEquals("KEEP WEAPONS = 1：只留一把剑", 1, swordCount(inv));
        assertEquals("留下的是最好的钻石剑", "sworddiamond#0", inv.hotbarKey(0));

        Fake two = new Fake();
        two.put(20, item("item.swordStone", 0, 1));
        two.put(21, item("item.swordDiamond", 0, 1));
        two.put(22, item("item.swordIron", 0, 1));
        InventoryPlanner.State state = two.state();
        state.keepWeapons = 2;
        run(two, state);
        assertEquals("KEEP WEAPONS = 2：留两把", 2, swordCount(two));
        assertEquals("石头剑被丢掉", 0, countName(two, "swordstone"));
        assertEquals("钻石剑进快捷栏 1", "sworddiamond#0", two.hotbarKey(0));

        Fake held = new Fake();
        held.put(36, item("item.swordStone", 0, 1));
        held.put(20, item("item.swordDiamond", 0, 1));
        held.put(21, item("item.swordIron", 0, 1));
        InventoryPlanner.State heldState = held.state();
        heldState.sortHotbar = false;
        heldState.selectedHotbarIndex = 0;
        run(held, heldState);
        assertEquals("当前手持的石剑不会被多余武器清理丢掉", 1, countName(held, "swordstone"));
        assertEquals("手持武器受保护后仍清理其它多余剑", 2, swordCount(held));
    }

    private static void blockDropUsesWholeStackClick() {
        Fake inv = new Fake();
        inv.put(20, item("tile.cobblestone", 0, 64));
        inv.put(21, item("tile.dirt", 0, 32));
        inv.put(22, item("tile.sand", 0, 16));
        InventoryPlanner.State state = inv.state();
        state.sortHotbar = false;
        InventoryPlanner.Action action = InventoryPlanner.next(state);
        assertTrue("方块清理使用整组丢弃按钮", action != null && action.type == InventoryPlanner.DROP
                && action.clicks()[0][1] == 1 && action.clicks()[0][2] == 4);
        String droppedName = action == null ? null : inv.nameAt(action.slotA);
        run(inv, state);
        assertEquals("整组丢弃后仍保留两组方块", 2, countKind(inv, InventoryRules.KIND_BLOCK));
        assertEquals("整组丢弃不会留下被选中堆叠的零散数量", 0, countName(inv, droppedName));
    }

    private static void armorAndBlocksUseCompactDefaults() {
        Fake inv = new Fake();
        inv.put(5, item("item.helmetIron", 0, 1));
        inv.put(20, item("item.helmetLeather", 0, 1));
        inv.put(21, item("item.helmetDiamond", 0, 1));
        inv.put(22, item("tile.cobblestone", 0, 64));
        inv.put(23, item("tile.dirt", 0, 64));
        inv.put(24, item("tile.sand", 0, 64));

        run(inv);

        assertEquals("默认只保留身上的头盔，备用盔甲丢弃", 1, countArmor(inv, InventoryRules.ARMOR_HELMET));
        assertEquals("方块总共只保留两组", 2, countKind(inv, InventoryRules.KIND_BLOCK));
        assertEquals("评分相同时按名称丢弃 cobblestone", 0, countName(inv, "cobblestone"));
    }

    private static void dropToolsKeepsOnePerFamily() {
        Fake inv = new Fake();
        inv.put(20, item("item.pickaxeIron", 0, 1));
        inv.put(21, item("item.pickaxeDiamond", 0, 1));
        inv.put(22, item("item.axeStone", 0, 1));
        inv.put(23, item("item.axeIron", 0, 1));

        InventoryPlanner.State state = inv.state();
        state.dropTools = true;
        run(inv, state);

        assertEquals("每种工具家族留一把（镐）", 1, countName(inv, "pickaxe"));
        assertEquals("石头斧子被丢掉", 0, countName(inv, "axestone"));
        assertEquals("只留下铁斧", 1, countName(inv, "axeiron"));
        assertEquals("留的是最好的镐", "pickaxediamond", inv.nameOfKey("pickaxe"));
        assertEquals("留的是铁斧", "axeiron", inv.nameOfKey("axe"));
    }

    private static void tidyInventoryIsLeftAlone() {
        Fake inv = new Fake();
        inv.put(36, item("item.swordIron", 0, 1));      // 1 武器
        inv.put(37, item("tile.cobblestone", 0, 32));   // 2 方块
        inv.put(38, item("item.appleGold", 0, 4));      // 3 食物
        inv.put(39, item("item.pickaxeIron", 0, 1));    // 4 工具
        inv.put(40, item("tile.dirt", 0, 12));          // 5 备用方块
        inv.put(41, item("item.apple", 0, 3));          // 6 备用食物
        inv.put(10, item("item.enderPearl", 0, 7));     // 背包：杂物
        inv.put(11, item("item.bucket", 0, 1));

        InventoryPlanner.Action action = InventoryPlanner.next(inv.state());
        assertTrue("已整理好的背包不再动：" + (action == null ? "null" : action.describe()),
                action == null);
    }

    private static void fullInventoryDoesNotLoop() {
        Fake inv = new Fake();
        for (int slot = InventoryPlanner.MAIN_FIRST; slot <= InventoryPlanner.HOTBAR_LAST; slot++) {
            inv.put(slot, item("tile.dirt", 0, 16));
        }
        inv.put(20, item("item.swordDiamond", 0, 1));
        inv.put(21, item("item.appleGold", 0, 3));

        run(inv);

        assertEquals("满背包也能把武器换进快捷栏 1", "sworddiamond#0", inv.hotbarKey(0));
        assertEquals("满背包也能把食物换进快捷栏 3", "applegold#0", inv.hotbarKey(2));
        assertTrue("光标空", inv.cursor == null);
    }

    private static void hotbarOrderIsDeterministic() {
        List<InventoryPlanner.Item> pool = new ArrayList<InventoryPlanner.Item>();
        pool.add(item("tile.dirt", 0, 64));         // 0
        pool.add(item("item.apple", 0, 5));         // 1
        pool.add(item("item.swordIron", 0, 1));     // 2
        pool.add(item("item.pickaxeIron", 0, 1));   // 3
        pool.add(item("tile.stone", 0, 64));        // 4
        List<Integer> order = InventoryPlanner.hotbarOrder(pool);
        assertEquals("第 1 格是武器", 2, order.get(0).intValue());
        assertEquals("第 2 格是方块（数量多的 dirt 优先）", 0, order.get(1).intValue());
        assertEquals("第 3 格是食物", 1, order.get(2).intValue());
        assertEquals("第 4 格是工具", 3, order.get(3).intValue());
        assertEquals("第 5 格是剩下的方块", 4, order.get(4).intValue());
        assertEquals("一共 5 项", 5, order.size());

        // 没有食物时第 3 格留空，工具仍然固定在第 4 格。
        List<InventoryPlanner.Item> noFood = new ArrayList<InventoryPlanner.Item>();
        noFood.add(item("item.swordIron", 0, 1));
        noFood.add(item("tile.dirt", 0, 64));
        noFood.add(item("item.pickaxeIron", 0, 1));
        List<Integer> fixed = InventoryPlanner.hotbarOrder(noFood);
        assertEquals("武器在第 1 格", 0, fixed.get(0).intValue());
        assertEquals("方块在第 2 格", 1, fixed.get(1).intValue());
        assertEquals("没有食物：第 3 格占位 -1", -1, fixed.get(2).intValue());
        assertEquals("工具仍固定在第 4 格", 2, fixed.get(3).intValue());
    }

    private static void randomInventoriesConverge() {
        String[] names = {
                "item.swordDiamond", "item.swordIron", "item.swordStone", "item.swordWood",
                "tile.cobblestone", "tile.dirt", "tile.stone", "tile.planks",
                "item.appleGold", "item.apple", "item.bread", "item.beefCooked",
                "item.pickaxeDiamond", "item.pickaxeIron", "item.axeIron", "item.shovelStone",
                "item.helmetDiamond", "item.helmetIron", "item.chestplateDiamond",
                "item.leggingsIron", "item.bootsDiamond", "item.bootsCloth",
                "item.enderPearl", "item.bucket", "item.torch"
        };
        java.util.Random random = new java.util.Random(20261121L);
        for (int round = 0; round < 300; round++) {
            Fake inv = new Fake();
            int items = 4 + random.nextInt(24);
            for (int i = 0; i < items; i++) {
                int slot;
                int dice = random.nextInt(5);
                if (dice == 0) {
                    slot = InventoryPlanner.HOTBAR_FIRST + random.nextInt(9);
                } else if (dice == 1) {
                    slot = InventoryPlanner.ARMOR_FIRST + random.nextInt(4);
                } else {
                    slot = InventoryPlanner.MAIN_FIRST + random.nextInt(27);
                }
                String name = names[random.nextInt(names.length)];
                boolean single = name.contains("sword") || name.contains("pickaxe")
                        || name.contains("axe") || name.contains("shovel")
                        || name.contains("helmet") || name.contains("chestplate")
                        || name.contains("leggings") || name.contains("boots");
                int count = single ? 1 : 1 + random.nextInt(32);
                inv.put(slot, item(name, 0, count, random.nextInt(5)));
            }
            InventoryPlanner.State state = inv.state();
            run(inv, state, 400);
            assertTrue("第 " + round + " 组收敛", InventoryPlanner.isSettled(inv.state()));
            assertTrue("第 " + round + " 组光标空", inv.cursor == null);
            assertTrue("第 " + round + " 组没有漏合并", !hasMergeable(inv.state()));
            assertTrue("第 " + round + " 组快捷栏布局：" + inv.hotbarKey(0) + " / "
                            + inv.hotbarKey(1) + " / " + inv.hotbarKey(2) + " / " + inv.hotbarKey(3),
                    hotbarLayoutOk(inv));
        }
    }

    private static void lobbyItemsStayInTheirOriginalSlots() {
        Fake inv = new Fake();
        String[] names = {"item.skull.player", "tile.chest", "tile.chestTrap", "tile.enderChest",
                "item.compass", "item.clock", "item.netherStar", "item.paper", "item.writtenBook"};
        for (int i = 0; i < names.length; i++) {
            inv.put(36 + i, item(names[i], 0, 1));
        }
        inv.put(20, item("tile.chest", 0, 1)); // 与快捷栏同类，也不能合并。
        inv.put(21, item("item.skull.player", 0, 1));
        assertEquals("大厅纯功能物品背包不产生任何点击", 0, run(inv));
        for (int i = 0; i < names.length; i++) {
            assertEquals("大厅物品保留原位 " + names[i], InventoryRules.clean(names[i]), inv.nameAt(36 + i));
        }
    }

    private static void protectedItemsAreExcludedFromCleanup() {
        Fake inv = new Fake();
        inv.put(20, item("tile.chest", 0, 1));
        inv.put(21, item("tile.chest", 0, 1));
        inv.put(22, item("tile.dirt", 0, 64));
        inv.put(23, item("tile.stone", 0, 64));
        inv.put(24, protectedItem("item.swordWood", 1));
        inv.put(25, item("item.swordDiamond", 0, 1));
        inv.put(26, protectedItem("item.pickaxeWood", 1));
        inv.put(27, item("item.pickaxeDiamond", 0, 1));
        inv.put(28, protectedItem("item.helmetCloth", 1));
        inv.put(29, item("item.helmetDiamond", 0, 1));
        inv.put(30, protectedItem("item.apple", 2));
        inv.put(31, protectedItem("item.apple", 3));
        inv.put(32, item("item.apple", 0, 4));
        inv.put(33, item("item.apple", 0, 5));
        InventoryPlanner.State state = inv.state();
        state.dropTools = true;
        run(inv, state);
        assertEquals("箱子不占用建材保留数量", 2, countName(inv, "chest"));
        assertEquals("两组普通建材都保留", 2, countName(inv, "dirt") + countName(inv, "stone"));
        assertEquals("自定义武器不被丢弃或移位", "swordwood", inv.nameAt(24));
        assertEquals("自定义工具不被丢弃或移位", "pickaxewood", inv.nameAt(26));
        assertEquals("自定义盔甲不被穿上或丢弃", "helmetcloth", inv.nameAt(28));
        assertEquals("自定义食物不与其它堆叠合并", 2, inv.slot(30).count);
        assertEquals("第二摞自定义食物保留数量", 3, inv.slot(31).count);
        assertEquals("普通食物仍正常合并", 3, countName(inv, "apple"));
        assertEquals("普通武器仍正常整理", "sworddiamond", inv.nameAt(36));
        assertEquals("普通盔甲仍正常穿上", "helmetdiamond", inv.nameAt(5));
    }

    private static void protectedArmorStaysEquipped() {
        Fake inv = new Fake();
        inv.put(5, item("item.skull.player", 0, 1));
        inv.put(6, protectedItem("item.chestplateCloth", 1));
        inv.put(20, item("item.helmetDiamond", 0, 1));
        inv.put(21, item("item.chestplateDiamond", 0, 1));
        InventoryPlanner.State state = inv.state();
        state.dropExtras = false;
        run(inv, state);
        assertEquals("戴着的头颅不被普通头盔替换", "skull.player", inv.nameAt(5));
        assertEquals("穿着的自定义盔甲不被替换", "chestplatecloth", inv.nameAt(6));
    }

    private static void reservedHotbarSlotsAreSkipped() {
        Fake inv = new Fake();
        inv.put(36, item("item.compass", 0, 1));
        inv.put(40, item("tile.chest", 0, 1));
        inv.put(20, item("item.swordDiamond", 0, 1));
        inv.put(21, item("tile.dirt", 0, 64));
        inv.put(22, item("item.apple", 0, 3));
        inv.put(23, item("item.pickaxeIron", 0, 1));
        run(inv);
        assertEquals("第 1 格的指南针不被武器交换挤走", "compass", inv.nameAt(36));
        assertEquals("第 5 格的箱子不被补位物品挤走", "chest", inv.nameAt(40));
        assertEquals("武器使用未占用的补位格", "sworddiamond", inv.nameAt(41));
        assertEquals("方块仍使用固定第 2 格", "dirt", inv.nameAt(37));
        assertEquals("食物仍使用固定第 3 格", "apple", inv.nameAt(38));
        assertEquals("工具仍使用固定第 4 格", "pickaxeiron", inv.nameAt(39));
    }

    private static void fullyReservedHotbarIsLeftAlone() {
        Fake inv = new Fake();
        for (int i = 36; i <= 44; i++) {
            inv.put(i, item("tile.chest", 0, 1));
        }
        inv.put(20, item("item.swordDiamond", 0, 1));
        inv.put(21, item("tile.dirt", 0, 64));
        assertEquals("快捷栏全被功能物品占用时不尝试交换", 0, run(inv));
    }

    private static void randomInventoriesWithProtectedItemsConverge() {
        String[] names = {"item.swordIron", "tile.dirt", "item.apple", "item.pickaxeIron",
                "item.helmetDiamond", "item.enderPearl", "tile.chest", "item.skull.player"};
        java.util.Random random = new java.util.Random(20261124L);
        for (int round = 0; round < 200; round++) {
            Fake inv = new Fake();
            for (int slot = 5; slot <= 44; slot++) {
                if (random.nextInt(3) == 0) {
                    continue;
                }
                String name = names[random.nextInt(names.length)];
                inv.put(slot, random.nextBoolean() ? item(name, 0, 1) : protectedItem(name, 1));
            }
            InventoryPlanner.Item[] original = inv.slots.clone();
            run(inv);
            assertTrue("混合功能物品第 " + round + " 组收敛", InventoryPlanner.isSettled(inv.state()));
            boolean preserved = inv.cursor == null;
            for (int slot = 5; slot <= 44; slot++) {
                if (original[slot] != null && original[slot].protectedItem) {
                    preserved &= original[slot] == inv.slot(slot);
                }
            }
            assertTrue("混合功能物品第 " + round + " 组保留原槽和物品", preserved);
        }
    }

    private static InventoryPlanner.Item protectedItem(String raw, int count) {
        InventoryPlanner.Item item = item(raw, 0, count);
        return new InventoryPlanner.Item(item.key, item.name, item.kind, item.armorType, item.family,
                item.count, item.maxStack, item.score, item.fullBlock, true);
    }

    // ==================================================================
    // 假容器：按原版 windowClick 语义执行点击
    // ==================================================================

    static final class Fake {
        final InventoryPlanner.Item[] slots = new InventoryPlanner.Item[InventoryPlanner.SLOTS];
        InventoryPlanner.Item cursor;

        void put(int slot, InventoryPlanner.Item item) {
            slots[slot] = item;
        }

        InventoryPlanner.Item slot(int slot) {
            return slots[slot];
        }

        String key(int slot) {
            return slots[slot] == null ? null : slots[slot].key;
        }

        String nameAt(int slot) {
            return slots[slot] == null ? null : slots[slot].name;
        }

        String hotbarKey(int index) {
            InventoryPlanner.Item item = slots[InventoryPlanner.HOTBAR_FIRST + index];
            return item == null ? null : item.key;
        }

        String nameOfKey(String prefix) {
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != null && slots[i].name.startsWith(prefix)) {
                    return slots[i].name;
                }
            }
            return null;
        }

        InventoryPlanner.State state() {
            InventoryPlanner.State state = new InventoryPlanner.State();
            for (int i = 0; i < slots.length; i++) {
                state.slots[i] = slots[i];
            }
            return state;
        }

        /** 原版 {@code Container.slotClick} 的三条主干：mode 0 / 2 / 4。 */
        void click(int slot, int button, int mode) {
            if (slots[slot] != null && slots[slot].protectedItem) {
                throw new IllegalStateException("点击触及受保护物品: " + slot);
            }
            if (mode == 2) { // 数字键：与快捷栏第 button 格互换
                int hotbar = InventoryPlanner.HOTBAR_FIRST + button;
                if (slots[hotbar] != null && slots[hotbar].protectedItem) {
                    throw new IllegalStateException("数字键交换覆盖受保护物品: " + hotbar);
                }
                InventoryPlanner.Item tmp = slots[slot];
                slots[slot] = slots[hotbar];
                slots[hotbar] = tmp;
                return;
            }
            if (mode == 4) { // 丢弃
                InventoryPlanner.Item dropped = slots[slot];
                if (button == 1 || dropped.count <= 1) {
                    slots[slot] = null;
                } else {
                    slots[slot] = new InventoryPlanner.Item(dropped.key, dropped.name,
                            dropped.kind, dropped.armorType, dropped.family, dropped.count - 1,
                            dropped.maxStack, dropped.score, dropped.fullBlock,
                            dropped.protectedItem);
                }
                return;
            }
            if (mode != 0) {
                throw new IllegalStateException("unexpected mode " + mode);
            }
            InventoryPlanner.Item occupied = slots[slot];
            if (occupied == null) {
                if (cursor != null) {
                    slots[slot] = cursor;
                    cursor = null;
                }
                return;
            }
            if (cursor == null) {
                cursor = occupied;
                slots[slot] = null;
                return;
            }
            if (cursor.key.equals(occupied.key) && occupied.maxStack > 1) {
                int total = cursor.count + occupied.count;
                if (total <= occupied.maxStack) {
                    slots[slot] = occupied.withCount(total);
                    cursor = null;
                } else {
                    slots[slot] = occupied.withCount(occupied.maxStack);
                    cursor = cursor.withCount(total - occupied.maxStack);
                }
                return;
            }
            slots[slot] = cursor;
            cursor = occupied;
        }
    }

    // ==================================================================
    // 跑规划循环
    // ==================================================================

    private static int run(Fake inv) {
        return run(inv, inv.state(), 400);
    }

    private static int run(Fake inv, InventoryPlanner.State state) {
        return run(inv, state, 400);
    }

    private static int run(Fake inv, InventoryPlanner.State template, int limit) {
        int steps = 0;
        while (steps < limit) {
            InventoryPlanner.State state = inv.state();
            state.autoArmor = template.autoArmor;
            state.sortHotbar = template.sortHotbar;
            state.dropExtras = template.dropExtras;
            state.dropTools = template.dropTools;
            state.keepWeapons = template.keepWeapons;
            state.selectedHotbarIndex = template.selectedHotbarIndex;
            state.spareArmor = template.spareArmor;
            state.keepBlockStacks = template.keepBlockStacks;
            state.keepTools = template.keepTools;
            InventoryPlanner.Action action = InventoryPlanner.next(state);
            if (action == null) {
                return steps;
            }
            int[][] clicks = action.clicks();
            for (int i = 0; i < clicks.length; i++) {
                int slot = clicks[i][0];
                int button = clicks[i][1];
                int mode = clicks[i][2];
                if (slot < 0 || slot >= InventoryPlanner.SLOTS) {
                    throw new IllegalStateException("非法槽位 " + slot + "：" + action.describe());
                }
                if (button < 0 || button > 8) {
                    throw new IllegalStateException("非法按钮 " + button + "：" + action.describe());
                }
                if (mode != 0 && mode != 1 && mode != 2 && mode != 4) {
                    throw new IllegalStateException("非法模式 " + mode + "：" + action.describe());
                }
                inv.click(slot, button, mode);
            }
            steps++;
        }
        throw new IllegalStateException("400 步内没有收敛，下一步 = "
                + (InventoryPlanner.next(inv.state()) == null
                        ? "null" : InventoryPlanner.next(inv.state()).describe()));
    }

    // ==================================================================
    // 断言与工具
    // ==================================================================

    private static int countKey(Fake inv, String key) {
        int count = 0;
        for (int i = 0; i < inv.slots.length; i++) {
            if (inv.slots[i] != null && key.equals(inv.slots[i].key)) {
                count++;
            }
        }
        return count;
    }

    private static int countName(Fake inv, String needle) {
        int count = 0;
        for (int i = 0; i < inv.slots.length; i++) {
            if (inv.slots[i] != null && inv.slots[i].name.contains(needle)) {
                count++;
            }
        }
        return count;
    }

    private static int countArmor(Fake inv, int armorType) {
        int count = 0;
        for (int i = 0; i < inv.slots.length; i++) {
            if (inv.slots[i] != null && inv.slots[i].kind == InventoryRules.KIND_ARMOR
                    && inv.slots[i].armorType == armorType) count++;
        }
        return count;
    }

    private static int countKind(Fake inv, int kind) {
        int count = 0;
        for (int i = 0; i < inv.slots.length; i++) {
            if (inv.slots[i] != null && inv.slots[i].kind == kind) count++;
        }
        return count;
    }

    private static int swordCount(Fake inv) {
        int count = 0;
        for (int i = InventoryPlanner.MAIN_FIRST; i <= InventoryPlanner.HOTBAR_LAST; i++) {
            if (inv.slots[i] != null && inv.slots[i].name.contains("sword")) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasMergeable(InventoryPlanner.State state) {
        for (int a = InventoryPlanner.MAIN_FIRST; a <= InventoryPlanner.HOTBAR_LAST; a++) {
            InventoryPlanner.Item left = state.slots[a];
            if (left == null || left.maxStack <= 1) {
                continue;
            }
            for (int b = a + 1; b <= InventoryPlanner.HOTBAR_LAST; b++) {
                InventoryPlanner.Item right = state.slots[b];
                if (right == null || !left.key.equals(right.key)) {
                    continue;
                }
                if (left.count + right.count <= left.maxStack) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hotbarLayoutOk(Fake inv) {
        if (hasKind(inv, InventoryRules.KIND_WEAPON) && !"weapon".equals(kindOfSlot(inv, 0))) {
            return false;
        }
        if (hasKind(inv, InventoryRules.KIND_BLOCK) && !"block".equals(kindOfSlot(inv, 1))) {
            return false;
        }
        if (hasKind(inv, InventoryRules.KIND_FOOD) && !"food".equals(kindOfSlot(inv, 2))) {
            return false;
        }
        if (hasKind(inv, InventoryRules.KIND_TOOL) && !"tool".equals(kindOfSlot(inv, 3))) {
            return false;
        }
        return true;
    }

    private static String kindOfSlot(Fake inv, int index) {
        InventoryPlanner.Item item = inv.slots[InventoryPlanner.HOTBAR_FIRST + index];
        if (item == null) {
            return "empty";
        }
        switch (item.kind) {
            case InventoryRules.KIND_WEAPON:
                return "weapon";
            case InventoryRules.KIND_BLOCK:
                return "block";
            case InventoryRules.KIND_FOOD:
                return "food";
            case InventoryRules.KIND_TOOL:
                return "tool";
            default:
                return "other";
        }
    }

    private static boolean hasKind(Fake inv, int kind) {
        for (int i = InventoryPlanner.MAIN_FIRST; i <= InventoryPlanner.HOTBAR_LAST; i++) {
            if (inv.slots[i] != null && inv.slots[i].kind == kind) {
                return true;
            }
        }
        return false;
    }

    private static InventoryPlanner.Item item(String raw, int damage, int count) {
        return item(raw, damage, count, 0);
    }

    /** 造一个格子数据：名称交给 InventoryRules 分类，protLevel 模拟保护附魔等级。 */
    private static InventoryPlanner.Item item(String raw, int damage, int count, int protLevel) {
        String name = InventoryRules.clean(raw);
        boolean block = InventoryRules.isTileName(raw);
        int armorType = InventoryRules.armorTypeOf(name);
        int kind = InventoryRules.kindOf(name, block);
        int family = InventoryRules.FAMILY_NONE;
        if (kind == InventoryRules.KIND_WEAPON) {
            family = InventoryRules.weaponFamilyOf(name);
        } else if (kind == InventoryRules.KIND_TOOL) {
            family = InventoryRules.toolFamilyOf(name);
        }
        boolean stackable = kind == InventoryRules.KIND_BLOCK || kind == InventoryRules.KIND_FOOD
                || kind == InventoryRules.KIND_OTHER;
        int maxStack = stackable ? 64 : 1;
        // 模拟耐久：damage 是已损耗值，最大耐久按 250 算。
        double durability = Math.max(0.0D, Math.min(1.0D, (250 - damage) / 250.0D));
        double score = kind == InventoryRules.KIND_FOOD
                ? InventoryRules.foodScore(name, damage)
                : InventoryRules.scoreOf(name, kind, armorType, family, count, true,
                        protLevel, 0, 0, 0, durability);
        return new InventoryPlanner.Item(name + "#" + damage, name, kind, armorType, family,
                count, maxStack, score, true);
    }

    private static void assertEquals(String what, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            ok(what);
        } else {
            fail(what + " — 期望 <" + expected + "> 实际 <" + actual + ">");
        }
    }

    private static void assertTrue(String what, boolean value) {
        if (value) {
            ok(what);
        } else {
            fail(what);
        }
    }

    private static void ok(String what) {
        passed++;
        System.out.println("  ok   " + what);
    }

    private static void fail(String what) {
        failed++;
        System.out.println("  FAIL " + what);
    }
}
