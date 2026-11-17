package net.minecraft.client.gui.inventory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerCreative;

/** 测试替身：创造模式的物品栏界面（按 E 打开的那个）。 */
public class GuiContainerCreative extends GuiScreen {
    public Object inventorySlots;
    public Object playerInventory;

    public GuiContainerCreative(ContainerCreative container) {
        this.inventorySlots = container;
        this.playerInventory = container.playerInventory;
    }

    @Override
    public void initGui() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.openContainer = (Container) inventorySlots;
        }
    }
}
