package net.minecraft.entity;

import net.minecraft.util.AxisAlignedBB;

public class Entity {
    public double posX;
    public double posY;
    public double posZ;
    public double motionX;
    public double motionY;
    public double motionZ;
    public float height;
    public float rotationYaw;
    public float rotationPitch;
    private int entityId;
    public boolean isEntityAlive() { return true; }
    public double getDistanceSqToEntity(Entity entity) { return 0.0D; }
    public double getDistanceToEntity(Entity entity) { return 0.0D; }
    public String getName() { return ""; }
    public AxisAlignedBB getEntityBoundingBox() { return null; }
    public int getEntityId() { return entityId; }
    public void setEntityId(int id) { this.entityId = id; }
    public void setLocationAndAngles(double x, double y, double z, float yaw, float pitch) {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.rotationYaw = yaw;
        this.rotationPitch = pitch;
    }
    public void setCustomNameTag(String name) { }
    public void setAlwaysRenderNameTag(boolean value) { }
}
