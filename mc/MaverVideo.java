import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 主菜单视频壁纸解码器：用本机 ffmpeg 把 MP4 / MOV / WebM / MKV 解成 RGBA 帧流。
 *
 * <p>GIF 可以在后台一次性解成一组帧再上传纹理；视频没有这个余量，所以这里走
 * 流式管线：
 * <ol>
 *   <li>后台线程启动
 *       {@code ffmpeg -stream_loop -1 -re -i FILE -f rawvideo -pix_fmt rgba pipe:1}，
 *       按原速循环输出原始帧；</li>
 *   <li>解码线程把 RGBA 字节转成 ARGB int，写进 3 槽环形缓冲；</li>
 *   <li>渲染线程每帧 {@link #take()} 取最新一帧上传纹理，取不到就继续画上一帧，
 *       主菜单帧率不会被视频解码拖慢。</li>
 * </ol>
 *
 * <p>ffmpeg 位置解析顺序：{@code -Dopenmaver.ffmpeg=<path>} → 环境变量
 * {@code OPENMAVER_FFMPEG} → {@code config/openmaver/bin/ffmpeg[.exe]} → {@code PATH}。
 * 找不到 ffmpeg 时视频壁纸回退到原版全景图，其它壁纸模式不受影响。
 *
 * <p>主菜单停止绘制（例如进入世界）超过 {@link #IDLE_STOP_MS} 会自动停掉解码进程，
 * 下次回到主菜单由 {@link #start} 重新拉起，避免后台长期占用 CPU。
 */
public final class MaverVideo {

    private static final String[] EXTENSIONS = {
        "mp4", "m4v", "mov", "webm", "mkv"
    };

    /** 探测失败时的兜底画布尺寸（按 16:9 拉伸）。 */
    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;

    /** 超过这个时间没有被绘制，就认为已经离开主菜单，暂停解码。 */
    private static final long IDLE_STOP_MS = 6000L;

    private static final int SLOT_COUNT = 3;
    private static final int MAX_FAILURES = 3;
    /** 连续失败后的冷却时间；到点后自动重试，不必重开游戏。 */
    private static final long FAILURE_COOLDOWN_MS = 20000L;
    /** 找不到 ffmpeg 时的重新探测间隔。 */
    private static final long BINARY_RETRY_MS = 30000L;
    /** acquireWriteSlot 的两种失败原因：会话已被替换 / 三帧都还在用。 */
    private static final int SLOT_REPLACED = -2;
    private static final int SLOT_BUSY = -1;

    private static final Object SESSION_LOCK = new Object();
    private static final Object FRAME_LOCK = new Object();

    // ---- 会话状态（渲染线程只读，start/stop 在同一把锁里改） ----
    private static volatile boolean running;
    private static volatile long lastTouchAt;
    /** 连续失败后的冷却截止时间；0 表示当前可用。 */
    private static volatile long unavailableUntil;
    private static Process process;
    private static Thread readerThread;
    private static Thread watchdogThread;
    private static String activeFile = "";
    private static int consecutiveFailures;
    private static int sessionSeq;
    /** 同一个文件最多打印几条解码错误，坏文件不要刷屏。 */
    private static int reportedErrors;

    // ---- 帧环（解码线程写，渲染线程读） ----
    private static int[][] slots = new int[0][];
    private static boolean[] slotBusy = new boolean[0];
    private static int publishedSlot = -1;
    private static volatile int frameWidth;
    private static volatile int frameHeight;

    // ---- 可执行文件探测缓存 ----
    private static volatile String binary;
    private static volatile boolean binaryChecked;
    /** 没找到 ffmpeg 时的下次重试时间（用户装好之后不用重启游戏）。 */
    private static volatile long binaryRetryAt;

    private MaverVideo() {
    }

    // ==================================================================
    // 对外接口
    // ==================================================================

    /** 文件名是否是本解码器支持的视频。 */
    public static boolean isVideoFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < EXTENSIONS.length; i++) {
            if (lower.endsWith("." + EXTENSIONS[i])) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRunning() {
        return running;
    }

    /** 当前解码会话是不是在播这个文件（切换文件时用来发现“起错文件”的会话）。 */
    public static boolean isPlaying(File file) {
        if (file == null) {
            return false;
        }
        synchronized (SESSION_LOCK) {
            return running && file.getAbsolutePath().equals(activeFile);
        }
    }

    /** 渲染线程每帧调用：刷新“主菜单还在绘制”的时间戳，空闲暂停靠它判断。 */
    public static void touch() {
        lastTouchAt = System.currentTimeMillis();
    }

    /**
     * 启动（或复用）循环播放的解码会话。
     *
     * @return true 表示会话已经可用；false 表示这次拿不到视频（没有 ffmpeg / 文件坏 / 解码器崩了）。
     */
    public static boolean start(File file, int maxWidth, int maxHeight, int fps) {
        synchronized (SESSION_LOCK) {
            if (running) {
                return true;
            }
            if (file == null || !file.isFile()) {
                return false;
            }
            String requested = file.getAbsolutePath();
            if (!requested.equals(activeFile)) {
                // 换了文件就把上一次的失败判定清掉，允许重新尝试。
                activeFile = requested;
                unavailableUntil = 0L;
                consecutiveFailures = 0;
                reportedErrors = 0;
            }
            if (System.currentTimeMillis() < unavailableUntil) {
                return false;
            }
            String executable = findBinary();
            if (executable == null) {
                return false;
            }

            int width = DEFAULT_WIDTH;
            int height = DEFAULT_HEIGHT;
            int[] source = probeSize(ffprobeBinary(executable), file);
            if (source != null) {
                int[] target = fitWithin(source[0], source[1], maxWidth, maxHeight);
                width = target[0];
                height = target[1];
            }
            String filter = scaleFilter(width, height, fps);
            int session = ++sessionSeq;

            try {
                Process started = new ProcessBuilder(
                        buildStreamCommand(executable, file, filter)).start();
                drainErrors(started);
                int[][] sessionSlots = prepareSlots(width, height);
                process = started;
                running = true;
                lastTouchAt = System.currentTimeMillis();

                Thread reader = new Thread(new Reader(started, width, height, session,
                        sessionSlots), "OpenMaver-Video");
                reader.setDaemon(true);
                readerThread = reader;
                reader.start();
                startWatchdog(session);
                return true;
            } catch (Throwable error) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_FAILURES) {
                    // 连续失败先冷却一会儿，冷却结束后仍然可以重试（换文件会立刻重置）。
                    unavailableUntil = System.currentTimeMillis() + FAILURE_COOLDOWN_MS;
                    consecutiveFailures = 0;
                }
                System.err.println("[OpenMaver] 视频壁纸启动失败: " + error);
                stop();
                return false;
            }
        }
    }

    /** 停止解码会话（可以在任意线程调用；不会 join 解码线程自己）。 */
    public static void stop() {
        Thread reader;
        Thread watchdogThreadLocal;
        Process owned;
        synchronized (SESSION_LOCK) {
            running = false;
            sessionSeq++;
            owned = process;
            process = null;
            reader = readerThread;
            readerThread = null;
            watchdogThreadLocal = watchdogThread;
            watchdogThread = null;
            synchronized (FRAME_LOCK) {
                publishedSlot = -1;
                for (int i = 0; i < slotBusy.length; i++) {
                    slotBusy[i] = false;
                }
            }
        }
        if (watchdogThreadLocal != null && watchdogThreadLocal != Thread.currentThread()) {
            watchdogThreadLocal.interrupt();
        }
        if (owned != null) {
            try {
                owned.destroy();
                if (!owned.waitFor(400L, TimeUnit.MILLISECONDS)) {
                    owned.destroyForcibly();
                }
            } catch (Throwable ignored) {
                // 进程已经退出 / 被系统回收时无需处理。
            }
        }
        if (reader != null && reader != Thread.currentThread()) {
            try {
                reader.join(600L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 渲染线程取最新一帧；没有可用的新帧时返回 null。取到后必须 {@link #release(Frame)}。 */
    public static Frame take() {
        synchronized (FRAME_LOCK) {
            int slot = publishedSlot;
            if (slot < 0 || slot >= slots.length || slotBusy[slot]) {
                return null;
            }
            slotBusy[slot] = true;
            publishedSlot = -1;
            return new Frame(slots[slot], frameWidth, frameHeight, slot);
        }
    }

    /** 把槽位还给解码线程。 */
    public static void release(Frame frame) {
        if (frame == null) {
            return;
        }
        synchronized (FRAME_LOCK) {
            if (frame.slot >= 0 && frame.slot < slotBusy.length) {
                slotBusy[frame.slot] = false;
            }
        }
    }

    /**
     * 用 ffmpeg 只解出第一帧：STATIC 模式的视频首帧，以及 ImageIO 读不了的静态图
     * （WebP / AVIF / 扩展名与内容不符）都走这里。
     *
     * @return 解码成功返回像素，失败返回 null。
     */
    public static Still firstFrame(File file, int maxWidth, int maxHeight) {
        String executable = findBinary();
        if (executable == null || file == null || !file.isFile()) {
            return null;
        }
        int width = DEFAULT_WIDTH;
        int height = DEFAULT_HEIGHT;
        int[] source = probeSize(ffprobeBinary(executable), file);
        if (source != null) {
            int[] target = fitWithin(source[0], source[1], maxWidth, maxHeight);
            width = target[0];
            height = target[1];
        }
        String filter = scaleFilter(width, height, 0);
        Process started = null;
        try {
            started = new ProcessBuilder(
                    buildStillCommand(executable, file, filter)).start();
            drainErrors(started);
            byte[] raw = new byte[width * height * 4];
            if (!readFully(started.getInputStream(), raw)) {
                return null;
            }
            int[] pixels = new int[width * height];
            int dst = 0;
            for (int i = 0; i < raw.length; i += 4) {
                pixels[dst++] = 0xFF000000 | ((raw[i] & 0xFF) << 16)
                        | ((raw[i + 1] & 0xFF) << 8) | (raw[i + 2] & 0xFF);
            }
            return new Still(pixels, width, height);
        } catch (Throwable error) {
            System.err.println("[OpenMaver] ffmpeg 首帧解码失败: " + error);
            return null;
        } finally {
            if (started != null) {
                try {
                    started.destroy();
                } catch (Throwable ignored) {
                    // 已经退出时忽略。
                }
            }
        }
    }

    // ==================================================================
    // 解码线程
    // ==================================================================

    private static final class Reader implements Runnable {
        private final Process owned;
        private final int width;
        private final int height;
        private final int session;
        private final int[][] sessionSlots;

        Reader(Process owned, int width, int height, int session, int[][] sessionSlots) {
            this.owned = owned;
            this.width = width;
            this.height = height;
            this.session = session;
            this.sessionSlots = sessionSlots;
        }

        @Override
        public void run() {
            int frames = 0;
            try {
                frames = readLoop(owned, width, height, sessionSlots);
            } catch (Throwable ignored) {
                // 停止进程 / 关闭管道都会走到这里，属于正常退出路径。
            } finally {
                onReaderExit(owned, frames, session);
            }
        }
    }

    private static int readLoop(Process owned, int width, int height, int[][] sessionSlots)
            throws IOException {
        int frameBytes = width * height * 4;
        byte[] raw = new byte[frameBytes];
        InputStream input = owned.getInputStream();
        int frames = 0;
        while (running && process == owned) {
            if (!readFully(input, raw)) {
                return frames;
            }
            int slot = acquireWriteSlot(sessionSlots);
            if (slot < 0) {
                if (slot == SLOT_REPLACED) {
                    // 会话已经被 stop + 新 start 替换，别把旧尺寸的帧写进新缓冲。
                    return frames;
                }
                // 三个槽都还在用（渲染线程卡住 / 掉帧），这一帧直接丢掉。
                continue;
            }
            int[] pixels = sessionSlots[slot];
            int dst = 0;
            for (int i = 0; i < frameBytes; i += 4) {
                pixels[dst++] = 0xFF000000 | ((raw[i] & 0xFF) << 16)
                        | ((raw[i + 1] & 0xFF) << 8) | (raw[i + 2] & 0xFF);
            }
            publishSlot(slot, sessionSlots);
            frames++;
        }
        return frames;
    }

    private static void onReaderExit(Process owned, int frames, int session) {
        boolean selfFailure;
        boolean report;
        synchronized (SESSION_LOCK) {
            if (process == owned) {
                process = null;
                running = false;
                readerThread = null;
            }
            // session 还被保留说明不是我们主动 stop 的：解码器自己退出了。
            selfFailure = session == sessionSeq;
            if (selfFailure && frames == 0) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_FAILURES) {
                    unavailableUntil = System.currentTimeMillis() + FAILURE_COOLDOWN_MS;
                    consecutiveFailures = 0;
                }
            }
            report = selfFailure && consecutiveFailures <= 1;
        }
        if (report) {
            System.err.println("[OpenMaver] 视频解码器提前退出（ffmpeg 退出码 "
                    + exitCode(owned) + "）");
        }
        try {
            owned.destroy();
        } catch (Throwable ignored) {
            // 已经退出时忽略。
        }
    }

    private static int exitCode(Process owned) {
        try {
            return owned.exitValue();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int[][] prepareSlots(int width, int height) {
        synchronized (FRAME_LOCK) {
            slots = new int[SLOT_COUNT][];
            slotBusy = new boolean[SLOT_COUNT];
            for (int i = 0; i < SLOT_COUNT; i++) {
                slots[i] = new int[width * height];
            }
            publishedSlot = -1;
            frameWidth = width;
            frameHeight = height;
            return slots;
        }
    }

    private static int acquireWriteSlot(int[][] sessionSlots) {
        synchronized (FRAME_LOCK) {
            if (sessionSlots != slots) {
                return SLOT_REPLACED;
            }
            for (int i = 0; i < slotBusy.length; i++) {
                if (!slotBusy[i] && i != publishedSlot) {
                    return i;
                }
            }
            return SLOT_BUSY;
        }
    }

    private static void publishSlot(int slot, int[][] sessionSlots) {
        synchronized (FRAME_LOCK) {
            if (sessionSlots != slots) {
                return;
            }
            publishedSlot = slot;
        }
    }

    private static void startWatchdog(final int session) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running && session == sessionSeq) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    if (!running || session != sessionSeq) {
                        return;
                    }
                    if (System.currentTimeMillis() - lastTouchAt > IDLE_STOP_MS) {
                        // 主菜单已经不再绘制，先释放解码进程，下次绘制自动重启。
                        stop();
                        return;
                    }
                }
            }
        }, "OpenMaver-VideoIdle");
        thread.setDaemon(true);
        watchdogThread = thread;
        thread.start();
    }

    // ==================================================================
    // ffmpeg 调用
    // ==================================================================

    private static List<String> baseCommand(String executable) {
        List<String> command = new ArrayList<String>();
        command.add(executable);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        return command;
    }

    private static List<String> buildStreamCommand(String executable, File file, String filter) {
        List<String> command = baseCommand(executable);
        // -stream_loop 在 -i 之前：循环读同一个文件，壁纸永远不会播完。
        command.add("-stream_loop");
        command.add("-1");
        // -re 按原始帧率喂帧，否则 ffmpeg 会瞬间解完整个视频。
        command.add("-re");
        command.add("-i");
        command.add(file.getAbsolutePath());
        command.add("-an");
        command.add("-sn");
        command.add("-dn");
        command.add("-vf");
        command.add(filter);
        command.add("-f");
        command.add("rawvideo");
        command.add("-pix_fmt");
        command.add("rgba");
        command.add("pipe:1");
        return command;
    }

    private static List<String> buildStillCommand(String executable, File file, String filter) {
        List<String> command = baseCommand(executable);
        command.add("-i");
        command.add(file.getAbsolutePath());
        command.add("-an");
        command.add("-sn");
        command.add("-dn");
        command.add("-frames:v");
        command.add("1");
        command.add("-vf");
        command.add(filter);
        command.add("-f");
        command.add("rawvideo");
        command.add("-pix_fmt");
        command.add("rgba");
        command.add("pipe:1");
        return command;
    }

    private static String scaleFilter(int width, int height, int fps) {
        StringBuilder filter = new StringBuilder(48);
        if (fps > 0) {
            filter.append("fps=").append(fps).append(',');
        }
        filter.append("scale=").append(width).append(':').append(height)
                .append(":flags=bicubic,format=rgba");
        return filter.toString();
    }

    private static int[] fitWithin(int sourceWidth, int sourceHeight,
            int maxWidth, int maxHeight) {
        int limitWidth = Math.max(64, maxWidth);
        int limitHeight = Math.max(64, maxHeight);
        float scale = Math.min(1.0F, Math.min(limitWidth / (float) sourceWidth,
                limitHeight / (float) sourceHeight));
        int width = Math.max(2, Math.round(sourceWidth * scale) & ~1);
        int height = Math.max(2, Math.round(sourceHeight * scale) & ~1);
        return new int[]{width, height};
    }

    /**
     * 用 ffprobe 读“播放时”的原始分辨率；拿不到返回 null（调用方走默认画布）。
     *
     * <p>注意两个坑：手机视频常带 display matrix（rotation=90），ffmpeg 会先自动旋转再进滤镜链，
     * 所以这里按 rotation 交换宽高，否则竖屏视频会被压扁；另外 ffprobe 的 csv 输出在带
     * side data 时会多出字段，所以改成解析 {@code default=nw=1} 的 key=value 行。
     */
    private static int[] probeSize(String ffprobe, File file) {
        Process started = null;
        try {
            started = new ProcessBuilder(ffprobe,
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height:stream_side_data=rotation",
                    "-of", "default=nw=1",
                    file.getAbsolutePath()).redirectErrorStream(true).start();
            String text = readAll(started.getInputStream(), 4096);
            if (!started.waitFor(3L, TimeUnit.SECONDS)) {
                started.destroyForcibly();
                return null;
            }
            if (started.exitValue() != 0 || text == null) {
                return null;
            }
            int width = 0;
            int height = 0;
            int rotation = 0;
            String[] lines = text.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String key = line.substring(0, split).trim();
                String value = line.substring(split + 1).trim();
                try {
                    if ("width".equals(key)) {
                        width = Integer.parseInt(value);
                    } else if ("height".equals(key)) {
                        height = Integer.parseInt(value);
                    } else if ("rotation".equals(key)) {
                        rotation = Integer.parseInt(value);
                    }
                } catch (NumberFormatException ignored) {
                    // 非数字字段（例如 side data 的 type）直接跳过。
                }
            }
            if (width <= 1 || height <= 1) {
                return null;
            }
            if (Math.abs(rotation) % 180 == 90) {
                // 90 / 270 度：ffmpeg 解码后已经旋转，画面宽高互换。
                return new int[]{height, width};
            }
            return new int[]{width, height};
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (started != null) {
                try {
                    started.destroy();
                } catch (Throwable ignored) {
                    // 忽略。
                }
            }
        }
    }

    private static String ffprobeBinary(String ffmpeg) {
        try {
            File parent = new File(ffmpeg).getAbsoluteFile().getParentFile();
            if (parent != null) {
                File probe = new File(parent, isWindows() ? "ffprobe.exe" : "ffprobe");
                if (probe.isFile()) {
                    return probe.getPath();
                }
            }
        } catch (Throwable ignored) {
            // 用 PATH 里的 ffprobe。
        }
        return isWindows() ? "ffprobe.exe" : "ffprobe";
    }

    // ==================================================================
    // 可执行文件探测 / IO 工具
    // ==================================================================

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static String findBinary() {
        if (binary != null) {
            return binary;
        }
        if (binaryChecked && System.currentTimeMillis() < binaryRetryAt) {
            return null;
        }
        synchronized (SESSION_LOCK) {
            if (binary != null) {
                return binary;
            }
            if (binaryChecked && System.currentTimeMillis() < binaryRetryAt) {
                return null;
            }
            List<String> candidates = new ArrayList<String>();
            String configured = System.getProperty("openmaver.ffmpeg", "");
            if (configured != null && !configured.trim().isEmpty()) {
                candidates.add(configured.trim());
            }
            String fromEnv = System.getenv("OPENMAVER_FFMPEG");
            if (fromEnv != null && !fromEnv.trim().isEmpty()) {
                candidates.add(fromEnv.trim());
            }
            String name = isWindows() ? "ffmpeg.exe" : "ffmpeg";
            candidates.add(new File(new File("config/openmaver/bin"), name).getPath());
            candidates.add(name);
            for (int i = 0; i < candidates.size(); i++) {
                String candidate = candidates.get(i);
                if (works(candidate)) {
                    binary = candidate;
                    break;
                }
            }
            boolean firstProbe = !binaryChecked;
            binaryChecked = true;
            if (binary == null) {
                binaryRetryAt = System.currentTimeMillis() + BINARY_RETRY_MS;
                if (firstProbe) {
                    System.err.println("[OpenMaver] 未找到 ffmpeg，MP4/视频壁纸暂不可用；"
                            + "安装 ffmpeg、放到 config/openmaver/bin/ 或用 "
                            + "-Dopenmaver.ffmpeg=<path> 指定即可启用（会自动重试）。");
                }
            }
            return binary;
        }
    }

    private static boolean works(String candidate) {
        Process started = null;
        try {
            started = new ProcessBuilder(candidate, "-hide_banner", "-version")
                    .redirectErrorStream(true).start();
            readAll(started.getInputStream(), 8192);
            if (!started.waitFor(4L, TimeUnit.SECONDS)) {
                started.destroyForcibly();
                return false;
            }
            return started.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (started != null) {
                try {
                    started.destroy();
                } catch (Throwable ignored) {
                    // 忽略。
                }
            }
        }
    }

    /** ffmpeg 的 stderr 单独读掉，避免日志写满管道把它自己堵死。 */
    private static void drainErrors(final Process owned) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream input = owned.getErrorStream();
                byte[] buffer = new byte[1024];
                StringBuilder tail = new StringBuilder();
                try {
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        if (tail.length() < 2048) {
                            tail.append(new String(buffer, 0, read, "UTF-8"));
                        }
                    }
                } catch (Throwable ignored) {
                    // 进程被停止时管道关闭。
                }
                if (tail.length() == 0) {
                    return;
                }
                int code = exitCode(owned);
                if (code != 0) {
                    synchronized (SESSION_LOCK) {
                        if (reportedErrors >= 2) {
                            return;
                        }
                        reportedErrors++;
                    }
                    String text = tail.toString().trim().replace('\n', ' ');
                    if (text.length() > 300) {
                        text = text.substring(0, 300) + "...";
                    }
                    System.err.println("[OpenMaver] ffmpeg(" + code + "): " + text);
                }
            }
        }, "OpenMaver-VideoLog");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    private static String readAll(InputStream input, int limit) {
        try {
            byte[] buffer = new byte[1024];
            StringBuilder out = new StringBuilder();
            int read;
            while (out.length() < limit && (read = input.read(buffer)) > 0) {
                out.append(new String(buffer, 0, read, "UTF-8"));
            }
            return out.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================================================================
    // 数据载体
    // ==================================================================

    /** 一帧解码结果；pixels 是解码线程复用的槽位，必须在本帧内用完并 release。 */
    public static final class Frame {
        public final int[] pixels;
        public final int width;
        public final int height;
        private final int slot;

        Frame(int[] pixels, int width, int height, int slot) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.slot = slot;
        }
    }

    /** 静态首帧结果（独立数组，调用方可以长期持有）。 */
    public static final class Still {
        public final int[] pixels;
        public final int width;
        public final int height;

        Still(int[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }
}
