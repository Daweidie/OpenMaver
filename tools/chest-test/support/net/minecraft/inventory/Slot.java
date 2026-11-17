package net.minecraft.inventory;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntityChest;

/** 测试替身：inventory 字段 + getStack 是 ChestFeature 反射读取的全部内容。 */
public class Slot {
    public Object inventory;
    public int slotNumber;
    private final int index;

    public Slot(Object inventory, int index, int slotNumber) {
        this.inventory = inventory;
        this.index = index;
        this.slotNumber = slotNumber;
    }

    public ItemStack getStack() {
        if (inventory instanceof TileEntityChest) {
            return ((TileEntityChest) inventory).getStackInSlot(index);
        }
        if (inventory instanceof net.minecraft.entity.player.InventoryPlayer) {
            return ((net.minecraft.entity.player.InventoryPlayer) inventory).getStackInSlot(index);
        }
        return null;
    }

    public void putStack(ItemStack stack) {
        if (inventory instanceof TileEntityChest) {
            ((TileEntityChest) inventory).slots[index] = stack;
        } else if (inventory instanceof net.minecraft.entity.player.InventoryPlayer) {
            ((net.minecraft.entity.player.InventoryPlayer) inventory).mainInventory[index] = stack;
        }
    }
}
