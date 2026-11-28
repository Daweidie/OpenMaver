package net.minecraft.client.gui;
import net.minecraft.client.Minecraft;
public class GuiButton {
    public int id;
    public String displayString;
    public int xPosition, yPosition, width, height;
    public boolean visible = true;
    public boolean enabled = true;
    public GuiButton(int id, int x, int y, int w, int h, String t) {
        this.id = id;
        this.displayString = t;
        this.xPosition = x;
        this.yPosition = y;
        this.width = w;
        this.height = h;
    }
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {}
}
