package net.minecraft.entity;

import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;

public class EntityLivingBase extends Entity {
    public float getEyeHeight() { return 0.0F; }
    public float getHealth() { return 20.0F; }
    public void setHealth(float health) { }
    public float getMaxHealth() { return 20.0F; }
    public int getTotalArmorValue() { return 0; }
    public ItemStack getHeldItem() { return null; }
    public boolean canEntityBeSeen(Entity entity) { return true; }
    public boolean isPotionActive(int potionId) { return false; }
    public void addPotionEffect(PotionEffect effect) { }
    public void removePotionEffect(int potionId) { }
}
