import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.util.Properties;
import java.util.List;
import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Exercises actual rendering geometry and mouse/key entry points with no native GL. */
public final class ClickGuiInteractionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("openmaver-clickgui-");
        System.setProperty("openmaver.features", root.resolve("features.properties").toString());
        System.setProperty("openmaver.config", root.resolve("modules.jsonc").toString());
        System.setProperty("openmaver.wallpapers", root.resolve("wallpapers").toString());
        Files.write(root.resolve("modules.jsonc"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("wallpapers"));
        Files.write(root.resolve("wallpapers/test.png"), new byte[0]);
        Module combat = new Module("Combat Demo", "combat.demo") {};
        ModuleManager.register(combat);
        ModuleManager.register(new Module("KeepSprint", "movement.keepsprint") {});
        ModuleManager.register(new Module("Scaffold", "movement.scaffold") {});
        ModuleManager.register(new Module("Telly", "movement.telly") {});
        ModuleManager.register(new Module("GodBridge", "movement.godbridge") {});
        ModuleManager.register(new Module("Scaffold2", "movement.scaffold2") {});
        ModuleManager.register(new module.movement.AttackReduce());
        for (String category : new String[] {"movement", "render", "player", "misc"}) {
            ModuleManager.register(new Module(category + " Demo", category + ".demo") {});
        }
        module.render.NameTag nameTag = new module.render.NameTag();
        ModuleManager.register(nameTag);
        module.combat.AntiBot antiBot = new module.combat.AntiBot();
        ModuleManager.register(antiBot);

        GuiOpenMaverClickGui gui = open(null, null, 1200, 600);
        check(columns(gui).size() == 7, "Five module categories plus Config and Personalization");
        velocitySettings(gui);
        for (String hidden : new String[] {"Telly", "God Bridge", "Scaffold2"}) {
            check(!hasEntry(gui, hidden), hidden + " standalone row is hidden");
        }
        Object keepSprint = findEntry(gui, "Keep Sprint");
        check(findSetting(keepSprint, "ROTATE") == null, "KeepSprint does not expose ROTATE");
        check(findSetting(keepSprint, "OMNI BOOST") == null, "KeepSprint hides the inactive OMNI control");
        Method moduleInfo = OpenMaverHud.class.getDeclaredMethod("moduleInfo", Module.class);
        moduleInfo.setAccessible(true);
        check("".equals(moduleInfo.invoke(null, ModuleManager.getModule("KeepSprint"))),
                "HUD omits KeepSprint's parameter preview");
        Object scaffold = findEntry(gui, "Scaffold");
        Object scaffoldMode = findSetting(scaffold, "MODE");
        String[] scaffoldOptions = (String[]) get(scaffoldMode, "options");
        check(scaffoldOptions.length == 2 && "Normal".equals(scaffoldOptions[0])
                && "Simple".equals(scaffoldOptions[1]), "Scaffold exposes only Normal and Simple");
        check(findSetting(scaffold, "EAGLE") == null && findSetting(scaffold, "SNAP") == null,
                "Scaffold removes the old Normal settings");
        Object normalSpeed = findSetting(scaffold, "YAW SPEED");
        Object legacySneak = findSetting(scaffold, "SNEAK");
        invoke(scaffoldMode, "set", new Class<?>[] {double.class}, 1.0D);
        check(ScaffoldFeature.getMode() == ScaffoldFeature.MODE_TELLY_BRIDGE,
                "Scaffold Simple selection preserves the existing bridge implementation");
        check(Boolean.FALSE.equals(invoke(normalSpeed, "visible", new Class<?>[0]))
                && Boolean.TRUE.equals(invoke(legacySneak, "visible", new Class<?>[0])),
                "Simple shows its own settings");
        invoke(scaffoldMode, "set", new Class<?>[] {double.class}, 0.0D);
        check(ScaffoldFeature.getMode() == ScaffoldFeature.MODE_NORMAL,
                "Scaffold Normal selection maps to the normal implementation");
        check(Boolean.TRUE.equals(invoke(normalSpeed, "visible", new Class<?>[0]))
                && Boolean.FALSE.equals(invoke(legacySneak, "visible", new Class<?>[0])),
                "Normal shows Augustus settings and hides legacy controls");
        invoke(normalSpeed, "setLowPercent", new Class<?>[] {double.class}, .2D);
        check(AugustusScaffold.SETTINGS.yawSpeed.low == 36,
                "Normal range slider updates the actual controller settings");
        Properties legacyKeepSprint = new Properties();
        legacyKeepSprint.setProperty("keepSprint.rotate", "false");
        KeepSprintFeature.load(legacyKeepSprint);
        check(KeepSprintFeature.isRotate(), "Legacy rotate=false config keeps silent rotation enabled");
        KeepSprintFeature.setRotate(false);
        check(KeepSprintFeature.isRotate(), "Legacy API cannot disable silent rotation");
        check(!HudFont.text.contains("Client") && !HudFont.text.contains("SYSTEM"), "Old navigation is absent");
        check(HudFont.text.contains("Config") && HudFont.text.contains("Wallpaper"), "New panels render");
        Object miscColumn = column(gui, "misc");
        Object bypassHit = entryHit(miscColumn, "Bypass");
        check(OpenMaverFeatures.getBypassAnticheat() == OpenMaverFeatures.BYPASS_GRIM,
                "Legacy settings default to Grim");
        gui.mouseClicked((Integer) get(bypassHit, "x") + 20, (Integer) get(bypassHit, "y") + 8, 1);
        set(get(bypassHit, "entry"), "expandProgress", 1F);
        render(gui);
        clickHit(gui, settingHit(miscColumn, "ANTICHEAT"));
        check(OpenMaverFeatures.isIntaveBypass(), "Bypass anticheat selector changes the active target");
        gui.mouseClicked((Integer) get(bypassHit, "x") + 20, (Integer) get(bypassHit, "y") + 8, 1);
        set(get(bypassHit, "entry"), "expandProgress", 0F);
        render(gui);
        Object renderColumn = column(gui, "render");
        Object nameTagHit = entryHit(renderColumn, "Name Tag");
        clickHit(gui, nameTagHit);
        check(nameTag.isEnabled(), "NameTag can be enabled from Render");
        gui.mouseClicked((Integer) get(nameTagHit, "x") + 20, (Integer) get(nameTagHit, "y") + 8, 1);
        set(get(nameTagHit, "entry"), "expandProgress", 1F);
        render(gui);
        Object throughWalls = settingHit(renderColumn, "THROUGH WALLS");
        clickHit(gui, throughWalls);
        check(!NameTagFeature.isThroughWalls(), "NameTag depth setting changes from the expanded controls");
        Object combatColumn = column(gui, "combat");
        Object antiBotHit = entryHit(combatColumn, "Anti Bot");
        clickHit(gui, antiBotHit);
        check(antiBot.isEnabled(), "Anti Bot can be enabled from Combat");
        gui.mouseClicked((Integer) get(antiBotHit, "x") + 20, (Integer) get(antiBotHit, "y") + 8, 1);
        set(get(antiBotHit, "entry"), "expandProgress", 1F);
        render(gui);
        clickHit(gui, settingHit(combatColumn, "NAME CHECK"));
        check(AntiBotFeature.isNameCheck(), "Anti Bot detection controls change the real settings");
        gui.mouseClicked((Integer) get(antiBotHit, "x") + 20, (Integer) get(antiBotHit, "y") + 8, 1);
        set(get(antiBotHit, "entry"), "expandProgress", 0F);
        render(gui);
        Object moduleHit = entryHit(combatColumn, "Combat Demo");
        clickHit(gui, moduleHit);
        check(combat.isEnabled(), "Left-click still toggles modules");
        Object aimHit = entryHit(combatColumn, "Aim Assist");
        gui.mouseClicked((Integer) get(aimHit, "x") + 20, (Integer) get(aimHit, "y") + 8, 1);
        Object aim = get(aimHit, "entry");
        check(((java.util.Set<?>) get(gui, "expanded")).contains(get(aim, "id")), "Right-click opens module settings");
        set(aim, "expandProgress", 1F);
        render(gui);
        Object requireClick = settingHit(combatColumn, "REQUIRE CLICK");
        boolean previous = OpenMaverFeatures.isAimRequireClick();
        clickHit(gui, requireClick);
        check(OpenMaverFeatures.isAimRequireClick() != previous, "Expanded checkboxes still change settings");
        Object modeHit = settingHit(combatColumn, "MODE");
        int oldMode = OpenMaverFeatures.getAimMode();
        clickHit(gui, modeHit);
        check(OpenMaverFeatures.getAimMode() != oldMode, "Expanded options still cycle");
        Object config = column(gui, "configs");

        click(gui, config, "name");
        set(gui, "profileName", "");
        for (char c : "gui_profile".toCharArray()) gui.keyTyped(c, 0);
        check("gui_profile".equals(get(gui, "profileName")), "Config name accepts typing");
        check("".equals(get(gui, "searchQuery")), "Profile typing does not start module search");
        combat.setEnabled(true);
        click(gui, config, "save");
        check(ConfigProfiles.exists("gui_profile"), "Save button creates a profile");
        combat.setEnabled(false);
        click(gui, config, "load");
        check(combat.isEnabled(), "Load button restores module state");
        check(nameTag.isEnabled() && !NameTagFeature.isThroughWalls(), "Profile load restores NameTag and its options");
        check(antiBot.isEnabled() && AntiBotFeature.isNameCheck(), "Profile load restores Anti Bot and its detection settings");
        check(OpenMaverFeatures.getBypassAnticheat() == OpenMaverFeatures.BYPASS_INTAVE,
                "Profile load preserves the target anticheat");
        click(gui, config, "name");
        gui.keyTyped('\0', Keyboard.KEY_ESCAPE);
        check(!(Boolean) get(gui, "profileFieldFocused") && !(Boolean) get(gui, "closing"),
                "Escape first leaves the name field");
        click(gui, config, "new");
        check((Boolean) get(gui, "profileFieldFocused"), "Plus starts a new name");
        check(!(Boolean) get(get(config, "state"), "collapsed"), "Plus does not collapse the panel");
        click(gui, config, "preferences");
        click(gui, config, "bypassAnticheat");
        check(OpenMaverFeatures.getBypassAnticheat() == OpenMaverFeatures.BYPASS_GRIM,
                "Config preferences can change the target anticheat");
        String key = OpenMaverFeatures.getGuiKeyName();
        click(gui, config, "guiKey");
        check(!key.equals(OpenMaverFeatures.getGuiKeyName()), "GUI key setting stays accessible");
        click(gui, config, "preferences");

        // Local import keeps existing profiles and leaves application to the Load button.
        String imported = ConfigProfiles.importFile(ConfigProfiles.fileOf("gui_profile"));
        check("gui_profile_2".equals(imported), "Import does not overwrite a matching name");
        File invalid = root.resolve("invalid.cfg").toFile();
        Files.write(invalid.toPath(), "invalid".getBytes(StandardCharsets.UTF_8));
        check(ConfigProfiles.importFile(invalid) == null && ConfigProfiles.statusIsError(), "Invalid import is rejected");
        render(gui);
        clickValue(gui, config, "profile", "gui_profile_2");
        check(imported.equals(get(gui, "profileName")), "List selects the profile");
        click(gui, config, "delete");
        check(!ConfigProfiles.exists(imported) && ConfigProfiles.exists("gui_profile"), "Delete affects selected profile only");

        // All panel types use the same drag/collapse behavior and keep positions on reopen.
        Object state = get(config, "state");
        int oldX = (Integer) get(state, "x");
        int oldY = (Integer) get(state, "y");
        Mouse.left = true;
        gui.mouseClicked((Integer) get(config, "headerX") + 10, (Integer) get(config, "headerY") + 8, 0);
        invoke(gui, "updateColumnPress", new Class<?>[] {int.class, int.class}, oldX + 40, oldY + 38);
        Mouse.left = false;
        invoke(gui, "updateColumnPress", new Class<?>[] {int.class, int.class}, oldX + 40, oldY + 38);
        check((Integer) get(state, "x") == oldX + 30 && (Integer) get(state, "y") == oldY + 30,
                "Config header drags without collapsing");
        render(gui);
        gui.mouseClicked((Integer) get(config, "headerX") + 10, (Integer) get(config, "headerY") + 8, 1);
        check((Boolean) get(state, "collapsed"), "Right-click header collapses Config");
        gui = open(null, null, 1200, 600);
        config = column(gui, "configs");
        check((Boolean) get(get(config, "state"), "collapsed"), "Collapse persists when reopened");
        check((Integer) get(get(config, "state"), "x") == oldX + 30, "Position persists when reopened");

        Object wallpaper = column(gui, "personalization");
        click(gui, wallpaper, "section", "color");
        click(gui, wallpaper, "themePreset", "1");
        check(MaverPersonalization.getColor() == 0xFF83B4F5, "Theme swatch updates the ClickGUI color");
        click(gui, wallpaper, "themeInput");
        gui.keyTyped('#', 0);
        for (char c : "4C78D0".toCharArray()) gui.keyTyped(c, 0);
        gui.keyTyped('\0', Keyboard.KEY_RETURN);
        check("#4C78D0".equals(MaverPersonalization.getHexColor()), "Custom theme color accepts hex input");
        click(gui, wallpaper, "section", "text");
        click(gui, wallpaper, "textInput");
        for (char c : "Maver Client".toCharArray()) gui.keyTyped(c, 0);
        gui.keyTyped('\0', Keyboard.KEY_RETURN);
        check("Maver Client".equals(MaverPersonalization.getText()), "HUD label accepts custom text");
        click(gui, wallpaper, "section", "wallpaper");
        int mode = MaverWallpaper.getMode();
        click(gui, wallpaper, "mode");
        check(MaverWallpaper.getMode() != mode, "Wallpaper mode cycles in the new panel");
        String fit = MaverWallpaper.getFitName();
        click(gui, wallpaper, "fit");
        check(!fit.equals(MaverWallpaper.getFitName()), "Wallpaper fit cycles");
        MaverWallpaper.setMode(MaverWallpaper.MODE_PANORAMA);
        clickValue(gui, wallpaper, "wallpaper", "test.png");
        check(MaverWallpaper.isSelected(root.resolve("wallpapers/test.png").toFile()), "Wallpaper row selects a file");
        check(MaverWallpaper.getMode() == MaverWallpaper.MODE_STATIC, "Choosing an image exits panorama");
        click(gui, wallpaper, "source");
        check(MaverWallpaper.isBuiltIn(), "Source restores the built-in background");

        // Main-menu shortcut reopens the new Wallpaper panel, even if it was collapsed.
        set(get(wallpaper, "state"), "collapsed", true);
        GuiScreen parent = new GuiScreen();
        gui = open(parent, "wallpaper", 400, 240);
        wallpaper = column(gui, "personalization");
        check(columns(gui).get(columns(gui).size() - 1) == wallpaper, "Wallpaper shortcut brings panel to front");
        check(!(Boolean) get(get(wallpaper, "state"), "collapsed"), "Wallpaper shortcut expands panel");
        for (Object col : columns(gui)) {
            int x = (Integer) get(col, "headerX");
            int y = (Integer) get(col, "headerY");
            check(x >= 0 && x + (Integer) get(col, "width") <= gui.width, "Resized panel fits horizontally");
            check(y >= 0 && y + 20 + (Integer) get(col, "bodyH") <= gui.height, "Resized body stays on screen");
        }
        gui.keyTyped('\0', Keyboard.KEY_ESCAPE);
        set(gui, "closingAt", System.currentTimeMillis() - 300);
        gui.drawScreen(0, 0, 0);
        check(gui.mc.currentScreen == parent, "Closing returns to the original screen");

        // A long list scrolls within the floating panel; clicked rows match the rendered position.
        for (int i = 0; i < 35; i++) {
            Files.copy(ConfigProfiles.fileOf("gui_profile").toPath(), ConfigProfiles.fileOf("scroll_" + i).toPath());
        }
        gui = open(null, "configs", 1200, 240);
        config = column(gui, "configs");
        set(gui, "lastMouseX", (Integer) get(config, "headerX") + 12);
        set(gui, "lastMouseY", (Integer) get(config, "bodyY") + 10);
        Mouse.wheel = -120;
        gui.handleMouseInput();
        render(gui);
        check((Integer) get(config, "scroll") > 0, "Overflowing Config list scrolls");
        Object visible = visibleAction(config, "profile", null);
        clickHit(gui, visible);
        check(get(visible, "value").equals(get(gui, "profileName")), "Scrolled profile hit matches the rendered row");
        Mouse.left = true;
        gui.mouseClicked((Integer) get(config, "headerX") + 10,
                (Integer) get(config, "headerY") + 8, 0);
        check(get(gui, "pressColumn") == config, "Scrolled content cannot steal a header click");
        Mouse.left = false;
        gui.onGuiClosed();
        Properties persisted = new Properties();
        persisted.load(new ByteArrayInputStream(Files.readAllBytes(root.resolve("features.properties"))));
        check("#4C78D0".equals(persisted.getProperty("ui.theme.color")), "Theme color is saved to feature settings");
        check("Maver Client".equals(persisted.getProperty("hud.splash.text")), "HUD label is saved to feature settings");
        check("false".equals(persisted.getProperty("nametag.throughWalls")), "NameTag options are saved with feature settings");
        check("true".equals(persisted.getProperty("antibot.nameCheck")), "Anti Bot options are saved with feature settings");
        System.out.println("ClickGUI interaction checks passed: " + checks);
    }

    private static void velocitySettings(GuiOpenMaverClickGui gui) throws Exception {
        Object entry = findEntry(gui, "Velocity");
        Object mode = findSetting(entry, "MODE");
        String[] names = (String[]) get(mode, "options");
        check(names.length == 20, "Velocity exposes three legacy and seventeen Augustus modes");
        for (int i = 0; i < names.length; i++) {
            check(names[i].equalsIgnoreCase(AttackReduceFeature.MODE_NAMES[i]), "Velocity mode order " + i);
            invoke(mode, "set", new Class<?>[] {double.class}, (double) i);
            check(AttackReduceFeature.getMode() == i, "Velocity selector binds mode " + i);
            check(Boolean.valueOf(i < 3).equals(invoke(findSetting(entry, "CHANCE (RESETS)"), "visible", new Class<?>[0])),
                    "Legacy settings visibility for mode " + i);
        }
        invoke(mode, "set", new Class<?>[] {double.class}, 3D);
        Object lag = findSetting(entry, "Lag");
        check(Boolean.TRUE.equals(invoke(lag, "visible", new Class<?>[0])), "Augustus common controls are visible");
        invoke(lag, "setPercent", new Class<?>[] {double.class}, .5D);
        check(AugustusVelocity.value("common", "Lag") == 200, "Common Lag slider binds runtime settings");
        invoke(lag, "set", new Class<?>[] {double.class}, 0D);
        Object explosion = findSetting(entry, "Explosion");
        Object explosionXZ = findSetting(entry, "XZVelocityInAirExplosion");
        invoke(explosion, "set", new Class<?>[] {double.class}, 0D);
        check(Boolean.FALSE.equals(invoke(explosionXZ, "visible", new Class<?>[0])), "Basic hides disabled explosion controls");
        invoke(explosion, "set", new Class<?>[] {double.class}, 1D);
        check(Boolean.TRUE.equals(invoke(explosionXZ, "visible", new Class<?>[0])), "Basic reveals explosion controls");

        invoke(mode, "set", new Class<?>[] {double.class}, 6D);
        Object pushMode = findSetting(entry, "PushMode");
        Object expertMotion = findSetting(entry, "PushMotion1");
        check(Boolean.FALSE.equals(invoke(expertMotion, "visible", new Class<?>[0])), "Push Simple hides expert controls");
        invoke(pushMode, "set", new Class<?>[] {double.class}, 1D);
        check(Boolean.TRUE.equals(invoke(expertMotion, "visible", new Class<?>[0])), "Push Expert reveals per-hurt-time controls");
        invoke(expertMotion, "setPercent", new Class<?>[] {double.class}, .25D);
        check(AugustusVelocity.value("Push", "PushMotion1") == 5, "Expert slider binds the actual mode");

        // Velocity opens a modal surface instead of making the Movement column longer.
        Object col = column(gui, "movement");
        int originalHeight = gui.height;
        gui.height = 320;
        invoke(gui, "bringToFront", new Class<?>[] {col.getClass()}, col);
        render(gui);
        clickHit(gui, entryHit(col, "Velocity"));
        render(gui);
        Object panel = get(gui, "settingsPanel");
        check(panel != null && !ModuleManager.getModule("Velocity").isEnabled(),
                "Left-click opens Velocity settings without changing its enabled state");
        check(!((java.util.Set<?>) get(gui, "expanded")).contains(get(entry, "id"))
                && (Integer) get(col, "maxScroll") == 0,
                "Velocity settings do not expand inside the Movement column");
        check((Integer) get(panel, "maxScroll") > 0, "Expert parameters have their own scroll area");
        clickHit(gui, panelAction(panel, "enabled"));
        check(ModuleManager.getModule("Velocity").isEnabled(), "Subpanel can enable Velocity");
        clickHit(gui, panelAction(panel, "enabled"));
        check(!ModuleManager.getModule("Velocity").isEnabled(), "Subpanel can disable Velocity");
        gui.keyTyped('x', 0);
        check("".equals(get(gui, "searchQuery")), "Subpanel typing cannot activate module search");

        Object checkbox = settingHit(panel, "NotWhileSPressed");
        boolean previousCheck = AugustusVelocity.enabled("common", "NotWhileSPressed");
        clickHit(gui, checkbox);
        check(AugustusVelocity.enabled("common", "NotWhileSPressed") != previousCheck,
                "Subpanel checkbox updates runtime settings");
        Object lagHit = settingHit(panel, "Lag");
        Mouse.left = true;
        clickHit(gui, lagHit);
        invoke(gui, "updateDrag", new Class<?>[] {int.class},
                (Integer) get(lagHit, "controlX") + (Integer) get(lagHit, "controlW") * 3 / 4);
        check(AugustusVelocity.value("common", "Lag") == 300, "Subpanel sliders use their wider track");
        Mouse.left = false;
        gui.drawScreen(0, 0, 0F);
        check(get(gui, "dragSetting") == null, "Releasing a slider finishes and saves the drag");
        invoke(lag, "set", new Class<?>[] {double.class}, 0D);

        int modeY = (Integer) get(settingHit(panel, "MODE"), "y");
        boolean reached = false;
        for (int i = 0; i < 40; i++) {
            for (Object hit : (List<?>) get(panel, "hits")) {
                if (get(hit, "setting") != expertMotion) continue;
                reached = true;
                break;
            }
            if (reached) break;
            wheel(gui, (Integer) get(panel, "x") + 20, (Integer) get(panel, "bodyY") + 15, -120);
        }
        check(reached && (Integer) get(panel, "scroll") > 0, "Last Expert motion is reachable by wheel scrolling");
        check((Integer) get(settingHit(panel, "MODE"), "y") == modeY, "Mode selector stays fixed while parameters scroll");
        for (Object hit : (List<?>) get(panel, "hits")) {
            if (get(hit, "setting") == null || get(hit, "setting") == mode) continue;
            check((Integer) get(hit, "y") >= (Integer) get(panel, "bodyY")
                    && (Integer) get(hit, "y") + (Integer) get(hit, "h")
                    <= (Integer) get(panel, "bodyY") + (Integer) get(panel, "bodyH"),
                    "Scrolled setting hits stay inside the parameter area");
        }

        clickHit(gui, settingHit(panel, "MODE"));
        render(gui);
        check(AttackReduceFeature.getMode() == 6 && get(panel, "dropdown") != null,
                "Mode click opens a dropdown without cycling the current value");
        int bodyScroll = (Integer) get(panel, "scroll");
        for (int i = 0; i < 12; i++) {
            wheel(gui, (Integer) get(panel, "choiceX") + 15, (Integer) get(panel, "choiceY") + 10, -120);
        }
        check((Integer) get(panel, "scroll") == bodyScroll, "Dropdown wheel cannot move the parameter area");
        clickHit(gui, dropdownChoice(panel, "19"));
        render(gui);
        check(AttackReduceFeature.getMode() == 19 && get(panel, "dropdown") == null,
                "Dropdown can directly select the final Velocity mode");
        check((Integer) get(panel, "scroll") == 0, "Changing modes resets parameter scrolling");
        check(findVisibleSetting(panel, "PushMotion1") == null, "Mode changes remove settings from the previous mode");
        clickHit(gui, settingHit(panel, "MODE"));
        render(gui);
        check(dropdownChoice(panel, "19") != null, "Dropdown opens with the current mode in view");
        gui.keyTyped('\0', Keyboard.KEY_ESCAPE);
        check(get(panel, "dropdown") == null && get(gui, "settingsPanel") == panel,
                "First Escape closes just the dropdown");
        gui.keyTyped('\0', Keyboard.KEY_ESCAPE);
        check(get(gui, "settingsPanel") == null && !(Boolean) get(gui, "closing"),
                "Second Escape returns to the main ClickGUI");

        gui.mouseClicked((Integer) get(entryHit(col, "Velocity"), "x") + 20,
                (Integer) get(entryHit(col, "Velocity"), "y") + 8, 1);
        render(gui);
        check(get(gui, "settingsPanel") != null, "Right-click also opens the Velocity subpanel");
        Module demo = ModuleManager.getModule("Combat Demo");
        boolean demoEnabled = demo.isEnabled();
        clickHit(gui, entryHit(column(gui, "combat"), "Combat Demo"));
        check(get(gui, "settingsPanel") == null && demo.isEnabled() == demoEnabled,
                "Clicking outside dismisses the subpanel without toggling a background module");

        // The same geometry remains usable at Minecraft's smaller GUI resolutions.
        int originalWidth = gui.width;
        gui.width = 320;
        gui.height = 240;
        invoke(gui, "openSettingsPanel", new Class<?>[] {entry.getClass()}, entry);
        render(gui);
        panel = get(gui, "settingsPanel");
        clickHit(gui, settingHit(panel, "MODE"));
        render(gui);
        check((Integer) get(panel, "x") >= 0 && (Integer) get(panel, "x") + (Integer) get(panel, "w") <= gui.width
                && (Integer) get(panel, "y") + (Integer) get(panel, "h") <= gui.height,
                "Subpanel fits a 320x240 GUI");
        check((Integer) get(panel, "choiceY") >= 0
                && (Integer) get(panel, "choiceY") + (Integer) get(panel, "choiceH") <= gui.height,
                "Dropdown fits a small GUI");
        gui.keyTyped('\0', Keyboard.KEY_ESCAPE);
        render(gui);
        clickHit(gui, panelAction(panel, "close"));
        check(get(gui, "settingsPanel") == null, "Close button returns to the main GUI");
        gui.width = originalWidth;
        gui.height = originalHeight;
        invoke(mode, "set", new Class<?>[] {double.class}, 1D);
        render(gui);
    }

    private static void wheel(GuiOpenMaverClickGui gui, int x, int y, int amount) throws Exception {
        set(gui, "lastMouseX", x);
        set(gui, "lastMouseY", y);
        Mouse.wheel = amount;
        gui.handleMouseInput();
        render(gui);
    }

    private static Object panelAction(Object panel, String action) throws Exception {
        for (Object hit : (List<?>) get(panel, "hits")) if (action.equals(get(hit, "action"))) return hit;
        throw new AssertionError("No panel action " + action);
    }

    private static Object dropdownChoice(Object panel, String value) throws Exception {
        for (Object hit : (List<?>) get(panel, "choices")) if (value.equals(get(hit, "value"))) return hit;
        throw new AssertionError("No dropdown choice " + value);
    }

    private static Object findVisibleSetting(Object panel, String label) throws Exception {
        for (Object hit : (List<?>) get(panel, "hits")) {
            Object setting = get(hit, "setting");
            if (setting != null && label.equalsIgnoreCase((String) get(setting, "label"))) return setting;
        }
        return null;
    }

    private static GuiOpenMaverClickGui open(GuiScreen parent, String initial, int w, int h) throws Exception {
        GuiOpenMaverClickGui gui = new GuiOpenMaverClickGui(parent, initial);
        gui.width = w;
        gui.height = h;
        gui.mc = new Minecraft();
        gui.mc.fontRendererObj = new FontRenderer();
        gui.initGui();
        render(gui);
        return gui;
    }

    private static void render(GuiOpenMaverClickGui gui) throws Exception {
        set(gui, "openProgress", 1F);
        set(gui, "columnsAt", System.currentTimeMillis() - 2000);
        set(gui, "frameDelta", 50F);
        for (int i = 0; i < 12; i++) {
            set(gui, "frameNow", System.currentTimeMillis());
            invoke(gui, "drawColumns", new Class<?>[0]);
        }
    }

    private static List<?> columns(GuiOpenMaverClickGui gui) throws Exception { return (List<?>) get(gui, "columns"); }
    private static Object column(GuiOpenMaverClickGui gui, String id) throws Exception {
        for (Object column : columns(gui)) if (id.equals(get(column, "id"))) return column;
        throw new AssertionError("Missing column " + id);
    }
    private static void click(GuiOpenMaverClickGui gui, Object column, String action) throws Exception {
        clickValue(gui, column, action, null);
    }
    private static void click(GuiOpenMaverClickGui gui, Object column, String action, String value) throws Exception {
        clickValue(gui, column, action, value);
    }
    private static Object entryHit(Object column, String name) throws Exception {
        for (Object hit : (List<?>) get(column, "hits")) {
            Object entry = get(hit, "entry");
            if (entry != null && get(hit, "setting") == null && name.equals(get(entry, "name"))) return hit;
        }
        throw new AssertionError("No module row " + name);
    }
    private static boolean hasEntry(GuiOpenMaverClickGui gui, String name) throws Exception {
        for (Object entry : (List<?>) get(gui, "entries")) {
            if (name.equals(get(entry, "name"))) return true;
        }
        return false;
    }
    private static Object findEntry(GuiOpenMaverClickGui gui, String name) throws Exception {
        for (Object entry : (List<?>) get(gui, "entries")) {
            if (name.equals(get(entry, "name"))) return entry;
        }
        throw new AssertionError("No module entry " + name);
    }
    private static Object findSetting(Object entry, String label) throws Exception {
        for (Object setting : (List<?>) get(entry, "settings")) {
            if (label.equalsIgnoreCase((String) get(setting, "label"))) return setting;
        }
        return null;
    }
    private static Object settingHit(Object column, String label) throws Exception {
        for (Object hit : (List<?>) get(column, "hits")) {
            Object setting = get(hit, "setting");
            if (setting != null && label.equalsIgnoreCase((String) get(setting, "label"))) return hit;
        }
        throw new AssertionError("No setting " + label);
    }
    private static void clickValue(GuiOpenMaverClickGui gui, Object column, String action, String value) throws Exception {
        render(gui);
        clickHit(gui, visibleAction(column, action, value));
        render(gui);
    }
    private static Object visibleAction(Object column, String action, String value) throws Exception {
        int top = (Integer) get(column, "headerY");
        int bottom = (Integer) get(column, "bodyY") + (Integer) get(column, "bodyH");
        for (Object hit : (List<?>) get(column, "hits")) {
            int center = (Integer) get(hit, "y") + (Integer) get(hit, "h") / 2;
            if (action.equals(get(hit, "action")) && (value == null || value.equals(get(hit, "value")))
                    && center >= top && center < bottom) return hit;
        }
        throw new AssertionError("No visible action " + action + " / " + value);
    }
    private static void clickHit(GuiOpenMaverClickGui gui, Object hit) throws Exception {
        gui.mouseClicked((Integer) get(hit, "x") + (Integer) get(hit, "w") / 2,
                (Integer) get(hit, "y") + (Integer) get(hit, "h") / 2, 0);
    }
    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(owner, args);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
