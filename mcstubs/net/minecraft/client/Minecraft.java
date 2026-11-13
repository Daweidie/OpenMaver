package net.minecraft.client;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

public class Minecraft {
    public World theWorld;
    public EntityPlayer thePlayer;
    public GuiScreen currentScreen;
    public FontRenderer fontRendererObj;
    public RenderManager renderManager;
    public MovingObjectPosition objectMouseOver;

    public static Minecraft getMinecraft() { return null; }
    public void displayGuiScreen(GuiScreen s) { this.currentScreen = s; }
    public RenderManager getRenderManager() { return renderManager; }
    public TextureManager getTextureManager() { return null; }
}
