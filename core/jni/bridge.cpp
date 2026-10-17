// ---------------------------------------------------------------------------
// OpenMaver V2.0 —— JNI 桥接层实现
//
// 职责：
//   1. 导出 Java 侧声明的 native 方法：init / shutdown / getVersion
//   2. 维护核心运行状态
//
// 约束：
//   - C++17
//   - 关闭异常与 RTTI（见 CMakeLists）
//   - 不使用智能指针（只用栈对象与 POD 全局状态）
//   - 字符串常量做简易 XOR 混淆，避免明文出现在二进制里
// ---------------------------------------------------------------------------

#include "bridge.h"

#include <cstddef>
#include <cstdio>

// ===========================================================================
// 简易 XOR 字符串混淆工具
//
// 思路：用 constexpr 构造函数在编译期把明文字面量异或成密文，
//       对象中只保留密文；运行时再用 decode() 还原到栈缓冲区。
//       强度很低，仅用于绕过 `strings` 之类的简单静态扫描。
// ===========================================================================
namespace obf {

// 异或密钥（改动此值即可让所有常量重新混淆）
constexpr char kKey = 0x5A;

// 编译期异或容器：N 为含结尾 '\0' 的字面量长度
template <std::size_t N>
class XorStr {
public:
    constexpr explicit XorStr(const char (&plain)[N]) : m_buf{} {
        for (std::size_t i = 0; i < N; ++i) {
            m_buf[i] = static_cast<char>(plain[i] ^ kKey);
        }
    }

    // 读取第 i 个密文字节
    char raw(std::size_t i) const { return m_buf[i]; }

    // 密文长度（含结尾 '\0'）
    static constexpr std::size_t length() { return N; }

private:
    char m_buf[N];
};

// 运行时把密文还原写入 out（调用方需保证 out 至少有 N 字节）
template <std::size_t N>
inline void decode(const XorStr<N>& s, char* out) {
    for (std::size_t i = 0; i < N; ++i) {
        out[i] = static_cast<char>(s.raw(i) ^ kKey);
    }
}

} // namespace obf

// ===========================================================================
// 内部状态与常量
// ===========================================================================
namespace {

// 所有需要输出的文案均为编译期密文
constexpr obf::XorStr<sizeof("[OpenMaver] Core initialized\n")> kMsgInit(
    "[OpenMaver] Core initialized\n");
constexpr obf::XorStr<sizeof("[OpenMaver] Core shutdown\n")> kMsgShutdown(
    "[OpenMaver] Core shutdown\n");
constexpr obf::XorStr<sizeof("2.0.0")> kVersion("2.0.0");

// 核心是否已初始化（单线程初始化，简单布尔即可）
bool g_initialized = false;

// 解密并输出一行到标准输出
template <std::size_t N>
void printDecoded(const obf::XorStr<N>& s) {
    char buf[N];
    obf::decode(s, buf);
    std::fputs(buf, stdout);
    std::fflush(stdout);
}

} // namespace

// ===========================================================================
// 核心内部接口实现
// ===========================================================================
namespace openmaver {

bool coreInit() {
    if (g_initialized) {
        // 幂等：重复初始化直接返回成功
        return true;
    }

    printDecoded(kMsgInit);
    g_initialized = true;
    return true;
}

void coreShutdown() {
    if (!g_initialized) {
        // 未初始化时调用为空操作
        return;
    }

    printDecoded(kMsgShutdown);
    g_initialized = false;
}

bool isInitialized() {
    return g_initialized;
}

} // namespace openmaver

// ===========================================================================
// JNI 导出实现
// ===========================================================================
extern "C" {

void JNICALL Java_OpenMaver_init(JNIEnv* env, jclass clazz) {
    // 本方法不需要使用 env / clazz，显式忽略以避免 -Wunused-parameter
    (void)env;
    (void)clazz;
    openmaver::coreInit();
}

void JNICALL Java_OpenMaver_shutdown(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    openmaver::coreShutdown();
}

jstring JNICALL Java_OpenMaver_getVersion(JNIEnv* env, jclass clazz) {
    (void)clazz;

    // 版本号同样以密文形式存放，运行时还原
    char buf[obf::XorStr<sizeof("2.0.0")>::length()];
    obf::decode(kVersion, buf);
    return env->NewStringUTF(buf);
}

} // extern "C"
