package net.minecraft.entity.player;

import net.minecraft.item.ItemStack;

public class InventoryPlayer {
    public final ItemStack[] mainInventory = new ItemStack[36];
    public ItemStack itemStack;

    public ItemStack getItemStack() {
        return itemStack;
    }

    public ItemStack getStackInSlot(int index) {
        return index >= 0 && index < mainInventory.length ? mainInventory[index] : null;
    }

    public int getSizeInventory() {
        return mainInventory.length;
    }

    public void setItemStack(ItemStack stack) {
        this.itemStack = stack;
    }
}
