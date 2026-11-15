package net.minecraftforge.client.event;

import net.minecraft.client.gui.GuiScreen;

/** Minimal 1.8.9 stub for replacing the title screen on open. */
public class GuiOpenEvent {
    public GuiScreen gui;

    public GuiOpenEvent(GuiScreen gui) {
        this.gui = gui;
    }
}
