package org.lwjgl.opengl;

/** Headless window focus fixture. */
public final class Display {
    public static boolean active = true;
    public static boolean isActive() { return active; }
    public static int getWidth() { return 854; }
    public static int getHeight() { return 480; }
}
