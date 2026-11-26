package module.misc;

import module.Module;
import util.Logger;

/** 记录 Intave 背包/移动诊断数据；只观测，不修改网络包或输入。 */
public final class IntaveDebug extends Module {
    public IntaveDebug() {
        super("Intave Debug", "misc.intavedebug");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "诊断已启用；完整包序写入 logs/intave-movedisabler-debug.log");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "诊断已关闭");
    }
}
