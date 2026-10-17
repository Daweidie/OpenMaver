package net.minecraft.client.gui;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
public class GuiScreen extends Gui {
    public int width;
    public int height;
    public Minecraft mc;
    public FontRenderer fontRendererObj;
    public final List<GuiButton> buttonList = new ArrayList<GuiButton>();
    public void initGui() {}
    public void drawScreen(int mx, int my, float pt) {}
    public void drawDefaultBackground() {}
    protected void keyTyped(char c, int k) throws IOException {}
    protected void actionPerformed(GuiButton b) throws IOException {}
    protected void mouseClicked(int mx, int my, int btn) throws IOException {}
    public boolean doesGuiPauseGame() { return true; }
}
