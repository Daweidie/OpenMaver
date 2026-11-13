package module.render;

import module.Module;

/**
 * Target HUD switch.
 *
 * <p>The actual overlay is drawn on the Minecraft side by {@code TargetHud}; this module only
 * stores the enabled flag and key bind so it integrates with the module list, ClickGUI and
 * configuration hot-reload like every other feature.
 */
public final class TargetHud extends Module {

    public TargetHud() {
        super("Target HUD", "render.targethud");
    }
}
