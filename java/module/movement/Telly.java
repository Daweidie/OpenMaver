package module.movement;

import module.Module;
import module.ModuleManager;
import util.Logger;

/** OpenZen Telly Bridge / Old Telly，MC 侧复用 Scaffold 的放置与静默转向引擎。 */
public final class Telly extends Module {

    public Telly() {
        super("Telly");
    }

    @Override
    public void onEnable() {
        for (String name : new String[] {"Scaffold", "GodBridge", "Scaffold2"}) {
            Module other = ModuleManager.getModule(name);
            if (other != null) other.setEnabled(false);
        }
        Logger.info(getName(), "Telly 搭路已启用（移动时自动起跳）");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "Telly 搭路已禁用");
    }
}
