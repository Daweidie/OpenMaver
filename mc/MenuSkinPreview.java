import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

/** A display-only skin model that also works before a world has been loaded. */
public final class MenuSkinPreview {
    private MenuSkinPreview() {
    }

    public static void draw(TextureManager textures, ResourceLocation skin,
            float centerX, float feetY, float scale) {
        if (skin == null) {
            return;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.bindTexture(0);
            textures.bindTexture(skin);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTranslatef(centerX, feetY, 100.0F);
            GL11.glScalef(scale, -scale, scale);
            GL11.glRotatef(-8.0F, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef(-22.0F, 0.0F, 1.0F, 0.0F);

            // Standard 64 x 64 skin UVs: legs, torso, arms and head, then outer layers.
            box(-4, 0, -2, 4, 12, 4, 0, 16, 0);
            box(0, 0, -2, 4, 12, 4, 16, 48, 0);
            box(-4, 12, -2, 8, 12, 4, 16, 16, 0);
            box(-8, 12, -2, 4, 12, 4, 40, 16, 0);
            box(4, 12, -2, 4, 12, 4, 32, 48, 0);
            box(-4, 24, -4, 8, 8, 8, 0, 0, 0);
            box(-4, 0, -2, 4, 12, 4, 0, 32, 0.25F);
            box(0, 0, -2, 4, 12, 4, 0, 48, 0.25F);
            box(-4, 12, -2, 8, 12, 4, 16, 32, 0.25F);
            box(-8, 12, -2, 4, 12, 4, 40, 32, 0.25F);
            box(4, 12, -2, 4, 12, 4, 48, 48, 0.25F);
            box(-4, 24, -4, 8, 8, 8, 32, 0, 0.5F);
        } finally {
            GL11.glPopMatrix();
            GlStateManager.bindTexture(0);
            GL11.glPopAttrib();
            GlColor.restore();
        }
    }

    private static void box(float x, float y, float z, int w, int h, int d,
            int u, int v, float expand) {
        float x0 = x - expand, x1 = x + w + expand;
        float y0 = y - expand, y1 = y + h + expand;
        float z0 = z - expand, z1 = z + d + expand;
        GL11.glBegin(GL11.GL_QUADS);
        // Front, back, right, left, top, bottom. Vertex order follows the skin net.
        face(u + d, v + d, w, h, 1.0F,
                x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1);
        face(u + 2 * d + w, v + d, w, h, 0.65F,
                x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0);
        face(u, v + d, d, h, 0.78F,
                x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0);
        face(u + d + w, v + d, d, h, 0.78F,
                x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1);
        face(u + d, v, w, d, 0.9F,
                x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        face(u + d + w, v, w, d, 0.6F,
                x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
        GL11.glEnd();
    }

    private static void face(int u, int v, int w, int h, float light,
            float ax, float ay, float az, float bx, float by, float bz,
            float cx, float cy, float cz, float dx, float dy, float dz) {
        GlColor.set(light, light, light, 1.0F);
        GL11.glTexCoord2f(u / 64.0F, v / 64.0F);
        GL11.glVertex3f(ax, ay, az);
        GL11.glTexCoord2f((u + w) / 64.0F, v / 64.0F);
        GL11.glVertex3f(bx, by, bz);
        GL11.glTexCoord2f((u + w) / 64.0F, (v + h) / 64.0F);
        GL11.glVertex3f(cx, cy, cz);
        GL11.glTexCoord2f(u / 64.0F, (v + h) / 64.0F);
        GL11.glVertex3f(dx, dy, dz);
    }
}
