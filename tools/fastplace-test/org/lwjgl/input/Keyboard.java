package org.lwjgl.input;
public class Keyboard {
    public static boolean down;
    public static boolean isKeyDown(int key) { return down && key == 37; }
}
