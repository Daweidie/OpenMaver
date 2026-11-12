package net.minecraft.entity;

import net.minecraft.util.AxisAlignedBB;

public class Entity {
    public double posX;
    public double posY;
    public double posZ;
    public float height;
    public float rotationYaw;
    public float rotationPitch;
    public boolean isEntityAlive() { return true; }
    public double getDistanceSqToEntity(Entity entity) { return 0.0D; }
    public AxisAlignedBB getEntityBoundingBox() { return null; }
}
