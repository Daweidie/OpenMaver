import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.EXTFramebufferBlit;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/** Full-resolution model silhouette; the world's color/depth buffers are never used as a mask. */
final class PlayerEspOutline {
    private static final int DEPTH_COMPONENT24 = 0x81A6;
    private static final int CLAMP_TO_EDGE = 0x812F;
    private static final String VERTEX_SHADER =
            "#version 120\n"
            + "void main() {\n"
            + "    gl_Position = gl_Vertex;\n"
            + "    gl_TexCoord[0] = gl_MultiTexCoord0;\n"
            + "}\n";
    // A separable max filter uses two fixed nine-tap scans. Red carries
    // the undilated mask through the horizontal pass, so the composite can remove
    // the entire model interior without another sampler or a third fullscreen pass.
    private static final String FRAGMENT_SHADER =
            "#version 120\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uStep;\n"
            + "uniform float uRadius;\n"
            + "uniform int uStage;\n"
            + "uniform vec4 uColor;\n"
            + "void main() {\n"
            + "    vec2 uv = gl_TexCoord[0].st;\n"
            + "    vec4 center = texture2D(uTex, uv);\n"
            + "    float expanded = 0.0;\n"
            + "    for (int i = -4; i <= 4; i++) {\n"
            + "        float a = texture2D(uTex, uv + uStep * float(i)).a;\n"
            + "        if (uStage == 0) a = step(0.1, a);\n"
            + "        float weight = clamp(uRadius + 1.0 - abs(float(i)), 0.0, 1.0);\n"
            + "        expanded = max(expanded, a * weight);\n"
            + "    }\n"
            + "    if (uStage == 0) gl_FragColor = vec4(step(0.1, center.a), 0.0, 0.0, expanded);\n"
            + "    else gl_FragColor = vec4(uColor.rgb, max(0.0, expanded - center.r) * uColor.a);\n"
            + "}\n";

    private static int program, texLocation, stepLocation, radiusLocation, stageLocation, colorLocation;
    private static int maskTexture, expandedTexture, maskFramebuffer, expandedFramebuffer, depthBuffer;
    private static int targetWidth, targetHeight;
    private static boolean rendering, failed;

    private PlayerEspOutline() {}

    static boolean isRendering() { return rendering; }

    /** False lets the caller retain the box renderer on unsupported drivers. */
    static boolean render(int width, int height, float thickness, float[] rgb, float opacity,
            Runnable models) {
        if (failed || rendering || width <= 0 || height <= 0) return false;
        rendering = true;
        try (ModelRenderState ignored = new ModelRenderState()) {
            int framebuffer = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
            int renderbuffer = GL11.glGetInteger(EXTFramebufferObject.GL_RENDERBUFFER_BINDING_EXT);
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            boolean split = hasSeparateFramebufferBindings();
            int readFramebuffer = split
                    ? GL11.glGetInteger(EXTFramebufferBlit.GL_READ_FRAMEBUFFER_BINDING_EXT) : framebuffer;
            try {
                ensureResources(width, height);
                EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                        maskFramebuffer);
                GL11.glViewport(0, 0, width, height);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL20.glUseProgram(0);
                GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
                GlStateManager.enableTexture2D();
                GlStateManager.enableDepth();
                GlStateManager.depthFunc(GL11.GL_LEQUAL);
                GlStateManager.depthMask(true);
                GlStateManager.colorMask(true, true, true, true);
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
                GL11.glClearColor(0, 0, 0, 0);
                GL11.glClearDepth(1.0D);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                NameTagFeature.beginSkinPass();
                try {
                    models.run();
                } finally {
                    NameTagFeature.endSkinPass();
                }

                // No Minecraft renderer runs after these raw GL changes. The enclosing
                // ModelRenderState restores both the driver and GlStateManager's mirrors.
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glColorMask(true, true, true, true);
                GL11.glViewport(0, 0, width, height);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL20.glUseProgram(program);
                GL20.glUniform1i(texLocation, 0);
                GL20.glUniform1f(radiusLocation, Math.max(0.5F, Math.min(4.0F, thickness)));
                GL20.glUniform4f(colorLocation, rgb[0], rgb[1], rgb[2], opacity);

                EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                        expandedFramebuffer);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTexture);
                GL20.glUniform1i(stageLocation, 0);
                GL20.glUniform2f(stepLocation, 1.0F / width, 0.0F);
                quad();

                EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                        framebuffer);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, expandedTexture);
                GL20.glUniform1i(stageLocation, 1);
                GL20.glUniform2f(stepLocation, 0.0F, 1.0F / height);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                quad();
                return true;
            } finally {
                EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
                if (split) EXTFramebufferObject.glBindFramebufferEXT(
                        EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, readFramebuffer);
                EXTFramebufferObject.glBindRenderbufferEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, renderbuffer);
                GL20.glUseProgram(previousProgram);
            }
        } catch (Throwable error) {
            failed = true;
            destroyResources();
            System.err.println("[OpenMaver] Player ESP Outline using Box fallback: " + error);
            return false;
        } finally {
            rendering = false;
        }
    }

    private static boolean hasSeparateFramebufferBindings() {
        try {
            Object capabilities = Class.forName("org.lwjgl.opengl.GLContext")
                    .getMethod("getCapabilities").invoke(null);
            return capabilities.getClass().getField("GL_EXT_framebuffer_blit").getBoolean(capabilities);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void ensureResources(int width, int height) {
        if (program == 0) {
            int vertex = 0, fragment = 0;
            try {
                vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
                fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
                program = GL20.glCreateProgram();
                if (program == 0) throw new IllegalStateException("Outline program allocation failed");
                GL20.glAttachShader(program, vertex);
                GL20.glAttachShader(program, fragment);
                GL20.glLinkProgram(program);
                if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                    throw new IllegalStateException(GL20.glGetProgramInfoLog(program, 2048));
                }
                texLocation = GL20.glGetUniformLocation(program, "uTex");
                stepLocation = GL20.glGetUniformLocation(program, "uStep");
                radiusLocation = GL20.glGetUniformLocation(program, "uRadius");
                stageLocation = GL20.glGetUniformLocation(program, "uStage");
                colorLocation = GL20.glGetUniformLocation(program, "uColor");
            } finally {
                if (vertex != 0) GL20.glDeleteShader(vertex);
                if (fragment != 0) GL20.glDeleteShader(fragment);
            }
        }
        if (maskFramebuffer != 0 && width == targetWidth && height == targetHeight) return;
        destroyTargets();
        maskTexture = texture(width, height);
        expandedTexture = texture(width, height);
        depthBuffer = EXTFramebufferObject.glGenRenderbuffersEXT();
        if (depthBuffer == 0) throw new IllegalStateException("Outline depth allocation failed");
        EXTFramebufferObject.glBindRenderbufferEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, depthBuffer);
        EXTFramebufferObject.glRenderbufferStorageEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT,
                DEPTH_COMPONENT24, width, height);
        maskFramebuffer = framebuffer(maskTexture, depthBuffer);
        expandedFramebuffer = framebuffer(expandedTexture, 0);
        targetWidth = width;
        targetHeight = height;
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException("Outline shader allocation failed");
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader, 2048);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(log);
        }
        return shader;
    }

    private static int texture(int width, int height) {
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = GL11.glGenTextures();
        if (texture == 0) throw new IllegalStateException("Outline texture allocation failed");
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height,
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            return texture;
        } catch (RuntimeException error) {
            GL11.glDeleteTextures(texture);
            throw error;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
    }

    private static int framebuffer(int texture, int depth) {
        int framebuffer = EXTFramebufferObject.glGenFramebuffersEXT();
        if (framebuffer == 0) throw new IllegalStateException("Outline framebuffer allocation failed");
        EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
        EXTFramebufferObject.glFramebufferTexture2DEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.GL_TEXTURE_2D, texture, 0);
        if (depth != 0) EXTFramebufferObject.glFramebufferRenderbufferEXT(
                EXTFramebufferObject.GL_FRAMEBUFFER_EXT, EXTFramebufferObject.GL_DEPTH_ATTACHMENT_EXT,
                EXTFramebufferObject.GL_RENDERBUFFER_EXT, depth);
        if (EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT)
                != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) {
            EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer);
            throw new IllegalStateException("Incomplete outline framebuffer");
        }
        return framebuffer;
    }

    private static void quad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, -1);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, -1);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(-1, 1);
        GL11.glEnd();
    }

    private static void destroyTargets() {
        if (maskFramebuffer != 0) EXTFramebufferObject.glDeleteFramebuffersEXT(maskFramebuffer);
        if (expandedFramebuffer != 0) EXTFramebufferObject.glDeleteFramebuffersEXT(expandedFramebuffer);
        if (depthBuffer != 0) EXTFramebufferObject.glDeleteRenderbuffersEXT(depthBuffer);
        if (maskTexture != 0) GL11.glDeleteTextures(maskTexture);
        if (expandedTexture != 0) GL11.glDeleteTextures(expandedTexture);
        maskFramebuffer = expandedFramebuffer = depthBuffer = maskTexture = expandedTexture = 0;
        targetWidth = targetHeight = 0;
    }

    private static void destroyResources() {
        try {
            destroyTargets();
            if (program != 0) GL20.glDeleteProgram(program);
            program = 0;
        } catch (Throwable ignored) {
            // A lost GL context should leave the ordinary box renderer available.
        }
    }
}
