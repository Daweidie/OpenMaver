import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 客户端事件桥接：把 Minecraft 1.8.9 的 tick / 渲染事件转发给 {@link ModuleManager}，
 * 并负责「进入世界后启动核心 + 右下角加载成功提示」、ClickGUI 开关以及模块绑定键位。
 *
 * <p><b>健壮性</b>：所有逻辑都包在 try/catch 里。运行期注入时若某个成员名/签名不匹配
 * （NoSuchFieldError/NoSuchMethodError），只记录一次并停用，**绝不让异常冒泡导致游戏崩溃**。
 */
@SideOnly(Side.CLIENT)
public final class OpenMaverClientHandler {

    /** tick 轮询用的 RSHIFT 边沿状态（键盘事件之外的第二条路径）。 */
    private boolean rightShiftDown = false;
    private long lastToggleNanos = 0L;

    /**
     * 已经按下、尚未收到释放事件的按键。
     *
     * <p>Forge 的 {@code InputEvent.KeyInputEvent} 在按住按键时会随系统键盘重复
     * 逐次触发。之前每一条重复事件都会执行一次模块 toggle，按住 R 时会看到
     * AutoClicker 被反复开关。这里只在一次物理按下时处理，释放后才允许下一次。
     */
    private final Set<Integer> heldKeys = new HashSet<Integer>();

    /** 是否已经处理过「首次进入世界」。 */
    private boolean worldEntered = false;

    /** 出错后停用，避免每帧刷屏 / 反复抛异常。 */
    private boolean failed = false;
    private boolean keyFailed = false;
    private boolean renderTickFailed = false;
    private boolean motionBlurFailed = false;
    private boolean tickSeen = false;
    private boolean renderSeen = false;

    private static void debug(String message, Throwable error) {
        StringBuilder line = new StringBuilder("[OpenMaver] event: ").append(message);
        if (error != null) {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            line.append(System.lineSeparator()).append(stack);
        }
        String text = line.toString();
        System.err.println(text);
        try (FileOutputStream out = new FileOutputStream("/tmp/openmaver-events.log", true)) {
            out.write((text + System.lineSeparator()).getBytes("UTF-8"));
        } catch (Throwable ignored) {
            // Diagnostics must not interfere with the game loop.
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (failed) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            ensureCustomMainMenu(mc);
            // 首次 tick：把 jar 内自带的 2pimp v2 [16x] 材质包装进 resourcepacks/。
            BundledResourcePack.install(mc);
            // START：在 vanilla 发送 C03 之前应用 aim / sprint。
            if (event.phase == TickEvent.Phase.START) {
                syncHeldKeys();
                OpenMaverFeatures.onTickStart(mc);
                return;
            }
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            if (!tickSeen) {
                tickSeen = true;
                debug("first client tick", null);
            }
            OpenMaverFeatures.onTick(mc);
            if (readField(mc, "theWorld", "field_71441_e") == null
                    || readField(mc, "thePlayer", "field_71439_g") == null) {
                return;
            }
            if (!worldEntered) {
                worldEntered = true;
                debug("world/player available; starting core", null);
                onEnterWorld();
            }
            pollClickGui();
            ModuleManager.onTick();
        } catch (Throwable t) {
            failed = true;
            debug("client tick bridge disabled: " + t, t);
        }
    }

    private void onEnterWorld() {
        if (!OpenMaver.isStarted()) {
            OpenMaver.start();
        }
        int enabled = OpenMaverHud.enabledFeatureCount();
        OpenMaverHud.success("Loaded successfully, " + enabled
                + (enabled == 1 ? " feature enabled!" : " features enabled!"));
    }

    /**
     * Replaces vanilla's title screen with the Maver menu.
     *
     * <p>Forge fires {@link GuiOpenEvent} for the initial title screen, but a runtime-injected
     * client can attach after that event has already passed. The tick-side check below catches
     * both cases; it only ever replaces the exact vanilla {@code GuiMainMenu}, so sub-screens
     * and other mods' menus are left alone.
     */
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (event == null || event.gui == null) {
            return;
        }
        if (isVanillaMainMenu(event.gui)) {
            event.gui = new GuiMaverMainMenu();
        }
    }

    private void ensureCustomMainMenu(Minecraft mc) {
        GuiScreen current = (GuiScreen) readField(mc, "currentScreen", "field_71462_r");
        if (current != null && isVanillaMainMenu(current)) {
            mc.displayGuiScreen(new GuiMaverMainMenu());
        }
    }

    private static boolean isVanillaMainMenu(GuiScreen screen) {
        return "net.minecraft.client.gui.GuiMainMenu".equals(screen.getClass().getName());
    }

    /**
     * 键盘事件路径：RSHIFT 开关 ClickGUI，其余按键匹配模块绑定键位。
     *
     * <p>Forge 的 {@link InputEvent.KeyInputEvent} 能拿到每一次按下/释放，不会像 tick
     * 轮询那样漏掉快速点按。tick 里仍保留一条轮询兜底路径，两条路径共享
     * {@link #lastToggleNanos} 去抖，避免同一按键触发两次。
     *
     * <p>允许从暂停菜单（{@code GuiIngameMenu}）直接打开：窗口失去焦点后 Minecraft
     * 会自动暂停并弹出该菜单，此时不应要求玩家先点「回到游戏」。
     */
    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (failed || keyFailed) {
            return;
        }
        try {
            int key = Keyboard.getEventKey();
            if (!Keyboard.getEventKeyState()) {
                heldKeys.remove(Integer.valueOf(key));
                return;
            }
            // 同一次物理按下只处理一次：忽略系统键盘重复事件，避免按住 R
            // 时 AutoClicker 被反复开关。
            if (!heldKeys.add(Integer.valueOf(key))) {
                return;
            }
            if (key == Keyboard.KEY_RSHIFT) {
                toggleClickGui("event");
                return;
            }
            toggleModuleKey(key);
        } catch (Throwable t) {
            keyFailed = true;
            debug("key bridge disabled: " + t, t);
        }
    }

    /** 清理丢失释放事件的按键，防止窗口失焦后某个键永远无法再次触发。 */
    private void syncHeldKeys() {
        try {
            Iterator<Integer> iterator = heldKeys.iterator();
            while (iterator.hasNext()) {
                Integer key = iterator.next();
                if (key == null || !Keyboard.isKeyDown(key.intValue())) {
                    iterator.remove();
                }
            }
        } catch (Throwable ignored) {
            // A broken LWJGL state must not break the tick bridge.
        }
    }

    /**
     * 处理模块绑定键位（HUD 右侧显示的那个键）。
     *
     * <p>仅在没有任何 GUI / 聊天栏打开时生效，避免玩家打字时误触模块。
     */
    private void toggleModuleKey(int keyCode) {
        if (keyCode <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || readField(mc, "currentScreen", "field_71462_r") != null) {
            return;
        }
        String keyName = Keyboard.getKeyName(keyCode);
        if (keyName == null || keyName.isEmpty()) {
            return;
        }
        List<Module> modules = ModuleManager.getModules();
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            String bound = module.getKeybind();
            if (bound != null && bound.equalsIgnoreCase(keyName)) {
                boolean enable = !module.isEnabled();
                module.setEnabled(enable);
                OpenMaverHud.notifyToggle(module.getName(), enable);
                debug("module " + module.getName() + " toggled by key " + keyName, null);
            }
        }
    }

    /** tick 轮询兜底：即使 KeyInputEvent 因环境原因没送达，也能开关。 */
    private void pollClickGui() {
        boolean down = Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        if (down && !rightShiftDown) {
            toggleClickGui("poll");
        }
        rightShiftDown = down;
    }

    private void toggleClickGui(String source) {
        long now = System.nanoTime();
        if (now - lastToggleNanos < 200_000_000L) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || readField(mc, "theWorld", "field_71441_e") == null) {
            return;
        }
        lastToggleNanos = now;
        GuiScreen current = (GuiScreen) readField(mc, "currentScreen", "field_71462_r");
        debug("RSHIFT pressed (" + source + "); currentScreen="
                + (current == null ? "null" : current.getClass().getName()), null);
        if (current instanceof GuiOpenMaverClickGui) {
            ((GuiOpenMaverClickGui) current).closeGui();
        } else if (current == null || isPauseMenu(current)) {
            mc.displayGuiScreen(new GuiOpenMaverClickGui(current));
        }
    }

    /** 暂停菜单的类名在 MCP/SRG 下一致，用字符串判断可避免额外的 stub/import。 */
    private static boolean isPauseMenu(GuiScreen screen) {
        return "net.minecraft.client.gui.GuiIngameMenu".equals(screen.getClass().getName());
    }

    /**
     * 渲染帧开始：在 vanilla 处理鼠标输入和更新相机之前应用 aim，
     * 这样本帧的相机 / getMouseOver / 下一次 C03 都使用同一个 rotation。
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (failed || renderTickFailed) {
            return;
        }
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        try {
            OpenMaverFeatures.onRenderTick();
        } catch (Throwable t) {
            renderTickFailed = true;
            debug("render tick bridge disabled: " + t, t);
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (failed) {
            return;
        }
        if (!renderSeen) {
            renderSeen = true;
            debug("first world render event", null);
        }
        try {
            OpenMaverFeatures.onRender(event);
            ModuleManager.onRender();
            if (!motionBlurFailed) {
                try {
                    MotionBlur.onRenderWorldLast(Minecraft.getMinecraft());
                } catch (Throwable t) {
                    motionBlurFailed = true;
                    debug("motion blur disabled: " + t, t);
                }
            }
        } catch (Throwable t) {
            failed = true;
            debug("world render bridge disabled: " + t, t);
        }
    }

    /** LaunchWrapper may leave dynamically loaded default-package classes unremapped. */
    private static Object readField(Object target, String... names) {
        Class<?> type = target.getClass();
        for (String name : names) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Try the next mapping or the type based fallback below.
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.getType().isAssignableFrom(Object.class)) {
                continue;
            }
            String n = field.getType().getName();
            if ((n.equals("net.minecraft.world.World") && names[0].equals("theWorld"))
                    || (n.equals("net.minecraft.entity.player.EntityPlayer") && names[0].equals("thePlayer"))
                    || (n.equals("net.minecraft.client.gui.GuiScreen") && names[0].equals("currentScreen"))) {
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
}
