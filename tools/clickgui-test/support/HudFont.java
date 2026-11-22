import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.FontRenderer;

/** Headless font fixture records text while production layout and hit testing run. */
public final class HudFont {
    public static final List<String> text = new ArrayList<String>();
    public static void useHudFont() {}
    public static int getStringWidth(FontRenderer font, String value, float scale) {
        return value == null ? 0 : Math.round(value.length() * 6 * scale);
    }
    public static void drawString(FontRenderer font, String value, int x, int y, int color, float scale) {
        text.add(value);
    }
}
