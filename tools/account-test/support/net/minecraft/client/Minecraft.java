package net.minecraft.client;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.Session;
import net.minecraft.world.World;

public class Minecraft {
    public static Minecraft instance;
    public World theWorld;
    public GuiScreen currentScreen;
    public FontRenderer fontRendererObj = new FontRenderer();
    public GameSettings gameSettings;
    private final Session session = new Session("Original", "11111111111111111111111111111111", "original-token", "mojang");
    private final ProfileProperties profileProperties = new ProfileProperties();
    public boolean cacheCleared() { return profileProperties.cleared; }
    public static class ProfileProperties {
        boolean cleared;
        public void clear() { cleared = true; }
    }
    public static Minecraft getMinecraft() { return instance; }
    public Session getSession() { return session; }
    public TextureManager getTextureManager() { return null; }
    public void toggleFullscreen() { }
    public void shutdown() { }
    public void displayGuiScreen(GuiScreen screen) {
        if (currentScreen != null) currentScreen.onGuiClosed();
        currentScreen = screen;
        if (screen != null) {
            screen.mc = this;
            screen.width = 900;
            screen.height = 500;
            screen.fontRendererObj = fontRendererObj;
            screen.initGui();
        }
    }
}
