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
 * <p>每个分类都是独立的可拖动、可折叠面板，右键模块展开设置。
 * Config 与 Wallpaper 也使用相同的列式布局，不再切换到侧栏式界面。
 * 所有控件由立即模式几何与打包字体绘制，支持运行期注入。
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
    // 分类
    // ------------------------------------------------------------------

    private static final Category[] CATEGORIES = {
        new Category("combat", "Combat"),
        new Category("movement", "Movement"),
        new Category("render", "Render"),
        new Category("player", "Player"),
        new Category("misc", "Misc")
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
    private final String initialColumn;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final Set<String> expanded = new HashSet<String>();

    private String searchQuery = "";
    private boolean searchFocused;

    /** 所有按钮共用的过渡时长：悬停快速、选中/激活稍慢，形成统一的“平滑滚动”手感。 */
    private static final float HOVER_MS = 130.0F;
    private static final float ACTIVE_MS = 190.0F;
    private static final float PRESS_MS = 160.0F;

    /** 列面板打开 / 关闭时淡入淡出。 */
    private static final float OPEN_DURATION_MS = 220.0F;
    private static final float CLOSE_DURATION_MS = 210.0F;
    private String profileName = "";
    private boolean profileFieldFocused;
    private boolean configPreferences;
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
    /** RANGE 正在拖动的是上限手柄。 */
    private boolean dragHigh;
    private int searchX;
    private int searchY;
    private int searchW;
    private int searchH;

    /** 上一帧到这一帧的毫秒数（已截断到 50ms），给列式界面的平滑动画用。 */
    private float frameDelta;
    /** 出错后停用绘制，避免崩溃。 */
    private boolean failed;
    private boolean drawLogged;

    public GuiOpenMaverClickGui(GuiScreen returnScreen) {
        this(returnScreen, null);
    }

    /** 将指定的新面板置于最上层（主菜单的 Wallpaper 入口也走同一个界面）。 */
    public GuiOpenMaverClickGui(GuiScreen returnScreen, String initialColumn) {
        this.returnScreen = returnScreen;
        this.initialColumn = initialColumn;
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
        // 打开面板：短促高音，和模块开关的“开启”音同调。
        OpenMaverFeatures.playUiClick(1.15F);
        Keyboard.enableRepeatEvents(true);
        buildEntries();

        expanded.clear();
        this.columnsAt = this.openedAt;
        initColumns();

        if (profileName.isEmpty()) {
            profileName = ConfigProfiles.suggestName();
        }
        focusInitialColumn();

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
            String imported = ConfigProfiles.takeImportedName();
            if (imported != null) {
                profileName = imported;
                profileFieldFocused = false;
            }
            updateColumnPress(mouseX, mouseY);
            drawColumns();
        } catch (Throwable t) {
            failed = true;
            System.out.println("[OpenMaver] ClickGUI 绘制出错，已停用: " + t);
            t.printStackTrace();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        OpenMaverHud.renderToastOverlay(this.mc.fontRendererObj, this.width, this.height);
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
            } else if ("KeepSprint".equals(module.getName())) {
                addKeepSprintSettings(entry);
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
            } else if ("Telly".equals(module.getName())) {
                addTellySettings(entry);
            } else if ("GodBridge".equals(module.getName())) {
                addGodBridgeSettings(entry);
            } else if ("Scaffold2".equals(module.getName())) {
                addScaffold2Settings(entry);
            } else if ("SafeWalk".equals(module.getName())) {
                addSafeWalkSettings(entry);
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

    /**
     * KeepSprint：默认移动朝向对齐；OMNI BOOST / PAUSE 仅控制旧模式。
     */
    private void addKeepSprintSettings(Entry entry) {
        entry.settings.add(Setting.check("ROTATE", new Binding() {
            public double get() { return KeepSprintFeature.isRotate() ? 1.0D : 0.0D; }
            public void set(double value) { KeepSprintFeature.setRotate(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("OMNI BOOST", new Binding() {
            public double get() { return KeepSprintFeature.isSpeedBoost() ? 1.0D : 0.0D; }
            public void set(double value) { KeepSprintFeature.setSpeedBoost(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("PAUSE SNEAK", new Binding() {
            public double get() { return KeepSprintFeature.isPauseSneak() ? 1.0D : 0.0D; }
            public void set(double value) { KeepSprintFeature.setPauseSneak(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("PAUSE ITEM", new Binding() {
            public double get() { return KeepSprintFeature.isPauseItem() ? 1.0D : 0.0D; }
            public void set(double value) { KeepSprintFeature.setPauseItem(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("HIT RESEND", new Binding() {
            public double get() { return KeepSprintFeature.isAttackResend() ? 1.0D : 0.0D; }
            public void set(double value) { KeepSprintFeature.setAttackResend(value > 0.5D); }
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
        // 一个双滑块同时给出 CPS 下限和上限（左侧手柄 = MIN，右侧 = MAX）。
        entry.settings.add(Setting.range("CPS", 1.0F, 20.0F, 1.0F, 0, "", new Binding() {
            public double get() { return OpenMaverFeatures.getAutoClickerCpsMin(); }
            public void set(double value) { OpenMaverFeatures.setAutoClickerCpsMin((int) Math.round(value)); }
        }, new Binding() {
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
        // DISTANCE 就是实际攻击距离；GRIM CAP 会把它压到 3.00 米（Grim 上限）。
        entry.settings.add(Setting.slider("DISTANCE", 0.0F, 5.0F, 0.05F, 2, " m", new Binding() {
            public double get() { return OpenMaverFeatures.getReachDistance(); }
            public void set(double value) { OpenMaverFeatures.setReachDistance((float) value); }
        }));
        entry.settings.add(Setting.slider("CHANCE", 1.0F, 100.0F, 1.0F, 0, " %", new Binding() {
            public double get() { return OpenMaverFeatures.getReachChance(); }
            public void set(double value) { OpenMaverFeatures.setReachChance((float) value); }
        }));
        // 默认关闭：3.00 米等于原版上限，开着就不会多打任何距离。
        entry.settings.add(Setting.check("GRIM CAP", new Binding() {
            public double get() { return OpenMaverFeatures.isReachGrimCheck() ? 1 : 0; }
            public void set(double value) { OpenMaverFeatures.setReachGrimCheck(value > 0.5D); }
        }));
        // Ping spoof：压后事务确认，放大服务端的“可能位置”窗口。0 = 关闭。
        entry.settings.add(Setting.slider("PING SPOOF", 0.0F, 400.0F, 10.0F, 0, " ms", new Binding() {
            public double get() { return OpenMaverFeatures.getReachPingSpoof(); }
            public void set(double value) { OpenMaverFeatures.setReachPingSpoof((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.slider("SPOOF JITTER", 0.0F, 150.0F, 5.0F, 0, " ms", new Binding() {
            public double get() { return OpenMaverFeatures.getReachPingJitter(); }
            public void set(double value) { OpenMaverFeatures.setReachPingJitter((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("DEBUG CHAT", new Binding() {
            public double get() { return OpenMaverFeatures.isReachDebugChat() ? 1 : 0; }
            public void set(double value) { OpenMaverFeatures.setReachDebugChat(value > 0.5D); }
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
     * SafeWalk 设置 —— 从 Scaffold 的 eagle 拆出来的独立“方块边缘潜行”。
     *
     * <ul>
     *   <li><b>DISTANCE</b>：探针沿输入方向前伸多少格开始按 Shift（越大越早接管）；</li>
     *   <li><b>ONLY GROUND</b>：只在地面生效；</li>
     *   <li><b>REQUIRE MOVING</b>：只有按着 WASD 才生效；</li>
     *   <li><b>RESPECT SNEAK</b>：玩家自己按着 Shift 时不接管；</li>
     *   <li><b>HOLD</b>：松开边缘后继续保持潜行的 tick 数（防抖动）。</li>
     * </ul>
     */
    private void addSafeWalkSettings(Entry entry) {
        entry.settings.add(Setting.slider("DISTANCE", 0.05F, 0.60F, 0.01F, 2, "b", new Binding() {
            public double get() { return SafeWalkFeature.getDistance(); }
            public void set(double value) { SafeWalkFeature.setDistance(value); }
        }));
        entry.settings.add(Setting.check("ONLY GROUND", new Binding() {
            public double get() { return SafeWalkFeature.isOnlyGround() ? 1.0D : 0.0D; }
            public void set(double value) { SafeWalkFeature.setOnlyGround(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("REQUIRE MOVING", new Binding() {
            public double get() { return SafeWalkFeature.isRequireMoving() ? 1.0D : 0.0D; }
            public void set(double value) { SafeWalkFeature.setRequireMoving(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("RESPECT SNEAK", new Binding() {
            public double get() { return SafeWalkFeature.isRespectSneak() ? 1.0D : 0.0D; }
            public void set(double value) { SafeWalkFeature.setRespectSneak(value > 0.5D); }
        }));
        entry.settings.add(Setting.slider("HOLD", 0.0F, 5.0F, 1.0F, 0, "t", new Binding() {
            public double get() { return SafeWalkFeature.getHoldTicks(); }
            public void set(double value) { SafeWalkFeature.setHoldTicks((int) Math.round(value)); }
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

    /** OpenZen Telly 两种模式及其独立参数。 */
    private void addTellySettings(Entry entry) {
        entry.settings.add(Setting.select("MODE", TellyFeature.MODE_NAMES, new Binding() {
            public double get() { return TellyFeature.getMode(); }
            public void set(double value) { TellyFeature.setMode((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("SNEAK", new Binding() {
            public double get() { return TellyFeature.isSneak() ? 1.0D : 0.0D; }
            public void set(double value) { TellyFeature.setSneak(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("CLUTCH", new Binding() {
            public double get() { return TellyFeature.isClutch() ? 1.0D : 0.0D; }
            public void set(double value) { TellyFeature.setClutch(value > 0.5D); }
        }));
        entry.settings.add(Setting.slider("ROTATION TICK", 1.0F, 6.0F, 1.0F, 0, "t", new Binding() {
            public double get() { return TellyFeature.getRotationTick(); }
            public void set(double value) { TellyFeature.setRotationTick((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.select("SLOT", SCAFFOLD_SLOT_MODES, new Binding() {
            public double get() { return TellyFeature.getSlotMode(); }
            public void set(double value) { TellyFeature.setSlotMode((int) Math.round(value)); }
        }));
        entry.settings.add(Setting.check("ITEM SPOOF", new Binding() {
            public double get() { return TellyFeature.isItemSpoof() ? 1.0D : 0.0D; }
            public void set(double value) { TellyFeature.setItemSpoof(value > 0.5D); }
        }));
        entry.settings.add(Setting.check("BLOCK HUD", new Binding() {
            public double get() { return TellyFeature.isHud() ? 1.0D : 0.0D; }
            public void set(double value) { TellyFeature.setHud(value > 0.5D); }
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

    private static final String CONFIG_COLUMN = "configs";
    private static final String WALLPAPER_COLUMN = "wallpaper";
    private static final int CONFIG_W = 146;
    private static final int WALLPAPER_W = 158;
    private static final int CFG_PINK = 0xFFFFE2E3;
    private static final int CFG_INK = 0xFF987E83;
    private String tooltip;

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
        for (int i = 0; i < CATEGORIES.length + 2; i++) {
            String id = i < CATEGORIES.length ? CATEGORIES[i].id
                    : i == CATEGORIES.length ? CONFIG_COLUMN : WALLPAPER_COLUMN;
            String title = i < CATEGORIES.length ? CATEGORIES[i].name
                    : i == CATEGORIES.length ? "Config" : "Wallpaper";
            if (i < CATEGORIES.length && !hasEntries(id)) {
                continue;
            }
            int w = Math.min(this.width, CONFIG_COLUMN.equals(id) ? CONFIG_W
                    : WALLPAPER_COLUMN.equals(id) ? WALLPAPER_W : COL_W);
            if (x + w > this.width - COL_GAP && x > COL_GAP) {
                x = COL_GAP;
                y = Math.min(rowMaxBottom + COL_GAP, Math.max(COL_TOP, this.height / 2));
                rowMaxBottom = y;
            }
            ColumnState state = COLUMN_STATE.get(id);
            if (state == null) {
                state = new ColumnState(x, y);
                COLUMN_STATE.put(id, state);
            }
            state.x = clampInt(state.x, 0, Math.max(0, this.width - w));
            state.y = clampInt(state.y, 0, Math.max(0, this.height - COL_HEADER_H - 48));
            Column column = new Column(id, title, state, columns.size(), w);
            columns.add(column);
            int estimate = COL_HEADER_H + COL_BOTTOM_PAD
                    + (isUtilityColumn(column) ? 120 : COL_ROW_H * countCategory(id));
            rowMaxBottom = Math.max(rowMaxBottom, y + estimate);
            x += w + COL_GAP;
        }
    }

    private static boolean isUtilityColumn(Column column) {
        return CONFIG_COLUMN.equals(column.id) || WALLPAPER_COLUMN.equals(column.id);
    }

    private void focusInitialColumn() {
        if (initialColumn == null) {
            return;
        }
        String id = "settings".equals(initialColumn) ? CONFIG_COLUMN : initialColumn;
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            if (id.equals(column.id)) {
                column.state.collapsed = false;
                column.collapse = 0.0F;
                column.state.y = Math.min(column.state.y,
                        Math.max(0, this.height - COL_HEADER_H - 160));
                column.visY = column.state.y;
                configPreferences = "settings".equals(initialColumn);
                bringToFront(column);
                return;
            }
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
        return setting.kind == Setting.SLIDER || setting.kind == Setting.RANGE
                ? SET_SLIDER_H : SET_LINE_H;
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
        tooltip = null;

        FontRenderer font = this.mc.fontRendererObj;
        Column top = topColumnAt(lastMouseX, lastMouseY);
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            drawColumn(font, column, columnReveal(column.order), column == top);
        }
        drawSearchBar(font);
        drawTooltip(font);
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
        int w = column.width;
        boolean utility = isUtilityColumn(column);
        List<Entry> list = utility ? Collections.<Entry>emptyList() : columnEntries(column.id);

        // 内容总高度（含正在展开的设置区）。
        int contentH = utility ? utilityContentHeight(column) : 0;
        int rows = list.size();
        for (int i = 0; i < rows; i++) {
            contentH += COL_ROW_H;
            Entry entry = list.get(i);
            if (!entry.settings.isEmpty()) {
                contentH += Math.round(columnSettingsHeight(entry) * getEntryProgress(entry, now));
            }
        }
        contentH += COL_BOTTOM_PAD;
        int maxBody = Math.max(0, this.height - y - COL_HEADER_H - 6);
        int fullBody = Math.min(contentH, maxBody);
        int maxScroll = Math.max(0, contentH - fullBody);
        column.maxScroll = maxScroll;
        column.scroll = clampInt(column.scroll, 0, maxScroll);
        column.scrollVis = approach(column.scrollVis, column.scroll, frameDelta, 90.0F);
        int bodyH = Math.round(fullBody * open);
        int totalH = COL_HEADER_H + bodyH;

        // 投影 + 外框
        roundRect(x - 1, y + 1, w + 2, totalH + 2, COL_RADIUS + 1,
                fade(0x40000000, alpha));
        roundRect(x, y, w, totalH, COL_RADIUS, fade(COL_BODY_BG, alpha));
        if (bodyH < COL_RADIUS) {
            roundRect(x, y, w, COL_HEADER_H, COL_RADIUS, fade(COL_HEADER_BG, alpha));
        } else {
            shapePolygon(x, y, w, COL_HEADER_H, COL_RADIUS, COL_RADIUS, 0, 0,
                    fade(COL_HEADER_BG, alpha));
        }

        // 标题栏
        boolean headerHover = topmost && isHovered(lastMouseX, lastMouseY, x, y, w, COL_HEADER_H);
        ControlAnim headerAnim = control("col:" + column.id, headerHover, pressColumn == column && pressMoved, now);
        if (headerAnim.hover > 0.01F) {
            shapePolygon(x, y, w, COL_HEADER_H, COL_RADIUS, COL_RADIUS,
                    bodyH < COL_RADIUS ? COL_RADIUS : 0, bodyH < COL_RADIUS ? COL_RADIUS : 0,
                    fade(0x0CFFFFFF, alpha * headerAnim.hover));
        }
        HudFont.drawString(font, column.title, x + 7, y + 6, fade(COL_TITLE, alpha), 1.12F * FONT_SCALE);
        drawChevronProgress(x + w - 10, y + COL_HEADER_H / 2, open,
                fade(blendColor(COL_DIM, COL_TITLE, headerAnim.hover), alpha));
        if (CONFIG_COLUMN.equals(column.id)) {
            drawHeaderAction(column, "new", x + w - 46, y, "+", "New profile", alpha, topmost);
            drawHeaderAction(column, "preferences", x + w - 30, y, "gear", "Settings", alpha, topmost);
        }
        column.headerX = x;
        column.headerY = y;
        column.bodyY = y + COL_HEADER_H;
        column.bodyH = bodyH;

        if (bodyH <= 0) {
            return;
        }

        int bodyTop = y + COL_HEADER_H;
        pushScissor(x, bodyTop, w, bodyH);
        int rowY = bodyTop - Math.round(column.scrollVis);
        boolean bodyHoverable = topmost && open > 0.95F
                && isHovered(lastMouseX, lastMouseY, x, bodyTop, w, bodyH);
        if (utility) {
            if (CONFIG_COLUMN.equals(column.id)) {
                drawConfigColumn(font, column, x, rowY, alpha, bodyHoverable);
            } else {
                drawWallpaperColumn(font, column, x, rowY, alpha, bodyHoverable);
            }
        }
        for (int i = 0; i < rows; i++) {
            boolean hovered = bodyHoverable
                    && isHovered(lastMouseX, lastMouseY, x, rowY, COL_W, COL_ROW_H);
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
        if (maxScroll > 0 && bodyH > 12) {
            int trackH = bodyH - COL_BOTTOM_PAD - 2;
            int thumbH = Math.min(trackH, Math.max(12, trackH * fullBody / contentH));
            int thumbY = bodyTop + 1 + Math.round((trackH - thumbH) * column.scrollVis / maxScroll);
            roundRect(x + w - 3, thumbY, 2, thumbH, 1, fade(0x50FFFFFF, alpha));
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
            } else if (setting.kind == Setting.RANGE) {
                micro(font, ellipsizeMicro(font, setting.label, cw - 56), cx, cy + 3, labelColor);
                String text = ellipsizeMicro(font,
                        setting.lowDisplay() + "-" + setting.highDisplay(), 50);
                micro(font, text, cx + cw - microWidth(font, text), cy + 3,
                        fade(0xFFFFFFFF, alpha));
                int trackY = cy + 13;
                roundRect(cx, trackY, cw, 3, 1, fade(0x26FFFFFF, alpha));
                int lowX = cx + Math.round(cw * clamp01((float) setting.lowPercent()));
                int highX = cx + Math.round(cw * clamp01((float) setting.highPercent()));
                if (highX > lowX) {
                    roundRect(lowX, trackY, highX - lowX, 3, 1, fade(COL_ON_FILL, alpha));
                }
                fillTriangle(lowX + 4, trackY + 1, lowX, trackY - 2, lowX, trackY + 4,
                        fade(0xFFFFFFFF, alpha));
                fillTriangle(highX - 4, trackY + 1, highX, trackY - 2, highX, trackY + 4,
                        fade(0xFFFFFFFF, alpha));
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
            if (isHovered(mx, my, column.headerX, column.headerY, column.width,
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
        profileFieldFocused = false;
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
            for (Hit hit : column.hits) {
                if (hit.header
                        && isHovered(mx, my, hit.x, hit.y, hit.w, hit.h)) {
                    if (button == 0) {
                        handleUtilityAction(column, hit);
                    }
                    return;
                }
            }
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
            if (hit.action != null) {
                if (button == 0) {
                    handleUtilityAction(column, hit);
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
        } else if (setting.kind == Setting.RANGE) {
            if (button == 0) {
                dragEntry = hit.entry;
                dragSetting = setting;
                dragX = hit.controlX;
                dragW = hit.controlW;
                beginRangeDrag(setting, mx, hit.controlX, hit.controlW);
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
        if (CONFIG_COLUMN.equals(column.id) && column.state.collapsed) {
            profileFieldFocused = false;
        }
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
                state.x = clampInt(mx - pressOffsetX, 0, Math.max(0, this.width - pressColumn.width));
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
            column.scroll = clampInt(column.scroll - Integer.signum(wheel) * COL_ROW_H * 2,
                    0, column.maxScroll);
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
        final int width;
        final List<Hit> hits = new ArrayList<Hit>();
        float visX;
        float visY;
        float collapse;
        int scroll;
        int maxScroll;
        float scrollVis;
        int headerX;
        int headerY;
        int bodyY;
        int bodyH;

        Column(String id, String title, ColumnState state, int order, int width) {
            this.id = id;
            this.title = title;
            this.state = state;
            this.order = order;
            this.width = width;
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
        String action;
        String value;
        boolean header;
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

        static Hit action(String action, String value, int x, int y, int w, int h) {
            Hit hit = entry(null, x, y, w, h);
            hit.action = action;
            hit.value = value;
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

    // Config 与 Wallpaper 是同一套列面板；绘制和命中共用 Hit，不存在旧页面入口。

    private int utilityContentHeight(Column column) {
        if (CONFIG_COLUMN.equals(column.id)) {
            return 74 + (configPreferences ? 72 : 0)
                    + Math.max(1, ConfigProfiles.listNames().size()) * COL_ROW_H + 18;
        }
        return 88 + Math.max(1, MaverWallpaper.listWallpapers().size()) * COL_ROW_H + 24;
    }

    private void drawHeaderAction(Column column, String action, int x, int y,
            String icon, String hint, float alpha, boolean hoverable) {
        boolean hovered = hoverable && isHovered(lastMouseX, lastMouseY, x, y + 2, 14, 16);
        if (hovered) {
            tooltip = hint;
            roundRect(x, y + 2, 14, 16, 4, fade(0x20FFFFFF, alpha));
        }
        drawActionIcon(icon, x + 7, y + 10, fade(hovered ? COL_TITLE : COL_DIM, alpha));
        Hit hit = Hit.action(action, null, x, y + 2, 14, 16);
        hit.header = true;
        column.hits.add(hit);
    }

    private void drawConfigColumn(FontRenderer font, Column column, int x, int y,
            float alpha, boolean hoverable) {
        int innerX = x + 6;
        int innerW = column.width - 12;
        boolean fieldHover = hoverable
                && isHovered(lastMouseX, lastMouseY, innerX, y + 4, innerW, 22);
        ControlAnim field = control("cfg:field", fieldHover, profileFieldFocused, frameNow);
        roundRect(innerX, y + 4, innerW, 22, 7,
                fade(blendColor(CFG_PINK, 0xFFFFF1F1, field.active), alpha));
        String label = profileName.isEmpty() ? "Profile name" : profileName + ConfigProfiles.EXTENSION;
        micro(font, ellipsizeMicro(font, label, innerW - 14), innerX + 7, y + 12,
                fade(CFG_INK, alpha));
        if (profileFieldFocused && (frameNow / 480L) % 2L == 0L) {
            int caretX = innerX + 7 + Math.min(innerW - 16,
                    microWidth(font, profileName));
            Gui.drawRect(caretX, y + 10, caretX + 1, y + 20, fade(CFG_INK, alpha));
        }
        column.hits.add(Hit.action("name", null, innerX, y + 4, innerW, 22));

        String[] actions = {"refresh", "save", "load", "delete"};
        String[] hints = {"Refresh profiles", "Save current settings", "Load selected profile", "Delete selected profile"};
        int buttonW = (innerW - 18) / 4;
        for (int i = 0; i < actions.length; i++) {
            drawUtilityButton(font, column, actions[i], null, innerX + i * (buttonW + 6),
                    y + 32, buttonW, 16, actions[i], hints[i], true, false, alpha, hoverable);
        }
        int halfW = (innerW - 6) / 2;
        drawUtilityButton(font, column, "folder", null, innerX, y + 52, halfW, 16,
                "Folder", "Open profiles folder", true, false, alpha, hoverable);
        drawUtilityButton(font, column, "import", null, innerX + halfW + 6, y + 52, halfW, 16,
                "Import", "Import a local .cfg file", true, false, alpha, hoverable);

        int rowY = y + 74;
        if (configPreferences) {
            micro(font, "SETTINGS", innerX + 2, rowY + 3, fade(COL_DIM, alpha));
            rowY += 12;
            drawUtilityRow(font, column, "bypass", "Grim Bypass",
                    OpenMaverFeatures.isBypassEnabled() ? "ON" : "OFF",
                    x, rowY, alpha, hoverable, "Toggle Grim Bypass");
            drawUtilityRow(font, column, "debug", "Debug log",
                    OpenMaverFeatures.isDebugEnabled() ? "ON" : "OFF",
                    x, rowY + 14, alpha, hoverable, "Toggle debug logging");
            drawUtilityRow(font, column, "bypassProfile", "Profile",
                    BYPASS_PROFILES[OpenMaverFeatures.getBypassProfile()],
                    x, rowY + 28, alpha, hoverable, "Cycle bypass profile");
            drawUtilityRow(font, column, "guiKey", "GUI key", OpenMaverFeatures.getGuiKeyName(),
                    x, rowY + 42, alpha, hoverable, "Change ClickGUI key");
            rowY += 60;
        }
        List<String> names = ConfigProfiles.listNames();
        if (names.isEmpty()) {
            micro(font, "No saved profiles", innerX + 2, rowY + 5, fade(COL_DIM, alpha));
            rowY += COL_ROW_H;
        }
        for (String name : names) {
            boolean selected = name.equals(ConfigProfiles.sanitize(profileName));
            drawUtilityButton(font, column, "profile", name, innerX, rowY,
                    innerW, COL_ROW_H - 1, name + ConfigProfiles.EXTENSION,
                    name + ConfigProfiles.EXTENSION + " · select, then Load", false, selected, alpha, hoverable);
            rowY += COL_ROW_H;
        }
        String status = ConfigProfiles.status();
        drawUtilityStatus(font, column, innerX, rowY + 4, innerW,
                status.isEmpty() ? "Select a profile or save a new one" : status,
                ConfigProfiles.statusIsError(), alpha, hoverable);
    }

    private void drawWallpaperColumn(FontRenderer font, Column column, int x, int y,
            float alpha, boolean hoverable) {
        drawUtilityRow(font, column, "mode", "Mode", MaverWallpaper.getModeName(),
                x, y + 4, alpha, hoverable, "Cycle panorama / static / dynamic");
        drawUtilityRow(font, column, "fit", "Fit", MaverWallpaper.getFitName(),
                x, y + 22, alpha, hoverable, "Cycle cover / contain / stretch");
        drawUtilityRow(font, column, "source", "Source", MaverWallpaper.getDisplayName(),
                x, y + 40, alpha, hoverable, "Current: " + MaverWallpaper.getDisplayName() + " · click to reset");
        drawUtilityRow(font, column, "wallpaperFolder", "Folder", "OPEN",
                x, y + 58, alpha, hoverable, "Open wallpapers folder and refresh files");
        micro(font, "WALLPAPERS", x + 8, y + 79, fade(COL_DIM, alpha));
        List<File> files = MaverWallpaper.listWallpapers();
        int rowY = y + 88;
        if (files.isEmpty()) {
            micro(font, "No wallpapers · open Folder", x + 8, rowY + 5, fade(COL_DIM, alpha));
            rowY += COL_ROW_H;
        }
        for (File file : files) {
            drawUtilityButton(font, column, "wallpaper", file.getName(), x + 6, rowY,
                    column.width - 12, COL_ROW_H - 1, file.getName(), file.getName(),
                    false, MaverWallpaper.isSelected(file), alpha, hoverable);
            rowY += COL_ROW_H;
        }
        String issue = MaverWallpaper.getStatusHint();
        drawUtilityStatus(font, column, x + 8, rowY + 6, column.width - 16,
                issue == null || issue.isEmpty() ? "PNG / JPG / GIF / WEBP / MP4" : issue,
                issue != null && !issue.isEmpty(), alpha, hoverable);
    }

    private void drawUtilityButton(FontRenderer font, Column column, String action, String value,
            int x, int y, int w, int h, String label, String hint, boolean pink,
            boolean selected, float alpha, boolean hoverable) {
        boolean hovered = hoverable && isHovered(lastMouseX, lastMouseY, x, y, w, h);
        ControlAnim anim = control(column.id + ":" + action + ":" + value, hovered, selected, frameNow);
        int fill = pink ? blendColor(CFG_PINK, 0xFFFFF1F1, anim.hover)
                : blendColor(selected ? COL_ON_FILL : 0x18FFFFFF,
                        selected ? COL_ON_HOVER : 0x30FFFFFF, anim.hover);
        roundRect(x, y, w, h, pink ? h / 2 : 3, fade(fill, alpha));
        if (anim.press > 0.01F) {
            roundRect(x, y, w, h, pink ? h / 2 : 3, fade(0x24FFFFFF, alpha * anim.press));
        }
        int color = fade(pink ? CFG_INK : selected ? COL_ON_TEXT : COL_ROW_TEXT, alpha);
        if ("refresh".equals(label) || "save".equals(label)
                || "load".equals(label) || "delete".equals(label)) {
            drawActionIcon(label, x + w / 2, y + h / 2, color);
        } else {
            String shown = ellipsizeMicro(font, label, w - 10);
            micro(font, shown, pink ? x + (w - microWidth(font, shown)) / 2 : x + 5,
                    y + (h - 7) / 2, color);
        }
        if (hovered) {
            tooltip = hint;
        }
        column.hits.add(Hit.action(action, value, x, y, w, h));
    }

    private void drawUtilityRow(FontRenderer font, Column column, String action,
            String label, String value, int x, int y, float alpha, boolean hoverable, String hint) {
        int w = column.width - 12;
        boolean hovered = hoverable && isHovered(lastMouseX, lastMouseY, x + 6, y, w, 14);
        roundRect(x + 6, y, w, 14, 3, fade(hovered ? 0x25FFFFFF : 0x10FFFFFF, alpha));
        micro(font, label, x + 10, y + 4, fade(COL_ROW_TEXT, alpha));
        int space = Math.max(8, w - microWidth(font, label) - 14);
        String shown = ellipsizeMicro(font, value, space);
        micro(font, shown, x + column.width - 10 - microWidth(font, shown), y + 4,
                fade(COL_ON_FILL, alpha));
        if (hovered) {
            tooltip = hint;
        }
        column.hits.add(Hit.action(action, null, x + 6, y, w, 14));
    }

    private void drawUtilityStatus(FontRenderer font, Column column, int x, int y, int w,
            String message, boolean error, float alpha, boolean hoverable) {
        micro(font, ellipsizeMicro(font, message, w), x, y,
                fade(error ? 0xFFE5826E : COL_DIM, alpha));
        if (hoverable && isHovered(lastMouseX, lastMouseY, x, y, w, 10)) {
            tooltip = message;
        }
    }

    private void drawTooltip(FontRenderer font) {
        if (tooltip == null || tooltip.isEmpty()) {
            return;
        }
        String shown = ellipsizeMicro(font, tooltip, Math.max(20, this.width - 24));
        int w = microWidth(font, shown) + 12;
        int x = clampInt(lastMouseX + 8, 4, Math.max(4, this.width - w - 4));
        int y = clampInt(lastMouseY + 12, 4, Math.max(4, this.height - 20));
        roundRect(x, y, w, 15, 4, fade(0xF5141518, openProgress));
        micro(font, shown, x + 6, y + 4, fade(COL_TITLE, openProgress));
    }

    private static void drawActionIcon(String action, int cx, int cy, int color) {
        if ("+".equals(action)) {
            line(cx - 4, cy, cx + 4, cy, color);
            line(cx, cy - 4, cx, cy + 4, color);
        } else if ("gear".equals(action)) {
            ringCircle(cx, cy, 3, color);
            fillCircle(cx, cy, 1, color);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                line(cx + (float) Math.cos(a) * 3, cy + (float) Math.sin(a) * 3,
                        cx + (float) Math.cos(a) * 4.5F, cy + (float) Math.sin(a) * 4.5F, color);
            }
        } else if ("refresh".equals(action)) {
            ringCircle(cx, cy, 4, color);
            fillTriangle(cx + 1, cy - 5, cx + 5, cy - 4, cx + 2, cy - 1, color);
        } else if ("save".equals(action)) {
            line(cx - 4, cy - 4, cx + 4, cy - 4, color);
            line(cx + 4, cy - 4, cx + 4, cy + 4, color);
            line(cx + 4, cy + 4, cx - 4, cy + 4, color);
            line(cx - 4, cy + 4, cx - 4, cy - 4, color);
            line(cx - 1, cy - 3, cx - 1, cy - 1, color);
            line(cx - 2, cy + 1, cx + 2, cy + 1, color);
            line(cx - 2, cy + 3, cx + 2, cy + 3, color);
        } else if ("load".equals(action)) {
            line(cx, cy - 5, cx, cy + 1, color);
            line(cx - 2, cy - 1, cx, cy + 1, color);
            line(cx + 2, cy - 1, cx, cy + 1, color);
            line(cx - 4, cy, cx - 4, cy + 4, color);
            line(cx - 4, cy + 4, cx + 4, cy + 4, color);
            line(cx + 4, cy + 4, cx + 4, cy, color);
        } else if ("delete".equals(action)) {
            line(cx - 4, cy - 2, cx + 4, cy - 2, color);
            line(cx - 1, cy - 4, cx + 1, cy - 4, color);
            line(cx - 3, cy - 2, cx - 2, cy + 4, color);
            line(cx - 2, cy + 4, cx + 2, cy + 4, color);
            line(cx + 2, cy + 4, cx + 3, cy - 2, color);
            line(cx, cy, cx, cy + 2, color);
        }
    }

    private void handleUtilityAction(Column column, Hit hit) {
        String action = hit.action;
        if ("name".equals(action)) {
            profileFieldFocused = true;
            searchFocused = false;
            return;
        }
        OpenMaverFeatures.playUiClick(1.0F);
        press(column.id + ":" + action + ":" + hit.value);
        if ("new".equals(action)) {
            profileName = ConfigProfiles.suggestName();
            profileFieldFocused = true;
            column.state.collapsed = false;
            column.scroll = 0;
            ConfigProfiles.clearStatus();
        } else if ("preferences".equals(action)) {
            configPreferences = !configPreferences;
            column.state.collapsed = false;
        } else if ("refresh".equals(action)) {
            ConfigProfiles.clearStatus();
        } else if ("save".equals(action)) {
            if (ConfigProfiles.sanitize(profileName).isEmpty()) {
                profileName = ConfigProfiles.suggestName();
            }
            profileName = ConfigProfiles.sanitize(profileName);
            ConfigProfiles.save(profileName);
        } else if ("load".equals(action)) {
            ConfigProfiles.load(profileName);
        } else if ("delete".equals(action)) {
            if (ConfigProfiles.delete(profileName)) {
                profileName = ConfigProfiles.suggestName();
            }
        } else if ("folder".equals(action)) {
            ConfigProfiles.openFolder();
        } else if ("import".equals(action)) {
            ConfigProfiles.requestImport();
        } else if ("profile".equals(action)) {
            profileName = hit.value;
        } else if ("bypass".equals(action)) {
            OpenMaverFeatures.setBypassEnabled(!OpenMaverFeatures.isBypassEnabled());
        } else if ("debug".equals(action)) {
            OpenMaverFeatures.setDebugEnabled(!OpenMaverFeatures.isDebugEnabled());
        } else if ("bypassProfile".equals(action)) {
            OpenMaverFeatures.setBypassProfile((OpenMaverFeatures.getBypassProfile() + 1) % BYPASS_PROFILES.length);
        } else if ("guiKey".equals(action)) {
            OpenMaverFeatures.cycleGuiKey();
        } else if ("mode".equals(action)) {
            MaverWallpaper.nextMode();
        } else if ("fit".equals(action)) {
            MaverWallpaper.nextFit();
        } else if ("source".equals(action)) {
            MaverWallpaper.setFilePath("");
        } else if ("wallpaperFolder".equals(action)) {
            MaverWallpaper.refreshFiles();
            MaverWallpaper.openFolder();
        } else if ("wallpaper".equals(action)) {
            MaverWallpaper.setFilePath(hit.value);
            if (MaverWallpaper.getMode() == MaverWallpaper.MODE_PANORAMA) {
                MaverWallpaper.setMode(MaverWallpaper.isAnimatedFile(hit.value)
                        ? MaverWallpaper.MODE_DYNAMIC : MaverWallpaper.MODE_STATIC);
            }
        }
    }

    private boolean handleConfigFieldKey(char typedChar, int keyCode) {
        if (!profileFieldFocused) {
            return false;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            profileFieldFocused = false;
        } else if (keyCode == Keyboard.KEY_RETURN) {
            profileName = ConfigProfiles.sanitize(profileName);
            if (profileName.isEmpty()) {
                profileName = ConfigProfiles.suggestName();
            }
            ConfigProfiles.save(profileName);
            profileFieldFocused = false;
        } else if (keyCode == Keyboard.KEY_BACK) {
            if (!profileName.isEmpty()) {
                profileName = profileName.substring(0, profileName.length() - 1);
            }
        } else if (typedChar >= 32 && typedChar != 127 && profileName.length() < 40) {
            boolean safe = Character.isLetterOrDigit(typedChar) || typedChar == '_'
                    || typedChar == '-' || typedChar == '.' || typedChar == ' ';
            profileName += safe ? typedChar : '_';
        }
        return true;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (closing || failed) {
            return;
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        handleColumnClick(mouseX, mouseY, button);
    }

    private void updateDrag(int mouseX) {
        if (dragSetting == null || dragW <= 0) {
            return;
        }
        double pct = (mouseX - dragX) / (double) dragW;
        pct = Math.max(0.0D, Math.min(1.0D, pct));
        if (dragSetting.kind == Setting.RANGE) {
            if (dragHigh) {
                dragSetting.setHighPercent(pct);
            } else {
                dragSetting.setLowPercent(pct);
            }
            return;
        }
        dragSetting.setPercent(pct);
    }

    /** 双滑块：按点击位置选择更近的手柄。 */
    private void beginRangeDrag(Setting setting, int mouseX, int x, int w) {
        double pct = w <= 1 ? 0.0D : (mouseX - x) / (double) (w - 1);
        pct = Math.max(0.0D, Math.min(1.0D, pct));
        dragHigh = Math.abs(pct - setting.highPercent()) < Math.abs(pct - setting.lowPercent());
    }

    private void toggleExpanded(Entry entry) {
        if (expanded.contains(entry.id)) {
            expanded.remove(entry.id);
        } else {
            expanded.add(entry.id);
        }
    }

    private float getEntryProgress(Entry entry, long now) {
        float target = expanded.contains(entry.id) ? 1.0F : 0.0F;
        float delta = Math.min(50.0F, Math.max(0.0F, now - entry.expandAt));
        entry.expandProgress = approach(entry.expandProgress, target, delta, 160.0F);
        entry.expandAt = now;
        return easeOutCubic(entry.expandProgress);
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
            scrollColumn(lastMouseX, lastMouseY, wheel);
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
        if (handleConfigFieldKey(typedChar, keyCode)) {
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (searchFocused || !searchQuery.isEmpty()) {
                searchQuery = "";
                searchFocused = false;
                return;
            }
            closeGui();
            return;
        }
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
        if (closing) {
            return;
        }
        closing = true;
        closingAt = System.currentTimeMillis();
        closingFrom = Math.max(0.0F, Math.min(1.0F, openProgress));
        // 关闭动画期间不再响应拖拽 / 输入，避免面板缩小时误触。
        dragSetting = null;
        dragEntry = null;
        pressColumn = null;
        searchFocused = false;
        profileFieldFocused = false;
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

    private int countCategory(String cat) {
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).category.equals(cat)) {
                count++;
            }
        }
        return count;
    }

    // ==================================================================
    // 文本
    // ==================================================================

    /** 正文 / 模块名：保留原始大小写（模块名由 prettyName 统一成词首大写）。 */
    private static void hud(FontRenderer font, String text, int x, int y, int color) {
        HudFont.drawString(font, text, x, y, color, 1.02F * FONT_SCALE);
    }

    private static int hudWidth(FontRenderer font, String text) {
        return HudFont.getStringWidth(font, text, 1.02F * FONT_SCALE);
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

    /** 实心三角形：区间滑块的左右手柄与数值之间的双向箭头都用它。 */
    private static void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        if ((color >>> 24) == 0) {
            return;
        }
        aaBegin();
        GL11.glBegin(GL11.GL_TRIANGLES);
        setVertexColor(color, 1.0F);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x2, y2);
        GL11.glVertex2f(x3, y3);
        GL11.glEnd();
        aaEnd();
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
        Category(String id, String name) {
            this.id = id;
            this.name = name;
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
        /** 双滑块区间：两个手柄分别绑定 low / high。 */
        static final int RANGE = 3;

        final String label;
        final int kind;
        final float min;
        final float max;
        final float step;
        final int decimals;
        final String suffix;
        final String[] options;
        final Binding binding;
        /** RANGE 的上限手柄；其它类型为 null。 */
        final Binding bindingHigh;

        private Setting(String label, int kind, float min, float max, float step,
                int decimals, String suffix, String[] options, Binding binding) {
            this(label, kind, min, max, step, decimals, suffix, options, binding, null);
        }

        private Setting(String label, int kind, float min, float max, float step,
                int decimals, String suffix, String[] options, Binding binding,
                Binding bindingHigh) {
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
            this.bindingHigh = bindingHigh;
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

        /** 双滑块区间：low / high 各自绑定一个配置项。 */
        static Setting range(String label, float min, float max, float step,
                int decimals, String suffix, Binding low, Binding high) {
            return new Setting(label, RANGE, min, max, step, decimals, suffix,
                    null, low, high);
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
            if (kind == RANGE) {
                setLowPercent(percent);
                return;
            }
            binding.set(stepped(percent, 0.0D));
        }

        double lowPercent() {
            return percentOf(binding.get());
        }

        double highPercent() {
            return bindingHigh == null ? 1.0D : percentOf(bindingHigh.get());
        }

        void setLowPercent(double percent) {
            if (bindingHigh == null) {
                binding.set(stepped(percent, 0.0D));
                return;
            }
            // 下限不允许越过上限（拖到另一端就停住，不做“推着走”）。
            binding.set(Math.min(stepped(percent, 0.0D), bindingHigh.get()));
        }

        void setHighPercent(double percent) {
            if (bindingHigh == null) {
                return;
            }
            bindingHigh.set(Math.max(stepped(percent, 0.0D), binding.get()));
        }

        private double percentOf(double value) {
            if (max - min <= 0.0F) {
                return 0.0D;
            }
            return Math.max(0.0D, Math.min(1.0D, (value - min) / (double) (max - min)));
        }

        /** 把百分比换算成步进后的数值（不做区间方向约束）。 */
        private double stepped(double percent, double ignored) {
            double raw = min + (max - min) * percent;
            double quantized = Math.round(raw / step) * (double) step;
            return Math.max(min, Math.min(max, quantized));
        }

        String lowDisplay() {
            return formatValue(binding.get());
        }

        String highDisplay() {
            return formatValue(bindingHigh == null ? binding.get() : bindingHigh.get());
        }

        private String formatValue(double value) {
            if (decimals <= 0) {
                return String.valueOf((int) Math.round(value)) + suffix;
            }
            return String.format(Locale.ROOT, "%." + decimals + "f", value) + suffix;
        }

        String display() {
            if (kind == SELECT) {
                int size = options == null ? 1 : options.length;
                int index = (int) Math.round(binding.get());
                index = Math.max(0, Math.min(size - 1, index));
                return options[index];
            }
            if (kind == RANGE) {
                return lowDisplay() + " - " + highDisplay();
            }
            return formatValue(binding.get());
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
                if ("KeepSprint".equals(module.getName())) {
                    return KeepSprintFeature.getSummary();
                }
                if ("AutoClicker".equals(module.getName())) {
                    return OpenMaverFeatures.getAutoClickerCpsMin() + "-"
                            + OpenMaverFeatures.getAutoClickerCpsMax() + " CPS"
                            + "  ·  " + OpenMaverFeatures.getAutoClickerAttackModeName();
                }
                if ("Reach".equals(module.getName())) {
                    return "CAP " + format(OpenMaverFeatures.getEffectiveReachDistance(), 2)
                            + " m" + (OpenMaverFeatures.isReachGrimCheck() ? "  ·  GRIM" : "")
                            + (OpenMaverFeatures.getReachPingSpoof() > 0
                                    ? "  ·  PING+" + OpenMaverFeatures.getReachPingSpoof() : "")
                            + "  ·  " + Math.round(OpenMaverFeatures.getReachChance()) + "%";
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
