package net.minecraft.inventory;

import net.minecraft.entity.player.InventoryPlayer;

/**
 * 测试替身：与 1.8.9 的 {@code ContainerCreative} 结构一致 —— 45 个页签槽位
 * （{@code inventory} 是 {@code InventoryCreative}，不是玩家背包）+ 36 个背包槽位。
 *
 * <p>旧版"槽位 > 36 且有箱子侧"的兜底会把这种东西判成箱子，于是按 E 打开物品栏
 * 时会被静默开箱逻辑接管；这个替身就是用来钉住这个回归的。
 */
public class ContainerCreative extends Container {
    public final InventoryPlayer playerInventory;
    public final InventoryCreative tabInventory = new InventoryCreative();

    public ContainerCreative(InventoryPlayer playerInventory) {
        this.playerInventory = playerInventory;
        for (int i = 0; i < 45; i++) {
            this.inventorySlots.add(new Slot(tabInventory, i, i));
        }
        for (int i = 0; i < 36; i++) {
            this.inventorySlots.add(new Slot(playerInventory, i, 45 + i));
        }
    }
}
