package net.minecraft.entity.player;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.Container;
import net.minecraft.network.NetHandlerPlayClient;
import net.minecraft.network.play.client.C0DPacketCloseWindow;

/**
 * 测试替身：继承真实的 mcstubs 实体链（父类要能和 Entity 对上，
 * 否则 OpenMaverFeatures 的字节码校验会失败），加上箱子逻辑需要的字段。
 */
public class EntityPlayer extends EntityLivingBase {
    public final InventoryPlayer inventory = new InventoryPlayer();
    public Container openContainer;
    public Container inventoryContainer;
    public final NetHandlerPlayClient sendQueue = new NetHandlerPlayClient();

    public EntityPlayer() {
        inventoryContainer = new Container();
        openContainer = inventoryContainer;
    }

    /** 与 EntityPlayerSP.closeScreen 一致：发 C0D、换回背包容器、关界面。 */
    public void closeScreen() {
        if (openContainer != null) {
            sendQueue.addToSendQueue(new C0DPacketCloseWindow(openContainer.windowId));
        }
        openContainer = inventoryContainer;
        Minecraft.getMinecraft().displayGuiScreen(null);
    }
}
