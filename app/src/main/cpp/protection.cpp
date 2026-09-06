#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <pthread.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <linux/time.h>
#include <time.h>
#include <elf.h>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <dirent.h>
#include <android/log.h>
#include <sys/uio.h>
#include <errno.h>
#include <unwind.h>
#include <sys/syscall.h>

#define LOG_TAG "ADFXCBNM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static volatile sig_atomic_t g_selfTraced = 0;

// Forward declarations
static void safeKill();

// ============================================================
// 检测目标库名特征列表
// ============================================================
static const char *HOOK_LIBS[] = {
    "XposedBridge", "edxp", "sandhook", "libxposed", "libsubstrate",
    "libwhale", "libdreamland", "edxposed", "twist",
    "epic", "dexposed", "poseidon", NULL
};
static const char *FRIDA_LIBS[] = {
    "frida", "gum-js-loop", "gmain", "linjector", "frida-agent",
    "frida-gadget", "gdbus", "frida-helper", "linjector",
    "gum-heap", "frida-agent-main", NULL
};
static const char *ROOT_PATHS[] = {
    "/system/bin/su", "/system/xbin/su", "/sbin/su",
    "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
    "/su/bin/su", "/system/sd/xbin/su", "/system/bin/.ext/.su",
    "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
    "/data/adb/magisk", "/sbin/.magisk",
    "/system/sbin/su", "/vendor/bin/su", "/vendor/xbin/su",
    "/odm/bin/su", "/product/bin/su", NULL
};
static const char *ROOT_APPS[] = {
    "com.topjohnwu.magisk", "com.koushikdutta.superuser",
    "com.noshufou.android.su", "com.noshufou.android.su.elite",
    "eu.chainfire.supersu", "com.yellowes.su",
    "com.devadvance.rootcloak", "com.saurik.substrate",
    "com.formyhm.hiderodule", "io.github.vvb2060.magisk",
    "me.weishu.exp", "org.edxp.manager", "org.lsposed.manager",
    "io.github.lsposed.manager", "me.weishu.kernelsu",
    "me.bmax.apatch", "io.github.huskydev.kernel_su", NULL
};
static const char *EMU_FILES[] = {
    "/dev/qemu_pipe", "/dev/socket/qemud",
    "/system/lib/libc_malloc_debug_qemu.so", "/sys/qemu_trace",
    "/system/bin/qemu-pros", "/dev/socket/genyd",
    "/dev/socket/baseband_genyd", "/proc/tty/drivers",
    "/dev/socket/goldfish", "/dev/goldfish_pipe",
    "/system/bin/microemu", NULL
};
static const char *MAGISK_FILES[] = {
    "/sbin/.magisk", "/data/adb/magisk", "/data/adb/magisk.img",
    "/data/adb/magisk.db", "/data/adb/magisk",
    "/cache/.disable_magisk", "/dev/.magisk.unblock",
    "/data/adb/modules", NULL
};

// ============================================================
// 工具函数
// ============================================================
static bool stringEndsWith(const char *str, const char *suffix) {
    if (!str || !suffix) return false;
    size_t strLen = strlen(str), sufLen = strlen(suffix);
    if (sufLen > strLen) return false;
    return strcmp(str + strLen - sufLen, suffix) == 0;
}

static bool fileExists(const char *path) {
    struct stat st;
    return stat(path, &st) == 0;
}

static bool readSmallFile(const char *path, char *buf, int maxLen) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return false;
    ssize_t len = read(fd, buf, maxLen - 1);
    close(fd);
    if (len <= 0) return false;
    buf[len] = '\0';
    return true;
}

// 读取 maps 到字符串（带缓存机制）
static pthread_mutex_t g_mapsMutex = PTHREAD_MUTEX_INITIALIZER;
static char *g_mapsBuf = NULL;
static int g_mapsLen = 0;
static time_t g_mapsTime = 0;

static const char *readMapsCached() {
    time_t now = time(NULL);
    pthread_mutex_lock(&g_mapsMutex);
    if (g_mapsBuf != NULL && (now - g_mapsTime) < 2) {
        pthread_mutex_unlock(&g_mapsMutex);
        return g_mapsBuf;
    }
    pthread_mutex_unlock(&g_mapsMutex);

    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return NULL;
    char *buf = (char *)malloc(262144);
    if (!buf) { close(fd); return NULL; }
    ssize_t total = 0;
    ssize_t n;
    while ((n = read(fd, buf + total, 262144 - total - 1)) > 0) total += n;
    close(fd);
    if (total <= 0) { free(buf); return NULL; }
    buf[total] = '\0';

    pthread_mutex_lock(&g_mapsMutex);
    if (g_mapsBuf) free(g_mapsBuf);
    g_mapsBuf = buf;
    g_mapsLen = (int)total;
    g_mapsTime = now;
    pthread_mutex_unlock(&g_mapsMutex);
    return buf;
}

// ============================================================
// 防调试检测（7种方法）
// ============================================================

// 方法1: TracerPid 检测（libc读取）
static bool antiDebugTracerPid() {
    char buf[512];
    if (!readSmallFile("/proc/self/status", buf, sizeof(buf))) return false;
    char *t = strstr(buf, "TracerPid:");
    if (t) { int pid = atoi(t + 10); return pid != 0; }
    return false;
}

// 方法2: 进程状态检测
static bool antiDebugState() {
    char buf[512];
    if (!readSmallFile("/proc/self/status", buf, sizeof(buf))) return false;
    return strstr(buf, "State:") && strstr(buf, "t (tracing stop)");
}

// 方法3: PTRACE_TRACEME 检测
static bool antiDebugPtrace() {
    if (g_selfTraced) return false;
    return ptrace(PTRACE_TRACEME, 0, 0, 0) == -1;
}

// 方法4: JDWP端口检测
static bool antiDebugJdwp() {
    char buf[4096];
    if (!readSmallFile("/proc/net/tcp", buf, sizeof(buf))) return false;
    char *pos = buf;
    while ((pos = strstr(pos, "0055")) != NULL) {
        if (pos == buf || *(pos - 1) == ' ' || *(pos - 1) == '\n') return true;
        pos++;
    }
    if (!readSmallFile("/proc/net/tcp6", buf, sizeof(buf))) return false;
    pos = buf;
    while ((pos = strstr(pos, "0055")) != NULL) {
        if (pos == buf || *(pos - 1) == ' ' || *(pos - 1) == '\n') return true;
        pos++;
    }
    return false;
}

// 方法5: 时间差检测（检测单步调试/断点）
static bool antiDebugTiming() {
    struct timespec t1, t2;
    clock_gettime(CLOCK_MONOTONIC, &t1);
    volatile int d = 0;
    for (int i = 0; i < 1000; i++) d += i;
    clock_gettime(CLOCK_MONOTONIC, &t2);
    long diff = (t2.tv_sec - t1.tv_sec) * 1000000000L + (t2.tv_nsec - t1.tv_nsec);
    return diff > 10000000L;
}

// 方法6: 文件描述符检测（debuggerd管道）
static bool antiDebugFdCheck() {
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) return false;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char linkPath[256], target[256];
        snprintf(linkPath, sizeof(linkPath), "/proc/self/fd/%s", entry->d_name);
        ssize_t len = readlink(linkPath, target, sizeof(target) - 1);
        if (len > 0) {
            target[len] = '\0';
            if (strstr(target, "pipe:[") && strstr(target, "debuggerd")) {
                closedir(dir); return true;
            }
        }
    }
    closedir(dir);
    return false;
}

// 方法7: syscall级TracerPid检测（绕过libc hook）
static ssize_t sysRead(int fd, void *buf, size_t count) {
    return syscall(__NR_read, fd, buf, count);
}
static int sysOpen(const char *path) {
    return (int)syscall(__NR_openat, AT_FDCWD, path, O_RDONLY);
}
static int sysClose(int fd) {
    return (int)syscall(__NR_close, fd);
}
static bool sysReadFile(const char *path, char *buf, int maxLen) {
    int fd = sysOpen(path);
    if (fd < 0) return false;
    ssize_t len = sysRead(fd, buf, maxLen - 1);
    sysClose(fd);
    if (len <= 0) return false;
    buf[len] = '\0';
    return true;
}
static bool antiDebugTracerPidSyscall() {
    char buf[512];
    if (!sysReadFile("/proc/self/status", buf, sizeof(buf))) return false;
    char *t = strstr(buf, "TracerPid:");
    if (t) { int pid = atoi(t + 10); return pid != 0; }
    return false;
}
static bool antiDebugStateSyscall() {
    char buf[512];
    if (!sysReadFile("/proc/self/status", buf, sizeof(buf))) return false;
    return strstr(buf, "State:") && strstr(buf, "t (tracing stop)");
}

// ============================================================
// ROOT检测（6种方法）
// ============================================================
static bool rootCheckBinary() {
    for (int i = 0; ROOT_PATHS[i]; i++) {
        if (fileExists(ROOT_PATHS[i])) return true;
    }
    return false;
}
static bool rootCheckApps() {
    FILE *fp = popen("pm list packages 2>/dev/null", "r");
    if (!fp) return false;
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        for (int i = 0; ROOT_APPS[i]; i++) {
            if (strstr(line, ROOT_APPS[i])) { pclose(fp); return true; }
        }
    }
    pclose(fp);
    return false;
}
static bool rootCheckWritableSystem() {
    struct stat st;
    return stat("/system", &st) == 0 && (st.st_mode & S_IWOTH) != 0;
}
static bool rootCheckMagisk() {
    for (int i = 0; MAGISK_FILES[i]; i++) {
        if (fileExists(MAGISK_FILES[i])) return true;
    }
    return false;
}
static bool rootCheckProps() {
    char buf[512];
    if (!readSmallFile("/system/build.prop", buf, sizeof(buf))) return false;
    return strstr(buf, "test-keys") != NULL;
}
static bool rootCheckSuEnv() {
    return getenv("PATH") != NULL && strstr(getenv("PATH"), "/su/bin") != NULL;
}

// ============================================================
// 防Hook检测（8种方法）
// ============================================================
static bool hookCheckMaps() {
    const char *maps = readMapsCached();
    if (!maps) return false;
    for (int i = 0; HOOK_LIBS[i]; i++) {
        if (strstr(maps, HOOK_LIBS[i])) return true;
    }
    return false;
}
static bool hookCheckFridaMaps() {
    const char *maps = readMapsCached();
    if (!maps) return false;
    for (int i = 0; FRIDA_LIBS[i]; i++) {
        if (strstr(maps, FRIDA_LIBS[i])) return true;
    }
    return false;
}
static bool hookCheckFridaPort() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;
    struct sockaddr_in addr = {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27042);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bool found = (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    close(sock);
    return found;
}
static bool hookCheckFridaPort27043() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;
    struct sockaddr_in addr = {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27043);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bool found = (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    close(sock);
    return found;
}

// 检测 libc 函数是否被 inline hook（ARM64指令级检测）
static bool isFunctionHooked(const char *funcName) {
    void *addr = dlsym(RTLD_DEFAULT, funcName);
    if (!addr) return false;
#if defined(__aarch64__)
    unsigned char *p = (unsigned char *)addr;
    unsigned int instr = *(unsigned int *)p;
    // ARM64 B指令: 0001_01xx_xxxx_xxxx (0x14000000)
    if ((instr & 0xFC000000) == 0x14000000) return true;
    // ARM64 BL指令: 1001_01xx_xxxx_xxxx (0x94000000)
    if ((instr & 0xFC000000) == 0x94000000) return true;
    // ARM64 BR Xn: 1101_0110_0001_1111_0000_00xx_xxx0_0000
    if ((instr & 0xFFFFFC1F) == 0xD61F0000) return true;
    // ARM64 BLR Xn: 1101_0110_0011_1111_0000_00xx_xxx0_0000
    if ((instr & 0xFFFFFC1F) == 0xD63F0000) return true;
    // Frida hook pattern: ldr x16/x17, #8; br x16/x17
    if (instr == 0x58000050 || instr == 0x58000070) {
        unsigned int next = ((unsigned int *)p)[1];
        if (next == 0xD61F0200 || next == 0xD61F0220) return true;
    }
#endif
    return false;
}
static bool hookCheckFunctionPrologue() {
    const char *funcs[] = {
        "open", "read", "write", "close", "mmap", "mprotect",
        "execve", "fork", "ptrace", "fopen", "fgets", "readlink",
        "stat", "lseek", "ioctl", "opendir", "readdir", "closedir",
        "openat", "fstat", "access", "dlopen", "dlsym", "syscall",
        "getpid", "getppid", "kill", "pipe", "socket", "connect",
        NULL
    };
    for (int i = 0; funcs[i]; i++) {
        if (isFunctionHooked(funcs[i])) return true;
    }
    return false;
}
static bool hookCheckXposed() {
    const char *maps = readMapsCached();
    if (!maps) return false;
    return strstr(maps, "XposedBridge") || strstr(maps, "edxp") ||
           strstr(maps, "sandhook") || strstr(maps, "libxposed") ||
           strstr(maps, "edxposed");
}
static bool scanMemoryForHookPatterns() {
    const char *maps = readMapsCached();
    if (!maps) return false;
    return strstr(maps, "frida") || strstr(maps, "gum-js") ||
           strstr(maps, "linjector") || strstr(maps, "libgadget") ||
           strstr(maps, "xposed") || strstr(maps, "lsposed") ||
           strstr(maps, "riru") || strstr(maps, "zygisk");
}
static bool detectLibcHook() {
    char buf1[512], buf2[512];
    bool syscallOk = sysReadFile("/proc/self/status", buf1, sizeof(buf1));
    bool libcOk = readSmallFile("/proc/self/status", buf2, sizeof(buf2));
    if (syscallOk && !libcOk) return true;
    if (syscallOk && libcOk && memcmp(buf1, buf2, 64) != 0) return true;
    return false;
}

// ============================================================
// 模拟器检测（5种方法）
// ============================================================
static bool emuCheckFiles() {
    for (int i = 0; EMU_FILES[i]; i++) {
        if (fileExists(EMU_FILES[i])) return true;
    }
    return false;
}
static bool emuCheckProps() {
    char buf[512];
    if (!readSmallFile("/system/build.prop", buf, sizeof(buf))) return false;
    return strstr(buf, "goldfish") || strstr(buf, "ranchu") || strstr(buf, "emulator");
}
static bool emuCheckCpu() {
    char buf[512];
    if (!readSmallFile("/proc/cpuinfo", buf, sizeof(buf))) return false;
    return strstr(buf, "goldfish") || strstr(buf, "ranchu") ||
           strstr(buf, "Common KVM") ||
           strstr(buf, "hyperv");
}
static bool emuCheckQemu() {
    char buf[256];
    if (!readSmallFile("/proc/tty/drivers", buf, sizeof(buf))) return false;
    return strstr(buf, "goldfish") || strstr(buf, "msm_serial");
}
static bool emuCheckHardware() {
    char buf[512];
    if (!readSmallFile("/proc/cpuinfo", buf, sizeof(buf))) return false;
    return strstr(buf, "QEMU") || strstr(buf, "VirtualBox") || strstr(buf, "VMware");
}

// ============================================================
// 防注入保护（3种方法）
// ============================================================
static bool injectCheckMaps() {
    const char *maps = readMapsCached();
    if (!maps) return false;
    const char *suspicious[] = {"/data/local/tmp/frida", "/data/local/tmp/re.frida", "/data/local/tmp/linjector", "/data/local/tmp/xposed", NULL};
    for (int i = 0; suspicious[i]; i++) {
        if (strstr(maps, suspicious[i])) return true;
    }
    return false;
}
static bool injectCheckFd() {
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) return false;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char linkPath[256], target[256];
        snprintf(linkPath, sizeof(linkPath), "/proc/self/fd/%s", entry->d_name);
        ssize_t len = readlink(linkPath, target, sizeof(target) - 1);
        if (len > 0) {
            target[len] = '\0';
            if (strstr(target, "/data/local/tmp") ||
                (strstr(target, "memfd:") && strstr(target, "frida"))) {
                closedir(dir); return true;
            }
        }
    }
    closedir(dir);
    return false;
}
static bool injectCheckStackTrace() {
    char buf[4096];
    int fd = open("/proc/self/stack", O_RDONLY);
    if (fd < 0) return false;
    ssize_t len = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (len <= 0) return false;
    buf[len] = '\0';
    return strstr(buf, "gum-js-loop") || strstr(buf, "frida");
}

// ============================================================
// 防内存Dump（4种方法）
// ============================================================
static bool dumpCheckPtrace() {
    if (g_selfTraced) return false;
    return ptrace(PTRACE_TRACEME, 0, 0, 0) == -1;
}
static bool dumpCheckMaps() {
    const char *maps = readMapsCached();
    if (!maps) return false;
    const char *p = maps;
    while (*p) {
        if (strstr(p, "r-xp") && strstr(p, "/data/local/tmp")) return true;
        const char *nl = strchr(p, '\n');
        if (!nl) break;
        p = nl + 1;
    }
    return false;
}
static bool dumpCheckMemAccess() {
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) return false;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char linkPath[256], target[256];
        snprintf(linkPath, sizeof(linkPath), "/proc/self/fd/%s", entry->d_name);
        ssize_t len = readlink(linkPath, target, sizeof(target) - 1);
        if (len > 0) {
            target[len] = '\0';
            if (strstr(target, "/proc/") && strstr(target, "/mem")) {
                closedir(dir); return true;
            }
        }
    }
    closedir(dir);
    return false;
}
static bool dumpCheckProcessStatus() {
    char buf[512];
    if (!readSmallFile("/proc/self/status", buf, sizeof(buf))) return false;
    char *t = strstr(buf, "Threads:");
    if (t) {
        int threads = atoi(t + 8);
        if (threads > 250) return true;
    }
    return false;
}

// ============================================================
// 防代理检测（4种方法）
// ============================================================
static bool proxyCheckHttp() {
    char buf[256];
    if (!readSmallFile("/system/build.prop", buf, sizeof(buf))) return false;
    return strstr(buf, "http.proxyHost") || strstr(buf, "http.proxyPort");
}
static bool proxyCheckEnv() {
    return getenv("http_proxy") || getenv("https_proxy") ||
           getenv("HTTP_PROXY") || getenv("HTTPS_PROXY") ||
           getenv("proxy_host") || getenv("proxy_port");
}
static bool proxyCheckPort() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;
    struct sockaddr_in addr = {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(8080);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bool found = (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    close(sock);
    if (found) return true;
    addr.sin_port = htons(3128);
    sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;
    found = (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    close(sock);
    return found;
}
static bool proxyCheckSystemProperties() {
    char buf[256];
    FILE *fp = popen("getprop http.proxyHost 2>/dev/null", "r");
    if (fp) {
        if (fgets(buf, sizeof(buf), fp) != NULL) {
            pclose(fp);
            if (strlen(buf) > 1) return true;
        }
        pclose(fp);
    }
    return false;
}

// ============================================================
// 完整性校验
// ============================================================
static bool integrityCheckSignature(const char *apkPath) {
    if (!apkPath) return false;
    char cmd[512];
    snprintf(cmd, sizeof(cmd), "pm path %s 2>/dev/null", apkPath);
    FILE *fp = popen(cmd, "r");
    if (!fp) return false;
    char result[512];
    bool valid = false;
    if (fgets(result, sizeof(result), fp) != NULL) {
        valid = (strstr(result, "package:") != NULL);
    }
    pclose(fp);
    return valid;
}
static bool integrityCheckDex(const char *dexPath) {
    if (!dexPath) return false;
    int fd = open(dexPath, O_RDONLY);
    if (fd < 0) return false;
    unsigned char magic[8];
    ssize_t n = read(fd, magic, 8);
    close(fd);
    return n == 8 && memcmp(magic, "dex\n035\0", 8) == 0;
}

// ============================================================
// 速度检测
// ============================================================
static bool speedCheck() {
    struct timespec t1, t2;
    clock_gettime(CLOCK_MONOTONIC, &t1);
    volatile int d = 0;
    for (int i = 0; i < 100000; i++) d += i * i;
    clock_gettime(CLOCK_MONOTONIC, &t2);
    long diff = (t2.tv_sec - t1.tv_sec) * 1000000000L + (t2.tv_nsec - t1.tv_nsec);
    return diff > 200000000L;
}

// ============================================================
// 多开检测（2种方法）
// ============================================================
static bool multiCheckSo() {
    const char *maps = readMapsCached();
    if (!maps) return false;
    int count = 0;
    const char *p = maps;
    while ((p = strstr(p, ".so")) != NULL) { count++; p += 3; }
    return count > 500;
}
static bool multiCheckThread() {
    DIR *dir = opendir("/proc/self/task");
    if (!dir) return false;
    int threadCount = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] != '.') threadCount++;
    }
    closedir(dir);
    return threadCount > 200;
}
static bool multiCheck() {
    return multiCheckSo() || multiCheckThread();
}

// ============================================================
// SSL/检测（系统属性）
// ============================================================
static bool sslCheck() {
    char buf[512];
    if (!readSmallFile("/system/build.prop", buf, sizeof(buf))) return false;
    return strstr(buf, "ro.build.tags=test-keys") ||
           strstr(buf, "ro.build.type=userdebug") ||
           strstr(buf, "ro.build.type=eng") ||
           strstr(buf, "ro.secure=0") ||
           strstr(buf, "ro.debuggable=1");
}

// ============================================================
// 代码注入检测
// ============================================================
static bool codeInjectCheck() {
    const char *maps = readMapsCached();
    if (!maps) return false;
    const char *p = maps;
    while (*p) {
        if (strstr(p, "rwxp")) return true;
        if (strstr(p, "rw-p") && strstr(p, "/tmp/")) return true;
        const char *nl = strchr(p, '\n');
        if (!nl) break;
        p = nl + 1;
    }
    return false;
}

// ============================================================
// Magisk检测（3种方法）
// ============================================================
static bool magiskCheckFiles() {
    return fileExists("/sbin/.magisk") || fileExists("/data/adb/magisk") ||
           fileExists("/data/adb/magisk.img") || fileExists("/data/adb/magisk.db") ||
           fileExists("/data/adb/modules") || fileExists("/cache/.disable_magisk");
}
static bool magiskCheckZygisk() {
    const char *maps = readMapsCached();
    if (!maps) return false;
    return strstr(maps, "zygisk") != NULL;
}
static bool magiskCheckHide() {
    char buf[256];
    if (!readSmallFile("/proc/self/mountinfo", buf, sizeof(buf))) return false;
    return strstr(buf, "magisk") != NULL || strstr(buf, "core/mirror") != NULL;
}
static bool magiskCheck() {
    return magiskCheckFiles() || magiskCheckZygisk() || magiskCheckHide();
}

// ============================================================
// 运行时保护（自身代码完整性）
// ============================================================
static unsigned int g_codeChecksum = 0;
static bool g_checksumInitialized = false;

static unsigned int calcElfSectionChecksum(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    unsigned int checksum = 0;
    unsigned char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            checksum = checksum * 31 + buf[i];
        }
    }
    close(fd);
    return checksum;
}
static void initCodeChecksum() {
    if (g_checksumInitialized) return;
    g_codeChecksum = calcElfSectionChecksum("/proc/self/exe");
    g_checksumInitialized = true;
}
static bool runtimeCheckCodeIntegrity() {
    initCodeChecksum();
    if (g_codeChecksum == 0) return false;
    unsigned int current = calcElfSectionChecksum("/proc/self/exe");
    return current != g_codeChecksum;
}

// ============================================================
// 内存保护（SO区段校验）
// ============================================================
static unsigned int g_memChecksum = 0;
static bool g_memChecksumInit = false;
static time_t g_lastMemCheck = 0;

static unsigned int calcMemRegionChecksum() {
    const char *maps = readMapsCached();
    if (!maps) return 0;
    unsigned int checksum = 0;
    const char *p = maps;
    while (*p) {
        if (strstr(p, "r-xp") && strstr(p, ".so") && !strstr(p, "libsecurity_check.so")) {
            const char *nl = strchr(p, '\n');
            size_t n = nl ? (size_t)(nl - p) : strlen(p);
            for (size_t i = 0; i < n; i++) checksum = checksum * 31 + (unsigned char)p[i];
        }
        const char *nl = strchr(p, '\n');
        if (!nl) break;
        p = nl + 1;
    }
    return checksum;
}
static void initMemChecksum() {
    if (g_memChecksumInit) return;
    g_memChecksum = calcMemRegionChecksum();
    g_memChecksumInit = true;
    g_lastMemCheck = time(NULL);
}
static bool memoryCheckTampering() {
    initMemChecksum();
    if (g_memChecksum == 0) return false;
    time_t now = time(NULL);
    if (now - g_lastMemCheck < 5) return false;
    g_lastMemCheck = now;
    unsigned int current = calcMemRegionChecksum();
    return current != g_memChecksum;
}

// ============================================================
// 文件访问控制
// ============================================================
static bool fileCheckUnauthorizedAccess() {
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) return false;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char linkPath[256], target[256];
        snprintf(linkPath, sizeof(linkPath), "/proc/self/fd/%s", entry->d_name);
        ssize_t len = readlink(linkPath, target, sizeof(target) - 1);
        if (len > 0) {
            target[len] = '\0';
            if (strstr(target, "/data/local/tmp")) {
                closedir(dir); return true;
            }
        }
    }
    closedir(dir);
    return false;
}

// ============================================================
// 自身SO完整性校验
// ============================================================
static unsigned int g_selfSoChecksum = 0;
static bool g_selfSoChecksumInit = false;

static unsigned int calcSelfSoChecksum() {
    const char *maps = readMapsCached();
    if (!maps) return 0;
    unsigned int checksum = 0;
    const char *p = maps;
    while (*p) {
        if (strstr(p, "libsecurity_check.so") && strstr(p, "r-xp")) {
            const char *nl = strchr(p, '\n');
            size_t n = nl ? (size_t)(nl - p) : strlen(p);
            for (size_t i = 0; i < n; i++) checksum = checksum * 31 + (unsigned char)p[i];
        }
        const char *nl = strchr(p, '\n');
        if (!nl) break;
        p = nl + 1;
    }
    return checksum;
}
static void initSelfSoChecksum() {
    if (g_selfSoChecksumInit) return;
    g_selfSoChecksum = calcSelfSoChecksum();
    g_selfSoChecksumInit = true;
}
static bool selfIntegrityCheck() {
    initSelfSoChecksum();
    if (g_selfSoChecksum == 0) return false;
    unsigned int current = calcSelfSoChecksum();
    return current != g_selfSoChecksum;
}

// ============================================================
// 环境检测增强
// ============================================================
static bool envCheckTestKeys() {
    char buf[256];
    if (!readSmallFile("/system/build.prop", buf, sizeof(buf))) return false;
    return strstr(buf, "test-keys") != NULL;
}
static bool envCheckSelinux() {
    char buf[256];
    if (!readSmallFile("/sys/fs/selinux/enforce", buf, sizeof(buf))) return false;
    return buf[0] == '0';
}
static bool envCheckPartitions() {
    return fileExists("/system/etc/init.d") || fileExists("/data/local/userinit.sh");
}

// ============================================================
// 综合检测函数
// ============================================================
static bool antiDebugEnhanced() {
    if (antiDebugTracerPid()) return true;
    if (antiDebugState()) return true;
    if (antiDebugTracerPidSyscall()) return true;
    if (antiDebugStateSyscall()) return true;
    return false;
}

static bool hookCheckEnhanced() {
    if (hookCheckMaps()) return true;
    if (hookCheckFridaMaps()) return true;
    if (hookCheckFridaPort()) return true;
    if (hookCheckFridaPort27043()) return true;
    if (hookCheckXposed()) return true;
    if (hookCheckFunctionPrologue()) return true;
    if (scanMemoryForHookPatterns()) return true;
    if (detectLibcHook()) return true;
    return false;
}

// ============================================================
// 崩溃日志采集（native signal handler）
// ============================================================
static char g_crashLogPath[512] = {0};
static volatile sig_atomic_t g_crashLogReady = 0;

struct BacktraceState {
    void **current;
    void **end;
};

static _Unwind_Reason_Code unwindCallback(struct _Unwind_Context *ctx, void *arg) {
    BacktraceState *state = (BacktraceState *)arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc) {
        if (state->current == state->end) return _URC_END_OF_STACK;
        *state->current++ = (void *)pc;
    }
    return _URC_NO_REASON;
}

static size_t captureBacktrace(void **buffer, size_t max) {
    BacktraceState state = {buffer, buffer + max};
    _Unwind_Backtrace(unwindCallback, &state);
    return state.current - buffer;
}

static const char *sigName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        case SIGTRAP: return "SIGTRAP";
        default: return "SIG";
    }
}

static void crashHandler(int sig, siginfo_t *info, void *context) {
    uintptr_t pc = 0, lr = 0, sp = 0, fault = 0;
#if defined(__aarch64__)
    ucontext_t *uc = (ucontext_t *)context;
    if (uc) {
        pc = uc->uc_mcontext.pc;
        lr = uc->uc_mcontext.regs[30];
        sp = uc->uc_mcontext.sp;
        fault = uc->uc_mcontext.fault_address;
    }
#elif defined(__arm__)
    ucontext_t *uc = (ucontext_t *)context;
    if (uc) {
        pc = uc->uc_mcontext.arm_pc;
        lr = uc->uc_mcontext.arm_lr;
        sp = uc->uc_mcontext.arm_sp;
        fault = uc->uc_mcontext.fault_address;
    }
#else
    (void)context;
#endif
    if (info) fault = (uintptr_t)info->si_addr;
    int fd = -1;
    if (g_crashLogReady && g_crashLogPath[0]) {
        fd = open(g_crashLogPath, O_WRONLY | O_CREAT | O_APPEND, 0644);
    }
    __android_log_print(ANDROID_LOG_ERROR, "ADFXCBNM_CRASH",
                        "signal=%d(%s) pid=%d tid=%d pc=0x%llx lr=0x%llx sp=0x%llx fault=0x%llx",
                        sig, sigName(sig), (int)getpid(), (int)gettid(),
                        (unsigned long long)pc, (unsigned long long)lr,
                        (unsigned long long)sp, (unsigned long long)fault);
    char buf[384];
    if (fd >= 0) {
        int len = snprintf(buf, sizeof(buf),
            "\n[NATIVE CRASH] time=%lld pid=%d tid=%d\nsignal=%d(%s)\npc=0x%llx\nlr=0x%llx\nsp=0x%llx\nfault=0x%llx\nbacktrace:\n",
            (long long)time(NULL), (int)getpid(), (int)gettid(),
            sig, sigName(sig),
            (unsigned long long)pc, (unsigned long long)lr,
            (unsigned long long)sp, (unsigned long long)fault);
        if (len > 0) write(fd, buf, (size_t)len);
    }
    void *bt[32];
    size_t n = captureBacktrace(bt, 32);
    for (size_t i = 0; i < n; i++) {
        __android_log_print(ANDROID_LOG_ERROR, "ADFXCBNM_CRASH", "  #%02zu pc=0x%llx",
                            i, (unsigned long long)(uintptr_t)bt[i]);
        if (fd >= 0) {
            int len = snprintf(buf, sizeof(buf), "  #%02zu pc=0x%llx\n", i,
                               (unsigned long long)(uintptr_t)bt[i]);
            if (len > 0) write(fd, buf, (size_t)len);
        }
    }
    if (fd >= 0) close(fd);
    signal(sig, SIG_DFL);
    raise(sig);
    _exit(1);
}

// ============================================================
// JNI Exports
// ============================================================
extern "C" {
static void safeKill();
static bool isKillHooked();

JNIEXPORT void JNICALL
Java_com_adfxcbnm_protect_SecurityCheckProvider_nativeInstallCrashHandler(JNIEnv *env, jobject, jstring path) {
    if (!path) return;
    const char *p = env->GetStringUTFChars(path, NULL);
    if (!p) return;
    strncpy(g_crashLogPath, p, sizeof(g_crashLogPath) - 1);
    g_crashLogPath[sizeof(g_crashLogPath) - 1] = '\0';
    env->ReleaseStringUTFChars(path, p);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
    sigaction(SIGTRAP, &sa, NULL);
    g_crashLogReady = 1;
    LOGI("ADFXCBNM crash handler installed, log=%s", g_crashLogPath);
}

#define JNI_EXPORT(name, ...) \
    JNIEXPORT jboolean JNICALL Java_com_adfxcbnm_hardeningtool_ProtectionNative_##name(JNIEnv *env, jobject thiz) { return __VA_ARGS__; }

JNI_EXPORT(checkDebugger, antiDebugEnhanced())
JNI_EXPORT(checkRoot, rootCheckBinary() || rootCheckApps() || rootCheckWritableSystem() || rootCheckMagisk() || rootCheckProps() || rootCheckSuEnv())
JNI_EXPORT(checkHook, hookCheckEnhanced())
JNI_EXPORT(checkEmulator, emuCheckFiles() || emuCheckProps() || emuCheckCpu() || emuCheckQemu() || emuCheckHardware())
JNI_EXPORT(checkDebugTiming, antiDebugTiming())
JNI_EXPORT(checkProxy, proxyCheckHttp() || proxyCheckEnv() || proxyCheckPort() || proxyCheckSystemProperties())
JNI_EXPORT(checkInjection, injectCheckMaps() || injectCheckFd() || injectCheckStackTrace())
JNI_EXPORT(checkMemoryDump, dumpCheckPtrace() || dumpCheckMaps() || dumpCheckMemAccess() || dumpCheckProcessStatus())
JNI_EXPORT(checkSpeed, speedCheck())
JNI_EXPORT(checkMultiInstance, multiCheck())
JNI_EXPORT(checkSsl, sslCheck())
JNI_EXPORT(checkCodeInject, codeInjectCheck())
JNI_EXPORT(checkMagisk, magiskCheck())
JNI_EXPORT(checkFrida, hookCheckFridaMaps() || hookCheckFridaPort() || hookCheckFridaPort27043())
JNI_EXPORT(checkXposed, hookCheckXposed())
JNI_EXPORT(checkRuntimeProtect, runtimeCheckCodeIntegrity() || memoryCheckTampering() || selfIntegrityCheck() || detectLibcHook())
JNI_EXPORT(checkMemoryProtect, memoryCheckTampering())
JNI_EXPORT(checkFileAccess, fileCheckUnauthorizedAccess())
JNI_EXPORT(checkSelfIntegrity, selfIntegrityCheck())
JNI_EXPORT(checkLibcHook, detectLibcHook())
JNI_EXPORT(checkInlineHook, hookCheckFunctionPrologue())
JNI_EXPORT(checkMemoryScan, scanMemoryForHookPatterns())
JNI_EXPORT(checkEnvTestKeys, envCheckTestKeys())
JNI_EXPORT(checkEnvSelinux, envCheckSelinux())

JNIEXPORT jstring JNICALL
Java_com_adfxcbnm_hardeningtool_ProtectionNative_getFullStatus(JNIEnv *env, jobject) {
    std::stringstream ss;
    ss << "{";
    ss << "\"debugger\":" << (antiDebugEnhanced() ? "true" : "false");
    ss << ",\"root\":" << (rootCheckBinary() || rootCheckMagisk() ? "true" : "false");
    ss << ",\"hook\":" << (hookCheckEnhanced() ? "true" : "false");
    ss << ",\"emulator\":" << (emuCheckFiles() || emuCheckProps() ? "true" : "false");
    ss << ",\"proxy\":" << (proxyCheckHttp() || proxyCheckEnv() ? "true" : "false");
    ss << ",\"injection\":" << (injectCheckMaps() || injectCheckFd() ? "true" : "false");
    ss << ",\"dump\":" << (dumpCheckPtrace() || dumpCheckMaps() || dumpCheckMemAccess() ? "true" : "false");
    ss << ",\"magisk\":" << (magiskCheck() ? "true" : "false");
    ss << ",\"frida\":" << (hookCheckFridaMaps() || hookCheckFridaPort() ? "true" : "false");
    ss << ",\"xposed\":" << (hookCheckXposed() ? "true" : "false");
    ss << ",\"speed\":" << (speedCheck() ? "true" : "false");
    ss << ",\"multi\":" << (multiCheck() ? "true" : "false");
    ss << ",\"ssl\":" << (sslCheck() ? "true" : "false");
    ss << ",\"code_inject\":" << (codeInjectCheck() ? "true" : "false");
    ss << ",\"runtime_protect\":" << (runtimeCheckCodeIntegrity() || selfIntegrityCheck() ? "true" : "false");
    ss << ",\"mem_protect\":" << (memoryCheckTampering() ? "true" : "false");
    ss << ",\"file_access\":" << (fileCheckUnauthorizedAccess() ? "true" : "false");
    ss << ",\"libc_hook\":" << (detectLibcHook() ? "true" : "false");
    ss << ",\"inline_hook\":" << (hookCheckFunctionPrologue() ? "true" : "false");
    ss << ",\"mem_scan\":" << (scanMemoryForHookPatterns() ? "true" : "false");
    ss << ",\"env_testkeys\":" << (envCheckTestKeys() ? "true" : "false");
    ss << ",\"env_selinux\":" << (envCheckSelinux() ? "true" : "false");
    ss << "}";
    return env->NewStringUTF(ss.str().c_str());
}

// ============================================================
// 目标应用自动保护（运行时线程）
// ============================================================
static int currentTracerPid() {
    char buf[512];
    if (!readSmallFile("/proc/self/status", buf, sizeof(buf))) return -1;
    char *t = strstr(buf, "TracerPid:");
    return t ? atoi(t + 10) : -1;
}

static bool nativeTracerPresent() {
    int tp = currentTracerPid();
    if (tp <= 0) return false;
    if (g_selfTraced) return tp != getppid();
    return true;
}

static bool nativeFridaPort() {
    int ports[] = {27042, 27043};
    for (int i = 0; i < 2; i++) {
        int sock = socket(AF_INET, SOCK_STREAM, 0);
        if (sock < 0) continue;
        struct sockaddr_in addr = {};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(ports[i]);
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        bool ok = (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0);
        close(sock);
        if (ok) return true;
    }
    return false;
}

static volatile bool g_autoProtectActive = false;
static volatile bool g_checkFrida = false;

static void *autoProtectThread(void *) {
    while (g_autoProtectActive) {
        for (int i = 0; i < 10 && g_autoProtectActive; i++) {
            usleep(100000);
        }
        if (!g_autoProtectActive) break;
        bool frida = g_checkFrida && nativeFridaPort();
        bool hooked = hookCheckFridaMaps() || hookCheckXposed();
        if (nativeTracerPresent() || frida || hooked) {
            LOGE("RUNTIME THREAT: tracer=%d frida=%d hook=%d",
                 nativeTracerPresent(), frida, hooked);
            safeKill();
        }
    }
    return NULL;
}

__attribute__((constructor)) static void protection_ctor() {
    int tp = currentTracerPid();
    if (tp == 0 && ptrace(PTRACE_TRACEME, 0, 0, 0) == 0) {
        g_selfTraced = 1;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_adfxcbnm_protect_SecurityCheckProvider_nativeSelfProtect(JNIEnv *, jobject,
                                                                   jboolean checkFrida) {
    if (nativeTracerPresent()) {
        LOGE("external tracer at startup - terminating");
        safeKill();
    }
    if (!g_selfTraced && ptrace(PTRACE_TRACEME, 0, 0, 0) == 0) {
        g_selfTraced = 1;
    }
    g_checkFrida = (checkFrida == JNI_TRUE);
    if (!g_autoProtectActive) {
        g_autoProtectActive = true;
        pthread_t tid;
        if (pthread_create(&tid, NULL, autoProtectThread, NULL) == 0) {
            pthread_detach(tid);
        }
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_adfxcbnm_protect_SecurityCheckProvider_nativeCheckThreat(JNIEnv *, jobject) {
    if (nativeTracerPresent()) return JNI_TRUE;
    if (g_checkFrida && nativeFridaPort()) return JNI_TRUE;
    if (hookCheckFridaMaps() || hookCheckXposed()) return JNI_TRUE;
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_adfxcbnm_protect_SecurityCheckProvider_nativeKill(JNIEnv *, jobject) {
    safeKill();
}

} // extern "C"

static bool isKillHooked() {
    void *addr = dlsym(RTLD_DEFAULT, "kill");
    if (!addr) return false;
#if defined(__aarch64__)
    unsigned char *p = (unsigned char *)addr;
    unsigned int instr = *(unsigned int *)p;
    if ((instr & 0xFC000000) == 0x14000000) return true;
    if ((instr & 0xFC000000) == 0x94000000) return true;
    if ((instr & 0xFFFFFC1F) == 0xD61F0000) return true;
    if (instr == 0x58000050 || instr == 0x58000070) {
        unsigned int next = ((unsigned int *)p)[1];
        if (next == 0xD61F0200 || next == 0xD61F0220) return true;
    }
#endif
    return false;
}

static void safeKill() {
    if (isKillHooked()) {
        syscall(SYS_kill, getpid(), SIGKILL);
        _exit(1);
    }
    kill(getpid(), SIGKILL);
    _exit(1);
}
