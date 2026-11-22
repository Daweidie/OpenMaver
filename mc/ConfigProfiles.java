import config.Config;
import module.Module;
import module.ModuleManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ClickGUI「Config」浮动面板的配置档案（profile）读写。
 *
 * <p>一个 {@code .cfg} 文件就是一整套客户端状态，内部是纯文本、分段存放：
 * <pre>
 *   # OpenMaver profile v1
 *   @@FEATURES        <- config/openmaver.properties 的原文
 *   @@MODULES         <- config/default.jsonc 的原文
 *   @@STATE           <- 当前模块开关 / 键位（GUI 里点出来的状态）
 * </pre>
 *
 * <p>这样「保存配置到 xxx」= 把当前内存状态落盘后打包成一个文件，
 * 「导入配置为 xxx」= 把文件拆回两个配置文件、重载配置、再把模块开关按档案恢复。
 * 单文件便于像截图里那样直接丢给别人（{@code LF_Opal10.26.cfg}）。
 *
 * <p>所有异常都在内部吞掉并写进 {@link #status()}，GUI 只负责显示，
 * 绝不让文件问题冒到渲染循环里。
 */
public final class ConfigProfiles {

    /** 档案扩展名（面板里显示成 {@code name.cfg}）。 */
    public static final String EXTENSION = ".cfg";

    private static final String MARK_FEATURES = "@@FEATURES";
    private static final String MARK_MODULES = "@@MODULES";
    private static final String MARK_STATE = "@@STATE";

    private static final String HEADER = "# OpenMaver profile v1";
    private static final int MAX_NAME = 40;

    /** 最近一次操作的结果（GUI 底部一行提示用）。 */
    private static volatile String status = "";
    private static volatile boolean statusError;
    private static volatile long statusAt;
    private static boolean importing;
    private static String importedName;

    private ConfigProfiles() {
    }

    // ------------------------------------------------------------------
    // 路径
    // ------------------------------------------------------------------

    /** 功能参数文件（与 OpenMaverFeatures.saveSettings 使用同一套解析规则）。 */
    public static String featuresPath() {
        return System.getProperty("openmaver.features", "config/openmaver.properties");
    }

    /** 模块开关 / 键位文件。 */
    public static String modulesPath() {
        return System.getProperty("openmaver.config", "config/default.jsonc");
    }

    /** 档案目录：与功能参数文件同级下的 {@code profiles/}。 */
    public static File folder() {
        File features = new File(featuresPath());
        File parent = features.getParentFile();
        File folder = new File(parent == null ? new File("config") : parent, "profiles");
        if (!folder.isDirectory()) {
            folder.mkdirs();
        }
        return folder;
    }

    public static File fileOf(String name) {
        return new File(folder(), sanitize(name) + EXTENSION);
    }

    /** 目录里已有的档案名（不含扩展名），按字母序。 */
    public static List<String> listNames() {
        List<String> names = new ArrayList<String>();
        File[] files = folder().listFiles();
        if (files == null) {
            return names;
        }
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (!file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (name.length() <= EXTENSION.length()
                    || !name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
                continue;
            }
            names.add(name.substring(0, name.length() - EXTENSION.length()));
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static boolean exists(String name) {
        String clean = sanitize(name);
        return !clean.isEmpty() && fileOf(clean).isFile();
    }

    /** 档案保存时间（毫秒），用于列表右侧显示；不存在返回 0。 */
    public static long savedAt(String name) {
        File file = fileOf(name);
        return file.isFile() ? file.lastModified() : 0L;
    }

    public static String savedAtText(String name) {
        long at = savedAt(name);
        if (at <= 0L) {
            return "";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(at));
    }

    // ------------------------------------------------------------------
    // 保存 / 导入 / 删除
    // ------------------------------------------------------------------

    /**
     * 把当前状态保存成 {@code <name>.cfg}（同名覆盖）。
     *
     * @param rawName 玩家输入的名字，允许带 {@code .cfg}
     * @return 是否成功
     */
    public static boolean save(String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            return fail("Type a profile name first.");
        }
        try {
            // 先把内存里的滑块 / 开关落盘，否则存下来的是上一次的旧值。
            OpenMaverFeatures.markDirty();
            OpenMaverFeatures.flushSettings();

            String features = readText(new File(featuresPath()));
            String modules = readText(new File(modulesPath()));

            StringBuilder out = new StringBuilder();
            out.append(HEADER).append('\n');
            out.append("# name: ").append(name).append('\n');
            out.append("# saved: ").append(
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                            .format(new Date())).append('\n');
            out.append(MARK_FEATURES).append('\n').append(features);
            if (!features.endsWith("\n")) {
                out.append('\n');
            }
            out.append(MARK_MODULES).append('\n').append(modules);
            if (!modules.endsWith("\n")) {
                out.append('\n');
            }
            out.append(MARK_STATE).append('\n');
            List<Module> list = ModuleManager.getModules();
            for (int i = 0; i < list.size(); i++) {
                Module module = list.get(i);
                String keybind = module.getKeybind();
                out.append(module.getName()).append('|')
                        .append(module.isEnabled() ? "on" : "off").append('|')
                        .append(keybind == null ? "" : keybind).append('\n');
            }
            writeText(fileOf(name), out.toString());
            return ok("Saved " + name + EXTENSION + " (" + list.size() + " modules)");
        } catch (Throwable t) {
            return fail("Save failed: " + t);
        }
    }

    /**
     * 导入档案：还原两个配置文件 → 重载配置 → 按档案恢复模块开关 / 键位。
     *
     * @param rawName 档案名（可带 {@code .cfg}）
     * @return 是否成功
     */
    public static boolean load(String rawName) {
        String name = sanitize(rawName);
        File file = fileOf(name);
        if (name.isEmpty() || !file.isFile()) {
            return fail("Profile not found: " + (name.isEmpty() ? "?" : name + EXTENSION));
        }
        try {
            String raw = readText(file);
            String features = section(raw, MARK_FEATURES, MARK_MODULES);
            String modules = section(raw, MARK_MODULES, MARK_STATE);
            String state = section(raw, MARK_STATE, null);

            if (!features.trim().isEmpty()) {
                writeText(new File(featuresPath()), features);
            }
            if (!modules.trim().isEmpty()) {
                writeText(new File(modulesPath()), modules);
            }

            Config config = Config.getDefault();
            if (config != null) {
                try {
                    config.reload();
                } catch (IOException ignored) {
                    // 文件刚写过，解析失败时保留旧配置即可。
                }
            }
            OpenMaverFeatures.reloadSettingsNow();
            ModuleManager.syncAll();
            applyState(state);
            return ok("Loaded " + name + EXTENSION);
        } catch (Throwable t) {
            return fail("Load failed: " + t);
        }
    }

    /** 删除档案。 */
    public static boolean delete(String rawName) {
        String name = sanitize(rawName);
        File file = fileOf(name);
        if (name.isEmpty() || !file.isFile()) {
            return fail("Nothing to delete: " + (name.isEmpty() ? "?" : name + EXTENSION));
        }
        return file.delete() ? ok("Deleted " + name + EXTENSION)
                : fail("Could not delete " + name + EXTENSION);
    }

    /** 打开档案目录（与壁纸页的 FOLDER 行同一套做法）。 */
    public static boolean openFolder() {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(folder());
                return ok("Opened " + folder().getAbsolutePath());
            }
        } catch (Throwable ignored) {
            // 无桌面环境 / 权限不足
        }
        return fail("Folder: " + folder().getAbsolutePath());
    }

    /** 供面板复制的绝对路径（FOLDER 打不开时至少能贴出来）。 */
    public static String folderPath() {
        return folder().getAbsolutePath();
    }

    /** 文件选择器在 AWT 线程运行，不阻塞 Minecraft 渲染；导入后由游戏线程选中档案。 */
    public static synchronized void requestImport() {
        if (importing) {
            return;
        }
        importing = true;
        try {
            java.awt.EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(folder());
                        chooser.setDialogTitle("Import OpenMaver profile");
                        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                                "OpenMaver profiles (*.cfg)", "cfg"));
                        chooser.setAcceptAllFileFilterUsed(false);
                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                            String name = importFile(chooser.getSelectedFile());
                            synchronized (ConfigProfiles.class) {
                                importedName = name;
                            }
                        }
                    } catch (Throwable t) {
                        fail("Import failed: " + t.getMessage());
                    } finally {
                        synchronized (ConfigProfiles.class) {
                            importing = false;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            importing = false;
            fail("Import failed: " + t.getMessage());
        }
    }

    public static synchronized String takeImportedName() {
        String name = importedName;
        importedName = null;
        return name;
    }

    /** 导入本地档案到 profiles；保留同名文件，用户随后点击 Load 应用。 */
    public static String importFile(File source) {
        try {
            if (source == null || !source.isFile()
                    || !source.getName().toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
                fail("Choose a .cfg profile.");
                return null;
            }
            String raw = readText(source).replace("\r\n", "\n");
            if (!raw.startsWith(HEADER + "\n") || !raw.contains("\n" + MARK_FEATURES + "\n")
                    || !raw.contains("\n" + MARK_MODULES + "\n")
                    || !raw.contains("\n" + MARK_STATE + "\n")) {
                fail("Invalid OpenMaver profile.");
                return null;
            }
            String base = sanitize(source.getName());
            if (base.isEmpty()) {
                base = suggestName();
            }
            String name = base;
            int suffix = 2;
            while (exists(name)) {
                String ending = "_" + suffix++;
                name = base.substring(0, Math.min(base.length(), MAX_NAME - ending.length())) + ending;
            }
            writeText(fileOf(name), raw);
            ok("Imported " + name + EXTENSION);
            return name;
        } catch (Throwable t) {
            fail("Import failed: " + t.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 状态提示
    // ------------------------------------------------------------------

    public static String status() {
        return status;
    }

    public static boolean statusIsError() {
        return statusError;
    }

    public static long statusAt() {
        return statusAt;
    }

    public static void clearStatus() {
        status = "";
        statusError = false;
    }

    private static boolean ok(String message) {
        status = message;
        statusError = false;
        statusAt = System.currentTimeMillis();
        System.out.println("[OpenMaver] config: " + message);
        return true;
    }

    private static boolean fail(String message) {
        status = message;
        statusError = true;
        statusAt = System.currentTimeMillis();
        System.out.println("[OpenMaver] config: " + message);
        return false;
    }

    // ------------------------------------------------------------------
    // 名字 / 文本
    // ------------------------------------------------------------------

    /** 只保留安全字符，去掉 {@code .cfg} 后缀，限制长度。 */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            value = value.substring(0, value.length() - EXTENSION.length());
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < MAX_NAME; i++) {
            char c = value.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'
                    || c == ' ';
            out.append(safe ? c : '_');
        }
        String cleaned = out.toString().trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1).trim();
        }
        return cleaned;
    }

    /** 默认档案名：{@code LF_Opal11.22} 这种「前缀 + 月日」风格。 */
    public static String suggestName() {
        String stamp = new SimpleDateFormat("MM.dd", Locale.ROOT).format(new Date());
        String base = "config_" + stamp;
        if (!exists(base)) {
            return base;
        }
        for (int i = 2; i < 100; i++) {
            String candidate = base + "_" + i;
            if (!exists(candidate)) {
                return candidate;
            }
        }
        return base;
    }

    private static String section(String raw, String startMark, String endMark) {
        String[] lines = raw.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inside = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.equals(startMark)) {
                inside = true;
                continue;
            }
            if (endMark != null && trimmed.equals(endMark)) {
                break;
            }
            if (inside) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static void applyState(String state) {
        if (state == null || state.trim().isEmpty()) {
            return;
        }
        String[] lines = state.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < 2) {
                continue;
            }
            Module module = ModuleManager.getModule(parts[0]);
            if (module == null) {
                continue;
            }
            module.setEnabled("on".equalsIgnoreCase(parts[1]));
            if (parts.length >= 3) {
                module.setKeybind(parts[2]);
            }
        }
    }

    private static String readText(File file) throws IOException {
        if (!file.isFile()) {
            return "";
        }
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            input.close();
        }
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
