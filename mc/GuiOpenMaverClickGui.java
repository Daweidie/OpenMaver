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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 游戏内 ClickGUI（Forge 1.8.9 / {@link GuiScreen}）。
 *
 * <p>视觉对应 {@code design/clickgui.html}：纯灰色面板、中性灰描边、左侧分类栏、
 * 顶部搜索与筛选、模块行 + 可展开设置区。全部由 {@link Gui#drawRect} 与立即模式
 * 线段自绘，不依赖 GuiButton / 纹理资源，因此运行期注入也能用。
 *
 * <p>字体：模块名 / 分类名用打包的 Lucida Sans Typewriter（{@link HudFont}），
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

    private static final int PANEL_MAX_W = 500;
    private static final int PANEL_MAX_H = 400;
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

    /** 左栏分类 / 系统入口的固定几何，绘制与点击共用。 */
    private static final int RAIL_NAV_Y = 84;
    private static final int RAIL_NAV_STRIDE = 27;
    private static final int RAIL_NAV_H = 25;
    private static final int RAIL_SYS_LABEL_Y = 224;
    private static final int RAIL_SYS_Y = 236;
    private static final int RAIL_SYS_STRIDE = 25;
    private static final int RAIL_SYS_H = 22;
    private static final int RAIL_SYS_MIN_H = 315;

    private static final int RADIUS = 22;
    private static final int CARD_RADIUS = 13;

    // ------------------------------------------------------------------
    // 配色
    // ------------------------------------------------------------------

    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_2 = 0xFFA9B5C6;
    private static final int MUTED = 0xFF6E7A8C;
    private static final int FAINT = 0xFF525C6B;
    /** 全界面只使用白色强调；不再出现青 / 紫 / 红 / 绿。 */
    private static final int ACCENT = 0xFFFFFFFF;

    /** 中性灰色边缘，用于外框、顶部高光与轻微外发光。 */
    private static final int GLASS_EDGE = 0xFFBFBFBF;
    private static final int GLASS_EDGE_BRIGHT = 0xFFEDEDED;

    /** 纯灰色面板 / 分类栏 / 卡片。面板与分类栏略带透明，让底层模糊透出来。 */
    private static final int PANEL_SOLID = 0xC82E2E2E;
    private static final int RAIL_SOLID = 0xD0272727;
    private static final int CARD_FILL = 0xE6383838;
    private static final int CARD_HOVER = 0xEA444444;
    private static final int CARD_OPEN = 0xE83E3E3E;
    private static final int FIELD_SOLID = 0xE6232323;
    private static final int FIELD_HOVER = 0xEA2D2D2D;


    private static final int SLIDER_VALUE = 0xFFFFFFFF;
    private static final int KEYCAP_TEXT = 0xFFD5D9DF;

    private static final float MIN_LOGO_SCALE = 0.35F;
    private static final float MAX_LOGO_SCALE = 1.25F;
    private static final float LOGO_SCALE_STEP = 0.05F;

    // ------------------------------------------------------------------
    // 分类
    // ------------------------------------------------------------------

    private static final Category[] CATEGORIES = {
        new Category("combat", "Combat", "Target acquisition, reach and attack timing.",
                ACCENT, ACCENT),
        new Category("movement", "Movement", "Velocity, sprint and travel assistance.",
                ACCENT, ACCENT),
        new Category("render", "Render", "ESP, overlays and visual clarity.",
                ACCENT, ACCENT),
        new Category("player", "Player", "Inventory, tools and survival helpers.",
                ACCENT, ACCENT),
        new Category("misc", "Misc", "Utility, exploit and quality-of-life.",
                ACCENT, ACCENT)
    };

    /** 左栏 SYSTEM 入口（对应设计稿的 Configs / Statistics / Settings）。 */
    private static final SystemItem[] SYSTEM_ITEMS = {
        new SystemItem("configs", "Configs", "Profiles, storage and active settings."),
        new SystemItem("stats", "Statistics", "Module counters and category activity."),
        new SystemItem("settings", "Settings", "Client preferences and diagnostics.")
    };

    private static final String[] ESP_COLOR_NAMES = {"Orange", "Cyan", "Violet", "Red"};
    private static final String[] XRAY_COLOR_NAMES = {"Health", "Orange", "Cyan", "Violet", "Red"};
    private static final String[] AIM_MODES = {"Smooth", "Instant"};
    private static final String[] AIM_TARGETS = {"Head", "Chest", "Body"};
    private static final String[] AUTOCLICKER_MODES = {
            "FULL CHARGE", "FASTEST", "VANILLA"};
    private static final String[] BYPASS_PROFILES = {"LEGIT", "BALANCED", "AGGRESSIVE"};
    private static final String[] CRITICALS_MODES = {
            "ALWAYS", "NCP", "FALLING", "GRIM", "JUMP", "LOW", "DOWN"};

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private final GuiScreen returnScreen;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final Set<String> expanded = new HashSet<String>();

    private String category = "combat";
    /** null 表示模块列表；否则显示 Configs / Statistics / Settings 页面。 */
    private String systemPage;
    private String searchQuery = "";
    private boolean searchFocused;
    private int filter = 0; // 0 all, 1 enabled, 2 disabled
    private boolean sortAscending = true;

    /** 所有按钮共用的过渡时长：悬停快速、选中/激活稍慢，形成统一的“平滑滚动”手感。 */
    private static final float HOVER_MS = 130.0F;
    private static final float ACTIVE_MS = 190.0F;
    private static final float PRESS_MS = 160.0F;

    private int scroll;
    private float scrollVisual;
    private long lastAnimationAt;
    private long frameNow;
    private float openProgress;
    private final Map<String, ControlAnim> controls = new HashMap<String, ControlAnim>();
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
        this.systemPage = null;
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
        this.frameNow = now;
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
        OpenMaverHud.renderToastOverlay(this.mc.fontRendererObj, this.width, this.height);
    }

    private void layout() {
        // 面板保持 5:4 长宽比：先取可用空间，再以较短的一边为准收缩。
        int availW = Math.min(this.width - 32, PANEL_MAX_W);
        int availH = Math.min(this.height - 32, PANEL_MAX_H);
        panelW = Math.min(availW, availH * 5 / 4);
        panelH = panelW * 4 / 5;
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
            } else if ("AutoClicker".equals(module.getName())) {
                addAutoClickerSettings(entry);
            } else if ("Reach".equals(module.getName())) {
                addReachSettings(entry);
            } else if ("Criticals".equals(module.getName())) {
                addCriticalsSettings(entry);
            } else if ("FastPlace".equals(module.getName())) {
                addFastPlaceSettings(entry);
            } else if ("GodBridge".equals(module.getName())) {
                addGodBridgeSettings(entry);
            } else if ("FakePlayer".equals(module.getName())) {
                addFakePlayerSettings(entry);
            } else if ("Target HUD".equals(module.getName())) {
                addTargetHudSettings(entry);
            } else if ("Player X-Ray".equals(module.getName())) {
                addPlayerXraySettings(entry);
            }
            entries.add(entry);
        }

        Entry aim = new Entry("aim", "Aim Assist", "combat");
        addAimSettings(aim);
        entries.add(aim);

        Entry esp = new Entry("esp", "Player ESP", "render");
        addEspSettings(esp);
        entries.add(esp);

        Entry bypass = new Entry("bypass", "Grim Bypass", "misc");
        addBypassSettings(bypass);
        entries.add(bypass);
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
        entry.settings.add(Setting.check("GCD QUANTIZE", new Binding() {
            public double get() { return OpenMaverFeatures.isAimGcdEnabled() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setAimGcdEnabled(value > 0.5D); }
        }));
        entry.settings.add(Setting.slider("MAX STEP", 5.0F, 90.0F, 5.0F, 0, "°", new Binding() {
            public double get() { return OpenMaverFeatures.getAimMaxStep(); }
            public void set(double value) { OpenMaverFeatures.setAimMaxStep((float) value); }
        }));
    }

    private void addBypassSettings(Entry entry) {
        entry.settings.add(Setting.select("PROFILE", BYPASS_PROFILES, new Binding() {
            public double get() { return OpenMaverFeatures.getBypassProfile(); }
            public void set(double value) { OpenMaverFeatures.setBypassProfile((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("ENABLED", new Binding() {
            public double get() { return OpenMaverFeatures.isBypassEnabled() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setBypassEnabled(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("DEBUG LOG", new Binding() {
            public double get() { return OpenMaverFeatures.isDebugEnabled() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setDebugEnabled(value > 0.5D); }
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

    private void addTargetHudSettings(Entry entry) {
        entry.settings.add(Setting.slider("RANGE", 8.0F, 64.0F, 2.0F, 0, " m", new Binding() {
            public double get() { return OpenMaverFeatures.getTargetHudRange(); }
            public void set(double value) { OpenMaverFeatures.setTargetHudRange((float) value); }
        }));
        entry.settings.add(Setting.slider("SCALE", 0.7F, 1.4F, 0.05F, 2, "x", new Binding() {
            public double get() { return OpenMaverFeatures.getTargetHudScale(); }
            public void set(double value) { OpenMaverFeatures.setTargetHudScale((float) value); }
        }));
        entry.settings.add(Setting.check("PLAYERS ONLY", new Binding() {
            public double get() { return OpenMaverFeatures.isTargetHudPlayersOnly() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setTargetHudPlayersOnly(value > 0.5D); }
        }));
    }

    private void addPlayerXraySettings(Entry entry) {
        entry.settings.add(Setting.slider("RANGE", 8.0F, 128.0F, 4.0F, 0, " m", new Binding() {
            public double get() { return OpenMaverFeatures.getPlayerXrayRange(); }
            public void set(double value) { OpenMaverFeatures.setPlayerXrayRange((float) value); }
        }));
        entry.settings.add(Setting.select("COLOR", XRAY_COLOR_NAMES, new Binding() {
            public double get() { return OpenMaverFeatures.getPlayerXrayColor(); }
            public void set(double value) { OpenMaverFeatures.setPlayerXrayColor((int) Math.round(value)); }
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

    private void addAutoClickerSettings(Entry entry) {
        entry.settings.add(Setting.slider("CPS MIN", 1.0F, 20.0F, 1.0F, 0, "", new Binding() {
            public double get() { return OpenMaverFeatures.getAutoClickerCpsMin(); }
            public void set(double value) { OpenMaverFeatures.setAutoClickerCpsMin((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("CPS MAX", 1.0F, 20.0F, 1.0F, 0, "", new Binding() {
            public double get() { return OpenMaverFeatures.getAutoClickerCpsMax(); }
            public void set(double value) { OpenMaverFeatures.setAutoClickerCpsMax((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("LEFT CLICK", new Binding() {
            public double get() { return OpenMaverFeatures.isAutoClickerLeft() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setAutoClickerLeft(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("RIGHT CLICK", new Binding() {
            public double get() { return OpenMaverFeatures.isAutoClickerRight() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setAutoClickerRight(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("REQUIRE HOLD", new Binding() {
            public double get() { return OpenMaverFeatures.isAutoClickerRequireHold() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setAutoClickerRequireHold(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("WAIT FOR HURT", new Binding() {
            public double get() { return OpenMaverFeatures.isAutoClickerWaitForHurt() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setAutoClickerWaitForHurt(value > 0.5D); }
        }));
        entry.settings.add(Setting.select("MODE", AUTOCLICKER_MODES, new Binding() {
            public double get() { return OpenMaverFeatures.getAutoClickerAttackMode(); }
            public void set(double value) { OpenMaverFeatures.setAutoClickerAttackMode((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("HUMANIZE", new Binding() {
            public double get() { return OpenMaverFeatures.isAutoClickerHumanize() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setAutoClickerHumanize(value > 0.5D); }
        }));
    }

    private void addReachSettings(Entry entry) {
        entry.settings.add(Setting.slider("EXTRA", 0.0F, 0.5F, 0.05F, 2, " m", new Binding() {
            public double get() { return OpenMaverFeatures.getReachExtra(); }
            public void set(double value) { OpenMaverFeatures.setReachExtra((float) value); }
        }));
        entry.settings.add(Setting.slider("CHANCE", 1.0F, 100.0F, 1.0F, 0, " %", new Binding() {
            public double get() { return OpenMaverFeatures.getReachChance(); }
            public void set(double value) { OpenMaverFeatures.setReachChance((float) value); }
        }));
    }

    private void addCriticalsSettings(Entry entry) {
        entry.settings.add(Setting.select("MODE", CRITICALS_MODES, new Binding() {
            public double get() { return OpenMaverFeatures.getCriticalsMode(); }
            public void set(double value) { OpenMaverFeatures.setCriticalsMode((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("STOP SPRINT", new Binding() {
            public double get() { return OpenMaverFeatures.isCriticalsStopSprint() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setCriticalsStopSprint(value > 0.5D); }
        }));
    }

    private void addFastPlaceSettings(Entry entry) {
        entry.settings.add(Setting.slider("DELAY", 0.0F, 4.0F, 1.0F, 0, "t", new Binding() {
            public double get() { return OpenMaverFeatures.getFastPlaceDelayTicks(); }
            public void set(double value) { OpenMaverFeatures.setFastPlaceDelayTicks((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("ONLY BLOCKS", new Binding() {
            public double get() { return OpenMaverFeatures.isFastPlaceOnlyBlocks() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setFastPlaceOnlyBlocks(value > 0.5D); }
        }));
    }

    private void addGodBridgeSettings(Entry entry) {
        entry.settings.add(Setting.slider("DELAY", 0.0F, 4.0F, 1.0F, 0, "t", new Binding() {
            public double get() { return OpenMaverFeatures.getGodBridgeDelayTicks(); }
            public void set(double value) { OpenMaverFeatures.setGodBridgeDelayTicks((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("FAST PLACE", new Binding() {
            public double get() { return OpenMaverFeatures.isGodBridgeFastPlace() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setGodBridgeFastPlace(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("REQUIRE EDGE AIM", new Binding() {
            public double get() { return OpenMaverFeatures.isGodBridgeRequireEdgeAim() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setGodBridgeRequireEdgeAim(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("AUTO ROTATE", new Binding() {
            public double get() { return OpenMaverFeatures.isGodBridgeAutoRotate() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setGodBridgeAutoRotate(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("LEGIT AIM", new Binding() {
            public double get() { return OpenMaverFeatures.isGodBridgeLegitAim() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setGodBridgeLegitAim(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("PACKET ROTATION", new Binding() {
            public double get() { return OpenMaverFeatures.isGodBridgePacketRotation() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setGodBridgePacketRotation(value > 0.5D); }
        }));
    }

    private void addFakePlayerSettings(Entry entry) {
        entry.settings.add(Setting.slider("COUNT", 1.0F, 5.0F, 1.0F, 0, "", new Binding() {
            public double get() { return OpenMaverFeatures.getFakePlayerCount(); }
            public void set(double value) { OpenMaverFeatures.setFakePlayerCount((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("DISTANCE", 2.0F, 10.0F, 1.0F, 0, " m", new Binding() {
            public double get() { return OpenMaverFeatures.getFakePlayerDistance(); }
            public void set(double value) { OpenMaverFeatures.setFakePlayerDistance((float) value); }
        }));
        entry.settings.add(Setting.check("NAME TAG", new Binding() {
            public double get() { return OpenMaverFeatures.isFakePlayerShowName() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setFakePlayerShowName(value > 0.5D); }
        }));
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    private void drawGui() {
        float reveal = easeOutCubic(openProgress);
        GlColor.restore();

        // 背景：先抓取世界画面做高斯模糊，再叠一层轻薄的深色，形成毛玻璃景深。
        ScaledResolution resolution = new ScaledResolution(this.mc);
        GuiBlur.beginFrame(this.width, this.height, resolution.getScaleFactor());
        GuiBlur.draw(0, 0, this.width, this.height, 0.0F, 0x0C0F15, 0.34F, reveal);

        GL11.glPushMatrix();
        GL11.glTranslatef(0.0F, (1.0F - reveal) * 7.0F, 0.0F);

        drawPanelShadow(reveal);
        drawPanel(reveal);

        FontRenderer font = this.mc.fontRendererObj;
        drawRail(font);
        if (systemPage == null) {
            drawHeader(font);
            drawTools(font);
            drawList(font);
        } else {
            drawSystemHeader(font);
            drawSystemPage(font);
        }
        drawFooter(font);
        drawSelectPopup(font);
        GL11.glPopMatrix();
    }

    // ------------------------------------------------------------------
    // 面板
    // ------------------------------------------------------------------

    /** 面板投影 + 一圈非常淡的白色外发光，让面板从世界里浮起来。 */
    private void drawPanelShadow(float reveal) {
        roundRect(panelX + 8, panelY + 20, panelW - 16, panelH, RADIUS + 6,
                withAlpha(0x000000, Math.round(0x12 * reveal)));
        roundRect(panelX + 4, panelY + 11, panelW - 8, panelH, RADIUS + 3,
                withAlpha(0x000000, Math.round(0x1E * reveal)));
        roundRect(panelX + 1, panelY + 5, panelW - 2, panelH, RADIUS + 1,
                withAlpha(0x000000, Math.round(0x30 * reveal)));
        roundRect(panelX - 3, panelY - 3, panelW + 6, panelH + 6, RADIUS + 4,
                withAlpha(GLASS_EDGE, Math.round(0x10 * reveal)));
    }

    /** 纯色玻璃面板：1px 描边、顶部折射亮线、四周内阴影。 */
    private void drawPanel(float reveal) {
        int accent = accent();
        roundRect(panelX, panelY, panelW, panelH, RADIUS,
                withAlpha(GLASS_EDGE, Math.round(0x42 * reveal)));
        roundRect(panelX + 1, panelY + 1, panelW - 2, panelH - 2, RADIUS - 1,
                scaleAlpha(PANEL_SOLID, reveal));

        // 顶部 1px 高光：中间最亮，向两侧淡出。
        int lineY = panelY + 1;
        int middle = panelX + panelW / 2;
        Gui.drawRect(panelX + RADIUS, lineY, panelX + panelW - RADIUS, lineY + 1,
                withAlpha(0xFFFFFF, Math.round(0x10 * reveal)));
        Gui.drawRect(middle - panelW / 3, lineY, middle + panelW / 3, lineY + 1,
                withAlpha(GLASS_EDGE_BRIGHT, Math.round(0x1A * reveal)));
        Gui.drawRect(middle - panelW / 9, lineY, middle + panelW / 9, lineY + 1,
                withAlpha(0xFFFFFF, Math.round(0x34 * reveal)));
        // 顶部极淡的一层高光，保留“玻璃”质感但不破坏纯色。
        Gui.drawRect(panelX + RADIUS, panelY + 2, panelX + panelW - RADIUS,
                panelY + 3, withAlpha(0xFFFFFF, Math.round(0x05 * reveal)));

        // 四周内阴影：让面板有厚度，同时把视线收进内容区。
        for (int i = 0; i < 6; i++) {
            int alpha = Math.round((6 - i) * 1.1F * reveal);
            int inset = roundedInset(RADIUS, i + 0.5F, panelH);
            Gui.drawRect(panelX + inset, panelY + i, panelX + panelW - inset,
                    panelY + i + 1, withAlpha(0x000000, alpha));
        }
        for (int i = 0; i < 10; i++) {
            int alpha = Math.round((10 - i) * 1.7F * reveal);
            int inset = roundedInset(RADIUS, panelH - 0.5F - i, panelH);
            Gui.drawRect(panelX + inset, panelY + panelH - 1 - i,
                    panelX + panelW - inset, panelY + panelH - i,
                    withAlpha(0x000000, alpha));
        }
        for (int i = 0; i < 8; i++) {
            int alpha = Math.round((8 - i) * 1.1F * reveal);
            Gui.drawRect(panelX + i, panelY + RADIUS, panelX + i + 1,
                    panelY + panelH - RADIUS, withAlpha(0x000000, alpha));
            Gui.drawRect(panelX + panelW - 1 - i, panelY + RADIUS,
                    panelX + panelW - i, panelY + panelH - RADIUS,
                    withAlpha(0x000000, alpha));
        }
        // 底边反射光，呼应顶部高光。
        Gui.drawRect(middle - panelW / 4, panelY + panelH - 1,
                middle + panelW / 4, panelY + panelH,
                withAlpha(accent, Math.round(0x18 * reveal)));
    }

    // ------------------------------------------------------------------
    // 左栏
    // ------------------------------------------------------------------

    private void drawRail(FontRenderer font) {
        float reveal = easeOutCubic(openProgress);
        long now = frameNow != 0L ? frameNow : System.currentTimeMillis();
        int x = panelX;
        int y = panelY;
        int accent = accent();

        // 分类栏纯色底：与主面板用 1px 渐隐线分开。
        railPanel(x + 1, y + 1, railW - 1, panelH - 2, RADIUS - 1,
                scaleAlpha(RAIL_SOLID, reveal));
        int dividerX = x + railW;
        for (int i = 0; i < panelH - 2; i += 2) {
            float t = i / (float) Math.max(1, panelH - 2);
            int alpha = Math.round((0x1A - 0x16 * t) * reveal);
            Gui.drawRect(dividerX, y + 1 + i, dividerX + 1,
                    Math.min(y + panelH - 1, y + 3 + i), withAlpha(0xFFFFFF, alpha));
        }

        MaverLogo.draw(font, x + 18, y + 3, railW - 36, 32, logoScale, TEXT);

        // LOGO SIZE 滑杆
        micro(font, "LOGO SIZE", x + 16, y + 44, FAINT);
        String scaleText = String.format(Locale.ROOT, "%.2fx", logoScale);
        micro(font, scaleText, x + railW - 16 - microWidth(font, scaleText), y + 44,
                TEXT_2);
        int sliderX = x + 16;
        int sliderW = railW - 32;
        int sliderY = y + 58;
        boolean logoHovered = isHovered(lastMouseX, lastMouseY, x + 8, y + 42, railW - 16, 23);
        ControlAnim logoAnim = control("logo", logoHovered, logoSizeDragging, now);
        float logoHi = Math.max(logoAnim.hover, logoAnim.active);
        int trackColor = blendColor(withAlpha(0xFFFFFF, 0x12), withAlpha(0xFFFFFF, 0x24), logoHi);
        roundRect(sliderX, sliderY, sliderW, 4, 2, trackColor);
        float logoSizePercent = (logoScale - MIN_LOGO_SCALE)
                / (MAX_LOGO_SCALE - MIN_LOGO_SCALE);
        int sliderFill = Math.max(1, Math.round((sliderW - 1) * logoSizePercent));
        roundRect(sliderX, sliderY, sliderFill, 4, 2,
                withAlpha(accent, Math.round(0xDD + 0x22 * logoHi)));
        int thumbX = sliderX + sliderFill;
        int thumbGlow = 6 + Math.round(logoHi * 2.0F);
        fillCircle(thumbX, sliderY + 2, thumbGlow, withAlpha(accent, Math.round(0x18 + 0x10 * logoHi)));
        fillCircle(thumbX, sliderY + 2, 4, withAlpha(accent, Math.round(0x40 + 0x24 * logoHi)));
        fillCircle(thumbX, sliderY + 2, 2, 0xFFFFFFFF);

        int navY = y + RAIL_NAV_Y;
        for (int i = 0; i < CATEGORIES.length; i++) {
            Category cat = CATEGORIES[i];
            boolean active = systemPage == null && category.equals(cat.id);
            int itemX = x + 4;
            int itemY = navY + i * RAIL_NAV_STRIDE;
            int itemW = railW - 12;
            int itemH = RAIL_NAV_H;
            boolean hovered = isHovered(lastMouseX, lastMouseY, itemX, itemY, itemW, itemH);
            ControlAnim anim = control("nav:" + cat.id, hovered, active, now);
            float on = anim.active;
            float hover = anim.hover * (1.0F - on);

            if (hover > 0.01F) {
                roundRect(itemX, itemY, itemW, itemH, 7,
                        withAlpha(0xFFFFFF, Math.round(0x08 * hover)));
            }
            if (on > 0.01F) {
                // 横向渗入的强调色 + 左侧发光竖条，像液体被点亮。
                roundRect(itemX, itemY, itemW, itemH, 7,
                        withAlpha(cat.color, Math.round(0x26 * on)));
                roundRect(itemX, itemY, itemW * 3 / 4, itemH, 7,
                        withAlpha(cat.color, Math.round(0x14 * on)));
                roundRect(itemX, itemY, itemW / 3, itemH, 7,
                        withAlpha(cat.color, Math.round(0x14 * on)));
                roundRect(itemX - 1, itemY + 4, 3, itemH - 8, 2,
                        withAlpha(cat.color, Math.round(0x44 * on)));
                roundRect(itemX, itemY + 6, 2, itemH - 12, 1,
                        withAlpha(cat.color, Math.round(0xF0 * on)));
            }
            if (anim.press > 0.01F) {
                roundRect(itemX, itemY, itemW, itemH, 7,
                        withAlpha(0xFFFFFF, Math.round(0x0A * anim.press)));
            }

            int iconColor = blendColor(0xFF7D879A, cat.color, on);
            iconColor = blendColor(iconColor, 0xFFB6C2D4, hover * 0.7F);
            drawCategoryIcon(cat.id, x + 22, itemY + 8, iconColor);
            int textColor = blendColor(TEXT_2, 0xFFF4FBFF, on);
            textColor = blendColor(textColor, 0xFFE4EBF6, hover * 0.65F);
            hud(font, cat.name, x + 40, itemY + 8, textColor);

            int count = countCategory(cat.id);
            String countText = count < 10 ? "0" + count : String.valueOf(count);
            int countColor = blendColor(0xFF596272, withAlpha(cat.color, 0xEE), on);
            countColor = blendColor(countColor, 0xFF8A95A6, hover * 0.5F);
            micro(font, countText, x + railW - 26, itemY + 10, countColor);
        }

        // SYSTEM 入口：Configs / Statistics / Settings（高度不足时省略）
        if (panelH >= RAIL_SYS_MIN_H) {
            micro(font, "SYSTEM", x + 18, y + RAIL_SYS_LABEL_Y, FAINT);
            for (int i = 0; i < SYSTEM_ITEMS.length; i++) {
                SystemItem item = SYSTEM_ITEMS[i];
                boolean active = item.id.equals(systemPage);
                int itemX = x + 4;
                int itemY = y + RAIL_SYS_Y + i * RAIL_SYS_STRIDE;
                int itemW = railW - 12;
                int itemH = RAIL_SYS_H;
                boolean hovered = isHovered(lastMouseX, lastMouseY, itemX, itemY, itemW, itemH);
                ControlAnim anim = control("sysnav:" + item.id, hovered, active, now);
                float on = anim.active;
                float hover = anim.hover * (1.0F - on);

                if (hover > 0.01F) {
                    roundRect(itemX, itemY, itemW, itemH, 7,
                            withAlpha(0xFFFFFF, Math.round(0x08 * hover)));
                }
                if (on > 0.01F) {
                    roundRect(itemX, itemY, itemW, itemH, 7,
                            withAlpha(ACCENT, Math.round(0x24 * on)));
                    roundRect(itemX, itemY, itemW * 3 / 4, itemH, 7,
                            withAlpha(ACCENT, Math.round(0x12 * on)));
                    roundRect(itemX - 1, itemY + 4, 3, itemH - 8, 2,
                            withAlpha(ACCENT, Math.round(0x40 * on)));
                    roundRect(itemX, itemY + 5, 2, itemH - 10, 1,
                            withAlpha(ACCENT, Math.round(0xF0 * on)));
                }
                if (anim.press > 0.01F) {
                    roundRect(itemX, itemY, itemW, itemH, 7,
                            withAlpha(0xFFFFFF, Math.round(0x0A * anim.press)));
                }

                int iconColor = blendColor(0xFF6B7686, ACCENT, on);
                iconColor = blendColor(iconColor, 0xFFB6C2D4, hover * 0.7F);
                drawSystemIcon(item.id, x + 22, itemY + 7, iconColor);
                int textColor = blendColor(0xFF98A3B4, 0xFFF4FBFF, on);
                textColor = blendColor(textColor, 0xFFE4EBF6, hover * 0.65F);
                hud(font, item.name, x + 40, itemY + 7, textColor);
            }
        }

    }

    // ------------------------------------------------------------------
    // 顶部
    // ------------------------------------------------------------------

    private void drawHeader(FontRenderer font) {
        Category cat = currentCategory();
        long now = frameNow != 0L ? frameNow : System.currentTimeMillis();
        int accent = cat.color;
        int x = mainX + 18;
        int y = panelY;

        String title = cat.name;
        hudBold(font, title, x, y + 21, TEXT);
        int titleW = hudBoldWidth(font, title);
        int chipX = x + titleW + 9;
        int count = countCategory(cat.id);
        String countText = String.valueOf(count);
        int chipW = Math.max(18, microWidth(font, countText) + 12);
        roundRect(chipX, y + 21, chipW, 13, 5, withAlpha(accent, 0x24));
        roundRect(chipX, y + 21, chipW, 13, 5, withAlpha(accent, 0x12));
        micro(font, countText, chipX + (chipW - microWidth(font, countText)) / 2,
                y + 24, withAlpha(accent, 0xE8));

        // 搜索框：内凹玻璃 + 聚焦时的白色光圈
        boolean focus = searchFocused;
        boolean searchHovered = isHovered(lastMouseX, lastMouseY,
                searchX, searchY, searchW, searchH);
        ControlAnim searchAnim = control("search", searchHovered, focus, now);
        float focusAmt = searchAnim.active;
        float searchHover = searchAnim.hover * (1.0F - focusAmt);
        if (focusAmt > 0.01F) {
            roundRect(searchX - 2, searchY - 2, searchW + 4, searchH + 4, 9,
                    withAlpha(ACCENT, Math.round(0x18 * focusAmt)));
        }
        if (searchAnim.press > 0.01F) {
            roundRect(searchX, searchY, searchW, searchH, 7,
                    withAlpha(0xFFFFFF, Math.round(0x0A * searchAnim.press)));
        }
        int searchBorder = blendColor(withAlpha(0xFFFFFF, 0x14),
                withAlpha(ACCENT, 0x5A), focusAmt);
        searchBorder = blendColor(searchBorder, withAlpha(0xFFFFFF, 0x26), searchHover * 0.6F);
        roundRect(searchX, searchY, searchW, searchH, 7, searchBorder);
        roundRect(searchX + 1, searchY + 1, searchW - 2, searchH - 2, 6,
                blendColor(FIELD_SOLID, 0xFF1E1E1E, focusAmt));
        Gui.drawRect(searchX + 6, searchY + 1, searchX + searchW - 6, searchY + 2,
                withAlpha(0xFFFFFF, Math.round(0x0A + 0x0A * focusAmt + 0x04 * searchHover)));
        drawSearchIcon(searchX + 7, searchY + 7,
                blendColor(0xFF7C8798, ACCENT, focusAmt));

        String shown = searchQuery;
        if (shown.isEmpty()) {
            micro(font, ellipsizeMicro(font, "Search modules...", searchW - 30),
                    searchX + 20, searchY + 7, 0xFF5E6878);
            if (focus && ((System.currentTimeMillis() - openedAt) / 500L) % 2L == 0L) {
                Gui.drawRect(searchX + 20, searchY + 7, searchX + 21, searchY + 15, ACCENT);
            }
        } else {
            int max = searchW - 26;
            micro(font, ellipsizeMicro(font, shown, max), searchX + 20, searchY + 7, TEXT_2);
            if (focus && ((System.currentTimeMillis() - openedAt) / 500L) % 2L == 0L) {
                int caretX = searchX + 20 + microWidth(font, ellipsizeMicro(font, shown, max));
                Gui.drawRect(caretX + 1, searchY + 7, caretX + 2, searchY + 15, ACCENT);
            }
        }
    }

    // ------------------------------------------------------------------
    // SYSTEM 页面（Configs / Statistics / Settings）
    // ------------------------------------------------------------------

    private void drawSystemHeader(FontRenderer font) {
        SystemItem item = currentSystemItem();
        int x = mainX + 18;
        int y = panelY;

        hudBold(font, item.name, x, y + 21, TEXT);
    }

    private void drawSystemPage(FontRenderer font) {
        int x = listX;
        int y = panelY + HEADER_H + 8;
        int w = listW;
        int h = panelH - HEADER_H - FOOTER_H - 16;
        if ("stats".equals(systemPage)) {
            drawStatsPage(font, x, y, w, h);
        } else if ("configs".equals(systemPage)) {
            drawConfigsPage(font, x, y, w, h);
        } else {
            drawSettingsPage(font, x, y, w, h);
        }
    }

    private void drawStatsPage(FontRenderer font, int x, int y, int w, int h) {
        int gap = 10;
        int cardW = (w - gap * 2) / 3;
        int cardH = 70;
        drawStatCard(font, "MODULES", entries.size(), x, y, cardW, cardH);
        drawStatCard(font, "ENABLED", enabledCount(), x + cardW + gap, y, cardW, cardH);
        drawStatCard(font, "CATEGORIES", CATEGORIES.length,
                x + (cardW + gap) * 2, y, cardW, cardH);

        int rowY = y + cardH + 18;
        int stride = Math.min(30, Math.max(20, (y + h - rowY) / CATEGORIES.length));
        int rowH = stride - 4;
        for (int i = 0; i < CATEGORIES.length; i++) {
            Category cat = CATEGORIES[i];
            int ry = rowY + i * stride;
            if (ry + rowH > y + h) {
                break;
            }
            roundRect(x, ry, w, rowH, 7, withAlpha(0xFFFFFF, 0x06));
            drawCategoryIcon(cat.id, x + 10, ry + rowH / 2 - 5, cat.color);
            hud(font, cat.name, x + 27, ry + (rowH - 9) / 2, TEXT_2);

            int enabled = countCategoryEnabled(cat.id);
            int total = countCategory(cat.id);
            String count = (enabled < 10 ? "0" : "") + enabled + " / "
                    + (total < 10 ? "0" : "") + total;
            int countW = microWidth(font, count);
            int countX = x + w - 12 - countW;
            micro(font, count, countX, ry + (rowH - 8) / 2,
                    enabled > 0 ? withAlpha(cat.color, 0xDD) : 0xFF596272);

            int barW = Math.min(90, Math.max(40, w / 5));
            int barX = countX - 14 - barW;
            int barY = ry + rowH / 2 - 2;
            roundRect(barX, barY, barW, 4, 2, withAlpha(0xFFFFFF, 0x12));
            if (enabled > 0) {
                int fill = total <= 0 ? 0 : Math.max(1, barW * enabled / total);
                roundRect(barX, barY, fill, 4, 2, withAlpha(cat.color, 0xC8));
                roundRect(barX, barY, fill, 2, 1, withAlpha(0xFFFFFF, 0x28));
            }
        }
    }

    private void drawStatCard(FontRenderer font, String label, int value,
            int x, int y, int w, int h) {
        roundRect(x, y, w, h, 9, withAlpha(0xFFFFFF, 0x12));
        roundRect(x + 1, y + 1, w - 2, h - 2, 8, CARD_FILL);
        Gui.drawRect(x + 9, y + 1, x + w - 9, y + 2, withAlpha(0xFFFFFF, 0x12));

        micro(font, ellipsizeMicro(font, label, w - 24), x + 12, y + 14, FAINT);
        hudBold(font, String.valueOf(value), x + 12, y + 28, TEXT);

        if (w >= 90) {
            int ringX = x + w - 24;
            int ringY = y + 34;
            fillCircle(ringX, ringY, 11, withAlpha(ACCENT, 0x12));
            ringCircle(ringX, ringY, 8, withAlpha(ACCENT, 0x55));
            fillCircle(ringX, ringY, 3, withAlpha(ACCENT, 0xCC));
        }
    }

    private void drawConfigsPage(FontRenderer font, int x, int y, int w, int h) {
        int cardH = h >= 250 ? 84 : 70;
        roundRect(x, y, w, cardH, 9, withAlpha(0xFFFFFF, 0x14));
        roundRect(x + 1, y + 1, w - 2, cardH - 2, 8, CARD_FILL);
        Gui.drawRect(x + 12, y + 1, x + w - 12, y + 2, withAlpha(0xFFFFFF, 0x12));

        int titleY = y + (cardH - 26) / 2;
        hud(font, "Default", x + 14, titleY, TEXT);
        hud(font, ".jsonc", x + 14 + hudWidth(font, "Default"), titleY, FAINT);

        int barW = w - 28;
        int barY = y + cardH - 22;
        roundRect(x + 14, barY, barW, 5, 2, withAlpha(0xFFFFFF, 0x12));
        int enabled = enabledCount();
        int total = entries.size();
        if (enabled > 0 && total > 0) {
            int fill = Math.max(1, barW * enabled / total);
            roundRect(x + 14, barY, fill, 5, 2, withAlpha(ACCENT, 0xD8));
            roundRect(x + 14, barY, fill, 2, 1, withAlpha(0xFFFFFF, 0x30));
            fillCircle(x + 13 + fill, barY + 2, 3, withAlpha(ACCENT, 0x30));
        }

        int rowH = h >= 250 ? 44 : 38;
        int stride = rowH + 6;
        int rowY = y + cardH + 16;
        drawConfigFile(font, x, rowY, w, rowH, "default.jsonc");
        drawConfigFile(font, x, rowY + stride, w, rowH, "openmaver.properties");
    }

    private void drawConfigFile(FontRenderer font, int x, int y, int w, int h, String name) {
        roundRect(x, y, w, h, 8, withAlpha(0xFFFFFF, 0x07));
        roundRect(x, y + 2, 2, h - 4, 1, withAlpha(ACCENT, 0x66));
        hud(font, name, x + 12, y + (h - 11) / 2 + 1, TEXT);
    }

    private int systemSettingsRowH() {
        int h = panelH - HEADER_H - FOOTER_H - 16;
        return Math.min(54, Math.max(40, (h - 12 - 24) / 4));
    }

    private void drawSettingsPage(FontRenderer font, int x, int y, int w, int h) {
        int rowH = systemSettingsRowH();
        int gap = 8;
        drawSettingsRow(font, x, y, w, rowH, 0, "GRIM BYPASS");
        drawSettingsRow(font, x, y + rowH + gap, w, rowH, 1, "DEBUG LOG");
        drawSettingsRow(font, x, y + (rowH + gap) * 2, w, rowH, 2, "BYPASS PROFILE");
        drawSettingsRow(font, x, y + (rowH + gap) * 3, w, rowH, 3, "GUI KEY");
    }

    private void drawSettingsRow(FontRenderer font, int x, int y, int w, int rowH,
            int index, String label) {
        long now = frameNow != 0L ? frameNow : System.currentTimeMillis();
        boolean on = index == 0 ? OpenMaverFeatures.isBypassEnabled()
                : index == 1 && OpenMaverFeatures.isDebugEnabled();
        boolean hovered = isHovered(lastMouseX, lastMouseY, x, y, w, rowH);
        ControlAnim anim = control("sysrow:" + index, hovered, on, now);
        float hover = anim.hover * (1.0F - anim.active * 0.5F);

        roundRect(x, y, w, rowH, 8,
                withAlpha(0xFFFFFF, Math.round(0x08 + 0x07 * hover)));
        roundRect(x, y + 2, 2, rowH - 4, 1,
                withAlpha(ACCENT, Math.round(0x44 + 0x40 * hover)));
        if (anim.press > 0.01F) {
            roundRect(x, y, w, rowH, 8,
                    withAlpha(0xFFFFFF, Math.round(0x0A * anim.press)));
        }
        hud(font, label, x + 14, y + (rowH - 11) / 2 + 2,
                blendColor(TEXT, 0xFFFFFFFF, hover * 0.6F));

        int right = x + w - 14;
        int controlY = y + (rowH - 16) / 2;
        if (index == 0) {
            drawSwitch(right - 32, controlY, OpenMaverFeatures.isBypassEnabled(), anim);
        } else if (index == 1) {
            drawSwitch(right - 32, controlY, OpenMaverFeatures.isDebugEnabled(), anim);
        } else if (index == 2) {
            String name = OpenMaverFeatures.getBypassProfileName().toUpperCase(Locale.ROOT);
            int pillW = microWidth(font, name) + 16;
            roundRect(right - pillW, controlY, pillW, 16, 6,
                    withAlpha(ACCENT, Math.round(0x18 + 0x14 * hover)));
            micro(font, name, right - pillW + 8, controlY + 4,
                    withAlpha(ACCENT, Math.round(0xEE)));
        } else {
            String value = "RSHIFT";
            int valueW = microWidth(font, value);
            roundRect(right - valueW - 12, controlY, valueW + 12, 16, 5,
                    withAlpha(0xFFFFFF, Math.round(0x10 + 0x0C * hover)));
            micro(font, value, right - valueW - 6, controlY + 4,
                    blendColor(TEXT_2, TEXT, hover * 0.65F));
        }
    }

    private void drawSwitch(int x, int y, boolean on, ControlAnim anim) {
        int w = 32;
        int h = 16;
        float onAmt = anim == null ? (on ? 1.0F : 0.0F) : anim.active;
        float hover = anim == null ? 0.0F : anim.hover * (1.0F - onAmt);
        int track = blendColor(withAlpha(0xFFFFFF, 0x14), withAlpha(ACCENT, 0x66), onAmt);
        track = blendColor(track, withAlpha(0xFFFFFF, 0x30), hover * 0.6F);
        int fill = blendColor(FIELD_SOLID, withAlpha(ACCENT, 0x26), onAmt);
        int knobColor = blendColor(0xFF8A95A6, ACCENT, onAmt);
        int knobX = x + Math.round(8 + (w - 16) * onAmt);

        fillCircle(knobX, y + 8, 6 + Math.round(onAmt * 1.0F),
                withAlpha(ACCENT, Math.round(0x18 * onAmt + 0x08 * hover)));
        roundRect(x, y, w, h, 8, track);
        roundRect(x + 1, y + 1, w - 2, h - 2, 7, fill);
        fillCircle(knobX, y + 8, 4, knobColor);
    }

    private SystemItem currentSystemItem() {
        for (int i = 0; i < SYSTEM_ITEMS.length; i++) {
            if (SYSTEM_ITEMS[i].id.equals(systemPage)) {
                return SYSTEM_ITEMS[i];
            }
        }
        return SYSTEM_ITEMS[0];
    }

    private void drawTools(FontRenderer font) {
        long now = frameNow != 0L ? frameNow : System.currentTimeMillis();
        int y = panelY + HEADER_H;
        int tabY = y + 6;
        int tabH = 20;
        int accent = accent();
        final int[] widths = {56, 74, 82};
        int tabsW = widths[0] + widths[1] + widths[2] + 8;
        roundRect(listX, tabY, tabsW, tabH, 8, withAlpha(0xFFFFFF, 0x10));
        roundRect(listX + 1, tabY + 1, tabsW - 2, tabH - 2, 7, FIELD_SOLID);
        String[] labels = {"ALL", "ENABLED", "DISABLED"};
        int[] counts = {
            countCategory(category),
            countCategoryEnabled(category),
            countCategory(category) - countCategoryEnabled(category)
        };
        int tx = listX + 4;
        for (int i = 0; i < 3; i++) {
            int tabW = widths[i];
            int ty = tabY + 3;
            boolean active = filter == i;
            boolean hovered = isHovered(lastMouseX, lastMouseY, tx, ty, tabW - 2, 14);
            ControlAnim anim = control("tab:" + i, hovered, active, now);
            float on = anim.active;
            float hover = anim.hover * (1.0F - on);

            if (hover > 0.01F) {
                roundRect(tx, ty, tabW - 2, 14, 5,
                        withAlpha(0xFFFFFF, Math.round(0x08 * hover)));
            }
            if (on > 0.01F) {
                roundRect(tx, ty, tabW - 2, 14, 5,
                        withAlpha(accent, Math.round(0x22 * on)));
                roundRect(tx, ty, tabW - 2, 14, 5,
                        withAlpha(accent, Math.round(0x10 * on)));
                Gui.drawRect(tx + 4, ty + 11, tx + tabW - 6, ty + 12,
                        withAlpha(accent, Math.round(0xAA * on)));
            }
            if (anim.press > 0.01F) {
                roundRect(tx, ty, tabW - 2, 14, 5,
                        withAlpha(0xFFFFFF, Math.round(0x0A * anim.press)));
            }

            int textColor = blendColor(0xFF788395, withAlpha(accent, 0xFF), on);
            textColor = blendColor(textColor, 0xFFE4EBF6, hover * 0.55F);
            micro(font, labels[i], tx + 7, ty + 4, textColor);
            String c = String.valueOf(counts[i]);
            int countColor = blendColor(0xFF5F6A7B, withAlpha(accent, 0xBB), on);
            countColor = blendColor(countColor, 0xFFA9B5C6, hover * 0.45F);
            micro(font, c, tx + tabW - 8 - microWidth(font, c), ty + 4, countColor);
            tx += tabW;
        }

        if (listW >= 330) {
            boolean sortHovered = isHovered(lastMouseX, lastMouseY,
                    listX + listW - 110, tabY - 2, 110, 18);
            ControlAnim sortAnim = control("sort", sortHovered, false, now);
            float hover = sortAnim.hover;
            if (hover > 0.01F) {
                roundRect(listX + listW - 110, tabY - 2, 110, 18, 6,
                        withAlpha(0xFFFFFF, Math.round(0x08 * hover)));
            }
            if (sortAnim.press > 0.01F) {
                roundRect(listX + listW - 110, tabY - 2, 110, 18, 6,
                        withAlpha(0xFFFFFF, Math.round(0x0A * sortAnim.press)));
            }
            micro(font, sortAscending ? "A-Z" : "Z-A", listX + listW - 46, tabY + 7,
                    blendColor(0xFF9EAABB, TEXT, hover * 0.65F));
            drawChevron(listX + listW - 30, tabY + 11, false,
                    blendColor(MUTED, 0xFFD8E2F0, hover));
        }
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
            int fadeH = 26;
            for (int i = 0; i < fadeH; i += 2) {
                Gui.drawRect(listX, listY + listH - fadeH + i, listX + listW,
                        listY + listH - fadeH + i + 2, (i * 4) << 24);
            }
        }
        popScissor();

        // 细滚动条
        if (maxScroll > 0) {
            int trackX = listX + listW - 3;
            int thumbH = Math.max(20, listH * listH / contentH);
            // 滚动条与内容使用同一条平滑值，滚轮时不会出现“文字在动、滑块瞬移”。
            int visualScroll = clampInt(Math.round(scrollVisual), 0, maxScroll);
            int thumbY = listY + (listH - thumbH) * visualScroll / maxScroll;
            roundRect(trackX, listY, 2, listH, 1, withAlpha(0xFFFFFF, 0x08));
            roundRect(trackX, thumbY, 2, thumbH, 1, withAlpha(0xFFFFFF, 0x22));
            roundRect(trackX, thumbY + thumbH / 3, 2, Math.max(4, thumbH / 3), 1,
                    withAlpha(accent(), 0x44));
        }
    }

    private void drawEntry(FontRenderer font, Entry entry, int y, int cardH) {
        int x = listX;
        int w = listW - 6;
        boolean open = expanded.contains(entry.id);
        float openProgress = getEntryProgress(entry, System.currentTimeMillis());
        boolean hover = isHovered(lastMouseX, lastMouseY, x, y, w, ROW_H);
        long now = frameNow != 0L ? frameNow : System.currentTimeMillis();
        entry.hoverProgress = approach(entry.hoverProgress, hover ? 1.0F : 0.0F,
                Math.min(50.0F, now - entry.animationAt), 120.0F);
        entry.animationAt = now;
        entry.pressProgress = approach(entry.pressProgress, 0.0F,
                Math.min(50.0F, now - entry.pressAt), PRESS_MS);
        entry.pressAt = now;
        boolean on = entry.isEnabled();
        entry.toggleProgress = approach(entry.toggleProgress, on ? 1.0F : 0.0F,
                Math.min(50.0F, now - entry.toggleAt), 140.0F);
        entry.toggleAt = now;
        float hoverProgress = entry.hoverProgress;
        float pressProgress = entry.pressProgress;
        float glow = Math.max(hoverProgress * 0.55F + pressProgress * 0.25F,
                openProgress * 0.45F + (on ? 0.12F : 0.0F));
        int accent = accentFor(entry);

        // 启用 / 悬停 / 展开时透出的一层柔光。
        if (glow > 0.01F) {
            roundRect(x - 1, y - 1, w + 2, cardH + 2, CARD_RADIUS + 1,
                    withAlpha(accent, Math.round(0x12 * glow)));
        }
        int border = (on || open)
                ? withAlpha(accent, Math.round(0x20 + 0x22 * glow))
                : withAlpha(0xFFFFFF, Math.round(0x12 + 0x10 * hoverProgress));
        roundRect(x, y, w, cardH, CARD_RADIUS, border);

        // 卡片本体：纯色 + 悬停/展开时轻微提亮。
        int fill = blendColor(CARD_FILL, CARD_HOVER, hoverProgress);
        if (on) {
            fill = blendColor(fill, CARD_OPEN, 0.6F);
        }
        if (openProgress > 0.01F) {
            fill = blendColor(fill, CARD_OPEN, openProgress * 0.7F);
        }
        if (pressProgress > 0.01F) {
            fill = blendColor(fill, CARD_HOVER, pressProgress * 0.55F);
        }
        roundRect(x + 1, y + 1, w - 2, cardH - 2, CARD_RADIUS - 1, fill);
        if (w > CARD_RADIUS * 2) {
            Gui.drawRect(x + CARD_RADIUS, y + 1, x + w - CARD_RADIUS, y + 2,
                    withAlpha(0xFFFFFF, Math.round(0x10 * (0.5F + glow))));
        }

        // 开关圆点：外层光晕 + 实心核心。
        float toggleProgress = entry.toggleProgress;
        int dotX = x + 17;
        int dotY = y + ROW_H / 2;
        if (toggleProgress > 0.01F) {
            fillCircle(dotX, dotY, 7, withAlpha(accent, Math.round(0x18 * toggleProgress)));
            fillCircle(dotX, dotY, 4, withAlpha(accent, Math.round(0x3C * toggleProgress)));
            fillCircle(dotX, dotY, 2, withAlpha(accent, Math.round(255 * toggleProgress)));
        }
        if (toggleProgress < 0.99F) {
            float off = 1.0F - toggleProgress;
            fillCircle(dotX, dotY, 4, withAlpha(0xFFFFFF, Math.round(0x1C * off)));
            fillCircle(dotX, dotY, 2, withAlpha(0xFFFFFF, Math.round(0x32 * off)));
        }

        hud(font, ellipsizeHud(font, entry.name, w - 100), x + 32, y + 13,
                on ? TEXT : 0xFFB3BCCB);

        // 键帽
        String keybind = entry.keybind();
        int keyX = x + w - 48;
        if (keybind != null) {
            int keyW = Math.min(26, Math.max(15, microWidth(font, keybind) + 8));
            int kx = keyX + (15 - keyW) / 2;
            roundRect(kx, y + 10, keyW, 14, 5, withAlpha(0xFFFFFF, 0x18));
            roundRect(kx + 1, y + 11, keyW - 2, 12, 4, 0xFF4A4A4A);
            Gui.drawRect(kx + 3, y + 11, kx + keyW - 3, y + 12,
                    withAlpha(0xFFFFFF, 0x10));
            String keyText = ellipsizeMicro(font, keybind, keyW - 6);
            micro(font, keyText, kx + (keyW - microWidth(font, keyText)) / 2,
                    y + 13, KEYCAP_TEXT);
        } else {
            Gui.drawRect(keyX + 5, y + 16, keyX + 10, y + 17,
                    withAlpha(0xFFFFFF, 0x18));
        }

        drawChevronProgress(x + w - 18, y + ROW_H / 2, openProgress,
                blendColor(MUTED, accent, openProgress));

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
        int accent = accentFor(entry);

        roundRect(x, y, w, panelH, 8, withAlpha(0xFFFFFF, 0x0C));
        roundRect(x + 1, y + 1, w - 2, panelH - 2, 7, 0xE62A2A2A);
        // 和上方开关圆点连成一条的强调色细线。
        int connectorX = x - 15;
        roundRect(connectorX, y + 2, 2, panelH - 4, 1, withAlpha(accent, 0x26));
        roundRect(connectorX, y + 2, 2, Math.max(8, panelH / 3), 1,
                withAlpha(accent, 0xA8));
        Gui.drawRect(x + 6, y + 1, x + w - 6, y + 2, withAlpha(0xFFFFFF, 0x08));

        int innerX = x + SETTINGS_PAD;
        int innerY = y + SETTINGS_PAD;
        int cols = settingsColumns();
        int cellW = (w - SETTINGS_PAD * 2 - GRID_GAP * (cols - 1)) / cols;

        for (int i = 0; i < entry.settings.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = innerX + col * (cellW + GRID_GAP);
            int cy = innerY + row * (CONTROL_H + CONTROL_ROW_GAP);
            drawSetting(font, entry, entry.settings.get(i), cx, cy, cellW);
        }
    }

    private void drawSetting(FontRenderer font, Entry entry, Setting setting,
            int x, int y, int w) {
        int accent = accentFor(entry);
        long now = frameNow != 0L ? frameNow : System.currentTimeMillis();
        String key = settingKey(entry, setting);
        if (setting.kind == Setting.SLIDER) {
            boolean hovered = isHovered(lastMouseX, lastMouseY, x, y + 7, w, 16);
            boolean dragging = dragSetting == setting;
            ControlAnim anim = control("slider:" + key, hovered, dragging, now);
            float hi = Math.max(anim.hover, anim.active);

            micro(font, setting.label, x, y,
                    blendColor(0xFF73808F, 0xFFA9B5C6, hi * 0.75F));
            String value = setting.display();
            micro(font, value, x + w - microWidth(font, value), y,
                    blendColor(SLIDER_VALUE, 0xFFFFFFFF, hi * 0.55F));

            int trackY = y + 15;
            roundRect(x, trackY, w, 4, 2,
                    blendColor(withAlpha(0xFFFFFF, 0x12), withAlpha(0xFFFFFF, 0x26), hi));
            double pct = setting.percent();
            int fillW = (int) Math.round((w - 1) * pct);
            if (fillW > 0) {
                roundRect(x, trackY, fillW, 4, 2,
                        withAlpha(accent, Math.round(0xE2 + 0x1D * hi)));
                if (fillW > 5) {
                    Gui.drawRect(x + 2, trackY, x + fillW - 1, trackY + 1,
                            withAlpha(0xFFFFFF, Math.round(0x34 + 0x14 * hi)));
                }
            }
            int thumbX = x + clampInt((int) Math.round((w - 1) * pct), 0, w - 1);
            int glow = 6 + Math.round(hi * 2.0F);
            fillCircle(thumbX, trackY + 2, glow,
                    withAlpha(accent, Math.round(0x18 + 0x12 * hi)));
            fillCircle(thumbX, trackY + 2, 4,
                    withAlpha(accent, Math.round(0x40 + 0x28 * hi)));
            fillCircle(thumbX, trackY + 2, 2, 0xFFFFFFFF);
        } else if (setting.kind == Setting.SELECT) {
            int fieldY = y + 9;
            boolean hovered = isHovered(lastMouseX, lastMouseY, x, fieldY, w, 16);
            boolean open = selectSetting == setting;
            ControlAnim anim = control("select:" + key, hovered, open, now);
            float focus = anim.active;
            float hover = anim.hover * (1.0F - focus);
            float hi = Math.max(focus, hover);
            micro(font, setting.label, x, y,
                    blendColor(0xFF73808F, 0xFFA9B5C6, hi * 0.75F));

            int fieldBorder = blendColor(withAlpha(0xFFFFFF, 0x14),
                    withAlpha(accent, 0x66), focus);
            fieldBorder = blendColor(fieldBorder, withAlpha(0xFFFFFF, 0x3C), hover * 0.7F);
            roundRect(x, fieldY, w, 16, 6, fieldBorder);
            roundRect(x + 1, fieldY + 1, w - 2, 14, 5,
                    blendColor(FIELD_SOLID, FIELD_HOVER, hi));
            Gui.drawRect(x + 4, fieldY + 1, x + w - 4, fieldY + 2,
                    withAlpha(0xFFFFFF, Math.round(0x0A + 0x0A * hi)));
            if (anim.press > 0.01F) {
                roundRect(x, fieldY, w, 16, 6,
                        withAlpha(0xFFFFFF, Math.round(0x0A * anim.press)));
            }
            String value = setting.display();
            micro(font, ellipsizeMicro(font, value, w - 22), x + 6, fieldY + 4,
                    blendColor(0xFFD3DCE9, 0xFFFFFFFF, hi * 0.65F));
            drawChevronProgress(x + w - 9, fieldY + 8, focus,
                    blendColor(0xFF7B8798, accent, hi));
        } else if (setting.kind == Setting.CHECK) {
            boolean checked = setting.checked();
            boolean hovered = isHovered(lastMouseX, lastMouseY, x, y, w, CONTROL_H);
            ControlAnim anim = control("check:" + key, hovered, checked, now);
            float on = anim.active;
            float hover = anim.hover * (1.0F - on);
            int box = 11;
            int boxX = x + 1;
            int boxY = y + 8;

            int border = blendColor(withAlpha(0xFFFFFF, 0x22),
                    withAlpha(accent, 0xF0), on);
            border = blendColor(border, withAlpha(0xFFFFFF, 0x48), hover * 0.7F);
            roundRect(boxX, boxY, box, box, 3, border);
            roundRect(boxX + 1, boxY + 1, box - 2, box - 2, 2,
                    withAlpha(0x000000, 0x50));
            if (on > 0.01F) {
                roundRect(boxX - 1, boxY - 1, box + 2, box + 2, 4,
                        withAlpha(accent, Math.round(0x2E * on)));
                roundRect(boxX, boxY, box, box, 3,
                        withAlpha(accent, Math.round(0xF0 * on)));
                roundRect(boxX, boxY, box, 4, 3,
                        withAlpha(0xFFFFFF, Math.round(0x30 * on)));
                drawCheck(boxX + 3, boxY + 6,
                        withAlpha(0xFF151515, Math.round(0xFF * on)));
            }
            int labelColor = blendColor(0xFFAEB9C9, 0xFFFFFFFF, on);
            labelColor = blendColor(labelColor, 0xFFFFFFFF, hover * 0.6F);
            micro(font, setting.label, boxX + 17, y + 10, labelColor);
        }
    }

    // ------------------------------------------------------------------
    // 底部
    // ------------------------------------------------------------------

    private void drawFooter(FontRenderer font) {
        int y = panelY + panelH - FOOTER_H;
        int left = mainX + 18;
        int right = panelX + panelW - 18;
        Gui.drawRect(left, y, right, y + 1, withAlpha(0xFFFFFF, 0x08));
        int width = right - left;
        Gui.drawRect(left + width / 5, y, right - width / 5, y + 1,
                withAlpha(0xFFFFFF, 0x07));

        if (systemPage != null) {
            drawHint(font, right - keycapWidth(font, "RSHIFT") - 6
                    - microWidth(font, "CLOSE"), y + 7, "RSHIFT", "CLOSE");
            return;
        }

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
        roundRect(x, y, kw, 13, 5, withAlpha(0xFFFFFF, 0x16));
        roundRect(x + 1, y + 1, kw - 2, 11, 4, 0xFF4A4A4A);
        Gui.drawRect(x + 3, y + 1, x + kw - 3, y + 2, withAlpha(0xFFFFFF, 0x0C));
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
        long now = frameNow != 0L ? frameNow : System.currentTimeMillis();
        int accent = accentFor(selectEntry);
        String popupKey = settingKey(selectEntry, selectSetting);
        int w = selectFieldW;
        int h = selectSetting.options.length * 18 + 6;
        int x = Math.min(selectFieldX, panelX + panelW - 8 - w);
        int y = selectFieldY + 18;
        if (y + h > panelY + panelH - 8) {
            y = selectFieldY - h - 2;
        }
        y = Math.max(panelY + 8, y);

        roundRect(x + 2, y + 4, w, h, 8, withAlpha(0x000000, 0x55));
        roundRect(x, y, w, h, 8, withAlpha(accent, 0x55));
        roundRect(x + 1, y + 1, w - 2, h - 2, 7, withAlpha(0x0A0E16, 0xFA));
        Gui.drawRect(x + 6, y + 1, x + w - 6, y + 2, withAlpha(0xFFFFFF, 0x12));
        int current = (int) Math.round(selectSetting.value());
        for (int i = 0; i < selectSetting.options.length; i++) {
            int rowY = y + 3 + i * 18;
            boolean active = i == current;
            boolean hovered = isHovered(lastMouseX, lastMouseY, x + 1, rowY, w - 2, 18);
            ControlAnim anim = control("popup:" + popupKey + ":" + i, hovered, active, now);
            float on = anim.active;
            float hover = anim.hover * (1.0F - on);

            if (hover > 0.01F) {
                roundRect(x + 2, rowY + 1, w - 4, 16, 4,
                        withAlpha(0xFFFFFF, Math.round(0x0C * hover)));
            }
            if (on > 0.01F) {
                roundRect(x + 2, rowY + 1, w - 4, 16, 4,
                        withAlpha(accent, Math.round(0x20 * on)));
                roundRect(x + 2, rowY + 1, w - 4, 16, 4,
                        withAlpha(accent, Math.round(0x12 * on)));
            }
            if (anim.press > 0.01F) {
                roundRect(x + 2, rowY + 1, w - 4, 16, 4,
                        withAlpha(0xFFFFFF, Math.round(0x0A * anim.press)));
            }
            int textColor = blendColor(TEXT_2, 0xFFFFFFFF, on);
            textColor = blendColor(textColor, 0xFFFFFFFF, hover * 0.6F);
            micro(font, selectSetting.options[i], x + 9, rowY + 5, textColor);
            if (on > 0.01F) {
                fillCircle(x + w - 10, rowY + 9, 3,
                        withAlpha(accent, Math.round(0x34 * on)));
                fillCircle(x + w - 10, rowY + 9, 1,
                        withAlpha(accent, Math.round(0xFF * on)));
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
                press("popup:" + settingKey(selectEntry, selectSetting) + ":" + index);
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
                press("logo");
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

            if (systemPage == null
                    && isHovered(mouseX, mouseY, searchX, searchY, searchW, searchH)) {
                searchFocused = true;
                press("search");
                return;
            }
            searchFocused = false;

            if (!isHovered(mouseX, mouseY, panelX, panelY, panelW, panelH)) {
                super.mouseClicked(mouseX, mouseY, mouseButton);
                return;
            }

            // SYSTEM 入口
            if (handleSystemNavClick(mouseX, mouseY)) {
                return;
            }

            // 分类栏
            int navY = panelY + RAIL_NAV_Y;
            for (int i = 0; i < CATEGORIES.length; i++) {
                if (isHovered(mouseX, mouseY, panelX + 4,
                        navY + i * RAIL_NAV_STRIDE, railW - 12, RAIL_NAV_H)) {
                    category = CATEGORIES[i].id;
                    systemPage = null;
                    filter = 0;
                    searchQuery = "";
                    searchFocused = false;
                    selectSetting = null;
                    scroll = 0;
                    press("nav:" + category);
                    return;
                }
            }

            // SYSTEM 页面内容（页面激活时不再落到模块筛选 / 列表）
            if (systemPage != null) {
                handleSystemPageClick(mouseX, mouseY, mouseButton);
                return;
            }

            // 分段筛选
            int tabY = panelY + HEADER_H + 9;
            final int[] widths = {56, 74, 82};
            int tx = listX + 4;
            for (int i = 0; i < 3; i++) {
                if (isHovered(mouseX, mouseY, tx, tabY, widths[i] - 2, 14)) {
                    filter = i;
                    selectSetting = null;
                    scroll = 0;
                    press("tab:" + i);
                    return;
                }
                tx += widths[i];
            }

            // 排序
            if (isHovered(mouseX, mouseY, listX + listW - 110, tabY - 2, 110, 18)) {
                sortAscending = !sortAscending;
                selectSetting = null;
                press("sort");
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

    private boolean handleSystemNavClick(int mouseX, int mouseY) {
        if (panelH < RAIL_SYS_MIN_H) {
            return false;
        }
        for (int i = 0; i < SYSTEM_ITEMS.length; i++) {
            int itemY = panelY + RAIL_SYS_Y + i * RAIL_SYS_STRIDE;
            if (isHovered(mouseX, mouseY, panelX + 4, itemY, railW - 12, RAIL_SYS_H)) {
                systemPage = SYSTEM_ITEMS[i].id;
                filter = 0;
                searchQuery = "";
                searchFocused = false;
                selectSetting = null;
                selectEntry = null;
                scroll = 0;
                press("sysnav:" + systemPage);
                return true;
            }
        }
        return false;
    }

    private void handleSystemPageClick(int mouseX, int mouseY, int button) {
        if (!"settings".equals(systemPage) || button != 0) {
            return;
        }
        int x = listX;
        int y = panelY + HEADER_H + 8;
        int w = listW;
        int rowH = systemSettingsRowH();
        int gap = 8;
        for (int i = 0; i < 4; i++) {
            int rowY = y + i * (rowH + gap);
            if (!isHovered(mouseX, mouseY, x, rowY, w, rowH)) {
                continue;
            }
            if (i == 0) {
                OpenMaverFeatures.setBypassEnabled(!OpenMaverFeatures.isBypassEnabled());
            } else if (i == 1) {
                OpenMaverFeatures.setDebugEnabled(!OpenMaverFeatures.isDebugEnabled());
            } else if (i == 2) {
                int next = (OpenMaverFeatures.getBypassProfile() + 1)
                        % BYPASS_PROFILES.length;
                OpenMaverFeatures.setBypassProfile(next);
            }
            press("sysrow:" + i);
            return;
        }
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
                entry.press();
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
        int cols = settingsColumns();
        int cellW = (w - SETTINGS_PAD * 2 - GRID_GAP * (cols - 1)) / cols;

        for (int i = 0; i < entry.settings.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = innerX + col * (cellW + GRID_GAP);
            int cy = innerY + row * (CONTROL_H + CONTROL_ROW_GAP);
            Setting setting = entry.settings.get(i);

            if (setting.kind == Setting.SLIDER) {
                if (isHovered(mouseX, mouseY, cx, cy + 7, cellW, 16)) {
                    dragEntry = entry;
                    dragSetting = setting;
                    dragX = cx;
                    dragW = cellW;
                    press("slider:" + settingKey(entry, setting));
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
                    press("select:" + settingKey(entry, setting));
                } else if (setting.kind == Setting.CHECK) {
                    setting.toggle();
                    press("check:" + settingKey(entry, setting));
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
            if (systemPage == null
                    && isHovered(lastMouseX, lastMouseY, listX, listY, listW, listH)) {
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
            systemPage = null;
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

    /** 设置区列数：窗口较窄时自动降为 2 列 / 1 列，避免控件互相挤压。 */
    private int settingsColumns() {
        int available = Math.max(40, listW - 50);
        int cols = GRID_COLS;
        while (cols > 1
                && (available - SETTINGS_PAD * 2 - GRID_GAP * (cols - 1)) / cols < 74) {
            cols--;
        }
        return cols;
    }

    private int settingsHeight(Entry entry) {
        int cols = settingsColumns();
        int rows = Math.max(1, (entry.settings.size() + cols - 1) / cols);
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
        return ACCENT;
    }

    // ==================================================================
    // 文本
    // ==================================================================

    /** 正文 / 模块名：保留原始大小写（模块名由 prettyName 统一成词首大写）。 */
    private static void hud(FontRenderer font, String text, int x, int y, int color) {
        HudFont.drawString(font, text, x, y, color, 1.02F);
    }

    private static void hudBold(FontRenderer font, String text, int x, int y, int color) {
        HudFont.drawString(font, "\u00a7l" + text, x, y, color, 1.44F);
    }

    private static int hudWidth(FontRenderer font, String text) {
        return HudFont.getStringWidth(font, text, 1.02F);
    }

    private static int hudBoldWidth(FontRenderer font, String text) {
        return HudFont.getStringWidth(font, "\u00a7l" + text, 1.44F);
    }

    private static void micro(FontRenderer font, String text, int x, int y, int color) {
        HudFont.drawString(font, text, x, y, color, 0.80F);
    }

    private static int microWidth(FontRenderer font, String text) {
        return HudFont.getStringWidth(font, text, 0.80F);
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

    private static String ellipsizeHud(FontRenderer font, String text, int maxWidth) {
        if (text == null || hudWidth(font, text) <= maxWidth) {
            return text;
        }
        String value = text;
        while (value.length() > 3 && hudWidth(font, value + "...") > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "...";
    }

    // ==================================================================
    // 基础几何 / 图标
    // ==================================================================

    private static void roundRect(int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        int r = Math.min(radius, Math.min(w / 2, h / 2));
        if (r <= 1) {
            Gui.drawRect(x, y, x + w, y + h, color);
            return;
        }
        shapePolygon(x, y, w, h, r, r, r, r, color);
    }

    /** 左栏纯色底：左侧圆角、右侧与分割线齐平。 */
    private static void railPanel(int x, int y, int w, int h, float radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        float r = Math.min(radius, h / 2.0F);
        shapePolygon(x, y, w, h, r, 0.0F, 0.0F, r, color);
    }

    /**
     * 抗锯齿圆角多边形。四角用 8 段圆弧逼近，配合 GL_POLYGON_SMOOTH，
     * 任意尺寸下边缘都是连续过渡，不会出现逐行矩形的台阶。
     */
    private static void shapePolygon(int x, int y, int w, int h,
            float tl, float tr, float br, float bl, int color) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlColor.set(color);
        GL11.glBegin(GL11.GL_POLYGON);
        cornerArc(x + tl, y + tl, tl, 180.0F);
        cornerArc(x + w - tr, y + tr, tr, 270.0F);
        cornerArc(x + w - br, y + h - br, br, 0.0F);
        cornerArc(x + bl, y + h - bl, bl, 90.0F);
        GL11.glEnd();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    private static void cornerArc(float cx, float cy, float radius, float startAngle) {
        if (radius <= 0.5F) {
            GL11.glVertex2f(cx, cy);
            return;
        }
        final int segments = 8;
        for (int i = 0; i <= segments; i++) {
            double angle = Math.toRadians(startAngle + i * (90.0F / segments));
            GL11.glVertex2f(cx + (float) Math.cos(angle) * radius,
                    cy + (float) Math.sin(angle) * radius);
        }
    }

    /** 圆角行内缩量，四舍五入到像素，用于内阴影。 */
    private static int roundedInset(int radius, float rowCenter, int height) {
        return Math.round(roundedInsetF(radius, rowCenter, height));
    }

    /** 圆角行内缩量（浮点，用于抗锯齿圆角）。 */
    private static float roundedInsetF(int radius, float rowCenter, int height) {
        if (radius <= 0) {
            return 0.0F;
        }
        float dy;
        if (rowCenter < radius) {
            dy = radius - rowCenter;
        } else if (rowCenter > height - radius) {
            dy = rowCenter - (height - radius);
        } else {
            return 0.0F;
        }
        float square = radius * radius - dy * dy;
        if (square <= 0.0F) {
            return radius;
        }
        return radius - (float) Math.sqrt(square);
    }

    /** 按比例缩放 ARGB 的 alpha（用于打开动画的整体淡入）。 */
    private static int scaleAlpha(int argb, float factor) {
        int alpha = Math.round((argb >>> 24) * Math.max(0.0F, factor));
        return withAlpha(argb, Math.min(255, alpha));
    }

    private static void fillCircle(int cx, int cy, int radius, int color) {
        if (radius <= 0 || (color >>> 24) == 0) {
            return;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlColor.set(color);
        int steps = Math.max(16, Math.min(48, radius * 8));
        GL11.glBegin(GL11.GL_POLYGON);
        for (int i = 0; i < steps; i++) {
            double angle = Math.PI * 2.0D * i / steps;
            GL11.glVertex2f(cx + (float) Math.cos(angle) * radius,
                    cy + (float) Math.sin(angle) * radius);
        }
        GL11.glEnd();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    private static void ringCircle(int cx, int cy, int radius, int color) {
        if (radius <= 0 || (color >>> 24) == 0) {
            return;
        }
        int steps = Math.max(24, radius * 10);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_LINE_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GlColor.set(color);
        GL11.glLineWidth(1.0F);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < steps; i++) {
            double angle = Math.PI * 2.0D * i / steps;
            GL11.glVertex2f(cx + (float) Math.cos(angle) * radius,
                    cy + (float) Math.sin(angle) * radius);
        }
        GL11.glEnd();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    /** 小图标用的浮点版本；像素级绘制取整即可。 */
    private static void ringCircle(float cx, float cy, float radius, int color) {
        ringCircle(Math.round(cx), Math.round(cy), Math.round(radius), color);
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

    /** 展开进度在 0..1 之间时，V 形箭头会平滑翻转而不是瞬间跳变。 */
    private static void drawChevronProgress(int cx, int cy, float progress, int color) {
        float p = Math.max(0.0F, Math.min(1.0F, progress));
        float outerY = cy - 2.0F + 3.0F * p;
        float middleY = cy + 1.0F - 3.0F * p;
        line(cx - 3, outerY, cx, middleY, color);
        line(cx, middleY, cx + 3, outerY, color);
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
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GlColor.set(red, green, blue, alpha);
        GL11.glLineWidth(1.2F);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x2, y2);
        GL11.glEnd();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    private static void drawCategoryIcon(String id, int x, int y, int color) {
        if ("combat".equals(id)) {
            // 八边形准星：四向刻度 + 中心竖线（对应设计稿的 Combat 图标）。
            float cx = x + 5.0F;
            float cy = y + 5.0F;
            float radius = 3.55F;
            float previousX = cx;
            float previousY = cy - radius;
            for (int i = 1; i <= 8; i++) {
                double angle = Math.toRadians(-90.0D + i * 45.0D);
                float px = cx + (float) Math.cos(angle) * radius;
                float py = cy + (float) Math.sin(angle) * radius;
                line(previousX, previousY, px, py, color);
                previousX = px;
                previousY = py;
            }
            line(cx, y, cx, cy - radius, color);
            line(cx, cy + radius, cx, y + 10, color);
            line(x, cy, cx - radius, cy, color);
            line(cx + radius, cy, x + 10, cy, color);
            line(cx, cy - 2.6F, cx, cy + 2.6F, color);
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

    private static void drawSystemIcon(String id, int x, int y, int color) {
        if ("configs".equals(id)) {
            line(x, y + 2.5F, x + 3.2F, y + 2.5F, color);
            line(x + 3.2F, y + 2.5F, x + 4.6F, y + 4.0F, color);
            line(x + 4.6F, y + 4.0F, x + 10, y + 4.0F, color);
            line(x + 10, y + 4.0F, x + 10, y + 8.2F, color);
            line(x + 10, y + 8.2F, x, y + 8.2F, color);
            line(x, y + 8.2F, x, y + 2.5F, color);
        } else if ("stats".equals(id)) {
            line(x + 1, y + 1, x + 1, y + 9, color);
            line(x + 1, y + 9, x + 9.5F, y + 9, color);
            line(x + 3.4F, y + 9, x + 3.4F, y + 6, color);
            line(x + 5.8F, y + 9, x + 5.8F, y + 3.6F, color);
            line(x + 8.2F, y + 9, x + 8.2F, y + 6.6F, color);
        } else {
            float cx = x + 5.0F;
            float cy = y + 5.0F;
            ringCircle(cx, cy, 2.7F, color);
            ringCircle(cx, cy, 1.0F, color);
            for (int i = 0; i < 8; i++) {
                double angle = Math.toRadians(i * 45.0D);
                float ix = cx + (float) Math.cos(angle) * 2.7F;
                float iy = cy + (float) Math.sin(angle) * 2.7F;
                float ox = cx + (float) Math.cos(angle) * 4.4F;
                float oy = cy + (float) Math.sin(angle) * 4.4F;
                line(ix, iy, ox, oy, color);
            }
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

    /** 采样一次按钮动画状态；同一控件每帧只调用一次。 */
    private ControlAnim control(String key, boolean hovered, boolean active, long now) {
        ControlAnim anim = controls.get(key);
        if (anim == null) {
            anim = new ControlAnim(now);
            controls.put(key, anim);
        }
        float delta = Math.min(50.0F, Math.max(0.0F, now - anim.at));
        anim.hover = approach(anim.hover, hovered ? 1.0F : 0.0F, delta, HOVER_MS);
        anim.active = approach(anim.active, active ? 1.0F : 0.0F, delta, ACTIVE_MS);
        anim.press = approach(anim.press, 0.0F, delta, PRESS_MS);
        anim.at = now;
        return anim;
    }

    /** 点击时给按钮一次短暂回弹，让鼠标操作也有平滑的收尾反馈。 */
    private void press(String key) {
        ControlAnim anim = controls.get(key);
        if (anim != null) {
            anim.press = 1.0F;
        }
    }

    private static String settingKey(Entry entry, Setting setting) {
        return entry.id + ":" + setting.label;
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
        /** 渐变的第二个色标，让强调色有方向感。 */
        final int color2;

        Category(String id, String name, String description, int color, int color2) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.color = color;
            this.color2 = color2;
        }
    }

    private static final class SystemItem {
        final String id;
        final String name;
        final String description;

        SystemItem(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
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

    /** 单个可交互控件的平滑状态：悬停、激活与点击回弹。 */
    private static final class ControlAnim {
        float hover;
        float active;
        float press;
        long at;

        ControlAnim(long now) {
            this.at = now;
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
        float pressProgress;
        long animationAt;
        long toggleAt;
        long expandAt;
        long pressAt;

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
            if ("bypass".equals(featureId)) {
                return OpenMaverFeatures.isBypassEnabled();
            }
            return false;
        }

        void press() {
            pressProgress = 1.0F;
            pressAt = System.currentTimeMillis();
        }

        void toggle() {
            boolean before = isEnabled();
            if (module != null) {
                module.setEnabled(!module.isEnabled());
            } else if ("aim".equals(featureId)) {
                OpenMaverFeatures.toggleAim();
            } else if ("esp".equals(featureId)) {
                OpenMaverFeatures.toggleEsp();
            } else if ("bypass".equals(featureId)) {
                OpenMaverFeatures.setBypassEnabled(!OpenMaverFeatures.isBypassEnabled());
            }
            boolean after = isEnabled();
            if (after != before) {
                OpenMaverHud.notifyToggle(name, after);
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
            if ("bypass".equals(featureId)) {
                return OpenMaverFeatures.getBypassProfileName()
                        + "  ·  REACH " + format(OpenMaverFeatures.getEffectiveReachDistance(), 2) + " m";
            }
            if (module != null) {
                if ("AutoSprint".equals(module.getName())) {
                    return OpenMaverFeatures.isSprintOnlyForward()
                            ? "AUTO  ·  FORWARD" : "AUTO  ·  OMNI";
                }
                if ("AutoClicker".equals(module.getName())) {
                    return OpenMaverFeatures.getAutoClickerCpsMin() + "-"
                            + OpenMaverFeatures.getAutoClickerCpsMax() + " CPS"
                            + "  ·  " + OpenMaverFeatures.getAutoClickerAttackModeName();
                }
                if ("Reach".equals(module.getName())) {
                    return "CAP " + format(OpenMaverFeatures.getEffectiveReachDistance(), 2)
                            + " m  ·  " + Math.round(OpenMaverFeatures.getReachChance()) + "%";
                }
                if ("Criticals".equals(module.getName())) {
                    String mode = OpenMaverFeatures.getCriticalsModeName();
                    if (OpenMaverFeatures.isCriticalsDisabledByProfile()) {
                        mode += "  ·  OFF (LEGIT)";
                    } else if (OpenMaverFeatures.isCriticalsPacketSuppressed()) {
                        mode += "  ·  SAFE→JUMP";
                    }
                    return mode
                            + (OpenMaverFeatures.isCriticalsStopSprint() ? "  ·  STOP SPRINT" : "");
                }
                if ("FakePlayer".equals(module.getName())) {
                    int count = OpenMaverFeatures.getFakePlayerCount();
                    return count + (count == 1 ? " BOT" : " BOTS")
                            + "  ·  " + Math.round(OpenMaverFeatures.getFakePlayerDistance()) + " m";
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
