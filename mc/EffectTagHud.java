import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Compact, localized potion cards along the left edge of the screen. */
public final class EffectTagHud {
    private static final ResourceLocation ICONS =
            new ResourceLocation("textures/gui/container/inventory.png");
    private static final int MARGIN = 6;
    private static final int HEIGHT = 32;
    private static final int GAP = 5;
    private static final float TEXT_SIZE = 0.85F;

    private EffectTagHud() {}

    public static boolean isEnabled() {
        Module module = ModuleManager.getModule("Effect Tag");
        return module != null && module.isEnabled();
    }

    public static void render(FontRenderer font, ScaledResolution resolution) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!isEnabled() || mc == null || mc.thePlayer == null || mc.theWorld == null
                || (mc.gameSettings != null && mc.gameSettings.hideGUI)) return;
        // Use the declaring type so the small MCP -> SRG mapper resolves the inherited API.
        EntityLivingBase player = mc.thePlayer;
        List<PotionEffect> effects = sortedEffects(player.getActivePotionEffects());
        if (effects.isEmpty()) return;

        List<String> names = new ArrayList<String>();
        int width = 106;
        for (PotionEffect effect : effects) {
            String name = effectName(effect);
            names.add(name);
            width = Math.max(width, HudFont.getStringWidth(font, name, TEXT_SIZE) + 42);
        }
        width = Math.min(width, Math.max(70, resolution.getScaledWidth() - MARGIN * 2));
        int stackHeight = effects.size() * (HEIGHT + GAP) - GAP;
        int y = Math.max(StatsHud.bottom() + GAP, MARGIN);
        int x = MARGIN;
        // Keep the cards anchored at the left edge; long lists wrap down before
        // taking another column rather than drifting right beside other HUDs.
        if (y + stackHeight > resolution.getScaledHeight() - MARGIN) {
            y = MARGIN;
        }
        int rows = Math.max(1, (resolution.getScaledHeight() - y - MARGIN + GAP)
                / (HEIGHT + GAP));
        for (int i = 0; i < effects.size(); i++) {
            int cardX = x + (i / rows) * (width + GAP);
            int cardY = y + (i % rows) * (HEIGHT + GAP);
            if (cardX + width > resolution.getScaledWidth() - MARGIN) break;
            // Draw all glass first, so later cards never sample already drawn text.
            GuiBlur.draw(cardX, cardY, width, HEIGHT, 6.0F, 0x11141B, 0.80F, 0.94F);
        }
        GlColor.restore();
        try (ModelRenderState ignored = new ModelRenderState()) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            for (int i = 0; i < effects.size(); i++) {
                int cardX = x + (i / rows) * (width + GAP);
                int cardY = y + (i % rows) * (HEIGHT + GAP);
                if (cardX + width > resolution.getScaledWidth() - MARGIN) break;
                PotionEffect effect = effects.get(i);
                Potion potion = Potion.potionTypes[effect.getPotionID()];
                int color = 0xFF000000 | potion.getLiquidColor();
                if (potion.hasStatusIcon()) {
                    mc.getTextureManager().bindTexture(ICONS);
                    GlColor.set(color);
                    int icon = potion.getStatusIconIndex();
                    Gui.drawModalRectWithCustomSizedTexture(cardX + 8, cardY + 7,
                            icon % 8 * 18, 198 + icon / 8 * 18, 18, 18, 256, 256);
                    GlColor.restore();
                }
                String name = fit(font, names.get(i), width - 40);
                HudFont.drawString(font, name, cardX + 34, cardY + 5, color, TEXT_SIZE);
                int timeColor = effect.getDuration() <= 200 ? 0xFFFF6D79
                        : effect.getDuration() <= 600 ? 0xFFFFC34D : 0xFFF0F1F5;
                HudFont.drawString(font, effect.getIsPotionDurationMax() ? "∞" : duration(effect.getDuration()),
                        cardX + 34, cardY + 18, timeColor, TEXT_SIZE);
            }
        } finally {
            GlColor.restore();
        }
    }

    static List<PotionEffect> sortedEffects(Iterable<PotionEffect> active) {
        List<PotionEffect> result = new ArrayList<PotionEffect>();
        for (PotionEffect effect : active) {
            int id = effect.getPotionID();
            if (effect.getDuration() > 0 && id >= 0 && id < Potion.potionTypes.length
                    && Potion.potionTypes[id] != null) result.add(effect);
        }
        Collections.sort(result, new Comparator<PotionEffect>() {
            public int compare(PotionEffect a, PotionEffect b) {
                // Stable placement while timers count down or effects are refreshed.
                return Integer.compare(a.getPotionID(), b.getPotionID());
            }
        });
        return result;
    }

    static String effectName(PotionEffect effect) {
        String name = I18n.format(Potion.potionTypes[effect.getPotionID()].getName());
        return effect.getAmplifier() == 0 ? name : name + " " + level(effect.getAmplifier() + 1);
    }

    static String duration(int ticks) {
        long seconds = (Math.max(0L, ticks) + 19L) / 20L;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    private static String level(int value) {
        if (value < 1 || value > 10) return Integer.toString(value);
        return new String[] {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"}[value - 1];
    }

    private static String fit(FontRenderer font, String name, int width) {
        if (HudFont.getStringWidth(font, name, TEXT_SIZE) <= width) return name;
        while (!name.isEmpty()
                && HudFont.getStringWidth(font, name + "…", TEXT_SIZE) > width) {
            name = name.substring(0, name.offsetByCodePoints(name.length(), -1));
        }
        return name + "…";
    }
}
