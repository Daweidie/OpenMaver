// ---------------------------------------------------------------------------
// OpenMaver V2.0 —— 自研 JVM 运行期注入器（C++ / POSIX）
//
// 作用：把一个 **native agent（.so）** 注入到已经运行的 JVM（本骨架用于 MC 1.8.9）。
//       这与 jattach 的 `load <.so> true` 命令是同一件事，但此处自己实现
//       HotSpot 的 Dynamic Attach 协议，便于与 jattach 对比。
//
// 两种模式：
//   --re-self     自研实现：直接走 attach 协议（.attach_pid + SIGQUIT + unix socket）
//   --re-jattach  调用外部 jattach 可执行文件（代码最少）
//
// 用法：
//   openmaver-injector --re-self --pid <pid> --so <agent.so> [--options "jar=...,..."]
//   openmaver-injector --re-jattach --pid <pid> --so <agent.so> [--options "jar=..."]
//
// 与 jattach 的差距（自研版只覆盖“同用户 / 非容器 / HotSpot”这一常见子集）：
//   - 不做容器 namespace 切换（net/ipc/mnt）
//   - 不做 root -> 目标 euid/egid 的凭据切换
//   - 不支持 OpenJ9，仅实现 HotSpot 协议
//   - 仅实现 load 命令
// ---------------------------------------------------------------------------

#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <dirent.h>
#include <fcntl.h>
#include <signal.h>
#include <time.h>
#include <unistd.h>

#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char* kDefaultJattach = "jattach";

// ---------------------------------------------------------------------------
// 目标进程辅助
// ---------------------------------------------------------------------------

// 目标 JVM 的临时目录：优先读 /proc/<pid>/environ 里的 TMPDIR，否则 /tmp。
std::string targetTmpPath(pid_t pid) {
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/environ", static_cast<int>(pid));

    FILE* f = std::fopen(path, "rb");
    if (f != nullptr) {
        // environ 以 '\0' 分隔
        std::vector<char> buf(65536);
        const std::size_t n = std::fread(buf.data(), 1, buf.size(), f);
        std::fclose(f);
        std::size_t i = 0;
        while (i < n) {
            const char* entry = buf.data() + i;
            const std::size_t len = std::strlen(entry);
            if (std::strncmp(entry, "TMPDIR=", 7) == 0 && len > 7) {
                return std::string(entry + 7, len - 7);
            }
            i += len + 1;
        }
    }
    return "/tmp";
}

// 目标 JVM 是否已创建 Dynamic Attach socket
bool hasAttachSocket(pid_t pid, const std::string& tmpPath) {
    char path[4096];
    std::snprintf(path, sizeof(path), "%s/.java_pid%d", tmpPath.c_str(), static_cast<int>(pid));
    struct stat st;
    return stat(path, &st) == 0 && S_ISSOCK(st.st_mode);
}

// 触发 HotSpot 创建 Attach listener：写入 .attach_pid 文件并发送 SIGQUIT。
// 返回 0 表示 socket 已就绪。
int startAttachMechanism(pid_t pid, const std::string& tmpPath) {
    // 优先写目标进程当前目录，失败再退回 tmp 目录（与 jattach 行为一致）
    char path[4096];
    std::snprintf(path, sizeof(path), "/proc/%d/cwd/.attach_pid%d",
                  static_cast<int>(pid), static_cast<int>(pid));

    int fd = creat(path, 0660);
    if (fd == -1) {
        std::snprintf(path, sizeof(path), "%s/.attach_pid%d", tmpPath.c_str(), static_cast<int>(pid));
        fd = creat(path, 0660);
        if (fd == -1) {
            return -1;
        }
    }
    close(fd);

    kill(pid, SIGQUIT);

    // 以 20ms 递增等待，总超时约 500ms
    struct timespec ts = {0, 20000000L};
    int result;
    do {
        nanosleep(&ts, nullptr);
        result = hasAttachSocket(pid, tmpPath) ? 0 : -1;
    } while (result != 0 && (ts.tv_nsec += 20000000L) < 500000000L && kill(pid, 0) == 0);

    unlink(path);
    return result;
}

// 连接 Dynamic Attach unix socket
int connectAttachSocket(pid_t pid, const std::string& tmpPath) {
    int fd = socket(PF_UNIX, SOCK_STREAM, 0);
    if (fd == -1) {
        return -1;
    }

    struct sockaddr_un addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    std::snprintf(addr.sun_path, sizeof(addr.sun_path), "%s/.java_pid%d",
                  tmpPath.c_str(), static_cast<int>(pid));

    if (connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) == -1) {
        close(fd);
        return -1;
    }
    return fd;
}

// ---------------------------------------------------------------------------
// Attach 协议编解码（对应 HotSpot attachListener）
// ---------------------------------------------------------------------------

// 编码：protocol "1" \0 后跟以 '\0' 分隔的参数；第 4 个之后的参数用空格拼接。
int writeCommand(int fd, const std::vector<std::string>& args) {
    char buf[8192];
    char* const limit = buf + sizeof(buf);

    char* p = buf;
    *p++ = '1';
    *p++ = '\0';

    std::size_t cmdArgs = args.size();
    if (args.size() >= 2 && args[0] == "jcmd") {
        cmdArgs = 2;
    } else if (args.size() >= 4) {
        cmdArgs = 4;
    }

    for (std::size_t i = 0; i < args.size() && p < limit; ++i) {
        if (i >= cmdArgs) {
            p[-1] = ' ';
        }
        const std::size_t n = args[i].size();
        if (p + n + 1 > limit) {
            break;
        }
        std::memcpy(p, args[i].c_str(), n);
        p += n;
        *p++ = '\0';
    }
    for (std::size_t i = cmdArgs; i < 4 && p < limit; ++i) {
        *p++ = '\0';
    }

    for (char* q = buf; q < p;) {
        const ssize_t written = write(fd, q, static_cast<std::size_t>(p - q));
        if (written <= 0) {
            return -1;
        }
        q += written;
    }
    return 0;
}

// 读取响应：首行是命令返回码；load 命令需额外解析 Agent_OnAttach 的返回码。
int readResponse(int fd, const std::vector<std::string>& args) {
    char buf[8192];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    if (n <= 0) {
        std::fputs("读取 JVM 响应失败\n", stderr);
        return 1;
    }
    buf[n] = '\0';
    int result = std::atoi(buf);

    const bool isLoad = !args.empty() && args[0] == "load";
    std::size_t total = static_cast<std::size_t>(n);
    if (isLoad) {
        while (total < sizeof(buf) - 1) {
            const ssize_t r = read(fd, buf + total, sizeof(buf) - 1 - total);
            if (r <= 0) {
                break;
            }
            total += static_cast<std::size_t>(r);
        }
        buf[total] = '\0';

        if (result == 0 && total >= 2) {
            if (std::strncmp(buf + 2, "return code: ", 13) == 0) {
                result = std::atoi(buf + 15);          // JDK 9+
            } else if ((buf[2] >= '0' && buf[2] <= '9') || buf[2] == '-') {
                result = std::atoi(buf + 2);           // JDK 8
            } else {
                result = -1;                            // JDK 21+：非 0 即错误文案
            }
        }
        if (result != 0) {
            std::fputs(buf, stderr);
        } else {
            std::fputs(buf, stdout);
        }
    }
    return result;
}

// ---------------------------------------------------------------------------
// 自研注入 / jattach 转发
// ---------------------------------------------------------------------------

int injectSelf(pid_t pid, const std::string& soPath, const std::string& options) {
    const std::string tmpPath = targetTmpPath(pid);

    if (!hasAttachSocket(pid, tmpPath)) {
        if (startAttachMechanism(pid, tmpPath) != 0) {
            std::fprintf(stderr, "[injector] 无法启动目标的 attach 机制（请确认与目标同用户）\n");
            return 1;
        }
    }

    const int fd = connectAttachSocket(pid, tmpPath);
    if (fd == -1) {
        std::fprintf(stderr, "[injector] 无法连接 attach socket\n");
        return 1;
    }

    // 与 jattach 等价：load <绝对路径> true [options]
    std::vector<std::string> args;
    args.push_back("load");
    args.push_back(soPath);
    args.push_back("true");
    args.push_back(options);

    if (writeCommand(fd, args) != 0) {
        std::fprintf(stderr, "[injector] 写入命令失败\n");
        close(fd);
        return 1;
    }
    const int result = readResponse(fd, args);
    close(fd);
    return result;
}

int injectJattach(const std::string& jattach, pid_t pid,
                  const std::string& soPath, const std::string& options) {
    std::vector<std::string> argv;
    argv.push_back(jattach);
    argv.push_back(std::to_string(static_cast<int>(pid)));
    argv.push_back("load");
    argv.push_back(soPath);
    argv.push_back("true");
    if (!options.empty()) {
        argv.push_back(options);
    }

    std::vector<char*> cargv;
    for (std::size_t i = 0; i < argv.size(); ++i) {
        cargv.push_back(const_cast<char*>(argv[i].c_str()));
    }
    cargv.push_back(nullptr);

    std::fprintf(stdout, "[injector] jattach(");
    for (std::size_t i = 0; i < argv.size(); ++i) {
        std::fprintf(stdout, "%s%s", i ? " " : "", argv[i].c_str());
    }
    std::fprintf(stdout, ")\n");

    const pid_t child = fork();
    if (child == 0) {
        execvp(argv[0].c_str(), cargv.data());
        std::perror("execvp jattach");
        _exit(127);
    }
    if (child < 0) {
        std::perror("fork");
        return 1;
    }

    int status = 0;
    if (waitpid(child, &status, 0) < 0) {
        std::perror("waitpid");
        return 1;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 1;
}

// ---------------------------------------------------------------------------
// 目标进程嗅探（Linux /proc）
//
// 判定一个「像 MC 1.8.9」的候选：
//   1) 必须是与当前用户同 euid 的 Java 进程（动态 attach 只允许同用户）
//   2) 命令行命中 Minecraft / LaunchWrapper / Forge 等特征，按特征加权打分
//   3) 若目标已开过 attach socket，额外加分（说明 JVM attach 机制可用）
// ---------------------------------------------------------------------------

struct ProcCandidate {
    pid_t pid;
    int score;
    std::string desc;
};

bool isNumeric(const char* s) {
    if (s == nullptr || *s == '\0') {
        return false;
    }
    for (; *s != '\0'; ++s) {
        if (*s < '0' || *s > '9') {
            return false;
        }
    }
    return true;
}

std::string toLower(std::string s) {
    for (std::size_t i = 0; i < s.size(); ++i) {
        s[i] = static_cast<char>(std::tolower(static_cast<unsigned char>(s[i])));
    }
    return s;
}

bool contains(const std::string& haystack, const char* needle) {
    return haystack.find(needle) != std::string::npos;
}

// 读取 /proc/<pid>/cmdline，'\0' 替换为空格
bool readCmdline(pid_t pid, std::string& out) {
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/cmdline", static_cast<int>(pid));
    FILE* f = std::fopen(path, "rb");
    if (f == nullptr) {
        return false;
    }
    char buf[8192];
    const std::size_t n = std::fread(buf, 1, sizeof(buf) - 1, f);
    std::fclose(f);
    for (std::size_t i = 0; i < n; ++i) {
        if (buf[i] == '\0') {
            buf[i] = ' ';
        }
    }
    out.assign(buf, n);
    return n > 0;
}

// 进程可执行文件是否像 java/javaw（通过 /proc/<pid>/exe 判断）
bool isJavaProcess(pid_t pid) {
    char link[64];
    std::snprintf(link, sizeof(link), "/proc/%d/exe", static_cast<int>(pid));
    char target[4096];
    const ssize_t n = readlink(link, target, sizeof(target) - 1);
    if (n > 0) {
        target[n] = '\0';
        const char* base = std::strrchr(target, '/');
        base = (base != nullptr) ? base + 1 : target;
        if (std::strstr(base, "java") != nullptr) {
            return true;
        }
    }
    return false;
}

// 目标进程 euid（用于过滤其它用户的进程）
uid_t processUid(pid_t pid) {
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d", static_cast<int>(pid));
    struct stat st;
    return stat(path, &st) == 0 ? st.st_uid : static_cast<uid_t>(-1);
}

// 命令行特征打分：越高越像「游戏本体 JVM」而不是启动器
int scoreCmdline(const std::string& low) {
    int score = 0;
    if (contains(low, "net.minecraft.launchwrapper.launch")) score += 6;  // 1.8.9 默认主类
    if (contains(low, "net.minecraft.client.main.main"))    score += 6;  // 部分启动器/现代版
    if (contains(low, "minecraftforge") || contains(low, "net.minecraftforge")) score += 3;
    if (contains(low, "launchwrapper"))                     score += 2;
    if (contains(low, "minecraft"))                         score += 1;
    if (contains(low, "1.8.9"))                             score += 2;
    if (contains(low, "-djava.library.path"))               score += 1;
    if (contains(low, "openmaver"))                         score -= 100; // 排除自家
    return score;
}

// 目标是否已经开过 attach socket（默认 /tmp）
bool hasJavaPidSocket(pid_t pid) {
    char path[4096];
    std::snprintf(path, sizeof(path), "/tmp/.java_pid%d", static_cast<int>(pid));
    struct stat st;
    return stat(path, &st) == 0 && S_ISSOCK(st.st_mode);
}

// 收集所有候选（同用户 + Java 进程 + 命中 MC 特征），按分数从高到低排序
void collectCandidates(std::vector<ProcCandidate>& out) {
    out.clear();
    DIR* proc = opendir("/proc");
    if (proc == nullptr) {
        return;
    }
    const uid_t myUid = geteuid();
    const pid_t self = getpid();

    struct dirent* ent;
    while ((ent = readdir(proc)) != nullptr) {
        if (!isNumeric(ent->d_name)) {
            continue;
        }
        const pid_t pid = static_cast<pid_t>(std::atoi(ent->d_name));
        if (pid == self) {
            continue;
        }
        if (processUid(pid) != myUid) {
            continue;
        }
        if (!isJavaProcess(pid)) {
            continue;
        }
        std::string cmdline;
        if (!readCmdline(pid, cmdline)) {
            continue;
        }
        const std::string low = toLower(cmdline);
        int score = scoreCmdline(low);
        if (score <= 0) {
            continue;
        }
        if (hasJavaPidSocket(pid)) {
            score += 2;
        }
        ProcCandidate c;
        c.pid = pid;
        c.score = score;
        c.desc = cmdline;
        out.push_back(c);
    }
    closedir(proc);

    // 简单插入排序（候选很少），高分在前
    for (std::size_t i = 1; i < out.size(); ++i) {
        ProcCandidate key = out[i];
        std::size_t j = i;
        while (j > 0 && out[j - 1].score < key.score) {
            out[j] = out[j - 1];
            --j;
        }
        out[j] = key;
    }
}

pid_t findMinecraftPid() {
    std::vector<ProcCandidate> candidates;
    collectCandidates(candidates);
    return candidates.empty() ? -1 : candidates[0].pid;
}

void printCandidates() {
    std::vector<ProcCandidate> candidates;
    collectCandidates(candidates);
    if (candidates.empty()) {
        std::fputs("[injector] 未嗅探到 Minecraft 进程\n", stdout);
        return;
    }
    std::fprintf(stdout, "[injector] 嗅探到 %zu 个候选：\n", candidates.size());
    for (std::size_t i = 0; i < candidates.size(); ++i) {
        std::string d = candidates[i].desc;
        if (d.size() > 160) {
            d = d.substr(0, 157) + "...";
        }
        std::fprintf(stdout, "  %s pid=%-7d score=%-3d %s\n",
                     i == 0 ? "*" : " ", static_cast<int>(candidates[i].pid),
                     candidates[i].score, d.c_str());
    }
}

void printUsage() {
    std::fputs(
        "OpenMaver 自研注入器（C++）\n"
        "\n"
        "用法: openmaver-injector [模式] [选项]\n"
        "\n"
        "模式（二选一，默认 --re-self）:\n"
        "  --re-self            自研实现 HotSpot Dynamic Attach 协议\n"
        "  --re-jattach         调用外部 jattach 可执行文件\n"
        "  --re-jattch          --re-jattach 的兼容别名\n"
        "\n"
        "选项:\n"
        "  --find               嗅探并列出 MC 1.8.9 候选进程后退出（别名 --list）\n"
        "  --pid <pid>          目标 JVM（省略则自动嗅探 Minecraft 进程）\n"
        "  --so <path>          要注入的 native agent 库（默认 build/lib/libopenmaver_core.so）\n"
        "  --options <k=v,...>  传给 Agent_OnAttach 的参数（如 jar=.../openmaver-core.jar）\n"
        "  --mcjar <path>       含游戏内 UI 类的 jar（默认 build/openmaver-mc.jar），\n"
        "                       提供后注入时会注册 HUD/ClickGUI 到 Forge 事件总线\n"
        "  --jattach <path>     jattach 可执行文件（默认取 PATH 中的 jattach）\n"
        "  -h, --help           显示本帮助\n",
        stdout);
}

} // namespace

int main(int argc, char** argv) {
    bool useJattach = false;
    bool listOnly = false;
    pid_t pid = -1;
    std::string soPath = "build/lib/libopenmaver_core.so";
    std::string options = "";
    std::string jattach = kDefaultJattach;
    std::string mcJar = "build/openmaver-mc.jar";

    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        if (a == "-h" || a == "--help") {
            printUsage();
            return 0;
        } else if (a == "--find" || a == "--list") {
            listOnly = true;
        } else if (a == "--re-self") {
            useJattach = false;
        } else if (a == "--re-jattach" || a == "--re-jattch") {
            useJattach = true;
        } else if (a == "--pid" && i + 1 < argc) {
            pid = static_cast<pid_t>(std::atoi(argv[++i]));
        } else if (a == "--so" && i + 1 < argc) {
            soPath = argv[++i];
        } else if (a == "--options" && i + 1 < argc) {
            options = argv[++i];
        } else if (a == "--mcjar" && i + 1 < argc) {
            mcJar = argv[++i];
        } else if (a == "--jattach" && i + 1 < argc) {
            jattach = argv[++i];
        } else {
            std::fprintf(stderr, "[injector] 未知参数: %s\n", a.c_str());
            printUsage();
            return 2;
        }
    }

    if (listOnly) {
        printCandidates();
        return 0;
    }

    // 若存在游戏内 UI 的 jar，则作为 mcjar= 传给 agent（注册 HUD/ClickGUI）
    // 同时 driver=false：由 Forge 的 tick 事件驱动（避免与独立驱动线程双重驱动）
    if (!mcJar.empty()) {
        char mcResolved[4096];
        if (realpath(mcJar.c_str(), mcResolved) != nullptr) {
            std::string extra = std::string("mcjar=") + mcResolved + ",driver=false";
            options = options.empty() ? extra : (options + "," + extra);
        } else {
            std::fprintf(stderr,
                         "[injector] 提示: 未找到 mc jar(%s)，本次不会注册游戏内 UI\n"
                         "           （需要含 OpenMaverHud 等类的 jar，如 build/openmaver-mc.jar）\n",
                         mcJar.c_str());
        }
    }

    if (pid <= 0) {
        pid = findMinecraftPid();
        if (pid <= 0) {
            std::fputs("[injector] 未嗅探到 Minecraft 进程，请用 --pid 指定（可先 --list 查看）\n", stderr);
            return 2;
        }
        std::fprintf(stdout, "[injector] 嗅探到 Minecraft 进程 pid=%d\n", static_cast<int>(pid));
    }

    // 转成绝对路径：Dynamic Attach 的 load 以 true(绝对) 发送
    char resolved[4096];
    if (realpath(soPath.c_str(), resolved) == nullptr) {
        std::fprintf(stderr, "[injector] agent 库不存在: %s\n", soPath.c_str());
        return 2;
    }
    const std::string absSo = resolved;

    std::fprintf(stdout, "[injector] 模式=%s pid=%d so=%s\n",
                 useJattach ? "jattach" : "self", static_cast<int>(pid), absSo.c_str());

    if (options.find("jar=") == std::string::npos) {
        std::fputs("[injector] 警告: --options 未包含 jar=<openmaver-core.jar>，"
                   "agent 将找不到 OpenMaver 核心类\n", stderr);
    }

    const int result = useJattach
        ? injectJattach(jattach, pid, absSo, options)
        : injectSelf(pid, absSo, options);

    std::fprintf(stdout, "[injector] %s (rc=%d)\n", result == 0 ? "注入完成" : "注入失败", result);
    if (result != 0) {
        std::fputs("[injector] 排查:\n"
                   "  1) --options 必须含 jar=/绝对路径/openmaver-core.jar\n"
                   "  2) --so 指向 libopenmaver_core.so（jar 内 Agent_OnAttach）\n"
                   "  3) 目标须与当前用户相同，且未用 -XX:+DisableAttachMechanism 启动\n"
                   "  4) Flatpak/Snap 等沙箱命名空间下自研模式会失败，改用 --re-jattach\n"
                   "  5) 看目标进程控制台/stderr 里 [OpenMaver] agent: 开头的报错\n", stderr);
    }
    return result == 0 ? 0 : 1;
}
