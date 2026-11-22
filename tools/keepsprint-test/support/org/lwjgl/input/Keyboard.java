package org.lwjgl.input;

/** Physical state deliberately independent of KeyBinding state. */
public final class Keyboard {
    private static final boolean[] keys = new boolean[256];

    public static boolean isKeyDown(int code) {
        return code > 0 && code < keys.length && keys[code];
    }

    public static void setKeyDown(int code, boolean down) {
        keys[code] = down;
    }

    public static void reset() {
        java.util.Arrays.fill(keys, false);
    }
}
