import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EXTFramebufferBlit;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

/**
 * 方向性运动模糊（Directional / Radial Motion Blur）。
 *
 * <p>实现方式：把当前世界画面整帧拷贝到一张纹理，然后在一个全屏 pass 里
 * <b>沿运动方向多次采样当前帧</b>再叠回主帧缓冲。因为只采样当前帧、不做历史帧
 * 累积，所以不会出现“残影 / 双影 / 晕糊”那种拖泥带水的观感。
 *
 * <p>拖影向量由两类运动合成：
 * <ul>
 *   <li>相机转动（yaw / pitch 角速度）：一小部分作为全屏统一位移，大部分作为
 *       从屏幕中心向外的径向拖影，越靠近边缘拖得越长，和真实转头的透视运动一致；</li>
 *   <li>水平移动（左右平移 + 前后移动）：平移产生统一方向的拖影，前后移动产生
 *       径向（zoom）拖影。</li>
 * </ul>
 * 角速度按时间常数做指数平滑，避免 20 TPS 下视角只在 tick 跳变导致的闪烁。
 *
 * <p>静止时整条管线直接跳过：不抓帧、不跑 shader。只在 {@code RenderWorldLastEvent}
 * 之后执行，因此不会模糊 HUD / GUI。
 *
 * <p>兼容性：优先使用 {@code EXT_framebuffer_blit} 直接把主帧缓冲拷贝到纹理；
 * 如果驱动/环境不支持，则回退到 {@code glCopyTexSubImage2D}。两者都不可用时
 * 静默停用，不影响游戏。
 */
public final class MotionBlur {

    /** 方向采样次数。次数越多拖影越顺滑，开销也越高。 */
    private static final int SAMPLES = 12;
    /** 每帧角速度对应的屏幕 UV 位移（约 70° FOV 下的中心位移）。 */
    private static final float UV_PER_DEGREE = 0.0070F;
    /** 转动中作为全屏统一位移的比例，其余走径向拖影。 */
    private static final float ROTATION_UNIFORM_SHARE = 0.45F;
    /** 转动对应的径向拖影系数（越大边缘拖得越长）。 */
    private static final float ROTATION_RADIAL_SHARE = 0.85F;
    /** 每次 tick 的水平速度（格/tick）对应的统一方向 UV 位移。 */
    private static final float TRANSLATION_UV_PER_BLOCK = 0.11F;
    /** 前后移动速度对应的 zoom 拖影系数。 */
    private static final float FORWARD_ZOOM_PER_BLOCK = 0.16F;
    /** 统一方向拖影的最大长度（UV）。 */
    private static final float MAX_DIRECTION = 0.090F;
    /** 径向拖影的最大系数。 */
    private static final float MAX_ZOOM = 0.130F;
    /** 角速度平滑时间常数（秒），让 tick 级跳变变连续。 */
    private static final float VELOCITY_SMOOTHING = 0.05F;
    /** 低于该运动量完全不出图（防鼠标微抖）。 */
    private static final float MIN_MOTION = 0.002F;
    /** 达到该运动量时模糊叠加强度达到最大。 */
    private static final float FULL_MOTION = 0.022F;

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
            + "uniform float uZoom;\n"
            + "uniform float uAlpha;\n"
            + "void main() {\n"
            + "    vec2 uv = gl_TexCoord[0].st;\n"
            + "    vec2 delta = uDir + (uv - 0.5) * uZoom;\n"
            // 每像素固定抖动：打散少量采样产生的条带，同时不引入帧间噪点。
            + "    float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);\n"
            + "    vec3 sum = vec3(0.0);\n"
            + "    for (int i = 0; i < " + SAMPLES + "; i++) {\n"
            + "        float t = (float(i) + jitter) / " + (SAMPLES - 1) + ".0 - 0.5;\n"
            + "        vec2 sampleUv = clamp(uv + delta * t, vec2(0.0), vec2(1.0));\n"
            + "        sum += texture2D(uTex, sampleUv).rgb;\n"
            + "    }\n"
            + "    gl_FragColor = vec4(sum / " + SAMPLES + ".0, clamp(uAlpha, 0.0, 1.0));\n"
            + "}\n";

    private static boolean disabled;
    private static boolean programsReady;
    private static int blurProgram = -1;

    private static int blurTextureLocation;
    private static int blurDirectionLocation;
    private static int blurZoomLocation;
    private static int blurAlphaLocation;

    private static int sourceWidth;
    private static int sourceHeight;

    /** 当前帧快照（全分辨率，避免拖影糊成一团）。 */
    private static int sourceTexture;
    private static int sourceFramebuffer;

    private static boolean targetsReady;
    private static boolean blitAvailable = true;
    /** 首次 blit 后检查一次 GL 错误，避免驱动静默失败时一直采样空纹理。 */
    private static boolean blitVerified;

    /** 平滑后的角速度（度/秒）。 */
    private static float yawVelocity;
    private static float pitchVelocity;

    private static float lastYaw = Float.NaN;
    private static float lastPitch = Float.NaN;
    private static long lastFrameNanos;
    private static Object lastWorld;
    /** 注册表只在启动时写一次；缓存模块实例，避免每帧获取锁。 */
    private static Module cachedModule;
    /** 第一次真正叠回画面时打一行日志，方便确认模块已生效。 */
    private static boolean activeLogged;

    private MotionBlur() {
    }

    /**
     * 在世界渲染结束后调用。只有模块启用且玩家确实在运动时才会创建/使用 GL 资源。
     *
     * @param mc Minecraft 客户端实例
     */
    public static void onRenderWorldLast(Minecraft mc) {
        Module module = cachedModule;
        if (module == null) {
            module = ModuleManager.getModule("Motion Blur");
            cachedModule = module;
        }
        if (module == null || !module.isEnabled()) {
            reset();
            return;
        }
        if (disabled || mc == null) {
            return;
        }

        try {
            // 不要直接 mc.thePlayer / mc.theWorld：1.8.9 运行期字段声明类型是
            // EntityPlayerSP / WorldClient，与编译期 stub 的 EntityPlayer / World
            // 描述符不一致，会抛 NoSuchFieldError。项目其它功能统一走反射。
            Object playerObject = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g",
                    EntityPlayer.class);
            EntityPlayer player = playerObject instanceof EntityPlayer
                    ? (EntityPlayer) playerObject : null;
            Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", World.class);
            if (player == null || world == null) {
                reset();
                return;
            }
            if (world != lastWorld) {
                lastWorld = world;
                reset();
            }

            float strength = OpenMaverFeatures.getMotionBlurStrength();
            if (strength <= 0.01F) {
                resetMotion();
                return;
            }

            long now = System.nanoTime();
            float dt = (lastFrameNanos == 0L)
                    ? (1.0F / 60.0F)
                    : (now - lastFrameNanos) / 1_000_000_000.0F;
            lastFrameNanos = now;
            if (dt < 0.001F) {
                dt = 1.0F / 60.0F;
            }
            if (dt > 0.25F) {
                // 暂停 / 切窗口 / 长卡顿后不要把这几秒的视角变化当成拖影。
                resetMotion();
                lastYaw = Float.NaN;
                lastPitch = Float.NaN;
                return;
            }
            if (dt > 0.1F) {
                dt = 0.1F;
            }

            float yaw = player.rotationYaw;
            float pitch = player.rotationPitch;
            if (Float.isNaN(lastYaw)) {
                lastYaw = yaw;
                lastPitch = pitch;
                yawVelocity = 0.0F;
                pitchVelocity = 0.0F;
                return;
            }

            float deltaYaw = wrapDegrees(yaw - lastYaw);
            float deltaPitch = pitch - lastPitch;
            lastYaw = yaw;
            lastPitch = pitch;

            // 20 TPS 下视角只在 tick 时跳变，直接按帧差会一顿一顿；先估角速度再平滑。
            float instantYawVelocity = deltaYaw / dt;
            float instantPitchVelocity = deltaPitch / dt;
            float velocityBlend = 1.0F - (float) Math.exp(-dt / VELOCITY_SMOOTHING);
            yawVelocity += (instantYawVelocity - yawVelocity) * velocityBlend;
            pitchVelocity += (instantPitchVelocity - pitchVelocity) * velocityBlend;

            double motionX = player.motionX;
            double motionZ = player.motionZ;
            double yawRadians = Math.toRadians(yaw);
            // 相机前向 / 右向（yaw 0 面向 +Z，右手边是 -X）。
            float forwardSpeed = (float) (motionX * -Math.sin(yawRadians)
                    + motionZ * Math.cos(yawRadians));
            float strafeSpeed = (float) (motionX * -Math.cos(yawRadians)
                    + motionZ * -Math.sin(yawRadians));

            // 本帧屏幕位移（UV）：转头位移 + 平移位移。
            float uniformX = -yawVelocity * dt * UV_PER_DEGREE * ROTATION_UNIFORM_SHARE
                    - strafeSpeed * TRANSLATION_UV_PER_BLOCK;
            float uniformY = pitchVelocity * dt * UV_PER_DEGREE * ROTATION_UNIFORM_SHARE;
            // 径向分量：转头越靠近屏幕边缘位移越大；前后移动则整体 zoom。
            float turnRadial = (float) Math.sqrt(yawVelocity * yawVelocity
                    + pitchVelocity * pitchVelocity) * dt * UV_PER_DEGREE
                    * ROTATION_RADIAL_SHARE;
            float zoom = forwardSpeed * FORWARD_ZOOM_PER_BLOCK + turnRadial;

            float motion = (float) Math.sqrt(uniformX * uniformX + uniformY * uniformY
                    + 0.25F * zoom * zoom);
            if (motion < MIN_MOTION) {
                return;
            }

            float dirLength = (float) Math.sqrt(uniformX * uniformX + uniformY * uniformY);
            if (dirLength > MAX_DIRECTION) {
                float scale = MAX_DIRECTION / dirLength;
                uniformX *= scale;
                uniformY *= scale;
            }
            if (zoom > MAX_ZOOM) {
                zoom = MAX_ZOOM;
            } else if (zoom < -MAX_ZOOM) {
                zoom = -MAX_ZOOM;
            }

            // STRENGTH 同时影响拖影长度和叠加强度，调低更清爽。
            float scale = 0.45F + 0.80F * strength;
            uniformX *= scale;
            uniformY *= scale;
            zoom *= scale;

            float alpha = 0.95F * strength * smoothstep(MIN_MOTION, FULL_MOTION, motion);

            if (!ensureResources()) {
                return;
            }
            if (!captureCurrentFrame()) {
                return;
            }
            if (alpha > 0.01F) {
                if (!activeLogged) {
                    activeLogged = true;
                    System.out.println("[OpenMaver] Motion Blur active: "
                            + sourceWidth + "x" + sourceHeight
                            + " blit=" + blitAvailable
                            + " strength=" + strength);
                }
                drawBlur(uniformX, uniformY, zoom, alpha);
            }
        } catch (Throwable error) {
            disabled = true;
            System.err.println("[OpenMaver] Motion Blur 已停用: " + error);
        }
    }

    /** 模块关闭 / 切换世界时清理运动状态，但保留已分配的 GL 纹理。 */
    private static void reset() {
        resetMotion();
        lastYaw = Float.NaN;
        lastPitch = Float.NaN;
        lastFrameNanos = 0L;
    }

    private static void resetMotion() {
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
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
        int fragment = compileShader(GL20.GL_FRAGMENT_SHADER, BLUR_SHADER);
        if (vertex == 0 || fragment == 0) {
            deleteShader(vertex);
            deleteShader(fragment);
            return false;
        }
        blurProgram = linkProgram(vertex, fragment);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        if (blurProgram == 0) {
            return false;
        }
        blurTextureLocation = GL20.glGetUniformLocation(blurProgram, "uTex");
        blurDirectionLocation = GL20.glGetUniformLocation(blurProgram, "uDir");
        blurZoomLocation = GL20.glGetUniformLocation(blurProgram, "uZoom");
        blurAlphaLocation = GL20.glGetUniformLocation(blurProgram, "uAlpha");
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
            System.err.println("[OpenMaver] Motion Blur 着色器编译失败: "
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
            System.err.println("[OpenMaver] Motion Blur 着色器链接失败: "
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
        sourceTexture = createTexture(sourceWidth, sourceHeight);
        GlStateManager.bindTexture(0);
        GL13.glActiveTexture(previousActiveTexture);

        sourceFramebuffer = createFramebuffer(sourceTexture, "source");
        targetsReady = sourceFramebuffer != 0;
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

    private static int createFramebuffer(int texture, String label) {
        int previousFramebuffer = GL11.glGetInteger(
                EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
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
                EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
        if (status != EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) {
            System.err.println("[OpenMaver] Motion Blur 帧缓冲不完整 (" + label + "): 0x"
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
        if (sourceFramebuffer != 0) {
            EXTFramebufferObject.glDeleteFramebuffersEXT(sourceFramebuffer);
            sourceFramebuffer = 0;
        }
    }

    // ==================================================================
    // 抓帧 / 模糊
    // ==================================================================

    /**
     * 把当前世界画面拷贝到 {@link #sourceTexture}。
     *
     * <p>优先 blit（GPU 内部拷贝）；失败时退回 {@code glCopyTexSubImage2D}，
     * 并永久记住该选择。
     */
    private static boolean captureCurrentFrame() {
        if (blitAvailable) {
            int previousRead = 0;
            int previousDraw = 0;
            try {
                if (!blitVerified) {
                    // 清掉 MC 之前留下的错误，首次 blit 后再统一检查。
                    while (GL11.glGetError() != 0) {
                        // drain
                    }
                }
                previousRead = GL11.glGetInteger(
                        EXTFramebufferBlit.GL_READ_FRAMEBUFFER_BINDING_EXT);
                previousDraw = GL11.glGetInteger(
                        EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_BINDING_EXT);
                EXTFramebufferObject.glBindFramebufferEXT(
                        EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, previousRead);
                EXTFramebufferObject.glBindFramebufferEXT(
                        EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_EXT, sourceFramebuffer);
                EXTFramebufferBlit.glBlitFramebufferEXT(
                        0, 0, sourceWidth, sourceHeight,
                        0, 0, sourceWidth, sourceHeight,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
                if (!blitVerified) {
                    blitVerified = true;
                    int error = GL11.glGetError();
                    if (error != 0) {
                        throw new IllegalStateException("glBlitFramebufferEXT 失败: 0x"
                                + Integer.toHexString(error));
                    }
                }
                restoreFramebufferBindings(previousRead, previousDraw);
                return true;
            } catch (Throwable error) {
                blitAvailable = false;
                try {
                    restoreFramebufferBindings(previousRead, previousDraw);
                } catch (Throwable ignored) {
                    // 如果 blit 类本身缺失，之前的绑定从未改变；忽略恢复失败。
                }
                System.err.println("[OpenMaver] Motion Blur: framebuffer blit 不可用，"
                        + "改用 glCopyTexSubImage2D: " + error);
            }
        }

        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(sourceTexture);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                sourceWidth, sourceHeight);
        GlStateManager.bindTexture(0);
        GL13.glActiveTexture(previousActiveTexture);
        return true;
    }

    /** 恢复 blit 前的 read / draw 帧缓冲绑定；两者相同时只绑定一次。 */
    private static void restoreFramebufferBindings(int read, int draw) {
        EXTFramebufferObject.glBindFramebufferEXT(
                EXTFramebufferObject.GL_FRAMEBUFFER_EXT, draw);
        if (read != draw) {
            EXTFramebufferObject.glBindFramebufferEXT(
                    EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, read);
        }
    }

    /**
     * 沿运动方向多次采样当前帧，按 alpha 叠回主帧缓冲。
     * 只读 {@link #sourceTexture}（当前帧快照），不读历史帧，因此没有残影。
     */
    private static void drawBlur(float dirX, float dirY, float zoom, float alpha) {
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int previousFramebuffer = GL11.glGetInteger(
                EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_SCISSOR_BIT
                | GL11.GL_TEXTURE_BIT);
        try {
            EXTFramebufferObject.glBindFramebufferEXT(
                    EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(sourceTexture);
            GL20.glUseProgram(blurProgram);
            GL20.glUniform1i(blurTextureLocation, 0);
            GL20.glUniform2f(blurDirectionLocation, dirX, dirY);
            GL20.glUniform1f(blurZoomLocation, zoom);
            GL20.glUniform1f(blurAlphaLocation, clamp01(alpha));
            drawFullscreenQuad();
        } finally {
            GlStateManager.bindTexture(0);
            GL13.glActiveTexture(previousActiveTexture);
            GL20.glUseProgram(previousProgram);
            GL11.glPopAttrib();
            GlColor.restore();
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
    // 数学
    // ==================================================================

    private static float wrapDegrees(float angle) {
        angle = angle % 360.0F;
        if (angle >= 180.0F) {
            angle -= 360.0F;
        } else if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }

    private static float clamp01(float value) {
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = clamp01((value - edge0) / (edge1 - edge0));
        return t * t * (3.0F - 2.0F * t);
    }
}
