package net.minecraft.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.BlockPos;

public class World {
    public final List<Object> loadedTileEntityList = new ArrayList<Object>();
    private final Map<String, Object> tiles = new HashMap<String, Object>();

    public void add(TileEntityChest chest) {
        loadedTileEntityList.add(chest);
        BlockPos pos = chest.getPos();
        tiles.put(key(pos), chest);
    }

    public Object getTileEntity(BlockPos pos) {
        return tiles.get(key(pos));
    }

    private static String key(BlockPos pos) {
        return pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }
}
