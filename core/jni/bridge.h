#ifndef OPENMAVER_CORE_JNI_BRIDGE_H
#define OPENMAVER_CORE_JNI_BRIDGE_H

// ---------------------------------------------------------------------------
// OpenMaver 核心本地库（libopenmaver_core）对外接口声明
//
// 该头文件同时被 JNI 桥接层和库内部实现使用。
// 注意：本库编译时关闭了异常（-fno-exceptions）与 RTTI（-fno-rtti），
//       JNI 边界绝不向 Java 层抛出 C++ 异常。
// ---------------------------------------------------------------------------

#include <jni.h>

// 导出符号宏：确保 JNI 函数在动态库中保持可见。
// 根 CMakeLists 使用了 -fvisibility=hidden，因此这里必须显式导出。
#if defined(_WIN32)
#  define OM_EXPORT __declspec(dllexport)
#else
#  define OM_EXPORT __attribute__((visibility("default")))
#endif

// JNI 函数必须用 C 链接包裹，否则 C++ 名称修饰（name mangling）会让
// Java 端在加载时找不到对应符号。
extern "C" {

// 对应 Java: private static native void init();
OM_EXPORT void JNICALL Java_OpenMaver_init(JNIEnv* env, jclass clazz);

// 对应 Java: private static native void shutdown();
OM_EXPORT void JNICALL Java_OpenMaver_shutdown(JNIEnv* env, jclass clazz);

// 对应 Java: private static native String getVersion();
OM_EXPORT jstring JNICALL Java_OpenMaver_getVersion(JNIEnv* env, jclass clazz);

} // extern "C"

// ---------------------------------------------------------------------------
// 纯 C++ 内部接口（供本库其它翻译单元复用，不直接暴露给 Java）
// ---------------------------------------------------------------------------
namespace openmaver {

// 初始化核心。可重复调用，第二次及以后为幂等的空操作。
// 返回 true 表示初始化成功。
bool coreInit();

// 关闭核心并释放资源。未初始化时调用为空操作。
void coreShutdown();

// 查询当前是否已初始化。
bool isInitialized();

} // namespace openmaver

#endif // OPENMAVER_CORE_JNI_BRIDGE_H
