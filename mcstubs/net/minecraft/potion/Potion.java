package net.minecraft.potion;

/** Compile-time potion registry and status-icon API. */
public class Potion {
    public static final Potion[] potionTypes = new Potion[32];
    public String getName() { return ""; }
    public int getLiquidColor() { return 0; }
    public boolean hasStatusIcon() { return false; }
    public int getStatusIconIndex() { return 0; }
}
