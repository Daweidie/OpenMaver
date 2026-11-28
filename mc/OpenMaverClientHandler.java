import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
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

    /** tick 轮询用的开关键边沿状态（键盘事件之外的第二条路径）。 */
    private boolean rightShiftDown = false;
    private long lastToggleNanos = 0L;
    /** 按键桥出错日志的限流时间戳。 */
    private long lastKeyErrorNanos = 0L;
    /** 第一帧键盘事件只打印一次键名，方便确认右 Shift 实际报的是哪个键码。 */
    private boolean firstKeyLogged = false;

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

    /** 出错后停用，避免每帧刷屏 / 反复抛异常（按键路径不再受此限制）。 */
    private boolean failed = false;
    private boolean renderTickFailed = false;
    private boolean motionBlurFailed = false;
    private boolean tickSeen = false;
    private boolean renderSeen = false;
    private boolean blockHitFailed = false;
    private boolean tauntFailed = false;

    private static void debug(String message, Throwable error) {
        StringBuilder line = new StringBuilder("[OpenMaver] event: ").append(message);
        if (error != null) {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            line.append(System.lineSeparator()).append(stack);
        }
        String text = line.toString();
        System.err.println(text);
        // 事件日志跟着系统临时目录走：写死 /tmp 在 Windows 上会静默失败，
        // 于是「别人那边没反应」时连一行排查线索都拿不到。
        String logPath = System.getProperty("openmaver.events.log",
                System.getProperty("java.io.tmpdir", ".") + java.io.File.separator
                        + "openmaver-events.log");
        try (FileOutputStream out = new FileOutputStream(logPath, true)) {
            out.write((text + System.lineSeparator()).getBytes("UTF-8"));
        } catch (Throwable ignored) {
            // Diagnostics must not interfere with the game loop.
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        // 轮询兜底放在最前面，且自带 try/catch：
        // 即使别的功能在 tick 里抛过一次异常把 failed 置位，开关键位也还能用。
        if (event != null && event.phase == TickEvent.Phase.END) {
            try {
                pollClickGui();
            } catch (Throwable t) {
                logThrottled("clickgui poll", t);
            }
        }
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
            // START：在 vanilla 发送 C03 之前应用 aim / sprint / 箱子点击。
            if (event.phase == TickEvent.Phase.START) {
                syncHeldKeys();
                // 一次性准备事务，之后直到 C03 不再从 tick 维护里插入延迟 C0F。
                OpenMaverFeatures.prepareTick(mc);
                tickBlockHit(mc, true);
                LegacySoundFeature.onTick(mc);
                // 先挂窗口出站钩子，覆盖手动操作和 Chest Stealer 产生的 C0E / C0D。
                MoveDisablerFeature.onTick(mc);
                // 纯观测：记录 C03/C0E/C16/C0D 及服务端窗口/事务响应，不改包序。
                IntaveMoveDebugFeature.onTick(mc);
                // 先处理到期入站队列（含 S32 -> C0F），再产生本 tick 的 action。
                AttackReduceFeature.beforeTick(mc);
                // 背包整理：同样要在 C03 之前发 windowClick（C0E）。
                // 只在当前窗口就是玩家背包时动手，开着箱子时会自行让位给下面的 ChestFeature。
                InventoryManagerFeature.onTickStart(mc);
                // 箱子：windowClick（C0E）必须排在本 tick 的 C03 之前。
                // 放在 tick END 时，服务端的 transaction ack（C0F）总会落在
                // 「END 的 C0E」和「下一个 C03」之间，Grim 的 Post 检查会稳定打出
                // failed Post (vl:x): click window v1.8 —— 改到 START 后包序变成
                // C0F → C0E → C03，和玩家自己在界面里点鼠标一致。
                ChestFeature.onTick(mc);
                OpenMaverFeatures.onTickStartPrepared(mc);
                // Velocity 独立维护本地实体 id 和击退包处理器。
                AttackReduceFeature.onTickStart(mc);
                tickBlockHit(mc, false);
                return;
            }
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            MoveDisablerFeature.afterClientTick();
            if (!tickSeen) {
                tickSeen = true;
                debug("first client tick", null);
            }
            OpenMaverFeatures.onTick(mc);
            if (!tauntFailed) {
                try {
                    TauntFeature.onTick(mc);
                } catch (Throwable t) {
                    tauntFailed = true;
                    debug("taunt disabled", t);
                }
            }
            AttackReduceFeature.onTickEnd(mc);
            // 箱子：Chest ESP 的目标快照等每帧数据在 tick END 结算；
            // 真正发 windowClick 的搬运逻辑在 START（见上面那段注释）。
            if (readField(mc, "theWorld", "field_71441_e") == null
                    || readField(mc, "thePlayer", "field_71439_g") == null) {
                return;
            }
            if (!worldEntered) {
                worldEntered = true;
                debug("world/player available; starting core", null);
                onEnterWorld();
            }
            ModuleManager.onTick();
        } catch (Throwable t) {
            failed = true;
            debug("client tick bridge disabled: " + t, t);
        }
    }

    /**
     * PlayerTickEvent：
     * <ul>
     *   <li>START：ROTATE 安装/准备输入 hook；旧 OMNI 模式预判速度补偿；</li>
     *   <li>END：vanilla 已完成移动与速度计算，但 {@code onUpdateWalkingPlayer()}
     *       （C0B / C03）还没有发送 —— 恢复冲刺状态、补空中速度、攻击后补包。</li>
     * </ul>
     * ROTATE 在输入回调里写 yaw，保留到原版 C03 后，由 ClientTick END 恢复相机。
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (failed || event == null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            // 运行期字段类型是 EntityPlayerSP；沿用桥接层的 MCP/SRG 反射读取，
            // 避免宽泛编译 stub 生成 EntityPlayer 描述符后触发 NoSuchFieldError。
            if (mc == null || event.player == null
                    || event.player != readField(mc, "thePlayer", "field_71439_g")) {
                return;
            }
            if (event.phase == TickEvent.Phase.START) {
                KeepSprintFeature.onPlayerTickStart(mc, event.player);
            } else if (event.phase == TickEvent.Phase.END) {
                KeepSprintFeature.onPlayerTickEnd(event.player);
            }
        } catch (Throwable t) {
            debug("keep sprint player tick", t);
        }
    }

    /**
     * AttackEntityEvent：1.8.9 的冲刺攻击会让本地和服务端都清掉冲刺，
     * 而客户端的 serverSprintState 不会变化，vanilla 不会补 START_SPRINTING。
     * 这里只记一个记号，由同一个 tick 的 PlayerTickEvent.END 在 C03 之前补包。
     * BlockHit 同时记录对手，由后续 tick START 判断反击窗口和连击保护。
     */
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (failed || event == null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || event.entityPlayer == null
                    || event.entityPlayer != readField(mc, "thePlayer", "field_71439_g")) {
                return;
            }
            KeepSprintFeature.onAttack(event.entityPlayer);
            if (!blockHitFailed) BlockHitFeature.onAttack(mc, event);
        } catch (Throwable t) {
            debug("attack event", t);
        }
    }

    /** A feature error must not interrupt core startup or other modules. */
    private void tickBlockHit(Minecraft mc, boolean before) {
        if (blockHitFailed) return;
        try {
            if (before) BlockHitFeature.beforeTick(mc);
            else BlockHitFeature.afterTickStart(mc);
        } catch (Throwable t) {
            blockHitFailed = true;
            debug("blockhit disabled", t);
            Module blockHit = ModuleManager.getModule("BlockHit");
            if (blockHit != null) blockHit.setEnabled(false);
            try {
                BlockHitFeature.beforeTick(mc);
            } catch (Throwable cleanup) {
                debug("blockhit cleanup", cleanup);
            }
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

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (tauntFailed) return;
        try {
            TauntFeature.onChat(Minecraft.getMinecraft(), event);
        } catch (Throwable t) {
            debug("taunt chat event", t);
        }
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
        // 箱子静默开箱：轮到我们自己的开箱请求时，把箱子界面拦掉（容器照常挂上）。
        try {
            ChestFeature.onGuiOpen(event);
        } catch (Throwable t) {
            debug("chest GUI open hook", t);
        }
        if (event.isCanceled()) {
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
     * 键盘事件路径：开关键位开关 ClickGUI，其余按键匹配模块绑定键位。
     *
     * <p>Forge 的 {@link InputEvent.KeyInputEvent} 能拿到每一次按下/释放，不会像 tick
     * 轮询那样漏掉快速点按。tick 里仍保留一条轮询兜底路径，两条路径共享
     * {@link #lastToggleNanos} 去抖，避免同一按键触发两次。
     *
     * <p>允许从暂停菜单（{@code GuiIngameMenu}）直接打开：窗口失去焦点后 Minecraft
     * 会自动暂停并弹出该菜单，此时不应要求玩家先点「回到游戏」。
     *
     * <p><b>不要在这里永久停用整条按键桥</b>：早先的实现遇到一次异常就把
     * {@code keyFailed} 置位，那之后所有按键（包括开关键）在本局游戏里彻底失效，
     * 玩家只会看到「按了没反应」。现在改为限流记录日志并继续处理后续事件。
     */
    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        try {
            int key = Keyboard.getEventKey();
            if (!firstKeyLogged) {
                firstKeyLogged = true;
                debug("first key event: code=" + key + " name=" + Keyboard.getKeyName(key)
                        + ", clickgui key=" + OpenMaverFeatures.getGuiKeyName()
                        + " (" + OpenMaverFeatures.getGuiKeyCode() + ")", null);
            }
            if (!Keyboard.getEventKeyState()) {
                heldKeys.remove(Integer.valueOf(key));
                return;
            }
            // 同一次物理按下只处理一次：忽略系统键盘重复事件，避免按住 R
            // 时 AutoClicker 被反复开关。
            if (!heldKeys.add(Integer.valueOf(key))) {
                return;
            }
            if (key == OpenMaverFeatures.getGuiKeyCode()) {
                toggleClickGui("event");
                return;
            }
            toggleModuleKey(key);
        } catch (Throwable t) {
            logThrottled("key bridge", t);
        }
    }

    /** 出错时最多 2 秒记一条，避免某个坏掉的键每帧刷日志。 */
    private void logThrottled(String where, Throwable error) {
        long now = System.nanoTime();
        if (now - lastKeyErrorNanos < 2_000_000_000L) {
            return;
        }
        lastKeyErrorNanos = now;
        debug(where + " error (continuing): " + error, error);
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
        int guiKey = OpenMaverFeatures.getGuiKeyCode();
        boolean down = Keyboard.isKeyDown(guiKey);
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
        if (mc == null) {
            return;
        }
        if (readField(mc, "theWorld", "field_71441_e") == null) {
            // 还没进世界：不是故障，但记一行方便排查「按了没反应」。
            if (OpenMaverFeatures.isDebugEnabled()) {
                debug("clickgui ignored (" + source + "): not in world yet", null);
            }
            return;
        }
        lastToggleNanos = now;
        try {
            GuiScreen current = (GuiScreen) readField(mc, "currentScreen", "field_71462_r");
            debug("clickgui key " + OpenMaverFeatures.getGuiKeyName() + " (" + source
                    + "); currentScreen="
                    + (current == null ? "null" : current.getClass().getName()), null);
            if (current instanceof GuiOpenMaverClickGui) {
                ((GuiOpenMaverClickGui) current).closeGui();
            } else if (current == null || isPauseMenu(current)) {
                mc.displayGuiScreen(new GuiOpenMaverClickGui(current));
            }
        } catch (Throwable t) {
            // GUI 构造 / 首次布局在个别显卡/驱动上会抛异常，这里只记录，
            // 不能让异常冒到 tick 桥把整客户端停掉。
            logThrottled("clickgui toggle", t);
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
        if (event == null) {
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            try {
                HurtCameraFeature.onRenderTick(Minecraft.getMinecraft(), false);
            } catch (Throwable t) {
                debug("hurt camera restore", t);
            }
            return;
        }
        if (failed || renderTickFailed) return;
        try {
            HurtCameraFeature.onRenderTick(Minecraft.getMinecraft(), true);
            // 静默开箱的兜底：万一开箱包在 tick 与这一帧渲染之间才处理完，
            // 也要在画这一帧之前把箱子界面收起来，别让玩家看到闪一下。
            ChestFeature.onRenderTickStart(Minecraft.getMinecraft());
            OpenMaverFeatures.onRenderTick();
        } catch (Throwable t) {
            renderTickFailed = true;
            debug("render tick bridge disabled: " + t, t);
        }
    }

    @SubscribeEvent
    public void onRenderNameTag(RenderLivingEvent.Specials.Pre<?> event) {
        if (!failed) NameTagFeature.onRenderName(Minecraft.getMinecraft(), event);
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
            ScaffoldFeature.onRenderWorld(event);
            ChestFeature.onRenderWorld(event);
            Object tagPartial = OpenMaverFeatures.read(event, "partialTicks", "partialTicks", Float.class);
            NameTagFeature.onRenderWorld(Minecraft.getMinecraft(),
                    tagPartial instanceof Number ? ((Number) tagPartial).floatValue() : 1.0F);
            ProjectileFeature.onRenderWorld(event);
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
