package net.minecraft.item;

/** Test-only block placement predicate, including isolated-loader instances. */
public class ItemBlock {
    public boolean placeable = true;
    public boolean canPlaceBlockOnSide(Object world, Object pos, Object face, Object player, Object stack) {
        return placeable;
    }
}
