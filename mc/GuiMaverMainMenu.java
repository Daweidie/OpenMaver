import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
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
 * 清晰的原版全景图、中央 Logo 与三行透明按钮；账号头像与快捷入口放在顶部。
 */
@SideOnly(Side.CLIENT)
public final class GuiMaverMainMenu extends GuiScreen {
    private static final ResourceLocation[] PANORAMA = {
        new ResourceLocation("textures/gui/title/background/panorama_0.png"),
        new ResourceLocation("textures/gui/title/background/panorama_1.png"),
        new ResourceLocation("textures/gui/title/background/panorama_2.png"),
        new ResourceLocation("textures/gui/title/background/panorama_3.png"),
        new ResourceLocation("textures/gui/title/background/panorama_4.png"),
        new ResourceLocation("textures/gui/title/background/panorama_5.png")
    };
    private static final float PANORAMA_SCALE = 1.8F;
    private static final int ACTION_SINGLEPLAYER = 1;
    private static final int ACTION_MULTIPLAYER = 2;
    private static final int ACTION_CLIENT = 3;
    private static final int ACTION_OPTIONS = 4;
    private static final int ACTION_QUIT = 5;
    private static final int ACTION_WALLPAPER = 6;
    private static final int ACTION_FULLSCREEN = 7;

    private final List<MenuButton> buttons = new ArrayList<MenuButton>();
    private String username = "Player";
    private boolean profileResolved;
    private boolean failed;
    private boolean avatarFailed;
    private volatile BufferedImage skinImage;
    private ResourceLocation skinTexture;
    private boolean skinFetchStarted;
    private static final Map<String, BufferedImage> SKIN_CACHE =
            new HashMap<String, BufferedImage>();

    private int menuTop;
    /** 菜单第一次打开的时间，用于入场动画；改窗口大小重新布局时不重置。 */
    private long openedAt;
    private long lastFrameAt;

    private static final class MenuButton {
        final String label;
        final int action;
        final int x, y, w, h;
        /** 入场动画的先后顺序。 */
        final int order;
        /** 退出按钮悬停时用红色强调。 */
        final boolean danger;
        /** 0..1 的悬停进度，逐帧向目标值靠近。 */
        float hover;

        MenuButton(String label, int action, int x, int y, int w, int h,
                int order, boolean danger) {
            this.label = label;
            this.action = action;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.order = order;
            this.danger = danger;
        }

        boolean isShortcut() {
            return action != ACTION_SINGLEPLAYER && action != ACTION_MULTIPLAYER
                    && action != ACTION_OPTIONS;
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        failed = false;
        avatarFailed = false;
        if (openedAt == 0L) {
            openedAt = System.currentTimeMillis();
        }
        resolveProfile();
        HudFont.useMenuFont();
        layoutMenu();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        HudFont.useMenuFont();
        updateHover(mouseX, mouseY);
        try {
            drawBackground(width, height);
            drawTitle();
            if (!avatarFailed) {
                try {
                    drawAccount();
                } catch (Throwable t) {
                    avatarFailed = true;
                    System.err.println("[OpenMaver] Account avatar unavailable: " + t);
                } finally {
                    resetUiState();
                }
            }
            drawButtons(mouseX, mouseY);
            drawFooter();
        } catch (Throwable t) {
            if (!failed) {
                failed = true;
                System.err.println("[OpenMaver] Main menu fallback: " + t);
            }
            resetUiState();
            Gui.drawRect(0, 0, width, height, 0xFF15181E);
            drawPixelText("Maver", width / 2.0F, height * 0.18F, 2.0F * uiScale(),
                    0xFFFFFFFF, true);
            drawButtons(mouseX, mouseY);
            drawFooter();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0) {
            return;
        }
        for (MenuButton button : buttons) {
            if (inside(mouseX, mouseY, button)) {
                perform(button.action);
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 与原版标题界面一样，ESC 不会关闭菜单进入空白画面。
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

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
     * 把官方皮肤图统一成 64×64 纹理，并为旧版皮肤镜像补全左手 / 左腿。
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
            boolean legacy = width == height * 2;
            graphics.drawImage(source, 0, 0, 64, legacy ? 32 : 64, null);
            if (legacy) {
                graphics.drawImage(result, 24, 48, 20, 52, 4, 16, 8, 20, null);
                graphics.drawImage(result, 28, 48, 24, 52, 8, 16, 12, 20, null);
                graphics.drawImage(result, 20, 52, 16, 64, 8, 20, 12, 32, null);
                graphics.drawImage(result, 24, 52, 20, 64, 4, 20, 8, 32, null);
                graphics.drawImage(result, 28, 52, 24, 64, 0, 20, 4, 32, null);
                graphics.drawImage(result, 32, 52, 28, 64, 12, 20, 16, 32, null);
                graphics.drawImage(result, 40, 48, 36, 52, 44, 16, 48, 20, null);
                graphics.drawImage(result, 44, 48, 40, 52, 48, 16, 52, 20, null);
                graphics.drawImage(result, 36, 52, 32, 64, 48, 20, 52, 32, null);
                graphics.drawImage(result, 40, 52, 36, 64, 44, 20, 48, 32, null);
                graphics.drawImage(result, 44, 52, 40, 64, 40, 20, 44, 32, null);
                graphics.drawImage(result, 48, 52, 44, 64, 52, 20, 56, 32, null);
            }
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private float uiScale() {
        return Math.max(0.5F, Math.min(2.0F, Math.min(width / 600.0F, height / 270.0F)));
    }

    private void layoutMenu() {
        float ui = uiScale();
        int buttonW = Math.round(190.0F * ui);
        int buttonH = Math.round(22.0F * ui);
        int gap = Math.max(3, Math.round(5.0F * ui));
        int x = (width - buttonW) / 2;
        int top = Math.round(height * 0.43F);
        buttons.clear();
        buttons.add(new MenuButton("Singleplayer", ACTION_SINGLEPLAYER,
                x, top, buttonW, buttonH, 0, false));
        buttons.add(new MenuButton("Multiplayer", ACTION_MULTIPLAYER,
                x, top + buttonH + gap, buttonW, buttonH, 1, false));
        buttons.add(new MenuButton("Settings", ACTION_OPTIONS,
                x, top + 2 * (buttonH + gap), buttonW, buttonH, 2, false));

        int iconSize = Math.round(24.0F * ui);
        int iconGap = Math.max(3, Math.round(5.0F * ui));
        int pad = Math.round(12.0F * ui);
        int iconY = Math.round(10.0F * ui);
        int right = width - pad - iconSize;
        buttons.add(new MenuButton("Fullscreen", ACTION_FULLSCREEN,
                right - 2 * (iconSize + iconGap), iconY, iconSize, iconSize, 0, false));
        buttons.add(new MenuButton("Wallpaper", ACTION_WALLPAPER,
                right - iconSize - iconGap, iconY, iconSize, iconSize, 0, false));
        buttons.add(new MenuButton("Quit Game", ACTION_QUIT,
                right, iconY, iconSize, iconSize, 0, true));
        int nameW = HudFont.getStringWidth(fontRendererObj, username, 0.95F * ui);
        int clientX = Math.round(40.0F * ui) + nameW + Math.round(5.0F * ui);
        clientX = Math.min(clientX, right - 3 * (iconSize + iconGap));
        buttons.add(new MenuButton("ClickGUI", ACTION_CLIENT,
                clientX, iconY, iconSize, iconSize, 0, false));
        menuTop = top;
    }

    /** 0..1 的入场进度；index 越大越晚出现，形成错落的渐入。 */
    private float appear(int index) {
        float t = (System.currentTimeMillis() - openedAt - index * 45.0F) / 380.0F;
        t = Math.max(0.0F, Math.min(1.0F, t));
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }

    private void updateHover(int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        float dt = lastFrameAt == 0L ? 0.0F : Math.min(50.0F, now - lastFrameAt);
        lastFrameAt = now;
        float k = Math.min(1.0F, dt / 90.0F);
        for (MenuButton button : buttons) {
            float target = inside(mouseX, mouseY, button) ? 1.0F : 0.0F;
            button.hover += (target - button.hover) * k;
        }
    }

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
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(false);
        GlColor.restore();
    }

    /** 与菜单设置页共用，每帧绘制当前壁纸，异步加载完成后立即显示。 */
    void drawBackground(int width, int height) {
        resetUiState();
        boolean custom = MaverWallpaper.draw(mc, width, height);
        if (!custom) {
            drawPanorama();
        }
        // 直接呈现原始背景，不捕获低分辨率快照，也不执行任何模糊通道。
        verticalGradient(0, 0, width, Math.round(height * 0.18F), 0x28000000, 0x00000000);
        verticalGradient(0, Math.round(height * 0.88F), width, height, 0x00000000, 0x30000000);
    }

    private void drawTitle() {
        float ui = uiScale();
        float a = appear(0);
        float lift = (1.0F - a) * 8.0F * ui;
        int centerX = width / 2;
        int logoH = Math.round(44.0F * ui);
        int logoW = Math.round(148.0F * ui);
        int logoCenterY = Math.round(menuTop - 40.0F * ui + lift);

        if (a > 0.02F) {
            MaverLogo.drawMenuCentered(fontRendererObj, centerX, logoCenterY,
                    logoW, logoH, 1.0F, fade(0xFFFFFFFF, a));
        }
    }

    private void drawButtons(int mouseX, int mouseY) {
        float ui = uiScale();
        float textSize = 1.05F * ui;
        for (MenuButton button : buttons) {
            if (button.isShortcut()) {
                drawShortcut(button, mouseX, mouseY);
                continue;
            }
            float a = appear(button.order + 1);
            if (a <= 0.01F) {
                continue;
            }
            float h = button.hover;
            float lift = (1.0F - a) * 6.0F * ui;
            int x = button.x;
            int y = Math.round(button.y + lift);
            int w = button.w;
            int bh = button.h;
            float r = Math.min(6.0F * ui, bh / 2.0F);
            // 纯半透明底色，背景纹理直接透过按钮，不做毛玻璃处理。
            fillRound(x, y, w, bh, r, fade(blend(0x48FFFFFF, 0x78FFFFFF, h), a));
            int rim = blend(0x28FFFFFF, 0x80FFFFFF, h);
            strokeRound(x, y, w, bh, r, 1.0F, fade(rim, a));

            int textColor = 0xFFFFFFFF;
            float size = Math.min(textSize,
                    (w - 12.0F) / Math.max(1, HudFont.getStringWidth(fontRendererObj,
                            button.label, 1.0F)));
            int textW = HudFont.getStringWidth(fontRendererObj, button.label, size);
            drawText(button.label, x + (w - textW) / 2.0F,
                    y + (bh - 8.0F * size) / 2.0F, size, fade(textColor, a));
            if (h > 0.02F) {
                // 悬停时底部出现一条细亮线，宽度随悬停进度展开。
                float lineW = (w - 2.0F * r) * h;
                fillRound(x + (w - lineW) / 2.0F, y + bh - 2.0F, lineW, 1.0F, 0.5F,
                        fade(0xFFFFFFFF, a * h * 0.85F));
            }
        }
    }

    private void drawFooter() {
        float ui = uiScale();
        float a = appear(3);
        float size = 0.78F * ui;
        float y = height - 13.0F * ui;
        int color = fade(0xFFDDE2E5, a);
        float pad = 8.0F * ui;
        drawText("Maver Client " + OpenMaver.VERSION, pad, y, size, color);
        String copyright = "Copyright Mojang AB. Do not distribute!";
        int copyrightW = HudFont.getStringWidth(fontRendererObj, copyright, size);
        drawText(copyright, width - pad - copyrightW, y, size, color);
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

            // 全分辨率绘制一次原版全景立方体，不叠加抖动采样或模糊副本。
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
                    // 原版全景贴图只有 256×256；强制逐像素采样，避免放大时被平滑滤镜糊开。
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                            GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                            GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                            GL11.GL_CLAMP_TO_EDGE);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                            GL11.GL_CLAMP_TO_EDGE);
                    GL11.glBegin(GL11.GL_QUADS);
                    setVertexColor(0xFFFFFFFF, 1.0F);
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
            GlStateManager.bindTexture(0);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            GlColor.restore();
        }
    }

    private void drawAccount() {
        TextureManager textures = mc.getTextureManager();
        if (textures == null) {
            return;
        }
        float ui = uiScale();
        float a = appear(0);
        float x = 14.0F * ui;
        float y = 13.0F * ui;
        float size = 18.0F * ui;
        BufferedImage image = skinImage;
        if (skinTexture == null && image != null) {
            ResourceLocation location = new ResourceLocation("openmaver", "skin/menu_player");
            if (textures.loadTexture(location, new DynamicTexture(image))) {
                skinTexture = location;
            }
        }
        ResourceLocation skin = skinTexture != null ? skinTexture
                : DefaultPlayerSkin.getDefaultSkin(new UUID(0L, 0L));
        GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);
        try {
            GlStateManager.bindTexture(0);
            textures.bindTexture(skin);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            setVertexColor(fade(0xFFFFFFFF, a), 1.0F);
            drawSkinFace(x, y, size, 8, 8);
            drawSkinFace(x, y, size, 40, 8);
        } finally {
            GlStateManager.bindTexture(0);
            GL11.glPopAttrib();
            GlColor.restore();
        }
        float nameX = 40.0F * ui;
        float nameSize = 0.95F * ui;
        float available = buttons.get(buttons.size() - 1).x - nameX - 4.0F * ui;
        String name = username;
        while (name.length() > 0
                && HudFont.getStringWidth(fontRendererObj, name, nameSize) > available) {
            name = name.substring(0, name.length() - 1);
        }
        drawText(name, nameX, y + (size - 8.0F * nameSize) / 2.0F,
                nameSize, fade(0xFFFFFFFF, a));
    }

    private static void drawSkinFace(float x, float y, float size, int u, int v) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u / 64.0F, v / 64.0F);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u / 64.0F, (v + 8) / 64.0F);
        GL11.glVertex2f(x, y + size);
        GL11.glTexCoord2f((u + 8) / 64.0F, (v + 8) / 64.0F);
        GL11.glVertex2f(x + size, y + size);
        GL11.glTexCoord2f((u + 8) / 64.0F, v / 64.0F);
        GL11.glVertex2f(x + size, y);
        GL11.glEnd();
    }

    private void drawShortcut(MenuButton button, int mouseX, int mouseY) {
        float ui = uiScale();
        float a = appear(0);
        float h = button.hover;
        int tint = button.danger ? 0xFFE5676B : 0xFFFFFFFF;
        int color = fade(blend(0xE6FFFFFF, tint, h), a);
        float x = button.x, y = button.y, w = button.w, bh = button.h;
        float cx = x + w / 2.0F, cy = y + bh / 2.0F;
        if (button.action != ACTION_CLIENT || h > 0.01F) {
            fillRound(x, y, w, bh, 3.0F * ui,
                    fade(blend(0x28FFFFFF, button.danger ? 0x60D84950 : 0x55FFFFFF, h), a));
        }
        float d = 5.0F * ui;
        float lineWidth = Math.max(0.7F, 1.2F * ui);
        if (button.action == ACTION_QUIT || button.action == ACTION_CLIENT) {
            if (button.action == ACTION_QUIT) {
                iconLine(cx - d, cy - d, cx + d, cy + d, lineWidth, color);
                iconLine(cx - d, cy + d, cx + d, cy - d, lineWidth, color);
            } else {
                iconLine(cx - d, cy, cx + d, cy, lineWidth, color);
                iconLine(cx, cy - d, cx, cy + d, lineWidth, color);
            }
        } else if (button.action == ACTION_FULLSCREEN) {
            strokeRound(cx - d, cy - d / 2, d * 1.5F, d * 1.5F,
                    ui, lineWidth, color);
            strokeRound(cx - d / 2, cy - d, d * 1.5F, d * 1.5F,
                    ui, lineWidth, color);
        } else if (button.action == ACTION_WALLPAPER) {
            strokeRound(cx - d, cy - d, d * 2, d * 2, ui, lineWidth, color);
            fillRound(cx + d * 0.25F, cy - d * 0.55F, ui * 1.8F, ui * 1.8F, ui, color);
            iconLine(cx - d * 0.7F, cy + d * 0.6F, cx - d * 0.15F, cy - d * 0.1F,
                    lineWidth, color);
            iconLine(cx - d * 0.15F, cy - d * 0.1F, cx + d * 0.65F, cy + d * 0.6F,
                    lineWidth, color);
        }
        if (inside(mouseX, mouseY, button)) {
            float textSize = 0.75F * ui;
            int textW = HudFont.getStringWidth(fontRendererObj, button.label, textSize);
            float textX = Math.max(4.0F * ui,
                    Math.min(cx - textW / 2.0F, width - textW - 8.0F * ui));
            float textY = y + bh + 7.0F * ui;
            fillRound(textX - 4.0F * ui, textY - 3.0F * ui,
                    textW + 8.0F * ui, 14.0F * ui, 3.0F * ui, fade(0xC02A3036, a));
            drawText(button.label, textX, textY, textSize, fade(0xFFFFFFFF, a));
        }
    }

    private static void iconLine(float x0, float y0, float x1, float y1,
            float thickness, int color) {
        float length = (float) Math.hypot(x1 - x0, y1 - y0);
        if (length <= 0.0F) {
            return;
        }
        float dx = -(y1 - y0) / length * thickness / 2.0F;
        float dy = (x1 - x0) / length * thickness / 2.0F;
        shapeBegin();
        GL11.glBegin(GL11.GL_QUADS);
        setVertexColor(color, 1.0F);
        GL11.glVertex2f(x0 + dx, y0 + dy);
        GL11.glVertex2f(x1 + dx, y1 + dy);
        GL11.glVertex2f(x1 - dx, y1 - dy);
        GL11.glVertex2f(x0 - dx, y0 - dy);
        GL11.glEnd();
        shapeEnd();
    }

    private void drawText(String text, float x, float y, float size, int color) {
        if ((color >>> 24) < 4) {
            return;
        }
        GlColor.restore();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        HudFont.drawString(fontRendererObj, text, Math.round(x), Math.round(y), color, size);
        GlColor.restore();
    }

    private void drawPixelText(String text, float x, float y, float size, int color,
            boolean shadow) {
        GlColor.restore();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0.0F);
            GL11.glScalef(size, size, 1.0F);
            int textX = -fontRendererObj.getStringWidth(text) / 2;
            if (shadow) {
                fontRendererObj.drawStringWithShadow(text, textX, 0, color);
            } else {
                fontRendererObj.drawString(text, textX, 0, color);
            }
        } finally {
            GL11.glPopMatrix();
            GlColor.restore();
        }
    }

    // ------------------------------------------------------------------
    // 形状：带 1px 顶点 alpha 羽化的圆角矩形，与 ClickGUI 的画法一致。
    // ------------------------------------------------------------------

    private static final int ARC_SEGMENTS = 8;

    private static void fillRound(float x, float y, float w, float h, float radius, int color) {
        if (w <= 0.0F || h <= 0.0F || (color >>> 24) == 0) {
            return;
        }
        float r = Math.max(0.0F, Math.min(radius, Math.min(w, h) / 2.0F));
        float[][] inner = roundedPath(x, y, w, h, r);
        float[][] outer = roundedPath(x - 1.0F, y - 1.0F, w + 2.0F, h + 2.0F, r + 1.0F);
        shapeBegin();
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        setVertexColor(color, 1.0F);
        GL11.glVertex2f(x + w / 2.0F, y + h / 2.0F);
        for (int i = 0; i <= inner[0].length; i++) {
            int k = i % inner[0].length;
            GL11.glVertex2f(inner[0][k], inner[1][k]);
        }
        GL11.glEnd();
        band(inner, outer, color, 1.0F, 0.0F);
        shapeEnd();
    }

    /** 圆角描边：内外各带一圈羽化，细线也不会出现锯齿。 */
    private static void strokeRound(float x, float y, float w, float h, float radius,
            float thickness, int color) {
        if (w <= 0.0F || h <= 0.0F || (color >>> 24) == 0) {
            return;
        }
        float r = Math.max(0.0F, Math.min(radius, Math.min(w, h) / 2.0F));
        float t = thickness;
        float[][] solidOut = roundedPath(x, y, w, h, r);
        float[][] solidIn = roundedPath(x + t, y + t, w - 2 * t, h - 2 * t, Math.max(0.0F, r - t));
        float[][] featherOut = roundedPath(x - 0.6F, y - 0.6F, w + 1.2F, h + 1.2F, r + 0.6F);
        float[][] featherIn = roundedPath(x + t + 0.6F, y + t + 0.6F,
                w - 2 * t - 1.2F, h - 2 * t - 1.2F, Math.max(0.0F, r - t - 0.6F));
        shapeBegin();
        band(featherIn, solidIn, color, 0.0F, 1.0F);
        band(solidIn, solidOut, color, 1.0F, 1.0F);
        band(solidOut, featherOut, color, 1.0F, 0.0F);
        shapeEnd();
    }

    private static void band(float[][] from, float[][] to, int color, float fromAlpha,
            float toAlpha) {
        int n = from[0].length;
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= n; i++) {
            int k = i % n;
            setVertexColor(color, fromAlpha);
            GL11.glVertex2f(from[0][k], from[1][k]);
            setVertexColor(color, toAlpha);
            GL11.glVertex2f(to[0][k], to[1][k]);
        }
        GL11.glEnd();
    }

    /** 顺时针采样四个圆角，点数固定，方便内外两条路径逐点配对。 */
    private static float[][] roundedPath(float x, float y, float w, float h, float r) {
        int per = ARC_SEGMENTS + 1;
        float[] xs = new float[per * 4];
        float[] ys = new float[per * 4];
        float[] cx = {x + r, x + w - r, x + w - r, x + r};
        float[] cy = {y + r, y + r, y + h - r, y + h - r};
        float[] start = {180.0F, 270.0F, 0.0F, 90.0F};
        int index = 0;
        for (int corner = 0; corner < 4; corner++) {
            for (int i = 0; i <= ARC_SEGMENTS; i++) {
                double angle = Math.toRadians(start[corner] + i * (90.0F / ARC_SEGMENTS));
                xs[index] = cx[corner] + (float) Math.cos(angle) * r;
                ys[index] = cy[corner] + (float) Math.sin(angle) * r;
                index++;
            }
        }
        return new float[][] {xs, ys};
    }

    private static void verticalGradient(float x0, float y0, float x1, float y1,
            int top, int bottom) {
        shapeBegin();
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glBegin(GL11.GL_QUADS);
        setVertexColor(top, 1.0F);
        GL11.glVertex2f(x0, y0);
        setVertexColor(bottom, 1.0F);
        GL11.glVertex2f(x0, y1);
        GL11.glVertex2f(x1, y1);
        setVertexColor(top, 1.0F);
        GL11.glVertex2f(x1, y0);
        GL11.glEnd();
        shapeEnd();
    }

    private static void shapeBegin() {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_POLYGON_SMOOTH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void shapeEnd() {
        GL11.glPopAttrib();
        GlColor.restore();
    }

    private static int fade(int argb, float alpha) {
        float a = Math.max(0.0F, Math.min(1.0F, alpha));
        return Math.round((argb >>> 24) * a) << 24 | (argb & 0xFFFFFF);
    }

    private static int blend(int from, int to, float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int alpha = Math.round((from >>> 24) + ((to >>> 24) - (from >>> 24)) * t);
        int red = Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
        int green = Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
        int blue = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private void perform(int action) {
        OpenMaverFeatures.playUiClick(action == ACTION_QUIT ? 0.85F : 1.0F);
        try {
            if (action == ACTION_SINGLEPLAYER) {
                mc.displayGuiScreen(new GuiSelectWorld(this));
            } else if (action == ACTION_MULTIPLAYER) {
                mc.displayGuiScreen(new GuiMultiplayer(this));
            } else if (action == ACTION_CLIENT) {
                openClickGui(null);
            } else if (action == ACTION_WALLPAPER) {
                openClickGui("wallpaper");
            } else if (action == ACTION_OPTIONS) {
                mc.displayGuiScreen(new GuiOptions(this, mc.gameSettings));
            } else if (action == ACTION_FULLSCREEN) {
                mc.toggleFullscreen();
            } else if (action == ACTION_QUIT) {
                mc.shutdown();
            }
        } catch (Throwable t) {
            System.err.println("[OpenMaver] Main menu action failed: " + action + " -> " + t);
        }
    }

    private void openClickGui(String initialColumn) {
        try {
            if (!OpenMaver.isStarted()) {
                OpenMaver.start();
            }
        } catch (Throwable t) {
            System.err.println("[OpenMaver] Core startup failed: " + t);
        }
        mc.displayGuiScreen(new GuiOpenMaverClickGui(this, initialColumn));
    }

    private static boolean inside(int mouseX, int mouseY, MenuButton button) {
        return mouseX >= button.x && mouseX < button.x + button.w
                && mouseY >= button.y && mouseY < button.y + button.h;
    }

    private static void setVertexColor(int argb, float alphaScale) {
        GlColor.set((argb >> 16 & 0xFF) / 255.0F, (argb >> 8 & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F, (argb >>> 24) / 255.0F * alphaScale);
    }
}
