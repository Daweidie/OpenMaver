package org.lwjgl.opengl;
public final class GL13 {
    public static void glActiveTexture(int unit) { GL11.state.put("active", "" + (unit - 0x84C0)); }
}
