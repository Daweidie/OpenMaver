import org.lwjgl.opengl.GL11;

/** Translucent world-space cuboid, with all render state restored after the pass. */
final class FilledBox {
    private FilledBox() {}

    static void draw(double x1, double y1, double z1, double x2, double y2, double z2,
            float r, float g, float b, float alpha, boolean throughWalls) {
        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            if (throughWalls) GL11.glDisable(GL11.GL_DEPTH_TEST);
            else GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlColor.set(r, g, b, alpha);
            GL11.glBegin(GL11.GL_QUADS);
            face(x1,y1,z1, x2,y1,z1, x2,y2,z1, x1,y2,z1);
            face(x1,y1,z2, x2,y1,z2, x2,y2,z2, x1,y2,z2);
            face(x1,y1,z1, x1,y1,z2, x1,y2,z2, x1,y2,z1);
            face(x2,y1,z1, x2,y1,z2, x2,y2,z2, x2,y2,z1);
            face(x1,y1,z1, x2,y1,z1, x2,y1,z2, x1,y1,z2);
            face(x1,y2,z1, x2,y2,z1, x2,y2,z2, x1,y2,z2);
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
            GlColor.restore();
        }
    }

    private static void face(double a, double b, double c, double d, double e, double f,
            double g, double h, double i, double j, double k, double l) {
        GL11.glVertex3d(a,b,c); GL11.glVertex3d(d,e,f);
        GL11.glVertex3d(g,h,i); GL11.glVertex3d(j,k,l);
    }
}
