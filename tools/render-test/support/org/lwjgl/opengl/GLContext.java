package org.lwjgl.opengl;
public final class GLContext {
    private static final Capabilities caps = new Capabilities();
    public static Capabilities getCapabilities() { return caps; }
    public static final class Capabilities { public boolean GL_EXT_framebuffer_blit = true; }
}
