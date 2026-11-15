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
 * 主菜单壁纸：原版全景图、静态图片、动态 GIF 与内置动态背景。
 *
 * <p>壁纸文件统一放在 {@code config/openmaver/wallpapers/}，支持 PNG / JPG / BMP / GIF。
 * 静态模式只显示第一帧；动态模式下 GIF 会循环播放，普通图片则使用缓慢的
 * Ken Burns 平移 / 缩放，没有选择文件时绘制内置的动态渐变背景。
 *
 * <p>图片解码与缩放全部在后台 daemon 线程完成，渲染线程只负责上传纹理和绘制，
 * 避免在主菜单里读大图造成卡顿。配置与 {@link OpenMaverFeatures} 共用
 * {@code config/openmaver.properties}，ClickGUI 修改后由统一的保存流程落盘。
 */
public final class MaverWallpaper {

    // ------------------------------------------------------------------
    // 模式 / 填充方式（与 ClickGUI 的墙纸页面共用）
    // ------------------------------------------------------------------

    public static final int MODE_PANORAMA = 0;
    public static final int MODE_STATIC = 1;
    public static final int MODE_DYNAMIC = 2;

    public static final String[] MODE_NAMES = {"PANORAMA", "STATIC", "DYNAMIC"};
    private static final String[] MODE_KEYS = {"panorama", "static", "dynamic"};

    public static final int FIT_COVER = 0;
    public static final int FIT_CONTAIN = 1;
    public static final int FIT_STRETCH = 2;

    public static final String[] FIT_NAMES = {"COVER", "CONTAIN", "STRETCH"};
    private static final String[] FIT_KEYS = {"cover", "contain", "stretch"};

    private static final String[] IMAGE_EXTENSIONS = {
        "png", "jpg", "jpeg", "bmp", "gif"
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
    private static volatile float blurStrength = 0.35F;
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
        float nextBlur = readBlur(values.getProperty("menu.wallpaper.blur"));
        String nextPath = values.getProperty("menu.wallpaper.file", "");
        if (nextPath == null) {
            nextPath = "";
        }
        nextPath = nextPath.trim();

        boolean reload = nextMode != mode || !nextPath.equals(path);
        mode = nextMode;
        fit = nextFit;
        blurStrength = nextBlur;
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
                + "\nmenu.wallpaper.blur=" + Math.round(blurStrength * 100.0F)
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

    /** 背景模糊强度 0.0 ~ 1.0，0 表示完全清晰。 */
    public static float getBlurStrength() {
        ensureConfigLoaded();
        return blurStrength;
    }

    public static int getBlurPercent() {
        return Math.round(getBlurStrength() * 100.0F);
    }

    public static void setBlurStrength(float value) {
        ensureConfigLoaded();
        float next = Math.max(0.0F, Math.min(1.0F, value));
        if (Math.abs(next - blurStrength) < 0.0005F) {
            return;
        }
        blurStrength = next;
        OpenMaverFeatures.markDirty();
    }

    /** 当前选中的文件路径（可为相对路径 / 绝对路径 / 空字符串）。 */
    public static String getFilePath() {
        ensureConfigLoaded();
        return path;
    }

    /** 当前选中文件的显示名；没有文件时返回 BUILT-IN。 */
    public static String getDisplayName() {
        ensureConfigLoaded();
        if (path.isEmpty()) {
            return "BUILT-IN";
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
        if (next.equals(path)) {
            return;
        }
        path = next;
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

    /** 文件名是否代表动态 GIF（用于选中文件时自动切到 DYNAMIC）。 */
    public static boolean isAnimatedFile(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".gif");
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
        for (int i = 0; i < IMAGE_EXTENSIONS.length; i++) {
            if (name.endsWith("." + IMAGE_EXTENSIONS[i])) {
                return true;
            }
        }
        return false;
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
        if (width <= 0 || height <= 0 || mode == MODE_PANORAMA) {
            // 切回原版全景图时释放自定义壁纸纹理；每帧只在确实有纹理时删一次。
            if (frameTextures.length > 0) {
                cleanupRequested = true;
            }
            releaseIfRequested();
            return false;
        }

        releaseIfRequested();
        if (pending != null) {
            installPending();
        }

        // 没选文件时两种模式都有内置背景：静态是冻结的渐变，动态是流动的极光。
        if (path.isEmpty()) {
            drawGenerated(width, height, System.currentTimeMillis(),
                    mode == MODE_DYNAMIC);
            return true;
        }

        ensureLoaded();
        if (pending != null) {
            installPending();
        }
        if (frameTextures.length == 0) {
            // 加载中 / 文件损坏时先用内置渐变垫底，避免露出上一帧或黑屏。
            drawGenerated(width, height, System.currentTimeMillis(),
                    mode == MODE_DYNAMIC);
            return true;
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
        String key = currentKey();
        if (key.equals(loadedKey) || key.equals(loadingKey) || key.equals(failedKey)) {
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
                    failedKey = key;
                } else {
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
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            return null;
        }
        List<Frame> frames = new ArrayList<Frame>();
        frames.add(toFrame(image, MAX_STATIC_SIZE, 100));
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
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
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
                GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_LINEAR);
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

    // ------------------------------------------------------------------
    // 内置动态背景
    // ------------------------------------------------------------------

    private static void drawGenerated(int width, int height, long now, boolean animated) {
        float time = animated ? (now % 1_000_000L) / 1000.0F : 0.0F;
        int columns = clamp(Math.round(width / 42.0F), 14, 40);
        int rows = clamp(Math.round(height / 42.0F), 10, 28);

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glShadeModel(GL11.GL_SMOOTH);

            GL11.glBegin(GL11.GL_QUADS);
            for (int row = 0; row < rows; row++) {
                float v0 = row / (float) rows;
                float v1 = (row + 1) / (float) rows;
                float y0 = v0 * height;
                float y1 = v1 * height;
                for (int column = 0; column < columns; column++) {
                    float u0 = column / (float) columns;
                    float u1 = (column + 1) / (float) columns;
                    float x0 = u0 * width;
                    float x1 = u1 * width;
                    setGeneratedColor(u0, v0, time);
                    GL11.glVertex2f(x0, y0);
                    setGeneratedColor(u1, v0, time);
                    GL11.glVertex2f(x1, y0);
                    setGeneratedColor(u1, v1, time);
                    GL11.glVertex2f(x1, y1);
                    setGeneratedColor(u0, v1, time);
                    GL11.glVertex2f(x0, y1);
                }
            }
            GL11.glEnd();
        } finally {
            GL11.glPopAttrib();
            GlColor.restore();
        }
    }

    private static void setGeneratedColor(float u, float v, float time) {
        float waveA = (float) Math.sin(u * 4.1F + time * 0.26F)
                + (float) Math.sin(v * 5.4F - time * 0.21F);
        float waveB = (float) Math.sin((u + v) * 3.2F + time * 0.17F)
                + (float) Math.cos((u - v) * 4.8F - time * 0.13F);
        float glowA = 0.5F + 0.25F * waveA;
        float glowB = 0.5F + 0.25F * waveB;

        float dx = (u - 0.5F) * 2.0F;
        float dy = (v - 0.5F) * 2.0F;
        float vignette = Math.max(0.35F, 1.0F - 0.42F * (dx * dx + dy * dy));

        float red = (0.030F + 0.115F * glowA + 0.045F * glowB) * vignette;
        float green = (0.055F + 0.235F * glowA + 0.105F * glowB) * vignette;
        float blue = (0.105F + 0.170F * glowA + 0.380F * glowB) * vignette;
        GL11.glColor4f(clamp01(red), clamp01(green), clamp01(blue), 1.0F);
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

    /** 读取背景模糊百分比；配置里写 0 ~ 100。 */
    private static float readBlur(String raw) {
        if (raw != null) {
            String value = raw.trim();
            if (value.endsWith("%")) {
                value = value.substring(0, value.length() - 1).trim();
            }
            try {
                float percent = Float.parseFloat(value);
                // 兼容手动写 0.0 ~ 1.0 的比例值；GUI 保存的是 0 ~ 100 的百分比。
                if (value.indexOf('.') >= 0 && percent >= 0.0F && percent <= 1.0F) {
                    percent *= 100.0F;
                }
                return Math.max(0.0F, Math.min(100.0F, percent)) / 100.0F;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0.35F;
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
