package module.render;

import module.Module;

/** Active potion cards rendered by the Minecraft-side EffectTagHud. */
public final class EffectTag extends Module {
    public EffectTag() {
        super("Effect Tag", "render.effecttag");
        defaultEnabled = true;
    }
}
