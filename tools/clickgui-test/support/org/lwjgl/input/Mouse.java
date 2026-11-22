package org.lwjgl.input;

public final class Mouse {
    public static boolean left;
    public static int wheel;
    public static boolean isButtonDown(int button) { return button == 0 && left; }
    public static int getEventDWheel() {
        int value = wheel;
        wheel = 0;
        return value;
    }
}
