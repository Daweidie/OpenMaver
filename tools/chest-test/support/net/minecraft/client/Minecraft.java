package net.minecraft.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraft.world.World;

/** 测试替身：模拟 Forge 打过补丁的 Minecraft.displayGuiScreen（先派发 GuiOpenEvent）。 */
public class Minecraft {

    /** 相当于 OpenMaverClientHandler 在 GuiOpenEvent 上的处理。 */
    public interface GuiOpenHook {
        boolean onOpen(GuiOpenEvent event);
    }

    public static GuiOpenHook hook;

    private static Minecraft instance;

    public World theWorld;
    public EntityPlayer thePlayer;
    public GuiScreen currentScreen;
    public Object objectMouseOver;
    public Object playerController;
    public int displayWidth = 854;
    public int displayHeight = 480;

    /** 真正显示出来的界面（被取消的不算）。 */
    public final List<GuiScreen> shownScreens = new ArrayList<GuiScreen>();

    public Minecraft() {
        instance = this;
    }

    public static Minecraft getMinecraft() {
        return instance;
    }

    public void displayGuiScreen(GuiScreen guiScreenIn) {
        GuiScreen old = currentScreen;
        GuiOpenEvent event = new GuiOpenEvent(guiScreenIn);
        if (hook != null && hook.onOpen(event)) {
            return;
        }
        if (event.isCanceled()) {
            return;
        }
        guiScreenIn = event.gui;
        if (old != null && guiScreenIn != old) {
            old.onGuiClosed();
        }
        currentScreen = guiScreenIn;
        if (guiScreenIn != null) {
            shownScreens.add(guiScreenIn);
            guiScreenIn.setWorldAndResolution(this, displayWidth, displayHeight);
        }
    }
}
