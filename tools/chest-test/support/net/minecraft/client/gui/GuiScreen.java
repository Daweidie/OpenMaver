package net.minecraft.client.gui;

public class GuiScreen {
    public int width = 854;
    public int height = 480;
    public Object mc;

    public void setWorldAndResolution(Object mc, int width, int height) {
        this.mc = mc;
        this.width = width;
        this.height = height;
        initGui();
    }

    public void initGui() {
    }

    public void onGuiClosed() {
    }
}
