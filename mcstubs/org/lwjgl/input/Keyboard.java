package org.lwjgl.input;
public class Keyboard {
    public static final int KEY_ESCAPE = 1;
    public static final int KEY_BACK = 14;
    public static final int KEY_RETURN = 28;
    public static final int KEY_LCONTROL = 29;
    public static final int KEY_K = 37;
    // LWJGL 2 key table: WASD / space used by the keystrokes HUD.
    public static final int KEY_A = 30;
    public static final int KEY_S = 31;
    public static final int KEY_D = 32;
    public static final int KEY_W = 17;
    public static final int KEY_SPACE = 57;
    // LWJGL 2 key table: left shift is 42, right shift is 54.
    public static final int KEY_RSHIFT = 54;
    public static final int KEY_J = 36;
    public static final int KEY_M = 50;
    public static final int KEY_RCONTROL = 157;
    public static boolean isKeyDown(int k) { return false; }
    public static void enableRepeatEvents(boolean enabled) {}
    public static int getEventKey() { return 0; }
    public static boolean getEventKeyState() { return false; }
    public static String getKeyName(int key) { return ""; }
    /** LWJGL 2: 键名 -> 键码，未知名字返回 -1（ChestFeature 的快捷键轮询用到）。 */
    public static int getKeyIndex(String keyName) { return -1; }
}
