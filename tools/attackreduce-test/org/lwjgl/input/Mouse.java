package org.lwjgl.input;

/** 避免在无图形环境加载 LWJGL native，同时可验证左右键避让。 */
public class Mouse {
    public static boolean left;
    public static boolean right;
    public static boolean isButtonDown(int button) { return button == 0 ? left : right; }
}
