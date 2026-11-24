package org.lwjgl.input;

/** Test-only physical input. */
public class Mouse {
    public static boolean down;
    public static boolean isButtonDown(int button) { return down && button == 1; }
}
