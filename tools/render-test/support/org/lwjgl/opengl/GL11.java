package org.lwjgl.opengl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Driver state separate from the Minecraft cache, so stale-cache bugs are observable. */
public class GL11 {
    public static final Map<String, String> state = new LinkedHashMap<String, String>();
    public static final Deque<Map<String, String>> stack = new ArrayDeque<Map<String, String>>();
    public static int matrices;
    public static boolean failNextDepth;
    public static final Set<Integer> textures = new HashSet<Integer>();
    public static final List<Integer> clears = new ArrayList<Integer>();
    public static final List<String> draws = new ArrayList<String>();
    private static int nextTexture = 1000;
    private static int matrixMode = 5888;

    public static void glPushAttrib(int mask) { stack.push(new LinkedHashMap<String, String>(state)); }
    public static void glPopAttrib() { state.clear(); state.putAll(stack.pop()); }
    public static int glGetInteger(int key) {
        if (key == 0x8CA6) return EXTFramebufferObject.draw;
        if (key == 0x8CAA) return EXTFramebufferObject.read;
        if (key == 0x8CA7) return EXTFramebufferObject.renderbuffer;
        if (key == 0x8B8D) return GL20.current;
        if (key == 0x8069) return Integer.parseInt(state.getOrDefault("texture" + activeUnit(), "0"));
        if (key == 0x84E0) return 0x84C0 + activeUnit();
        return matrixMode;
    }
    public static void glMatrixMode(int mode) { matrixMode = mode; }
    public static void glPushMatrix() { matrices++; }
    public static void glPopMatrix() { matrices--; }
    private static int activeUnit() { return Integer.parseInt(state.getOrDefault("active", "0")); }
    public static int glGenTextures() { int id = nextTexture++; textures.add(id); return id; }
    public static void glDeleteTextures(int id) { textures.remove(id); }
    public static void glBindTexture(int target, int id) { state.put("texture" + activeUnit(), "" + id); }
    public static void glTexParameteri(int target, int key, int value) {}
    public static void glTexImage2D(int target, int level, int internal, int w, int h,
            int border, int format, int type, java.nio.ByteBuffer data) {}
    public static void glViewport(int x, int y, int w, int h) { state.put("viewport", x + ":" + y + ":" + w + ":" + h); }
    public static void glDisable(int key) { state.put("enable" + key, "false"); }
    public static void glEnable(int key) { state.put("enable" + key, "true"); }
    public static void glDepthMask(boolean value) { state.put("depthMask", "" + value); }
    public static void glColorMask(boolean r, boolean g, boolean b, boolean a) { state.put("mask", "" + r + g + b + a); }
    public static void glClearColor(float r, float g, float b, float a) { state.put("clearColor", r + ":" + g + ":" + b + ":" + a); }
    public static void glClearDepth(double value) { state.put("clearDepth", "" + value); }
    public static void glClear(int mask) { clears.add(EXTFramebufferObject.draw); }
    public static void glBlendFunc(int a, int b) { state.put("blendFunc", a + ":" + b); }
    public static void glBegin(int mode) {}
    public static void glTexCoord2f(float u, float v) {}
    public static void glVertex2f(float x, float y) {}
    public static void glEnd() {
        draws.add(EXTFramebufferObject.draw + ":" + GL20.stage + ":" + state.get("depthMask"));
    }
}
