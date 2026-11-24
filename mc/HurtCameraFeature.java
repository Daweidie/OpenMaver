import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;

/** Client-side hurt-camera suppression for Minecraft 1.8.9. */
public final class HurtCameraFeature {
    private static Object savedPlayer;
    private static int savedHurtTime;
    private static boolean captured;

    private HurtCameraFeature() { }

    public static void onRenderTick(Minecraft mc, boolean start) {
        Module module = ModuleManager.getModule("No Hurt Cam");
        if (!start) {
            if (captured && mc != null && mc.thePlayer == savedPlayer) {
                OpenMaverFeatures.setNumber(savedPlayer, "hurtTime", "field_70737_aN", savedHurtTime);
            }
            captured = false;
            savedPlayer = null;
            return;
        }
        if (module == null || !module.isEnabled() || mc == null || mc.thePlayer == null) return;
        // EntityRenderer.hurtCameraEffect reads hurtTime. Hide it only during this render
        // frame, then restore it before client simulation continues.
        savedPlayer = mc.thePlayer;
        savedHurtTime = (int) OpenMaverFeatures.number(savedPlayer, "hurtTime", "field_70737_aN");
        captured = true;
        OpenMaverFeatures.setNumber(savedPlayer, "hurtTime", "field_70737_aN", 0.0D);
    }
}
