package net.minecraft.tileentity;

import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;

public class TileEntityChest {
    public final ItemStack[] slots = new ItemStack[27];
    private final BlockPos pos;

    public TileEntityChest(BlockPos pos) {
        this.pos = pos;
    }

    public String getName() {
        return "container.chest";
    }

    public BlockPos getPos() {
        return pos;
    }

    public int getSizeInventory() {
        return slots.length;
    }

    public ItemStack getStackInSlot(int index) {
        return index >= 0 && index < slots.length ? slots[index] : null;
    }
}
