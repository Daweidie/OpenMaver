package org.lwjgl.opengl;
public class GL11 {
    public static final int GL_ALL_ATTRIB_BITS = 0x000FFFFF;
    public static final int GL_MATRIX_MODE = 0x0BA0;
    public static final int GL_BLEND = 3042;
    public static final int GL_TEXTURE_2D = 3553;
    public static final int GL_TEXTURE_BINDING_2D = 0x8069;
    public static final int GL_SRC_ALPHA = 770;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 771;
    public static final int GL_QUADS = 7;
    public static final int GL_POLYGON = 9;
    public static final int GL_CURRENT_BIT = 1;
    public static final int GL_ENABLE_BIT = 0x00002000;
    public static final int GL_COLOR_BUFFER_BIT = 0x00004000;
    public static final int GL_TEXTURE_BIT = 0x00040000;
    public static final int GL_LINE_BIT = 0x00000004;
    public static final int GL_LIGHTING_BIT = 0x00000040;
    public static final int GL_DEPTH_BUFFER_BIT = 0x00000100;
    public static final int GL_DEPTH_TEST = 0x0B71;
    public static final int GL_LIGHTING = 0x0B50;
    public static final int GL_LINES = 1;
    public static final int GL_LINE_LOOP = 2;
    public static final int GL_LINE_STRIP = 3;
    public static final int GL_TRIANGLES = 4;
    public static final int GL_TRIANGLE_STRIP = 5;
    public static final int GL_TRIANGLE_FAN = 6;
    public static final int GL_LINE_SMOOTH = 0x0B20;
    public static final int GL_LINE_SMOOTH_HINT = 0x0C52;
    public static final int GL_NICEST = 0x1102;
    public static final int GL_ALPHA = 0x1906;
    public static final int GL_RGBA = 0x1908;
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_UNPACK_ALIGNMENT = 0x0CF5;
    public static final int GL_TEXTURE_ENV = 0x2300;
    public static final int GL_TEXTURE_ENV_MODE = 0x2200;
    public static final int GL_MODULATE = 0x2100;
    public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    public static final int GL_TEXTURE_WRAP_S = 0x2802;
    public static final int GL_TEXTURE_WRAP_T = 0x2803;
    public static final int GL_LINEAR = 0x2601;
    public static final int GL_NEAREST = 0x2600;
    public static final int GL_CLAMP = 0x2900;
    public static final int GL_CLAMP_TO_EDGE = 0x812F;
    public static final int GL_VIEWPORT_BIT = 0x00000800;
    public static final int GL_SCISSOR_BIT = 0x00001000;
    public static final int GL_ALPHA_TEST = 0x0BC0;
    public static final int GL_NEVER = 0x0200;
    public static final int GL_LESS = 0x0201;
    public static final int GL_EQUAL = 0x0202;
    public static final int GL_LEQUAL = 0x0203;
    public static final int GL_GREATER = 0x0204;
    public static final int GL_ALWAYS = 0x0207;
    public static final int GL_VERTEX_ARRAY = 0x8074;
    public static final int GL_COLOR_ARRAY = 0x8076;
    public static final int GL_TEXTURE_COORD_ARRAY = 0x8078;
    public static final int GL_CULL_FACE = 0x0B44;
    public static final int GL_POLYGON_SMOOTH = 0x0B41;
    public static final int GL_COLOR_LOGIC_OP = 0x0BF2;
    public static final int GL_SMOOTH = 0x1D01;
    public static final int GL_FLAT = 0x1D00;
    public static final int GL_SCISSOR_TEST = 0x0C11;
    public static final int GL_PROJECTION = 0x1701;
    public static final int GL_MODELVIEW = 0x1700;
    public static void glShadeModel(int mode) {}
    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {}
    public static void glDepthMask(boolean flag) {}
    public static void glClear(int mask) {}
    public static void glClearColor(float red, float green, float blue, float alpha) {}
    public static void glClearDepth(double depth) {}
    public static void glDepthFunc(int func) {}
    public static void glAlphaFunc(int func, float ref) {}
    public static void glPushAttrib(int mask) {}
    public static void glPopAttrib() {}
    public static void glPushMatrix() {}
    public static void glPopMatrix() {}
    public static void glLoadIdentity() {}
    public static void glMatrixMode(int mode) {}
    public static void glFrustum(double left, double right, double bottom, double top,
            double near, double far) {}
    public static void glRotatef(float angle, float x, float y, float z) {}
    public static void glTranslatef(float x, float y, float z) {}
    public static void glScalef(float x, float y, float z) {}
    public static void glScissor(int x, int y, int width, int height) {}
    public static void glViewport(int x, int y, int width, int height) {}
    public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset,
            int x, int y, int width, int height) {}
    public static void glTexParameterf(int target, int pname, float param) {}
    public static void glDeleteTextures(int texture) {}
    public static void glEnable(int c) {}
    public static void glDisable(int c) {}
    public static void glDisableClientState(int c) {}
    public static void glBlendFunc(int a, int b) {}
    public static void glColor4f(float r, float g, float b, float a) {}
    public static void glBegin(int m) {}
    public static void glVertex2f(float x, float y) {}
    public static void glVertex3f(float x, float y, float z) {}
    public static void glVertex3d(double x, double y, double z) {}
    public static void glTexCoord2f(float u, float v) {}
    public static int glGenTextures() { return 0; }
    public static void glBindTexture(int target, int texture) {}
    public static void glTexParameteri(int target, int pname, int param) {}
    public static void glTexEnvi(int target, int pname, int param) {}
    public static int glGetInteger(int pname) { return 4; }
    public static int glGetError() { return 0; }
    public static void glPixelStorei(int pname, int param) {}
    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height,
            int border, int format, int type, java.nio.ByteBuffer pixels) {}
    public static void glLineWidth(float width) {}
    public static void glHint(int target, int mode) {}
    public static void glEnd() {}
}
