package net.minecraft.client.renderer;

import java.util.Arrays;
import org.lwjgl.opengl.GL11;

/** Models vanilla's state cache: requesting the cached value skips the driver call. */
public class GlStateManager {
    private static int unit;
    private static final Texture[] textures = {new Texture(), new Texture(), new Texture()};
    private static final Color color = new Color();
    private static final Flag lighting = new Flag();
    private static int depthFunc;
    private static boolean depthMask, alpha, blend, depth, cull;
    private static String mask;

    public static void activeTexture(int value) {
        if (unit != value) { unit = value; GL11.state.put("active", "" + value); }
    }
    public static void setActiveTexture(int value) { activeTexture(value - 0x84C0); }
    public static void bindTexture(int value) {
        if (textures[unit].binding != value) {
            textures[unit].binding = value;
            GL11.state.put("texture" + unit, "" + value);
        }
    }
    public static void color(float r, float g, float b, float a) {
        if (color.r != r || color.g != g || color.b != b || color.a != a) {
            color.r = r; color.g = g; color.b = b; color.a = a;
            GL11.state.put("color", Arrays.toString(new float[] {r, g, b, a}));
        }
    }
    public static void lighting(boolean value) {
        if (lighting.on != value) { lighting.on = value; GL11.state.put("lighting", "" + value); }
    }
    public static void enableDepth() {
        depth = true;
        GL11.state.put("depth", "true");
        if (GL11.failNextDepth) {
            GL11.failNextDepth = false;
            throw new IllegalStateException("simulated state failure");
        }
    }
    public static void depthMask(boolean value) { depthMask = value; GL11.state.put("depthMask", "" + value); }
    public static void depthFunc(int value) { depthFunc = value; GL11.state.put("depthFunc", "" + value); }
    public static void enableAlpha() { alpha = true; GL11.state.put("alpha", "true"); }
    public static void alphaFunc(int f, float ref) { GL11.state.put("alphaFunc", f + ":" + ref); }
    public static void enableBlend() { blend = true; GL11.state.put("blend", "true"); }
    public static void blendFunc(int a, int b) { GL11.state.put("blendFunc", a + ":" + b); }
    public static void enableTexture2D() { textures[unit].enabled.on = true; GL11.state.put("textured" + unit, "true"); }
    public static void enableCull() { cull = true; GL11.state.put("cull", "true"); }
    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        mask = "" + r + g + b + a; GL11.state.put("mask", mask);
    }
    public static String cache() {
        return unit + ":" + textures[0].binding + ":" + textures[1].binding + ":"
                + textures[2].binding + ":" + textures[0].enabled.on + ":"
                + textures[1].enabled.on + ":" + textures[2].enabled.on + ":"
                + color.r + ":" + color.g + ":" + color.b + ":" + color.a + ":"
                + lighting.on + ":" + depthFunc + ":" + depthMask + ":" + alpha
                + ":" + blend + ":" + depth + ":" + cull;
    }
    private static final class Texture { int binding; final Flag enabled = new Flag(); }
    private static final class Flag { boolean on; }
    private static final class Color { float r, g, b, a; }
}
