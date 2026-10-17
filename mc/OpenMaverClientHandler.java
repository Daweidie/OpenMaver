import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;

/**
 * 客户端事件桥接：把 Minecraft 1.8.9 的 tick / 渲染事件转发给 {@link ModuleManager}，
 * 并负责「进入世界后启动核心 + 右下角注入成功提示」以及 ClickGUI 开关。
 *
 * <p><b>健壮性</b>：所有逻辑都包在 try/catch 里。运行期注入时若某个成员名/签名不匹配
 * （NoSuchFieldError/NoSuchMethodError），只记录一次并停用，**绝不让异常冒泡导致游戏崩溃**。
 */
@SideOnly(Side.CLIENT)
public final class OpenMaverClientHandler {

    /** RSHIFT 上一次是否按下，用于边沿触发。 */
    private boolean rightShiftDown = false;

    /** 是否已经处理过「首次进入世界」。 */
    private boolean worldEntered = false;

    /** 出错后停用，避免每帧刷屏 / 反复抛异常。 */
    private boolean failed = false;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (failed || event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || readField(mc, "theWorld", "field_71441_e") == null
                    || readField(mc, "thePlayer", "field_71439_g") == null) {
                return;
            }
            if (!worldEntered) {
                worldEntered = true;
                onEnterWorld();
            }
            handleClickGui(mc);
            ModuleManager.onTick();
        } catch (Throwable t) {
            failed = true;
            System.out.println("[OpenMaver] 客户端 tick 出错，已停用事件桥接: " + t);
        }
    }

    private void onEnterWorld() {
        if (!OpenMaver.isStarted()) {
            OpenMaver.start();
        }
        OpenMaverHud.success("注入成功！！");
    }

    private void handleClickGui(Minecraft mc) {
        boolean down = Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        if (down && !rightShiftDown) {
            GuiScreen current = (GuiScreen) readField(mc, "currentScreen", "field_71462_r");
            if (current instanceof GuiOpenMaverClickGui) {
                mc.displayGuiScreen(null);
            } else if (current == null) {
                mc.displayGuiScreen(new GuiOpenMaverClickGui());
            }
        }
        rightShiftDown = down;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (failed) {
            return;
        }
        try {
            ModuleManager.onRender();
        } catch (Throwable t) {
            failed = true;
            System.out.println("[OpenMaver] 客户端渲染出错，已停用事件桥接: " + t);
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
