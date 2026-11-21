import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** MoveDisabler 的容器移动输入；在 vanilla 更新输入之后、移动物理之前补入实体输入。 */
public final class InventoryMoveFeature {
    private static Minecraft client;

    private InventoryMoveFeature() { }

    /** 与包钩子独立：单人世界、网络钩子暂未就绪时也照常安装输入钩子。 */
    static void onTick(Minecraft mc) {
        client = mc;
        if (!enabled() || read(mc, "theWorld", "field_71441_e") == null) return;
        Object player = read(mc, "thePlayer", "field_71439_g");
        Object input = read(player, "movementInput", "field_71158_b");
        if (input instanceof ScaffoldMovementInput) return;
        // 复用 Maver 已有的输入回调，保留其他 mod 的自定义输入实现。
        if (input == null || input.getClass() != MovementInputFromOptions.class) return;
        Object settings = read(mc, "gameSettings", "field_71474_y");
        if (!(settings instanceof GameSettings)) return;
        MovementInput old = (MovementInput) input;
        ScaffoldMovementInput hook = new ScaffoldMovementInput((GameSettings) settings);
        hook.moveForward = old.moveForward;
        hook.moveStrafe = old.moveStrafe;
        hook.jump = old.jump;
        hook.sneak = old.sneak;
        OpenMaverFeatures.writeObjectField(player, "movementInput", "field_71158_b", hook);
    }

    /** 直接读取物理键位，每次重算输入，避免 GuiContainer 清空 KeyBinding 后走不动。 */
    static void afterMovementInput(MovementInput input) {
        Minecraft mc = client;
        if (!enabled() || mc == null || read(mc, "theWorld", "field_71441_e") == null) return;
        Object player = read(mc, "thePlayer", "field_71439_g");
        if (read(player, "movementInput", "field_71158_b") != input) return;
        Object screen = read(mc, "currentScreen", "field_71462_r");
        // 覆盖背包、箱子、熔炉等容器；聊天、告示牌、菜单和 ClickGUI 保持自身输入语义。
        if (!(screen instanceof GuiContainer)) return;
        // 先清零再采样：切出窗口或正在重命名/搜索时，上一帧的输入立即释放。
        input.moveForward = 0;
        input.moveStrafe = 0;
        input.jump = false;
        input.sneak = false;
        if (!Display.isActive() || hasFocusedTextField(screen)) return;
        Object settings = read(mc, "gameSettings", "field_71474_y");
        if (down(settings, "keyBindForward", "field_74351_w")) input.moveForward++;
        if (down(settings, "keyBindBack", "field_74368_y")) input.moveForward--;
        if (down(settings, "keyBindLeft", "field_74370_x")) input.moveStrafe++;
        if (down(settings, "keyBindRight", "field_74366_z")) input.moveStrafe--;
        input.jump = down(settings, "keyBindJump", "field_74314_A");
        input.sneak = down(settings, "keyBindSneak", "field_74311_E");
        if (input.sneak) {
            input.moveForward *= 0.3F;
            input.moveStrafe *= 0.3F;
        }
    }

    public static boolean isMovementHookInstalled() {
        Object player = read(client, "thePlayer", "field_71439_g");
        return read(player, "movementInput", "field_71158_b") instanceof ScaffoldMovementInput;
    }

    private static boolean down(Object settings, String mcp, String srg) {
        Object binding = read(settings, mcp, srg);
        Object value = OpenMaverFeatures.call(binding, "getKeyCode", "func_151463_i", null);
        if (!(value instanceof Number)) return false;
        int code = ((Number) value).intValue();
        if (code < 0) return code >= -100 && Mouse.isButtonDown(code + 100);
        return code > 0 && code < 256 && Keyboard.isKeyDown(code);
    }

    private static boolean hasFocusedTextField(Object screen) {
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !GuiTextField.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    if (Boolean.TRUE.equals(OpenMaverFeatures.call(field.get(screen),
                            "isFocused", "func_146206_l", null))) return true;
                } catch (ReflectiveOperationException ignored) {
                    // 读取文本状态异常时保留界面输入，避免一边打字一边移动。
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean enabled() {
        Module module = ModuleManager.getModule("MoveDisabler");
        return module != null && module.isEnabled();
    }

    private static Object read(Object target, String mcp, String srg) {
        return OpenMaverFeatures.read(target, mcp, srg, null);
    }
}
