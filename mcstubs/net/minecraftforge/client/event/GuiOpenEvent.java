package net.minecraftforge.client.event;

import net.minecraft.client.gui.GuiScreen;

/**
 * Minimal 1.8.9 stub for replacing the title screen on open and for suppressing the
 * chest GUI when the silent container open runs.
 *
 * <p>{@code setCanceled}/{@code isCanceled} 在运行期来自 Forge 的
 * {@code net.minecraftforge.fml.common.eventhandler.Event}（{@code GuiOpenEvent} 的父类），
 * 这里只为通过 javac 声明一遍，方法名不做 reobf（Forge 类名/成员名本身不是混淆的）。
 */
public class GuiOpenEvent {
    public GuiScreen gui;

    public GuiOpenEvent(GuiScreen gui) {
        this.gui = gui;
    }

    public void setCanceled(boolean canceled) {
    }

    public boolean isCanceled() {
        return false;
    }
}
