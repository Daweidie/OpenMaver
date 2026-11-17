package net.minecraft.client.multiplayer;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** 测试替身：记录右键开箱与 windowClick，并模拟服务端 shift-click 的搬运结果。 */
public class PlayerControllerMP {

    /** 右键开箱的「服务端」回调（测试里装成：开容器 + 显示界面 + 写 windowId）。 */
    public interface RightClickHandler {
        void onRightClick(Object world, Object blockPos);
    }

    public static RightClickHandler rightClickHandler;

    public int openCalls;
    public int clicks;
    public final List<String> movedItems = new ArrayList<String>();
    /** 每次 windowClick 用的窗口号：用来钉住「点击必须发生在服务端那个窗口上」。 */
    public final List<Integer> clickWindowIds = new ArrayList<Integer>();
    /** 每次 windowClick 的发出时间（nanoTime）：验证节拍不是机械等距。 */
    public final List<Long> clickNanos = new ArrayList<Long>();

    public boolean onPlayerRightClick(Object player, Object world, Object held,
            Object blockPos, Object side, Object hitVec) {
        openCalls++;
        if (rightClickHandler != null) {
            rightClickHandler.onRightClick(world, blockPos);
        }
        return true;
    }

    public void windowClick(int windowId, int slotId, int button, int mode, Object player) {
        clicks++;
        clickWindowIds.add(Integer.valueOf(windowId));
        clickNanos.add(Long.valueOf(System.nanoTime()));
        EntityPlayer entity = (EntityPlayer) player;
        Container container = entity.openContainer;
        if (container == null || slotId < 0 || slotId >= container.inventorySlots.size()) {
            return;
        }
        Slot slot = container.inventorySlots.get(slotId);
        ItemStack stack = slot.getStack();
        if (stack == null) {
            return;
        }
        if (mode != 1) {
            return;
        }
        InventoryPlayer inventory = entity.inventory;
        for (int i = 0; i < inventory.mainInventory.length; i++) {
            if (inventory.mainInventory[i] == null) {
                inventory.mainInventory[i] = stack;
                slot.putStack(null);
                movedItems.add(stack.unlocalizedName);
                return;
            }
        }
    }
}
