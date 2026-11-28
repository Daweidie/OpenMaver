package org.lwjgl.input;
public class Mouse {
    public static boolean down = true;
    public static boolean isButtonDown(int button) { return down && button == 1; }
}
