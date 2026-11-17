import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenMaver 游戏内 HUD：右侧垂直堆叠的模块列表（ModuleList）。
 *
 * <p>视觉稿：
 * <ul>
 *   <li>每个启用功能是一行右对齐的白色文字，下面垫一块边缘羽化的深色矩形</li>
 *   <li>模块名用白色绘制，当前参数摘要（如 AutoClicker 的 CPS 区间）用灰色半透明紧跟在后面</li>
 *   <li>整个列表紧贴屏幕右侧，最右侧压一条带柔光的白色分隔线</li>
 *   <li>启用/关闭时从右侧滑入或淡出，宽度变化保持右边缘对齐</li>
 * </ul>
 *
 * <p>动画全部基于 {@link System#nanoTime()}，与帧率无关。列表行按注册顺序错峰出现，
 * 因此首次进入世界时是一段很轻的瀑布式滑入。
 *
 * <p>右下角仍保留加载成功的提示（由 {@link #success(String)} 触发），
 * 灰色透明毛玻璃底、白色文字，底部白色进度条随时间收缩，结束时滑出淡出。
 * 连续触发时提示会以卡片栈形式向上叠加，每张卡片独立计时并平滑补位。
 */
@SideOnly(Side.CLIENT)
public final class OpenMaverHud {

    // ------------------------------------------------------------------
    // 模块列表布局
    // ------------------------------------------------------------------

    private static final int MARGIN = 12;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 0;
    private static final int ROW_PAD_LEFT = 6;
    private static final int ROW_PAD_RIGHT = 5;
    /** 模块名与参数预览之间的间距。 */
    private static final int INFO_GAP = 6;
    /** 底板的羽化宽度：向内分层淡化，让矩形边缘像被模糊过一样。 */
    private static final int ROW_FEATHER = 3;
    /** 功能列表字号：接近参考图里紧凑但清晰的白字。 */
    private static final float ROW_TEXT_SIZE = 1.0F;
    private static final int ROW_TEXT_Y = 5;
    /** 半透明深色底，保证白字在亮背景上也清晰。 */
    private static final int ROW_BG = 0x000000;
    private static final int ROW_BG_ALPHA = 0x80;
    private static final int ROW_TEXT = 0xFFFFFF;
    private static final int ROW_SHADOW_ALPHA = 0x90;
    /** 参数预览：灰色半透明，紧跟在模块名后面。 */
    private static final int ROW_INFO = 0xB4B4B4;
    private static final int ROW_INFO_ALPHA = 0xA0;
    /** 列表最右侧的白色分隔线，带一点外发光。 */
    private static final int EDGE_LINE = 0xFFFFFF;

    // ------------------------------------------------------------------
    // 动效时长
    // ------------------------------------------------------------------

    /** 功能栏插入/移除/换位的平滑时长。 */
    private static final float LIST_FADE_MS = 180.0F;

    private static final long TOAST_IN_MS = 320L;
    private static final long TOAST_HOLD_MS = 2400L;
    private static final long TOAST_OUT_MS = 300L;
    private static final long TOAST_TOTAL_MS = TOAST_IN_MS + TOAST_HOLD_MS + TOAST_OUT_MS;
    private static final int TOAST_H = 40;
    private static final int TOAST_RADIUS = 5;
    private static final int TOAST_PAD_X = 12;
    private static final int TOAST_TEXT_Y = 9;
    /** 左侧状态图标：勾 / 叉，仅用于模块开关提示。 */
    private static final int TOAST_ICON_SIZE = 10;
    private static final int TOAST_ICON_GAP = 8;
    private static final int TOAST_ICON_NONE = 0;
    private static final int TOAST_ICON_CHECK = 1;
    private static final int TOAST_ICON_CROSS = 2;
    private static final int TOAST_BAR_H = 3;
    private static final int TOAST_BAR_INSET = 10;
    private static final int TOAST_BAR_BOTTOM = 7;
    /** 同时堆叠显示的最大卡片数；超过后优先淘汰最旧的一张。 */
    private static final int TOAST_MAX = 6;
    private static final int TOAST_GAP = 7;

    // ------------------------------------------------------------------
    // 配色：只保留中性灰与白色，不再按分类着色
    // ------------------------------------------------------------------

    /** 右下角提示：灰色透明毛玻璃 + 白色倒计时进度条。 */
    private static final int TOAST_TINT = 0x8E8E8E;
    private static final float TOAST_TINT_STRENGTH = 0.46F;
    private static final int TOAST_TEXT = 0xFFFFFF;

    /** 右上角功能栏的独立状态：离开的功能会先淡出，再从列表移除。 */
    private static final Map<String, HudEntry> LIST_ENTRIES = new HashMap<String, HudEntry>();
    private static final Map<String, RowAnim> ROW_ANIMS = new HashMap<String, RowAnim>();

    /** 右下角提示栈：新提示插入队尾，多张卡片从下往上依次堆叠。 */
    private static final List<Toast> TOASTS = new ArrayList<Toast>();

    /** 出错后停用，避免每帧抛异常。 */
    private static boolean failed = false;

    public OpenMaverHud() {
        // 由 Forge 事件总线实例化/注册
    }

    /** 触发一次右下角成功提示。新提示会与尚未消失的提示叠成卡片栈。 */
    public static void success(String text) {
        pushToast(text, TOAST_ICON_NONE);
    }

    /** 功能开关时弹出「xxx enabled / disabled」提示，并在左侧显示勾 / 叉。 */
    public static void notifyToggle(String name, boolean enabled) {
        pushToast(name + (enabled ? " enabled" : " disabled"),
                enabled ? TOAST_ICON_CHECK : TOAST_ICON_CROSS);
        // 开启 / 关闭用经验球拾取音（random.orb）：开启走清脆的高音区、
        // 关闭走发闷的低音区，闭眼也能听出状态。
        OpenMaverFeatures.playXpPickup(enabled);
    }

    private static void pushToast(String text, int icon) {
        if (text == null || text.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        TOASTS.add(new Toast(text, now, icon));
        while (TOASTS.size() > TOAST_MAX) {
            TOASTS.remove(0);
        }
    }

    /** 当前启用的功能数量（模块 + 内置 Aim / ESP），用于加载完成提示。 */
    public static int enabledFeatureCount() {
        int count = 0;
        List<Module> modules = ModuleManager.getModules();
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i).isEnabled()) {
                count++;
            }
        }
        if (OpenMaverFeatures.isAimEnabled()) {
            count++;
        }
        if (OpenMaverFeatures.isEspEnabled()) {
            count++;
        }
        return count;
    }

    /** 是否正在显示提示。 */
    public static boolean isShowing() {
        return !TOASTS.isEmpty();
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (failed || event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft == null) {
                return;
            }
            // ClickGUI 打开时 HUD 仍然绘制：它会被 ClickGUI 的整屏毛玻璃一起模糊，
            // 作为背景的一部分保留下来。提示卡例外，由 ClickGUI 在面板上方清晰重绘。
            boolean clickGuiOpen = minecraft.currentScreen instanceof GuiOpenMaverClickGui;
            FontRenderer font = (FontRenderer) readField(minecraft,
                    "fontRendererObj", "field_71466_p");
            if (font == null) {
                return;
            }
            long now = System.nanoTime();
            List<Module> modules = ModuleManager.getModules();
            List<HudEntry> entries = new ArrayList<HudEntry>(modules.size() + 2);
            for (int i = 0; i < modules.size(); i++) {
                Module module = modules.get(i);
                if (!module.isEnabled()) {
                    continue;
                }
                entries.add(new HudEntry(module.getConfigKey() + "#" + module.getName(),
                        module.getName(), moduleInfo(module)));
            }
            if (OpenMaverFeatures.isAimEnabled()) {
                entries.add(new HudEntry("feature#aim", "Aim Assist",
                        aimInfo()));
            }
            if (OpenMaverFeatures.isEspEnabled()) {
                entries.add(new HudEntry("feature#esp", "Player ESP",
                        espInfo()));
            }

            // 先抓一张世界快照：功能栏卡片、提示卡都从这份快照里取背景，
            // 避免把随后绘制的 HUD 自己也糊进去。
            ScaledResolution resolution = event.resolution;
            GuiBlur.beginFrame(resolution.getScaledWidth(),
                    resolution.getScaledHeight(), resolution.getScaleFactor());
            if (!entries.isEmpty() || !TOASTS.isEmpty() || !LIST_ENTRIES.isEmpty()
                    || TargetHud.isEnabled() || ScaffoldHud.isEnabled()) {
                GuiBlur.capture();
            }
            StatsHud.render(font, event.resolution, now);
            try {
                TargetHud.render(font, event.resolution, now);
            } catch (Throwable targetError) {
                System.err.println("[OpenMaver] Target HUD 绘制出错: " + targetError);
            }
            try {
                ScaffoldHud.render(font, event.resolution, now);
            } catch (Throwable scaffoldError) {
                System.err.println("[OpenMaver] Scaffold HUD 绘制出错: " + scaffoldError);
            }
            int listBottom = renderModuleList(font, event.resolution, entries, now);
            if (!clickGuiOpen) {
                renderToasts(font, event.resolution.getScaledWidth(),
                        event.resolution.getScaledHeight(), now, listBottom);
            }
        } catch (Throwable t) {
            failed = true;
            TOASTS.clear();
            System.out.println("[OpenMaver] HUD 绘制出错，已停用: " + t);
        }
    }

    // ==================================================================
    // 模块列表
    // ==================================================================

    private static int renderModuleList(FontRenderer font, ScaledResolution resolution,
            List<HudEntry> enabledEntries, long now) {
        // 当前启用的功能集合。被禁用的旧行不立刻删除，而是先平滑退出。
        Set<String> currentIds = new HashSet<String>();
        for (int i = 0; i < enabledEntries.size(); i++) {
            HudEntry entry = enabledEntries.get(i);
            currentIds.add(entry.id);
            LIST_ENTRIES.put(entry.id, entry);
            RowAnim anim = rowAnim(entry, now);
            anim.order = i;
            anim.targetVisible = true;
        }

        List<String> removeIds = new ArrayList<String>();
        for (Map.Entry<String, HudEntry> stored : LIST_ENTRIES.entrySet()) {
            if (currentIds.contains(stored.getKey())) {
                continue;
            }
            RowAnim anim = rowAnim(stored.getValue(), now);
            anim.targetVisible = false;
            if (anim.visible <= 0.01F) {
                removeIds.add(stored.getKey());
            }
        }
        for (int i = 0; i < removeIds.size(); i++) {
            String id = removeIds.get(i);
            LIST_ENTRIES.remove(id);
            ROW_ANIMS.remove(id);
        }

        int screenW = resolution.getScaledWidth();
        int screenH = resolution.getScaledHeight();
        // 功能栏紧贴右边缘，最右侧再压一条白色分隔线。
        int right = screenW;
        int startY = MARGIN;

        if (LIST_ENTRIES.isEmpty()) {
            return startY;
        }

        List<HudEntry> display = new ArrayList<HudEntry>(LIST_ENTRIES.values());
        Collections.sort(display, new Comparator<HudEntry>() {
            @Override
            public int compare(HudEntry a, HudEntry b) {
                RowAnim left = ROW_ANIMS.get(a.id);
                RowAnim right = ROW_ANIMS.get(b.id);
                int leftOrder = left == null ? Integer.MAX_VALUE : left.order;
                int rightOrder = right == null ? Integer.MAX_VALUE : right.order;
                int result = leftOrder < rightOrder ? -1 : (leftOrder > rightOrder ? 1 : 0);
                return result != 0 ? result : a.id.compareTo(b.id);
            }
        });

        final int count = display.size();
        final String[] names = new String[count];
        final String[] infos = new String[count];
        for (int i = 0; i < count; i++) {
            HudEntry entry = display.get(i);
            names[i] = entry.name;
            infos[i] = entry.info;
        }

        int totalHeight = count * ROW_H + (count - 1) * ROW_GAP;
        if (startY + totalHeight > screenH - MARGIN) {
            startY = Math.max(MARGIN, screenH - MARGIN - totalHeight);
        }

        List<TextDraw> texts = new ArrayList<TextDraw>(count * 2);

        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH);

        float listAlpha = 0.0F;
        for (int i = 0; i < count; i++) {
            HudEntry entry = display.get(i);
            RowAnim anim = rowAnim(entry, now);
            long deltaNanos = Math.max(0L, now - anim.lastNanos);
            anim.lastNanos = now;
            float deltaMs = Math.min(50.0F, deltaNanos / 1_000_000.0F);

            // 插入/退出：每行独立淡入淡出，互不依赖其他文字或行。
            anim.visible = approach(anim.visible,
                    anim.targetVisible ? 1.0F : 0.0F, deltaMs / LIST_FADE_MS);

            int targetY = startY + i * (ROW_H + ROW_GAP);
            if (anim.y < 0.0F) {
                anim.y = targetY;
            } else {
                anim.y += (targetY - anim.y)
                        * Math.min(1.0F, deltaMs / (LIST_FADE_MS * 0.85F));
            }

            float alpha = clamp01(anim.visible);
            listAlpha = Math.max(listAlpha, alpha);
            int slide = (int) ((1.0F - easeOutCubic(alpha)) * 22.0F);
            int y = Math.round(anim.y);
            int rowRight = right + slide;

            // 右边缘固定对齐，每行宽度由自身文字决定，形成参考图里的阶梯感。
            int nameW = HudFont.getStringWidth(font, names[i], ROW_TEXT_SIZE);
            int infoW = hasInfo(infos[i])
                    ? HudFont.getStringWidth(font, infos[i], ROW_TEXT_SIZE) : 0;
            int rowWidth = nameW + (infoW > 0 ? INFO_GAP + infoW : 0)
                    + ROW_PAD_LEFT + ROW_PAD_RIGHT;
            int rowX = rowRight - rowWidth;

            // 半透明深色底：外圈逐层向内加深，边缘自然羽化。
            softRect(rowX, y, rowWidth, ROW_H,
                    withAlpha(ROW_BG, clamp255((int) (alpha * ROW_BG_ALPHA))),
                    ROW_FEATHER, i == 0, i == count - 1);

            int textY = y + ROW_TEXT_Y;
            int nameX = rowX + ROW_PAD_LEFT;
            texts.add(new TextDraw(names[i], nameX, textY,
                    withAlpha(ROW_TEXT, clamp255((int) (alpha * 255.0F))),
                    ROW_TEXT_SIZE,
                    withAlpha(0x000000,
                            clamp255((int) (alpha * ROW_SHADOW_ALPHA)))));
            if (infoW > 0) {
                texts.add(new TextDraw(infos[i], nameX + nameW + INFO_GAP, textY,
                        withAlpha(ROW_INFO,
                                clamp255((int) (alpha * ROW_INFO_ALPHA))),
                        ROW_TEXT_SIZE,
                        withAlpha(0x000000,
                                clamp255((int) (alpha * 0x60)))));
            }
        }

        if (listAlpha > 0.01F) {
            int lineTop = startY;
            int lineHeight = Math.max(1, totalHeight);
            int alpha = clamp255((int) (listAlpha * 255.0F));
            // 外侧两层很淡，核心 1px 白线紧贴屏幕右边缘。
            roundRect(screenW - 3, lineTop, 3, lineHeight, 0,
                    withAlpha(EDGE_LINE, Math.round(alpha * 0.18F)));
            roundRect(screenW - 2, lineTop, 2, lineHeight, 0,
                    withAlpha(EDGE_LINE, Math.round(alpha * 0.55F)));
            roundRect(screenW - 1, lineTop, 1, lineHeight, 0,
                    withAlpha(EDGE_LINE, alpha));
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopAttrib();
        GlColor.restore();

        drawTexts(font, texts);
        return startY + totalHeight;
    }

    private static float approach(float value, float target, float amount) {
        float clamped = clamp01(amount);
        return value + (target - value) * clamped;
    }

    private static boolean hasInfo(String info) {
        return info != null && !info.isEmpty();
    }

    private static RowAnim rowAnim(HudEntry entry, long now) {
        RowAnim anim = ROW_ANIMS.get(entry.id);
        if (anim == null) {
            anim = new RowAnim(now);
            ROW_ANIMS.put(entry.id, anim);
        }
        return anim;
    }

    // ------------------------------------------------------------------
    // 功能参数摘要（替代原来的键位提示）
    // ------------------------------------------------------------------

    /**
     * 功能当前的参数摘要，例如 AutoClicker 的 {@code 6-10}。
     * 没有可展示数值的功能返回空字符串，HUD 只显示模块名。
     */
    private static String moduleInfo(Module module) {
        String name = module.getName();
        if ("AutoClicker".equals(name)) {
            int min = Math.min(OpenMaverFeatures.getAutoClickerCpsMin(),
                    OpenMaverFeatures.getAutoClickerCpsMax());
            int max = Math.max(OpenMaverFeatures.getAutoClickerCpsMin(),
                    OpenMaverFeatures.getAutoClickerCpsMax());
            return min == max ? Integer.toString(min) : min + "-" + max;
        }
        if ("Reach".equals(name)) {
            return number(OpenMaverFeatures.getEffectiveReachDistance()) + "m";
        }
        if ("Criticals".equals(name)) {
            return OpenMaverFeatures.getCriticalsModeName();
        }
        if ("FastPlace".equals(name)) {
            return OpenMaverFeatures.getFastPlaceDelayTicks() + "t";
        }
        if ("Scaffold".equals(name)) {
            int blocks = ScaffoldFeature.getBlockCount();
            return Math.max(0, blocks) + "b";
        }
        if ("Velocity".equals(name)) {
            // 憋包中优先显示缓存的位置包数量，一眼能看出 blink 是否真的在起作用。
            if (VelocityFeature.isBlinking()) {
                return VelocityFeature.getHeldCount() + "p";
            }
            // SPRINT SLAP：窗口没打完时显示还剩几个 tick（每个 tick 一次 ×0.6）。
            return VelocityFeature.getSummary();
        }
        if ("KillAura".equals(name)) {
            int min = Math.min(OpenMaverFeatures.getKillAuraCpsMin(),
                    OpenMaverFeatures.getKillAuraCpsMax());
            int max = Math.max(OpenMaverFeatures.getKillAuraCpsMin(),
                    OpenMaverFeatures.getKillAuraCpsMax());
            return number(OpenMaverFeatures.getEffectiveKillAuraRange()) + "m "
                    + (min == max ? Integer.toString(min) : min + "-" + max);
        }
        if ("FakePlayer".equals(name)) {
            return OpenMaverFeatures.getFakePlayerCount() + "x "
                    + number(OpenMaverFeatures.getFakePlayerDistance()) + "m";
        }
        if ("Target HUD".equals(name)) {
            return Math.round(OpenMaverFeatures.getTargetHudRange()) + "m";
        }
        if ("FullBright".equals(name)) {
            return "NV";
        }
        if ("Player X-Ray".equals(name)) {
            return Math.round(OpenMaverFeatures.getPlayerXrayRange()) + "m";
        }
        if ("Motion Blur".equals(name)) {
            return Math.round(OpenMaverFeatures.getMotionBlurStrength() * 100.0F) + "%";
        }
        if ("Chest ESP".equals(name)) {
            return Math.round(ChestFeature.getEspRange()) + "m";
        }
        if ("Chest Stealer".equals(name)) {
            return ChestFeature.getTakeFilterName();
        }
        if ("Grim Bypass".equals(name)) {
            return OpenMaverFeatures.getBypassProfileName();
        }
        return "";
    }

    private static String aimInfo() {
        return number(OpenMaverFeatures.getAimRange()) + "m";
    }

    private static String espInfo() {
        return Math.round(OpenMaverFeatures.getEspRange()) + "m";
    }

    /** 最多保留两位小数，并去掉无意义的尾随 0（3.20 -> 3.2，3.00 -> 3）。 */
    private static String number(float value) {
        int hundredths = Math.round(value * 100.0F);
        int whole = hundredths / 100;
        int fraction = Math.abs(hundredths % 100);
        if (fraction == 0) {
            return Integer.toString(whole);
        }
        if (fraction % 10 == 0) {
            return whole + "." + (fraction / 10);
        }
        return whole + "." + (fraction < 10 ? "0" + fraction : Integer.toString(fraction));
    }

    // ==================================================================
    // 右下角提示（透明毛玻璃 + 白色进度条）
    // ==================================================================

    /** 供 GUI 等没有 RenderGameOverlayEvent 的场景复用同一套提示绘制。 */
    public static void renderToastOverlay(FontRenderer font, int screenW, int screenH) {
        if (failed || font == null || TOASTS.isEmpty()) {
            return;
        }
        try {
            renderToasts(font, screenW, screenH, System.nanoTime(), 0);
        } catch (Throwable t) {
            TOASTS.clear();
        }
    }

    /**
     * 绘制提示卡片栈：最新的一张贴底，旧卡片依次向上推。
     * 每张卡片拥有独立的时间线，因此可以同时播放滑入、停留和滑出。
     */
    private static void renderToasts(FontRenderer font, int screenW, int screenH,
            long now, int listBottom) {
        // 先清理已经彻底退场的卡片；下方卡片会在同一帧平滑补位。
        for (int i = TOASTS.size() - 1; i >= 0; i--) {
            if (TOASTS.get(i).elapsedMs(now) >= TOAST_TOTAL_MS) {
                TOASTS.remove(i);
            }
        }
        if (TOASTS.isEmpty()) {
            return;
        }

        int bottom = screenH - MARGIN;
        int minY = listBottom > 0 ? listBottom + TOAST_GAP : MARGIN;
        if (bottom - TOAST_H < minY) {
            return;
        }

        // 计算每张卡片的目标位置，并把当前 y 平滑地追向目标，避免删除时跳动。
        int nextBottom = bottom;
        for (int i = TOASTS.size() - 1; i >= 0; i--) {
            Toast toast = TOASTS.get(i);
            int targetY = nextBottom - TOAST_H;
            toast.inStack = targetY >= minY;
            if (Float.isNaN(toast.y)) {
                toast.y = targetY;
            } else {
                float deltaMs = Math.min(50.0F,
                        Math.max(0.0F, (now - toast.lastNanos) / 1_000_000.0F));
                toast.y += (targetY - toast.y) * Math.min(1.0F, deltaMs / 140.0F);
            }
            toast.lastNanos = now;
            nextBottom = targetY - TOAST_GAP;
        }

        List<TextDraw> texts = new ArrayList<TextDraw>(TOASTS.size());

        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH);

        for (int i = 0; i < TOASTS.size(); i++) {
            Toast toast = TOASTS.get(i);
            if (!toast.inStack) {
                continue;
            }
            long elapsedMs = toast.elapsedMs(now);

            float progress;
            float alpha;
            if (elapsedMs < TOAST_IN_MS) {
                float t = elapsedMs / (float) TOAST_IN_MS;
                progress = easeOutCubic(t);
                alpha = t;
            } else if (elapsedMs < TOAST_IN_MS + TOAST_HOLD_MS) {
                progress = 1.0f;
                alpha = 1.0f;
            } else {
                // 停留结束后只滑出并淡出，不再变色。
                float t = clamp01((elapsedMs - TOAST_IN_MS - TOAST_HOLD_MS)
                        / (float) TOAST_OUT_MS);
                progress = 1.0f - easeInCubic(t);
                alpha = 1.0f - easeInCubic(t);
            }

            int iconW = toast.icon == TOAST_ICON_NONE ? 0 : TOAST_ICON_SIZE + TOAST_ICON_GAP;
            int labelW = HudFont.getStringWidth(font, toast.text);
            int width = Math.max(180, TOAST_PAD_X * 2 + iconW + labelW);
            width = Math.min(width, screenW - MARGIN * 2);
            int targetX = screenW - MARGIN - width;
            int x = Math.round(screenW + (targetX - screenW) * progress);
            int y = Math.round(toast.y);

            int a = clamp255((int) (alpha * 255.0f));
            int barX = x + TOAST_BAR_INSET;
            int barW = width - TOAST_BAR_INSET * 2;
            int barY = y + TOAST_H - TOAST_BAR_BOTTOM - TOAST_BAR_H;
            float remaining = 1.0f - clamp01(elapsedMs / (float) TOAST_TOTAL_MS);

            // 柔和投影 + 1px 白色描边，卡片内部是「灰色透明毛玻璃」。
            roundRect(x + 2, y + 4, width, TOAST_H, TOAST_RADIUS,
                    withAlpha(0x000000, clamp255((int) (alpha * 0x36))));
            roundRect(x, y, width, TOAST_H, TOAST_RADIUS + 1,
                    withAlpha(0xFFFFFF, clamp255((int) (alpha * 0x3A))));
            GuiBlur.draw(x + 1, y + 1, width - 2, TOAST_H - 2,
                    Math.max(0.0F, TOAST_RADIUS - 1.0F), TOAST_TINT,
                    TOAST_TINT_STRENGTH, alpha);

            // 白色倒计时进度条（底部）
            int fillW = Math.round(barW * clamp01(remaining));
            if (fillW > 0) {
                roundRect(barX, barY, fillW, TOAST_BAR_H, TOAST_BAR_H / 2,
                        withAlpha(0xFFFFFF, a));
            }

            // 开关提示左侧的勾 / 叉；普通提示没有图标，文字从左边距开始。
            int textX = x + TOAST_PAD_X;
            if (toast.icon != TOAST_ICON_NONE) {
                int iconX = x + TOAST_PAD_X;
                int iconY = y + TOAST_TEXT_Y;
                drawToastIcon(toast.icon, iconX, iconY, TOAST_ICON_SIZE,
                        withAlpha(TOAST_TEXT, a));
                textX = iconX + TOAST_ICON_SIZE + TOAST_ICON_GAP;
            }

            texts.add(new TextDraw(toast.text, textX, y + TOAST_TEXT_Y,
                    withAlpha(TOAST_TEXT, clamp255((int) (alpha * 255.0f)))));
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopAttrib();
        GlColor.restore();

        drawTexts(font, texts);
    }

    // ==================================================================
    // GL 绘制辅助
    // ==================================================================

    private static void drawTexts(FontRenderer font, List<TextDraw> texts) {
        for (int i = 0; i < texts.size(); i++) {
            TextDraw text = texts.get(i);
            if ((text.shadowColor >>> 24) != 0) {
                HudFont.drawString(font, text.text, text.x + 1, text.y + 1,
                        text.shadowColor, text.size);
            }
            HudFont.drawString(font, text.text, text.x, text.y, text.color,
                    text.size);
        }
    }

    private static void drawToastIcon(int icon, int x, int y, int size, int argb) {
        float thin = 2.0F;
        if (icon == TOAST_ICON_CHECK) {
            drawSegment(x + 1.2F, y + size * 0.52F,
                    x + size * 0.40F, y + size * 0.84F, thin, argb);
            drawSegment(x + size * 0.40F, y + size * 0.84F,
                    x + size * 0.92F, y + size * 0.18F, thin, argb);
        } else if (icon == TOAST_ICON_CROSS) {
            drawSegment(x + size * 0.20F, y + size * 0.20F,
                    x + size * 0.80F, y + size * 0.80F, thin, argb);
            drawSegment(x + size * 0.80F, y + size * 0.20F,
                    x + size * 0.20F, y + size * 0.80F, thin, argb);
        }
    }

    /** 绘制一条有宽度的线段，用于提示卡左侧的勾 / 叉。 */
    private static void drawSegment(float x1, float y1, float x2, float y2,
            float thickness, int argb) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.0001F) {
            return;
        }
        float nx = -dy / length * thickness * 0.5F;
        float ny = dx / length * thickness * 0.5F;
        setColor(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x1 + nx, y1 + ny);
        GL11.glVertex2f(x2 + nx, y2 + ny);
        GL11.glVertex2f(x2 - nx, y2 - ny);
        GL11.glVertex2f(x1 - nx, y1 - ny);
        GL11.glEnd();
    }

    /**
     * 左右两侧始终羽化；只有列表最上面 / 最下面一行才羽化上下外缘。
     *
     * <p>这样行与行之间的公共边保持满高，功能栏会真正连成一块，
     * 同时整个列表的最外圈仍然有柔和的模糊边缘。
     */
    private static void softRect(int x, int y, int w, int h, int argb,
            int feather, boolean featherTop, boolean featherBottom) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
            return;
        }
        int rgb = argb & 0x00FFFFFF;
        int alpha = argb >>> 24;
        int layers = Math.max(0, Math.min(feather, Math.min(w / 2, h / 2)));
        for (int i = 0; i < layers; i++) {
            float t = (i + 1) / (float) layers;
            int layerAlpha = Math.round(alpha * (0.05F + 0.12F * t));
            int top = featherTop ? i : 0;
            int bottom = featherBottom ? i : 0;
            roundRect(x + i, y + top, w - i * 2, h - top - bottom, 0,
                    withAlpha(rgb, layerAlpha));
        }
        int top = featherTop ? layers : 0;
        int bottom = featherBottom ? layers : 0;
        roundRect(x + layers, y + top, w - layers * 2, h - top - bottom, 0, argb);
    }

    private static void roundRect(int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
            return;
        }
        float r = Math.min(radius, Math.min(w / 2.0F, h / 2.0F));
        setColor(argb);
        GL11.glBegin(GL11.GL_POLYGON);
        for (int corner = 0; corner < 4; corner++) {
            float centerX = x + ((corner == 0 || corner == 3) ? r : w - r);
            float centerY = y + ((corner < 2) ? r : h - r);
            float startAngle = 180.0F + corner * 90.0F;
            for (int segment = 0; segment <= 6; segment++) {
                double angle = Math.toRadians(startAngle + segment * 15.0F);
                GL11.glVertex2f(centerX + (float) Math.cos(angle) * r,
                        centerY + (float) Math.sin(angle) * r);
            }
        }
        GL11.glEnd();
    }

    private static void setColor(int argb) {
        GlColor.set(argb);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (clamp255(alpha) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    // ==================================================================
    // 动画缓动
    // ==================================================================

    private static float clamp01(float value) {
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }

    private static float easeOutCubic(float t) {
        float u = 1.0f - t;
        return 1.0f - u * u * u;
    }

    private static float easeInCubic(float t) {
        return t * t * t;
    }

    private static long nanos(long millis) {
        return millis * 1_000_000L;
    }

    private static Object readField(Object target, String... names) {
        Class<?> type = target.getClass();
        for (String name : names) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Try the type based fallback below.
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.getType().getName().equals("net.minecraft.client.gui.FontRenderer")) {
                field.setAccessible(true);
                try {
                    return field.get(target);
                } catch (IllegalAccessException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 一行中的一个文本绘制请求，等所有底板画完后再统一交给字体渲染器。 */
    private static final class TextDraw {
        final String text;
        final int x;
        final int y;
        final int color;
        final float size;
        /** 黑色投影颜色；alpha 为 0 时不绘制投影。 */
        final int shadowColor;

        TextDraw(String text, int x, int y, int color) {
            this(text, x, y, color, 1.0F);
        }

        TextDraw(String text, int x, int y, int color, float size) {
            this(text, x, y, color, size, 0);
        }

        TextDraw(String text, int x, int y, int color, float size, int shadowColor) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.color = color;
            this.size = size;
            this.shadowColor = shadowColor;
        }
    }

    private static final class HudEntry {
        final String id;
        final String name;
        final String info;

        HudEntry(String id, String name, String info) {
            this.id = id;
            this.name = name;
            this.info = info;
        }
    }

    /** 一张右下角提示卡片的独立时间线与堆叠位置。 */
    private static final class Toast {
        final String text;
        final int icon;
        final long startNanos;
        long lastNanos;
        float y = Float.NaN;
        boolean inStack = true;

        Toast(String text, long startNanos, int icon) {
            this.text = text;
            this.icon = icon;
            this.startNanos = startNanos;
            this.lastNanos = startNanos;
        }

        long elapsedMs(long now) {
            return (now - startNanos) / 1_000_000L;
        }
    }

    /** 单个功能行的独立动画状态（显隐、垂直换位）。 */
    private static final class RowAnim {
        float visible;
        float y = -1.0F;
        boolean targetVisible;
        int order;
        long lastNanos;

        RowAnim(long now) {
            this.lastNanos = now;
        }
    }
}
