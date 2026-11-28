package org.lwjgl.opengl;
import java.util.HashSet;
import java.util.Set;
public final class EXTFramebufferObject {
    public static int draw = 81, read = 82, renderbuffer = 83, next = 3000;
    public static boolean complete = true;
    public static final Set<Integer> framebuffers = new HashSet<Integer>();
    public static final Set<Integer> renderbuffers = new HashSet<Integer>();
    public static int glGenFramebuffersEXT() { int id = next++; framebuffers.add(id); return id; }
    public static void glBindFramebufferEXT(int target, int id) {
        if (target != 0x8CA8) draw = id;
        if (target != 0x8CA9) read = id;
    }
    public static void glFramebufferTexture2DEXT(int target, int attachment, int texTarget, int id, int level) {}
    public static int glCheckFramebufferStatusEXT(int target) { return complete ? 0x8CD5 : 0; }
    public static void glDeleteFramebuffersEXT(int id) { framebuffers.remove(id); }
    public static int glGenRenderbuffersEXT() { int id = next++; renderbuffers.add(id); return id; }
    public static void glBindRenderbufferEXT(int target, int id) { renderbuffer = id; }
    public static void glRenderbufferStorageEXT(int target, int format, int w, int h) {}
    public static void glFramebufferRenderbufferEXT(int target, int attachment, int bufferTarget, int id) {}
    public static void glDeleteRenderbuffersEXT(int id) { renderbuffers.remove(id); }
}
