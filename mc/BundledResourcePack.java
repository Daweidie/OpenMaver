import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 安装随 Maver 分发的 {@code 2pimp v2 [16x]} 材质包。
 *
 * <p>材质包已经打进 jar（{@code /openmaver/resourcepack/2pimp-v2-16x.zip}），
 * 第一次进入游戏时复制到实例的 {@code resourcepacks/} 目录，并把它加入
 * {@code GameSettings.resourcePacks}，游戏正常退出保存设置后下次启动就会启用。
 *
 * <p>只认第一次：写入 {@code .openmaver/resourcepack.installed} 标记后不再修改玩家
 * 的资源包列表，避免玩家手动关掉材质包后又被强制打开。文件本身在版本更新（大小
 * 变化）时会重新覆盖。
 */
public final class BundledResourcePack {

    /** jar 内资源名用 ASCII，避免 {@code §} / 空格在类路径上的转义问题。 */
    private static final String RESOURCE = "/openmaver/resourcepack/2pimp-v2-16x.zip";
    /** 写进 {@code resourcepacks/} 的文件名。用纯 ASCII：部分 1.8.9 的 JVM 以
     *  ASCII 解码文件名（sun.jnu.encoding=ANSI_X3.4-1968），带 § 的文件名会变成
     *  {@code ?} 而永远匹配不上。 */
    private static final String FILE_NAME = "2pimp v2 [16x].zip";
    /** 1.8.9 资源包列表里存的就是纯文件名（高版本才有 {@code file/} 前缀）。 */
    private static final String PACK_ID = FILE_NAME;
    /** 原始带颜色代码的文件名，用于清理旧版本安装的条目。 */
    private static final String LEGACY_NAME = "\u00a7c2\u00a75pimp \u00a7cv\u00a752 \u00a78[16x].zip";
    /** 去掉颜色代码/乱码后用于识别旧文件名。 */
    private static final String LEGACY_KEY = "c25pimp cv52 8[16x].zip";
    /** 标记：该实例已经由 Maver 处理过材质包。 */
    private static final String MARKER = ".openmaver/resourcepack.installed";

    private static boolean attempted;

    private BundledResourcePack() {
    }

    /**
     * 只在本次进程里执行一次；所有异常都被吞掉，不影响游戏主循环。
     *
     * @param mc Minecraft 客户端实例
     */
    public static void install(Minecraft mc) {
        if (attempted) {
            return;
        }
        attempted = true;
        try {
            if (mc == null) {
                return;
            }
            File gameDir = gameDir(mc);
            if (gameDir == null) {
                return;
            }
            File packDir = new File(gameDir, "resourcepacks");
            if (!packDir.isDirectory() && !packDir.mkdirs()) {
                return;
            }
            File target = new File(packDir, FILE_NAME);
            File marker = new File(gameDir, MARKER);
            boolean written = writeIfNeeded(target);
            if (written) {
                System.out.println("[OpenMaver] 已安装自带材质包: " + target);
            }
            ensureEnabled(mc, marker);
        } catch (Throwable error) {
            System.err.println("[OpenMaver] 自带材质包安装失败: " + error);
        }
    }

    /**
     * 确保材质包在已启用列表里：
     * <ul>
     *   <li>已经在列表里 → 写标记（说明玩家或启动脚本已经启用过）；</li>
     *   <li>列表里是旧版带 § 的文件名 / 被 ASCII JVM 解码坏的乱码 → 换成 ASCII 文件名；</li>
     *   <li>不在列表且没有标记 → 加入列表（退出游戏保存设置后下次启动启用）；</li>
     *   <li>不在列表但已有标记 → 玩家自己关掉了，尊重选择，不再添加。</li>
     * </ul>
     */
    private static void ensureEnabled(Minecraft mc, File marker) {
        try {
            Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
            if (settings == null) {
                return;
            }
            Object value = OpenMaverFeatures.read(settings, "resourcePacks",
                    "field_110540_a", null);
            if (!(value instanceof List)) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<String> packs = (List<String>) value;
            boolean migrated = false;
            for (java.util.Iterator<String> iterator = packs.iterator(); iterator.hasNext(); ) {
                if (isLegacyPackName(iterator.next())) {
                    iterator.remove();
                    migrated = true;
                }
            }
            if (packs.contains(PACK_ID)) {
                writeMarker(marker);
            } else if (migrated) {
                // 旧版本用 § 颜色代码做文件名，在 ASCII JVM 下会变成 ? 导致匹配失败。
                packs.add(PACK_ID);
                System.out.println("[OpenMaver] 已迁移自带材质包到 ASCII 文件名，"
                        + "退出游戏后下次启动生效");
            } else if (!marker.isFile()) {
                packs.add(PACK_ID);
                System.out.println("[OpenMaver] 自带材质包已加入资源包列表，"
                        + "退出游戏后下次启动生效");
            }
        } catch (Throwable ignored) {
            // 反射失败时保留文件即可，玩家仍可在 选项 → 资源包 中手动启用。
        }
    }

    /** 旧条目：{@code file/} 前缀、带 § 的文件名，或已被 ASCII JVM 解码坏的乱码。 */
    private static boolean isLegacyPackName(String name) {
        if (name == null) {
            return false;
        }
        String base = name.startsWith("file/") ? name.substring(5) : name;
        if (base.equals(LEGACY_NAME)) {
            return true;
        }
        StringBuilder stripped = new StringBuilder();
        for (int index = 0; index < base.length(); index++) {
            char character = base.charAt(index);
            if (character != '\u00a7' && character != '?' && character != '\ufffd') {
                stripped.append(character);
            }
        }
        return LEGACY_KEY.equals(stripped.toString());
    }

    /** 返回 true 表示这次真正覆盖了文件。 */
    private static boolean writeIfNeeded(File target) throws Exception {
        long size = resourceSize();
        if (size <= 0L) {
            return false;
        }
        if (target.isFile() && target.length() == size) {
            return false;
        }
        InputStream input = BundledResourcePack.class.getResourceAsStream(RESOURCE);
        if (input == null) {
            return false;
        }
        File temp = new File(target.getParentFile(), target.getName() + ".openmaver.tmp");
        try {
            OutputStream output = new FileOutputStream(temp);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable error) {
            temp.delete();
            throw error;
        }
        return true;
    }

    /** 统计 jar 内资源大小；资源不存在时返回 -1。 */
    private static long resourceSize() throws Exception {
        InputStream input = BundledResourcePack.class.getResourceAsStream(RESOURCE);
        if (input == null) {
            return -1L;
        }
        try {
            long total = 0L;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                total += read;
            }
            return total;
        } finally {
            input.close();
        }
    }

    private static File gameDir(Minecraft mc) {
        Object value = OpenMaverFeatures.read(mc, "mcDataDir", "field_71412_D", File.class);
        if (value instanceof File) {
            return (File) value;
        }
        String userDir = System.getProperty("user.dir");
        return (userDir == null || userDir.isEmpty()) ? null : new File(userDir);
    }

    private static void writeMarker(File marker) {
        try {
            File parent = marker.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            FileOutputStream output = new FileOutputStream(marker);
            try {
                output.write(0);
            } finally {
                output.close();
            }
        } catch (Throwable ignored) {
            // 标记失败只会让下次启动再检查一次，不影响材质包本身。
        }
    }
}
