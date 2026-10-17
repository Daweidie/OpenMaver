import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;

/**
 * 运行期注入 → 游戏内 UI 的桥接。
 *
 * <p>把 {@code mc/} 里的游戏内类（HUD / ClickGUI / 事件桥接）交给**游戏的 LaunchClassLoader**
 * 加载，再反射注册到 {@code MinecraftForge.EVENT_BUS}。
 *
 * <p><b>关键</b>：必须走 {@code LaunchClassLoader.loadClass}，不能直接 defineClass。
 * Forge 1.8.9 运行期用混淆名，FML 的 DeobfuscatingTransformer 在 LaunchClassLoader 里
 * 把 SRG 名（我们 reobf 后的名字）转成混淆名。绕过 findClass 就等于绕过转换器，
 * 会出现 {@code NoSuchFieldError}/{@code NoSuchMethodError}。
 *
 * <p>本类只依赖 JDK 反射，编译期不依赖 MC/Forge。由 JVMTI agent 调用
 * {@link #install(String)}。
 */
public final class OpenMaverMcBridge {

    private OpenMaverMcBridge() {
        // 工具类，禁止实例化
    }

    /**
     * 把游戏内 UI 挂到目标 Forge 客户端。
     *
     * @param mcJar 含 {@code mc/} 类（已 reobf 成 SRG）的 jar
     * @return 是否注册成功
     */
    public static boolean install(String mcJar) {
        return install(mcJar, null);
    }

    /**
     * Install using the same LaunchClassLoader for both the UI jar and the
     * bootstrap core jar.  The explicit core URL matters on LaunchWrapper
     * setups whose parent delegation does not expose JVMTI bootstrap classes.
     */
    public static boolean install(String mcJar, String coreJar) {
        try {
            System.out.println("[OpenMaver] 桥接开始: mcjar=" + mcJar);
            ClassLoader gameLoader = launchClassLoader();
            if (gameLoader == null) {
                System.out.println("[OpenMaver] 桥接失败: 未找到 LaunchClassLoader（不是 Forge 客户端？）");
                return false;
            }
            System.out.println("[OpenMaver] LaunchClassLoader=" + gameLoader);

            addUrl(gameLoader, mcJar);
            if (coreJar != null && !coreJar.isEmpty()) {
                addUrl(gameLoader, coreJar);
            }

            // 通过 LaunchClassLoader 加载（FML 转换器会对 SRG 名做映射）
            Class<?> hudClass = Class.forName("OpenMaverHud", true, gameLoader);
            Class<?> handlerClass = Class.forName("OpenMaverClientHandler", true, gameLoader);
            System.out.println("[OpenMaver] 已加载游戏内类: " + hudClass + ", " + handlerClass);

            Object bus = eventBus(gameLoader);
            Method register = bus.getClass().getMethod("register", Object.class);
            register.invoke(bus, handlerClass.getDeclaredConstructor().newInstance());
            register.invoke(bus, hudClass.getDeclaredConstructor().newInstance());

            System.out.println("[OpenMaver] 已注册游戏内 UI 到 Forge 事件总线 (HUD + ClickGUI)");
            return true;
        } catch (Throwable t) {
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            System.out.println("[OpenMaver] 注册游戏内 UI 失败: " + cause);
            return false;
        }
    }

    /** 取 Forge 1.8.9 的 LaunchClassLoader（net.minecraft.launchwrapper.Launch.classLoader）。 */
    private static ClassLoader launchClassLoader() throws Exception {
        ClassLoader system = ClassLoader.getSystemClassLoader();
        Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch", false, system);
        try {
            Object loader = launch.getField("classLoader").get(null);
            if (loader instanceof ClassLoader) {
                return (ClassLoader) loader;
            }
        } catch (NoSuchFieldException ignored) {
            // Some LaunchWrapper builds keep the field non-public.
            java.lang.reflect.Field field = launch.getDeclaredField("classLoader");
            field.setAccessible(true);
            Object loader = field.get(null);
            if (loader instanceof ClassLoader) {
                return (ClassLoader) loader;
            }
        }
        return null;
    }

    /** 用 LaunchClassLoader 的公开 addURL 追加 jar。 */
    private static void addUrl(ClassLoader loader, String jarPath) throws Exception {
        URL url = new File(jarPath).toURI().toURL();
        Method addUrl = loader.getClass().getMethod("addURL", URL.class);
        addUrl.invoke(loader, url);
        System.out.println("[OpenMaver] 已向 LaunchClassLoader 追加: " + jarPath);
    }

    /** 反射拿到 {@code MinecraftForge.EVENT_BUS}。 */
    private static Object eventBus(ClassLoader gameLoader) throws Exception {
        Class<?> forge = Class.forName("net.minecraftforge.common.MinecraftForge", false, gameLoader);
        try {
            return forge.getField("EVENT_BUS").get(null);
        } catch (NoSuchFieldException ignored) {
            java.lang.reflect.Field field = forge.getDeclaredField("EVENT_BUS");
            field.setAccessible(true);
            return field.get(null);
        }
    }
}
