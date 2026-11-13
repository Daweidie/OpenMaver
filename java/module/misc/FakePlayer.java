package module.misc;

import module.Module;
import util.Logger;

/**
 * Fake Player（假人玩家）模块开关。
 *
 * <p>本类只负责开关状态。真正的生成/移除逻辑位于 MC 侧的
 * {@code OpenMaverFeatures.updateFakePlayers()}：它会在客户端世界中创建若干
 * {@code EntityOtherPlayerMP}，让它们出现在玩家前方，可用于测试 Aim Assist、
 * Player ESP、Target HUD 等模块。
 *
 * <p><b>仅客户端</b>：假人不会发送任何生成包，也不会被服务端或其他玩家看到，
 * 因此不会影响服务器反作弊判定。
 *
 * <p>配置路径由基类按包名约定推导：{@code module.misc.FakePlayer -> "misc.fakeplayer"}，
 * 对应 default.jsonc 中的：
 * <pre>
 *   "misc": { "fakeplayer": { "enabled": false, "key": "N" } }
 * </pre>
 */
public final class FakePlayer extends Module {

    public FakePlayer() {
        // 配置路径自动推导为 "misc.fakeplayer"
        super("FakePlayer");
    }

    @Override
    public void onEnable() {
        Logger.info(getName(), "假人玩家已启用");
    }

    @Override
    public void onDisable() {
        Logger.info(getName(), "假人玩家已禁用");
    }
}
