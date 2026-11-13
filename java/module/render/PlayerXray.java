package module.render;

import module.Module;

/**
 * Player X-Ray switch.
 *
 * <p>The actual through-wall player overlay is rendered on the Minecraft side by
 * {@code OpenMaverFeatures.renderPlayerXray()}; this module stores the enabled flag and
 * key bind so it appears in the module list and ClickGUI.
 */
public final class PlayerXray extends Module {

    public PlayerXray() {
        super("Player X-Ray", "render.playerxray");
    }
}
