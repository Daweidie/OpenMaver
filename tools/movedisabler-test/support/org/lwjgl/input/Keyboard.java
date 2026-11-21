package org.lwjgl.input;

/** Physical keyboard fixture; independent of Minecraft KeyBinding.pressed. */
public final class Keyboard {
    private static final boolean[] keys = new boolean[256];
    public static final int KEY_ESCAPE=1, KEY_BACK=14, KEY_RETURN=28, KEY_LCONTROL=29;
    public static final int KEY_K=37, KEY_A=30, KEY_S=31, KEY_D=32, KEY_W=17, KEY_SPACE=57;
    public static final int KEY_RSHIFT=54, KEY_J=36, KEY_M=50, KEY_RCONTROL=157;
    public static boolean isKeyDown(int code) { return code > 0 && code < keys.length && keys[code]; }
    public static void setKeyDown(int code, boolean down) { keys[code] = down; }
    public static void reset() { java.util.Arrays.fill(keys, false); }
    public static void enableRepeatEvents(boolean enabled) { }
    public static int getEventKey() { return 0; }
    public static boolean getEventKeyState() { return false; }
    public static String getKeyName(int code) { return ""; }
    public static int getKeyIndex(String name) { return -1; }
}
