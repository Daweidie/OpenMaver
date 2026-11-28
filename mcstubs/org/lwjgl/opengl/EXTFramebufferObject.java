package org.lwjgl.opengl;
public final class EXTFramebufferObject {
    public static final int GL_FRAMEBUFFER_EXT = 0x8D40;
    public static final int GL_COLOR_ATTACHMENT0_EXT = 0x8CE0;
    public static final int GL_FRAMEBUFFER_COMPLETE_EXT = 0x8CD5;
    public static final int GL_FRAMEBUFFER_BINDING_EXT = 0x8CA6;
    public static final int GL_RENDERBUFFER_EXT = 0x8D41;
    public static final int GL_RENDERBUFFER_BINDING_EXT = 0x8CA7;
    public static final int GL_DEPTH_ATTACHMENT_EXT = 0x8D00;
    public static int glGenRenderbuffersEXT() { return 0; }
    public static void glBindRenderbufferEXT(int target, int renderbuffer) {}
    public static void glRenderbufferStorageEXT(int target, int format, int width, int height) {}
    public static void glFramebufferRenderbufferEXT(int target, int attachment, int renderbufferTarget, int renderbuffer) {}
    public static void glDeleteRenderbuffersEXT(int renderbuffer) {}
    public static int glGenFramebuffersEXT() { return 0; }
    public static void glBindFramebufferEXT(int target, int framebuffer) {}
    public static void glFramebufferTexture2DEXT(int target, int attachment, int texTarget, int texture, int level) {}
    public static int glCheckFramebufferStatusEXT(int target) { return GL_FRAMEBUFFER_COMPLETE_EXT; }
    public static void glDeleteFramebuffersEXT(int framebuffer) {}
}
