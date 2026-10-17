import module.Module;
import module.ModuleManager;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

/**
 * 游戏内 ClickGUI（Forge 1.8.9 / {@link GuiScreen}）。
 *
 * <p>按 RSHIFT 开关。中央一个扁平风格面板：顶部标题 + 「注入成功！！」，
 * 下面每个模块一行（悬停高亮，点击开关），底部操作提示。
 *
 * <p>为了「纯注入」也能用，本类不依赖 GuiButton，所有行自绘（{@code Gui.drawRect} /
 * {@code drawString} / {@code drawCenteredString}），并通过 {@code mouseClicked} 处理点击。
 *
 * <p>注意：这些是 MCP 名，注入前会由 {@code tools/McpReobf} 改成 SRG 名。
 */
@SideOnly(Side.CLIENT)
public final class GuiOpenMaverClickGui extends GuiScreen {

    private static final int PANEL_W = 260;
    private static final int HEADER_H = 46;
    private static final int ROW_H = 20;
    private static final int FOOTER_H = 22;

    private static final int COLOR_PANEL = 0xE6101014;
    private static final int COLOR_HEADER = 0xF01A1A22;
    private static final int COLOR_BORDER = 0xFF55FF55;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_SUB = 0xFF55FF55;
    private static final int COLOR_HOVER = 0x28FFFFFF;
    private static final int COLOR_ROW = 0x14000000;
    private static final int COLOR_ON = 0xFF55FF55;
    private static final int COLOR_OFF = 0xFF777777;

    /** 模块快照。 */
    private List<Module> modules;

    private int panelX;
    private int panelY;
    private int panelH;

    /** 出错后停用绘制，避免崩溃。 */
    private boolean failed = false;

    @Override
    public void initGui() {
        this.modules = ModuleManager.getModules();
        this.panelH = HEADER_H + modules.size() * ROW_H + FOOTER_H;
        this.panelX = (readIntField(this, "width", "field_146294_l") - PANEL_W) / 2;
        this.panelY = (readIntField(this, "height", "field_146295_m") - panelH) / 2;
        Object buttons = readField(this, "buttonList", "field_146292_n");
        if (buttons instanceof List) {
            ((List<?>) buttons).clear();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (failed) {
            return;
        }
        try {
            drawPanel(mouseX, mouseY);
        } catch (Throwable t) {
            failed = true;
            System.out.println("[OpenMaver] ClickGUI 绘制出错，已停用: " + t);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawPanel(int mouseX, int mouseY) {
        this.drawDefaultBackground();

        final int x = this.panelX;
        final int y = this.panelY;
        final int w = PANEL_W;
        final int h = this.panelH;

        // 面板本体 + 顶部高亮条
        Gui.drawRect(x, y, x + w, y + h, COLOR_PANEL);
        Gui.drawRect(x, y, x + w, y + 2, COLOR_BORDER);

        FontRenderer font = (FontRenderer) readField(this, "fontRendererObj", "field_146289_q");

        // 标题栏
        this.drawString(font, "OpenMaver", x + 10, y + 9, COLOR_TEXT);
        String ver = "v" + OpenMaver.VERSION;
        this.drawString(font, ver, x + w - 10 - font.getStringWidth(ver), y + 9, COLOR_OFF);
        this.drawCenteredString(font, "注入成功！！", x + w / 2, y + 27, COLOR_SUB);

        // 模块行
        int rowY = y + HEADER_H;
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_H;

            Gui.drawRect(x + 1, rowY, x + w - 1, rowY + ROW_H - 1, hover ? COLOR_HOVER : COLOR_ROW);

            boolean on = module.isEnabled();
            this.drawString(font, module.getName(), x + 12, rowY + 6, on ? COLOR_TEXT : COLOR_OFF);

            String status = on ? "ON" : "OFF";
            this.drawString(font, status, x + w - 12 - font.getStringWidth(status), rowY + 6,
                    on ? COLOR_ON : COLOR_OFF);
            rowY += ROW_H;
        }

        // 底部提示
        Gui.drawRect(x, y + h - FOOTER_H, x + w, y + h - FOOTER_H + 1, 0x40FFFFFF);
        this.drawCenteredString(font, "RSHIFT / ESC 关闭   点击开关模块", x + w / 2, y + h - 15, COLOR_OFF);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        try {
            if (mouseButton == 0 && this.modules != null) {
                int rowY = this.panelY + HEADER_H;
                for (int i = 0; i < this.modules.size(); i++) {
                    boolean inside = mouseX >= this.panelX && mouseX < this.panelX + PANEL_W
                            && mouseY >= rowY && mouseY < rowY + ROW_H;
                    if (inside) {
                        Module module = this.modules.get(i);
                        module.setEnabled(!module.isEnabled());
                        return;
                    }
                    rowY += ROW_H;
                }
            }
            super.mouseClicked(mouseX, mouseY, mouseButton);
        } catch (Throwable t) {
            failed = true;
            System.out.println("[OpenMaver] ClickGUI 点击处理出错: " + t);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RSHIFT) {
            ((net.minecraft.client.Minecraft) readField(this, "mc", "field_146297_k")).displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static Object readField(Object target, String... names) {
        Class<?> type = target.getClass();
        for (String name : names) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Try the next mapping or inherited fields below.
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                String n = field.getType().getName();
                if ((names[0].equals("fontRendererObj") && n.equals("net.minecraft.client.gui.FontRenderer"))
                        || (names[0].equals("mc") && n.equals("net.minecraft.client.Minecraft"))
                        || (names[0].equals("buttonList") && n.equals("java.util.List"))) {
                    try {
                        field.setAccessible(true);
                        return field.get(target);
                    } catch (IllegalAccessException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static int readIntField(Object target, String... names) {
        Object value = readField(target, names);
        return value instanceof Integer ? ((Integer) value).intValue() : 0;
    }
}
