package net.minecraft.client.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/** Headless GuiScreen with the standard button dispatch and Forge action boundary. */
public class GuiScreen extends Gui {
    public int width, height;
    public Minecraft mc;
    public FontRenderer fontRendererObj;
    public final List<GuiButton> buttonList = new ArrayList<GuiButton>();
    public static ActionListener listener;

    public interface ActionListener {
        void afterAction(GuiScreen screen, GuiButton button);
    }

    public void initGui() {}
    public void drawScreen(int x, int y, float partialTicks) {}
    public void drawDefaultBackground() {}
    public void handleMouseInput() throws IOException {}
    protected void keyTyped(char character, int key) throws IOException {}
    protected void actionPerformed(GuiButton button) throws IOException {}
    public void onGuiClosed() {}
    public boolean doesGuiPauseGame() { return true; }

    protected void mouseClicked(int x, int y, int mouseButton) throws IOException {
        if (mouseButton != 0) return;
        for (GuiButton button : buttonList) {
            if (!button.visible || !button.enabled || x < button.xPosition
                    || x >= button.xPosition + button.width || y < button.yPosition
                    || y >= button.yPosition + button.height) continue;
            actionPerformed(button);
            if (listener != null) listener.afterAction(this, button);
        }
    }
}
