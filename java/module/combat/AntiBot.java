package module.combat;

import module.Module;

/** Shared player filter; detection runs in the Minecraft-side AntiBotFeature. */
public final class AntiBot extends Module {
    public AntiBot() {
        super("AntiBot", "combat.antibot");
    }
}
