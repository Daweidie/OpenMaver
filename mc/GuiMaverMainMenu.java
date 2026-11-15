import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Session;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

/**
 * Maver 的主菜单（Forge 1.8.9 / {@link GuiScreen}）。
 *
 * <p>布局对应 Badlion 风格标题界面：正上方六边形 Logo、客户端名、副标题、
 * 主按钮与次级按钮、左上角快捷菜单、右上角账号胶囊、左下角版本号。
 * 背景沿用原版全景图，再叠加项目自己的毛玻璃模糊层。
 *
 * <p>所有控件都由 {@link Gui#drawRect} / 立即模式几何自绘，不依赖 GuiButton，
 * 因此字体、缩放和运行期注入环境都能保持一致。
 *
 * <p>注意：这些是 MCP 名，注入前会由 {@code tools/McpReobf} 改成 SRG 名。
 */
@SideOnly(Side.CLIENT)
public final class GuiMaverMainMenu extends GuiScreen {

    // ------------------------------------------------------------------
    // 配色
    // ------------------------------------------------------------------

    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFC0C8D4;
    private static final int TEXT_MUTED = 0xFF8793A5;

    private static final int CYAN = 0xFF56B8E8;

    private static final int GLASS = 0x6410161E;
    private static final int GLASS_HOVER = 0x8A202B38;
    private static final int EDGE_HOVER = 0xC8FFFFFF;
    private static final int PANEL = 0xE20D1219;

    // ------------------------------------------------------------------
    // 资源与尺寸
    // ------------------------------------------------------------------

    private static final ResourceLocation[] PANORAMA = {
        new ResourceLocation("textures/gui/title/background/panorama_0.png"),
        new ResourceLocation("textures/gui/title/background/panorama_1.png"),
        new ResourceLocation("textures/gui/title/background/panorama_2.png"),
        new ResourceLocation("textures/gui/title/background/panorama_3.png"),
        new ResourceLocation("textures/gui/title/background/panorama_4.png"),
        new ResourceLocation("textures/gui/title/background/panorama_5.png")
    };

    private static final String TITLE = "Maver";
    private static final String SUBTITLE = "A Open Src Cheat Client";

    private static final int PANORAMA_GRID = 6;
    /**
     * 每个全景面比单位立方体略大。原版是在 256×256 的 FBO 里画完再裁切缩放，
     * 直接画到全屏时单位立方体覆盖不到 120° 视场的边缘，底部会出现一条硬缝。
     */
    private static final float PANORAMA_SCALE = 1.8F;
    private static final float AA_FEATHER = 1.0F;
    private static final int AA_ARC_SEGMENTS = 24;
    private static final int AA_POINT_COUNT = 4 * (AA_ARC_SEGMENTS + 1);
    private static final float[] AA_INNER_X = new float[AA_POINT_COUNT];
    private static final float[] AA_INNER_Y = new float[AA_POINT_COUNT];
    private static final float[] AA_OUTER_X = new float[AA_POINT_COUNT];
    private static final float[] AA_OUTER_Y = new float[AA_POINT_COUNT];

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private static final int ACTION_NONE = 0;
    private static final int ACTION_SINGLEPLAYER = 1;
    private static final int ACTION_MULTIPLAYER = 2;
    private static final int ACTION_CLIENT = 3;
    private static final int ACTION_CONFIGS = 4;
    private static final int ACTION_OPTIONS = 5;
    private static final int ACTION_QUIT = 6;

    private static final int DROPDOWN_NONE = 0;
    private static final int DROPDOWN_HAMBURGER = 1;
    private static final int DROPDOWN_ACCOUNT = 2;

    private static final int[] HAMBURGER_ACTIONS = {
        ACTION_SINGLEPLAYER, ACTION_MULTIPLAYER, ACTION_CLIENT,
        ACTION_CONFIGS, ACTION_OPTIONS, ACTION_QUIT
    };
    private static final String[] HAMBURGER_LABELS = {
        "Singleplayer", "Multiplayer", "Client Settings",
        "Configs", "Options", "Quit Game"
    };

    private static final int[] ACCOUNT_ACTIONS = {
        ACTION_CLIENT, ACTION_CONFIGS, ACTION_OPTIONS, ACTION_QUIT
    };
    private static final String[] ACCOUNT_LABELS = {
        "Client Settings", "Configs", "Options", "Quit Game"
    };

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private final List<MenuButton> buttons = new ArrayList<MenuButton>();
    private int activeDropdown = DROPDOWN_NONE;
    private int lastMouseX;
    private int lastMouseY;
    private long openedAt;
    private long lastFrameAt;
    private float reveal;
    private String username = "Player";
    private UUID profileId = new UUID(0L, 0L);
    private boolean profileResolved;
    private boolean failed;

    private int hamburgerX;
    private int hamburgerY;
    private int hamburgerW;
    private int hamburgerH;
    private float hamburgerHover;

    private int accountX;
    private int accountY;
    private int accountW;
    private int accountH;
    private float accountHover;

    /** 已从微软 / Mojang 官方皮肤服务取回的皮肤图；为空时退回默认皮肤。 */
    private volatile BufferedImage skinImage;
    /** 皮肤纹理只在渲染线程创建一次，避免每帧重复上传。 */
    private ResourceLocation skinTexture;
    private boolean skinFetchStarted;

    /**
     * 按用户名缓存皮肤图，避免每次重新打开主菜单都重新联网。
     * 头像本身来自 textures.minecraft.net（微软 / Mojang 官方皮肤 CDN）。
     */
    private static final Map<String, BufferedImage> SKIN_CACHE =
            new HashMap<String, BufferedImage>();

    /** 一个标题界面按钮；几何由 {@link #layoutMenu()} 计算。 */
    private static final class MenuButton {
        final int action;
        final String label;
        final boolean primary;
        int x;
        int y;
        int w;
        int h;
        float hover;
        float press;
        long lastAt;

        MenuButton(String label, int action, boolean primary) {
            this.label = label;
            this.action = action;
            this.primary = primary;
        }
    }

    public GuiMaverMainMenu() {
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        this.failed = false;
        this.openedAt = System.currentTimeMillis();
        this.lastFrameAt = this.openedAt;
        this.reveal = 0.0F;
        this.activeDropdown = DROPDOWN_NONE;
        this.buttons.clear();
        resolveProfile();
        layoutMenu();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        long now = System.currentTimeMillis();
        float delta = Math.max(0.0F, Math.min(50.0F, now - lastFrameAt));
        lastFrameAt = now;
        reveal = approach(reveal, 1.0F, delta, 240.0F);
        try {
            drawMenu(mouseX, mouseY, delta);
        } catch (Throwable t) {
            if (!failed) {
                failed = true;
                System.err.println("[OpenMaver] 主菜单绘制失败，已切换简易模式: " + t);
                t.printStackTrace();
            }
            drawFallback(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0) {
            return;
        }
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        if (activeDropdown != DROPDOWN_NONE) {
            int action = dropdownActionAt(mouseX, mouseY);
            if (action != ACTION_NONE) {
                perform(action);
                return;
            }
            if (insideDropdown(mouseX, mouseY)) {
                return;
            }
            activeDropdown = DROPDOWN_NONE;
            return;
        }

        if (inside(mouseX, mouseY, hamburgerX, hamburgerY, hamburgerW, hamburgerH)) {
            activeDropdown = DROPDOWN_HAMBURGER;
            return;
        }
        if (inside(mouseX, mouseY, accountX, accountY, accountW, accountH)) {
            activeDropdown = DROPDOWN_ACCOUNT;
            return;
        }
        for (int i = 0; i < buttons.size(); i++) {
            MenuButton button = buttons.get(i);
            if (inside(mouseX, mouseY, button.x, button.y, button.w, button.h)) {
                button.press = 1.0F;
                perform(button.action);
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 原版 GuiMainMenu 同样吞掉按键；只在快捷菜单打开时响应 ESC。
        if (keyCode == Keyboard.KEY_ESCAPE && activeDropdown != DROPDOWN_NONE) {
            activeDropdown = DROPDOWN_NONE;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ==================================================================
    // 账户
    // ==================================================================

    private void resolveProfile() {
        if (profileResolved) {
            return;
        }
        profileResolved = true;
        GameProfile profile = null;
        String token = null;
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft != null) {
                Session session = minecraft.getSession();
                if (session != null) {
                    String name = session.getUsername();
                    if (name != null && name.length() > 0) {
                        username = name;
                    }
                    profile = session.getProfile();
                    if (profile != null && profile.getId() != null) {
                        profileId = profile.getId();
                    }
                    token = session.getToken();
                }
            }
        } catch (Throwable ignored) {
            // 离线或映射缺失时继续使用占位名，不影响菜单显示。
        }
        startSkinFetch(username, profile, token);
    }

    /**
     * 异步获取当前账号在微软 / Mojang 官方服务上的皮肤。
     *
     * <p>微软登录的 1.8.9 会话里通常没有 {@code textures} 属性，直接按用户名查询
     * 又容易被 sessionserver 限流。这里优先用会话 access token 调用官方
     * {@code api.minecraftservices.com/minecraft/profile}，拿到当前账号正在使用的
     * SKIN.url；失败后再退回 profile 属性、UUID 和用户名三条旧路径。
     */
    private void startSkinFetch(final String name, final GameProfile profile,
            final String token) {
        if (skinFetchStarted) {
            return;
        }
        skinFetchStarted = true;
        BufferedImage cached = cachedSkin(name);
        if (cached != null) {
            skinImage = cached;
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedImage image = loadOfficialSkin(name, profile, token);
                    if (image != null) {
                        cacheSkin(name, image);
                        skinImage = image;
                    }
                } catch (Throwable ignored) {
                    // 保持默认皮肤，绝不让联网失败影响主菜单。
                }
            }
        }, "OpenMaver-Skin");
        thread.setDaemon(true);
        thread.start();
    }

    private static BufferedImage cachedSkin(String name) {
        if (name == null) {
            return null;
        }
        synchronized (SKIN_CACHE) {
            return SKIN_CACHE.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private static void cacheSkin(String name, BufferedImage image) {
        if (name == null || image == null) {
            return;
        }
        synchronized (SKIN_CACHE) {
            SKIN_CACHE.put(name.toLowerCase(Locale.ROOT), image);
        }
    }

    private static BufferedImage loadOfficialSkin(String name, GameProfile profile,
            String token) {
        // 微软账号最可靠的路径：带 Bearer token 查询当前账号的 profile，直接拿到
        // skins[].url。这条接口返回的就是游戏内正在使用的皮肤。
        String url = skinUrlFromServices(token);
        if (url == null) {
            url = skinUrlFromProfile(profile);
        }
        if (url == null) {
            url = skinUrlFromId(profile == null ? null : profile.getId());
        }
        if (url == null && name != null && name.length() > 0) {
            url = skinUrlFromName(name);
        }
        return url == null ? null : readSkinImage(url);
    }

    /**
     * 使用登录会话 access token 调用官方 Minecraft Services profile 接口。
     * 返回示例：{@code {"skins":[{"state":"ACTIVE","url":"http://textures..."}]}}。
     */
    private static String skinUrlFromServices(String token) {
        if (token == null || token.length() == 0 || "0".equals(token)) {
            return null;
        }
        String json = fetchText("https://api.minecraftservices.com/minecraft/profile",
                "Bearer " + token);
        if (json == null) {
            return null;
        }
        int skins = json.indexOf("\"skins\"");
        if (skins < 0) {
            return null;
        }
        int url = json.indexOf("\"url\"", skins);
        if (url < 0) {
            return null;
        }
        return readJsonStringAfter(json, url + 5);
    }

    /** 按 UUID 查询 sessionserver profile；比用户名查询少一次请求。 */
    private static String skinUrlFromId(UUID id) {
        if (id == null || (id.getMostSignificantBits() == 0L
                && id.getLeastSignificantBits() == 0L)) {
            return null;
        }
        try {
            String hex = id.toString().replace("-", "");
            String profileJson = fetchText(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" + hex);
            return skinUrlFromTexturesValue(texturesValue(profileJson));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从登录会话的 {@code textures} 属性里取出官方皮肤地址（不依赖编译期 authlib API）。 */
    private static String skinUrlFromProfile(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        try {
            Object properties = profile.getClass().getMethod("getProperties").invoke(profile);
            if (properties == null) {
                return null;
            }
            // Guava 的具体 Multimap 实现可能是包私有类，反射时从公开接口取 get(Object)。
            Class<?> multimap = Class.forName("com.google.common.collect.Multimap");
            Object textures = multimap.getMethod("get", Object.class)
                    .invoke(properties, "textures");
            if (!(textures instanceof Iterable)) {
                return null;
            }
            for (Object property : (Iterable<?>) textures) {
                Object value = property.getClass().getMethod("getValue").invoke(property);
                String url = skinUrlFromTexturesValue(String.valueOf(value));
                if (url != null) {
                    return url;
                }
            }
        } catch (Throwable ignored) {
            // authlib 版本或离线会话没有属性时走用户名查询。
        }
        return null;
    }

    /** 离线会话没有 textures 属性时，按用户名查询 Mojang 官方 profile。 */
    private static String skinUrlFromName(String name) {
        try {
            String encoded = URLEncoder.encode(name, "UTF-8");
            String userJson = fetchText(
                    "https://api.mojang.com/users/profiles/minecraft/" + encoded);
            String id = jsonField(userJson, "id");
            if (id == null || id.length() == 0) {
                return null;
            }
            String profileJson = fetchText(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" + id);
            String value = texturesValue(profileJson);
            return skinUrlFromTexturesValue(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从 JSON 的 {@code "url"} 键之后读取字符串值，并统一成 https。 */
    private static String readJsonStringAfter(String json, int fromIndex) {
        if (json == null) {
            return null;
        }
        int colon = json.indexOf(':', fromIndex);
        int start = json.indexOf('"', colon + 1);
        int end = json.indexOf('"', start + 1);
        if (start < 0 || end <= start) {
            return null;
        }
        String result = json.substring(start + 1, end);
        // 官方 CDN 同时支持 http / https，统一走 https 以免被运行环境拦截。
        return result.startsWith("http://")
                ? "https://" + result.substring("http://".length()) : result;
    }

    /** Base64 解码 textures 属性，并取出 {@code SKIN.url}。 */
    private static String skinUrlFromTexturesValue(String value) {
        if (value == null || value.length() == 0) {
            return null;
        }
        try {
            String json = new String(Base64.getDecoder().decode(value),
                    StandardCharsets.UTF_8);
            int skin = json.indexOf("\"SKIN\"");
            if (skin < 0) {
                return null;
            }
            int url = json.indexOf("\"url\"", skin);
            if (url < 0) {
                return null;
            }
            return readJsonStringAfter(json, url + 5);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从 sessionserver 响应里取出 textures 属性的 Base64 value。 */
    private static String texturesValue(String json) {
        if (json == null) {
            return null;
        }
        int textures = json.indexOf("\"textures\"");
        if (textures < 0) {
            return null;
        }
        int value = json.indexOf("\"value\"", textures);
        if (value < 0) {
            return null;
        }
        int colon = json.indexOf(':', value + 7);
        int start = json.indexOf('"', colon + 1);
        int end = json.indexOf('"', start + 1);
        return start < 0 || end <= start ? null : json.substring(start + 1, end);
    }

    private static String jsonField(String json, String key) {
        if (json == null) {
            return null;
        }
        int field = json.indexOf('"' + key + '"');
        if (field < 0) {
            return null;
        }
        int colon = json.indexOf(':', field + key.length() + 2);
        int start = json.indexOf('"', colon + 1);
        int end = json.indexOf('"', start + 1);
        return start < 0 || end <= start ? null : json.substring(start + 1, end);
    }

    private static String fetchText(String url) {
        return fetchText(url, null);
    }

    private static String fetchText(String url, String authorization) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, 4000, 4000);
            if (authorization != null) {
                connection.setRequestProperty("Authorization", authorization);
                connection.setRequestProperty("Accept", "application/json");
            }
            if (connection.getResponseCode() != 200) {
                return null;
            }
            InputStream in = connection.getInputStream();
            try {
                return readAll(in);
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static BufferedImage readSkinImage(String url) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, 5000, 8000);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            InputStream in = connection.getInputStream();
            try {
                return normalizeSkin(ImageIO.read(in));
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection open(String url, int connectTimeout, int readTimeout)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "OpenMaver/" + OpenMaver.VERSION);
        return connection;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 把官方皮肤图统一成 64×64 纹理。旧版 64×32 皮肤放在左上角保留原始 UV，
     * 高清皮肤则缩放到 64×64，保证头部 UV 与 Minecraft 的标准布局一致。
     */
    private static BufferedImage normalizeSkin(BufferedImage source) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage result = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            if (height >= 64) {
                graphics.drawImage(source, 0, 0, 64, 64, null);
            } else {
                int scaledHeight = Math.max(1, Math.round(64.0F * height / width));
                graphics.drawImage(source, 0, 0, 64, Math.min(64, scaledHeight), null);
            }
        } finally {
            graphics.dispose();
        }
        return result;
    }

    // ==================================================================
    // 布局
    // ==================================================================

    private float uiScale() {
        return Math.max(0.55F, Math.min(1.8F, height / 480.0F));
    }

    private void layoutMenu() {
        float ui = uiScale();
        int center = width / 2;
        int buttonW = Math.max(200, Math.min(Math.round(width * 0.32F),
                Math.round(300.0F * ui)));
        int buttonH = clampInt(Math.round(31.0F * ui), 18, 44);
        int gap = clampInt(Math.round(6.0F * ui), 3, 10);
        int startY = Math.round(height * 0.575F);
        int blockH = 4 * buttonH + 3 * gap;
        int lowestStart = height - Math.round(24.0F * ui) - blockH;
        startY = Math.min(startY, Math.max(Math.round(height * 0.46F), lowestStart));

        buttons.clear();
        buttons.add(button("Singleplayer", ACTION_SINGLEPLAYER, true,
                center - buttonW / 2, startY, buttonW, buttonH));
        buttons.add(button("Multiplayer", ACTION_MULTIPLAYER, true,
                center - buttonW / 2, startY + buttonH + gap, buttonW, buttonH));

        int smallW = (buttonW - gap) / 2;
        int leftX = center - buttonW / 2;
        int rightX = leftX + smallW + gap;
        int row1 = startY + 2 * (buttonH + gap);
        int row2 = row1 + buttonH + gap;
        buttons.add(button("Client Settings", ACTION_CLIENT, false,
                leftX, row1, smallW, buttonH));
        buttons.add(button("Configs", ACTION_CONFIGS, false,
                rightX, row1, smallW, buttonH));
        buttons.add(button("Options", ACTION_OPTIONS, false,
                leftX, row2, smallW, buttonH));
        buttons.add(button("Quit Game", ACTION_QUIT, false,
                rightX, row2, smallW, buttonH));

        hamburgerW = clampInt(Math.round(40.0F * ui), 28, 54);
        hamburgerH = clampInt(Math.round(30.0F * ui), 22, 44);
        hamburgerX = clampInt(Math.round(10.0F * ui), 5, 22);
        hamburgerY = hamburgerX;

        int nameWidth = textWidth(fontRendererObj, username, 1.15F * ui);
        accountH = clampInt(Math.round(34.0F * ui), 24, 48);
        accountW = Math.max(Math.round(126.0F * ui), nameWidth + Math.round(70.0F * ui));
        accountX = width - Math.round(10.0F * ui) - accountW;
        accountY = hamburgerY;

    }

    private static MenuButton button(String label, int action, boolean primary,
            int x, int y, int w, int h) {
        MenuButton button = new MenuButton(label, action, primary);
        button.x = x;
        button.y = y;
        button.w = w;
        button.h = h;
        return button;
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    private void drawMenu(int mouseX, int mouseY, float delta) {
        resetUiState();
        drawBackground();
        drawLogo();
        drawTitle();
        drawButtons(mouseX, mouseY, delta);
        drawVersion();
        drawHamburgerButton(mouseX, mouseY, delta);
        drawAccountChip(mouseX, mouseY, delta);
        drawDropdowns(mouseX, mouseY);
    }

    /**
     * 重置本帧依赖的固定管线状态。
     *
     * <p>从原版界面 / ClickGUI 返回后，GL 的 alpha test、flat shading、scissor
     * 等状态可能仍是上一个界面的值。菜单的圆角与文字抗锯齿依赖逐顶点 alpha，
     * 这些状态残留会让边缘变成硬像素（看起来就是锯齿），因此每帧显式恢复。
     */
    private static void resetUiState() {
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_POLYGON_SMOOTH);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(false);
        GlColor.restore();
    }

    private void drawBackground() {
        try {
            drawPanorama();
            // 项目现有的毛玻璃实现：抓取刚画好的全景图并做两遍高斯模糊。
            // 原版在最终合成时会水平翻转全景图，这里通过 drawMirrored 保持一致。
            GuiBlur.beginFrame(width, height, 1);
            GuiBlur.drawMirrored(0, 0, width, height, 0.0F, 0x0A0F16, 0.22F, 1.0F);
        } catch (Throwable ignored) {
            // 模糊不可用时下面的深色渐变仍然保证界面可读。
        }
        drawVerticalGradient(0, 0, width, height, 0x66080B12, 0xA0080B12);
        // 顶部和底部再压一层，让标题与按钮从背景里浮出来。
        drawVerticalGradient(0, 0, width, Math.max(1, height / 4), 0x5003070C, 0x0003070C);
        drawVerticalGradient(0, height - Math.max(1, height / 4), width,
                Math.max(1, height / 4), 0x0003070C, 0x6003070C);
    }

    private void drawPanorama() {
        if (mc == null || mc.getTextureManager() == null) {
            return;
        }
        float ticks = (System.currentTimeMillis() % 1_000_000L) / 50.0F;
        float top = 0.05F * (float) Math.tan(Math.toRadians(60.0D));

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glFrustum(-top, top, -top, top, 0.05F, 10.0F);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glRotatef(180.0F, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef(90.0F, 0.0F, 0.0F, 1.0F);

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlColor.restore();

            for (int layer = 0; layer < PANORAMA_GRID * PANORAMA_GRID; layer++) {
                float offsetX = ((layer % PANORAMA_GRID) / (float) PANORAMA_GRID - 0.5F)
                        / 64.0F;
                float offsetY = ((layer / PANORAMA_GRID) / (float) PANORAMA_GRID - 0.5F)
                        / 64.0F;
                GL11.glPushMatrix();
                try {
                    GL11.glTranslatef(offsetX, offsetY, 0.0F);
                    GL11.glRotatef((float) Math.sin(ticks / 400.0D) * 25.0F + 20.0F,
                            1.0F, 0.0F, 0.0F);
                    GL11.glRotatef(-ticks * 0.1F, 0.0F, 1.0F, 0.0F);

                    for (int face = 0; face < 6; face++) {
                        GL11.glPushMatrix();
                        try {
                            if (face == 1) {
                                GL11.glRotatef(90.0F, 0.0F, 1.0F, 0.0F);
                            } else if (face == 2) {
                                GL11.glRotatef(180.0F, 0.0F, 1.0F, 0.0F);
                            } else if (face == 3) {
                                GL11.glRotatef(-90.0F, 0.0F, 1.0F, 0.0F);
                            } else if (face == 4) {
                                GL11.glRotatef(90.0F, 1.0F, 0.0F, 0.0F);
                            } else if (face == 5) {
                                GL11.glRotatef(-90.0F, 1.0F, 0.0F, 0.0F);
                            }

                            GlStateManager.bindTexture(0);
                            mc.getTextureManager().bindTexture(PANORAMA[face]);
                            float alpha = 1.0F / (layer + 1);
                            GL11.glBegin(GL11.GL_QUADS);
                            setVertexColor(0xFFFFFFFF, alpha);
                            GL11.glTexCoord2f(0.0F, 0.0F);
                            GL11.glVertex3f(-PANORAMA_SCALE, -PANORAMA_SCALE, 1.0F);
                            GL11.glTexCoord2f(1.0F, 0.0F);
                            GL11.glVertex3f(PANORAMA_SCALE, -PANORAMA_SCALE, 1.0F);
                            GL11.glTexCoord2f(1.0F, 1.0F);
                            GL11.glVertex3f(PANORAMA_SCALE, PANORAMA_SCALE, 1.0F);
                            GL11.glTexCoord2f(0.0F, 1.0F);
                            GL11.glVertex3f(-PANORAMA_SCALE, PANORAMA_SCALE, 1.0F);
                            GL11.glEnd();
                        } finally {
                            GL11.glPopMatrix();
                        }
                    }
                } finally {
                    GL11.glPopMatrix();
                }
            }
        } finally {
            GlStateManager.bindTexture(0);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            GlColor.restore();
        }
    }

    private void drawLogo() {
        float ui = uiScale();
        int cx = width / 2;
        int cy = Math.round(height * 0.235F);
        float radius = 64.0F * ui;

        // 光晕：三圈逐渐放大的低透明度六边形。
        fillHexagon(cx, cy, radius + 22.0F * ui, withAlpha(CYAN, 0x08));
        fillHexagon(cx, cy, radius + 12.0F * ui, withAlpha(CYAN, 0x10));
        fillHexagon(cx, cy, radius + 5.0F * ui, withAlpha(CYAN, 0x1C));

        fillHexagonGradient(cx, cy, radius, 0xFF3C9CC8, 0xFF1B4E69);
        // 上半部加一点高光。
        drawHexagonRing(cx, cy, radius - 1.0F, Math.max(1.0F, radius * 0.22F),
                withAlpha(0xFFFFFF, 0x10));
        drawHexagonRing(cx, cy, radius, Math.max(1.0F, 1.5F * ui),
                withAlpha(0xFFFFFF, 0x72));
        drawHexagonRing(cx, cy, radius - 2.0F * ui, Math.max(1.0F, 1.0F * ui),
                withAlpha(CYAN, 0x40));

        MaverLogo.drawCentered(fontRendererObj, cx, cy,
                Math.round(radius * 1.30F), Math.round(radius * 1.02F),
                1.0F, TEXT);
    }

    private void drawTitle() {
        float ui = uiScale();
        int center = width / 2;
        int titleY = Math.round(height * 0.39F);
        int subtitleY = titleY + Math.round(42.0F * ui);
        int titleColor = scaleAlpha(TEXT, reveal);
        int subtitleColor = scaleAlpha(TEXT, reveal);
        drawTrackedCentered(fontRendererObj, TITLE, center, titleY, titleColor,
                2.5F * ui, 7.0F * ui, true, true);
        drawTrackedCentered(fontRendererObj, SUBTITLE, center, subtitleY, subtitleColor,
                1.35F * ui, 0.5F * ui, false, false);
    }

    private void drawButtons(int mouseX, int mouseY, float delta) {
        for (int i = 0; i < buttons.size(); i++) {
            MenuButton button = buttons.get(i);
            boolean hovered = inside(mouseX, mouseY, button.x, button.y, button.w, button.h);
            if (button.lastAt == 0L) {
                button.lastAt = lastFrameAt;
            }
            long deltaMs = Math.max(0L, Math.min(50L, lastFrameAt - button.lastAt));
            button.lastAt = lastFrameAt;
            button.hover = approach(button.hover, hovered ? 1.0F : 0.0F,
                    deltaMs, 120.0F);
            button.press = approach(button.press, 0.0F, deltaMs, 150.0F);
            drawButton(button);
        }
    }

    private void drawButton(MenuButton button) {
        float ui = uiScale();
        float hover = button.hover;
        float press = button.press;
        int radius = Math.max(2, Math.round((button.primary ? 4.0F : 3.0F) * ui));
        int fill = blendColor(button.primary ? GLASS : 0x4E0F141B,
                GLASS_HOVER, hover);
        if (press > 0.01F) {
            fill = blendColor(fill, 0xA8FFFFFF, press * 0.12F);
        }
        int edge = blendColor(button.primary ? 0x58FFFFFF : 0x48FFFFFF,
                EDGE_HOVER, hover);
        int textColor = blendColor(button.primary ? TEXT : 0xFFF3F7FC,
                0xFFFFFFFF, hover);
        textColor = scaleAlpha(textColor, reveal);

        roundRect(button.x, button.y + Math.round(2.0F * ui), button.w, button.h,
                radius, 0x36000000);
        roundRect(button.x, button.y, button.w, button.h, radius, edge);
        roundRect(button.x + 1, button.y + 1, button.w - 2, button.h - 2,
                Math.max(0, radius - 1), scaleAlpha(fill, reveal));

        // 顶部一条很细的折射光，悬停时明显一些。
        Gui.drawRect(button.x + radius, button.y + 1,
                button.x + button.w - radius, button.y + 2,
                withAlpha(0xFFFFFF, Math.round((0x14 + 0x28 * hover) * reveal)));

        // 悬停时左右两侧各出现一小段高光竖线，和 ClickGUI 的卡片语言保持一致。
        if (hover > 0.02F) {
            int glow = Math.round(0x32 * hover * reveal);
            Gui.drawRect(button.x + 1, button.y + button.h / 4,
                    button.x + 2, button.y + button.h - button.h / 4,
                    withAlpha(0xFFFFFF, glow));
            Gui.drawRect(button.x + button.w - 2, button.y + button.h / 4,
                    button.x + button.w - 1, button.y + button.h - button.h / 4,
                    withAlpha(0xFFFFFF, glow));
        }

        float labelSize = 1.52F * ui;
        float tracking = 0.35F * ui;
        float maxLabelWidth = button.w - 16.0F * ui;
        float labelWidth = trackedWidth(fontRendererObj, button.label,
                labelSize, tracking, false);
        if (labelWidth > maxLabelWidth && labelWidth > 0.0F) {
            // Source Code Pro is monospace; long labels such as "Client Settings"
            // would overflow the half-width buttons at large GUI scales.
            labelSize *= maxLabelWidth / labelWidth;
        }
        int baseline = button.y + (button.h - Math.round(13.0F * ui)) / 2;
        drawTrackedCentered(fontRendererObj, button.label,
                button.x + button.w / 2, baseline, textColor,
                labelSize, tracking, false, false);
    }

    private void drawVersion() {
        float ui = uiScale();
        int x = clampInt(Math.round(10.0F * ui), 6, 22);
        int y = height - clampInt(Math.round(17.0F * ui), 14, 26);
        String version = "Maver " + OpenMaver.VERSION + "  \u00b7  Minecraft 1.8.9";
        drawTrackedLeft(fontRendererObj, version, x, y,
                scaleAlpha(TEXT_MUTED, reveal), 0.98F * ui, 0.25F * ui, false);
    }

    private void drawHamburgerButton(int mouseX, int mouseY, float delta) {
        boolean hovered = inside(mouseX, mouseY, hamburgerX, hamburgerY,
                hamburgerW, hamburgerH);
        hamburgerHover = approach(hamburgerHover, hovered ? 1.0F : 0.0F, delta, 120.0F);
        float hover = hamburgerHover;
        int radius = Math.max(2, Math.round(4.0F * uiScale()));
        roundRect(hamburgerX, hamburgerY, hamburgerW, hamburgerH, radius,
                blendColor(0x2EFFFFFF, 0xC8FFFFFF, hover));
        roundRect(hamburgerX + 1, hamburgerY + 1, hamburgerW - 2, hamburgerH - 2,
                Math.max(0, radius - 1),
                scaleAlpha(blendColor(0x6610161E, 0x8A1E2936, hover), reveal));

        int left = hamburgerX + Math.round(hamburgerW * 0.27F);
        int right = hamburgerX + hamburgerW - Math.round(hamburgerW * 0.27F);
        int centerY = hamburgerY + hamburgerH / 2;
        int thickness = Math.max(1, Math.round(1.5F * uiScale()));
        int color = withAlpha(0xFFFFFF, Math.round((0xC8 + 0x30 * hover) * reveal));
        Gui.drawRect(left, centerY - Math.round(5.0F * uiScale()),
                right, centerY - Math.round(5.0F * uiScale()) + thickness, color);
        Gui.drawRect(left, centerY - thickness / 2,
                right, centerY - thickness / 2 + thickness, color);
        Gui.drawRect(left, centerY + Math.round(4.0F * uiScale()),
                right, centerY + Math.round(4.0F * uiScale()) + thickness, color);
    }

    private void drawAccountChip(int mouseX, int mouseY, float delta) {
        boolean hovered = inside(mouseX, mouseY, accountX, accountY, accountW, accountH);
        accountHover = approach(accountHover, hovered ? 1.0F : 0.0F, delta, 120.0F);
        float hover = accountHover;
        float ui = uiScale();
        int radius = Math.max(3, Math.round(5.0F * ui));
        roundRect(accountX, accountY, accountW, accountH, radius,
                blendColor(0x35FFFFFF, 0x9AFFFFFF, hover));
        roundRect(accountX + 1, accountY + 1, accountW - 2, accountH - 2,
                Math.max(0, radius - 1),
                scaleAlpha(blendColor(0x7010161E, 0x9A1E2936, hover), reveal));
        Gui.drawRect(accountX + radius, accountY + 1, accountX + accountW - radius,
                accountY + 2, withAlpha(0xFFFFFF, Math.round((0x12 + 0x24 * hover) * reveal)));

        int headSize = accountH - Math.round(10.0F * ui);
        int headX = accountX + Math.round(5.0F * ui);
        int headY = accountY + (accountH - headSize) / 2;
        drawHead(headX, headY, headSize);
        Gui.drawRect(headX - 1, headY - 1, headX + headSize + 1, headY,
                withAlpha(0x000000, 0x60));
        Gui.drawRect(headX - 1, headY + headSize, headX + headSize + 1,
                headY + headSize + 1, withAlpha(0x000000, 0x60));
        Gui.drawRect(headX - 1, headY, headX, headY + headSize,
                withAlpha(0x000000, 0x60));
        Gui.drawRect(headX + headSize, headY, headX + headSize + 1,
                headY + headSize, withAlpha(0x000000, 0x60));

        int nameX = headX + headSize + Math.round(8.0F * ui);
        int nameY = accountY + (accountH - Math.round(11.0F * ui)) / 2;
        drawTrackedLeft(fontRendererObj, username, nameX, nameY,
                scaleAlpha(TEXT, reveal), 1.15F * ui, 0.2F * ui, false);

        // 下拉箭头
        int chevronX = accountX + accountW - Math.round(17.0F * ui);
        int chevronY = accountY + accountH / 2 - Math.round(1.0F * ui);
        int chevronColor = withAlpha(0xFFFFFF, Math.round((0xB0 + 0x30 * hover) * reveal));
        drawLine(chevronX, chevronY, chevronX + Math.round(3.0F * ui),
                chevronY + Math.round(3.0F * ui), chevronColor);
        drawLine(chevronX + Math.round(3.0F * ui), chevronY + Math.round(3.0F * ui),
                chevronX + Math.round(6.0F * ui), chevronY, chevronColor);
    }

    private void drawHead(int x, int y, int size) {
        if (drawOfficialSkin(x, y, size)) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            ResourceLocation skin = DefaultPlayerSkin.getDefaultSkin(
                    profileId != null ? profileId : new UUID(0L, 0L));
            if (minecraft != null && minecraft.getTextureManager() != null && skin != null) {
                GlStateManager.bindTexture(0);
                minecraft.getTextureManager().bindTexture(skin);
                Gui.drawModalRectWithCustomSizedTexture(x, y, 8, 8, size, size, 64, 64);
                GlStateManager.bindTexture(0);
                return;
            }
        } catch (Throwable ignored) {
            // 纹理不可用时退回首字母方块。
        }
        int hash = username == null ? 0 : username.hashCode();
        int color = 0xFF000000 | ((hash & 0x3F) << 18)
                | ((hash >> 6 & 0x3F) << 10) | (hash >> 12 & 0x3F);
        Gui.drawRect(x, y, x + size, y + size, scaleAlpha(color, reveal));
        String initial = username == null || username.length() == 0
                ? "?" : username.substring(0, 1).toUpperCase(Locale.ROOT);
        int textY = y + (size - Math.round(8.0F * uiScale())) / 2;
        drawTrackedCentered(fontRendererObj, initial, x + size / 2, textY,
                scaleAlpha(TEXT, reveal), uiScale(), 0.0F, true, false);
    }

    /**
     * 用刚从微软 / Mojang 官方服务下载的皮肤绘制头像（底层 + 帽子层）。
     * 纹理必须在渲染线程创建并注册，因此在这里而不是下载线程里调用 {@link DynamicTexture}。
     */
    private boolean drawOfficialSkin(int x, int y, int size) {
        BufferedImage image = skinImage;
        if (image == null) {
            return false;
        }
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null) {
                return false;
            }
            TextureManager textures = minecraft.getTextureManager();
            if (textures == null) {
                return false;
            }
            if (skinTexture == null) {
                String textureName = username == null || username.length() == 0
                        ? "player" : username.toLowerCase(Locale.ROOT);
                ResourceLocation location = new ResourceLocation(
                        "openmaver", "skin/" + textureName);
                if (!textures.loadTexture(location, new DynamicTexture(image))) {
                    return false;
                }
                skinTexture = location;
            }
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GlColor.restore();
            textures.bindTexture(skinTexture);
            Gui.drawModalRectWithCustomSizedTexture(x, y, 8, 8, size, size, 64, 64);
            textures.bindTexture(skinTexture);
            Gui.drawModalRectWithCustomSizedTexture(x, y, 40, 8, size, size, 64, 64);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GlStateManager.bindTexture(0);
            return true;
        } catch (Throwable ignored) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GlStateManager.bindTexture(0);
            return false;
        }
    }

    private void drawDropdowns(int mouseX, int mouseY) {
        if (activeDropdown == DROPDOWN_NONE) {
            return;
        }
        int[] actions = activeDropdown == DROPDOWN_HAMBURGER
                ? HAMBURGER_ACTIONS : ACCOUNT_ACTIONS;
        String[] labels = activeDropdown == DROPDOWN_HAMBURGER
                ? HAMBURGER_LABELS : ACCOUNT_LABELS;
        float ui = uiScale();
        int rowH = clampInt(Math.round(27.0F * ui), 22, 38);
        int pad = Math.round(5.0F * ui);
        int width = activeDropdown == DROPDOWN_HAMBURGER
                ? clampInt(Math.round(172.0F * ui), 136, 230) : accountW;
        int x = activeDropdown == DROPDOWN_HAMBURGER ? hamburgerX : accountX;
        int y = (activeDropdown == DROPDOWN_HAMBURGER
                ? hamburgerY + hamburgerH : accountY + accountH) + pad;
        int h = pad * 2 + rowH * actions.length;

        roundRect(x, y + 2, width, h, 5, 0x40000000);
        roundRect(x, y, width, h, 5, 0xD8FFFFFF);
        roundRect(x + 1, y + 1, width - 2, h - 2, 4, PANEL);
        Gui.drawRect(x + 6, y + 1, x + width - 6, y + 2,
                withAlpha(0xFFFFFF, 0x30));

        for (int i = 0; i < actions.length; i++) {
            int rowY = y + pad + i * rowH;
            boolean hovered = inside(mouseX, mouseY, x + 4, rowY, width - 8, rowH - 1);
            if (hovered) {
                roundRect(x + 4, rowY, width - 8, rowH - 1, 3,
                        withAlpha(0xFFFFFF, 0x1A));
                Gui.drawRect(x + 4, rowY + 5, x + 5, rowY + rowH - 6,
                        withAlpha(0xFFFFFF, 0xA0));
            }
            int color = hovered ? TEXT : TEXT_DIM;
            int textY = rowY + (rowH - Math.round(10.0F * ui)) / 2;
            drawTrackedLeft(fontRendererObj, labels[i], x + Math.round(13.0F * ui),
                    textY, color, 1.08F * ui, 0.2F * ui, false);
        }
    }

    private void drawFallback(int mouseX, int mouseY) {
        Gui.drawRect(0, 0, width, height, 0xFF0A0E14);
        if (fontRendererObj != null) {
            String title = TITLE;
            fontRendererObj.drawStringWithShadow(title,
                    width / 2 - fontRendererObj.getStringWidth(title) / 2, 30, 0xFFFFFFFF);
            for (int i = 0; i < buttons.size(); i++) {
                MenuButton button = buttons.get(i);
                boolean hovered = inside(mouseX, mouseY, button.x, button.y,
                        button.w, button.h);
                Gui.drawRect(button.x, button.y, button.x + button.w,
                        button.y + button.h, hovered ? 0xFF2D3948 : 0xFF1A222D);
                fontRendererObj.drawStringWithShadow(button.label,
                        button.x + (button.w - fontRendererObj.getStringWidth(button.label)) / 2,
                        button.y + (button.h - 8) / 2, 0xFFFFFFFF);
            }
        }
    }

    // ==================================================================
    // 交互
    // ==================================================================

    private void perform(int action) {
        activeDropdown = DROPDOWN_NONE;
        try {
            if (action == ACTION_SINGLEPLAYER) {
                mc.displayGuiScreen(new GuiSelectWorld(this));
            } else if (action == ACTION_MULTIPLAYER) {
                mc.displayGuiScreen(new GuiMultiplayer(this));
            } else if (action == ACTION_CLIENT) {
                openClientGui(null);
            } else if (action == ACTION_CONFIGS) {
                openClientGui("configs");
            } else if (action == ACTION_OPTIONS) {
                mc.displayGuiScreen(new GuiOptions(this, mc.gameSettings));
            } else if (action == ACTION_QUIT) {
                mc.shutdown();
            }
        } catch (Throwable t) {
            System.err.println("[OpenMaver] 主菜单动作失败: " + action + " -> " + t);
            t.printStackTrace();
        }
    }

    private void openClientGui(String initialPage) {
        try {
            if (!OpenMaver.isStarted()) {
                OpenMaver.start();
            }
        } catch (Throwable t) {
            System.err.println("[OpenMaver] 启动核心失败，仍尝试打开 ClickGUI: " + t);
        }
        mc.displayGuiScreen(new GuiOpenMaverClickGui(this, initialPage));
    }

    private int dropdownActionAt(int mouseX, int mouseY) {
        int[] actions = activeDropdown == DROPDOWN_HAMBURGER
                ? HAMBURGER_ACTIONS : ACCOUNT_ACTIONS;
        float ui = uiScale();
        int rowH = clampInt(Math.round(27.0F * ui), 22, 38);
        int pad = Math.round(5.0F * ui);
        int width = activeDropdown == DROPDOWN_HAMBURGER
                ? clampInt(Math.round(172.0F * ui), 136, 230) : accountW;
        int x = activeDropdown == DROPDOWN_HAMBURGER ? hamburgerX : accountX;
        int y = (activeDropdown == DROPDOWN_HAMBURGER
                ? hamburgerY + hamburgerH : accountY + accountH) + pad;
        for (int i = 0; i < actions.length; i++) {
            if (inside(mouseX, mouseY, x + 4, y + pad + i * rowH, width - 8, rowH - 1)) {
                return actions[i];
            }
        }
        return ACTION_NONE;
    }

    private boolean insideDropdown(int mouseX, int mouseY) {
        float ui = uiScale();
        int rowH = clampInt(Math.round(27.0F * ui), 22, 38);
        int pad = Math.round(5.0F * ui);
        int width = activeDropdown == DROPDOWN_HAMBURGER
                ? clampInt(Math.round(172.0F * ui), 136, 230) : accountW;
        int x = activeDropdown == DROPDOWN_HAMBURGER ? hamburgerX : accountX;
        int y = (activeDropdown == DROPDOWN_HAMBURGER
                ? hamburgerY + hamburgerH : accountY + accountH) + pad;
        int count = activeDropdown == DROPDOWN_HAMBURGER
                ? HAMBURGER_ACTIONS.length : ACCOUNT_ACTIONS.length;
        return inside(mouseX, mouseY, x, y, width, pad * 2 + rowH * count);
    }

    // ==================================================================
    // 文本
    // ==================================================================

    private static int textWidth(FontRenderer font, String text, float size) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        return Math.max(0, Math.round(HudFont.getStringWidth(font, text, size)));
    }

    private static void drawTrackedCentered(FontRenderer font, String text, int centerX,
            int y, int color, float size, float tracking, boolean bold, boolean shadow) {
        float width = trackedWidth(font, text, size, tracking, bold);
        drawTrackedLeft(font, text, centerX - width / 2.0F, y, color, size,
                tracking, bold, shadow);
    }

    private static void drawTrackedLeft(FontRenderer font, String text, float x, int y,
            int color, float size, float tracking, boolean bold) {
        drawTrackedLeft(font, text, x, y, color, size, tracking, bold, false);
    }

    private static void drawTrackedLeft(FontRenderer font, String text, float x, int y,
            int color, float size, float tracking, boolean bold, boolean shadow) {
        if (font == null || text == null || text.length() == 0 || (color >>> 24) == 0) {
            return;
        }
        String prefix = bold ? "\u00a7l" : "";
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            String glyph = prefix + text.charAt(i);
            int drawX = Math.round(cursor);
            if (shadow) {
                HudFont.drawStringWithShadow(font, glyph, drawX, y, color, size);
            } else {
                HudFont.drawString(font, glyph, drawX, y, color, size);
            }
            cursor += HudFont.getStringWidth(font, glyph, size) + tracking;
        }
    }

    private static float trackedWidth(FontRenderer font, String text, float size,
            float tracking, boolean bold) {
        if (font == null || text == null || text.length() == 0) {
            return 0.0F;
        }
        String prefix = bold ? "\u00a7l" : "";
        float width = 0.0F;
        for (int i = 0; i < text.length(); i++) {
            width += HudFont.getStringWidth(font, prefix + text.charAt(i), size) + tracking;
        }
        return Math.max(0.0F, width - tracking);
    }

    // ==================================================================
    // 基础几何
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
        float maxRadius = Math.min(w / 2.0F, h / 2.0F);
        float inner = Math.min(r, maxRadius);
        float ox = x - AA_FEATHER;
        float oy = y - AA_FEATHER;
        float ow = w + AA_FEATHER * 2.0F;
        float oh = h + AA_FEATHER * 2.0F;
        float outer = Math.min(inner + AA_FEATHER, Math.min(ow / 2.0F, oh / 2.0F));

        appendRoundedRect(AA_INNER_X, AA_INNER_Y, x, y, w, h, inner);
        appendRoundedRect(AA_OUTER_X, AA_OUTER_Y, ox, oy, ow, oh, outer);

        aaBegin();
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        setVertexColor(color, 1.0F);
        GL11.glVertex2f(x + w / 2.0F, y + h / 2.0F);
        for (int i = 0; i < AA_POINT_COUNT; i++) {
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(AA_INNER_X[i], AA_INNER_Y[i]);
        }
        GL11.glVertex2f(AA_INNER_X[0], AA_INNER_Y[0]);
        GL11.glEnd();

        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= AA_POINT_COUNT; i++) {
            int index = i == AA_POINT_COUNT ? 0 : i;
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(AA_INNER_X[index], AA_INNER_Y[index]);
            setVertexColor(color, 0.0F);
            GL11.glVertex2f(AA_OUTER_X[index], AA_OUTER_Y[index]);
        }
        GL11.glEnd();
        aaEnd();
    }

    private static void appendRoundedRect(float[] xs, float[] ys,
            float x, float y, float w, float h, float radius) {
        int index = 0;
        index = appendCorner(xs, ys, index, x + radius, y + radius, radius, 180.0F);
        index = appendCorner(xs, ys, index, x + w - radius, y + radius, radius, 270.0F);
        index = appendCorner(xs, ys, index, x + w - radius, y + h - radius, radius, 0.0F);
        appendCorner(xs, ys, index, x + radius, y + h - radius, radius, 90.0F);
    }

    private static int appendCorner(float[] xs, float[] ys, int index,
            float cx, float cy, float radius, float startAngle) {
        for (int i = 0; i <= AA_ARC_SEGMENTS; i++) {
            double angle = Math.toRadians(startAngle + i * (90.0F / AA_ARC_SEGMENTS));
            xs[index] = cx + (float) Math.cos(angle) * radius;
            ys[index] = cy + (float) Math.sin(angle) * radius;
            index++;
        }
        return index;
    }

    private static void drawLine(float x1, float y1, float x2, float y2, int color) {
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
        float half = 0.6F;
        float feather = Math.max(0.75F, AA_FEATHER * 0.5F);
        aaBegin();
        // Crisp core.
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int end = 0; end < 2; end++) {
            float px = end == 0 ? x1 : x2;
            float py = end == 0 ? y1 : y2;
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(px + nx * half, py + ny * half);
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(px - nx * half, py - ny * half);
        }
        GL11.glEnd();
        // Feather both sides of the line instead of leaving hard pixel edges.
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int end = 0; end < 2; end++) {
            float px = end == 0 ? x1 : x2;
            float py = end == 0 ? y1 : y2;
            setVertexColor(color, 0.0F);
            GL11.glVertex2f(px + nx * (half + feather), py + ny * (half + feather));
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(px + nx * half, py + ny * half);
        }
        GL11.glEnd();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int end = 0; end < 2; end++) {
            float px = end == 0 ? x1 : x2;
            float py = end == 0 ? y1 : y2;
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(px - nx * half, py - ny * half);
            setVertexColor(color, 0.0F);
            GL11.glVertex2f(px - nx * (half + feather), py - ny * (half + feather));
        }
        GL11.glEnd();
        aaEnd();
    }

    private static void drawVerticalGradient(int x, int y, int w, int h,
            int topColor, int bottomColor) {
        if (w <= 0 || h <= 0) {
            return;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glBegin(GL11.GL_QUADS);
        setVertexColor(topColor, 1.0F);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        setVertexColor(bottomColor, 1.0F);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    private static void fillHexagon(float cx, float cy, float radius, int color) {
        if (radius <= 0.0F || (color >>> 24) == 0) {
            return;
        }
        float core = Math.max(0.0F, radius - AA_FEATHER * 0.5F);
        float edge = radius + AA_FEATHER * 0.5F;
        aaBegin();
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        setVertexColor(color, 1.0F);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= 6; i++) {
            double angle = Math.toRadians(-90.0D + i * 60.0D);
            GL11.glVertex2f(cx + (float) Math.cos(angle) * core,
                    cy + (float) Math.sin(angle) * core);
        }
        GL11.glEnd();
        // Feather the polygon edge: opaque core fading to transparent at the outer radius.
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= 6; i++) {
            double angle = Math.toRadians(-90.0D + i * 60.0D);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(cx + cos * core, cy + sin * core);
            setVertexColor(color, 0.0F);
            GL11.glVertex2f(cx + cos * edge, cy + sin * edge);
        }
        GL11.glEnd();
        aaEnd();
    }

    private static void fillHexagonGradient(float cx, float cy, float radius,
            int topColor, int bottomColor) {
        if (radius <= 0.0F) {
            return;
        }
        float core = Math.max(0.0F, radius - AA_FEATHER * 0.5F);
        float edge = radius + AA_FEATHER * 0.5F;
        aaBegin();
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        setVertexColor(blendColor(topColor, bottomColor, 0.5F), 1.0F);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= 6; i++) {
            double angle = Math.toRadians(-90.0D + i * 60.0D);
            float y = (float) Math.sin(angle);
            int color = blendColor(topColor, bottomColor, (y + 1.0F) * 0.5F);
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(cx + (float) Math.cos(angle) * core, cy + y * core);
        }
        GL11.glEnd();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= 6; i++) {
            double angle = Math.toRadians(-90.0D + i * 60.0D);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            int color = blendColor(topColor, bottomColor, (sin + 1.0F) * 0.5F);
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(cx + cos * core, cy + sin * core);
            setVertexColor(color, 0.0F);
            GL11.glVertex2f(cx + cos * edge, cy + sin * edge);
        }
        GL11.glEnd();
        aaEnd();
    }

    private static void drawHexagonRing(float cx, float cy, float radius,
            float thickness, int color) {
        if (radius <= 0.0F || thickness <= 0.0F || (color >>> 24) == 0) {
            return;
        }
        float inner = Math.max(0.0F, radius - thickness);
        float outerEdge = radius + AA_FEATHER * 0.5F;
        float innerEdge = Math.max(0.0F, inner - AA_FEATHER * 0.5F);
        aaBegin();
        // Solid ring.
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= 6; i++) {
            double angle = Math.toRadians(-90.0D + i * 60.0D);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(cx + cos * radius, cy + sin * radius);
            GL11.glVertex2f(cx + cos * inner, cy + sin * inner);
        }
        GL11.glEnd();
        // Feather both edges of the ring.
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= 6; i++) {
            double angle = Math.toRadians(-90.0D + i * 60.0D);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(cx + cos * radius, cy + sin * radius);
            setVertexColor(color, 0.0F);
            GL11.glVertex2f(cx + cos * outerEdge, cy + sin * outerEdge);
        }
        GL11.glEnd();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= 6; i++) {
            double angle = Math.toRadians(-90.0D + i * 60.0D);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            setVertexColor(color, 1.0F);
            GL11.glVertex2f(cx + cos * inner, cy + sin * inner);
            setVertexColor(color, 0.0F);
            GL11.glVertex2f(cx + cos * innerEdge, cy + sin * innerEdge);
        }
        GL11.glEnd();
        aaEnd();
    }

    private static void aaBegin() {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_POLYGON_SMOOTH);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void aaEnd() {
        GL11.glPopAttrib();
        GlColor.restore();
    }

    private static void setVertexColor(int argb, float alphaScale) {
        float alpha = ((argb >>> 24) / 255.0F) * alphaScale;
        GL11.glColor4f((argb >> 16 & 0xFF) / 255.0F,
                (argb >> 8 & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F, alpha);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha & 0xFF) << 24 | (color & 0xFFFFFF);
    }

    private static int scaleAlpha(int argb, float factor) {
        int alpha = Math.round((argb >>> 24) * Math.max(0.0F, Math.min(1.0F, factor)));
        return withAlpha(argb, alpha);
    }

    private static int blendColor(int from, int to, float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int alpha = Math.round((from >>> 24) + ((to >>> 24) - (from >>> 24)) * t);
        int red = Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
        int green = Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
        int blue = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static float approach(float value, float target, float deltaMs, float durationMs) {
        if (deltaMs <= 0.0F || durationMs <= 0.0F) {
            return value;
        }
        float amount = Math.min(1.0F, deltaMs / durationMs);
        return value + (target - value) * amount;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
