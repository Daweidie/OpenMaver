import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * 游戏内「毛玻璃」背景：抓取当前帧缓冲，缩小后做两遍可分离高斯模糊，
 * 再把模糊后的画面贴回 GUI 的指定区域。
 *
 * <p>HUD 提示卡与 ClickGUI 共用同一份快照，因此每帧最多只抓一次屏。
 * 所有绘制都直接把顶点写到 NDC（{-1,1}），不依赖调用方当前使用的
 * {@code GL_PROJECTION}，在 HUD 的缩放坐标和 GuiScreen 坐标下都能工作。
 *
 * <p>如果显卡/驱动不支持 GLSL 或 EXT_framebuffer_object，会静默回退成
 * 一块半透明纯色底板，UI 仍然可读，不会影响游戏。
 */
public final class GuiBlur {

    /** 模糊缓冲的降采样倍数；越大越省性能，也越柔和。 */
    private static final int DOWNSCALE = 4;
    /** 每个模糊 pass 的采样半径（以模糊缓冲 texel 计）。 */
    private static final float BLUR_RADIUS = 2.6F;
    /** 单次横纵模糊后再叠加一轮，让毛玻璃更厚、更明显。 */
    private static final int BLUR_ITERATIONS = 2;

    private static final String VERTEX_SHADER =
            "#version 120\n"
            + "void main() {\n"
            + "    gl_Position = gl_Vertex;\n"
            + "    gl_TexCoord[0] = gl_MultiTexCoord0;\n"
            + "}\n";

    private static final String BLUR_SHADER =
            "#version 120\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uDir;\n"
            + "void main() {\n"
            + "    vec2 uv = gl_TexCoord[0].st;\n"
            + "    vec4 sum = texture2D(uTex, uv) * 0.2270270270;\n"
            + "    sum += texture2D(uTex, uv + uDir * 1.0) * 0.1945945946;\n"
            + "    sum += texture2D(uTex, uv - uDir * 1.0) * 0.1945945946;\n"
            + "    sum += texture2D(uTex, uv + uDir * 2.0) * 0.1216216216;\n"
            + "    sum += texture2D(uTex, uv - uDir * 2.0) * 0.1216216216;\n"
            + "    sum += texture2D(uTex, uv + uDir * 3.0) * 0.0540540541;\n"
            + "    sum += texture2D(uTex, uv - uDir * 3.0) * 0.0540540541;\n"
            + "    sum += texture2D(uTex, uv + uDir * 4.0) * 0.0162162162;\n"
            + "    sum += texture2D(uTex, uv - uDir * 4.0) * 0.0162162162;\n"
            + "    gl_FragColor = sum;\n"
            + "}\n";

    private static final String DRAW_SHADER =
            "#version 120\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec4 uTint;\n"
            + "uniform float uAlpha;\n"
            + "uniform vec2 uHalf;\n"
            + "uniform float uRadius;\n"
            + "uniform vec2 uUvMin;\n"
            + "uniform vec2 uUvSize;\n"
            + "void main() {\n"
            + "    vec2 uv = gl_TexCoord[0].st;\n"
            + "    vec4 sampled = texture2D(uTex, uUvMin + uv * uUvSize);\n"
            + "    vec3 color = mix(sampled.rgb, uTint.rgb, uTint.a);\n"
            + "    vec2 p = vec2(uv.x, 1.0 - uv.y) * uHalf * 2.0 - uHalf;\n"
            + "    vec2 q = abs(p) - uHalf + uRadius;\n"
            + "    float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;\n"
            + "    float mask = 1.0 - smoothstep(-1.0, 0.5, dist);\n"
            + "    gl_FragColor = vec4(color, uAlpha * mask);\n"
            + "}\n";

    private static boolean disabled;
    private static boolean programsReady;
    private static int blurProgram = -1;
    private static int drawProgram = -1;

    private static int blurTexLocation;
    private static int blurDirLocation;
    private static int drawTexLocation;
    private static int drawTintLocation;
    private static int drawAlphaLocation;
    private static int drawHalfLocation;
    private static int drawRadiusLocation;
    private static int drawUvMinLocation;
    private static int drawUvSizeLocation;

    private static int sourceTexture;
    private static int sourceWidth;
    private static int sourceHeight;

    private static int blurTextureA;
    private static int blurTextureB;
    private static int blurFramebufferA;
    private static int blurFramebufferB;
    private static int blurWidth;
    private static int blurHeight;
    private static boolean targetsReady;

    private static int screenWidth = 1;
    private static int screenHeight = 1;
    private static boolean captured;

    private GuiBlur() {
    }

    /**
     * 每帧开始时调用，记录当前 GUI 逻辑分辨率并允许重新抓屏。
     *
     * @param guiWidth     当前 GUI 宽度（ScaledResolution 单位）
     * @param guiHeight    当前 GUI 高度（ScaledResolution 单位）
     * @param scaleFactor  GUI 缩放倍数（保留给调用方，内部以真实帧缓冲为准）
     */
    public static void beginFrame(int guiWidth, int guiHeight, int scaleFactor) {
        screenWidth = Math.max(1, guiWidth);
        screenHeight = Math.max(1, guiHeight);
        captured = false;
    }

    /**
     * 立刻抓取当前帧缓冲并完成模糊。HUD 在绘制自己的列表前调用，
     * 避免把模块列表也糊进提示卡的背景里。
     */
    public static void capture() {
        if (disabled || captured) {
            return;
        }
        captured = true;
        try {
            if (!ensureResources()) {
                return;
            }
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL20.glUseProgram(0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(sourceTexture);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                    sourceWidth, sourceHeight);
            GlStateManager.bindTexture(0);
            GL13.glActiveTexture(previousActiveTexture);
            GL20.glUseProgram(previousProgram);
            runBlurPasses();
        } catch (Throwable t) {
            disabled = true;
            System.err.println("[OpenMaver] 背景模糊不可用，已回退纯色: " + t);
        }
    }

    /**
     * 把模糊后的背景画到 GUI 坐标中的一个矩形里。
     *
     * @param x            左上角 X（GUI 单位）
     * @param y            左上角 Y（GUI 单位）
     * @param w            宽度
     * @param h            高度
     * @param radius       圆角半径
     * @param tintRgb      叠加色调（0xRRGGBB）
     * @param tintStrength 色调占比 0..1
     * @param alpha        整体不透明度 0..1
     */
    public static void draw(int x, int y, int w, int h, float radius,
            int tintRgb, float tintStrength, float alpha) {
        draw(x, y, w, h, radius, tintRgb, tintStrength, alpha, false);
    }

    /**
     * 与 {@link #draw} 相同，但把采样到的整张背景水平镜像。
     *
     * <p>原版主菜单先把全景图渲染到 FBO，再在最终绘制时把 U 轴反过来，
     * 用来抵消立方体内部视角产生的镜像。Maver 直接绘制全景图再模糊，
     * 因此主菜单必须显式做同样的水平翻转，否则背景会和游戏内视角左右相反。
     */
    public static void drawMirrored(int x, int y, int w, int h, float radius,
            int tintRgb, float tintStrength, float alpha) {
        draw(x, y, w, h, radius, tintRgb, tintStrength, alpha, true);
    }

    private static void draw(int x, int y, int w, int h, float radius,
            int tintRgb, float tintStrength, float alpha, boolean mirrorX) {
        if (w <= 0 || h <= 0 || alpha <= 0.001F) {
            return;
        }
        if (disabled) {
            drawFallback(x, y, w, h, tintRgb, tintStrength, alpha);
            return;
        }
        capture();
        if (disabled || !targetsReady) {
            drawFallback(x, y, w, h, tintRgb, tintStrength, alpha);
            return;
        }

        float left = x / (float) screenWidth * 2.0F - 1.0F;
        float right = (x + w) / (float) screenWidth * 2.0F - 1.0F;
        float top = 1.0F - y / (float) screenHeight * 2.0F;
        float bottom = 1.0F - (y + h) / (float) screenHeight * 2.0F;

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_SCISSOR_BIT
                | GL11.GL_TEXTURE_BIT);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(blurTextureB);
            GL20.glUseProgram(drawProgram);
            GL20.glUniform1i(drawTexLocation, 0);
            GL20.glUniform4f(drawTintLocation,
                    (tintRgb >> 16 & 0xFF) / 255.0F,
                    (tintRgb >> 8 & 0xFF) / 255.0F,
                    (tintRgb & 0xFF) / 255.0F,
                    clamp01(tintStrength));
            GL20.glUniform1f(drawAlphaLocation, clamp01(alpha));
            GL20.glUniform2f(drawHalfLocation, w / 2.0F, h / 2.0F);
            GL20.glUniform1f(drawRadiusLocation, Math.max(0.0F, radius));
            // Sample only the part of the screen that sits behind this rectangle.
            // Using 0..1 here would squeeze the whole framebuffer into every card.
            float u0 = x / (float) screenWidth;
            float u1 = (x + w) / (float) screenWidth;
            float vTop = 1.0F - y / (float) screenHeight;
            float vBottom = 1.0F - (y + h) / (float) screenHeight;
            if (mirrorX) {
                // Negative U span samples the same rectangle right-to-left. The rounded-corner
                // mask still uses the original UVs, so only the background content is mirrored.
                GL20.glUniform2f(drawUvMinLocation, u1, vBottom);
                GL20.glUniform2f(drawUvSizeLocation, u0 - u1, vTop - vBottom);
            } else {
                GL20.glUniform2f(drawUvMinLocation, u0, vBottom);
                GL20.glUniform2f(drawUvSizeLocation, u1 - u0, vTop - vBottom);
            }

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0.0F, 1.0F);
            GL11.glVertex2f(left, top);
            GL11.glTexCoord2f(1.0F, 1.0F);
            GL11.glVertex2f(right, top);
            GL11.glTexCoord2f(1.0F, 0.0F);
            GL11.glVertex2f(right, bottom);
            GL11.glTexCoord2f(0.0F, 0.0F);
            GL11.glVertex2f(left, bottom);
            GL11.glEnd();
        } finally {
            GlStateManager.bindTexture(0);
            GL13.glActiveTexture(previousActiveTexture);
            GL20.glUseProgram(previousProgram);
            GL11.glPopAttrib();
            GlColor.restore();
        }
    }

    // ==================================================================
    // 资源
    // ==================================================================

    private static boolean ensureResources() {
        if (!programsReady && !createPrograms()) {
            disabled = true;
            return false;
        }
        int framebufferWidth = Math.max(1, Display.getWidth());
        int framebufferHeight = Math.max(1, Display.getHeight());
        if (!targetsReady || sourceWidth != framebufferWidth
                || sourceHeight != framebufferHeight) {
            createTargets(framebufferWidth, framebufferHeight);
        }
        if (!targetsReady) {
            disabled = true;
        }
        return targetsReady;
    }

    private static boolean createPrograms() {
        int vertex = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int blurFragment = compileShader(GL20.GL_FRAGMENT_SHADER, BLUR_SHADER);
        int drawFragment = compileShader(GL20.GL_FRAGMENT_SHADER, DRAW_SHADER);
        if (vertex == 0 || blurFragment == 0 || drawFragment == 0) {
            deleteShader(vertex);
            deleteShader(blurFragment);
            deleteShader(drawFragment);
            return false;
        }
        blurProgram = linkProgram(vertex, blurFragment);
        drawProgram = linkProgram(vertex, drawFragment);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(blurFragment);
        GL20.glDeleteShader(drawFragment);
        if (blurProgram == 0 || drawProgram == 0) {
            return false;
        }
        blurTexLocation = GL20.glGetUniformLocation(blurProgram, "uTex");
        blurDirLocation = GL20.glGetUniformLocation(blurProgram, "uDir");
        drawTexLocation = GL20.glGetUniformLocation(drawProgram, "uTex");
        drawTintLocation = GL20.glGetUniformLocation(drawProgram, "uTint");
        drawAlphaLocation = GL20.glGetUniformLocation(drawProgram, "uAlpha");
        drawHalfLocation = GL20.glGetUniformLocation(drawProgram, "uHalf");
        drawRadiusLocation = GL20.glGetUniformLocation(drawProgram, "uRadius");
        drawUvMinLocation = GL20.glGetUniformLocation(drawProgram, "uUvMin");
        drawUvSizeLocation = GL20.glGetUniformLocation(drawProgram, "uUvSize");
        programsReady = true;
        return true;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        if (shader == 0) {
            return 0;
        }
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            System.err.println("[OpenMaver] 模糊着色器编译失败: "
                    + GL20.glGetShaderInfoLog(shader, 2048));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static int linkProgram(int vertex, int fragment) {
        int program = GL20.glCreateProgram();
        if (program == 0) {
            return 0;
        }
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            System.err.println("[OpenMaver] 模糊着色器链接失败: "
                    + GL20.glGetProgramInfoLog(program, 2048));
            GL20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static void deleteShader(int shader) {
        if (shader != 0) {
            GL20.glDeleteShader(shader);
        }
    }

    private static void createTargets(int framebufferWidth, int framebufferHeight) {
        destroyTargets();
        sourceWidth = framebufferWidth;
        sourceHeight = framebufferHeight;

        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        sourceTexture = createTexture(framebufferWidth, framebufferHeight);

        blurWidth = Math.max(1, framebufferWidth / DOWNSCALE);
        blurHeight = Math.max(1, framebufferHeight / DOWNSCALE);
        blurTextureA = createTexture(blurWidth, blurHeight);
        blurTextureB = createTexture(blurWidth, blurHeight);
        GlStateManager.bindTexture(0);
        GL13.glActiveTexture(previousActiveTexture);

        blurFramebufferA = createFramebuffer(blurTextureA);
        blurFramebufferB = createFramebuffer(blurTextureB);
        targetsReady = blurFramebufferA != 0 && blurFramebufferB != 0;
    }

    private static int createTexture(int width, int height) {
        int texture = GL11.glGenTextures();
        GlStateManager.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                GL11.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                GL11.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height,
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
        return texture;
    }

    private static int createFramebuffer(int texture) {
        int framebuffer = EXTFramebufferObject.glGenFramebuffersEXT();
        EXTFramebufferObject.glBindFramebufferEXT(
                EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer);
        EXTFramebufferObject.glFramebufferTexture2DEXT(
                EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT,
                GL11.GL_TEXTURE_2D, texture, 0);
        int status = EXTFramebufferObject.glCheckFramebufferStatusEXT(
                EXTFramebufferObject.GL_FRAMEBUFFER_EXT);
        EXTFramebufferObject.glBindFramebufferEXT(
                EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0);
        if (status != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) {
            System.err.println("[OpenMaver] 模糊帧缓冲不完整: 0x"
                    + Integer.toHexString(status));
            EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer);
            return 0;
        }
        return framebuffer;
    }

    private static void destroyTargets() {
        targetsReady = false;
        if (sourceTexture != 0) {
            GL11.glDeleteTextures(sourceTexture);
            sourceTexture = 0;
        }
        if (blurTextureA != 0) {
            GL11.glDeleteTextures(blurTextureA);
            blurTextureA = 0;
        }
        if (blurTextureB != 0) {
            GL11.glDeleteTextures(blurTextureB);
            blurTextureB = 0;
        }
        if (blurFramebufferA != 0) {
            EXTFramebufferObject.glDeleteFramebuffersEXT(blurFramebufferA);
            blurFramebufferA = 0;
        }
        if (blurFramebufferB != 0) {
            EXTFramebufferObject.glDeleteFramebuffersEXT(blurFramebufferB);
            blurFramebufferB = 0;
        }
    }

    // ==================================================================
    // 模糊 pass
    // ==================================================================

    private static void runBlurPasses() {
        if (!targetsReady) {
            return;
        }
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousFramebuffer = GL11.glGetInteger(
                EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_VIEWPORT_BIT
                | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_SCISSOR_BIT | GL11.GL_TEXTURE_BIT);
        try {
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glViewport(0, 0, blurWidth, blurHeight);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUseProgram(blurProgram);
            GL20.glUniform1i(blurTexLocation, 0);

            for (int iteration = 0; iteration < BLUR_ITERATIONS; iteration++) {
                // 横向：首轮取全分辨率快照，之后在两张低分辨率缓冲之间往复。
                EXTFramebufferObject.glBindFramebufferEXT(
                        EXTFramebufferObject.GL_FRAMEBUFFER_EXT, blurFramebufferA);
                GlStateManager.bindTexture(iteration == 0 ? sourceTexture : blurTextureB);
                GL20.glUniform2f(blurDirLocation, BLUR_RADIUS / blurWidth, 0.0F);
                drawFullscreenQuad();

                // 纵向：A -> B
                EXTFramebufferObject.glBindFramebufferEXT(
                        EXTFramebufferObject.GL_FRAMEBUFFER_EXT, blurFramebufferB);
                GlStateManager.bindTexture(blurTextureA);
                GL20.glUniform2f(blurDirLocation, 0.0F, BLUR_RADIUS / blurHeight);
                drawFullscreenQuad();
            }
        } finally {
            EXTFramebufferObject.glBindFramebufferEXT(
                    EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GlStateManager.bindTexture(0);
            GL13.glActiveTexture(previousActiveTexture);
            GL20.glUseProgram(previousProgram);
            GL11.glPopAttrib();
        }
    }

    private static void drawFullscreenQuad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0F, 0.0F);
        GL11.glVertex2f(-1.0F, -1.0F);
        GL11.glTexCoord2f(1.0F, 0.0F);
        GL11.glVertex2f(1.0F, -1.0F);
        GL11.glTexCoord2f(1.0F, 1.0F);
        GL11.glVertex2f(1.0F, 1.0F);
        GL11.glTexCoord2f(0.0F, 1.0F);
        GL11.glVertex2f(-1.0F, 1.0F);
        GL11.glEnd();
    }

    // ==================================================================
    // 兜底
    // ==================================================================

    private static void drawFallback(int x, int y, int w, int h, int tintRgb,
            float tintStrength, float alpha) {
        float left = x / (float) screenWidth * 2.0F - 1.0F;
        float right = (x + w) / (float) screenWidth * 2.0F - 1.0F;
        float top = 1.0F - y / (float) screenHeight * 2.0F;
        float bottom = 1.0F - (y + h) / (float) screenHeight * 2.0F;
        int color = (clamp255((int) (alpha * 255.0F)) << 24)
                | (tintRgb & 0x00FFFFFF);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_SCISSOR_BIT);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlColor.set(color);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(left, top);
            GL11.glVertex2f(right, top);
            GL11.glVertex2f(right, bottom);
            GL11.glVertex2f(left, bottom);
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
            GlColor.restore();
        }
    }

    private static float clamp01(float value) {
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }
}
