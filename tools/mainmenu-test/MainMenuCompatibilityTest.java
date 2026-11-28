import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;

/** Exercise mod title-screen recognition, button rendering, and standard action dispatch. */
public final class MainMenuCompatibilityTest {
    private static int checks;
    private static int actions;
    private static GuiButton clicked;
    private static boolean openAccounts;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("openmaver-mainmenu-");
        System.setProperty("openmaver.features", root.resolve("features.properties").toString());
        System.setProperty("openmaver.accounts", root.resolve("accounts.json").toString());
        util.Logger.configure(root.resolve("test.log").toString(), util.Logger.Level.WARN, false);
        Minecraft mc = new Minecraft();
        Minecraft.instance = mc;
        Field skinCache = GuiMaverMainMenu.class.getDeclaredField("SKIN_CACHE");
        skinCache.setAccessible(true);
        ((Map<String, BufferedImage>) skinCache.get(null)).put("original",
                new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB));
        GuiScreen.listener = new GuiScreen.ActionListener() {
            @Override public void afterAction(GuiScreen screen, GuiButton button) {
                check(screen instanceof GuiMainMenu, "IAS recognizes title-screen action events");
                actions++;
                clicked = button;
                if (openAccounts) screen.mc.displayGuiScreen(new GuiScreen());
            }
        };

        for (int[] size : new int[][] {{320, 240}, {600, 270}, {1200, 600}}) {
            GuiMaverMainMenu menu = new GuiMaverMainMenu();
            mc.displayGuiScreen(menu);
            menu.width = size[0];
            menu.height = size[1];
            menu.initGui();
            Field openedAt = GuiMaverMainMenu.class.getDeclaredField("openedAt");
            openedAt.setAccessible(true);
            openedAt.setLong(menu, System.currentTimeMillis() - 2000L);
            check(menu instanceof GuiMainMenu, "IAS recognizes Maver as a title screen");
            check(menu.buttonList.isEmpty(), "No vanilla controls are added");

            // IAS 8.0.1 adds this button in InitGuiEvent.Post after an instanceof check.
            TrackingButton ias = new TrackingButton(104027, menu.width / 2 + 104,
                    menu.height / 4 + 96, 20, 20);
            menu.buttonList.add(ias);
            HudFont.text.clear();
            menu.drawScreen(ias.xPosition + 2, ias.yPosition + 2, 0);
            check(ias.draws == 1 && ias.hovered, "Mod button renders with hover coordinates");
            check(ias.afterFooter, "Mod button renders above Maver's own UI");
            check(!(Boolean) get(menu, "failed"), "Normal menu rendering succeeds");

            int before = actions;
            openAccounts = true;
            menu.mouseClicked(ias.xPosition + 2, ias.yPosition + 2, 0);
            openAccounts = false;
            check(actions == before + 1 && clicked == ias, "Mod receives exactly one standard action");
            check(mc.currentScreen != menu, "IAS action can open its account screen");

            mc.displayGuiScreen(menu);
            check(menu.buttonList.isEmpty(), "Reopening clears stale mod buttons");
            menu.buttonList.add(ias);
            menu.drawScreen(ias.xPosition, ias.yPosition, 0);
            check(ias.draws == 2, "Mod buttons render after reopening");
        }

        GuiMaverMainMenu menu = new GuiMaverMainMenu();
        mc.displayGuiScreen(menu);
        List<?> maverButtons = (List<?>) get(menu, "buttons");
        Object singleplayer = maverButtons.get(0);
        TrackingButton overlap = new TrackingButton(4, (Integer) get(singleplayer, "x"),
                (Integer) get(singleplayer, "y"), 20, 20);
        menu.buttonList.add(overlap);
        int x = overlap.xPosition + 2, y = overlap.yPosition + 2;
        int before = actions;
        menu.mouseClicked(x, y, 0);
        check(actions == before + 1 && clicked == overlap, "Visible mod button takes priority over Maver");
        check(mc.currentScreen == menu, "Mod ID 4 does not activate vanilla Quit or a Maver control");
        before = actions;
        menu.mouseClicked(x, y, 1);
        check(actions == before, "Right-click does not activate mod buttons");
        overlap.enabled = false;
        menu.mouseClicked(x, y, 0);
        check(actions == before && mc.currentScreen == menu, "Disabled overlay cannot activate either control");
        overlap.visible = false;
        menu.mouseClicked(x, y, 0);
        check(actions == before && mc.currentScreen != menu, "Hidden mod button does not block Maver controls");

        mc.displayGuiScreen(menu);
        menu.keyTyped('\0', 1);
        check(mc.currentScreen == menu, "Escape keeps the title screen open");
        menu.mouseClicked(25, 25, 0);
        check(mc.currentScreen instanceof GuiMaverAccounts, "Maver account manager remains accessible");

        mc.displayGuiScreen(menu);
        TrackingButton fallback = new TrackingButton(104027, 700, 250, 20, 20);
        menu.buttonList.add(fallback);
        menu.mc = new Minecraft() {
            @Override public net.minecraft.client.renderer.texture.TextureManager getTextureManager() {
                throw new IllegalStateException("Simulated panorama failure");
            }
        };
        menu.drawScreen(702, 252, 0);
        check((Boolean) get(menu, "failed"), "Background failure uses the fallback menu");
        check(fallback.draws == 1, "Mod buttons remain visible in the fallback menu");
        GuiScreen.listener = null;
        System.out.println("mainmenu-test: " + checks + " passed");
    }

    private static final class TrackingButton extends GuiButton {
        int draws;
        boolean hovered, afterFooter;
        TrackingButton(int id, int x, int y, int width, int height) {
            super(id, x, y, width, height, "IAS");
        }
        @Override public void drawButton(Minecraft mc, int x, int y) {
            draws++;
            hovered = x >= xPosition && x < xPosition + width
                    && y >= yPosition && y < yPosition + height;
            afterFooter = HudFont.text.contains("Copyright Mojang AB. Do not distribute!");
        }
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
