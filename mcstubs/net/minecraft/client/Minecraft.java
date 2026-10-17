package net.minecraft.client;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
public class Minecraft {
    public World theWorld;
    public EntityPlayer thePlayer;
    public GuiScreen currentScreen;
    public FontRenderer fontRendererObj;
    public static Minecraft getMinecraft() { return null; }
    public void displayGuiScreen(GuiScreen s) { this.currentScreen = s; }
}
