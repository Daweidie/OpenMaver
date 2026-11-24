package org.lwjgl.opengl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Driver state separate from the Minecraft cache, so stale-cache bugs are observable. */
public class GL11 {
    public static final Map<String, String> state = new LinkedHashMap<String, String>();
    public static final Deque<Map<String, String>> stack = new ArrayDeque<Map<String, String>>();
    public static int matrices;
    public static boolean failNextDepth;
    private static int matrixMode = 5888;

    public static void glPushAttrib(int mask) { stack.push(new LinkedHashMap<String, String>(state)); }
    public static void glPopAttrib() { state.clear(); state.putAll(stack.pop()); }
    public static int glGetInteger(int key) { return matrixMode; }
    public static void glMatrixMode(int mode) { matrixMode = mode; }
    public static void glPushMatrix() { matrices++; }
    public static void glPopMatrix() { matrices--; }
}
