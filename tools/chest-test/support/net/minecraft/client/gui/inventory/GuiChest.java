package net.minecraft.client.gui.inventory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Container;

/**
 * 测试替身：与 1.8.9 的 GuiChest 同样结构 —— 构造出容器，
 * {@code initGui()}（由 setWorldAndResolution 调）把容器挂到 player.openContainer。
 */
public class GuiChest extends GuiScreen {
    public Object lowerChestInventory;
    public Object inventorySlots;

    public GuiChest(Object chestInventory, Container container) {
        this.lowerChestInventory = chestInventory;
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
