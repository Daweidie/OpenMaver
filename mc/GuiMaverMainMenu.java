import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.gui.ScaledResolution;
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
 * Minecraft 标题界面风格的 Maver 主菜单：中央灰色按钮、右侧皮肤展示。
 * 自绘皮肤不依赖世界或玩家实体，因此尚未进入世界也能显示。
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

    private final List<MenuButton> buttons = new ArrayList<MenuButton>();
    private String username = "Player";
    private boolean profileResolved;
    private boolean failed;
    private boolean previewFailed;
    private volatile BufferedImage skinImage;
    private ResourceLocation skinTexture;
    private boolean skinFetchStarted;
    private static final Map<String, BufferedImage> SKIN_CACHE =
            new HashMap<String, BufferedImage>();

    private static final class MenuButton {
        final String label;
        final int action;
        final int x, y, w, h;

        MenuButton(String label, int action, int x, int y, int w, int h) {
            this.label = label;
            this.action = action;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        failed = false;
        previewFailed = false;
        resolveProfile();
        layoutMenu();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        try {
            resetUiState();
            drawBackground();
            drawTitle();
            if (!previewFailed) {
                try {
                    drawPlayerPreview();
                } catch (Throwable t) {
                    previewFailed = true;
                    System.err.println("[OpenMaver] Skin preview unavailable: " + t);
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
            Gui.drawRect(0, 0, width, height, 0xFF343434);
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
        int buttonH = Math.round(36.0F * ui);
        int gap = Math.max(2, Math.round(5.0F * ui));
        int x = (width - buttonW) / 2;
        int top = Math.round(height * 0.36F);
        buttons.clear();
        buttons.add(new MenuButton("Singleplayer", ACTION_SINGLEPLAYER,
                x, top, buttonW, buttonH));
        buttons.add(new MenuButton("Multiplayer", ACTION_MULTIPLAYER,
                x, top + buttonH + gap, buttonW, buttonH));
        buttons.add(new MenuButton("Settings", ACTION_OPTIONS,
                x, top + 2 * (buttonH + gap), buttonW, buttonH));

        int pad = Math.max(4, Math.round(8.0F * ui));
        int bottomY = height - Math.round(47.0F * ui);
        int smallH = Math.round(28.0F * ui);
        int clientW = Math.round(112.0F * ui);
        int wallpaperW = Math.round(82.0F * ui);
        int quitW = Math.round(82.0F * ui);
        buttons.add(new MenuButton("Client Settings", ACTION_CLIENT,
                pad, bottomY, clientW, smallH));
        buttons.add(new MenuButton("Wallpaper", ACTION_WALLPAPER,
                pad + clientW + gap, bottomY, wallpaperW, smallH));
        buttons.add(new MenuButton("Quit Game", ACTION_QUIT,
                width - pad - quitW, bottomY, quitW, smallH));
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

    private void drawBackground() {
        boolean custom = MaverWallpaper.draw(mc, width, height);
        if (!custom) {
            drawPanorama();
        }
        float blur = MaverWallpaper.getBlurStrength();
        if (blur > 0.01F) {
            try {
                ScaledResolution resolution = new ScaledResolution(mc);
                GuiBlur.beginFrame(width, height, resolution.getScaleFactor());
                GuiBlur.draw(0, 0, width, height, 0.0F, 0x000000, 0.0F, 1.0F, blur);
            } catch (Throwable ignored) {
                // 模糊不可用时仍保留全景图 / 壁纸。
            }
        }
        Gui.drawRect(0, 0, width, height, 0x18000000);
        Gui.drawRect(0, height - Math.round(16.0F * uiScale()), width, height, 0x35000000);
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

            // 只绘制一次全景立方体。之前叠加 36 层不同角度的半透明副本会在
            // 模糊度调到 0 时仍然显得发糊；模糊交给 GuiBlur 按滑块强度处理。
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

    private void drawTitle() {
        float ui = uiScale();
        drawMinecraftWordmark();
        double phase = (System.nanoTime() % 1_000_000_000L) / 1_000_000_000.0 * Math.PI * 2.0;
        float pulse = 1.8F - (float) Math.abs(Math.sin(phase)) * 0.1F;
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(width / 2.0F + 66.0F * ui, height * 0.285F, 0.0F);
            GL11.glRotatef(-20.0F, 0.0F, 0.0F, 1.0F);
            drawPixelText("Maver!", 0, -4.0F * pulse * ui, pulse * ui, 0xFFFFFF00, true);
        } finally {
            GL11.glPopMatrix();
            GlColor.restore();
        }
    }

    /** Five-by-seven block lettering with the pale stone face and dark extrusion of the MC title. */
    private void drawMinecraftWordmark() {
        final String[] glyphs = {
            // A proper M: the two outer stems stay vertical while the inner
            // diagonals meet at the top, so it can never be read as N.
            "10001/11011/10101/10101/10001/10001/10001", // M
            "01110/10001/10001/11111/10001/10001/10001", // A
            "10001/10001/10001/10001/10001/01010/00100", // V
            "11111/10000/10000/11110/10000/10000/11111", // E
            "11110/10001/10001/11110/10100/10010/10001"  // R
        };
        // Keep the brand mark present without letting it dominate the menu.
        float scale = Math.min(width * 0.44F / 29.0F, height * 0.16F / 7.0F);
        scale = Math.max(1.0F, scale);
        float depth = Math.max(2.0F, scale * 0.18F);
        float originX = width / 2.0F - 14.5F * scale;
        float originY = height * 0.13F;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glBegin(GL11.GL_QUADS);

            // Offset layers build a chunky black outline and the title's extruded edge.
            for (int layer = 4; layer >= 0; layer--) {
                int color = layer == 0 ? 0xFF101010 : 0xFF181818;
                float offset = depth + layer * Math.max(1.0F, scale * 0.06F);
                drawWordPixels(glyphs, originX + offset, originY + offset,
                        scale, color, false);
            }
            drawWordPixels(glyphs, originX, originY, scale, 0xFFE2E0DC, false);
            // A sparse darker pixel pattern gives the light face a stone-cut look.
            drawWordPixels(glyphs, originX, originY, scale, 0xFFAAA8A5, true);
            GL11.glEnd();
        } finally {
            GlColor.restore();
            GL11.glPopAttrib();
            GlColor.restore();
        }
    }

    private static void drawWordPixels(String[] glyphs, float x, float y,
            float scale, int color, boolean texture) {
        int cursor = 0;
        for (int glyph = 0; glyph < glyphs.length; glyph++) {
            String[] rows = glyphs[glyph].split("/");
            for (int row = 0; row < rows.length; row++) {
                for (int column = 0; column < rows[row].length(); column++) {
                    if (rows[row].charAt(column) != '1'
                            || (texture && (row + column + glyph) % 4 != 0)) {
                        continue;
                    }
                    GL11.glColor4f((color >> 16 & 0xFF) / 255.0F,
                            (color >> 8 & 0xFF) / 255.0F,
                            (color & 0xFF) / 255.0F,
                            (color >>> 24) / 255.0F);
                    float left = x + (cursor + column) * scale;
                    float top = y + row * scale;
                    GL11.glVertex2f(left, top);
                    GL11.glVertex2f(left, top + scale);
                    GL11.glVertex2f(left + scale, top + scale);
                    GL11.glVertex2f(left + scale, top);
                }
            }
            cursor += 6;
        }
    }

    private void drawButtons(int mouseX, int mouseY) {
        for (MenuButton button : buttons) {
            boolean hover = inside(mouseX, mouseY, button);
            int x = button.x, y = button.y, w = button.w, h = button.h;
            Gui.drawRect(x + 2, y + 3, x + w + 2, y + h + 3, 0x65000000);
            Gui.drawRect(x, y, x + w, y + h, 0xFF171717);
            Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1,
                    hover ? 0xFF218306 : 0xFFC6C6C6);
            Gui.drawRect(x + 2, y + 2, x + w - 2, y + 3,
                    hover ? 0xFF59BA39 : 0xFFF2F2F2);
            Gui.drawRect(x + 2, y + 3, x + 3, y + h - 3,
                    hover ? 0xFF59BA39 : 0xFFE8E8E8);
            Gui.drawRect(x + 2, y + h - 3, x + w - 2, y + h - 1,
                    hover ? 0xFF135000 : 0xFF656565);
            Gui.drawRect(x + w - 3, y + 3, x + w - 1, y + h - 3,
                    hover ? 0xFF135000 : 0xFF777777);
            float size = 1.05F * uiScale();
            size = Math.min(size, (w - 10.0F) / Math.max(1, fontRendererObj.getStringWidth(button.label)));
            drawPixelText(button.label, x + w / 2.0F, y + (h - 8.0F * size) / 2.0F,
                    size, hover ? 0xFFFFFFFF : 0xFF383838, false);
        }
    }

    private void drawFooter() {
        float size = uiScale();
        float y = height - 12.0F * size;
        drawPixelText("OPEN CLIENT  ·  Minecraft 1.8.9", 5.0F * size, y,
                size, 0xFFFFFFFF, false, false);
        String version = "Maver " + OpenMaver.VERSION;
        drawPixelText(version, width - (5.0F + fontRendererObj.getStringWidth(version)) * size,
                y, size, 0xFFFFFFFF, false, false);
    }

    private void drawPixelText(String text, float x, float y, float size, int color,
            boolean shadow) {
        drawPixelText(text, x, y, size, color, shadow, true);
    }

    private void drawPixelText(String text, float x, float y, float size, int color,
            boolean shadow, boolean centered) {
        GlColor.restore();
        // Gui.drawRect closes blending after drawing each button. Restore it here
        // so transparent glyph texels do not become solid black rectangles.
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0.0F);
            GL11.glScalef(size, size, 1.0F);
            int textX = centered ? -fontRendererObj.getStringWidth(text) / 2 : 0;
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

    private void drawPlayerPreview() {
        TextureManager textures = mc.getTextureManager();
        if (textures == null) {
            return;
        }
        BufferedImage image = skinImage;
        if (skinTexture == null && image != null) {
            ResourceLocation location = new ResourceLocation("openmaver", "skin/menu_player");
            if (textures.loadTexture(location, new DynamicTexture(image))) {
                skinTexture = location;
            }
        }
        // 没取到官方皮肤时展示 Steve，联网失败不影响菜单使用。
        ResourceLocation skin = skinTexture != null ? skinTexture
                : DefaultPlayerSkin.getDefaultSkin(new UUID(0L, 0L));
        float ui = uiScale();
        float centerX = width * 0.83F;
        float feetY = height * 0.68F;
        MenuSkinPreview.draw(textures, skin, centerX, feetY, 2.6F * ui);
        float nameSize = Math.min(ui, 96.0F * ui
                / Math.max(1, fontRendererObj.getStringWidth(username)));
        drawPixelText(username, centerX, feetY + 10.0F * ui, nameSize, 0xFFFFFFFF, true);
    }

    private void perform(int action) {
        OpenMaverFeatures.playUiClick(action == ACTION_QUIT ? 0.85F : 1.0F);
        try {
            if (action == ACTION_SINGLEPLAYER) {
                mc.displayGuiScreen(new GuiSelectWorld(this));
            } else if (action == ACTION_MULTIPLAYER) {
                mc.displayGuiScreen(new GuiMultiplayer(this));
            } else if (action == ACTION_CLIENT) {
                openClientGui(null);
            } else if (action == ACTION_WALLPAPER) {
                openClientGui("wallpaper");
            } else if (action == ACTION_OPTIONS) {
                mc.displayGuiScreen(new GuiOptions(this, mc.gameSettings));
            } else if (action == ACTION_QUIT) {
                mc.shutdown();
            }
        } catch (Throwable t) {
            System.err.println("[OpenMaver] Main menu action failed: " + action + " -> " + t);
        }
    }

    private void openClientGui(String initialPage) {
        try {
            if (!OpenMaver.isStarted()) {
                OpenMaver.start();
            }
        } catch (Throwable t) {
            System.err.println("[OpenMaver] Core startup failed: " + t);
        }
        mc.displayGuiScreen(new GuiOpenMaverClickGui(this, initialPage));
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
