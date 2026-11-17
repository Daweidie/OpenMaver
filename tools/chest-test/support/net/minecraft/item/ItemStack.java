package net.minecraft.item;

/** 测试替身：只保留 ChestFeature 反射读取的成员。 */
public class ItemStack {
    public String unlocalizedName;
    public int stackSize;
    public int itemDamage;
    public int maxStackSize = 64;

    public ItemStack(String unlocalizedName, int stackSize) {
        this.unlocalizedName = unlocalizedName;
        this.stackSize = stackSize;
    }

    public String getUnlocalizedName() {
        return unlocalizedName;
    }

    public String getDisplayName() {
        return unlocalizedName;
    }

    public int getItemDamage() {
        return itemDamage;
    }

    public int getMaxStackSize() {
        return maxStackSize;
    }
}
