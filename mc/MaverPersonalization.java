import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;
import java.util.Properties;

/** Shared ClickGUI theme and HUD splash settings, stored with feature profiles. */
public final class MaverPersonalization {
    public static final int DEFAULT_COLOR = 0xFFB3B9F0;
    public static final String DEFAULT_TEXT = "Maver!";
    public static final int MAX_TEXT_LENGTH = 64;
    public static final int MAX_TAUNT_LENGTH = 100;
    public static final String DEFAULT_TAUNT = "gg";
    private static boolean loaded;
    private static int color = DEFAULT_COLOR;
    private static String text = DEFAULT_TEXT;
    private static boolean tauntEnabled;
    private static String tauntText = DEFAULT_TAUNT;

    private MaverPersonalization() {}

    private static void ensureLoaded() {
        if (loaded) return;
        Properties values = new Properties();
        File file = new File(System.getProperty("openmaver.features", "config/openmaver.properties"));
        if (file.isFile()) {
            try (FileInputStream input = new FileInputStream(file)) {
                values.load(input);
            } catch (Exception error) {
                System.err.println("[OpenMaver] Cannot read personalization: " + error);
            }
        }
        load(values);
    }

    public static void load(Properties values) {
        loaded = true;
        color = parseColor(values.getProperty("ui.theme.color"), DEFAULT_COLOR);
        text = cleanText(values.getProperty("hud.splash.text", DEFAULT_TEXT));
        tauntEnabled = Boolean.parseBoolean(values.getProperty("personalization.taunt.enabled", "false"));
        tauntText = cleanTaunt(values.getProperty("personalization.taunt.text", DEFAULT_TAUNT));
    }

    public static String save() {
        ensureLoaded();
        // Properties.load(InputStream) uses Latin-1; escape Unicode and leading spaces.
        return "ui.theme.color=" + getHexColor() + "\nhud.splash.text=" + escape(text) + "\n"
                + "personalization.taunt.enabled=" + tauntEnabled + "\n"
                + "personalization.taunt.text=" + escape(tauntText) + "\n";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') escaped.append("\\\\");
            else if (c == ' ') escaped.append("\\ ");
            else if (c < 0x20 || c > 0x7E) {
                escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else escaped.append(c);
        }
        return escaped.toString();
    }

    public static boolean isTauntEnabled() { ensureLoaded(); return tauntEnabled; }
    public static void setTauntEnabled(boolean value) {
        ensureLoaded();
        tauntEnabled = value;
        OpenMaverFeatures.markDirty();
    }
    public static String getTauntText() { ensureLoaded(); return tauntText; }
    public static void setTauntText(String value) {
        ensureLoaded();
        tauntText = cleanTaunt(value);
        OpenMaverFeatures.markDirty();
    }
    static String cleanTaunt(String value) {
        if (value == null) return DEFAULT_TAUNT;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == 0xA7 || cp >= 0xD800 && cp <= 0xDFFF) continue;
            if (result.length() + Character.charCount(cp) > MAX_TAUNT_LENGTH) break;
            result.appendCodePoint(cp);
        }
        return result.toString();
    }

    public static int getColor() { ensureLoaded(); return color; }
    public static String getHexColor() {
        return String.format(Locale.ROOT, "#%06X", getColor() & 0xFFFFFF);
    }
    public static void setColor(int value) {
        ensureLoaded();
        color = 0xFF000000 | (value & 0xFFFFFF);
        OpenMaverFeatures.markDirty();
    }
    public static boolean setHexColor(String value) {
        int parsed = parseColor(value, 0);
        if (parsed == 0) return false;
        setColor(parsed);
        return true;
    }
    private static int parseColor(String value, int fallback) {
        if (value == null) return fallback;
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (!hex.matches("[0-9a-fA-F]{6}")) return fallback;
        return 0xFF000000 | Integer.parseInt(hex, 16);
    }
    public static int getHoverColor() { return mix(getColor(), 0xFFFFFFFF, 0.22F); }
    public static int getTextColor() {
        int rgb = getColor();
        double luminance = 0.2126 * linear(rgb >> 16 & 255)
                + 0.7152 * linear(rgb >> 8 & 255) + 0.0722 * linear(rgb & 255);
        return luminance > 0.179 ? 0xFF171923 : 0xFFFFFFFF;
    }
    private static double linear(int value) {
        double s = value / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
    public static int getDetailColor() { return mix(getColor(), 0xFFFFFFFF, 0.35F); }
    private static int mix(int a, int b, float t) {
        int r = Math.round((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = Math.round((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
    public static String getText() { ensureLoaded(); return text; }
    public static void setText(String value) {
        ensureLoaded();
        text = cleanText(value);
        OpenMaverFeatures.markDirty();
    }
    private static String cleanText(String value) {
        if (value == null) return DEFAULT_TEXT;
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (int i = 0; i < value.length() && count < MAX_TEXT_LENGTH;) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == 0xA7) continue;
            result.appendCodePoint(cp);
            count++;
        }
        return result.toString();
    }
}
