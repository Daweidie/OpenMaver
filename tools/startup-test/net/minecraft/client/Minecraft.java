package net.minecraft.client;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Session;
import net.minecraft.world.World;

/** Deliberately incompatible player descriptor to inject the same kind of linkage failure. */
public class Minecraft {
    public static Minecraft instance;
    public World theWorld;
    public EntityPlayer thePlayer;
    public GuiScreen currentScreen;
    public FontRenderer fontRendererObj;
    public RenderManager renderManager;
    public MovingObjectPosition objectMouseOver;
    public GameSettings gameSettings;
    public static Minecraft getMinecraft() { return instance; }
    public void displayGuiScreen(GuiScreen screen) { currentScreen = screen; }
    public RenderManager getRenderManager() { return renderManager; }
    public TextureManager getTextureManager() { return null; }
    public Session getSession() { return null; }
}
