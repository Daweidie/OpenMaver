package net.minecraft.client.multiplayer;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;

/** Minimal 1.8.9 stub for the client world's entity-ID bookkeeping. */
public class WorldClient extends World {
    public void addEntityToWorld(int entityID, Entity entityToSpawn) {
    }

    public Entity removeEntityFromWorld(int entityID) {
        return null;
    }
}
