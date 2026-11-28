package net.minecraft.client.gui;

/** Fail if Maver accidentally initializes, paints, or activates vanilla controls. */
public class GuiMainMenu extends GuiScreen {
    @Override public void initGui() {
        throw new AssertionError("Vanilla main menu initialization");
    }
    @Override public void drawScreen(int x, int y, float partialTicks) {
        throw new AssertionError("Vanilla main menu rendering");
    }
    @Override protected void actionPerformed(GuiButton button) {
        throw new AssertionError("Vanilla main menu action: " + button.id);
    }
}
