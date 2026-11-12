package net.minecraft.client;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraft.client.renderer.entity.RenderManager;
public class Minecraft {
    public World theWorld;
    public EntityPlayer thePlayer;
    public GuiScreen currentScreen;
    public FontRenderer fontRendererObj;
    public RenderManager renderManager;
    public static Minecraft getMinecraft() { return null; }
    public void displayGuiScreen(GuiScreen s) { this.currentScreen = s; }
    public RenderManager getRenderManager() { return renderManager; }
}
