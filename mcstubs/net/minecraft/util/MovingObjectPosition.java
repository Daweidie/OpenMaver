package net.minecraft.util;

import net.minecraft.entity.Entity;

/** Minimal 1.8.9 stub for the crosshair ray-trace result. */
public class MovingObjectPosition {
    public MovingObjectType typeOfHit;
    public Entity entityHit;

    public enum MovingObjectType {
        BLOCK,
        ENTITY,
        MISS
    }
}
