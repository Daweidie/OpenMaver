package org.lwjgl.opengl;
public final class GL20 {
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    public static final int GL_VERTEX_SHADER = 0x8B31;
    public static final int GL_COMPILE_STATUS = 0x8B81;
    public static final int GL_LINK_STATUS = 0x8B82;
    public static final int GL_CURRENT_PROGRAM = 0x8B8D;
    public static int glCreateShader(int type) { return 0; }
    public static void glShaderSource(int shader, CharSequence source) {}
    public static void glCompileShader(int shader) {}
    public static int glGetShaderi(int shader, int pname) { return 1; }
    public static String glGetShaderInfoLog(int shader, int maxLength) { return ""; }
    public static void glDeleteShader(int shader) {}
    public static int glCreateProgram() { return 0; }
    public static void glAttachShader(int program, int shader) {}
    public static void glLinkProgram(int program) {}
    public static int glGetProgrami(int program, int pname) { return 1; }
    public static String glGetProgramInfoLog(int program, int maxLength) { return ""; }
    public static void glUseProgram(int program) {}
    public static void glDeleteProgram(int program) {}
    public static int glGetUniformLocation(int program, CharSequence name) { return -1; }
    public static void glUniform1i(int location, int value) {}
    public static void glUniform1f(int location, float value) {}
    public static void glUniform2f(int location, float x, float y) {}
    public static void glUniform4f(int location, float x, float y, float z, float w) {}
}
