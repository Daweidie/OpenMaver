package net.minecraft.inventory;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.tileentity.TileEntityChest;

/** 模拟 1.8.9 ContainerChest：前 27 格箱子，后 36 格玩家背包。 */
public class ContainerChest extends Container {
    public final TileEntityChest chest;
    public final InventoryPlayer playerInventory;

    public ContainerChest(InventoryPlayer playerInventory, TileEntityChest chest) {
        this.playerInventory = playerInventory;
        this.chest = chest;
        for (int i = 0; i < 27; i++) {
            this.inventorySlots.add(new Slot(chest, i, i));
        }
        for (int i = 0; i < 36; i++) {
            this.inventorySlots.add(new Slot(playerInventory, i, 27 + i));
        }
    }
}
