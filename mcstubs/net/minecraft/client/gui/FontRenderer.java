package net.minecraft.client.gui;
public class FontRenderer {
    public int getStringWidth(String s) { return s == null ? 0 : s.length() * 6; }
    public int drawStringWithShadow(String s, float x, float y, int color) { return 0; }
}
