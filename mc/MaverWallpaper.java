import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.opengl.GL11;

import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.NodeList;

/**
 * 主菜单壁纸：原版全景图、静态图片、动态 GIF 与视频（MP4 / MOV / WebM / MKV）。
 *
 * <p>壁纸文件统一放在 {@code config/openmaver/wallpapers/}，支持
 * PNG / JPG / BMP / GIF / MP4 / M4V / MOV / WEBM / MKV。
 * 静态模式只显示第一帧（视频也一样，只解首帧）；动态模式下 GIF 会循环播放，
 * 视频走 {@link MaverVideo} 的 ffmpeg 流式解码循环播放，普通图片则使用缓慢的
 * Ken Burns 平移 / 缩放，没有选择文件时始终使用原版全景图。
 *
 * <p>图片解码与缩放全部在后台 daemon 线程完成，渲染线程只负责上传纹理和绘制，
 * 避免在主菜单里读大图造成卡顿。视频每帧只上传一帧到双缓冲纹理，解码跟不上时
 * 继续画上一帧，主菜单帧率不会跟着视频抖动。配置与 {@link OpenMaverFeatures} 共用
 * {@code config/openmaver.properties}，ClickGUI 修改后由统一的保存流程落盘。
 */
public final class MaverWallpaper {

    // ------------------------------------------------------------------
    // 模式 / 填充方式（与 ClickGUI 的墙纸页面共用）
    // ------------------------------------------------------------------

    public static final int MODE_PANORAMA = 0;
    public static final int MODE_STATIC = 1;
    public static final int MODE_DYNAMIC = 2;

    public static final String[] MODE_NAMES = {"Panorama", "Static", "Dynamic"};
    private static final String[] MODE_KEYS = {"panorama", "static", "dynamic"};

    public static final int FIT_COVER = 0;
    public static final int FIT_CONTAIN = 1;
    public static final int FIT_STRETCH = 2;

    public static final String[] FIT_NAMES = {"Cover", "Contain", "Stretch"};
    private static final String[] FIT_KEYS = {"cover", "contain", "stretch"};

    private static final String[] MEDIA_EXTENSIONS = {
        "png", "jpg", "jpeg", "bmp", "gif", "webp", "avif",
        "mp4", "m4v", "mov", "webm", "mkv"
    };

    /** GIF 最大纹理边长与帧数，避免高分辨率长 GIF 吃掉过多显存。 */
    private static final int MAX_GIF_SIZE = 720;
    private static final int MAX_GIF_FRAMES = 64;
    private static final long MAX_GIF_BYTES = 96L * 1024L * 1024L;
    /** 静态图片允许更高分辨率。 */
    private static final int MAX_STATIC_SIZE = 2560;

    private static final int[] NO_TEXTURES = new int[0];

    // ------------------------------------------------------------------
    // 配置（volatile：后台加载线程只读一次快照）
    // ------------------------------------------------------------------

    private static final Object CONFIG_LOCK = new Object();
    private static volatile boolean configLoaded;
    private static volatile int mode = MODE_PANORAMA;
    private static volatile int fit = FIT_COVER;
    private static volatile String path = "";
    private static volatile long revision = 1L;

    // ------------------------------------------------------------------
    // 文件列表缓存（避免每帧扫描目录）
    // ------------------------------------------------------------------

    private static final Object LIST_LOCK = new Object();
    private static List<File> cachedFiles;
    private static long cachedDirectoryModified = Long.MIN_VALUE;

    // ------------------------------------------------------------------
    // 运行时纹理 / 动画状态（只在渲染线程读写）
    // ------------------------------------------------------------------

    private static int[] frameTextures = NO_TEXTURES;
    private static int[] frameDelays = new int[0];
    private static int frameWidth;
    private static int frameHeight;
    private static int activeFrame;
    private static long animationStartedAt;
    private static volatile String loadedKey;
    private static volatile String loadingKey;
    private static volatile String failedKey;
    private static boolean cleanupRequested;
    private static volatile PendingData pending;

    // ---- 视频（MP4 / MOV / WebM）流式播放状态（只在渲染线程读写） ----
    private static final int[] videoTextures = new int[2];
    private static int videoActive = -1;
    private static int videoWidth;
    private static int videoHeight;
    private static ByteBuffer videoBuffer;
    private static int videoBufferBytes;
    private static String videoKey;
    private static volatile boolean videoStarting;
    /** 图片/首帧解码失败后的重试时间（WebP 等格式可能等 ffmpeg 装好就能读）。 */
    private static volatile long decodeRetryAt;
    private static volatile int decodeFailures;
    /** 已经提示过解码失败的那个 key，避免每帧刷日志。 */
    private static volatile String warnedKey;
    /** 当前壁纸解码失败时的 key，供 ClickGUI 直接显示原因。 */
    private static volatile String decodeFailureKey;
    /** 启动失败后的退避：连续失败越多等得越久，成功一帧就清零。 */
    private static volatile int videoStartFailures;
    private static volatile long videoNextRetryAt;

    private MaverWallpaper() {
    }

    // ==================================================================
    // 配置
    // ==================================================================

    /** 首次绘制前直接从磁盘读取，避免主菜单要等 20 tick 才出现正确壁纸。 */
    private static void ensureConfigLoaded() {
        if (configLoaded) {
            return;
        }
        synchronized (CONFIG_LOCK) {
            if (configLoaded) {
                return;
            }
            configLoaded = true;
            Properties values = new Properties();
            File file = new File(settingsPath());
            if (file.isFile()) {
                try (FileInputStream input = new FileInputStream(file)) {
                    values.load(input);
                } catch (Throwable error) {
                    System.err.println("[OpenMaver] 无法读取壁纸配置: " + error);
                }
            }
            apply(values);
        }
    }

    /** 由 {@link OpenMaverFeatures#refreshSettings()} 在热重载时调用。 */
    public static void load(Properties values) {
        if (values == null) {
            return;
        }
        configLoaded = true;
        apply(values);
    }

    private static void apply(Properties values) {
        int nextMode = readMode(values.getProperty("menu.wallpaper.mode"));
        int nextFit = readFit(values.getProperty("menu.wallpaper.fit"));
        String nextPath = values.getProperty("menu.wallpaper.file", "");
        if (nextPath == null) {
            nextPath = "";
        }
        nextPath = nextPath.trim();
        // 兼容旧版保存的 dynamic + 空文件配置，直接恢复清晰的原版背景。
        if (nextPath.isEmpty()) {
            nextMode = MODE_PANORAMA;
        }

        boolean reload = nextMode != mode || !nextPath.equals(path);
        mode = nextMode;
        fit = nextFit;
        path = nextPath;
        if (reload) {
            invalidate();
        }
    }

    /** 追加到 openmaver.properties 的文本。 */
    public static String save() {
        ensureConfigLoaded();
        int safeMode = clamp(mode, MODE_PANORAMA, MODE_DYNAMIC);
        int safeFit = clamp(fit, FIT_COVER, FIT_STRETCH);
        return "menu.wallpaper.mode=" + MODE_KEYS[safeMode]
                + "\nmenu.wallpaper.file=" + escapeProperty(path)
                + "\nmenu.wallpaper.fit=" + FIT_KEYS[safeFit]
                + "\n";
    }

    public static int getMode() {
        ensureConfigLoaded();
        return mode;
    }

    public static String getModeName() {
        return MODE_NAMES[clamp(getMode(), MODE_PANORAMA, MODE_DYNAMIC)];
    }

    public static void setMode(int value) {
        ensureConfigLoaded();
        int next = clamp(value, MODE_PANORAMA, MODE_DYNAMIC);
        if (mode == next) {
            return;
        }
        mode = next;
        invalidate();
        OpenMaverFeatures.markDirty();
    }

    public static int nextMode() {
        setMode((getMode() + 1) % MODE_NAMES.length);
        return getMode();
    }

    public static int getFit() {
        ensureConfigLoaded();
        return fit;
    }

    public static String getFitName() {
        return FIT_NAMES[clamp(getFit(), FIT_COVER, FIT_STRETCH)];
    }

    public static void setFit(int value) {
        ensureConfigLoaded();
        int next = clamp(value, FIT_COVER, FIT_STRETCH);
        if (fit == next) {
            return;
        }
        fit = next;
        OpenMaverFeatures.markDirty();
    }

    public static int nextFit() {
        setFit((getFit() + 1) % FIT_NAMES.length);
        return getFit();
    }

    /** 当前选中的文件路径（可为相对路径 / 绝对路径 / 空字符串）。 */
    public static String getFilePath() {
        ensureConfigLoaded();
        return path;
    }

    /** 当前选中文件的显示名；没有文件时返回 Minecraft。 */
    public static String getDisplayName() {
        ensureConfigLoaded();
        if (path.isEmpty()) {
            return "Minecraft";
        }
        File file = new File(path);
        String name = file.getName();
        return name == null || name.isEmpty() ? path : name;
    }

    public static boolean isBuiltIn() {
        ensureConfigLoaded();
        return path.isEmpty();
    }

    public static void setFilePath(String value) {
        ensureConfigLoaded();
        String next = value == null ? "" : value.trim();
        boolean restorePanorama = next.isEmpty() && mode != MODE_PANORAMA;
        if (next.equals(path) && !restorePanorama) {
            return;
        }
        path = next;
        if (next.isEmpty()) {
            mode = MODE_PANORAMA;
        }
        invalidate();
        OpenMaverFeatures.markDirty();
    }

    // ==================================================================
    // 文件列表
    // ==================================================================

    /** 用户放置壁纸的目录；运行期可通过 {@code -Dopenmaver.wallpapers=...} 覆盖。 */
    public static File getWallpaperFolder() {
        String configured = System.getProperty("openmaver.wallpapers",
                "config/openmaver/wallpapers");
        return new File(configured);
    }

    public static String getWallpaperFolderDisplay() {
        try {
            return getWallpaperFolder().getPath();
        } catch (Throwable ignored) {
            return "config/openmaver/wallpapers";
        }
    }

    public static void ensureFolder() {
        try {
            File folder = getWallpaperFolder();
            if (!folder.isDirectory()) {
                folder.mkdirs();
            }
        } catch (Throwable ignored) {
            // 目录不可写时 GUI 会提示空列表，不影响主菜单。
        }
    }

    /** 在系统文件管理器中打开壁纸目录；失败时静默返回 false。 */
    public static boolean openFolder() {
        ensureFolder();
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(getWallpaperFolder());
                return true;
            }
        } catch (Throwable ignored) {
            // 无桌面环境 / 权限不足时保持静默。
        }
        return false;
    }

    /** 扫描目录中的图片文件。结果按文件名排序并缓存到目录时间戳变化为止。 */
    public static List<File> listWallpapers() {
        ensureFolder();
        File folder = getWallpaperFolder();
        long modified;
        try {
            modified = folder.isDirectory() ? folder.lastModified() : Long.MIN_VALUE;
        } catch (Throwable ignored) {
            modified = Long.MIN_VALUE;
        }
        synchronized (LIST_LOCK) {
            if (cachedFiles != null && modified == cachedDirectoryModified) {
                return cachedFiles;
            }
            List<File> found = new ArrayList<File>();
            File[] entries = null;
            try {
                entries = folder.listFiles();
            } catch (Throwable ignored) {
                // 权限问题 / 目录被删除时保持空列表。
            }
            if (entries != null) {
                for (int i = 0; i < entries.length; i++) {
                    File entry = entries[i];
                    if (entry != null && entry.isFile() && isSupported(entry)) {
                        found.add(entry);
                    }
                }
            }
            Collections.sort(found, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            cachedFiles = Collections.unmodifiableList(found);
            cachedDirectoryModified = modified;
            return cachedFiles;
        }
    }

    /** 手动刷新目录缓存（用户刚拖入文件后调用）。 */
    public static void refreshFiles() {
        synchronized (LIST_LOCK) {
            cachedFiles = null;
            cachedDirectoryModified = Long.MIN_VALUE;
        }
    }

    /** 文件名是否代表可循环播放的动态素材（GIF 或视频，用于选中文件时自动切到 DYNAMIC）。 */
    public static boolean isAnimatedFile(String name) {
        if (name == null) {
            return false;
        }
        return name.toLowerCase(Locale.ROOT).endsWith(".gif") || MaverVideo.isVideoFile(name);
    }

    /**
     * 当前壁纸解码失败时的简短原因（正常/加载中返回 null），供 ClickGUI 直接提示用户。
     * 典型场景：`.png` 其实是 WebP、视频缺 ffmpeg。
     */
    public static String getStatusHint() {
        ensureConfigLoaded();
        String failed = decodeFailureKey;
        if (failed == null || !failed.equals(currentKey())) {
            return null;
        }
        if (MaverVideo.isVideoFile(new File(path).getName())) {
            return "Video needs ffmpeg";
        }
        return "Can't decode · needs ffmpeg";
    }

    /** 文件名是否是视频（MP4 / M4V / MOV / WEBM / MKV）。 */
    public static boolean isVideoFile(String name) {
        return MaverVideo.isVideoFile(name);
    }

    /** 当前选中的文件是否就是目录中的这一项。 */
    public static boolean isSelected(File file) {
        if (file == null) {
            return false;
        }
        File selected = resolveSelectedFile();
        if (selected == null) {
            return false;
        }
        try {
            return selected.getCanonicalFile().equals(file.getCanonicalFile());
        } catch (Throwable ignored) {
            return selected.getAbsoluteFile().equals(file.getAbsoluteFile());
        }
    }

    private static boolean isSupported(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        for (int i = 0; i < MEDIA_EXTENSIONS.length; i++) {
            if (name.endsWith("." + MEDIA_EXTENSIONS[i])) {
                return true;
            }
        }
        return false;
    }

    /** 当前路径指向视频文件（视频由 ffmpeg 流式解码，不参与图片预解码队列）。 */
    private static boolean isVideoPath() {
        return !path.isEmpty() && MaverVideo.isVideoFile(new File(path).getName());
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    /**
     * 绘制主菜单壁纸。
     *
     * @return true 表示已经画过自定义背景（调用方不要再画原版全景图），
     *         false 表示应继续使用原版全景图。
     */
    public static boolean draw(Minecraft mc, int width, int height) {
        ensureConfigLoaded();
        if (width <= 0 || height <= 0 || mode == MODE_PANORAMA || path.isEmpty()) {
            // 切回原版全景图时释放自定义壁纸纹理；每帧只在确实有纹理时删一次。
            if (frameTextures.length > 0 || videoActive >= 0 || MaverVideo.isRunning()) {
                cleanupRequested = true;
            }
            releaseIfRequested();
            return false;
        }

        if (mode == MODE_DYNAMIC && isVideoPath()) {
            return drawVideo(width, height);
        }

        releaseIfRequested();
        if (pending != null) {
            installPending();
        }

        ensureLoaded();
        if (pending != null) {
            installPending();
        }
        if (frameTextures.length == 0) {
            // 加载中 / 文件损坏时回到原版全景图，不显示柔化的渐变背景。
            return false;
        }

        long now = System.currentTimeMillis();
        if (frameTextures.length > 1) {
            advanceGifFrame(now);
        } else {
            activeFrame = 0;
        }

        float zoom = 1.0F;
        float panX = 0.0F;
        float panY = 0.0F;
        if (mode == MODE_DYNAMIC && frameTextures.length <= 1) {
            // 静图动态化：非常慢的缩放与漂移，保持“动态壁纸”的呼吸感。
            float seconds = (now - animationStartedAt) / 1000.0F;
            zoom = 1.06F + 0.035F * (float) Math.sin(seconds * 0.11F);
            float limit = Math.max(0.0F, 1.0F - 1.0F / zoom);
            panX = limit * (float) Math.sin(seconds * 0.07F);
            panY = limit * 0.65F * (float) Math.cos(seconds * 0.05F);
        }

        int index = Math.max(0, Math.min(frameTextures.length - 1, activeFrame));
        drawTexture(frameTextures[index], frameWidth, frameHeight,
                width, height, fit, zoom, panX, panY);
        return true;
    }

    // ------------------------------------------------------------------
    // 视频壁纸（ffmpeg 流式解码）
    // ------------------------------------------------------------------

    /**
     * 视频背景：每帧从 {@link MaverVideo} 取一帧上传到双缓冲纹理。
     *
     * <p>解码跟不上（或视频刚启动、还没出帧）时继续画上一帧纹理，主菜单不会因为
     * 视频编码复杂而掉帧。视频播完/文件被删/没有 ffmpeg 时返回 false，回退到原版全景图。
     */
    private static boolean drawVideo(int width, int height) {
        releaseIfRequested();
        String key = currentKey();
        if (!key.equals(videoKey)) {
            // 换文件 / 换模式 / 配置热重载：重建解码会话。
            stopVideoSession();
            videoKey = key;
            videoStartFailures = 0;
            videoNextRetryAt = 0L;
        }
        if (frameTextures.length > 0 || pending != null) {
            // 从图片切到视频时清掉图片纹理，避免占着显存。
            releaseTextures();
            pending = null;
        }
        File selected = resolveFile(path);
        if (MaverVideo.isRunning() && !MaverVideo.isPlaying(selected)) {
            // 异步启动线程还没结束就换了文件：这里把起错的会话收掉。
            stopVideoSession();
        }
        if (!MaverVideo.isRunning() && !videoStarting
                && System.currentTimeMillis() >= videoNextRetryAt) {
            startVideoSession(selected, key);
        }

        MaverVideo.touch();
        MaverVideo.Frame frame = MaverVideo.take();
        if (frame != null) {
            try {
                uploadVideoFrame(frame);
            } finally {
                MaverVideo.release(frame);
            }
        }
        if (videoActive < 0 || videoTextures[videoActive] == 0) {
            // 首帧还没解出来，这一帧先回退到原版全景图。
            return false;
        }
        drawTexture(videoTextures[videoActive], videoWidth, videoHeight,
                width, height, fit, 1.0F, 0.0F, 0.0F);
        return true;
    }

    /**
     * 在后台线程拉起解码会话：ffprobe 读分辨率 + 启动 ffmpeg 大约要 100ms 以上，
     * 放在渲染线程里会让主菜单卡一下，所以这里只起线程，这一帧先画旧纹理/全景图。
     */
    private static void startVideoSession(final File selected, final String startKey) {
        if (selected == null) {
            noteVideoStartFailure();
            return;
        }
        final int maxWidth = videoMaxWidth();
        final int maxHeight = videoMaxHeight();
        final int fps = videoFps();
        videoStarting = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!MaverVideo.start(selected, maxWidth, maxHeight, fps)
                            && startKey.equals(currentKey())) {
                        // 只有用户还停在同一个壁纸上时才记失败；启动期间换了文件就不打扰新会话。
                        noteVideoStartFailure();
                    }
                } finally {
                    videoStarting = false;
                }
            }
        }, "OpenMaver-VideoStart");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 记录一次启动失败并安排退避重试：前几次 1s / 2s / 3s，之后固定 20s，
     * 这样 ffmpeg 后装、文件刚下载完、临时读不到盘这些情况都能自己恢复。
     */
    private static void noteVideoStartFailure() {
        int failures = videoStartFailures + 1;
        videoStartFailures = failures;
        long delay = failures <= 3 ? 1000L * failures : 20000L;
        videoNextRetryAt = System.currentTimeMillis() + delay;
    }

    private static void uploadVideoFrame(MaverVideo.Frame frame) {
        if (frame.width <= 0 || frame.height <= 0) {
            return;
        }
        if (videoWidth != frame.width || videoHeight != frame.height || videoActive < 0) {
            releaseVideoTextures();
            videoWidth = frame.width;
            videoHeight = frame.height;
            videoTextures[0] = createVideoTexture();
            videoTextures[1] = createVideoTexture();
            if (videoTextures[0] == 0 || videoTextures[1] == 0) {
                releaseVideoTextures();
                // GL 纹理创建失败：先停一会儿，避免每帧都去申请纹理。
                videoNextRetryAt = System.currentTimeMillis() + 3000L;
                return;
            }
            videoActive = 0;
        }
        int back = 1 - videoActive;
        if (videoTextures[back] == 0) {
            videoTextures[back] = createVideoTexture();
            if (videoTextures[back] == 0) {
                return;
            }
        }
        int bytes = frame.width * frame.height * 4;
        if (videoBuffer == null || videoBufferBytes < bytes) {
            videoBuffer = ByteBuffer.allocateDirect(bytes);
            videoBufferBytes = bytes;
        }
        videoBuffer.clear();
        int[] pixels = frame.pixels;
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            videoBuffer.put((byte) (argb >> 16 & 0xFF));
            videoBuffer.put((byte) (argb >> 8 & 0xFF));
            videoBuffer.put((byte) (argb & 0xFF));
            videoBuffer.put((byte) (argb >>> 24));
        }
        videoBuffer.flip();
        GlStateManager.bindTexture(videoTextures[back]);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                frame.width, frame.height, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, videoBuffer);
        GlStateManager.bindTexture(0);
        videoActive = back;
        videoStartFailures = 0;
        videoNextRetryAt = 0L;
    }

    /** 视频帧用线性过滤，缩到窗口大小时比像素图的 NEAREST 更平滑。 */
    private static int createVideoTexture() {
        int texture = GL11.glGenTextures();
        if (texture == 0) {
            return 0;
        }
        GlStateManager.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                GL11.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                GL11.GL_CLAMP_TO_EDGE);
        GlStateManager.bindTexture(0);
        return texture;
    }

    private static void releaseVideoTextures() {
        for (int i = 0; i < videoTextures.length; i++) {
            if (videoTextures[i] != 0) {
                GL11.glDeleteTextures(videoTextures[i]);
                videoTextures[i] = 0;
            }
        }
        videoActive = -1;
        videoWidth = 0;
        videoHeight = 0;
        GlStateManager.bindTexture(0);
    }

    /** 停止解码进程 + 释放视频纹理 + 复位会话键。 */
    private static void stopVideoSession() {
        MaverVideo.stop();
        releaseVideoTextures();
    }

    private static int videoMaxWidth() {
        return intProperty("openmaver.wallpaper.videoWidth", 1280, 160, 3840);
    }

    private static int videoMaxHeight() {
        return intProperty("openmaver.wallpaper.videoHeight", 720, 120, 2160);
    }

    private static int videoFps() {
        return intProperty("openmaver.wallpaper.videoFps", 24, 5, 60);
    }

    private static int intProperty(String key, int fallback, int min, int max) {
        try {
            String raw = System.getProperty(key);
            if (raw == null || raw.trim().isEmpty()) {
                return fallback;
            }
            return clamp(Integer.parseInt(raw.trim()), min, max);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void advanceGifFrame(long now) {
        long total = 0L;
        for (int i = 0; i < frameDelays.length; i++) {
            total += Math.max(20, frameDelays[i]);
        }
        if (total <= 0L) {
            activeFrame = 0;
            return;
        }
        long elapsed = (now - animationStartedAt) % total;
        if (elapsed < 0L) {
            elapsed += total;
        }
        long cursor = 0L;
        for (int i = 0; i < frameDelays.length; i++) {
            cursor += Math.max(20, frameDelays[i]);
            if (elapsed < cursor) {
                activeFrame = i;
                return;
            }
        }
        activeFrame = 0;
    }

    // ------------------------------------------------------------------
    // 纹理 / 异步加载
    // ------------------------------------------------------------------

    private static void ensureLoaded() {
        if (mode == MODE_DYNAMIC && isVideoPath()) {
            // 视频是流式播放，不需要（也不能）把整段预解码进内存。
            return;
        }
        String key = currentKey();
        if (key.equals(loadedKey) || key.equals(loadingKey) || key.equals(failedKey)) {
            return;
        }
        if (System.currentTimeMillis() < decodeRetryAt) {
            return;
        }
        loadingKey = key;
        final String requestedPath = path;
        final int requestedMode = mode;
        final long requestedRevision = revision;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                PendingData data = null;
                try {
                    data = decode(requestedPath, requestedMode);
                } catch (Throwable error) {
                    System.err.println("[OpenMaver] 壁纸加载失败: " + error);
                }
                if (requestedRevision != revision) {
                    return;
                }
                if (data == null) {
                    // 不再把 failedKey 钉死：WebP/AVIF 要等 ffmpeg 就位、文件也可能还在写，
                    // 所以按退避重试，用户重新选文件（revision 变化）时会立刻再试。
                    int failures = decodeFailures + 1;
                    decodeFailures = failures;
                    decodeRetryAt = System.currentTimeMillis()
                            + (failures <= 3 ? 3000L : 30000L);
                    decodeFailureKey = key;
                    if (!key.equals(warnedKey)) {
                        warnedKey = key;
                        System.err.println("[OpenMaver] 壁纸无法解码: " + requestedPath
                                + "（ImageIO 不支持或文件损坏；WebP / AVIF 需要安装 ffmpeg）");
                    }
                } else {
                    decodeFailures = 0;
                    decodeRetryAt = 0L;
                    decodeFailureKey = null;
                    data.key = key;
                    pending = data;
                }
                loadingKey = null;
            }
        }, "OpenMaver-Wallpaper");
        thread.setDaemon(true);
        thread.start();
    }

    private static String currentKey() {
        return revision + "|" + mode + "|" + path;
    }

    private static PendingData decode(String requestedPath, int requestedMode) throws Exception {
        File file = resolveFile(requestedPath);
        if (file == null || !file.isFile()) {
            return null;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (MaverVideo.isVideoFile(name)) {
            // 视频走 ffmpeg：STATIC 只解首帧，DYNAMIC 由 drawVideo 的流式会话接管
            // （ensureLoaded 不会为动态视频起线程）。
            MaverVideo.Still still = MaverVideo.firstFrame(file,
                    videoMaxWidth(), videoMaxHeight());
            if (still == null) {
                return null;
            }
            List<Frame> videoFrames = new ArrayList<Frame>();
            videoFrames.add(new Frame(still.pixels, still.width, still.height, 100));
            return new PendingData(videoFrames);
        }
        if (requestedMode == MODE_DYNAMIC && name.endsWith(".gif")) {
            try {
                List<Frame> frames = decodeGif(file);
                if (!frames.isEmpty()) {
                    return new PendingData(frames);
                }
            } catch (Throwable ignored) {
                // 动画解码失败时退回第一帧静态图。
            }
        }
        BufferedImage image = null;
        try {
            image = ImageIO.read(file);
        } catch (Throwable ignored) {
            // 损坏 / 不支持的编码：交给下面的 ffmpeg 兜底。
        }
        if (image != null) {
            List<Frame> frames = new ArrayList<Frame>();
            frames.add(toFrame(image, MAX_STATIC_SIZE, 100));
            return new PendingData(frames);
        }
        // ImageIO 不支持 WebP / AVIF，扩展名和真实格式不符时也会走到这里；
        // ffmpeg 能读的格式就直接解一帧当静态壁纸。
        MaverVideo.Still still = MaverVideo.firstFrame(file, MAX_STATIC_SIZE, MAX_STATIC_SIZE);
        if (still == null) {
            return null;
        }
        List<Frame> frames = new ArrayList<Frame>();
        frames.add(new Frame(still.pixels, still.width, still.height, 100));
        return new PendingData(frames);
    }

    private static List<Frame> decodeGif(File file) throws Exception {
        List<Frame> frames = new ArrayList<Frame>();
        ImageInputStream input = null;
        ImageReader reader = null;
        try {
            input = ImageIO.createImageInputStream(file);
            if (input == null) {
                return frames;
            }
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                return frames;
            }
            reader = readers.next();
            reader.setInput(input, false, false);
            int count = Math.min(MAX_GIF_FRAMES, reader.getNumImages(true));
            long bytes = 0L;
            int targetWidth = 0;
            int targetHeight = 0;
            for (int i = 0; i < count; i++) {
                BufferedImage image = reader.read(i);
                if (image == null) {
                    continue;
                }
                int delay = readGifDelay(reader, i);
                Frame frame;
                if (frames.isEmpty()) {
                    frame = toFrame(image, MAX_GIF_SIZE, delay);
                    targetWidth = frame.width;
                    targetHeight = frame.height;
                } else {
                    // 少数 GIF 的帧尺寸不一致；统一到第一帧尺寸，避免播放时比例跳动。
                    frame = toFrame(image, targetWidth, targetHeight, delay);
                }
                long frameBytes = (long) frame.width * frame.height * 4L;
                if (!frames.isEmpty() && bytes + frameBytes > MAX_GIF_BYTES) {
                    break;
                }
                frames.add(frame);
                bytes += frameBytes;
            }
        } finally {
            try {
                if (reader != null) {
                    reader.dispose();
                }
            } catch (Throwable ignored) {
                // 资源已经失效时无需再处理。
            }
            try {
                if (input != null) {
                    input.close();
                }
            } catch (Throwable ignored) {
                // 解码结果已经拿到，关闭失败不影响壁纸。
            }
        }
        return frames;
    }

    private static int readGifDelay(ImageReader reader, int index) {
        try {
            IIOMetadata data = reader.getImageMetadata(index);
            if (data == null) {
                return 100;
            }
            IIOMetadataNode root = (IIOMetadataNode) data.getAsTree(
                    "javax_imageio_gif_image_1.0");
            NodeList nodes = root.getElementsByTagName("GraphicControlExtension");
            if (nodes.getLength() > 0) {
                IIOMetadataNode control = (IIOMetadataNode) nodes.item(0);
                String raw = control.getAttribute("delayTime");
                if (raw != null && !raw.isEmpty()) {
                    int hundredths = Integer.parseInt(raw);
                    if (hundredths > 0) {
                        return Math.max(20, Math.min(5000, hundredths * 10));
                    }
                }
            }
        } catch (Throwable ignored) {
            // 元数据损坏时按 10 FPS 处理。
        }
        return 100;
    }

    /** 缩放到限制尺寸并转成 ARGB 像素数组（之后在渲染线程上传）。 */
    private static Frame toFrame(BufferedImage source, int maxSize, int delay) {
        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());
        float scale = Math.min(1.0F, maxSize / (float) Math.max(width, height));
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        return toFrame(source, targetWidth, targetHeight, delay);
    }

    /** 缩放到指定尺寸，供 GIF 后续帧与第一帧对齐。 */
    private static Frame toFrame(BufferedImage source, int targetWidth,
            int targetHeight, int delay) {
        BufferedImage canvas = new BufferedImage(targetWidth, targetHeight,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        int[] pixels = new int[targetWidth * targetHeight];
        canvas.getRGB(0, 0, targetWidth, targetHeight, pixels, 0, targetWidth);
        return new Frame(pixels, targetWidth, targetHeight, delay);
    }

    private static void installPending() {
        PendingData data = pending;
        pending = null;
        if (data == null || data.key == null || !data.key.equals(currentKey())) {
            return;
        }
        releaseTextures();
        int count = data.frames.size();
        int[] textures = new int[count];
        int[] delays = new int[count];
        for (int i = 0; i < count; i++) {
            Frame frame = data.frames.get(i);
            textures[i] = upload(frame);
            if (textures[i] == 0) {
                // GL 纹理创建失败：释放已经上传的帧，退回内置背景。
                for (int j = 0; j < i; j++) {
                    if (textures[j] != 0) {
                        GL11.glDeleteTextures(textures[j]);
                    }
                }
                frameTextures = NO_TEXTURES;
                frameDelays = new int[0];
                failedKey = data.key;
                cleanupRequested = false;
                return;
            }
            delays[i] = frame.delayMs;
            if (i == 0) {
                frameWidth = frame.width;
                frameHeight = frame.height;
            }
        }
        frameTextures = textures;
        frameDelays = delays;
        activeFrame = 0;
        animationStartedAt = System.currentTimeMillis();
        loadedKey = data.key;
        failedKey = null;
        cleanupRequested = false;
    }

    private static int upload(Frame frame) {
        int texture = GL11.glGenTextures();
        GlStateManager.bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                GL11.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                GL11.GL_CLAMP_TO_EDGE);
        // 每行 RGBA 数据长度天然是 4 的倍数，不需要修改 GL_UNPACK_ALIGNMENT。
        ByteBuffer buffer = ByteBuffer.allocateDirect(frame.width * frame.height * 4);
        int[] pixels = frame.pixels;
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            buffer.put((byte) (argb >> 16 & 0xFF));
            buffer.put((byte) (argb >> 8 & 0xFF));
            buffer.put((byte) (argb & 0xFF));
            buffer.put((byte) (argb >>> 24));
        }
        buffer.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                frame.width, frame.height, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, buffer);
        GlStateManager.bindTexture(0);
        return texture;
    }

    private static void releaseIfRequested() {
        if (cleanupRequested) {
            cleanupRequested = false;
            releaseTextures();
            stopVideoSession();
            videoKey = null;
        }
    }

    private static void releaseTextures() {
        if (frameTextures != null) {
            for (int i = 0; i < frameTextures.length; i++) {
                if (frameTextures[i] != 0) {
                    GL11.glDeleteTextures(frameTextures[i]);
                }
            }
        }
        frameTextures = NO_TEXTURES;
        frameDelays = new int[0];
        frameWidth = 0;
        frameHeight = 0;
        activeFrame = 0;
        loadedKey = null;
        GlStateManager.bindTexture(0);
    }

    private static void invalidate() {
        revision++;
        decodeRetryAt = 0L;
        decodeFailures = 0;
        decodeFailureKey = null;
        pending = null;
        loadingKey = null;
        failedKey = null;
        loadedKey = null;
        cleanupRequested = true;
    }

    // ------------------------------------------------------------------
    // 纹理绘制
    // ------------------------------------------------------------------

    private static void drawTexture(int texture, int imageWidth, int imageHeight,
            int width, int height, int fitMode, float zoom, float panX, float panY) {
        if (texture == 0 || imageWidth <= 0 || imageHeight <= 0) {
            return;
        }
        float screenAspect = width / (float) Math.max(1, height);
        float imageAspect = imageWidth / (float) Math.max(1, imageHeight);
        float left = 0.0F;
        float top = 0.0F;
        float right = width;
        float bottom = height;
        float u0 = 0.0F;
        float u1 = 1.0F;
        float v0 = 0.0F;
        float v1 = 1.0F;

        if (fitMode == FIT_CONTAIN) {
            if (imageAspect > screenAspect) {
                float drawHeight = width / imageAspect;
                top = (height - drawHeight) * 0.5F;
                bottom = top + drawHeight;
            } else {
                float drawWidth = height * imageAspect;
                left = (width - drawWidth) * 0.5F;
                right = left + drawWidth;
            }
        } else {
            float uSpan = 1.0F;
            float vSpan = 1.0F;
            if (fitMode == FIT_COVER) {
                if (imageAspect > screenAspect) {
                    uSpan = screenAspect / imageAspect;
                } else {
                    vSpan = imageAspect / screenAspect;
                }
            }
            if (zoom > 1.0001F) {
                uSpan /= zoom;
                vSpan /= zoom;
            }
            float uCenter = 0.5F + panX * Math.max(0.0F, 1.0F - uSpan);
            float vCenter = 0.5F + panY * Math.max(0.0F, 1.0F - vSpan);
            u0 = uCenter - uSpan * 0.5F;
            u1 = uCenter + uSpan * 0.5F;
            v0 = vCenter - vSpan * 0.5F;
            v1 = vCenter + vSpan * 0.5F;
        }

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GlColor.restore();

            // 先铺一层不透明底色：透明 PNG / CONTAIN 黑边都不会透出上一帧内容。
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GlColor.set(0xFF05080D);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(0.0F, 0.0F);
            GL11.glVertex2f(width, 0.0F);
            GL11.glVertex2f(width, height);
            GL11.glVertex2f(0.0F, height);
            GL11.glEnd();

            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GlColor.restore();
            GlStateManager.bindTexture(texture);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(u0, v0);
            GL11.glVertex2f(left, top);
            GL11.glTexCoord2f(u1, v0);
            GL11.glVertex2f(right, top);
            GL11.glTexCoord2f(u1, v1);
            GL11.glVertex2f(right, bottom);
            GL11.glTexCoord2f(u0, v1);
            GL11.glVertex2f(left, bottom);
            GL11.glEnd();
        } finally {
            GlStateManager.bindTexture(0);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glPopAttrib();
            // glPopAttrib 可能恢复调用前的纹理绑定；再显式绑定 0，保证
            // GlStateManager 的缓存与真实 GL 状态一致。
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GlColor.restore();
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static File resolveSelectedFile() {
        ensureConfigLoaded();
        return resolveFile(path);
    }

    private static File resolveFile(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            value = System.getProperty("user.home", ".") + value.substring(1);
        }
        File file = new File(value);
        if (file.isAbsolute()) {
            return file;
        }
        File inFolder = new File(getWallpaperFolder(), value);
        if (inFolder.isFile()) {
            return inFolder;
        }
        return file;
    }

    private static String settingsPath() {
        return System.getProperty("openmaver.features", "config/openmaver.properties");
    }

    private static int readMode(String raw) {
        if (raw != null) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < MODE_KEYS.length; i++) {
                if (MODE_KEYS[i].equals(value)) {
                    return i;
                }
            }
            try {
                return clamp(Integer.parseInt(value), MODE_PANORAMA, MODE_DYNAMIC);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return MODE_PANORAMA;
    }

    private static int readFit(String raw) {
        if (raw != null) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < FIT_KEYS.length; i++) {
                if (FIT_KEYS[i].equals(value)) {
                    return i;
                }
            }
            try {
                return clamp(Integer.parseInt(value), FIT_COVER, FIT_STRETCH);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return FIT_COVER;
    }

    /** Properties 值需要转义反斜杠、冒号、等号与前导空格。 */
    private static String escapeProperty(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '=':
                    out.append("\\=");
                    break;
                case ':':
                    out.append("\\:");
                    break;
                case ' ':
                    out.append(i == 0 ? "\\ " : " ");
                    break;
                default:
                    if (c < 0x20 || c > 0x7E) {
                        // Properties.load(InputStream) 按 ISO-8859-1 读取，
                        // 非 ASCII 路径写成 unicode 转义才不会变成乱码。
                        String hex = Integer.toHexString(c);
                        out.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) {
                            out.append('0');
                        }
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static final class Frame {
        final int[] pixels;
        final int width;
        final int height;
        final int delayMs;

        Frame(int[] pixels, int width, int height, int delayMs) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.delayMs = delayMs;
        }
    }

    private static final class PendingData {
        final List<Frame> frames;
        String key;

        PendingData(List<Frame> frames) {
            this.frames = frames;
        }
    }
}
