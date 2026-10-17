// ---------------------------------------------------------------------------
// OpenMaver V2.0 —— JVMTI agent 入口
//
// 作用：让 libopenmaver_core.so 同时充当 **JVMTI agent**。通过 JVM Dynamic Attach
//       机制（自研注入器 inject/injector.cpp 或外部 jattach）加载本库时，
//       JVM 会调用 Agent_OnAttach()，于是我们在**已经运行**的进程内启动 OpenMaver，
//       无需重启、无需预置 -Djava.library.path。
//
// 与 JNI 桥接层的关系：
//   - 本库已由 JVM 作为 agent 载入，Java 侧 OpenMaver 的静态块再调 System.load
//     可能因路径解析失败而抛 UnsatisfiedLinkError（已在 Java 侧吞掉）；
//   - 为保证 native 方法必然可用，这里用 RegisterNatives 显式绑定
//     init / shutdown / getVersion，函数指针直接取自本翻译单元。
//
// 参数（agent options，key=value 以 , 分隔）：
//   jar=<path>    OpenMaver 核心类 jar（openmaver-core.jar），加入 bootstrap 搜索路径
//   driver=false  只 start()，不启用独立驱动线程（默认启用驱动线程）
//
// 约束：C++17、-fno-exceptions -fno-rtti，不向 Java 抛 C++ 异常。
// ---------------------------------------------------------------------------

#include "bridge.h"

#include <jvmti.h>

#include <cstdio>
#include <cstring>

namespace {

// 从 options 中取 key=value 的 value，写入 out（含结尾 '\0'）。
// 未找到返回 false。
bool optionValue(const char* options, const char* key, char* out, std::size_t outSize) {
    if (options == nullptr || out == nullptr || outSize == 0) {
        return false;
    }
    out[0] = '\0';

    const std::size_t keyLen = std::strlen(key);
    const char* p = options;
    while (*p != '\0') {
        // 逐个 token 扫描（以 ',' 分隔）
        const char* end = std::strchr(p, ',');
        const std::size_t len = (end != nullptr) ? static_cast<std::size_t>(end - p)
                                                 : std::strlen(p);
        if (len > keyLen && p[keyLen] == '=' && std::strncmp(p, key, keyLen) == 0) {
            const char* v = p + keyLen + 1;
            std::size_t vLen = len - keyLen - 1;
            if (vLen >= outSize) {
                vLen = outSize - 1;
            }
            std::memcpy(out, v, vLen);
            out[vLen] = '\0';
            return true;
        }
        if (end == nullptr) {
            break;
        }
        p = end + 1;
    }
    return false;
}

// 找到游戏的类加载器（for reference）：直接交给 Java 桥接处理。
// 这里不再用 JVMTI 猜加载器——统一由 OpenMaverMcBridge 取 LaunchClassLoader，
// 以保证类经由 findClass 加载、FML 转换器能生效。

// 把游戏内 UI（HUD / ClickGUI）挂到 LaunchClassLoader 并注册到 Forge 事件总线。
// 返回是否成功。
bool installGameUi(JNIEnv* env, const char* mcJar, const char* coreJar) {
    std::fprintf(stdout, "[OpenMaver] agent: 开始注册游戏内 UI mcjar=%.240s\n", mcJar);
    std::fflush(stdout);
    jclass bridge = env->FindClass("OpenMaverMcBridge");
    if (bridge == nullptr) {
        std::fputs("[OpenMaver] agent: 未找到 OpenMaverMcBridge（core jar 里应包含）\n", stderr);
        env->ExceptionClear();
        return false;
    }

    jmethodID install = env->GetStaticMethodID(
        bridge, "install", "(Ljava/lang/String;Ljava/lang/String;)Z");
    if (install == nullptr) {
        std::fputs("[OpenMaver] agent: 未找到 OpenMaverMcBridge.install(String,String)\n", stderr);
        env->ExceptionClear();
        return false;
    }

    jstring jar = env->NewStringUTF(mcJar);
    jstring core = env->NewStringUTF(coreJar != nullptr ? coreJar : "");
    jboolean ok = env->CallStaticBooleanMethod(bridge, install, jar, core);
    if (env->ExceptionCheck()) {
        std::fputs("[OpenMaver] agent: 游戏内 UI 桥接异常\n", stderr);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return ok == JNI_TRUE;
}

// 兜底：桥接失败时用独立驱动线程，避免核心无人驱动。
void fallbackDriver(JNIEnv* env) {
    jclass clazz = env->FindClass("OpenMaver");
    if (clazz == nullptr) {
        env->ExceptionClear();
        return;
    }
    jmethodID enable = env->GetStaticMethodID(clazz, "enableStandaloneDriver", "()V");
    if (enable != nullptr) {
        env->CallStaticVoidMethod(clazz, enable);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

// 在目标 JVM 内启动 OpenMaver。返回 0 表示成功。
jint startOpenMaver(JavaVM* vm, const char* options) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK || env == nullptr) {
            std::fputs("[OpenMaver] agent: 无法获取 JNIEnv\n", stderr);
            return JNI_ERR;
        }
    }

    // 可选：把核心类 jar 加入 bootstrap 搜索路径（agent 线程无 Java 栈帧，FindClass 走 bootstrap）
    char jarPath[4096];
    if (optionValue(options, "jar", jarPath, sizeof(jarPath))) {
        jvmtiEnv* jvmti = nullptr;
        if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) == JNI_OK && jvmti != nullptr) {
            jvmtiError err = jvmti->AddToBootstrapClassLoaderSearch(jarPath);
            if (err != JVMTI_ERROR_NONE) {
                char buf[256];
                std::snprintf(buf, sizeof(buf),
                              "[OpenMaver] agent: 加入 bootstrap 搜索失败 (err=%d) jar=%.180s\n",
                              static_cast<int>(err), jarPath);
                std::fputs(buf, stderr);
            }
        } else {
            std::fputs("[OpenMaver] agent: 未取得 JVMTI 环境，无法追加 jar\n", stderr);
        }
    } else {
        std::fputs("[OpenMaver] agent: 未提供 jar= 参数，将直接在目标 JVM 里查找 OpenMaver 类\n", stderr);
    }

    jclass clazz = env->FindClass("OpenMaver");
    if (clazz == nullptr) {
        std::fputs("[OpenMaver] agent: 未找到 OpenMaver 类（请用 jar= 指定核心类 jar）\n", stderr);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return JNI_ERR;
    }

    // 显式绑定 native 方法：不依赖 System.loadLibrary 的路径解析
    JNINativeMethod methods[] = {
        {const_cast<char*>("init"),       const_cast<char*>("()V"),                  reinterpret_cast<void*>(&Java_OpenMaver_init)},
        {const_cast<char*>("shutdown"),   const_cast<char*>("()V"),                  reinterpret_cast<void*>(&Java_OpenMaver_shutdown)},
        {const_cast<char*>("getVersion"), const_cast<char*>("()Ljava/lang/String;"), reinterpret_cast<void*>(&Java_OpenMaver_getVersion)},
    };
    if (env->RegisterNatives(clazz, methods, 3) != JNI_OK) {
        std::fputs("[OpenMaver] agent: RegisterNatives 失败\n", stderr);
        env->ExceptionClear();
        return JNI_ERR;
    }

    // 默认开启独立驱动线程；driver=false 时仅 start()（由 Forge 事件驱动）
    char driverOpt[16];
    const bool useDriver = !optionValue(options, "driver", driverOpt, sizeof(driverOpt))
                           || std::strcmp(driverOpt, "false") != 0;
    const char* method = useDriver ? "startWithDriver" : "start";

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    jmethodID entry = env->GetStaticMethodID(clazz, method, "()V");
    if (entry == nullptr) {
        std::fputs("[OpenMaver] agent: 未找到 OpenMaver 入口方法\n", stderr);
        env->ExceptionClear();
        return JNI_ERR;
    }
    env->CallStaticVoidMethod(clazz, entry);
    if (env->ExceptionCheck()) {
        std::fputs("[OpenMaver] agent: OpenMaver 启动异常\n", stderr);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return JNI_ERR;
    }

    // 可选：把游戏内 UI（HUD / ClickGUI）挂到游戏的类加载器并注册到 Forge 事件总线。
    // 只有提供 mcjar= 时才做；这样「直接注入」也能显示右下角「注入成功！！」。
    char mcJar[4096];
    if (optionValue(options, "mcjar", mcJar, sizeof(mcJar))) {
        char coreJar[4096];
        const char* core = optionValue(options, "jar", coreJar, sizeof(coreJar)) ? coreJar : "";
        std::fprintf(stdout, "[OpenMaver] agent: mcjar 已提供，corejar=%.240s\n", core);
        if (!installGameUi(env, mcJar, core)) {
            fallbackDriver(env);
        }
    } else {
        std::fputs("[OpenMaver] agent: 未提供 mcjar=，跳过游戏内 UI 注册\n", stderr);
    }

    return JNI_OK;
}

} // namespace

extern "C" {

// 通过 -agentlib/-agentpath 在启动期加载时：空操作。
OM_EXPORT JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved) {
    (void)vm;
    (void)options;
    (void)reserved;
    return JNI_OK;
}

// 通过 Dynamic Attach（jattach load / 自研注入器）在运行期加载时：启动 OpenMaver。
OM_EXPORT JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void* reserved) {
    (void)reserved;
    return startOpenMaver(vm, options);
}

// 卸载时清理（本骨架仅转发到核心 shutdown）。
OM_EXPORT JNIEXPORT void JNICALL Agent_OnUnload(JavaVM* vm) {
    (void)vm;
    // 核心状态的释放由 Java 侧 OpenMaver.stop() 负责，此处不做额外操作。
}

} // extern "C"
