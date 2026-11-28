package org.lwjgl.opengl;
import java.util.HashSet;
import java.util.Set;
public final class GL20 {
    public static int current = 73, stage, next = 2000;
    public static boolean failCompile;
    public static final Set<Integer> programs = new HashSet<Integer>();
    public static final Set<Integer> shaders = new HashSet<Integer>();
    public static int glCreateShader(int type) { int id = next++; shaders.add(id); return id; }
    public static void glShaderSource(int id, CharSequence source) {}
    public static void glCompileShader(int id) {}
    public static int glGetShaderi(int id, int key) { return failCompile ? 0 : 1; }
    public static String glGetShaderInfoLog(int id, int max) { return "simulated shader failure"; }
    public static void glDeleteShader(int id) { shaders.remove(id); }
    public static int glCreateProgram() { int id = next++; programs.add(id); return id; }
    public static void glAttachShader(int program, int shader) {}
    public static void glLinkProgram(int id) {}
    public static int glGetProgrami(int id, int key) { return 1; }
    public static String glGetProgramInfoLog(int id, int max) { return ""; }
    public static void glUseProgram(int id) { current = id; }
    public static void glDeleteProgram(int id) { programs.remove(id); }
    public static int glGetUniformLocation(int program, CharSequence name) { return "uStage".contentEquals(name) ? 1 : 2; }
    public static void glUniform1i(int id, int value) { if (id == 1) stage = value; }
    public static void glUniform1f(int id, float value) {}
    public static void glUniform2f(int id, float x, float y) {}
    public static void glUniform4f(int id, float x, float y, float z, float w) {}
}
