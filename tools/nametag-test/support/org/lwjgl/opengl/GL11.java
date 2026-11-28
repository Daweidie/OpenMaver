package org.lwjgl.opengl;

/** Records the actual renderer's submitted geometry without requiring a GL context. */
public class GL11 {
    public static int vertices3d;
    public static boolean failNextBegin;
    public static final java.util.List<double[]> points = new java.util.ArrayList<double[]>();
    private static final java.util.Deque<double[]> matrices = new java.util.ArrayDeque<double[]>();
    private static double[] matrix = identity();
    public static float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
    public static void reset() { vertices3d = 0; minY = Float.POSITIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY; points.clear(); }
    public static int glGetInteger(int name) { return 0; }
    public static void glPushAttrib(int bits) {}
    public static void glPopAttrib() {}
    public static void glPushMatrix() { matrices.push(matrix.clone()); }
    public static void glPopMatrix() { matrix = matrices.pop(); }
    private static double[] identity() {
        return new double[] {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }
    private static void multiply(double[] next) {
        double[] result = new double[16];
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++)
            for (int k = 0; k < 4; k++) result[row * 4 + col] += matrix[row * 4 + k] * next[k * 4 + col];
        matrix = result;
    }
    public static void glTranslatef(float x, float y, float z) {
        double[] next = identity(); next[3] = x; next[7] = y; next[11] = z; multiply(next);
    }
    public static void glRotatef(float angle, float x, float y, float z) {
        double c = Math.cos(Math.toRadians(angle)), s = Math.sin(Math.toRadians(angle));
        double[] next = identity();
        if (x == 1) { next[5] = c; next[6] = -s; next[9] = s; next[10] = c; }
        else if (y == 1) { next[0] = c; next[2] = s; next[8] = -s; next[10] = c; }
        else throw new AssertionError("Unsupported test rotation axis");
        multiply(next);
    }
    public static void glScalef(float x, float y, float z) {
        double[] next = identity(); next[0] = x; next[5] = y; next[10] = z; multiply(next);
    }
    public static void glDisable(int name) {}
    public static void glEnable(int name) {}
    public static void glDepthMask(boolean enabled) {}
    public static void glBlendFunc(int a, int b) {}
    public static void glBegin(int mode) {
        if (failNextBegin) {
            failNextBegin = false;
            throw new IllegalStateException("simulated hurt fill failure");
        }
    }
    public static void glEnd() {}
    public static void glVertex2f(float x, float y) {
        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        points.add(new double[] {matrix[0] * x + matrix[1] * y + matrix[3],
                matrix[4] * x + matrix[5] * y + matrix[7], matrix[8] * x + matrix[9] * y + matrix[11]});
    }
    public static void glVertex3d(double x, double y, double z) { vertices3d++; }
}
