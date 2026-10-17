import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import util.Logger;

/**
 * Minecraft 1.8.9 (Forge 11.15.1.2318) 入口。
 *
 * <p>职责：只做“接线”——把 Forge 生命周期与客户端事件转发给 {@link OpenMaver} 核心，
 * 不含任何具体功能逻辑。
 *
 * <ul>
 *   <li>preInit：仅注册 JVM 退出钩子（核心延迟到进入世界时启动）</li>
 *   <li>init：把客户端事件处理器与 HUD 注册到 {@code MinecraftForge.EVENT_BUS}</li>
 *   <li>首次进入世界：{@code OpenMaverClientHandler} 启动核心并弹右下角提示</li>
 * </ul>
 *
 * <p>注意：1.8.9 中 {@code FMLCommonHandler.instance().bus()} 已废弃并返回
 * {@code MinecraftForge.EVENT_BUS}，因此 tick 与渲染事件统一注册到后者即可。
 *
 * <p>本类与 {@link OpenMaver} 同处默认包，以便直接访问核心门面。
 */
@Mod(
    modid = OpenMaverMod.MODID,
    name = OpenMaverMod.NAME,
    version = OpenMaverMod.VERSION,
    clientSideOnly = true,
    acceptedMinecraftVersions = "[1.8.9]"
)
public final class OpenMaverMod {

    public static final String MODID = "openmaver";
    public static final String NAME = "OpenMaver";
    public static final String VERSION = "2.0.0";

    /** 客户端事件处理器实例（init 阶段创建并注册）。 */
    private OpenMaverClientHandler clientHandler;

    /** 右下角 HUD 提示实例（init 阶段创建并注册）。 */
    private OpenMaverHud hud;

    /** 加载阶段最早的回调：仅注册退出钩子。 */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // 游戏退出时确保本地核心被正确关闭
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                OpenMaver.stop();
            }
        }, "OpenMaver-Shutdown"));

        Logger.info(NAME, "Forge preInit 完成 (MC 1.8.9)");
    }

    /** 注册客户端 tick / 渲染事件桥接与 HUD。 */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        clientHandler = new OpenMaverClientHandler();
        MinecraftForge.EVENT_BUS.register(clientHandler);

        hud = new OpenMaverHud();
        MinecraftForge.EVENT_BUS.register(hud);

        Logger.info(NAME, "客户端事件桥接已注册");
    }
}
