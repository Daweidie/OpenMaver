package org.lwjgl.opengl;

public final class EXTFramebufferBlit {
    public static final int GL_READ_FRAMEBUFFER_EXT = 0x8CA8;
    public static final int GL_DRAW_FRAMEBUFFER_EXT = 0x8CA9;
    public static final int GL_DRAW_FRAMEBUFFER_BINDING_EXT = 0x8CA6;
    public static final int GL_READ_FRAMEBUFFER_BINDING_EXT = 0x8CAA;

    public static void glBlitFramebufferEXT(int srcX0, int srcY0, int srcX1, int srcY1,
            int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {}
}
