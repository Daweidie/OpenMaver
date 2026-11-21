package net.minecraft.client;

/** Headless client; game state is accessed through the production reflection paths. */
public class Minecraft {
    private static Minecraft instance;
    public Object thePlayer, theWorld, currentScreen, gameSettings, playerController;

    public Minecraft() { instance = this; }
    public static Minecraft getMinecraft() { return instance; }
}
