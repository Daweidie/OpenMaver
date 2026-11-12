import net.minecraft.client.renderer.GlStateManager;

/**
 * Single entry point for changing the current OpenGL color.
 *
 * <p>Minecraft's {@link GlStateManager} caches the current color and skips the underlying
 * {@code glColor4f} call when the requested color matches its cache. Code that calls
 * {@code GL11.glColor4f} directly therefore leaves the cache out of sync with the real GL state;
 * later Minecraft draws can then skip a required color update and inherit the previous color
 * (usually black text). Every OpenMaver renderer must set its color through this class instead,
 * and must not save/restore GL_CURRENT_BIT around those draws.
 */
public final class GlColor {

    private GlColor() {
    }

    /** Sets the current color in both OpenGL and Minecraft's state cache. */
    public static void set(float red, float green, float blue, float alpha) {
        GlStateManager.color(red, green, blue, alpha);
    }

    /** Sets the current color from an ARGB integer in both OpenGL and Minecraft's cache. */
    public static void set(int argb) {
        set((argb >> 16 & 0xFF) / 255.0F, (argb >> 8 & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F, (argb >>> 24) / 255.0F);
    }

    /** Restores opaque white, the color Minecraft's GUI/text renderer expects by default. */
    public static void restore() {
        // GlStateManager.color is a no-op when the requested color already equals its cache.
        // Set a different value first so the white restore reaches the driver even if the cache
        // was stale (for example after a raw GL push/pop or another renderer changed color).
        GlStateManager.color(0.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
