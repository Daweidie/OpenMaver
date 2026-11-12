import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import util.Logger;

import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.imageio.ImageIO;

/** Draws the bundled Maver logo in the ClickGUI rail. */
public final class MaverLogo {

    private static final String RESOURCE = "/assets/openmaver/MaverLogo.png";

    private static int textureId;
    private static int imageWidth;
    private static int imageHeight;
    private static int lastLoggedWidth = -1;
    private static int lastLoggedHeight = -1;
    private static float lastLoggedScale = -1.0F;
    private static boolean failed;

    private MaverLogo() {
    }

    public static boolean draw(FontRenderer fallback, int x, int y, int maxWidth, int maxHeight,
            float scale, int fallbackColor) {
        if (!ensureLoaded()) {
            fallback.drawStringWithShadow("Maver", x, y + 12, fallbackColor);
            return false;
        }

        float fit = Math.min(maxWidth / (float) imageWidth, maxHeight / (float) imageHeight);
        int width = Math.max(1, Math.round(imageWidth * fit * scale));
        int height = Math.max(1, Math.round(imageHeight * fit * scale));

        if (width != lastLoggedWidth || height != lastLoggedHeight
                || scale != lastLoggedScale) {
            Logger.info("Logo", String.format(java.util.Locale.ROOT,
                    "显示尺寸 %.2fx, %d x %d GUI px", scale, width, height));
            lastLoggedWidth = width;
            lastLoggedHeight = height;
            lastLoggedScale = scale;
        }

        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_TEXTURE_BIT
                | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GlColor.set(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        int drawX = x + Math.max(0, (maxWidth - width) / 2);
        // This helper derives UVs from the destination size, so use that size
        // for the texture span as well to sample the entire logo when scaled.
        Gui.drawModalRectWithCustomSizedTexture(drawX, y, 0.0F, 0.0F, width, height,
                width, height);
        GL11.glPopAttrib();
        GlColor.restore();
        return true;
    }

    private static boolean ensureLoaded() {
        if (textureId != 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        try (InputStream input = MaverLogo.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Maver wordmark resource is missing");
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("Maver wordmark image is invalid");
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
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, imageWidth, imageHeight,
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment);
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
