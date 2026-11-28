import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.FontRenderer;

public final class HudFont {
    static final List<String> text = new ArrayList<String>();
    public static void useMenuFont() { }
    public static int getStringWidth(FontRenderer font, String text, float size) {
        return text == null ? 0 : Math.round(text.length() * 6 * size);
    }
    public static void drawString(FontRenderer font, String value, int x, int y, int color, float size) {
        text.add(value);
    }
}
