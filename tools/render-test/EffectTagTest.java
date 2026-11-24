import java.util.Arrays;
import java.util.List;
import module.ModuleManager;
import module.render.EffectTag;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

public final class EffectTagTest {
    private static int checks;
    public static void main(String[] args) {
        EffectTag module = new EffectTag();
        ModuleManager.register(module);
        check(EffectTagHud.isEnabled(), "New and existing configs enable the cards by default");
        module.setEnabled(false);
        check(!EffectTagHud.isEnabled(), "Render module switch controls the cards");
        Potion.potionTypes[3] = new Potion() {
            public String getName() { return "Haste"; }
        };
        Potion.potionTypes[22] = new Potion() {
            public String getName() { return "Absorption"; }
        };
        Effect haste = new Effect(3, 5900, 1);
        Effect absorption = new Effect(22, 300, 0);
        check("Haste II".equals(EffectTagHud.effectName(haste)), "Amplifier displays as a Roman level");
        check("Absorption".equals(EffectTagHud.effectName(absorption)), "Level one omits the suffix");
        check("4:55".equals(EffectTagHud.duration(haste.getDuration())), "Reference timer format");
        check("0:01".equals(EffectTagHud.duration(1)), "Positive durations never show zero early");
        check("0:00".equals(EffectTagHud.duration(-1)), "Expired durations clamp to zero");
        List<PotionEffect> sorted = EffectTagHud.sortedEffects(Arrays.<PotionEffect>asList(
                absorption, new Effect(80, 200, 0), new Effect(3, 0, 0), haste));
        check(sorted.size() == 2 && sorted.get(0) == haste && sorted.get(1) == absorption,
                "Expired/unknown effects are omitted and valid cards keep stable positions");
        check(EffectTagHud.sortedEffects(Arrays.<PotionEffect>asList()).isEmpty(),
                "No active effects produces no cards");
        System.out.println("Effect Tag checks passed: " + checks);
    }
    private static final class Effect extends PotionEffect {
        final int id, ticks, amplifier;
        Effect(int id, int ticks, int amplifier) {
            super(id, ticks, amplifier, false, true);
            this.id = id; this.ticks = ticks; this.amplifier = amplifier;
        }
        public int getPotionID() { return id; }
        public int getDuration() { return ticks; }
        public int getAmplifier() { return amplifier; }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
