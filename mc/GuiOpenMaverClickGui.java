import module.Module;
import module.ModuleManager;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 游戏内 ClickGUI（Forge 1.8.9 / {@link GuiScreen}）。
 *
 * <p>视觉对应 {@code design/clickgui.html}：深色半透明面板、青蓝描边、左侧分类栏、
 * 顶部搜索与筛选、模块行 + 可展开设置区。全部由 {@link Gui#drawRect} 与立即模式
 * 线段自绘，不依赖 GuiButton / 纹理资源，因此运行期注入也能用。
 *
 * <p>字体：模块名 / 分类名用打包的 Open Sans（{@link OpenSansFont}），
 * 微型标签、数值、键帽用 Minecraft 自带字体，形成第二层级（数值天然接近等宽）。
 *
 * <p>真实数据：模块来自 {@link ModuleManager}，Aim Assist / Player ESP 来自
 * {@link OpenMaverFeatures}；两者都可以在展开区直接调参并持久化。
 *
 * <p>注意：这些是 MCP 名，注入前会由 {@code tools/McpReobf} 改成 SRG 名。
 */
@SideOnly(Side.CLIENT)
public final class GuiOpenMaverClickGui extends GuiScreen {

    // ------------------------------------------------------------------
    // 布局（GUI 单位，最终按屏幕缩放）
    // ------------------------------------------------------------------

    private static final int PANEL_MAX_W = 880;
    private static final int PANEL_MAX_H = 380;
    private static final int HEADER_H = 64;
    private static final int TOOLS_H = 32;
    private static final int FOOTER_H = 26;
    private static final int ROW_H = 34;
    private static final int ROW_GAP = 5;
    private static final int LIST_PAD = 10;
    private static final int GRID_COLS = 3;
    private static final int GRID_GAP = 10;
    private static final int CONTROL_H = 30;
    private static final int CONTROL_ROW_GAP = 8;
    private static final int SETTINGS_PAD = 8;

    private static final int RADIUS = 8;
    private static final int CARD_RADIUS = 6;

    // ------------------------------------------------------------------
    // 配色
    // ------------------------------------------------------------------

    private static final int TEXT = 0xFFEEF3FB;
    private static final int TEXT_2 = 0xFFA6B0C0;
    private static final int MUTED = 0xFF6B7586;
    private static final int FAINT = 0xFF4E5765;
    private static final int CYAN = 0xFFC4CAD2;
    private static final int GREEN = 0xFF9EA6B1;

    private static final int PANEL_FILL = 0xF20D0F12;
    private static final int PANEL_BORDER = 0xFF25292F;
    private static final int RAIL_TINT = 0x10000000;

    private static final int LINE = 0x12FFFFFF;
    private static final int CARD_FILL = 0xFF14171B;
    private static final int CARD_HOVER = 0xFF1B1F24;
    private static final int CARD_BORDER = 0xFF20242A;
    private static final int FIELD_FILL = 0x33000000;
    private static final int FIELD_BORDER = 0x14FFFFFF;

    private static final int BTN_FILL = 0x33000000;
    private static final int SLIDER_TRACK = 0x1AFFFFFF;
    private static final int SLIDER_VALUE = 0xFFC4CAD2;
    private static final int KEYCAP_TEXT = 0xFFA9B0BA;
    private static final float MIN_LOGO_SCALE = 0.35F;
    private static final float MAX_LOGO_SCALE = 1.25F;
    private static final float LOGO_SCALE_STEP = 0.05F;

    // ------------------------------------------------------------------
    // 分类
    // ------------------------------------------------------------------

    private static final Category[] CATEGORIES = {
        new Category("combat", "Combat", "Target acquisition, reach and attack timing.", 0xFFC4CAD2),
        new Category("movement", "Movement", "Velocity, sprint and travel assistance.", 0xFFC4CAD2),
        new Category("render", "Render", "ESP, overlays and visual clarity.", 0xFFC4CAD2),
        new Category("player", "Player", "Inventory, tools and survival helpers.", 0xFFC4CAD2),
        new Category("misc", "Misc", "Utility, exploit and quality-of-life.", 0xFFC4CAD2)
    };

    private static final String[] ESP_COLOR_NAMES = {"Orange", "Cyan", "Violet", "Red"};
    private static final String[] AIM_MODES = {"Smooth", "Instant"};
    private static final String[] AIM_TARGETS = {"Head", "Chest", "Body"};

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private final GuiScreen returnScreen;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final Set<String> expanded = new HashSet<String>();

    private String category = "combat";
    private String searchQuery = "";
    private boolean searchFocused;
    private int filter = 0; // 0 all, 1 enabled, 2 disabled
    private boolean sortAscending = true;

    private int scroll;
    private float scrollVisual;
    private long lastAnimationAt;
    private float openProgress;
    private int lastMouseX;
    private int lastMouseY;
    private long openedAt = System.currentTimeMillis();

    private Entry dragEntry;
    private Setting dragSetting;
    private int dragX;
    private int dragW;
    private boolean panelDragging;
    private boolean logoSizeDragging;
    private float logoScale = 1.00F;
    private int panelDragOffsetX;
    private int panelDragOffsetY;

    private Entry selectEntry;
    private Setting selectSetting;
    private int selectFieldX;
    private int selectFieldY;
    private int selectFieldW;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int railW;
    private int mainX;
    private int mainW;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int searchX;
    private int searchY;
    private int searchW;
    private int searchH;

    /** 出错后停用绘制，避免崩溃。 */
    private boolean failed;
    private boolean drawLogged;

    public GuiOpenMaverClickGui(GuiScreen returnScreen) {
        this.returnScreen = returnScreen;
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    public void initGui() {
        super.initGui();
        this.openedAt = System.currentTimeMillis();
        this.lastAnimationAt = this.openedAt;
        this.openProgress = 0.0F;
        Keyboard.enableRepeatEvents(true);
        layout();
        buildEntries();

        // 默认展开 Aim Assist，打开即可看到设置控件
        expanded.clear();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if ("aim".equals(entry.featureId)) {
                expanded.add(entry.id);
                break;
            }
        }

        // 默认选中第一个有内容的分类
        if (!hasEntries(category)) {
            for (int i = 0; i < CATEGORIES.length; i++) {
                if (hasEntries(CATEGORIES[i].id)) {
                    category = CATEGORIES[i].id;
                    break;
                }
            }
        }

        this.buttonList.clear();
        System.err.println("[OpenMaver] ClickGUI initialized: " + this.width + "x" + this.height
                + ", entries=" + entries.size());
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (failed) {
            return;
        }
        long now = System.currentTimeMillis();
        float frameDelta = Math.min(50.0F, Math.max(0.0F, now - lastAnimationAt));
        lastAnimationAt = now;
        float openTarget = 1.0F;
        openProgress = approach(openProgress, openTarget, frameDelta, 150.0F);
        scrollVisual = approach(scrollVisual, scroll, frameDelta, 180.0F);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        try {
            if (!drawLogged) {
                drawLogged = true;
                System.err.println("[OpenMaver] ClickGUI first draw");
            }
            if (dragSetting != null) {
                if (Mouse.isButtonDown(0)) {
                    updateDrag(mouseX);
                } else {
                    dragSetting = null;
                    dragEntry = null;
                    OpenMaverFeatures.flushSettings();
                }
            }
            if (panelDragging) {
                if (Mouse.isButtonDown(0)) {
                    movePanelTo(mouseX - panelDragOffsetX, mouseY - panelDragOffsetY);
                } else {
                    panelDragging = false;
                }
            }
            if (logoSizeDragging) {
                if (Mouse.isButtonDown(0)) {
                    updateLogoSize(mouseX);
                } else {
                    logoSizeDragging = false;
                }
            }
            drawGui();
        } catch (Throwable t) {
            failed = true;
            System.out.println("[OpenMaver] ClickGUI 绘制出错，已停用: " + t);
            t.printStackTrace();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void layout() {
        panelW = Math.min(this.width - 32, PANEL_MAX_W);
        panelH = Math.min(this.height - 32, PANEL_MAX_H);
        panelW = Math.max(320, panelW);
        panelH = Math.max(240, panelH);
        panelW = Math.min(panelW, this.width - 8);
        panelH = Math.min(panelH, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        railW = Math.max(120, Math.min(176, panelW * 19 / 100));
        mainX = panelX + railW;
        mainW = panelW - railW;

        listX = mainX + 16;
        listW = mainW - 32;
        listY = panelY + HEADER_H + TOOLS_H;
        listH = panelH - HEADER_H - TOOLS_H - FOOTER_H;

        searchW = Math.min(196, Math.max(120, mainW / 3));
        searchH = 22;
        searchX = mainX + mainW - 16 - searchW;
        searchY = panelY + 19;
    }

    private void buildEntries() {
        entries.clear();
        List<Module> modules = ModuleManager.getModules();
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            String cat = categoryOf(module.getConfigKey());
            Entry entry = new Entry(module, cat);
            if ("AutoSprint".equals(module.getName())) {
                addSprintSettings(entry);
            }
            entries.add(entry);
        }

        Entry aim = new Entry("aim", "Aim Assist", "combat");
        addAimSettings(aim);
        entries.add(aim);

        Entry esp = new Entry("esp", "Player ESP", "render");
        addEspSettings(esp);
        entries.add(esp);
    }

    private static String categoryOf(String configKey) {
        if (configKey != null) {
            int dot = configKey.indexOf('.');
            String prefix = dot > 0 ? configKey.substring(0, dot) : configKey;
            for (int i = 0; i < CATEGORIES.length; i++) {
                if (CATEGORIES[i].id.equals(prefix)) {
                    return prefix;
                }
            }
        }
        return "misc";
    }

    private boolean hasEntries(String cat) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).category.equals(cat)) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 设置定义（绑定到真实功能）
    // ==================================================================

    private void addAimSettings(Entry entry) {
        entry.settings.add(Setting.slider("RANGE", 3.0F, 12.0F, 0.5F, 1, " m", new Binding() {
            public double get() { return OpenMaverFeatures.getAimRange(); }
            public void set(double value) { OpenMaverFeatures.setAimRange((float) value); }
        }));
        entry.settings.add(Setting.slider("SMOOTHING", 0.05F, 1.0F, 0.05F, 2, "", new Binding() {
            public double get() { return OpenMaverFeatures.getAimSpeed(); }
            public void set(double value) { OpenMaverFeatures.setAimSpeed((float) value); }
        }));
        entry.settings.add(Setting.select("MODE", AIM_MODES, new Binding() {
            public double get() { return OpenMaverFeatures.getAimMode(); }
            public void set(double value) { OpenMaverFeatures.setAimMode((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("FOV", 30.0F, 180.0F, 5.0F, 0, "", new Binding() {
            public double get() { return OpenMaverFeatures.getAimFov(); }
            public void set(double value) { OpenMaverFeatures.setAimFov((float) value); }
        }));
        entry.settings.add(Setting.select("TARGET", AIM_TARGETS, new Binding() {
            public double get() { return OpenMaverFeatures.getAimTarget(); }
            public void set(double value) { OpenMaverFeatures.setAimTarget((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("REQUIRE CLICK", new Binding() {
            public double get() { return OpenMaverFeatures.isAimRequireClick() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setAimRequireClick(value > 0.5D); }
        }));
    }

    private void addEspSettings(Entry entry) {
        entry.settings.add(Setting.slider("RANGE", 16.0F, 128.0F, 4.0F, 0, " m", new Binding() {
            public double get() { return OpenMaverFeatures.getEspRange(); }
            public void set(double value) { OpenMaverFeatures.setEspRange((float) value); }
        }));
        entry.settings.add(Setting.slider("THICKNESS", 0.5F, 4.0F, 0.1F, 1, "", new Binding() {
            public double get() { return OpenMaverFeatures.getEspThickness(); }
            public void set(double value) { OpenMaverFeatures.setEspThickness((float) value); }
        }));
        entry.settings.add(Setting.slider("OPACITY", 0.2F, 1.0F, 0.05F, 2, "", new Binding() {
            public double get() { return OpenMaverFeatures.getEspOpacity(); }
            public void set(double value) { OpenMaverFeatures.setEspOpacity((float) value); }
        }));
        entry.settings.add(Setting.select("COLOR", ESP_COLOR_NAMES, new Binding() {
            public double get() { return OpenMaverFeatures.getEspColor(); }
            public void set(double value) { OpenMaverFeatures.setEspColor((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("PLAYERS ONLY", new Binding() {
            public double get() { return OpenMaverFeatures.isEspPlayersOnly() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setEspPlayersOnly(value > 0.5D); }
        }));
    }

    private void addSprintSettings(Entry entry) {
        entry.settings.add(Setting.check("ONLY FORWARD", new Binding() {
            public double get() { return OpenMaverFeatures.isSprintOnlyForward() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setSprintOnlyForward(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("PAUSE SNEAK", new Binding() {
            public double get() { return OpenMaverFeatures.isSprintPauseSneak() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setSprintPauseSneak(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("PAUSE ITEM", new Binding() {
            public double get() { return OpenMaverFeatures.isSprintPauseItem() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setSprintPauseItem(value > 0.5D); }
        }));
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    private void drawGui() {
        // 不画 MC 的泥土底：直接压暗实时世界，面板的半透明才会像玻璃。
        float reveal = easeOutCubic(openProgress);
        GlColor.restore();
        GL11.glPushMatrix();
        GL11.glTranslatef(0.0F, (1.0F - reveal) * 7.0F, 0.0F);
        Gui.drawRect(0, 0, this.width, this.height, withAlpha(0xFF000000,
                Math.round(0x66 * reveal)));

        // 面板阴影 + 霓虹描边 + 玻璃填充
        roundRect(panelX + 7, panelY + 10, panelW, panelH, RADIUS + 2, 0x12000000);
        roundRect(panelX + 4, panelY + 6, panelW, panelH, RADIUS + 1, 0x22000000);
        roundRect(panelX + 2, panelY + 3, panelW, panelH, RADIUS + 1, 0x30000000);
        roundRect(panelX, panelY, panelW, panelH, RADIUS, PANEL_BORDER);
        roundRect(panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                Math.max(0, RADIUS - 1), PANEL_FILL);

        // 顶部高光
        Gui.drawRect(panelX + 20, panelY + 1, panelX + panelW - 20, panelY + 2, 0x55D8DDE3);
        Gui.drawRect(panelX + 1, panelY + 2, panelX + 2, panelY + panelH - 3,
                0x16FFFFFF);

        // 左侧分类栏
        Gui.drawRect(panelX + 1, panelY + 1, panelX + railW, panelY + panelH - 1, RAIL_TINT);
        Gui.drawRect(panelX + railW, panelY + 1, panelX + railW + 1, panelY + panelH - 1, LINE);

        FontRenderer font = this.mc.fontRendererObj;
        drawRail(font);
        drawHeader(font);
        drawTools(font);
        drawList(font);
        drawFooter(font);
        drawSelectPopup(font);
        GL11.glPopMatrix();
    }

    // ------------------------------------------------------------------
    // 左栏
    // ------------------------------------------------------------------

    private void drawRail(FontRenderer font) {
        int x = panelX;
        int y = panelY;

        MaverLogo.draw(font, x + 18, y + 3, railW - 36, 32, logoScale, TEXT);

        micro(font, "LOGO SIZE", x + 14, y + 45, FAINT);
        String scaleText = String.format(Locale.ROOT, "%.2fx", logoScale);
        micro(font, scaleText, x + railW - 14 - microWidth(font, scaleText), y + 45,
                TEXT_2);
        int sliderX = x + 14;
        int sliderW = railW - 28;
        int sliderY = y + 59;
        Gui.drawRect(sliderX, sliderY, sliderX + sliderW, sliderY + 3, SLIDER_TRACK);
        float logoSizePercent = (logoScale - MIN_LOGO_SCALE)
                / (MAX_LOGO_SCALE - MIN_LOGO_SCALE);
        int sliderFill = Math.round((sliderW - 1) * logoSizePercent);
        Gui.drawRect(sliderX, sliderY, sliderX + sliderFill, sliderY + 3, CYAN);
        int thumbX = sliderX + sliderFill;
        fillCircle(thumbX, sliderY + 1, 3, 0xFFEAFBFF);
        fillCircle(thumbX, sliderY + 1, 1, CYAN);

        micro(font, "CATEGORIES", x + 18, y + 77, FAINT);
        micro(font, "05", x + railW - 22, y + 77, 0xFF3F4754);

        int navY = y + 90;
        for (int i = 0; i < CATEGORIES.length; i++) {
            Category cat = CATEGORIES[i];
            boolean active = category.equals(cat.id);
            int itemY = navY + i * 30;
            int itemH = 27;

            if (active) {
                roundRect(x, itemY, railW - 8, itemH, 5, withAlpha(cat.color, 0x2A));
                Gui.drawRect(x, itemY + 4, x + 2, itemY + itemH - 4, cat.color);
                // 竖条外发光
                Gui.drawRect(x + 2, itemY + 5, x + 3, itemY + itemH - 5,
                        withAlpha(cat.color, 0x55));
            } else if (isHovered(lastMouseX, lastMouseY, x + 4, itemY, railW - 12, itemH)) {
                roundRect(x + 4, itemY, railW - 12, itemH, 5, 0x0CFFFFFF);
            }

            int iconColor = active ? cat.color : 0xFF7D879A;
            drawCategoryIcon(cat.id, x + 24, itemY + 8, iconColor);
            os(font, cat.name, x + 40, itemY + 8, active ? 0xFFF2FAFF : TEXT_2);

            int count = countCategory(cat.id);
            String countText = count < 10 ? "0" + count : String.valueOf(count);
            micro(font, countText, x + railW - 26, itemY + 10,
                    active ? withAlpha(cat.color, 0xFF) : 0xFF596272);
        }

        // Profile 卡（高度不足时省略，避免和分类列表重叠）
        if (panelH >= 360) {
            int cardH = 58;
            int cardX = x + 12;
            int cardY = y + panelH - 22 - cardH;
            int cardW = railW - 24;
            roundRect(cardX, cardY, cardW, cardH, 6, 0x1AFFFFFF);
            roundRect(cardX + 1, cardY + 1, cardW - 2, cardH - 2, 5, 0x06FFFFFF);
            micro(font, "PROFILE", cardX + 9, cardY + 8, FAINT);
            micro(font, "SAVED", cardX + cardW - 9 - microWidth(font, "SAVED"),
                    cardY + 8, GREEN);
            Gui.drawRect(cardX + cardW - 15 - microWidth(font, "SAVED"), cardY + 10,
                    cardX + cardW - 12 - microWidth(font, "SAVED"), cardY + 13, GREEN);

            os(font, "default", cardX + 9, cardY + 19, TEXT);
            os(font, ".jsonc", cardX + 9 + osWidth(font, "default"), cardY + 19, FAINT);

            int barY = cardY + 35;
            Gui.drawRect(cardX + 9, barY, cardX + cardW - 9, barY + 2, 0x14FFFFFF);
            int enabled = enabledCount();
            int total = entries.size();
            int fill = total <= 0 ? 0 : (cardW - 18) * enabled / total;
            Gui.drawRect(cardX + 9, barY, cardX + 9 + fill, barY + 2, CYAN);
            if (fill > 0) {
                Gui.drawRect(cardX + 9 + Math.max(0, fill - 2), barY,
                        cardX + 9 + fill, barY + 2, 0x99C4CAD2);
            }

            micro(font, String.valueOf(enabled), cardX + 9, cardY + 43, 0xFFDCE7F5);
            micro(font, "active", cardX + 9 + microWidth(font, String.valueOf(enabled)) + 4,
                    cardY + 43, MUTED);
            String totalText = total + " total";
            micro(font, totalText, cardX + cardW - 9 - microWidth(font, totalText),
                    cardY + 43, 0xFF596272);
        }

        // 栏底部信息
        if (panelH >= 300) {
            micro(font, "FORGE 1.8.9", x + 14, y + panelH - 12, 0xFF454E5C);
            String build = "v2.0.0";
            micro(font, build, x + railW - 16 - microWidth(font, build), y + panelH - 12,
                    0xFF4A5361);
        }
    }

    // ------------------------------------------------------------------
    // 顶部
    // ------------------------------------------------------------------

    private void drawHeader(FontRenderer font) {
        Category cat = currentCategory();
        int x = mainX + 18;
        int y = panelY;

        micro(font, "MODULES", x, y + 10, FAINT);
        micro(font, "/", x + microWidth(font, "MODULES") + 6, y + 10, 0xFF39414E);
        micro(font, cat.name.toUpperCase(Locale.ROOT),
                x + microWidth(font, "MODULES") + 16, y + 10, 0xFF7C8798);

        String title = cat.name;
        osBold(font, title, x, y + 21, TEXT);
        int titleW = osBoldWidth(font, title);
        int chipX = x + titleW + 9;
        int count = countCategory(cat.id);
        String countText = String.valueOf(count);
        int chipW = Math.max(16, microWidth(font, countText) + 10);
        roundRect(chipX, y + 21, chipW, 11, 4, 0x14FFFFFF);
        micro(font, countText, chipX + (chipW - microWidth(font, countText)) / 2,
                y + 23, 0xFF6E7A8C);

        micro(font, ellipsizeMicro(font, cat.description, Math.max(80, searchX - x - 10)),
                x, y + 40, MUTED);

        // 搜索框
        boolean focus = searchFocused;
        roundRect(searchX, searchY, searchW, searchH, 5,
                focus ? withAlpha(CYAN, 0x3D) : FIELD_BORDER);
        roundRect(searchX + 1, searchY + 1, searchW - 2, searchH - 2, 4,
                focus ? 0x4D000000 : FIELD_FILL);
        drawSearchIcon(searchX + 7, searchY + 7, focus ? CYAN : 0xFF7C8798);

        String shown = searchQuery;
        if (shown.isEmpty()) {
            micro(font, ellipsizeMicro(font, "Search modules...", searchW - 30),
                    searchX + 20, searchY + 7, 0xFF5E6878);
            if (focus && ((System.currentTimeMillis() - openedAt) / 500L) % 2L == 0L) {
                Gui.drawRect(searchX + 20, searchY + 7, searchX + 21, searchY + 15, CYAN);
            }
        } else {
            int max = searchW - 26;
            micro(font, ellipsizeMicro(font, shown, max), searchX + 20, searchY + 7, TEXT_2);
            if (focus && ((System.currentTimeMillis() - openedAt) / 500L) % 2L == 0L) {
                int caretX = searchX + 20 + microWidth(font, ellipsizeMicro(font, shown, max));
                Gui.drawRect(caretX + 1, searchY + 7, caretX + 2, searchY + 15, CYAN);
            }
        }
        // 快捷键提示（空间不足时不画）
        if (!focus && searchQuery.isEmpty() && searchW >= 176) {
            micro(font, "CTRL K", searchX + searchW - 8 - microWidth(font, "CTRL K"),
                    searchY + 7, 0xFF4B5461);
        }
    }

    private void drawTools(FontRenderer font) {
        int y = panelY + HEADER_H;
        int tabY = y + 6;
        int tabH = 20;
        final int[] widths = {56, 74, 82};
        int tabsW = widths[0] + widths[1] + widths[2] + 6;
        roundRect(listX, tabY, tabsW, tabH, 5, BTN_FILL);
        String[] labels = {"ALL", "ENABLED", "DISABLED"};
        int[] counts = {
            countCategory(category),
            countCategoryEnabled(category),
            countCategory(category) - countCategoryEnabled(category)
        };
        int tx = listX + 3;
        for (int i = 0; i < 3; i++) {
            int tabW = widths[i];
            int ty = tabY + 3;
            boolean active = filter == i;
            if (active) {
                roundRect(tx, ty, tabW - 2, 14, 4, withAlpha(accent(), 0x22));
                Gui.drawRect(tx, ty + 3, tx + 1, ty + 11, withAlpha(accent(), 0xAA));
            }
            int textColor = active ? withAlpha(accent(), 0xFF) : 0xFF788395;
            micro(font, labels[i], tx + 7, ty + 4, textColor);
            String c = String.valueOf(counts[i]);
            micro(font, c, tx + tabW - 8 - microWidth(font, c), ty + 4,
                    active ? withAlpha(accent(), 0xBB) : 0xFF5F6A7B);
            tx += tabW;
        }

        String sortLabel = "SORT";
        micro(font, sortLabel, listX + listW - 104, tabY + 7, FAINT);
        micro(font, sortAscending ? "A-Z" : "Z-A", listX + listW - 80, tabY + 7, 0xFF9EAABB);
        drawChevron(listX + listW - 68, tabY + 11, false, MUTED);
        String items = visibleEntries().size() == 1 ? "1 ITEM" : visibleEntries().size() + " ITEMS";
        micro(font, items, listX + listW - microWidth(font, items), tabY + 7, 0xFF576170);
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    private void drawList(FontRenderer font) {
        List<Entry> visible = visibleEntries();
        int contentH = contentHeight(visible);
        int maxScroll = Math.max(0, contentH - listH);
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
        if (scroll < 0) {
            scroll = 0;
        }

        pushScissor(listX, listY, listW, listH);
        int y = listY + LIST_PAD - Math.round(scrollVisual);
        for (int i = 0; i < visible.size(); i++) {
            Entry entry = visible.get(i);
            int cardH = animatedEntryHeight(entry, System.currentTimeMillis());
            if (y + cardH >= listY && y <= listY + listH) {
                drawEntry(font, entry, y, cardH);
            }
            y += cardH + ROW_GAP;
        }

        if (visible.isEmpty()) {
            String message = searchQuery.isEmpty()
                    ? "No modules registered in this category"
                    : "No modules match \"" + searchQuery + "\"";
            micro(font, message, listX + (listW - microWidth(font, message)) / 2,
                    listY + listH / 2 - 4, 0xFF5A6474);
        }

        // 底部渐隐（可滚动时）
        if (maxScroll > 0) {
            int fadeH = 22;
            for (int i = 0; i < fadeH; i += 2) {
                Gui.drawRect(listX, listY + listH - fadeH + i, listX + listW,
                        listY + listH - fadeH + i + 2, (i * 4) << 24);
            }
        }
        popScissor();

        // 细滚动条
        if (maxScroll > 0) {
            int trackX = listX + listW - 2;
            int thumbH = Math.max(20, listH * listH / contentH);
            int thumbY = listY + (listH - thumbH) * scroll / maxScroll;
            Gui.drawRect(trackX, listY, trackX + 1, listY + listH, 0x0AFFFFFF);
            Gui.drawRect(trackX, thumbY, trackX + 1, thumbY + thumbH, 0x28FFFFFF);
        }
    }

    private void drawEntry(FontRenderer font, Entry entry, int y, int cardH) {
        int x = listX;
        int w = listW - 6;
        boolean open = expanded.contains(entry.id);
        float openProgress = getEntryProgress(entry, System.currentTimeMillis());
        boolean hover = isHovered(lastMouseX, lastMouseY, x, y, w, ROW_H);
        long now = System.currentTimeMillis();
        entry.hoverProgress = approach(entry.hoverProgress, hover ? 1.0F : 0.0F,
                Math.min(50.0F, now - entry.animationAt), 120.0F);
        entry.animationAt = now;
        boolean on = entry.isEnabled();
        entry.toggleProgress = approach(entry.toggleProgress, on ? 1.0F : 0.0F,
                Math.min(50.0F, now - entry.toggleAt), 140.0F);
        entry.toggleAt = now;
        float hoverProgress = entry.hoverProgress;

        int fill = blendColor(CARD_FILL, CARD_HOVER, hoverProgress);
        fill = blendColor(fill, 0xFF1A1D21, openProgress * 0.55F);
        int border = blendColor(CARD_BORDER, 0xFF343A42, hoverProgress * 0.7F);
        roundRect(x, y, w, cardH, CARD_RADIUS, border);
        roundRect(x + 1, y + 1, w - 2, cardH - 2, Math.max(0, CARD_RADIUS - 1), fill);

        // 开关圆点
        float toggleProgress = entry.toggleProgress;
        int dotX = x + 16;
        int dotY = y + 16;
        int accent = accentFor(entry);
        if (toggleProgress > 0.01F) {
            fillCircle(dotX, dotY, 6, withAlpha(accent, 0x18));
            fillCircle(dotX, dotY, Math.max(1, Math.round(2 + toggleProgress * 2)),
                    withAlpha(accent, Math.round(toggleProgress * 255.0F)));
        }
        if (toggleProgress < 0.99F) {
            ringCircle(dotX, dotY, 4, 0x66FFFFFF);
            fillCircle(dotX, dotY, 2, withAlpha(0xFFFFFFFF,
                    Math.round((1.0F - toggleProgress) * 0x38)));
        }

        os(font, ellipsizeOs(font, entry.name, w - 100), x + 32, y + 7,
                on ? TEXT : 0xFFB3BCCB);
        micro(font, ellipsizeMicro(font, entry.meta(), w - 100), x + 32, y + 21,
                on ? 0xFF6E7C8E : 0xFF596475);

        // 键帽
        String keybind = entry.keybind();
        int keyX = x + w - 48;
        if (keybind != null) {
            int keyW = Math.min(26, Math.max(15, microWidth(font, keybind) + 8));
            int kx = keyX + (15 - keyW) / 2;
            roundRect(kx, y + 10, keyW, 13, 3, 0x1FFFFFFF);
            micro(font, ellipsizeMicro(font, keybind, keyW - 6),
                    kx + (keyW - microWidth(font, ellipsizeMicro(font, keybind, keyW - 6))) / 2,
                    y + 13, KEYCAP_TEXT);
        } else {
            Gui.drawRect(keyX + 5, y + 16, keyX + 10, y + 17, 0xFF39414E);
        }

        drawChevron(x + w - 18, y + 17, open, MUTED);

        if (openProgress > 0.01F && !entry.settings.isEmpty()) {
            int settingsHeight = Math.round(settingsHeight(entry) * openProgress);
            pushScissor(x, y + ROW_H, w, settingsHeight + 4);
            drawSettings(font, entry, x, y, w);
            popScissor();
        }
    }

    private void drawSettings(FontRenderer font, Entry entry, int cardX, int cardY, int cardW) {
        int panelH = settingsHeight(entry);
        int x = cardX + 32;
        int y = cardY + ROW_H + 2;
        int w = cardW - 44;

        roundRect(x, y, w, panelH, 5, 0x40000000);
        roundRect(x + 1, y + 1, w - 2, panelH - 2, 4, 0x08FFFFFF);
        Gui.drawRect(x - 9, y + 3, x - 8, y + panelH - 3, withAlpha(accentFor(entry), 0x55));

        int innerX = x + SETTINGS_PAD;
        int innerY = y + SETTINGS_PAD;
        int cellW = (w - SETTINGS_PAD * 2 - GRID_GAP * (GRID_COLS - 1)) / GRID_COLS;

        for (int i = 0; i < entry.settings.size(); i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int cx = innerX + col * (cellW + GRID_GAP);
            int cy = innerY + row * (CONTROL_H + CONTROL_ROW_GAP);
            drawSetting(font, entry, entry.settings.get(i), cx, cy, cellW);
        }
    }

    private void drawSetting(FontRenderer font, Entry entry, Setting setting,
            int x, int y, int w) {
        int accent = accentFor(entry);
        if (setting.kind == Setting.SLIDER) {
            micro(font, setting.label, x, y, 0xFF616C7D);
            String value = setting.display();
            micro(font, value, x + w - microWidth(font, value), y, SLIDER_VALUE);

            int trackY = y + 15;
            Gui.drawRect(x, trackY, x + w, trackY + 3, SLIDER_TRACK);
            double pct = setting.percent();
            int fillW = (int) Math.round((w - 1) * pct);
            if (fillW > 0) {
                Gui.drawRect(x, trackY, x + fillW, trackY + 3, accent);
                Gui.drawRect(x, trackY, x + fillW, trackY + 1, withAlpha(0xFFFFFFFF, 0x22));
            }
            int thumbX = x + clampInt((int) Math.round((w - 1) * pct), 0, w - 1);
            fillCircle(thumbX, trackY + 1, 3, 0xFFEAFBFF);
            fillCircle(thumbX, trackY + 1, 1, accent);
        } else if (setting.kind == Setting.SELECT) {
            micro(font, setting.label, x, y, 0xFF616C7D);
            int fieldY = y + 9;
            boolean hovered = isHovered(lastMouseX, lastMouseY, x, fieldY, w, 16);
            roundRect(x, fieldY, w, 16, 4,
                    hovered ? withAlpha(0xFFC4CAD2, 0x55) : FIELD_BORDER);
            roundRect(x + 1, fieldY + 1, w - 2, 14, 3, FIELD_FILL);
            String value = setting.display();
            micro(font, ellipsizeMicro(font, value, w - 20), x + 5, fieldY + 4, 0xFFD3DCE9);
            drawChevron(x + w - 9, fieldY + 8, false, 0xFF677386);
        } else if (setting.kind == Setting.CHECK) {
            boolean checked = setting.checked();
            int boxX = x;
            int boxY = y + 9;
            if (checked) {
                roundRect(boxX, boxY, 9, 9, 2, accent);
                drawCheck(boxX + 2, boxY + 4, 0xFF06222D);
            } else {
                roundRect(boxX, boxY, 9, 9, 2, 0x45FFFFFF);
                roundRect(boxX + 1, boxY + 1, 7, 7, 1, 0x33000000);
            }
            micro(font, setting.label, boxX + 14, y + 10, checked ? 0xFFE4EEF9 : 0xFFAEB9C9);
        }
    }

    // ------------------------------------------------------------------
    // 底部
    // ------------------------------------------------------------------

    private void drawFooter(FontRenderer font) {
        int y = panelY + panelH - FOOTER_H;
        Gui.drawRect(mainX, y, panelX + panelW, y + 1, LINE);

        String left = String.valueOf(entries.size()) + " MODULES";
        micro(font, left, mainX + 18, y + 10, 0xFF545E6D);
        int dividerX = mainX + 18 + microWidth(font, left) + 6;
        Gui.drawRect(dividerX, y + 13, dividerX + 2, y + 15, 0xFF39414E);
        String on = enabledCount() + " ENABLED";
        micro(font, on, dividerX + 7, y + 10, 0xFF7E8A9C);

        int right = panelX + panelW - 18;
        int closeW = keycapWidth(font, "RSHIFT") + 6 + microWidth(font, "CLOSE");
        int expandW = keycapWidth(font, "RMB") + 6 + microWidth(font, "EXPAND");
        int toggleW = keycapWidth(font, "LMB") + 6 + microWidth(font, "TOGGLE");

        int totalW = toggleW + 14 + expandW;
        if (totalW + closeW + 20 < mainW - 170) {
            totalW += 14 + closeW;
        } else if (totalW > mainW - 170) {
            totalW = toggleW;
        }

        int cx = right - totalW;
        cx = drawHint(font, cx, y + 7, "LMB", "TOGGLE") + 14;
        if (totalW >= toggleW + 14 + expandW) {
            cx = drawHint(font, cx, y + 7, "RMB", "EXPAND") + 14;
            if (totalW >= toggleW + 14 + expandW + 14 + closeW) {
                drawHint(font, cx, y + 7, "RSHIFT", "CLOSE");
            }
        }
    }

    private int drawHint(FontRenderer font, int x, int y, String key, String label) {
        int kw = keycapWidth(font, key);
        roundRect(x, y, kw, 12, 3, 0x14FFFFFF);
        micro(font, key, x + (kw - microWidth(font, key)) / 2, y + 2, KEYCAP_TEXT);
        micro(font, label, x + kw + 6, y + 2, 0xFF545E6D);
        return x + kw + 6 + microWidth(font, label);
    }

    private int keycapWidth(FontRenderer font, String key) {
        return Math.max(15, microWidth(font, key) + 8);
    }

    // ------------------------------------------------------------------
    // 下拉弹层
    // ------------------------------------------------------------------

    private void drawSelectPopup(FontRenderer font) {
        if (selectSetting == null || selectSetting.options == null) {
            return;
        }
        int w = selectFieldW;
        int h = selectSetting.options.length * 18 + 6;
        int x = Math.min(selectFieldX, panelX + panelW - 8 - w);
        int y = selectFieldY + 18;
        if (y + h > panelY + panelH - 8) {
            y = selectFieldY - h - 2;
        }
        y = Math.max(panelY + 8, y);

        roundRect(x + 2, y + 3, w, h, 5, 0x50000000);
        roundRect(x, y, w, h, 5, withAlpha(accentFor(selectEntry), 0x66));
        roundRect(x + 1, y + 1, w - 2, h - 2, 4, 0xF20A0E16);
        int current = (int) Math.round(selectSetting.value());
        for (int i = 0; i < selectSetting.options.length; i++) {
            int rowY = y + 3 + i * 18;
            boolean active = i == current;
            boolean hovered = isHovered(lastMouseX, lastMouseY, x + 1, rowY, w - 2, 18);
            if (active) {
                Gui.drawRect(x + 1, rowY, x + w - 1, rowY + 18,
                        withAlpha(accentFor(selectEntry), 0x18));
            } else if (hovered) {
                Gui.drawRect(x + 1, rowY, x + w - 1, rowY + 18, 0x0FFFFFFF);
            }
            micro(font, selectSetting.options[i], x + 8, rowY + 5,
                    active ? 0xFFEAF6FF : TEXT_2);
            if (active) {
                fillCircle(x + w - 9, rowY + 9, 2,
                        accentFor(selectEntry));
            }
        }
    }

    private boolean handleSelectClick(int mouseX, int mouseY) {
        if (selectSetting == null || selectSetting.options == null) {
            return false;
        }
        int w = selectFieldW;
        int h = selectSetting.options.length * 18 + 6;
        int x = Math.min(selectFieldX, panelX + panelW - 8 - w);
        int y = selectFieldY + 18;
        if (y + h > panelY + panelH - 8) {
            y = selectFieldY - h - 2;
        }
        y = Math.max(panelY + 8, y);
        if (isHovered(mouseX, mouseY, x, y, w, h)) {
            int index = (mouseY - (y + 3)) / 18;
            if (index >= 0 && index < selectSetting.options.length) {
                selectSetting.binding.set(index);
            }
        }
        selectSetting = null;
        selectEntry = null;
        return true;
    }

    // ==================================================================
    // 输入
    // ==================================================================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        try {
            lastMouseX = mouseX;
            lastMouseY = mouseY;

            if (handleSelectClick(mouseX, mouseY)) {
                return;
            }

            if (mouseButton == 0 && isHovered(mouseX, mouseY,
                    panelX + 8, panelY + 42, railW - 16, 23)) {
                logoSizeDragging = true;
                searchFocused = false;
                updateLogoSize(mouseX);
                return;
            }

            if (mouseButton == 0 && isHovered(mouseX, mouseY,
                    panelX + 4, panelY + 4, railW - 8, 38)) {
                panelDragging = true;
                panelDragOffsetX = mouseX - panelX;
                panelDragOffsetY = mouseY - panelY;
                searchFocused = false;
                return;
            }

            if (isHovered(mouseX, mouseY, searchX, searchY, searchW, searchH)) {
                searchFocused = true;
                return;
            }
            searchFocused = false;

            if (!isHovered(mouseX, mouseY, panelX, panelY, panelW, panelH)) {
                super.mouseClicked(mouseX, mouseY, mouseButton);
                return;
            }

            // 分类栏
            int navY = panelY + 90;
            for (int i = 0; i < CATEGORIES.length; i++) {
                if (isHovered(mouseX, mouseY, panelX + 4, navY + i * 30, railW - 12, 27)) {
                    category = CATEGORIES[i].id;
                    filter = 0;
                    searchQuery = "";
                    searchFocused = false;
                    selectSetting = null;
                    scroll = 0;
                    return;
                }
            }

            // 分段筛选
            int tabY = panelY + HEADER_H + 9;
            final int[] widths = {56, 74, 82};
            int tx = listX + 3;
            for (int i = 0; i < 3; i++) {
                if (isHovered(mouseX, mouseY, tx, tabY, widths[i] - 2, 14)) {
                    filter = i;
                    selectSetting = null;
                    scroll = 0;
                    return;
                }
                tx += widths[i];
            }

            // 排序
            if (isHovered(mouseX, mouseY, listX + listW - 110, tabY - 2, 110, 18)) {
                sortAscending = !sortAscending;
                selectSetting = null;
                return;
            }

            // 列表
            if (isHovered(mouseX, mouseY, listX, listY, listW, listH)) {
                handleListClick(mouseX, mouseY, mouseButton);
                return;
            }
        } catch (Throwable t) {
            failed = true;
            System.out.println("[OpenMaver] ClickGUI 点击处理出错: " + t);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private void handleListClick(int mouseX, int mouseY, int button) {
        List<Entry> visible = visibleEntries();
        int y = listY + LIST_PAD - Math.round(scrollVisual);
        for (int i = 0; i < visible.size(); i++) {
            Entry entry = visible.get(i);
            int cardH = animatedEntryHeight(entry, System.currentTimeMillis());
            int x = listX;
            int w = listW - 6;
            if (mouseY >= y && mouseY < y + cardH && mouseX >= x && mouseX < x + w) {
                if (expanded.contains(entry.id) && mouseY >= y + ROW_H && !entry.settings.isEmpty()) {
                    if (handleSettingsClick(entry, x, y, w, mouseX, mouseY, button)) {
                        return;
                    }
                    return; // 点在设置面板空白处，不折叠
                }

                boolean onDot = mouseX >= x + 10 && mouseX < x + 24
                        && mouseY >= y + 8 && mouseY < y + 24;
                if (onDot && button == 0) {
                    entry.toggle();
                    return;
                }
                if (button == 1) {
                    if (!entry.settings.isEmpty()) {
                        toggleExpanded(entry);
                    }
                    return;
                }
                if (!entry.settings.isEmpty()) {
                    toggleExpanded(entry);
                } else {
                    entry.toggle();
                }
                return;
            }
            y += cardH + ROW_GAP;
        }
    }

    private boolean handleSettingsClick(Entry entry, int cardX, int cardY, int cardW,
            int mouseX, int mouseY, int button) {
        if (button != 0) {
            return false;
        }
        int panelH = settingsHeight(entry);
        int x = cardX + 32;
        int y = cardY + ROW_H + 2;
        int w = cardW - 44;
        if (!isHovered(mouseX, mouseY, x, y, w, panelH)) {
            return false;
        }

        int innerX = x + SETTINGS_PAD;
        int innerY = y + SETTINGS_PAD;
        int cellW = (w - SETTINGS_PAD * 2 - GRID_GAP * (GRID_COLS - 1)) / GRID_COLS;

        for (int i = 0; i < entry.settings.size(); i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int cx = innerX + col * (cellW + GRID_GAP);
            int cy = innerY + row * (CONTROL_H + CONTROL_ROW_GAP);
            Setting setting = entry.settings.get(i);

            if (setting.kind == Setting.SLIDER) {
                if (isHovered(mouseX, mouseY, cx, cy + 7, cellW, 16)) {
                    dragEntry = entry;
                    dragSetting = setting;
                    dragX = cx;
                    dragW = cellW;
                    updateDrag(mouseX);
                    return true;
                }
            } else if (isHovered(mouseX, mouseY, cx, cy, cellW, CONTROL_H)) {
                if (setting.kind == Setting.SELECT) {
                    selectEntry = entry;
                    selectSetting = setting;
                    selectFieldX = cx;
                    selectFieldY = cy + 9;
                    selectFieldW = cellW;
                } else if (setting.kind == Setting.CHECK) {
                    setting.toggle();
                }
                return true;
            }
        }
        return true;
    }

    private void updateDrag(int mouseX) {
        if (dragSetting == null || dragW <= 0) {
            return;
        }
        double pct = (mouseX - dragX) / (double) dragW;
        pct = Math.max(0.0D, Math.min(1.0D, pct));
        dragSetting.setPercent(pct);
    }

    private void updateLogoSize(int mouseX) {
        int sliderX = panelX + 14;
        int sliderW = railW - 28;
        if (sliderW <= 0) {
            return;
        }
        double percent = Math.max(0.0D, Math.min(1.0D,
                (mouseX - sliderX) / (double) sliderW));
        float raw = MIN_LOGO_SCALE + (MAX_LOGO_SCALE - MIN_LOGO_SCALE) * (float) percent;
        logoScale = Math.max(MIN_LOGO_SCALE,
                Math.min(MAX_LOGO_SCALE, Math.round(raw / LOGO_SCALE_STEP) * LOGO_SCALE_STEP));
    }

    private void toggleExpanded(Entry entry) {
        if (expanded.contains(entry.id)) {
            expanded.remove(entry.id);
        } else {
            expanded.add(entry.id);
        }
        selectSetting = null;
        selectEntry = null;
    }

    private float getEntryProgress(Entry entry, long now) {
        float target = expanded.contains(entry.id) ? 1.0F : 0.0F;
        float delta = Math.min(50.0F, Math.max(0.0F, now - entry.expandAt));
        entry.expandProgress = approach(entry.expandProgress, target, delta, 160.0F);
        entry.expandAt = now;
        return easeOutCubic(entry.expandProgress);
    }

    private void movePanelTo(int x, int y) {
        panelX = clampInt(x, 0, Math.max(0, width - panelW));
        panelY = clampInt(y, 0, Math.max(0, height - panelH));
        mainX = panelX + railW;
        listX = mainX + 16;
        listY = panelY + HEADER_H + TOOLS_H;
        searchX = mainX + mainW - 16 - searchW;
        searchY = panelY + 19;
    }

    @Override
    public void handleMouseInput() throws IOException {
        try {
            super.handleMouseInput();
            int wheel = Mouse.getEventDWheel();
            if (wheel == 0) {
                return;
            }
            if (isHovered(lastMouseX, lastMouseY, listX, listY, listW, listH)) {
                List<Entry> visible = visibleEntries();
                int max = Math.max(0, contentHeight(visible) - listH);
                scroll = Math.max(0, Math.min(max, scroll - Integer.signum(wheel) * 18));
                selectSetting = null;
            }
        } catch (Throwable t) {
            failed = true;
            System.out.println("[OpenMaver] ClickGUI 滚轮处理出错: " + t);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (selectSetting != null) {
                selectSetting = null;
                selectEntry = null;
                return;
            }
            OpenMaverFeatures.flushSettings();
            closeGui();
            return;
        }
        selectSetting = null;
        selectEntry = null;
        if ((keyCode == Keyboard.KEY_K)
                && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                    || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))) {
            searchFocused = true;
            return;
        }
        if (searchFocused) {
            if (keyCode == Keyboard.KEY_BACK) {
                if (!searchQuery.isEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                }
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN) {
                searchFocused = false;
                return;
            }
            if (typedChar >= 32 && typedChar != 127 && searchQuery.length() < 28) {
                searchQuery += typedChar;
            }
            return;
        }
        if (typedChar >= 32 && typedChar != 127) {
            searchFocused = true;
            if (searchQuery.length() < 28) {
                searchQuery += typedChar;
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        OpenMaverFeatures.flushSettings();
        super.onGuiClosed();
    }

    public void closeGui() {
        this.mc.displayGuiScreen(this.returnScreen);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ==================================================================
    // 数据 / 布局辅助
    // ==================================================================

    private List<Entry> visibleEntries() {
        List<Entry> out = new ArrayList<Entry>();
        String query = searchQuery.toLowerCase(Locale.ROOT);
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.category.equals(category)) {
                continue;
            }
            boolean on = entry.isEnabled();
            if (filter == 1 && !on) {
                continue;
            }
            if (filter == 2 && on) {
                continue;
            }
            if (!query.isEmpty() && !entry.name.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            out.add(entry);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int result = a.name.compareToIgnoreCase(b.name);
                return sortAscending ? result : -result;
            }
        });
        return out;
    }

    private int contentHeight(List<Entry> visible) {
        int height = LIST_PAD * 2;
        for (int i = 0; i < visible.size(); i++) {
            height += entryHeight(visible.get(i)) + ROW_GAP;
        }
        return height;
    }

    private int entryHeight(Entry entry) {
        if (!expanded.contains(entry.id) || entry.settings.isEmpty()) {
            return ROW_H;
        }
        return ROW_H + settingsHeight(entry) + 10;
    }

    private int animatedEntryHeight(Entry entry, long now) {
        if (entry.settings.isEmpty()) {
            return ROW_H;
        }
        return ROW_H + Math.round((settingsHeight(entry) + 10)
                * getEntryProgress(entry, now));
    }

    private int settingsHeight(Entry entry) {
        int rows = Math.max(1, (entry.settings.size() + GRID_COLS - 1) / GRID_COLS);
        return SETTINGS_PAD * 2 + rows * CONTROL_H + (rows - 1) * CONTROL_ROW_GAP;
    }

    private int countCategory(String cat) {
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).category.equals(cat)) {
                count++;
            }
        }
        return count;
    }

    private int countCategoryEnabled(String cat) {
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.category.equals(cat) && entry.isEnabled()) {
                count++;
            }
        }
        return count;
    }

    private int enabledCount() {
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).isEnabled()) {
                count++;
            }
        }
        return count;
    }

    private Category currentCategory() {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].id.equals(category)) {
                return CATEGORIES[i];
            }
        }
        return CATEGORIES[0];
    }

    private int accent() {
        return currentCategory().color;
    }

    private int accentFor(Entry entry) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].id.equals(entry.category)) {
                return CATEGORIES[i].color;
            }
        }
        return CYAN;
    }

    // ==================================================================
    // 文本
    // ==================================================================

    private static void os(FontRenderer font, String text, int x, int y, int color) {
        OpenSansFont.drawString(font, text, x, y, color, 0.92F);
    }

    private static void osBold(FontRenderer font, String text, int x, int y, int color) {
        OpenSansFont.drawString(font, "\u00a7l" + text, x, y, color, 1.32F);
    }

    private static int osWidth(FontRenderer font, String text) {
        return OpenSansFont.getStringWidth(font, text, 0.92F);
    }

    private static int osBoldWidth(FontRenderer font, String text) {
        return OpenSansFont.getStringWidth(font, "\u00a7l" + text, 1.32F);
    }

    private static void micro(FontRenderer font, String text, int x, int y, int color) {
        OpenSansFont.drawString(font, text, x, y, color, 0.72F);
    }

    private static int microWidth(FontRenderer font, String text) {
        return OpenSansFont.getStringWidth(font, text, 0.72F);
    }

    private static String ellipsizeMicro(FontRenderer font, String text, int maxWidth) {
        if (text == null || microWidth(font, text) <= maxWidth) {
            return text;
        }
        String value = text;
        while (value.length() > 3 && microWidth(font, value + "...") > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "...";
    }

    private static String ellipsizeOs(FontRenderer font, String text, int maxWidth) {
        if (text == null || osWidth(font, text) <= maxWidth) {
            return text;
        }
        String value = text;
        while (value.length() > 3 && osWidth(font, value + "...") > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "...";
    }

    // ==================================================================
    // 基础几何 / 图标
    // ==================================================================

    private static void roundRect(int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.min(radius, Math.min(w / 2, h / 2));
        if (r <= 0) {
            Gui.drawRect(x, y, x + w, y + h, color);
            return;
        }
        Gui.drawRect(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - (i + 0.5D);
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0D, r * r - dy * dy)));
            Gui.drawRect(x + inset, y + i, x + w - inset, y + i + 1, color);
            Gui.drawRect(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    private static void fillCircle(int cx, int cy, int radius, int color) {
        if (radius <= 0) {
            return;
        }
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.sqrt(radius * radius - dy * dy);
            Gui.drawRect(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private static void ringCircle(int cx, int cy, int radius, int color) {
        int steps = Math.max(18, radius * 12);
        for (int i = 0; i < steps; i++) {
            double angle = Math.PI * 2.0D * i / steps;
            int x = cx + (int) Math.round(Math.cos(angle) * radius);
            int y = cy + (int) Math.round(Math.sin(angle) * radius);
            Gui.drawRect(x, y, x + 1, y + 1, color);
        }
    }

    private static void drawChevron(int cx, int cy, boolean up, int color) {
        if (up) {
            line(cx - 3, cy + 1, cx, cy - 2, color);
            line(cx, cy - 2, cx + 3, cy + 1, color);
        } else {
            line(cx - 3, cy - 2, cx, cy + 1, color);
            line(cx, cy + 1, cx + 3, cy - 2, color);
        }
    }

    private static void drawCheck(int x, int y, int color) {
        line(x, y, x + 1, y + 1, color);
        line(x + 1, y + 1, x + 4, y - 2, color);
    }

    private static void drawSearchIcon(int x, int y, int color) {
        ringCircle(x + 4, y + 4, 4, color);
        line(x + 7, y + 7, x + 10, y + 10, color);
    }

    private static void line(float x1, float y1, float x2, float y2, int color) {
        float alpha = (color >>> 24) / 255.0F;
        float red = (color >> 16 & 0xFF) / 255.0F;
        float green = (color >> 8 & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_LINE_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlColor.set(red, green, blue, alpha);
        GL11.glLineWidth(1.0F);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x2, y2);
        GL11.glEnd();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    private static void drawCategoryIcon(String id, int x, int y, int color) {
        if ("combat".equals(id)) {
            line(x + 5, y, x + 5, y + 2, color);
            line(x + 5, y + 8, x + 5, y + 10, color);
            line(x, y + 5, x + 2, y + 5, color);
            line(x + 8, y + 5, x + 10, y + 5, color);
            ringCircle(x + 5, y + 5, 3, color);
            fillCircle(x + 5, y + 5, 1, color);
        } else if ("movement".equals(id)) {
            line(x, y + 2, x + 4, y + 5, color);
            line(x + 4, y + 5, x, y + 8, color);
            line(x + 4, y + 2, x + 8, y + 5, color);
            line(x + 8, y + 5, x + 4, y + 8, color);
        } else if ("render".equals(id)) {
            line(x, y + 5, x + 5, y + 1, color);
            line(x + 5, y + 1, x + 10, y + 5, color);
            line(x + 10, y + 5, x + 5, y + 9, color);
            line(x + 5, y + 9, x, y + 5, color);
            fillCircle(x + 5, y + 5, 1, color);
        } else if ("player".equals(id)) {
            ringCircle(x + 5, y + 3, 2, color);
            line(x + 1, y + 9, x + 2, y + 7, color);
            line(x + 2, y + 7, x + 8, y + 7, color);
            line(x + 8, y + 7, x + 9, y + 9, color);
        } else {
            line(x, y + 2, x + 10, y + 2, color);
            line(x, y + 5, x + 6, y + 5, color);
            line(x, y + 8, x + 10, y + 8, color);
            fillCircle(x + 7, y + 2, 1, color);
            fillCircle(x + 3, y + 5, 1, color);
            fillCircle(x + 6, y + 8, 1, color);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha & 0xFF) << 24 | (color & 0xFFFFFF);
    }

    private static float approach(float value, float target, float deltaMs, float durationMs) {
        if (deltaMs <= 0.0F || durationMs <= 0.0F) {
            return value;
        }
        float amount = Math.min(1.0F, deltaMs / durationMs);
        return value + (target - value) * amount;
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0F - Math.max(0.0F, Math.min(1.0F, value));
        return 1.0F - inverse * inverse * inverse;
    }

    private static int blendColor(int from, int to, float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int alpha = Math.round((from >>> 24) + ((to >>> 24) - (from >>> 24)) * t);
        int red = Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
        int green = Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
        int blue = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static boolean isHovered(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Minecraft 坐标 -> 帧缓冲坐标（滚动裁剪）
    // ------------------------------------------------------------------

    private void pushScissor(int x, int y, int w, int h) {
        if (this.mc == null || w <= 0 || h <= 0) {
            return;
        }
        ScaledResolution resolution = new ScaledResolution(this.mc);
        int scale = Math.max(1, resolution.getScaleFactor());
        int fbHeight = resolution.getScaledHeight() * scale;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, fbHeight - (y + h) * scale, w * scale, h * scale);
    }

    private void popScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    // ==================================================================
    // 数据模型
    // ==================================================================

    private static final class Category {
        final String id;
        final String name;
        final String description;
        final int color;

        Category(String id, String name, String description, int color) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.color = color;
        }
    }

    private interface Binding {
        double get();
        void set(double value);
    }

    private static final class Setting {
        static final int SLIDER = 0;
        static final int SELECT = 1;
        static final int CHECK = 2;

        final String label;
        final int kind;
        final float min;
        final float max;
        final float step;
        final int decimals;
        final String suffix;
        final String[] options;
        final Binding binding;

        private Setting(String label, int kind, float min, float max, float step,
                int decimals, String suffix, String[] options, Binding binding) {
            this.label = label;
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.step = step;
            this.decimals = decimals;
            this.suffix = suffix;
            this.options = options;
            this.binding = binding;
        }

        static Setting slider(String label, float min, float max, float step,
                int decimals, String suffix, Binding binding) {
            return new Setting(label, SLIDER, min, max, step, decimals, suffix, null, binding);
        }

        static Setting select(String label, String[] options, Binding binding) {
            return new Setting(label, SELECT, 0.0F, options.length - 1, 1.0F,
                    0, "", options, binding);
        }

        static Setting check(String label, Binding binding) {
            return new Setting(label, CHECK, 0.0F, 1.0F, 1.0F, 0, "", null, binding);
        }

        double value() {
            return binding.get();
        }

        void set(double value) {
            binding.set(value);
        }

        boolean checked() {
            return binding.get() > 0.5D;
        }

        void toggle() {
            binding.set(checked() ? 0.0D : 1.0D);
        }

        double percent() {
            if (kind == SELECT) {
                int size = options == null ? 1 : options.length;
                return size <= 1 ? 0.0D : Math.round(binding.get()) / (double) (size - 1);
            }
            return (binding.get() - min) / (double) (max - min);
        }

        void setPercent(double percent) {
            double raw = min + (max - min) * percent;
            double stepped = Math.round(raw / step) * (double) step;
            binding.set(Math.max(min, Math.min(max, stepped)));
        }

        String display() {
            if (kind == SELECT) {
                int size = options == null ? 1 : options.length;
                int index = (int) Math.round(binding.get());
                index = Math.max(0, Math.min(size - 1, index));
                return options[index];
            }
            double value = binding.get();
            if (decimals <= 0) {
                return String.valueOf((int) Math.round(value)) + suffix;
            }
            return String.format(Locale.ROOT, "%." + decimals + "f", value) + suffix;
        }
    }

    private static final class Entry {
        final String id;
        final String name;
        final String category;
        final Module module;
        final String featureId;
        final List<Setting> settings = new ArrayList<Setting>();
        float hoverProgress;
        float toggleProgress;
        float expandProgress;
        long animationAt;
        long toggleAt;
        long expandAt;

        Entry(Module module, String category) {
            this.module = module;
            this.featureId = null;
            this.name = prettyName(module.getName());
            this.category = category;
            this.id = "module:" + module.getName();
        }

        Entry(String featureId, String name, String category) {
            this.module = null;
            this.featureId = featureId;
            this.name = name;
            this.category = category;
            this.id = "feature:" + featureId;
        }

        boolean isEnabled() {
            if (module != null) {
                return module.isEnabled();
            }
            if ("aim".equals(featureId)) {
                return OpenMaverFeatures.isAimEnabled();
            }
            if ("esp".equals(featureId)) {
                return OpenMaverFeatures.isEspEnabled();
            }
            return false;
        }

        void toggle() {
            if (module != null) {
                module.setEnabled(!module.isEnabled());
            } else if ("aim".equals(featureId)) {
                OpenMaverFeatures.toggleAim();
            } else if ("esp".equals(featureId)) {
                OpenMaverFeatures.toggleEsp();
            }
        }

        String keybind() {
            return module == null ? null : module.getKeybind();
        }

        String meta() {
            if ("aim".equals(featureId)) {
                return "SMOOTH " + format(OpenMaverFeatures.getAimSpeed(), 2)
                        + "  ·  RANGE " + format(OpenMaverFeatures.getAimRange(), 1) + " m";
            }
            if ("esp".equals(featureId)) {
                return "BOX  ·  RANGE " + Math.round(OpenMaverFeatures.getEspRange()) + " m";
            }
            if (module != null) {
                if ("AutoSprint".equals(module.getName())) {
                    return OpenMaverFeatures.isSprintOnlyForward()
                            ? "AUTO  ·  FORWARD" : "AUTO  ·  OMNI";
                }
                return module.getConfigKey().toUpperCase(Locale.ROOT);
            }
            return "";
        }

        private static String format(float value, int decimals) {
            return String.format(Locale.ROOT, "%." + decimals + "f", value);
        }

        private static String prettyName(String raw) {
            if (raw == null || raw.isEmpty()) {
                return "Unnamed";
            }
            StringBuilder out = new StringBuilder();
            char previous = 0;
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (i > 0 && Character.isUpperCase(c)
                        && (Character.isLowerCase(previous) || Character.isDigit(previous))) {
                    out.append(' ');
                }
                if (i == 0 || raw.charAt(i - 1) == ' ') {
                    out.append(Character.toUpperCase(c));
                } else {
                    out.append(c);
                }
                previous = c;
            }
            return out.toString();
        }
    }
}
