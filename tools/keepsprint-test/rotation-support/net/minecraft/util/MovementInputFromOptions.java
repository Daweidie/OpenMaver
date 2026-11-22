package net.minecraft.util;

import net.minecraft.client.settings.GameSettings;

/** Mirrors vanilla key accumulation and sneak scaling before sprint/physics. */
public class MovementInputFromOptions extends MovementInput {
    private final GameSettings settings;
    public MovementInputFromOptions(GameSettings settings) { this.settings = settings; }

    @Override
    public void func_78898_a() {
        field_78900_b = (down("field_74351_w") ? 1 : 0) - (down("field_74368_y") ? 1 : 0);
        field_78902_a = (down("field_74370_x") ? 1 : 0) - (down("field_74366_z") ? 1 : 0);
        field_78899_d = down("field_74311_E");
        field_78901_c = down("field_74314_A");
        if (field_78899_d) { field_78900_b *= 0.3F; field_78902_a *= 0.3F; }
    }

    private boolean down(String field) {
        try {
            Object key = settings.getClass().getField(field).get(settings);
            return Boolean.TRUE.equals(key.getClass().getMethod("func_151470_d").invoke(key));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
