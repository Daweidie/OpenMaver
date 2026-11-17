package module.movement;

import module.Module;
import util.Logger;

/** 教材式手动搭路辅助开关。 */
public final class Scaffold2 extends Module {

    public Scaffold2() {
        super("Scaffold2");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "教材式搭路辅助已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "教材式搭路辅助已禁用");
    }
}
