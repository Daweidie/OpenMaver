package net.minecraft.inventory;

import java.util.ArrayList;
import java.util.List;

public class Container {
    public final List<Slot> inventorySlots = new ArrayList<Slot>();
    public int windowId;

    public int getNextTransactionID(Object playerInventory) {
        return 0;
    }
}
