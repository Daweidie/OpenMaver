package net.minecraft.client;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.GameSettings;

/** SRG name and WorldClient descriptor intentionally differ from the build stub. */
public class Minecraft {
    public WorldClient field_71441_e;
    public EntityPlayerSP thePlayer;
    public FontRenderer fontRendererObj;
    public RenderManager renderManager;
    public GameSettings gameSettings;
    public RenderManager getRenderManager() { return renderManager; }
}
