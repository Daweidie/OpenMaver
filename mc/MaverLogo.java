import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import util.Logger;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;

/** Draws the ClickGUI wordmark and the separate main-menu mark. */
public final class MaverLogo {

    private static final MaverLogo WORDMARK = new MaverLogo("/assets/openmaver/MaverLogo.png");
    private static final MaverLogo MENU_MARK = new MaverLogo("/assets/openmaver/MenuLogo.png");

    private final String resource;

    private int textureId;
    private int imageWidth;
    private int imageHeight;
    private final Set<String> LOGGED_SIZES = new HashSet<String>();
    private boolean failed;

    private MaverLogo(String resource) {
        this.resource = resource;
    }

    public static boolean draw(FontRenderer fallback, int x, int y, int maxWidth, int maxHeight,
            float scale, int fallbackColor) {
        return WORDMARK.drawInBox(fallback, x, y, maxWidth, maxHeight, scale, fallbackColor);
    }

    private boolean drawInBox(FontRenderer fallback, int x, int y, int maxWidth, int maxHeight,
            float scale, int fallbackColor) {
        if (!ensureLoaded()) {
            fallback.drawStringWithShadow("maver", x, y + 12, fallbackColor);
            return false;
        }

        float fit = Math.min(maxWidth / (float) imageWidth, maxHeight / (float) imageHeight);
        int width = Math.max(1, Math.round(imageWidth * fit * scale));
        int height = Math.max(1, Math.round(imageHeight * fit * scale));
        int drawX = x + Math.max(0, (maxWidth - width) / 2);
        return drawScaled(drawX, y, width, height, scale, fallbackColor);
    }

    /** Draws the logo centered on the given GUI point (used by the title screen badge). */
    public static boolean drawCentered(FontRenderer fallback, int centerX, int centerY,
            int maxWidth, int maxHeight, float scale, int fallbackColor) {
        return WORDMARK.drawCenteredImage(fallback, centerX, centerY, maxWidth, maxHeight,
                scale, fallbackColor);
    }

    public static boolean drawMenuCentered(FontRenderer fallback, int centerX, int centerY,
            int maxWidth, int maxHeight, float scale, int fallbackColor) {
        return MENU_MARK.drawCenteredImage(fallback, centerX, centerY, maxWidth, maxHeight,
                scale, fallbackColor);
    }

    private boolean drawCenteredImage(FontRenderer fallback, int centerX, int centerY,
            int maxWidth, int maxHeight, float scale, int fallbackColor) {
        if (!ensureLoaded()) {
            fallback.drawStringWithShadow("maver", centerX - 18, centerY, fallbackColor);
            return false;
        }

        float fit = Math.min(maxWidth / (float) imageWidth, maxHeight / (float) imageHeight);
        int width = Math.max(1, Math.round(imageWidth * fit * scale));
        int height = Math.max(1, Math.round(imageHeight * fit * scale));
        return drawScaled(centerX - width / 2, centerY - height / 2, width, height,
                scale, fallbackColor);
    }

    private boolean drawScaled(int drawX, int y, int width,
            int height, float scale, int color) {
        String sizeKey = width + "x" + height + "@" + scale;
        if (LOGGED_SIZES.add(sizeKey)) {
            Logger.info("Logo", String.format(java.util.Locale.ROOT,
                    "显示尺寸 %.2fx, %d x %d GUI px", scale, width, height));
        }

        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_TEXTURE_BIT
                | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GlColor.set(1.0F, 1.0F, 1.0F, (color >>> 24) / 255.0F);
        GlStateManager.bindTexture(textureId);
        // This helper derives UVs from the destination size, so use that size
        // for the texture span as well to sample the entire logo when scaled.
        Gui.drawModalRectWithCustomSizedTexture(drawX, y, 0.0F, 0.0F, width, height,
                width, height);
        GlStateManager.bindTexture(0);
        GL11.glPopAttrib();
        GlColor.restore();
        return true;
    }

    private boolean ensureLoaded() {
        if (textureId != 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        try (InputStream input = MaverLogo.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Maver logo resource is missing: " + resource);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("Maver logo image is invalid: " + resource);
            }
            imageWidth = image.getWidth();
            imageHeight = image.getHeight();
            ByteBuffer pixels = ByteBuffer.allocateDirect(imageWidth * imageHeight * 4)
                    .order(ByteOrder.nativeOrder());
            for (int row = 0; row < imageHeight; row++) {
                for (int column = 0; column < imageWidth; column++) {
                    int argb = image.getRGB(column, row);
                    pixels.put((byte) (argb >>> 16));
                    pixels.put((byte) (argb >>> 8));
                    pixels.put((byte) argb);
                    pixels.put((byte) (argb >>> 24));
                }
            }
            pixels.flip();

            int unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);
            textureId = GL11.glGenTextures();
            GlStateManager.bindTexture(textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, imageWidth, imageHeight,
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment);
            GlStateManager.bindTexture(0);
            GL11.glPopAttrib();
            if (textureId == 0) {
                throw new IllegalStateException("OpenGL did not allocate a texture for the logo");
            }
            return true;
        } catch (Throwable error) {
            failed = true;
            System.err.println("[OpenMaver] Maver logo unavailable: " + error);
            return false;
        }
    }
}
