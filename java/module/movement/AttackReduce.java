package module.movement;

import module.Module;
import util.Logger;

/** Velocity 防击退开关；显式保留 movement.attackreduce 以兼容旧开关和热键。 */
public final class AttackReduce extends Module {
    public AttackReduce() {
        super("Velocity", "movement.attackreduce");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "Velocity 防击退已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "Velocity 已禁用");
    }
}
