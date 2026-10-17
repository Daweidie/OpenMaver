package config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSONC 配置读取器（JSON with Comments）。
 *
 * <p>支持：
 * <ul>
 *   <li>{@code //} 行注释与 {@code /* *}{@code /} 块注释</li>
 *   <li>对象、数组、字符串、数字、布尔、null</li>
 *   <li>点号嵌套 key，如 {@code "combat.aimassist.enabled"}</li>
 *   <li>外部修改文件后通过 {@link #reloadIfChanged()} 热重载</li>
 * </ul>
 *
 * <p>所有 getter 都提供带默认值的重载，读取失败时返回默认值而非抛异常。
 * 仅使用 Java 8 自带 API，不依赖任何第三方库。
 */
public final class Config {

    /** 配置文件路径。 */
    private final Path path;

    /** 解析后的根对象（不可变，通过整体替换实现热重载）。 */
    private volatile Map<String, Object> root = Collections.emptyMap();

    /** 上次成功加载时的文件修改时间（毫秒）。 */
    private volatile long lastModified = 0L;

    /**
     * 全局默认配置实例。
     *
     * <p>由 {@code OpenMaver} 在加载配置后通过 {@link #setDefault(Config)} 设置；
     * 模块基类 {@code module.Module} 按约定从此处读取自己的配置。
     *
     * <p>这样做是因为 {@code module} 属于具名包，而入口 {@code OpenMaver} 位于默认包，
     * Java 规范不允许具名包直接引用默认包，故用此静态入口解耦。
     */
    private static volatile Config defaultInstance;

    /** 获取全局默认配置（可能为 null）。 */
    public static Config getDefault() {
        return defaultInstance;
    }

    /** 设置全局默认配置（可为 null）。 */
    public static void setDefault(Config config) {
        defaultInstance = config;
    }

    public Config(String filePath) {
        this.path = Paths.get(filePath);
    }

    public Path getPath() {
        return path;
    }

    // ------------------------------------------------------------------
    // 加载 / 重载
    // ------------------------------------------------------------------

    /** 从磁盘读取并解析配置，会抛出 IO / 解析异常。 */
    public synchronized void load() throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("配置文件不存在: " + path.toAbsolutePath());
        }
        String raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String json = stripComments(raw);
        Map<String, Object> parsed = new JsonParser(json).parseObject();
        this.root = Collections.unmodifiableMap(parsed);
        this.lastModified = Files.getLastModifiedTime(path).toMillis();
    }

    /** 强制重新加载（等价于 {@link #load()}）。 */
    public synchronized void reload() throws IOException {
        load();
    }

    /**
     * 检测文件是否被外部修改，若修改则自动重载。
     *
     * @return 是否发生了重载
     */
    public synchronized boolean reloadIfChanged() {
        try {
            if (!Files.exists(path)) {
                return false;
            }
            long mod = Files.getLastModifiedTime(path).toMillis();
            if (mod != lastModified) {
                load();
                return true;
            }
        } catch (IOException ignored) {
            // 读取失败时保留旧配置，等待下次机会
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 读取接口
    // ------------------------------------------------------------------

    /** 指定 key 是否存在。 */
    public boolean has(String key) {
        return resolve(key) != null;
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = resolve(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0d;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return defaultValue;
    }

    public double getDouble(String key) {
        return getDouble(key, 0.0d);
    }

    public double getDouble(String key, double defaultValue) {
        Object value = resolve(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1.0d : 0.0d;
        }
        return defaultValue;
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int defaultValue) {
        Object value = resolve(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defaultValue) {
        Object value = resolve(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String) {
            return (String) value;
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // 内部：按点号路径解析嵌套 key
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Object resolve(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        Map<String, Object> current = root;
        String[] parts = key.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            Object value = current.get(parts[i]);
            if (value == null) {
                return null;
            }
            if (i == parts.length - 1) {
                return value;
            }
            if (!(value instanceof Map)) {
                return null; // 中间节点不是对象，路径无效
            }
            current = (Map<String, Object>) value;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 内部：注释剔除
    // ------------------------------------------------------------------

    /**
     * 移除 JSONC 中的注释。
     * 会正确跟踪字符串状态，避免误删字符串内的 "//" 或 "/*"。
     * 块注释内的换行会被保留，以便错误定位行号仍然准确。
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inString = false;
        boolean escape = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = (i + 1 < src.length()) ? src.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++; // 跳过 '/'
                } else if (c == '\n') {
                    out.append(c);
                }
                continue;
            }

            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 内部：极简 JSON 解析器
    // ------------------------------------------------------------------

    /**
     * 递归下降的 JSON 解析器。
     * 数值统一解析为 Double，对象为 LinkedHashMap，数组为 List。
     */
    private static final class JsonParser {

        private final String src;
        private int pos;

        JsonParser(String src) {
            this.src = src;
            this.pos = 0;
        }

        /** 解析一个顶层对象，返回其键值映射。 */
        Map<String, Object> parseObject() {
            skipWhitespace();
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    break;
                }
                throw error("对象中缺少 ',' 或 '}'");
            }
            return map;
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<Object>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    break;
                }
                throw error("数组中缺少 ',' 或 ']'");
            }
            return list;
        }

        private Object parseValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expectWord("true");
                    return Boolean.TRUE;
                case 'f':
                    expectWord("false");
                    return Boolean.FALSE;
                case 'n':
                    expectWord("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw error("字符串未闭合");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw error("转义序列不完整");
                    }
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > src.length()) {
                                throw error("\\u 转义不完整");
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw error("非法 \\u 转义: " + hex);
                            }
                            break;
                        default:
                            throw error("未知转义字符: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Double parseNumber() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                boolean numeric = (c >= '0' && c <= '9')
                        || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
                if (!numeric) {
                    break;
                }
                pos++;
            }
            if (start == pos) {
                throw error("期望一个值");
            }
            String num = src.substring(start, pos);
            try {
                return Double.valueOf(num);
            } catch (NumberFormatException e) {
                throw error("非法数字: " + num);
            }
        }

        // -------------------- 词法辅助 --------------------

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (pos >= src.length()) {
                throw error("输入意外结束");
            }
            return src.charAt(pos);
        }

        private char next() {
            if (pos >= src.length()) {
                throw error("输入意外结束");
            }
            return src.charAt(pos++);
        }

        private void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw error("期望 '" + expected + "' 但得到 '" + c + "'");
            }
        }

        private void expectWord(String word) {
            for (int i = 0; i < word.length(); i++) {
                if (pos >= src.length() || src.charAt(pos) != word.charAt(i)) {
                    throw error("期望 '" + word + "'");
                }
                pos++;
            }
        }

        private IllegalArgumentException error(String message) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < pos && i < src.length(); i++) {
                if (src.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new IllegalArgumentException(
                    "JSON 解析错误 (行 " + line + ", 列 " + column + "): " + message);
        }
    }
}
