package net.minecraft.client.gui.inventory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Container;

/**
 * 模拟「字段名对不上」的箱子界面：没有 lowerChestInventory 字段，
 * 只能靠类名 / GUI 容器槽位判断。用来确认兜底路径真的能搬。
 */
public class GuiChestCompat extends GuiScreen {
    public Object inventorySlots;

    public GuiChestCompat(Container container) {
        this.inventorySlots = container;
    }

    @Override
    public void initGui() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.openContainer = (Container) inventorySlots;
        }
    }
}
