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
import java.io.File;
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
 * <p>字体：模块名 / 分类名用打包的 Source Code Pro（{@link HudFont}），
 * 微型标签、数值、键帽用 Minecraft 自带字体，形成第二层级（数值天然接近等宽）。
 *
 * <p>真实数据：模块来自 {@link ModuleManager}，Aim Assist / Player ESP 来自
 * {@link OpenMaverFeatures}；两者都可以在展开区直接调参并持久化。
 *
 * <p>注意：这些是 MCP 名，注入前会由 {@code tools/McpReobf} 改成 SRG 名。
 */
@SideOnly(Side.CLIENT)
public final class GuiOpenMaverClickGui extends GuiScreen {

    /** ClickGUI 文字整体缩小 5%，绘制与宽度测量使用相同比例。 */
    private static final float FONT_SCALE = 0.95F;

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
    private static final int RAIL_SYS_LABEL_Y = 220;
    private static final int RAIL_SYS_Y = 231;
    private static final int RAIL_SYS_STRIDE = 23;
    private static final int RAIL_SYS_H = 21;
    private static final int RAIL_SYS_MIN_H = 352;

    private static final int RADIUS = 22;
    /** 模块卡片使用更饱满的圆角，和左侧圆形开关保持同一套圆润语言。 */
    private static final int CARD_RADIUS = 16;
    /** 模块行左侧开关图标的半径（含外圈）。 */
    private static final int TOGGLE_RADIUS = 10;
    /** 圆角/圆形的边缘羽化宽度（GUI 单位）；与主菜单保持一致的柔和描边。 */
    private static final float AA_FEATHER = 1.0F;
    /** 每个圆角使用的圆弧段数；越高越圆滑。 */
    private static final int AA_ARC_SEGMENTS = 24;
    /** 圆角多边形的采样点数量（4 个角 × 每个角的采样点）。 */
    private static final int AA_POINT_COUNT = 4 * (AA_ARC_SEGMENTS + 1);
    private static final float[] AA_INNER_X = new float[AA_POINT_COUNT];
    private static final float[] AA_INNER_Y = new float[AA_POINT_COUNT];
    private static final float[] AA_OUTER_X = new float[AA_POINT_COUNT];
    private static final float[] AA_OUTER_Y = new float[AA_POINT_COUNT];

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

    /** 纯灰色面板 / 分类栏 / 卡片，半透明区域直接透出清晰背景。 */
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

    /** 左栏 SYSTEM 入口（对应设计稿的 Configs / Statistics / Wallpaper / Settings）。 */
    private static final SystemItem[] SYSTEM_ITEMS = {
        new SystemItem("configs", "Configs", "Profiles, storage and active settings."),
        new SystemItem("stats", "Statistics", "Module counters and category activity."),
        new SystemItem("wallpaper", "Wallpaper", "Main menu static images and animated backgrounds."),
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
    private static final String[] KILLAURA_TARGET_MODES = {"Single", "Switch"};
    private static final String[] KILLAURA_SORTS = {"Distance", "Health"};
    /** OpenZen Scaffold 的四种模式。 */
    private static final String[] SCAFFOLD_MODES = ScaffoldFeature.MODE_NAMES;
    /** Scaffold 手持方块来源：SILENT（伪装换手）/ HOTBAR（真实换手）。 */
    private static final String[] SCAFFOLD_SLOT_MODES = ScaffoldFeature.SLOT_NAMES;
    /** GodBridge 的三种教材手法。 */
    private static final String[] GODBRIDGE_MODES = GodBridgeFeature.MODE_NAMES;
    private static final String[] SCAFFOLD2_MODES = Scaffold2Feature.MODE_NAMES;
    private static final String[] CHEST_COLOR_NAMES = {
            "Gold", "Cyan", "Violet", "Red", "White"};
    private static final String[] CHEST_TAKE_FILTERS = {"ALL", "NO JUNK", "VALUABLE"};
    private static final String[] CHEST_STORE_FILTERS = {"ALL", "JUNK", "VALUABLE"};

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private final GuiScreen returnScreen;
    private final String initialSystemPage;
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

    /** 打开 / 关闭时的缩放动画：从略小的面板放大展开，关闭时反向收回。 */
    private static final float OPEN_DURATION_MS = 220.0F;
    private static final float CLOSE_DURATION_MS = 210.0F;
    private static final float OPEN_SCALE_FROM = 0.82F;

    private int scroll;
    private float scrollVisual;
    /** 壁纸页文件列表的滚动偏移。 */
    private int wallpaperScroll;
    private long lastAnimationAt;
    private long frameNow;
    private float openProgress;
    private boolean closing;
    private long closingAt;
    private float closingFrom;
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

    /** 上一帧到这一帧的毫秒数（已截断到 50ms），给列式界面的平滑动画用。 */
    private float frameDelta;
    /** 进入当前视图（列式 / SYSTEM 页面）的时刻，用于两种视图之间的淡入。 */
    private long modeAt;

    /** 出错后停用绘制，避免崩溃。 */
    private boolean failed;
    private boolean drawLogged;

    public GuiOpenMaverClickGui(GuiScreen returnScreen) {
        this(returnScreen, null);
    }

    /** Opens the ClickGUI directly on one of the SYSTEM pages ({@code configs}/{@code stats}/{@code settings}). */
    public GuiOpenMaverClickGui(GuiScreen returnScreen, String initialSystemPage) {
        this.returnScreen = returnScreen;
        this.initialSystemPage = initialSystemPage;
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
        this.closing = false;
        this.closingAt = 0L;
        this.closingFrom = 0.0F;
        this.systemPage = initialSystemPage;
        // 打开面板：短促高音，和模块开关的“开启”音同调。
        OpenMaverFeatures.playUiClick(1.15F);
        Keyboard.enableRepeatEvents(true);
        layout();
        buildEntries();

        expanded.clear();
        this.modeAt = this.openedAt;
        this.columnsAt = this.openedAt;
        initColumns();

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
        HudFont.useHudFont();
        if (failed) {
            return;
        }
        long now = System.currentTimeMillis();
        this.frameNow = now;
        float frameDelta = Math.min(50.0F, Math.max(0.0F, now - lastAnimationAt));
        lastAnimationAt = now;
        if (closing) {
            float closeT = clamp01((now - closingAt) / CLOSE_DURATION_MS);
            // 关闭时使用两端都平滑的 S 曲线，画面不会在最后一帧突然消失。
            openProgress = closingFrom * (1.0F - easeInOutCubic(closeT));
            if (closeT >= 1.0F) {
                finishClose();
                return;
            }
        } else {
            float openT = clamp01((now - openedAt) / OPEN_DURATION_MS);
            openProgress = easeOutCubic(openT);
        }
        scrollVisual = approach(scrollVisual, scroll, frameDelta, 180.0F);
        this.frameDelta = frameDelta;
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
            if (systemPage == null) {
                updateColumnPress(mouseX, mouseY);
                drawColumns();
            } else {
                drawGui();
            }
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
            } else if ("KillAura".equals(module.getName())) {
                addKillAuraSettings(entry);
            } else if ("FastPlace".equals(module.getName())) {
                addFastPlaceSettings(entry);
            } else if ("Scaffold".equals(module.getName())) {
                addScaffoldSettings(entry);
            } else if ("GodBridge".equals(module.getName())) {
                addGodBridgeSettings(entry);
            } else if ("Scaffold2".equals(module.getName())) {
                addScaffold2Settings(entry);
            } else if ("Velocity".equals(module.getName())) {
                addAttackReduceSettings(entry);
            } else if ("FakePlayer".equals(module.getName())) {
                addFakePlayerSettings(entry);
            } else if ("Target HUD".equals(module.getName())) {
                addTargetHudSettings(entry);
            } else if ("Player X-Ray".equals(module.getName())) {
                addPlayerXraySettings(entry);
            } else if ("Motion Blur".equals(module.getName())) {
                addMotionBlurSettings(entry);
            } else if ("Chest ESP".equals(module.getName())) {
                addChestEspSettings(entry);
            } else if ("Chest Stealer".equals(module.getName())) {
                addChestStealerSettings(entry);
            } else if ("Inventory Manager".equals(module.getName())) {
                addInventoryManagerSettings(entry);
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
        // 落点抖动：0 = 精确打命中盒中心（机械感最强），1 = 贴命中盒边缘。
        entry.settings.add(Setting.slider("JITTER", 0.0F, 1.0F, 0.05F, 2, "", new Binding() {
            public double get() { return OpenMaverFeatures.getAimJitter(); }
            public void set(double value) { OpenMaverFeatures.setAimJitter((float) value); }
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

    private void addMotionBlurSettings(Entry entry) {
        entry.settings.add(Setting.slider("STRENGTH", 0.05F, 1.0F, 0.05F, 2, "", new Binding() {
            public double get() { return OpenMaverFeatures.getMotionBlurStrength(); }
            public void set(double value) { OpenMaverFeatures.setMotionBlurStrength((float) value); }
        }));
    }

    // ------------------------------------------------------------------
    // 箱子：ESP / 开箱搬运（可见界面 / 静默）
    // ------------------------------------------------------------------

    private void addChestEspSettings(Entry entry) {
        entry.settings.add(Setting.slider("RANGE", 8.0F, 128.0F, 4.0F, 0, " m", new Binding() {
            public double get() { return ChestFeature.getEspRange(); }
            public void set(double value) { ChestFeature.setEspRange((float) value); }
        }));
        entry.settings.add(Setting.slider("THICKNESS", 0.5F, 4.0F, 0.5F, 1, "px", new Binding() {
            public double get() { return ChestFeature.getEspThickness(); }
            public void set(double value) { ChestFeature.setEspThickness((float) value); }
        }));
        entry.settings.add(Setting.slider("OPACITY", 0.15F, 1.0F, 0.05F, 2, "", new Binding() {
            public double get() { return ChestFeature.getEspOpacity(); }
            public void set(double value) { ChestFeature.setEspOpacity((float) value); }
        }));
        entry.settings.add(Setting.select("COLOR", CHEST_COLOR_NAMES, new Binding() {
            public double get() { return ChestFeature.getEspColor(); }
            public void set(double value) { ChestFeature.setEspColor((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("STORAGE BLOCKS", new Binding() {
            public double get() { return ChestFeature.isEspStorageBlocks() ? 1.0D : 0.0D; }
            public void set(double value) { ChestFeature.setEspStorageBlocks(value > 0.5D); }
        }));
    }

    private void addChestStealerSettings(Entry entry) {
        entry.settings.add(Setting.select("TAKE", CHEST_TAKE_FILTERS, new Binding() {
            public double get() { return ChestFeature.getTakeFilter(); }
            public void set(double value) { ChestFeature.setTakeFilter((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.select("STORE", CHEST_STORE_FILTERS, new Binding() {
            public double get() { return ChestFeature.getStoreFilter(); }
            public void set(double value) { ChestFeature.setStoreFilter((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("CLICKS/TICK", 1.0F, 9.0F, 1.0F, 0, "", new Binding() {
            public double get() { return ChestFeature.getClicksPerTick(); }
            public void set(double value) { ChestFeature.setClicksPerTick((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("ONE BY ONE", new Binding() {
            public double get() { return ChestFeature.isOneByOne() ? 1.0D : 0.0D; }
            public void set(double value) { ChestFeature.setOneByOne(value > 0.5D); }
        }));
        entry.settings.add(Setting.slider("CLICK GAP", 0.0F, 10.0F, 1.0F, 0, "t", new Binding() {
            public double get() { return ChestFeature.getClickGapTicks(); }
            public void set(double value) { ChestFeature.setClickGapTicks((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("HUMANIZE", new Binding() {
            public double get() { return ChestFeature.isHumanize() ? 1.0D : 0.0D; }
            public void set(double value) { ChestFeature.setHumanize(value > 0.5D); }
        }));
        entry.settings.add(Setting.slider("CLICK MS", 80.0F, 800.0F, 10.0F, 0, " ms", new Binding() {
            public double get() { return ChestFeature.getClickDelayMinMs(); }
            public void set(double value) { ChestFeature.setClickDelayMinMs((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("CLICK MS+", 80.0F, 1200.0F, 10.0F, 0, " ms", new Binding() {
            public double get() { return ChestFeature.getClickDelayMaxMs(); }
            public void set(double value) { ChestFeature.setClickDelayMaxMs((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("FIRST MS", 0.0F, 1200.0F, 10.0F, 0, " ms", new Binding() {
            public double get() { return ChestFeature.getFirstClickDelayMs(); }
            public void set(double value) { ChestFeature.setFirstClickDelayMs((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("PAUSE %", 0.0F, 100.0F, 1.0F, 0, " %", new Binding() {
            public double get() { return ChestFeature.getPauseChancePct(); }
            public void set(double value) { ChestFeature.setPauseChancePct((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("SKIP %", 0.0F, 50.0F, 1.0F, 0, " %", new Binding() {
            public double get() { return ChestFeature.getSkipChancePct(); }
            public void set(double value) { ChestFeature.setSkipChancePct((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("CLOSE T", 1.0F, 20.0F, 1.0F, 0, " t", new Binding() {
            public double get() { return ChestFeature.getCloseDelayMinTicks(); }
            public void set(double value) { ChestFeature.setCloseDelayMinTicks((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("CLOSE T+", 1.0F, 40.0F, 1.0F, 0, " t", new Binding() {
            public double get() { return ChestFeature.getCloseDelayMaxTicks(); }
            public void set(double value) { ChestFeature.setCloseDelayMaxTicks((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("SWEEP CD", 100.0F, 3000.0F, 100.0F, 0, "ms", new Binding() {
            public double get() { return ChestFeature.getSweepCooldownMs(); }
            public void set(double value) { ChestFeature.setSweepCooldownMs((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("SHOW CHEST GUI", new Binding() {
            public double get() { return ChestFeature.isHideGui() ? 0.0D : 1.0D; }
            public void set(double value) { ChestFeature.setHideGui(value <= 0.5D); }
        }));
        entry.settings.add(Setting.slider("OPEN DELAY", 0.0F, 20.0F, 1.0F, 0, "t", new Binding() {
            public double get() { return ChestFeature.getOpenDelayTicks(); }
            public void set(double value) { ChestFeature.setOpenDelayTicks((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("AUTO LOOT", new Binding() {
            public double get() { return ChestFeature.isAutoLoot() ? 1.0D : 0.0D; }
            public void set(double value) { ChestFeature.setAutoLoot(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("AUTO SORT", new Binding() {
            public double get() { return ChestFeature.isAutoSort() ? 1.0D : 0.0D; }
            public void set(double value) { ChestFeature.setAutoSort(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("AUTO CLOSE", new Binding() {
            public double get() { return ChestFeature.isAutoClose() ? 1.0D : 0.0D; }
            public void set(double value) { ChestFeature.setAutoClose(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("AUTO OPEN", new Binding() {
            public double get() { return ChestFeature.isAutoOpen() ? 1.0D : 0.0D; }
            public void set(double value) { ChestFeature.setAutoOpen(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("AUTO SWEEP", new Binding() {
            public double get() { return ChestFeature.isAutoSweep() ? 1.0D : 0.0D; }
            public void set(double value) { ChestFeature.setAutoSweep(value > 0.5D); }
        }));
    }

    private void addInventoryManagerSettings(Entry entry) {
        entry.settings.add(Setting.check("AUTO ARMOR", new Binding() {
            public double get() { return InventoryManagerFeature.isAutoArmor() ? 1.0D : 0.0D; }
            public void set(double value) { InventoryManagerFeature.setAutoArmor(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("AUTO SORT", new Binding() {
            public double get() { return InventoryManagerFeature.isAutoSort() ? 1.0D : 0.0D; }
            public void set(double value) { InventoryManagerFeature.setAutoSort(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("DROP EXTRAS", new Binding() {
            public double get() { return InventoryManagerFeature.isDropExtras() ? 1.0D : 0.0D; }
            public void set(double value) { InventoryManagerFeature.setDropExtras(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("DROP TOOLS", new Binding() {
            public double get() { return InventoryManagerFeature.isDropTools() ? 1.0D : 0.0D; }
            public void set(double value) { InventoryManagerFeature.setDropTools(value > 0.5D); }
        }));
        entry.settings.add(Setting.slider("KEEP WEAPONS", 1.0F, 4.0F, 1.0F, 0, "", new Binding() {
            public double get() { return InventoryManagerFeature.getKeepWeapons(); }
            public void set(double value) { InventoryManagerFeature.setKeepWeapons((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("KEEP ARMOR", 0.0F, 3.0F, 1.0F, 0, "", new Binding() {
            public double get() { return InventoryManagerFeature.getSpareArmor(); }
            public void set(double value) { InventoryManagerFeature.setSpareArmor((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("KEEP TOOLS", 1.0F, 3.0F, 1.0F, 0, "", new Binding() {
            public double get() { return InventoryManagerFeature.getKeepTools(); }
            public void set(double value) { InventoryManagerFeature.setKeepTools((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("HUMANIZE", new Binding() {
            public double get() { return InventoryManagerFeature.isHumanize() ? 1.0D : 0.0D; }
            public void set(double value) { InventoryManagerFeature.setHumanize(value > 0.5D); }
        }));
        entry.settings.add(Setting.slider("CLICK GAP", 1.0F, 10.0F, 1.0F, 0, "t", new Binding() {
            public double get() { return InventoryManagerFeature.getClickGapTicks(); }
            public void set(double value) { InventoryManagerFeature.setClickGapTicks((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("CLICK MS", 20.0F, 800.0F, 10.0F, 0, " ms", new Binding() {
            public double get() { return InventoryManagerFeature.getActionDelayMinMs(); }
            public void set(double value) { InventoryManagerFeature.setActionDelayMinMs((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("CLICK MS+", 20.0F, 1200.0F, 10.0F, 0, " ms", new Binding() {
            public double get() { return InventoryManagerFeature.getActionDelayMaxMs(); }
            public void set(double value) { InventoryManagerFeature.setActionDelayMaxMs((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("IDLE", 5.0F, 200.0F, 5.0F, 0, "t", new Binding() {
            public double get() { return InventoryManagerFeature.getSortIntervalTicks(); }
            public void set(double value) { InventoryManagerFeature.setSortIntervalTicks((int) Math.round(value)); }
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
        entry.settings.add(Setting.check("CLICK ONLY", new Binding() {
            public double get() { return OpenMaverFeatures.isAutoClickerClickOnly() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setAutoClickerClickOnly(value > 0.5D); }
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
        entry.settings.add(Setting.check("JITTER", new Binding() {
            public double get() { return OpenMaverFeatures.isFastPlaceJitter() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setFastPlaceJitter(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("ONLY BLOCKS", new Binding() {
            public double get() { return OpenMaverFeatures.isFastPlaceOnlyBlocks() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setFastPlaceOnlyBlocks(value > 0.5D); }
        }));
    }

    /**
     * GodBridge 设置 —— 对应手动搭桥教材里的三个可调点。
     *
     * <ul>
     *   <li><b>MODE</b>：1.67 方块/秒的普通搭路 / 2 方块/秒的 45° 斜桥 /
     *       3 方块/秒的蹲起搭路；</li>
     *   <li><b>SNEAK WINDOW</b>：教材“松开 shift … 尽快按下 shift”中间松开几个 tick；</li>
     *   <li><b>EDGE MARGIN</b>：多贴边才按右键（越小越贴边、越快也越危险）。</li>
     * </ul>
     */
    private void addGodBridgeSettings(Entry entry) {
        entry.settings.add(Setting.select("MODE", GODBRIDGE_MODES, new Binding() {
            public double get() { return GodBridgeFeature.getMode(); }
            public void set(double value) { GodBridgeFeature.setMode((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("SNEAK WINDOW", 1.0F, 4.0F, 1.0F, 0, "t", new Binding() {
            public double get() { return GodBridgeFeature.getSneakWindow(); }
            public void set(double value) {
                GodBridgeFeature.setSneakWindow((int) Math.round(value));
            }
        }));
        entry.settings.add(Setting.slider("EDGE MARGIN", 0.10F, 0.45F, 0.01F, 2, "b", new Binding() {
            public double get() { return GodBridgeFeature.getEdgeMargin(); }
            public void set(double value) { GodBridgeFeature.setEdgeMargin(value); }
        }));
        entry.settings.add(Setting.check("SAFETY", new Binding() {
            public double get() { return GodBridgeFeature.isSafety() ? 1.0D : 0.0D; }
            public void set(double value) { GodBridgeFeature.setSafety(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("ITEM SPOOF", new Binding() {
            public double get() { return GodBridgeFeature.isItemSpoof() ? 1.0D : 0.0D; }
            public void set(double value) { GodBridgeFeature.setItemSpoof(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("SPEED HUD", new Binding() {
            public double get() { return GodBridgeFeature.isHud() ? 1.0D : 0.0D; }
            public void set(double value) { GodBridgeFeature.setHud(value > 0.5D); }
        }));
    }

    private void addScaffold2Settings(Entry entry) {
        entry.settings.add(Setting.select("MODE", SCAFFOLD2_MODES, new Binding() {
            public double get() { return Scaffold2Feature.getMode(); }
            public void set(double value) { Scaffold2Feature.setMode((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("RELEASE", 1.0F, 4.0F, 1.0F, 0, "t", new Binding() {
            public double get() { return Scaffold2Feature.getReleaseTicks(); }
            public void set(double value) { Scaffold2Feature.setReleaseTicks((int) Math.round(value)); }
        }));
    }

    /**
     * Scaffold 设置 —— 与 {@code ../OpenZen} 的 Scaffold 面板逐项对应。
     *
     * <p>OpenZen 里只有 8 个设置：Mode / Eagle / Sneak / Snap / Render Item Spoof /
     * Rotation Tick / Clutch，外加 HUD 元素开关。这里保持同样的集合，不再出现
     * Myau 的 Tower / KeepY / Motion / SafeWalk 之类。
     */
    private void addScaffoldSettings(Entry entry) {
        entry.settings.add(Setting.select("MODE", SCAFFOLD_MODES, new Binding() {
            public double get() { return ScaffoldFeature.getMode(); }
            public void set(double value) { ScaffoldFeature.setMode((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("EAGLE", new Binding() {
            public double get() { return ScaffoldFeature.isEagle() ? 1.0D : 0.0D; }
            public void set(double value) { ScaffoldFeature.setEagle(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("SNEAK", new Binding() {
            public double get() { return ScaffoldFeature.isSneak() ? 1.0D : 0.0D; }
            public void set(double value) { ScaffoldFeature.setSneak(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("SNAP", new Binding() {
            public double get() { return ScaffoldFeature.isSnap() ? 1.0D : 0.0D; }
            public void set(double value) { ScaffoldFeature.setSnap(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("CLUTCH", new Binding() {
            public double get() { return ScaffoldFeature.isClutch() ? 1.0D : 0.0D; }
            public void set(double value) { ScaffoldFeature.setClutch(value > 0.5D); }
        }));
        entry.settings.add(Setting.slider("ROTATION TICK", 1.0F, 6.0F, 1.0F, 0, "t", new Binding() {
            public double get() { return ScaffoldFeature.getRotationTick(); }
            public void set(double value) {
                ScaffoldFeature.setRotationTick((int) Math.round(value));
            }
        }));
        entry.settings.add(Setting.select("SLOT", SCAFFOLD_SLOT_MODES, new Binding() {
            public double get() { return ScaffoldFeature.getSlotMode(); }
            public void set(double value) { ScaffoldFeature.setSlotMode((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("ITEM SPOOF", new Binding() {
            public double get() { return ScaffoldFeature.isItemSpoof() ? 1.0D : 0.0D; }
            public void set(double value) { ScaffoldFeature.setItemSpoof(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("BLOCK HUD", new Binding() {
            public double get() { return ScaffoldFeature.isHud() ? 1.0D : 0.0D; }
            public void set(double value) { ScaffoldFeature.setHud(value > 0.5D); }
        }));
    }

    /** 输入式重置与 OpenZen NoXZ / Mix 参数。 */
    private void addAttackReduceSettings(Entry entry) {
        entry.settings.add(Setting.select("MODE", AttackReduceFeature.MODE_NAMES, new Binding() {
            public double get() { return AttackReduceFeature.getMode(); }
            public void set(double value) { AttackReduceFeature.setMode((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("ATTACK AMOUNT (NOXZ)", 1, 20, 1, 0, "", new Binding() {
            public double get() { return AttackReduceFeature.getAttackAmount(); }
            public void set(double value) { AttackReduceFeature.setAttackAmount((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("INSTANT ATTACK (NOXZ)", new Binding() {
            public double get() { return AttackReduceFeature.getInstantAttack() ? 1 : 0; }
            public void set(double value) { AttackReduceFeature.setInstantAttack(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("SPRINT STATE CHECK (NOXZ)", new Binding() {
            public double get() { return AttackReduceFeature.getSprintStateCheck() ? 1 : 0; }
            public void set(double value) { AttackReduceFeature.setSprintStateCheck(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("ROTATE (MIX)", new Binding() {
            public double get() { return AttackReduceFeature.getRotate() ? 1 : 0; }
            public void set(double value) { AttackReduceFeature.setRotate(value > 0.5D); }
        }));
        entry.settings.add(Setting.slider("CHANCE (RESETS)", 0, 100, 1, 0, "%", new Binding() {
            public double get() { return AttackReduceFeature.getChance(); }
            public void set(double value) { AttackReduceFeature.setChance((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("ACCURACY (RESETS)", 0, 100, 1, 0, "%", new Binding() {
            public double get() { return AttackReduceFeature.getAccuracy(); }
            public void set(double value) { AttackReduceFeature.setAccuracy((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("ONLY WHEN TARGETING (RESETS)", new Binding() {
            public double get() { return AttackReduceFeature.getOnlyWhenTargeting() ? 1 : 0; }
            public void set(double value) { AttackReduceFeature.setOnlyWhenTargeting(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("WATER CHECK (RESETS)", new Binding() {
            public double get() { return AttackReduceFeature.getWaterCheck() ? 1 : 0; }
            public void set(double value) { AttackReduceFeature.setWaterCheck(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("TRY ATTACK (MIX)", new Binding() {
            public double get() { return AttackReduceFeature.getTryAttack() ? 1 : 0; }
            public void set(double value) { AttackReduceFeature.setTryAttack(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("MOVEMENT OVERRIDE (MIX)", new Binding() {
            public double get() { return AttackReduceFeature.getMovementOverride() ? 1 : 0; }
            public void set(double value) { AttackReduceFeature.setMovementOverride(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("DEBUG CHAT", new Binding() {
            public double get() { return AttackReduceFeature.getDebugChat() ? 1 : 0; }
            public void set(double value) { AttackReduceFeature.setDebugChat(value > 0.5D); }
        }));
    }

    private void addKillAuraSettings(Entry entry) {
        entry.settings.add(Setting.slider("RANGE", 3.0F, 6.0F, 0.1F, 1, " m", new Binding() {
            public double get() { return OpenMaverFeatures.getKillAuraRange(); }
            public void set(double value) { OpenMaverFeatures.setKillAuraRange((float) value); }
        }));
        entry.settings.add(Setting.slider("CPS MIN", 1.0F, 20.0F, 1.0F, 0, "", new Binding() {
            public double get() { return OpenMaverFeatures.getKillAuraCpsMin(); }
            public void set(double value) { OpenMaverFeatures.setKillAuraCpsMin((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("CPS MAX", 1.0F, 20.0F, 1.0F, 0, "", new Binding() {
            public double get() { return OpenMaverFeatures.getKillAuraCpsMax(); }
            public void set(double value) { OpenMaverFeatures.setKillAuraCpsMax((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("FOV", 30.0F, 360.0F, 5.0F, 0, "\u00B0", new Binding() {
            public double get() { return OpenMaverFeatures.getKillAuraFov(); }
            public void set(double value) { OpenMaverFeatures.setKillAuraFov((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.select("TARGET", KILLAURA_TARGET_MODES, new Binding() {
            public double get() { return OpenMaverFeatures.isKillAuraSwitch() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setKillAuraSwitch(Math.round(value) == 1); }
        }));
        entry.settings.add(Setting.select("SORT", KILLAURA_SORTS, new Binding() {
            public double get() { return OpenMaverFeatures.getKillAuraSort(); }
            public void set(double value) { OpenMaverFeatures.setKillAuraSort((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("SWITCH DELAY", 0.0F, 1000.0F, 50.0F, 0, " ms", new Binding() {
            public double get() { return OpenMaverFeatures.getKillAuraSwitchDelay(); }
            public void set(double value) { OpenMaverFeatures.setKillAuraSwitchDelay((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("THROUGH WALLS", new Binding() {
            public double get() { return OpenMaverFeatures.isKillAuraThroughWalls() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setKillAuraThroughWalls(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("PLAYERS", new Binding() {
            public double get() { return OpenMaverFeatures.isKillAuraPlayers() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setKillAuraPlayers(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("MOBS", new Binding() {
            public double get() { return OpenMaverFeatures.isKillAuraMobs() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setKillAuraMobs(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("ANIMALS", new Binding() {
            public double get() { return OpenMaverFeatures.isKillAuraAnimals() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setKillAuraAnimals(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("ROTATE", new Binding() {
            public double get() { return OpenMaverFeatures.isKillAuraRotate() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setKillAuraRotate(value > 0.5D); }
        }));
        entry.settings.add(Setting.slider("SMOOTH", 0.05F, 1.0F, 0.05F, 2, "", new Binding() {
            public double get() { return OpenMaverFeatures.getKillAuraSmoothing(); }
            public void set(double value) { OpenMaverFeatures.setKillAuraSmoothing((float) value); }
        }));
        entry.settings.add(Setting.check("REQUIRE PRESS", new Binding() {
            public double get() { return OpenMaverFeatures.isKillAuraRequirePress() ? 1.0D : 0.0D; }
            public void set(double value) { OpenMaverFeatures.setKillAuraRequirePress(value > 0.5D); }
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
        float reveal = viewReveal();
        GlColor.restore();

        drawBackgroundOverlay(0.34F);

        GL11.glPushMatrix();
        // 打开时从面板中心向外放大，关闭时沿同一路径缩回。
        float scale = panelScale();
        float centerX = panelX + panelW / 2.0F;
        float centerY = panelY + panelH / 2.0F;
        float lift = (1.0F - reveal) * 6.0F;
        GL11.glTranslatef(centerX, centerY + lift, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        GL11.glTranslatef(-centerX, -centerY, 0.0F);

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

    private void drawBackgroundOverlay(float opacity) {
        if (returnScreen instanceof GuiMaverMainMenu) {
            // 主菜单不再是当前 screen，需要主动重绘背景才能加载并预览新壁纸。
            ((GuiMaverMainMenu) returnScreen).drawBackground(this.width, this.height);
        }
        // 只绘制透明遮罩，不抓屏、不降采样，背景始终保持原始清晰度。
        Gui.drawRect(0, 0, this.width, this.height,
                fade(0xFF0C0F15, opacity * openProgress * openProgress));
    }

    // ==================================================================
    // 列式 ClickGUI：每个分类一列，可拖动标题栏、点击标题折叠、右键模块展开设置
    // ==================================================================

    private static final int COL_W = 118;
    private static final int COL_HEADER_H = 20;
    private static final int COL_ROW_H = 16;
    private static final int COL_RADIUS = 6;
    private static final int COL_BOTTOM_PAD = 4;
    private static final int COL_GAP = 10;
    private static final int COL_TOP = 30;
    private static final int SET_SLIDER_H = 20;
    private static final int SET_LINE_H = 14;
    private static final int SET_PAD = 2;

    private static final int COL_HEADER_BG = 0xF2141518;
    private static final int COL_BODY_BG = 0xE01C1D21;
    private static final int COL_SETTINGS_BG = 0xF015161A;
    private static final int COL_TITLE = 0xFFF2F3F6;
    private static final int COL_ROW_TEXT = 0xFFE3E5EB;
    private static final int COL_ON_FILL = 0xFFB3B9F0;
    private static final int COL_ON_HOVER = 0xFFC4C9F7;
    private static final int COL_ON_TEXT = 0xFF1C2573;
    private static final int COL_DIM = 0xFF7C8494;

    private static final String CLIENT_COLUMN = "client";

    /** 列的位置 / 折叠状态在本次游戏会话内保留，重新打开 ClickGUI 不会复位。 */
    private static final Map<String, ColumnState> COLUMN_STATE = new HashMap<String, ColumnState>();

    /** 绘制顺序；最后一个在最上层，点击时倒序命中。 */
    private final List<Column> columns = new ArrayList<Column>();
    private long columnsAt;
    private Column pressColumn;
    private boolean pressMoved;
    private int pressStartX;
    private int pressStartY;
    private int pressOffsetX;
    private int pressOffsetY;

    private void initColumns() {
        columns.clear();
        int x = COL_GAP;
        int y = COL_TOP;
        int rowMaxBottom = y;
        for (int i = 0; i <= CATEGORIES.length; i++) {
            String id = i < CATEGORIES.length ? CATEGORIES[i].id : CLIENT_COLUMN;
            String title = i < CATEGORIES.length ? CATEGORIES[i].name : "Client";
            if (i < CATEGORIES.length && !hasEntries(id)) {
                continue;
            }
            if (x + COL_W > this.width - COL_GAP && x > COL_GAP) {
                x = COL_GAP;
                y = rowMaxBottom + COL_GAP;
            }
            ColumnState state = COLUMN_STATE.get(id);
            if (state == null) {
                state = new ColumnState(x, y);
                COLUMN_STATE.put(id, state);
            }
            state.x = clampInt(state.x, 0, Math.max(0, this.width - COL_W));
            state.y = clampInt(state.y, 0, Math.max(0, this.height - COL_HEADER_H));
            Column column = new Column(id, title, state, columns.size());
            columns.add(column);
            int estimate = COL_HEADER_H + COL_BOTTOM_PAD
                    + COL_ROW_H * (CLIENT_COLUMN.equals(id) ? SYSTEM_ITEMS.length : countCategory(id));
            rowMaxBottom = Math.max(rowMaxBottom, y + estimate);
            x += COL_W + COL_GAP;
        }
    }

    private List<Entry> columnEntries(String cat) {
        List<Entry> out = new ArrayList<Entry>();
        String query = searchQuery.toLowerCase(Locale.ROOT);
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.category.equals(cat)
                    && (query.isEmpty() || entry.name.toLowerCase(Locale.ROOT).contains(query))) {
                out.add(entry);
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    private static int columnSettingHeight(Setting setting) {
        return setting.kind == Setting.SLIDER ? SET_SLIDER_H : SET_LINE_H;
    }

    private static int columnSettingsHeight(Entry entry) {
        int h = SET_PAD * 2;
        for (int i = 0; i < entry.settings.size(); i++) {
            h += columnSettingHeight(entry.settings.get(i));
        }
        return h;
    }

    /** 列的进场动画：依次从上方滑入并淡入，关闭时随 openProgress 一起淡出。 */
    private float columnReveal(int index) {
        float t = clamp01((frameNow - columnsAt - index * 28L) / 260.0F);
        return easeOutCubic(t) * openProgress;
    }

    private static int fade(int argb, float alpha) {
        return withAlpha(argb, Math.round((argb >>> 24) * clamp01(alpha)));
    }

    private void drawColumns() {
        GlColor.restore();
        drawBackgroundOverlay(0.30F);

        FontRenderer font = this.mc.fontRendererObj;
        Column top = topColumnAt(lastMouseX, lastMouseY);
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            drawColumn(font, column, columnReveal(column.order), column == top);
        }
        drawSearchBar(font);
    }

    private void drawColumn(FontRenderer font, Column column, float alpha, boolean topmost) {
        column.hits.clear();
        if (alpha <= 0.02F) {
            return;
        }
        long now = frameNow;
        // 拖动时视觉位置快速追随目标位置，松手后也会平滑落位。
        column.visX = approach(column.visX, column.state.x, frameDelta, 40.0F);
        column.visY = approach(column.visY, column.state.y, frameDelta, 40.0F);
        if (Math.abs(column.visX - column.state.x) < 0.3F) {
            column.visX = column.state.x;
        }
        if (Math.abs(column.visY - column.state.y) < 0.3F) {
            column.visY = column.state.y;
        }
        column.collapse = approach(column.collapse, column.state.collapsed ? 1.0F : 0.0F,
                frameDelta, 150.0F);
        float open = 1.0F - easeOutCubic(column.collapse);

        int x = Math.round(column.visX);
        int y = Math.round(column.visY - (1.0F - clamp01(alpha / Math.max(0.01F, openProgress))) * 14.0F);
        boolean client = CLIENT_COLUMN.equals(column.id);
        List<Entry> list = client ? null : columnEntries(column.id);

        // 内容总高度（含正在展开的设置区）。
        int contentH = 0;
        int rows = client ? SYSTEM_ITEMS.length : list.size();
        for (int i = 0; i < rows; i++) {
            contentH += COL_ROW_H;
            if (!client) {
                Entry entry = list.get(i);
                if (!entry.settings.isEmpty()) {
                    contentH += Math.round(columnSettingsHeight(entry) * getEntryProgress(entry, now));
                }
            }
        }
        contentH += COL_BOTTOM_PAD;
        int maxBody = Math.max(COL_ROW_H * 3, this.height - y - COL_HEADER_H - 6);
        int fullBody = Math.min(contentH, maxBody);
        int maxScroll = Math.max(0, contentH - fullBody);
        column.scroll = clampInt(column.scroll, 0, maxScroll);
        column.scrollVis = approach(column.scrollVis, column.scroll, frameDelta, 90.0F);
        int bodyH = Math.round(fullBody * open);
        int totalH = COL_HEADER_H + bodyH;

        // 投影 + 外框
        roundRect(x - 1, y + 1, COL_W + 2, totalH + 2, COL_RADIUS + 1,
                fade(0x40000000, alpha));
        roundRect(x, y, COL_W, totalH, COL_RADIUS, fade(COL_BODY_BG, alpha));
        if (bodyH < COL_RADIUS) {
            roundRect(x, y, COL_W, COL_HEADER_H, COL_RADIUS, fade(COL_HEADER_BG, alpha));
        } else {
            shapePolygon(x, y, COL_W, COL_HEADER_H, COL_RADIUS, COL_RADIUS, 0, 0,
                    fade(COL_HEADER_BG, alpha));
        }

        // 标题栏
        boolean headerHover = topmost && isHovered(lastMouseX, lastMouseY, x, y, COL_W, COL_HEADER_H);
        ControlAnim headerAnim = control("col:" + column.id, headerHover, pressColumn == column && pressMoved, now);
        if (headerAnim.hover > 0.01F) {
            shapePolygon(x, y, COL_W, COL_HEADER_H, COL_RADIUS, COL_RADIUS,
                    bodyH < COL_RADIUS ? COL_RADIUS : 0, bodyH < COL_RADIUS ? COL_RADIUS : 0,
                    fade(0x0CFFFFFF, alpha * headerAnim.hover));
        }
        HudFont.drawString(font, column.title, x + 7, y + 6, fade(COL_TITLE, alpha), 1.12F * FONT_SCALE);
        drawChevronProgress(x + COL_W - 10, y + COL_HEADER_H / 2, open,
                fade(blendColor(COL_DIM, COL_TITLE, headerAnim.hover), alpha));
        column.headerX = x;
        column.headerY = y;
        column.bodyY = y + COL_HEADER_H;
        column.bodyH = bodyH;

        if (bodyH <= 0) {
            return;
        }

        int bodyTop = y + COL_HEADER_H;
        pushScissor(x, bodyTop, COL_W, bodyH);
        int rowY = bodyTop - Math.round(column.scrollVis);
        boolean bodyHoverable = topmost && open > 0.95F
                && isHovered(lastMouseX, lastMouseY, x, bodyTop, COL_W, bodyH);
        for (int i = 0; i < rows; i++) {
            boolean hovered = bodyHoverable
                    && isHovered(lastMouseX, lastMouseY, x, rowY, COL_W, COL_ROW_H);
            if (client) {
                SystemItem item = SYSTEM_ITEMS[i];
                ControlAnim anim = control("colsys:" + item.id, hovered, false, now);
                drawColumnRow(font, x, rowY, item.name, null, 0.0F, anim.hover, anim.press,
                        false, 0.0F, alpha);
                column.hits.add(Hit.system(item.id, x, rowY, COL_W, COL_ROW_H));
                rowY += COL_ROW_H;
                continue;
            }
            Entry entry = list.get(i);
            entry.hoverProgress = approach(entry.hoverProgress, hovered ? 1.0F : 0.0F,
                    Math.min(50.0F, now - entry.animationAt), HOVER_MS);
            entry.animationAt = now;
            entry.pressProgress = approach(entry.pressProgress, 0.0F,
                    Math.min(50.0F, now - entry.pressAt), PRESS_MS);
            entry.pressAt = now;
            entry.toggleProgress = approach(entry.toggleProgress, entry.isEnabled() ? 1.0F : 0.0F,
                    Math.min(50.0F, now - entry.toggleAt), 150.0F);
            entry.toggleAt = now;
            float expand = entry.settings.isEmpty() ? 0.0F : easeOutCubic(entry.expandProgress);
            boolean nextOn = i + 1 < rows && list.get(i + 1).isEnabled() && expand < 0.01F;
            drawColumnRow(font, x, rowY, entry.name, entry.keybind(), entry.toggleProgress,
                    entry.hoverProgress, entry.pressProgress, !entry.settings.isEmpty(), expand,
                    alpha);
            if (entry.toggleProgress > 0.5F && nextOn) {
                Gui.drawRect(x, rowY + COL_ROW_H - 1, x + COL_W, rowY + COL_ROW_H,
                        fade(0x24000000, alpha));
            }
            column.hits.add(Hit.entry(entry, x, rowY, COL_W, COL_ROW_H));
            rowY += COL_ROW_H;

            if (expand > 0.001F) {
                int fullH = columnSettingsHeight(entry);
                int shownH = Math.round(fullH * expand);
                int clipTop = Math.max(rowY, bodyTop);
                int clipBottom = Math.min(rowY + shownH, bodyTop + bodyH);
                if (clipBottom > clipTop) {
                    pushScissor(x, clipTop, COL_W, clipBottom - clipTop);
                    drawColumnSettings(font, column, entry, x, rowY, fullH, alpha * (0.4F + 0.6F * expand),
                            bodyHoverable && isHovered(lastMouseX, lastMouseY, x, clipTop, COL_W, clipBottom - clipTop));
                    pushScissor(x, bodyTop, COL_W, bodyH);
                }
                rowY += shownH;
            }
        }
        popScissor();

        // 内容超出时的细滚动条
        if (maxScroll > 0) {
            int trackH = bodyH - COL_BOTTOM_PAD - 2;
            int thumbH = Math.max(12, trackH * fullBody / contentH);
            int thumbY = bodyTop + 1 + Math.round((trackH - thumbH) * column.scrollVis / maxScroll);
            roundRect(x + COL_W - 3, thumbY, 2, thumbH, 1, fade(0x50FFFFFF, alpha));
        }
    }

    private void drawColumnRow(FontRenderer font, int x, int y, String name, String keybind,
            float on, float hover, float press, boolean hasSettings, float expand, float alpha) {
        if (on > 0.01F) {
            int fill = blendColor(COL_ON_FILL, COL_ON_HOVER, hover);
            Gui.drawRect(x, y, x + COL_W, y + COL_ROW_H, fade(fill, alpha * on));
        }
        if (hover > 0.01F && on < 0.99F) {
            Gui.drawRect(x, y, x + COL_W, y + COL_ROW_H,
                    fade(0x12FFFFFF, alpha * hover * (1.0F - on)));
        }
        if (press > 0.01F) {
            Gui.drawRect(x, y, x + COL_W, y + COL_ROW_H, fade(0x18FFFFFF, alpha * press));
        }
        int textColor = blendColor(COL_ROW_TEXT, COL_ON_TEXT, on);
        textColor = blendColor(textColor, on > 0.5F ? 0xFF0E1550 : 0xFFFFFFFF, hover * 0.5F);
        // 悬停时文字轻轻右移 1.5px，增加手感。
        int textX = x + 7 + Math.round(hover * 1.5F);
        int right = x + COL_W - 6;
        if (hasSettings) {
            int chevColor = blendColor(blendColor(COL_DIM, 0xFF39408A, on), textColor, expand);
            drawChevronProgress(right - 3, y + COL_ROW_H / 2, expand, fade(chevColor, alpha));
            right -= 11;
        }
        if (keybind != null && !keybind.isEmpty()) {
            String key = ellipsizeMicro(font, keybind, 30);
            int keyW = microWidth(font, key);
            micro(font, key, right - keyW, y + 5,
                    fade(blendColor(COL_DIM, 0xFF4B5299, on), alpha));
            right -= keyW + 4;
        }
        hud(font, ellipsizeHud(font, name, right - textX), textX, y + 4, fade(textColor, alpha));
    }

    private void drawColumnSettings(FontRenderer font, Column column, Entry entry, int x, int y,
            int h, float alpha, boolean hoverable) {
        long now = frameNow;
        Gui.drawRect(x, y, x + COL_W, y + h, fade(COL_SETTINGS_BG, alpha));
        Gui.drawRect(x, y, x + 2, y + h, fade(COL_ON_FILL, alpha * 0.85F));
        int cx = x + 8;
        int cw = COL_W - 14;
        int cy = y + SET_PAD;
        for (int i = 0; i < entry.settings.size(); i++) {
            Setting setting = entry.settings.get(i);
            int sh = columnSettingHeight(setting);
            boolean hovered = hoverable && isHovered(lastMouseX, lastMouseY, x, cy, COL_W, sh);
            String key = settingKey(entry, setting);
            ControlAnim anim = control("colset:" + key, hovered, dragSetting == setting, now);
            float hi = Math.max(anim.hover, anim.active);
            if (hi > 0.01F) {
                Gui.drawRect(x + 2, cy, x + COL_W, cy + sh, fade(0x0AFFFFFF, alpha * hi));
            }
            int labelColor = fade(blendColor(0xFF9AA2B2, 0xFFE6E8EE, hi), alpha);
            if (setting.kind == Setting.SLIDER) {
                micro(font, ellipsizeMicro(font, setting.label, cw - 34), cx, cy + 3, labelColor);
                String value = setting.display();
                micro(font, value, cx + cw - microWidth(font, value), cy + 3,
                        fade(0xFFFFFFFF, alpha));
                int trackY = cy + 13;
                roundRect(cx, trackY, cw, 3, 1, fade(0x26FFFFFF, alpha));
                int fillW = (int) Math.round(cw * clamp01((float) setting.percent()));
                if (fillW > 0) {
                    roundRect(cx, trackY, fillW, 3, 1, fade(COL_ON_FILL, alpha));
                }
                int thumbX = cx + fillW;
                fillCircle(thumbX, trackY + 1, 3 + Math.round(hi), fade(0xFFFFFFFF, alpha));
                addSettingHit(column, entry, setting, x, cy, sh, cx, cw);
            } else if (setting.kind == Setting.SELECT) {
                micro(font, ellipsizeMicro(font, setting.label, cw / 2), cx, cy + 4, labelColor);
                String value = ellipsizeMicro(font, setting.display(), cw / 2);
                micro(font, value, cx + cw - microWidth(font, value), cy + 4,
                        fade(blendColor(COL_ON_FILL, 0xFFFFFFFF, hi * 0.5F), alpha));
                addSettingHit(column, entry, setting, x, cy, sh, cx, cw);
            } else {
                boolean checked = setting.checked();
                ControlAnim box = control("colchk:" + key, false, checked, now);
                micro(font, ellipsizeMicro(font, setting.label, cw - 14), cx, cy + 4, labelColor);
                int bx = cx + cw - 8;
                int by = cy + 3;
                roundRect(bx, by, 8, 8, 2, fade(0x30FFFFFF, alpha));
                if (box.active > 0.01F) {
                    roundRect(bx, by, 8, 8, 2, fade(COL_ON_FILL, alpha * box.active));
                    drawCheck(bx + 2, by + 4, fade(COL_ON_TEXT, alpha * box.active));
                }
                addSettingHit(column, entry, setting, x, cy, sh, cx, cw);
            }
            cy += sh;
        }
    }

    /** 设置区还在展开 / 收起动画中时不登记点击区域，避免被下一行遮住的控件抢点击。 */
    private static void addSettingHit(Column column, Entry entry, Setting setting,
            int x, int y, int h, int controlX, int controlW) {
        if (entry.expandProgress >= 0.95F) {
            column.hits.add(Hit.setting(entry, setting, x, y, COL_W, h, controlX, controlW));
        }
    }

    private void drawSearchBar(FontRenderer font) {
        float alpha = openProgress;
        boolean active = searchFocused || !searchQuery.isEmpty();
        ControlAnim anim = control("colsearch", active, active, frameNow);
        int w = 170;
        int h = 16;
        int x = (this.width - w) / 2;
        int y = this.height - h - 8;
        float shown = 0.45F + 0.55F * anim.active;
        roundRect(x, y, w, h, h / 2, fade(0xD0141518, alpha * shown));
        drawSearchIcon(x + 7, y + 3, fade(0xFF9AA2B2, alpha * shown));
        String text;
        int color;
        if (searchQuery.isEmpty()) {
            text = "Type to search  ·  right-click: settings";
            color = 0xFF7C8494;
        } else {
            text = searchQuery;
            color = 0xFFF2F3F6;
        }
        if (searchFocused && (frameNow / 500L) % 2L == 0L && !searchQuery.isEmpty()) {
            text = text + "_";
        }
        micro(font, ellipsizeMicro(font, text, w - 30), x + 21, y + 5, fade(color, alpha * shown));
        searchX = x;
        searchY = y;
        searchW = w;
        searchH = h;
    }

    private Column topColumnAt(int mx, int my) {
        for (int i = columns.size() - 1; i >= 0; i--) {
            Column column = columns.get(i);
            if (isHovered(mx, my, column.headerX, column.headerY, COL_W,
                    COL_HEADER_H + column.bodyH)) {
                return column;
            }
        }
        return null;
    }

    private void bringToFront(Column column) {
        if (columns.remove(column)) {
            columns.add(column);
        }
    }

    private void handleColumnClick(int mx, int my, int button) {
        if (isHovered(mx, my, searchX, searchY, searchW, searchH)) {
            searchFocused = true;
            return;
        }
        searchFocused = false;
        Column column = topColumnAt(mx, my);
        if (column == null) {
            return;
        }
        bringToFront(column);
        if (my < column.headerY + COL_HEADER_H) {
            if (button == 0) {
                pressColumn = column;
                pressMoved = false;
                pressStartX = mx;
                pressStartY = my;
                pressOffsetX = mx - column.state.x;
                pressOffsetY = my - column.state.y;
            } else if (button == 1) {
                toggleCollapse(column);
            }
            return;
        }
        if (column.state.collapsed) {
            return;
        }
        for (int i = 0; i < column.hits.size(); i++) {
            Hit hit = column.hits.get(i);
            if (!isHovered(mx, my, hit.x, hit.y, hit.w, hit.h)
                    || my < column.bodyY || my >= column.bodyY + column.bodyH) {
                continue;
            }
            if (hit.systemId != null) {
                if (button == 0) {
                    press("colsys:" + hit.systemId);
                    OpenMaverFeatures.playUiClick(1.0F);
                    systemPage = hit.systemId;
                    modeAt = System.currentTimeMillis();
                    layout();
                }
            } else if (hit.setting == null) {
                Entry entry = hit.entry;
                entry.press();
                if (button == 0) {
                    entry.toggle();
                } else if (button == 1 && !entry.settings.isEmpty()) {
                    toggleExpanded(entry);
                    OpenMaverFeatures.playUiClick(expanded.contains(entry.id) ? 1.1F : 0.95F);
                }
            } else {
                handleColumnSettingClick(hit, mx, button);
            }
            return;
        }
    }

    private void handleColumnSettingClick(Hit hit, int mx, int button) {
        Setting setting = hit.setting;
        if (setting.kind == Setting.SLIDER) {
            if (button == 0) {
                dragEntry = hit.entry;
                dragSetting = setting;
                dragX = hit.controlX;
                dragW = hit.controlW;
                updateDrag(mx);
            }
        } else if (setting.kind == Setting.SELECT) {
            int size = setting.options.length;
            int current = (int) Math.round(setting.value());
            int next = button == 1 ? current - 1 : current + 1;
            setting.set(((next % size) + size) % size);
            OpenMaverFeatures.flushSettings();
        } else if (button == 0) {
            setting.toggle();
            OpenMaverFeatures.flushSettings();
        }
    }

    private void toggleCollapse(Column column) {
        column.state.collapsed = !column.state.collapsed;
        OpenMaverFeatures.playUiClick(column.state.collapsed ? 0.95F : 1.1F);
    }

    /** 每帧轮询标题栏按压：移动超过 2px 视为拖动，否则松手时折叠 / 展开。 */
    private void updateColumnPress(int mx, int my) {
        if (pressColumn == null) {
            return;
        }
        if (Mouse.isButtonDown(0)) {
            if (!pressMoved && (Math.abs(mx - pressStartX) > 2 || Math.abs(my - pressStartY) > 2)) {
                pressMoved = true;
            }
            if (pressMoved) {
                ColumnState state = pressColumn.state;
                state.x = clampInt(mx - pressOffsetX, 0, Math.max(0, this.width - COL_W));
                state.y = clampInt(my - pressOffsetY, 0, Math.max(0, this.height - COL_HEADER_H));
            }
        } else {
            if (!pressMoved) {
                toggleCollapse(pressColumn);
            }
            pressColumn = null;
            pressMoved = false;
        }
    }

    private void scrollColumn(int mx, int my, int wheel) {
        Column column = topColumnAt(mx, my);
        if (column != null && my >= column.bodyY) {
            column.scroll -= Integer.signum(wheel) * COL_ROW_H * 2;
        }
    }

    private static final class ColumnState {
        int x;
        int y;
        boolean collapsed;

        ColumnState(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Column {
        final String id;
        final String title;
        final ColumnState state;
        final int order;
        final List<Hit> hits = new ArrayList<Hit>();
        float visX;
        float visY;
        float collapse;
        int scroll;
        float scrollVis;
        int headerX;
        int headerY;
        int bodyY;
        int bodyH;

        Column(String id, String title, ColumnState state, int order) {
            this.id = id;
            this.title = title;
            this.state = state;
            this.order = order;
            this.visX = state.x;
            this.visY = state.y;
            this.collapse = state.collapsed ? 1.0F : 0.0F;
            this.headerX = state.x;
            this.headerY = state.y;
        }
    }

    /** 上一帧绘制时记录的可点击区域；点击直接按它命中，保证与画面完全一致。 */
    private static final class Hit {
        Entry entry;
        Setting setting;
        String systemId;
        int x;
        int y;
        int w;
        int h;
        int controlX;
        int controlW;

        static Hit entry(Entry entry, int x, int y, int w, int h) {
            Hit hit = new Hit();
            hit.entry = entry;
            hit.x = x;
            hit.y = y;
            hit.w = w;
            hit.h = h;
            return hit;
        }

        static Hit system(String id, int x, int y, int w, int h) {
            Hit hit = entry(null, x, y, w, h);
            hit.systemId = id;
            return hit;
        }

        static Hit setting(Entry entry, Setting setting, int x, int y, int w, int h,
                int controlX, int controlW) {
            Hit hit = entry(entry, x, y, w, h);
            hit.setting = setting;
            hit.controlX = controlX;
            hit.controlW = controlW;
            return hit;
        }
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
        float reveal = viewReveal();
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
        } else if ("wallpaper".equals(systemPage)) {
            drawWallpaperPage(font, x, y, w, h);
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

    // ------------------------------------------------------------------
    // 壁纸设置页
    // ------------------------------------------------------------------

    private int wallpaperRowH() {
        int h = panelH - HEADER_H - FOOTER_H - 16;
        // 小窗口也要为文件列表保留空间，不能让四个设置行占满面板。
        return clampInt((h - 96) / 7, 16, 42);
    }

    private int wallpaperRowGap() {
        return panelH < 280 ? 2 : 6;
    }

    private int wallpaperListTop() {
        int y = panelY + HEADER_H + 8;
        return y + 4 * (wallpaperRowH() + wallpaperRowGap())
                + (panelH < 280 ? 4 : 10);
    }

    private int wallpaperListBottom() {
        return panelY + panelH - FOOTER_H - 8;
    }

    private int wallpaperFileRowH() {
        int height = Math.max(1, wallpaperListBottom() - wallpaperListTop() - 18);
        return clampInt(height / 4, 24, 34);
    }

    private void clampWallpaperScroll(int fileCount) {
        int visibleHeight = Math.max(1, wallpaperListBottom() - wallpaperListTop() - 18);
        int content = fileCount * wallpaperFileRowH();
        int max = Math.max(0, content - visibleHeight);
        wallpaperScroll = Math.max(0, Math.min(max, wallpaperScroll));
    }

    private void drawWallpaperPage(FontRenderer font, int x, int y, int w, int h) {
        int rowH = wallpaperRowH();
        int gap = wallpaperRowGap();
        drawWallpaperRow(font, x, y, w, rowH, 0, "MODE",
                MaverWallpaper.getModeName(), false);
        drawWallpaperRow(font, x, y + rowH + gap, w, rowH, 1, "FIT",
                MaverWallpaper.getFitName(), false);
        drawWallpaperRow(font, x, y + (rowH + gap) * 2, w, rowH, 2, "SOURCE",
                MaverWallpaper.getDisplayName(), MaverWallpaper.isBuiltIn());
        drawWallpaperRow(font, x, y + (rowH + gap) * 3, w, rowH, 3, "FOLDER",
                "OPEN", false);

        int listTop = wallpaperListTop();
        int listBottom = wallpaperListBottom();
        int listHeight = Math.max(1, listBottom - listTop);
        roundRect(x, listTop, w, listHeight, 8, withAlpha(0xFFFFFF, 0x08));
        roundRect(x, listTop + 2, 2, listHeight - 4, 1, withAlpha(ACCENT, 0x55));
        micro(font, "WALLPAPERS", x + 12, listTop + 5, FAINT);
        String hint = "PNG / JPG / GIF";
        micro(font, hint, x + w - 12 - microWidth(font, hint), listTop + 5, 0xFF596272);

        List<File> files = MaverWallpaper.listWallpapers();
        clampWallpaperScroll(files.size());
        int fileRowH = wallpaperFileRowH();
        int rowTop = listTop + 16 - wallpaperScroll;
        pushScissor(x, listTop + 14, w, Math.max(1, listHeight - 14));
        for (int i = 0; i < files.size(); i++) {
            if (rowTop + fileRowH > listTop + 14 && rowTop < listBottom) {
                drawWallpaperFileRow(font, x, rowTop, w, fileRowH, files.get(i));
            }
            rowTop += fileRowH;
        }
        popScissor();

        if (files.isEmpty()) {
            String empty = "Drop images into " + MaverWallpaper.getWallpaperFolderDisplay();
            micro(font, ellipsizeMicro(font, empty, w - 28), x + 14, listTop + 26,
                    0xFF7C8798);
            micro(font, "Then reopen this page / restart the client.", x + 14,
                    listTop + 40, 0xFF596272);
        }
    }

    private void drawWallpaperRow(FontRenderer font, int x, int y, int w, int rowH,
            int index, String label, String value, boolean active) {
        long now = frameNow != 0L ? frameNow : System.currentTimeMillis();
        boolean hovered = isHovered(lastMouseX, lastMouseY, x, y, w, rowH);
        ControlAnim anim = control("wallrow:" + index, hovered, active, now);
        float on = anim.active;
        float hover = anim.hover * (1.0F - on * 0.5F);

        roundRect(x, y, w, rowH, 8,
                withAlpha(active ? ACCENT : 0xFFFFFF,
                        Math.round((active ? 0x11 : 0x08) + 0x07 * hover)));
        roundRect(x, y + 2, 2, rowH - 4, 1,
                withAlpha(ACCENT, Math.round((active ? 0x88 : 0x44) + 0x40 * hover)));
        if (anim.press > 0.01F) {
            roundRect(x, y, w, rowH, 8,
                    withAlpha(0xFFFFFF, Math.round(0x0A * anim.press)));
        }
        hud(font, label, x + 14, y + (rowH - 11) / 2 + 2,
                blendColor(TEXT, 0xFFFFFFFF, hover * 0.6F));

        int right = x + w - 14;
        String shown = ellipsizeMicro(font, value, Math.max(40, w / 2));
        int pillW = microWidth(font, shown) + 16;
        int controlY = y + (rowH - 16) / 2;
        roundRect(right - pillW, controlY, pillW, 16, 6,
                withAlpha(active ? ACCENT : 0xFFFFFF,
                        Math.round((active ? 0x20 : 0x10) + 0x0C * hover)));
        micro(font, shown, right - pillW + 8, controlY + 4,
                active ? withAlpha(ACCENT, 0xF0)
                        : blendColor(TEXT_2, TEXT, hover * 0.65F));
    }

    private void drawWallpaperFileRow(FontRenderer font, int x, int y, int w,
            int rowH, File file) {
        int rowY = y + 1;
        int rowHeight = rowH - 2;
        boolean selected = MaverWallpaper.isSelected(file);
        boolean hovered = isHovered(lastMouseX, lastMouseY, x + 4, rowY, w - 8, rowHeight);
        int fill = selected ? withAlpha(ACCENT, 0x16)
                : withAlpha(0xFFFFFF, hovered ? 0x10 : 0x06);
        roundRect(x + 4, rowY, w - 8, rowHeight, 6, fill);
        if (selected) {
            roundRect(x + 4, rowY + 4, 2, rowHeight - 8, 1, withAlpha(ACCENT, 0xCC));
        }

        String name = file.getName();
        String label = ellipsizeHud(font, name, w - 84);
        int textY = rowY + (rowHeight - 11) / 2 + 1;
        hud(font, label, x + 13, textY,
                selected ? TEXT : (hovered ? 0xFFE4EBF6 : TEXT_2));

        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 && dot + 1 < name.length()
                ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "IMG";
        if (extension.length() > 5) {
            extension = extension.substring(0, 5);
        }
        int badgeW = Math.max(26, microWidth(font, extension) + 12);
        int badgeX = x + w - 12 - badgeW;
        int badgeY = rowY + (rowHeight - 13) / 2;
        roundRect(badgeX, badgeY, badgeW, 13, 5,
                withAlpha("GIF".equals(extension) ? 0xBFE9FF : 0xFFFFFF,
                        selected ? 0x28 : 0x12));
        micro(font, extension, badgeX + (badgeW - microWidth(font, extension)) / 2,
                badgeY + 3, selected ? withAlpha(ACCENT, 0xF0) : 0xFF8B96A6);
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

        // 开关图标：更大的圆形底板 + 柔和外圈，开启时用强调色光晕替代原来的小圆点。
        float toggleProgress = entry.toggleProgress;
        int dotX = x + 17;
        int dotY = y + ROW_H / 2;
        int orbRadius = TOGGLE_RADIUS - 2;
        int offRing = withAlpha(0xFFFFFF, Math.round(0x48 + 0x20 * hoverProgress));
        int onRing = withAlpha(accent, Math.round(0xC8 + 0x28 * hoverProgress));
        fillCircle(dotX, dotY, orbRadius, blendColor(
                withAlpha(0xFFFFFF, 0x0A + Math.round(0x08 * hoverProgress)),
                withAlpha(accent, 0x1A), toggleProgress));
        ringCircle(dotX, dotY, orbRadius, blendColor(offRing, onRing, toggleProgress));
        fillCircle(dotX, dotY, 3, blendColor(
                withAlpha(0xFFFFFF, 0x70), accent, toggleProgress));
        if (toggleProgress > 0.01F) {
            // 左上角一点柔和高光，让开关看起来更像一枚圆润的玻璃按钮。
            fillCircle(dotX - 2, dotY - 2, 1,
                    withAlpha(0xFFFFFF, Math.round(0x70 * toggleProgress)));
        }

        hud(font, ellipsizeHud(font, entry.name, w - 100), x + 32, y + 13,
                on ? TEXT : 0xFFB3BCCB);

        // 键帽：胶囊状圆角，悬停展开时也保持和卡片一致的圆润感。
        String keybind = entry.keybind();
        int keyX = x + w - 48;
        if (keybind != null) {
            int keyW = Math.min(26, Math.max(16, microWidth(font, keybind) + 9));
            int kx = keyX + (15 - keyW) / 2;
            int keyY = y + 9;
            int keyH = 16;
            roundRect(kx, keyY, keyW, keyH, keyH / 2, withAlpha(0xFFFFFF, 0x1E));
            roundRect(kx + 1, keyY + 1, keyW - 2, keyH - 2, (keyH - 2) / 2, 0xFF484848);
            Gui.drawRect(kx + 4, keyY + 1, kx + keyW - 4, keyY + 2,
                    withAlpha(0xFFFFFF, 0x12));
            String keyText = ellipsizeMicro(font, keybind, keyW - 6);
            micro(font, keyText, kx + (keyW - microWidth(font, keyText)) / 2,
                    keyY + 4, KEYCAP_TEXT);
        } else {
            Gui.drawRect(keyX + 5, y + 16, keyX + 10, y + 17,
                    withAlpha(0xFFFFFF, 0x18));
        }

        // 右侧展开箭头：悬停 / 展开时出现圆形底光，和左侧开关图标呼应。
        int chevX = x + w - 18;
        int chevY = y + ROW_H / 2;
        float chevronGlow = Math.max(hoverProgress, openProgress);
        if (chevronGlow > 0.01F) {
            ringCircle(chevX, chevY, 8,
                    withAlpha(0xFFFFFF, Math.round(0x12 * chevronGlow)));
        }
        drawChevronProgress(chevX, chevY, openProgress,
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

        roundRect(x, y, w, panelH, 12, withAlpha(0xFFFFFF, 0x0C));
        roundRect(x + 1, y + 1, w - 2, panelH - 2, 11, 0xE62A2A2A);
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
            roundRect(x, trackY, w, 5, 3,
                    blendColor(withAlpha(0xFFFFFF, 0x12), withAlpha(0xFFFFFF, 0x26), hi));
            double pct = setting.percent();
            int fillW = (int) Math.round((w - 1) * pct);
            if (fillW > 0) {
                roundRect(x, trackY, fillW, 5, 3,
                        withAlpha(accent, Math.round(0xE2 + 0x1D * hi)));
                if (fillW > 5) {
                    Gui.drawRect(x + 2, trackY, x + fillW - 1, trackY + 1,
                            withAlpha(0xFFFFFF, Math.round(0x34 + 0x14 * hi)));
                }
            }
            int thumbX = x + clampInt((int) Math.round((w - 1) * pct), 0, w - 1);
            int glow = 7 + Math.round(hi * 2.0F);
            fillCircle(thumbX, trackY + 2, glow,
                    withAlpha(accent, Math.round(0x18 + 0x12 * hi)));
            fillCircle(thumbX, trackY + 2, 5,
                    withAlpha(accent, Math.round(0x40 + 0x28 * hi)));
            fillCircle(thumbX, trackY + 2, 3, 0xFFFFFFFF);
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
            roundRect(x, fieldY, w, 16, 8, fieldBorder);
            roundRect(x + 1, fieldY + 1, w - 2, 14, 7,
                    blendColor(FIELD_SOLID, FIELD_HOVER, hi));
            Gui.drawRect(x + 4, fieldY + 1, x + w - 4, fieldY + 2,
                    withAlpha(0xFFFFFF, Math.round(0x0A + 0x0A * hi)));
            if (anim.press > 0.01F) {
                roundRect(x, fieldY, w, 16, 8,
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
            roundRect(boxX, boxY, box, box, 4, border);
            roundRect(boxX + 1, boxY + 1, box - 2, box - 2, 3,
                    withAlpha(0x000000, 0x50));
            if (on > 0.01F) {
                roundRect(boxX - 1, boxY - 1, box + 2, box + 2, 5,
                        withAlpha(accent, Math.round(0x2E * on)));
                roundRect(boxX, boxY, box, box, 4,
                        withAlpha(accent, Math.round(0xF0 * on)));
                roundRect(boxX, boxY, box, 4, 4,
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
        if (closing) {
            return;
        }
        try {
            lastMouseX = mouseX;
            lastMouseY = mouseY;

            if (systemPage == null) {
                handleColumnClick(mouseX, mouseY, mouseButton);
                return;
            }

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
                    columnsAt = System.currentTimeMillis();
                    modeAt = columnsAt;
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
                wallpaperScroll = 0;
                press("sysnav:" + systemPage);
                return true;
            }
        }
        return false;
    }

    private void handleSystemPageClick(int mouseX, int mouseY, int button) {
        if (button != 0) {
            return;
        }
        if ("wallpaper".equals(systemPage)) {
            handleWallpaperPageClick(mouseX, mouseY);
            return;
        }
        if (!"settings".equals(systemPage)) {
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

    private void handleWallpaperPageClick(int mouseX, int mouseY) {
        int x = listX;
        int y = panelY + HEADER_H + 8;
        int w = listW;
        int rowH = wallpaperRowH();
        int gap = wallpaperRowGap();

        for (int i = 0; i < 4; i++) {
            int rowY = y + i * (rowH + gap);
            if (!isHovered(mouseX, mouseY, x, rowY, w, rowH)) {
                continue;
            }
            if (i == 0) {
                MaverWallpaper.nextMode();
                wallpaperScroll = 0;
            } else if (i == 1) {
                MaverWallpaper.nextFit();
            } else if (i == 2) {
                // SOURCE 行点击恢复内置背景；选择具体文件请点下面的列表。
                MaverWallpaper.setFilePath("");
                wallpaperScroll = 0;
            } else if (i == 3) {
                MaverWallpaper.refreshFiles();
                MaverWallpaper.openFolder();
            }
            press("wallrow:" + i);
            return;
        }

        int listTop = wallpaperListTop();
        int listBottom = wallpaperListBottom();
        if (!isHovered(mouseX, mouseY, x, listTop + 14, w,
                Math.max(1, listBottom - listTop - 14))) {
            return;
        }
        List<File> files = MaverWallpaper.listWallpapers();
        clampWallpaperScroll(files.size());
        int fileRowH = wallpaperFileRowH();
        int rowTop = listTop + 16 - wallpaperScroll;
        for (int i = 0; i < files.size(); i++) {
            if (isHovered(mouseX, mouseY, x + 4, rowTop + 1, w - 8, fileRowH - 2)) {
                File file = files.get(i);
                MaverWallpaper.setFilePath(file.getName());
                if (MaverWallpaper.getMode() == MaverWallpaper.MODE_PANORAMA) {
                    MaverWallpaper.setMode(MaverWallpaper.isAnimatedFile(file.getName())
                            ? MaverWallpaper.MODE_DYNAMIC : MaverWallpaper.MODE_STATIC);
                }
                press("wallfile:" + file.getName());
                return;
            }
            rowTop += fileRowH;
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

                boolean onDot = mouseX >= x + 6 && mouseX < x + 28
                        && mouseY >= y + 6 && mouseY < y + 28;
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
        if (closing) {
            return;
        }
        try {
            super.handleMouseInput();
            int wheel = Mouse.getEventDWheel();
            if (wheel == 0) {
                return;
            }
            if (systemPage == null) {
                scrollColumn(lastMouseX, lastMouseY, wheel);
            } else if ("wallpaper".equals(systemPage)
                    && isHovered(lastMouseX, lastMouseY, listX, wallpaperListTop(),
                            listW, Math.max(1, wallpaperListBottom() - wallpaperListTop()))) {
                int visibleHeight = Math.max(1, wallpaperListBottom()
                        - wallpaperListTop() - 18);
                int content = MaverWallpaper.listWallpapers().size() * wallpaperFileRowH();
                int max = Math.max(0, content - visibleHeight);
                wallpaperScroll = Math.max(0,
                        Math.min(max, wallpaperScroll - Integer.signum(wheel) * 18));
            }
        } catch (Throwable t) {
            failed = true;
            System.out.println("[OpenMaver] ClickGUI 滚轮处理出错: " + t);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (closing) {
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (selectSetting != null) {
                selectSetting = null;
                selectEntry = null;
                return;
            }
            if (systemPage == null && (searchFocused || !searchQuery.isEmpty())) {
                searchQuery = "";
                searchFocused = false;
                return;
            }
            if (systemPage != null && initialSystemPage == null) {
                // 从列式界面进入的 SYSTEM 页面：ESC 先回到模块列。
                systemPage = null;
                columnsAt = System.currentTimeMillis();
                modeAt = columnsAt;
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
        if (closing) {
            return;
        }
        closing = true;
        closingAt = System.currentTimeMillis();
        closingFrom = Math.max(0.0F, Math.min(1.0F, openProgress));
        // 关闭动画期间不再响应拖拽 / 输入，避免面板缩小时误触。
        dragSetting = null;
        dragEntry = null;
        panelDragging = false;
        logoSizeDragging = false;
        pressColumn = null;
        selectSetting = null;
        selectEntry = null;
        searchFocused = false;
        OpenMaverFeatures.flushSettings();
    }

    /** 关闭动画播完后才真正切换回上一个界面。 */
    private void finishClose() {
        closing = false;
        OpenMaverFeatures.playUiClick(0.9F);
        if (this.mc != null) {
            this.mc.displayGuiScreen(this.returnScreen);
        }
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
        HudFont.drawString(font, text, x, y, color, 1.02F * FONT_SCALE);
    }

    private static void hudBold(FontRenderer font, String text, int x, int y, int color) {
        HudFont.drawString(font, "\u00a7l" + text, x, y, color, 1.44F * FONT_SCALE);
    }

    private static int hudWidth(FontRenderer font, String text) {
        return HudFont.getStringWidth(font, text, 1.02F * FONT_SCALE);
    }

    private static int hudBoldWidth(FontRenderer font, String text) {
        return HudFont.getStringWidth(font, "\u00a7l" + text, 1.44F * FONT_SCALE);
    }

    private static void micro(FontRenderer font, String text, int x, int y, int color) {
        HudFont.drawString(font, text, x, y, color, 0.80F * FONT_SCALE);
    }

    private static int microWidth(FontRenderer font, String text) {
        return HudFont.getStringWidth(font, text, 0.80F * FONT_SCALE);
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
        if (r <= 0) {
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
     * 抗锯齿圆角多边形。
     *
     * <p>核心形状保持原有圆角，外面再加一圈 1px 的羽化带：内圈顶点 alpha 为 1，
     * 外圈顶点 alpha 为 0，由 GPU 插值出连续的覆盖率。这样不依赖
     * {@code GL_POLYGON_SMOOTH}（很多驱动会直接忽略它），圆角和直边都不会再出现台阶。
     */
    private static void shapePolygon(int x, int y, int w, int h,
            float tl, float tr, float br, float bl, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        float maxRadius = Math.min(w / 2.0F, h / 2.0F);
        float innerTl = Math.min(tl, maxRadius);
        float innerTr = Math.min(tr, maxRadius);
        float innerBr = Math.min(br, maxRadius);
        float innerBl = Math.min(bl, maxRadius);

        float ox = x - AA_FEATHER;
        float oy = y - AA_FEATHER;
        float ow = w + AA_FEATHER * 2.0F;
        float oh = h + AA_FEATHER * 2.0F;
        float outerMax = Math.min(ow / 2.0F, oh / 2.0F);
        float outerTl = Math.min(innerTl + AA_FEATHER, outerMax);
        float outerTr = Math.min(innerTr + AA_FEATHER, outerMax);
        float outerBr = Math.min(innerBr + AA_FEATHER, outerMax);
        float outerBl = Math.min(innerBl + AA_FEATHER, outerMax);

        float[] innerX = AA_INNER_X;
        float[] innerY = AA_INNER_Y;
        float[] outerX = AA_OUTER_X;
        float[] outerY = AA_OUTER_Y;
        appendRoundedRect(innerX, innerY, 0, x, y, w, h,
                innerTl, innerTr, innerBr, innerBl);
        appendRoundedRect(outerX, outerY, 0, ox, oy, ow, oh,
                outerTl, outerTr, outerBr, outerBl);

        aaBegin();

        // 实心内部：保持原始尺寸，白色/彩色区域不会被羽化带吃掉。
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        setVertexColor(color, 1.0F);
        GL11.glVertex2f(x + w / 2.0F, y + h / 2.0F);
        for (int i = 0; i < AA_POINT_COUNT; i++) {
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(innerX[i], innerY[i]);
        }
        GL11.glVertex2f(innerX[0], innerY[0]);
        GL11.glEnd();

        // 外侧很窄的羽化带：内沿不透明，外沿透明，只负责抹掉像素台阶。
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= AA_POINT_COUNT; i++) {
            int index = i == AA_POINT_COUNT ? 0 : i;
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(innerX[index], innerY[index]);
            setVertexColor(color, 0.0F);
            GL11.glVertex2f(outerX[index], outerY[index]);
        }
        GL11.glEnd();

        aaEnd();
    }

    /** 按顺时针把四个圆角的采样点写入数组。直边由相邻圆角端点直接连接。 */
    private static void appendRoundedRect(float[] xs, float[] ys, int offset,
            float x, float y, float w, float h,
            float tl, float tr, float br, float bl) {
        int index = offset;
        index = appendCorner(xs, ys, index, x + tl, y + tl, tl, 180.0F);
        index = appendCorner(xs, ys, index, x + w - tr, y + tr, tr, 270.0F);
        index = appendCorner(xs, ys, index, x + w - br, y + h - br, br, 0.0F);
        appendCorner(xs, ys, index, x + bl, y + h - bl, bl, 90.0F);
    }

    private static int appendCorner(float[] xs, float[] ys, int index,
            float cx, float cy, float radius, float startAngle) {
        for (int i = 0; i <= AA_ARC_SEGMENTS; i++) {
            double angle = Math.toRadians(startAngle
                    + i * (90.0F / AA_ARC_SEGMENTS));
            xs[index] = cx + (float) Math.cos(angle) * radius;
            ys[index] = cy + (float) Math.sin(angle) * radius;
            index++;
        }
        return index;
    }

    /** 进入逐顶点 alpha 的抗锯齿绘制状态。 */
    private static void aaBegin() {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        // 我们自己用顶点 alpha 做边缘羽化；开启多边形平滑反而会在三角形接缝处产生亮线。
        GL11.glDisable(GL11.GL_POLYGON_SMOOTH);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void aaEnd() {
        GL11.glPopAttrib();
        GlColor.restore();
    }

    /** 写入一个带 alpha 缩放的顶点颜色；alphaScale=0 即完全透明。 */
    private static void setVertexColor(int argb, float alphaScale) {
        float alpha = ((argb >>> 24) / 255.0F) * alphaScale;
        GL11.glColor4f((argb >> 16 & 0xFF) / 255.0F,
                (argb >> 8 & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F, alpha);
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
        int steps = Math.max(24, Math.min(96, radius * 6));
        float inner = radius;
        float outer = radius + AA_FEATHER;

        aaBegin();
        // 内层实心圆保持原始半径，避免小圆点/滑块被羽化带“吃掉”。
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        setVertexColor(color, 1.0F);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= steps; i++) {
            double angle = Math.PI * 2.0D * i / steps;
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(cx + (float) Math.cos(angle) * inner,
                    cy + (float) Math.sin(angle) * inner);
        }
        GL11.glEnd();

        // 外侧窄羽化带，只抹平边缘像素。
        aaRingStrip(cx, cy, inner, outer, 1.0F, 0.0F, steps, color);
        aaEnd();
    }

    private static void ringCircle(int cx, int cy, int radius, int color) {
        ringCircle((float) cx, (float) cy, (float) radius, color);
    }

    /** 浮点版本让图标圆心可以落在半像素上，避免整像素取整带来的轻微偏移。 */
    private static void ringCircle(float cx, float cy, float radius, int color) {
        if (radius <= 0.0F || (color >>> 24) == 0) {
            return;
        }
        int steps = Math.max(32, Math.min(128, Math.round(radius * 10.0F)));
        float core = 0.5F;
        float innerFeather = 0.2F;
        float outerFeather = AA_FEATHER;
        float inner = Math.max(0.0F, radius - core - innerFeather);
        float innerSolid = radius - core;
        float outerSolid = radius + core;
        float outer = radius + core + outerFeather;

        aaBegin();
        aaRingStrip(cx, cy, inner, innerSolid, 0.0F, 1.0F, steps, color);
        aaRingStrip(cx, cy, innerSolid, outerSolid, 1.0F, 1.0F, steps, color);
        aaRingStrip(cx, cy, outerSolid, outer, 1.0F, 0.0F, steps, color);
        aaEnd();
    }

    /** 用三角带画一条圆环，内外半径分别使用不同的顶点 alpha。 */
    private static void aaRingStrip(float cx, float cy, float innerRadius,
            float outerRadius, float innerAlpha, float outerAlpha,
            int steps, int color) {
        if (outerRadius <= innerRadius) {
            return;
        }
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= steps; i++) {
            double angle = Math.PI * 2.0D * i / steps;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            setVertexColor(color, innerAlpha);
            GL11.glVertex2f(cx + cos * innerRadius, cy + sin * innerRadius);
            setVertexColor(color, outerAlpha);
            GL11.glVertex2f(cx + cos * outerRadius, cy + sin * outerRadius);
        }
        GL11.glEnd();
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

    /**
     * 抗锯齿线段：用一条 4 行的三角带模拟 1px 宽的线，外沿 alpha=0、内沿 alpha=1。
     * 比 {@code GL_LINES + GL_LINE_SMOOTH} 可靠，后者在多数驱动上会被忽略。
     */
    private static void line(float x1, float y1, float x2, float y2, int color) {
        if ((color >>> 24) == 0) {
            return;
        }
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.0001F) {
            return;
        }
        float nx = -dy / length;
        float ny = dx / length;
        final float half = 0.4F;
        final float feather = 0.2F;
        float[] offsets = {-half - feather, -half, half, half + feather};
        float[] alphas = {0.0F, 1.0F, 1.0F, 0.0F};

        aaBegin();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i < offsets.length; i++) {
            float offset = offsets[i];
            setVertexColor(color, alphas[i]);
            GL11.glVertex2f(x1 + nx * offset, y1 + ny * offset);
            GL11.glVertex2f(x2 + nx * offset, y2 + ny * offset);
        }
        GL11.glEnd();
        aaEnd();
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
        } else if ("wallpaper".equals(id)) {
            // 相框 + 小山：壁纸页入口。
            line(x, y + 1, x + 10, y + 1, color);
            line(x + 10, y + 1, x + 10, y + 9, color);
            line(x + 10, y + 9, x, y + 9, color);
            line(x, y + 9, x, y + 1, color);
            line(x + 2, y + 7, x + 4.5F, y + 4.2F, color);
            line(x + 4.5F, y + 4.2F, x + 6.6F, y + 6.4F, color);
            line(x + 6.6F, y + 6.4F, x + 8.2F, y + 4.8F, color);
            line(x + 8.2F, y + 4.8F, x + 8.2F, y + 7, color);
            fillCircle(x + 7, y + 3, 1, color);
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

    /** 当前整体可见度。 */
    private float viewReveal() {
        return openProgress * easeOutCubic(clamp01((frameNow - modeAt) / OPEN_DURATION_MS));
    }

    /** 面板缩放：打开时带一点轻微回弹，关闭时直接跟随可见度收回。 */
    private float panelScale() {
        if (!closing) {
            float t = clamp01((frameNow - Math.max(openedAt, modeAt)) / OPEN_DURATION_MS);
            float spring = easeOutBack(t);
            return OPEN_SCALE_FROM + (1.0F - OPEN_SCALE_FROM) * spring;
        }
        return OPEN_SCALE_FROM + (1.0F - OPEN_SCALE_FROM) * openProgress;
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0F - Math.max(0.0F, Math.min(1.0F, value));
        return 1.0F - inverse * inverse * inverse;
    }

    private static float easeInOutCubic(float value) {
        float t = clamp01(value);
        if (t < 0.5F) {
            return 4.0F * t * t * t;
        }
        float inverse = -2.0F * t + 2.0F;
        return 1.0F - inverse * inverse * inverse / 2.0F;
    }

    /** 轻微的 ease-out-back：结束时带一点点回弹，避免缩放像生硬地推到终点。 */
    private static float easeOutBack(float value) {
        float t = clamp01(value);
        float c1 = 0.9F;
        float c3 = c1 + 1.0F;
        float p = t - 1.0F;
        return 1.0F + c3 * p * p * p + c1 * p * p;
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
            this.label = sentenceCase(label);
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.step = step;
            this.decimals = decimals;
            this.suffix = suffix;
            this.options = options == null ? null : options.clone();
            if (this.options != null) {
                for (int i = 0; i < this.options.length; i++) {
                    this.options[i] = sentenceCase(this.options[i]);
                }
            }
            this.binding = binding;
        }

        /** 展开配置的名称与选项统一为句首大写，不改动模块共享的名称数组。 */
        private static String sentenceCase(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            return lower.isEmpty() ? lower
                    : lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
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
                if ("Velocity".equals(module.getName())) {
                    return AttackReduceFeature.getSummary() + "  ·  "
                            + (AttackReduceFeature.isPacketHookInstalled() ? "PACKET" : "WAITING")
                            + "  ·  KB " + AttackReduceFeature.getKnockbackApplied()
                            + " / HIT " + AttackReduceFeature.getSprintHits() + "  ·  " + AttackReduceFeature.getStatus();
                }
                if ("MoveDisabler".equals(module.getName())) {
                    return "INVENTORY MOVE  ·  "
                            + (InventoryMoveFeature.isMovementHookInstalled() ? "READY" : "WAITING");
                }
                if ("Inventory Manager".equals(module.getName())) {
                    return (InventoryManagerFeature.isAutoArmor() ? "ARMOR" : "NO ARMOR")
                            + (InventoryManagerFeature.isAutoSort() ? "  ·  SORT" : "")
                            + (InventoryManagerFeature.isDropExtras() ? "  ·  DROP" : "")
                            + "  ·  " + InventoryManagerFeature.getSummary();
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
