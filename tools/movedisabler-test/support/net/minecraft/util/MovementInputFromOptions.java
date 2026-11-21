package net.minecraft.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.settings.GameSettings;

/** Vanilla 1.8.9 input update resets the vector and reads KeyBinding state, not hardware. */
public class MovementInputFromOptions extends MovementInput {
    private final GameSettings settings;
    public MovementInputFromOptions(GameSettings settings) { this.settings = settings; }
    @Override public void updatePlayerMoveState() {
        moveForward = 0; moveStrafe = 0;
        if (pressed("keyBindForward", "field_74351_w")) moveForward++;
        if (pressed("keyBindBack", "field_74368_y")) moveForward--;
        if (pressed("keyBindLeft", "field_74370_x")) moveStrafe++;
        if (pressed("keyBindRight", "field_74366_z")) moveStrafe--;
        jump = pressed("keyBindJump", "field_74314_A");
        sneak = pressed("keyBindSneak", "field_74311_E");
        if (sneak) { moveForward *= 0.3F; moveStrafe *= 0.3F; }
    }
    private boolean pressed(String mcp, String srg) {
        for (String name : new String[] {mcp, srg}) {
            try {
                Field field = settings.getClass().getField(name);
                Object binding = field.get(settings);
                for (String method : new String[] {"isKeyDown", "func_151470_d"}) {
                    try {
                        Method getter = binding.getClass().getMethod(method);
                        return Boolean.TRUE.equals(getter.invoke(binding));
                    } catch (NoSuchMethodException ignored) { }
                }
            } catch (ReflectiveOperationException ignored) { }
        }
        return false;
    }
}
