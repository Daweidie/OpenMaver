package net.minecraftforge.client.event;

import net.minecraft.client.gui.GuiScreen;

public class GuiOpenEvent {
    public GuiScreen gui;
    private boolean canceled;

    public GuiOpenEvent(GuiScreen gui) {
        this.gui = gui;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }

    public boolean isCanceled() {
        return canceled;
    }
}
