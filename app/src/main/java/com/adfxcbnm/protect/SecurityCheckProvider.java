package com.adfxcbnm.protect;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SecurityCheckProvider extends ContentProvider {
    private static String TAG;
    private static String AUTHORITY;
    private static final Set<String> CRITICAL = new HashSet<>();
    private static volatile long lastKillTime = 0;
    private static volatile int killCount = 0;
    private static volatile boolean crashHandlerInstalled = false;

    private static native void nativeKill();
    private static native void nativeInstallCrashHandler(String path);
    private static final Map<String, String> RESULT_TO_FEATURE = new HashMap<>();
    private static volatile Set<String> enabled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile Map<String, Boolean> results = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean initialized = false;
    private static volatile boolean nativeOk = false;
    private static volatile String expectedSigSha256 = "";
    private static volatile Map<String, Long> expectedDexCrc = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Context appContext = null;
    private static volatile String cachedMaps = null;
    private static volatile long cachedMapsTime = 0;

    static {
        TAG = "ADFXCBNM";
        AUTHORITY = S.t(S.com_adfxcbnm_authority);
        CRITICAL.add(S.t(S.root));
        CRITICAL.add(S.t(S.magisk));
        CRITICAL.add(S.t(S.xposed));
        CRITICAL.add(S.t(S.frida));
        CRITICAL.add(S.t(S.hook));
        CRITICAL.add(S.t(S.debugger));
        CRITICAL.add(S.t(S.inject));
        CRITICAL.add(S.t(S.code_inject));
        CRITICAL.add(S.t(S.runtime_protect));
        CRITICAL.add(S.t(S.signature));
        CRITICAL.add(S.t(S.integrity));
        RESULT_TO_FEATURE.put(S.t(S.root), S.t(S.root_detect));
        RESULT_TO_FEATURE.put(S.t(S.magisk), S.t(S.magisk_detect));
        RESULT_TO_FEATURE.put(S.t(S.xposed), S.t(S.xposed_detect));
        RESULT_TO_FEATURE.put(S.t(S.frida), S.t(S.frida_detect));
        RESULT_TO_FEATURE.put(S.t(S.hook), S.t(S.anti_hook));
        RESULT_TO_FEATURE.put(S.t(S.debugger), S.t(S.debugger_detect));
        RESULT_TO_FEATURE.put(S.t(S.inject), S.t(S.anti_inject));
        RESULT_TO_FEATURE.put(S.t(S.dump), S.t(S.anti_dump));
        RESULT_TO_FEATURE.put(S.t(S.emulator), S.t(S.emu_detect));
        RESULT_TO_FEATURE.put(S.t(S.proxy), S.t(S.anti_proxy));
        RESULT_TO_FEATURE.put(S.t(S.code_inject), S.t(S.code_inject));
        RESULT_TO_FEATURE.put(S.t(S.integrity), S.t(S.integrity));
        RESULT_TO_FEATURE.put(S.t(S.signature), S.t(S.sig_verify));
        RESULT_TO_FEATURE.put(S.t(S.speed), S.t(S.speed_check));
        RESULT_TO_FEATURE.put(S.t(S.multi), S.t(S.multi_instance));
        RESULT_TO_FEATURE.put(S.t(S.ssl), S.t(S.ssl_pinning));
        RESULT_TO_FEATURE.put(S.t(S.mem_protect), S.t(S.mem_protect));
        RESULT_TO_FEATURE.put(S.t(S.data_leak), S.t(S.data_leak));
        RESULT_TO_FEATURE.put(S.t(S.log_protect), S.t(S.log_protect));
        RESULT_TO_FEATURE.put(S.t(S.file_control), S.t(S.file_control));
        RESULT_TO_FEATURE.put(S.t(S.component_protect), S.t(S.component_protect));
        RESULT_TO_FEATURE.put(S.t(S.webview_secure), S.t(S.webview_secure));
        RESULT_TO_FEATURE.put(S.t(S.runtime_protect), S.t(S.runtime_protect));
        RESULT_TO_FEATURE.put(S.t(S.net_secure), S.t(S.net_secure));
        RESULT_TO_FEATURE.put(S.t(S.env_testkeys), S.t(S.env_testkeys));
        RESULT_TO_FEATURE.put(S.t(S.env_selinux), S.t(S.env_selinux));
    }

    private static native boolean nativeSelfProtect(boolean checkFrida);
    private static native boolean nativeCheckThreat();

    @Override
    public boolean onCreate() {
        try {
            if (initialized) return true;
            Context ctx = getContext();
            if (ctx == null) return true;
            appContext = ctx.getApplicationContext();
            // 安装全局闪退日志处理器
            installCrashHandler(appContext);
            loadEnabledFeatures(appContext);
            loadNativeLib();
            if (nativeOk) {
                try {
                    installNativeCrashHandler();
                    nativeSelfProtect(enabled.contains(S.t(S.frida_detect)));
                } catch (Throwable t) {
                    Log.w(TAG, S.t(S.nativeSelfProtect_failed), t);
                    writeCrashLog(S.t(S.nativeSelfProtect_failed), t);
                }
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        runAllChecks(appContext);
                        enforce(appContext);
                        startMonitorThread(appContext);
                    } catch (Throwable t) {
                        Log.w(TAG, S.t(S.init_checks_failed), t);
                        writeCrashLog(S.t(S.init_checks_failed), t);
                    }
                }
            }, S.t(S.ADFXCBNM_Init)).start();
            initialized = true;
            Log.i(TAG, S.t(S.protection_initializing_native) + nativeOk + ", features=" + enabled.size());
            writeCrashLog(S.t(S.protection_initialized_native) + nativeOk + ", features=" + enabled.size(), null);
        } catch (Throwable t) {
            Log.w(TAG, S.t(S.onCreate_failed), t);
            writeCrashLog(S.t(S.onCreate_failed), t);
        }
        return true;
    }

    private static void installCrashHandler(final Context ctx) {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    writeCrashLog(S.t(S.FATAL_) + thread.getName(), ex);
                } catch (Throwable ignored) {}
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, ex);
                }
            }
        });
    }

    private static void installNativeCrashHandler() {
        try {
            if (appContext == null) return;
            File logDir = appContext.getFilesDir();
            try {
                File extDir = appContext.getExternalFilesDir(null);
                if (extDir != null) logDir = extDir;
            } catch (Throwable ignored) {}
            File logFile = new File(logDir, S.t(S.adfxcbnm_crash_log));
            try {
                nativeInstallCrashHandler(logFile.getAbsolutePath());
            } catch (Throwable t) {
                Log.w(TAG, "nativeInstallCrashHandler failed", t);
            }
        } catch (Throwable ignored) {}
    }

    private static void writeCrashLog(String message, Throwable t) {
        try {
            if (appContext == null) return;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US);
            String time = sdf.format(new java.util.Date());
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(time).append("] ").append(message).append("\n");
            sb.append("  pkg=").append(appContext.getPackageName());
            sb.append(" pid=").append(android.os.Process.myPid());
            sb.append(" tid=").append(android.os.Process.myTid()).append("\n");
            sb.append("  nativeOk=").append(nativeOk);
            sb.append(" features=").append(new java.util.ArrayList<>(enabled).toString()).append("\n");
            if (t != null) {
                sb.append("  Exception: ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
                StackTraceElement[] stack = t.getStackTrace();
                for (int i = 0; i < Math.min(stack.length, 20); i++) {
                    sb.append("    at ").append(stack[i].toString()).append("\n");
                }
                Throwable cause = t.getCause();
                if (cause != null) {
                    sb.append("  Caused by: ").append(cause.getClass().getName()).append(": ").append(cause.getMessage()).append("\n");
                }
            }
            String content = sb.toString();
            File[] targets = new File[]{new File(appContext.getFilesDir(), S.t(S.adfxcbnm_crash_log))};
            try {
                File extDir = appContext.getExternalFilesDir(null);
                if (extDir != null) targets = new File[]{new File(extDir, S.t(S.adfxcbnm_crash_log)), targets[0]};
            } catch (Throwable ignored) {}
            for (File logFile : targets) {
                try {
                    FileWriter fw = new FileWriter(logFile, true);
                    fw.write(content);
                    fw.close();
                } catch (Throwable ignored) {}
            }
            Log.e("ADFXCBNM_CRASH", content);
        } catch (Throwable ignored) {}
    }

    private static void loadEnabledFeatures(Context ctx) {
        try {
            String[] lines = readConfig(ctx);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith(S.t(S.features_))) {
                    String[] parts = line.substring(9).split(",");
                    for (String p : parts) {
                        String key = p.trim();
                        if (!key.isEmpty()) enabled.add(key);
                    }
                } else if (line.startsWith(S.t(S.signature_sha256_))) {
                    expectedSigSha256 = line.substring(S.t(S.signature_sha256_).length()).trim();
                } else if (line.startsWith(S.t(S.cert_sha256_))) {
                    expectedSigSha256 = line.substring(S.t(S.cert_sha256_).length()).trim();
                } else if (line.startsWith(S.t(S.dex_crc_entry_))) {
                    String value = line.substring(14).trim();
                    int colonIdx = value.lastIndexOf(':');
                    if (colonIdx > 0) {
                        try {
                            String name = value.substring(0, colonIdx);
                            Long crc = Long.parseLong(value.substring(colonIdx + 1));
                            expectedDexCrc.put(name, crc);
                        } catch (NumberFormatException ignored) {}
                    }
                } else if (line.startsWith(S.t(S.dex_crc_))) {
                    String entries = line.substring(8).trim();
                    for (String entry : entries.split(",")) {
                        String[] parts = entry.split(":");
                        if (parts.length == 2) {
                            try {
                                expectedDexCrc.put(parts[0].trim(), Long.parseLong(parts[1].trim()));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, S.t(S.loadEnabledFeatures_failed), t);
        }
    }

    private static String[] readConfig(Context ctx) {
        try {
            java.io.InputStream is = ctx.getAssets().open(S.t(S.features_cfg));
            java.io.BufferedReader reader = new java.io.BufferedReader(new InputStreamReader(is));
            java.util.List<String> lines = new java.util.ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            reader.close();
            return lines.toArray(new String[0]);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private static void loadNativeLib() {
        try {
            System.loadLibrary(S.t(S.security_check));
            nativeOk = true;
        } catch (Throwable t) {
            Log.w(TAG, S.t(S.loadNativeLib_failed), t);
        }
    }

    private static String readMapsCached() {
        long now = System.currentTimeMillis();
        if (cachedMaps != null && (now - cachedMapsTime) < 2000) return cachedMaps;
        synchronized (SecurityCheckProvider.class) {
            if (cachedMaps != null && (System.currentTimeMillis() - cachedMapsTime) < 2000) return cachedMaps;
            cachedMaps = readFileString(S.t(S._proc_self_maps));
            cachedMapsTime = System.currentTimeMillis();
            return cachedMaps;
        }
    }

    private static void runAllChecks(Context ctx) {
        writeCrashLog("checks:start", null);
        put(S.t(S.root), enabled.contains(S.t(S.root_detect)) && detectRoot(ctx));
        put(S.t(S.magisk), enabled.contains(S.t(S.magisk_detect)) && detectMagisk());
        put(S.t(S.xposed), enabled.contains(S.t(S.xposed_detect)) && detectXposed());
        put(S.t(S.frida), enabled.contains(S.t(S.frida_detect)) && detectFrida());
        put(S.t(S.hook), enabled.contains(S.t(S.anti_hook)) && (detectHookLibs() || detectFrida()));
        put(S.t(S.debugger), (enabled.contains(S.t(S.anti_debug)) || enabled.contains(S.t(S.debugger_detect))) && checkRuntimeThreat());
        put(S.t(S.inject), enabled.contains(S.t(S.anti_inject)) && detectInjection());
        put(S.t(S.dump), enabled.contains(S.t(S.anti_dump)) && detectDumpRisk());
        put(S.t(S.emulator), enabled.contains(S.t(S.emu_detect)) && detectEmulator());
        put(S.t(S.proxy), enabled.contains(S.t(S.anti_proxy)) && detectProxy());
        put(S.t(S.code_inject), enabled.contains(S.t(S.code_inject)) && codeInjectCheck());
        put(S.t(S.integrity), enabled.contains(S.t(S.integrity)) && verifyDexChecksums(ctx));
        put(S.t(S.signature), (enabled.contains(S.t(S.sig_verify)) || enabled.contains(S.t(S.app_sig))) && verifySignatureMatches(ctx));
        put(S.t(S.speed), enabled.contains(S.t(S.speed_check)) && detectSpeedAnomaly());
        put(S.t(S.multi), enabled.contains(S.t(S.multi_instance)) && detectMultiInstance());
        put(S.t(S.ssl), enabled.contains(S.t(S.ssl_pinning)) && detectTestKeys());
        put(S.t(S.mem_protect), enabled.contains(S.t(S.mem_protect)) && detectRwxMemory());
        put(S.t(S.data_leak), enabled.contains(S.t(S.data_leak)) && detectWorldAccessibleDataDir(ctx));
        put(S.t(S.log_protect), enabled.contains(S.t(S.log_protect)) && detectLogLeak(ctx));
        put(S.t(S.file_control), enabled.contains(S.t(S.file_control)) && nonStandardDataDir(ctx));
        put(S.t(S.component_protect), enabled.contains(S.t(S.component_protect)) && hasUnprotectedExportedComponents(ctx));
        put(S.t(S.webview_secure), enabled.contains(S.t(S.webview_secure)) && detectWebviewDebugging(ctx));
        put(S.t(S.runtime_protect), enabled.contains(S.t(S.runtime_protect)) && isRuntimeCompromised(ctx));
        put(S.t(S.net_secure), enabled.contains(S.t(S.net_secure)) && detectMitmCert(ctx));
        put(S.t(S.env_testkeys), enabled.contains(S.t(S.env_testkeys)) && detectTestKeys());
        put(S.t(S.env_selinux), enabled.contains(S.t(S.env_selinux)) && detectSelinuxPermissive());
        writeCrashLog("checks:done", null);
    }

    private static void enforce(Context ctx) {
        for (Map.Entry<String, Boolean> e : results.entrySet()) {
            if (e.getValue() && CRITICAL.contains(e.getKey())) {
                Log.e(TAG, S.t(S.CRITICAL_) + e.getKey() + " detected");
                triggerKill(S.t(S.critical_) + e.getKey());
            }
        }
    }

    private static void triggerKill(final String reason) {
        long now = System.currentTimeMillis();
        if (now - lastKillTime < 1000) return;
        lastKillTime = now;
        killCount++;
        Log.e(TAG, S.t(S.KILL_) + reason + " (count=" + killCount + ")");
        try {
            if (nativeOk) nativeKill();
        } catch (Throwable ignored) {}
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable ignored) {}
        try {
            System.exit(0);
        } catch (Throwable ignored) {}
        try {
            Runtime.getRuntime().exit(0);
        } catch (Throwable ignored) {}
    }

    private static void startMonitorThread(final Context ctx) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(3000);
                        if (enabled.contains(S.t(S.anti_debug)) && checkRuntimeThreat()) {
                            put(S.t(S.debugger), true);
                            triggerKill(S.t(S.monitor_debugger));
                        }
                        if (enabled.contains(S.t(S.frida_detect)) && detectFrida()) {
                            put(S.t(S.frida), true);
                            triggerKill(S.t(S.monitor_frida));
                        }
                        if (enabled.contains(S.t(S.anti_hook)) && (detectHookLibs() || detectFrida())) {
                            put(S.t(S.hook), true);
                            triggerKill(S.t(S.monitor_hook));
                        }
                        if (enabled.contains(S.t(S.mem_protect)) && detectRwxMemory()) {
                            put(S.t(S.mem_protect), true);
                        }
                        if (enabled.contains(S.t(S.data_leak)) && detectWorldAccessibleDataDir(ctx)) {
                            put(S.t(S.data_leak), true);
                        }
                        if (enabled.contains(S.t(S.file_control)) && nonStandardDataDir(ctx)) {
                            put(S.t(S.file_control), true);
                        }
                        if (enabled.contains(S.t(S.component_protect)) && hasUnprotectedExportedComponents(ctx)) {
                            put(S.t(S.component_protect), true);
                        }
                        if (enabled.contains(S.t(S.webview_secure)) && detectWebviewDebugging(ctx)) {
                            put(S.t(S.webview_secure), true);
                        }
                        if (enabled.contains(S.t(S.runtime_protect)) && isRuntimeCompromised(ctx)) {
                            put(S.t(S.runtime_protect), true);
                            triggerKill(S.t(S.monitor_runtime));
                        }
                        if (enabled.contains(S.t(S.net_secure)) && detectMitmCert(ctx)) {
                            put(S.t(S.net_secure), true);
                        }
                        if (enabled.contains(S.t(S.env_selinux)) && detectSelinuxPermissive()) {
                            put(S.t(S.env_selinux), true);
                        }
                        if ((enabled.contains(S.t(S.sig_verify)) || enabled.contains(S.t(S.app_sig))) && verifySignatureMatches(ctx)) {
                            put(S.t(S.signature), true);
                            triggerKill(S.t(S.monitor_signature));
                        }
                        if (enabled.contains(S.t(S.integrity)) && verifyDexChecksums(ctx)) {
                            put(S.t(S.integrity), true);
                            triggerKill(S.t(S.monitor_integrity));
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, S.t(S.monitor_error), t);
                    }
                }
            }
        }, S.t(S.ADFXCBNM_Monitor)).start();
    }

    private static boolean checkRuntimeThreat() {
        try {
            if (nativeOk) return nativeCheckThreat();
        } catch (Throwable ignored) {}
        return detectTracerPid();
    }

    private static boolean detectTracerPid() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(S.t(S._proc_self_status)));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(S.t(S.TracerPid_))) {
                    int pid = Integer.parseInt(line.substring(10).trim());
                    reader.close();
                    return pid != 0;
                }
            }
            reader.close();
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectRoot(Context ctx) {
        String[] rootPaths = {
            S.t(S._system_bin_su), S.t(S._system_xbin_su), S.t(S._sbin_su),
            S.t(S._data_local_xbin_su), S.t(S._data_local_bin_su), S.t(S._data_local_su),
            S.t(S._su_bin_su), S.t(S._system_sd_xbin_su), S.t(S._system_bin__ext__su),
            S.t(S._system_app_Superuser_apk), S.t(S._system_app_SuperSU_apk),
            S.t(S._data_adb_magisk), S.t(S._sbin__magisk),
            S.t(S._system_sbin_su), S.t(S._vendor_bin_su), S.t(S._vendor_xbin_su),
            S.t(S._odm_bin_su), S.t(S._product_bin_su)
        };
        for (String path : rootPaths) {
            if (new File(path).exists()) return true;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{S.t(S.pm), S.t(S.list), S.t(S.packages)});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(S.t(S.com_topjohnwu_magisk)) || line.contains(S.t(S.com_koushikdutta_superuser)) ||
                    line.contains(S.t(S.com_noshufou_android_su)) || line.contains(S.t(S.eu_chainfire_supersu)) ||
                    line.contains(S.t(S.com_formyhm_hiderodule)) || line.contains(S.t(S.me_weishu_exp)) ||
                    line.contains(S.t(S.org_lsposed_manager)) || line.contains(S.t(S.me_weishu_kernelsu))) {
                    reader.close();
                    return true;
                }
            }
            reader.close();
        } catch (Throwable ignored) {}
        try {
            String buildProp = readBuildProps();
            if (buildProp.contains(S.t(S.test_keys))) return true;
        } catch (Throwable ignored) {}
        try {
            String path = System.getenv(S.t(S.PATH));
            if (path != null && path.contains(S.t(S._su_bin))) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectMagisk() {
        for (String path : new String[]{S.t(S._sbin__magisk), S.t(S._data_adb_magisk), S.t(S._data_adb_magisk_img), S.t(S._data_adb_magisk_db)}) {
            if (new File(path).exists()) return true;
        }
        try {
            String maps = readMapsCached();
            if (maps.contains(S.t(S.zygisk))) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean xposedClassLoaded(String className) {
        ClassLoader[] loaders = {
            SecurityCheckProvider.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                cl.loadClass(className);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static boolean detectXposed() {
        // 1) 类加载检测：Xposed/LSPosed/EdXposed hook 生效时，应用进程必加载 XposedBridge
        //    与 XposedHelpers（LSPosed 完整实现 Xposed API 并被注入到目标 classloader）。
        //    这是 LSPosed 无法通过隐藏注入 so 痕迹来规避的强信号。
        //    用 initialize=false 避免触发 Xposed 类的 <clinit> 副作用；多 loader 兜底。
        try {
            if (xposedClassLoaded(S.t(S.xposed_bridge_class))) return true;
        } catch (Throwable ignored) {}
        try {
            if (xposedClassLoaded(S.t(S.xposed_helpers_class))) return true;
        } catch (Throwable ignored) {}
        // 2) maps 特征：库文件名（老版 Xposed/EdXposed/SandHook/Dreamland），
        //    LSPosed（Zygisk/Riru/LSPosed loader 的 so 名）。
        try {
            String maps = readMapsCached();
            for (String lib : new String[]{
                S.t(S.XposedBridge), S.t(S.edxp), S.t(S.sandhook), S.t(S.libxposed),
                S.t(S.lspd), S.t(S.riru), S.t(S.libxposed_art), S.t(S.libxposed_lite), S.t(S.zygisk)
            }) {
                if (maps.contains(lib)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectFrida() {
        try {
            String maps = readMapsCached();
            if (maps.contains(S.t(S.frida)) || maps.contains(S.t(S.gum_js_loop)) || maps.contains(S.t(S.gmain)) || maps.contains(S.t(S.linjector)) || maps.contains(S.t(S.frida_agent)) || maps.contains(S.t(S.frida_gadget))) return true;
        } catch (Throwable ignored) {}
        for (int port : new int[]{27042, 27043}) {
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress(S.t(S._127_0_0_1), port), 200);
                socket.close();
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static boolean detectHookLibs() {
        try {
            String maps = readMapsCached();
            for (String lib : new String[]{S.t(S.XposedBridge), S.t(S.edxp), S.t(S.sandhook), S.t(S.libxposed), S.t(S.libsubstrate), S.t(S.libwhale), S.t(S.libdreamland), S.t(S.lspd), S.t(S.riru), S.t(S.zygisk)}) {
                if (maps.contains(lib)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectInjection() {
        try {
            String maps = readMapsCached();
            String selfPkg = appContext.getPackageName();
            for (String line : maps.split("\n")) {
                if (line.contains(S.t(S._data_local_tmp_)) && line.endsWith(".so") && !line.contains(S.t(S.app_modules))) return true;
                if (line.contains(S.t(S.memfd_)) && (line.contains(S.t(S.frida)) || line.contains(S.t(S.linjector)))) return true;
                if (line.contains(S.t(S._data_data_)) && !line.contains(selfPkg) && line.contains(S.t(S.frida))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectDumpRisk() {
        try {
            String maps = readMapsCached();
            for (String line : maps.split("\n")) {
                if (line.contains(S.t(S._proc_)) && line.contains(S.t(S._mem))) return true;
                if (line.contains(S.t(S.rw_p)) && line.contains(S.t(S._data_local_tmp_))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectEmulator() {
        for (String path : new String[]{S.t(S._dev_qemu_pipe), S.t(S._dev_socket_qemud), S.t(S._system_lib_libc_malloc_debug_qemu_so), S.t(S._sys_qemu_trace), S.t(S._system_bin_qemu_pros), S.t(S._dev_socket_genyd), S.t(S._dev_socket_baseband_genyd)}) {
            if (new File(path).exists()) return true;
        }
        try {
            String buildProp = readBuildProps();
            if (buildProp.contains(S.t(S.goldfish)) || buildProp.contains(S.t(S.ranchu)) || buildProp.contains(S.t(S.generic)) || buildProp.contains(S.t(S.sdk)) || buildProp.contains(S.t(S.emulator))) return true;
            String cpuinfo = readFileString(S.t(S._proc_cpuinfo));
            if (cpuinfo.contains(S.t(S.goldfish)) || cpuinfo.contains(S.t(S.ranchu)) || cpuinfo.contains(S.t(S.Common_KVM))) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectProxy() {
        try {
            String buildProp = readBuildProps();
            if (buildProp.contains(S.t(S.http_proxyHost)) || buildProp.contains(S.t(S.http_proxyPort))) return true;
        } catch (Throwable ignored) {}
        String proxyHost = System.getProperty(S.t(S.http_proxyHost));
        String proxyPort = System.getProperty(S.t(S.http_proxyPort));
        if (proxyHost != null && !proxyHost.isEmpty()) return true;
        return false;
    }

    private static boolean codeInjectCheck() {
        try {
            String maps = readMapsCached();
            for (String line : maps.split("\n")) {
                if (line.contains(S.t(S.rwxp))) return true;
                if (line.contains(S.t(S.rw_p)) && line.contains(S.t(S._tmp_)) && !line.contains(S.t(S._data_data_))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectSpeedAnomaly() {
        long start = System.nanoTime();
        int d = 0;
        for (int i = 0; i < 100000; i++) d += i * i;
        long diff = System.nanoTime() - start;
        return diff > 200000000L;
    }

    private static boolean detectMultiInstance() {
        try {
            String maps = readMapsCached();
            int count = 0;
            int idx = 0;
            while ((idx = maps.indexOf(".so", idx)) != -1) { count++; idx += 3; }
            if (count > 600) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectTestKeys() {
        try {
            String buildProp = readBuildProps();
            return buildProp.contains(S.t(S.ro_build_tags_test_keys)) || buildProp.contains(S.t(S.ro_build_type_userdebug)) || buildProp.contains(S.t(S.ro_build_type_eng)) || buildProp.contains(S.t(S.ro_secure_0)) || buildProp.contains(S.t(S.ro_debuggable_1));
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectSelinuxPermissive() {
        try {
            String enforce = readFileString(S.t(S._sys_fs_selinux_enforce));
            return enforce.trim().equals("0");
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectRwxMemory() {
        try {
            String maps = readMapsCached();
            return maps.contains(S.t(S.rwxp));
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectWorldAccessibleDataDir(Context ctx) {
        try {
            File dataDir = ctx.getFilesDir().getParentFile();
            if (dataDir == null || !dataDir.exists()) return false;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.nio.file.attribute.PosixFileAttributes attrs =
                        java.nio.file.Files.readAttributes(dataDir.toPath(), java.nio.file.attribute.PosixFileAttributes.class);
                java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = attrs.permissions();
                return perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
            }
            return false;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectLogLeak(Context ctx) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{S.t(S.logcat), "-d", "-t", "50"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int sensitiveCount = 0;
            String selfPkg = ctx.getPackageName();
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase();
                if ((lower.contains(S.t(S.password)) || lower.contains(S.t(S.token)) || lower.contains(S.t(S.secret)) || lower.contains("key=")) && lower.contains(selfPkg)) {
                    sensitiveCount++;
                    if (sensitiveCount > 3) {
                        reader.close();
                        p.destroy();
                        return true;
                    }
                }
            }
            reader.close();
            p.destroy();
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean nonStandardDataDir(Context ctx) {
        try {
            File dataDir = ctx.getFilesDir().getParentFile();
            String path = dataDir.getAbsolutePath();
            if (!path.startsWith(S.t(S._data_data_)) && !path.startsWith(S.t(S._data_user_))) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean hasUnprotectedExportedComponents(Context ctx) {
        int unprotected = 0;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
            if (pi.services != null) {
                for (android.content.pm.ServiceInfo s : pi.services) {
                    if (s.exported && (s.permission == null || s.permission.isEmpty())) unprotected++;
                }
            }
            if (pi.receivers != null) {
                for (android.content.pm.ActivityInfo r : pi.receivers) {
                    if (r.exported && (r.permission == null || r.permission.isEmpty())) unprotected++;
                }
            }
            if (pi.providers != null) {
                for (android.content.pm.ProviderInfo p : pi.providers) {
                    if (p.exported && (p.readPermission == null || p.writePermission == null)) unprotected++;
                }
            }
        } catch (Throwable ignored) {}
        return unprotected > 0;
    }

    private static boolean detectWebviewDebugging(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                java.lang.reflect.Method m = android.webkit.WebView.class.getMethod(S.t(S.getWebContentsDebuggingEnabled));
                Object result = m.invoke(null);
                if (result instanceof Boolean) return (Boolean) result;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isRuntimeCompromised(Context ctx) {
        try {
            String state = readFileString(S.t(S._proc_self_status));
            if (state.contains(S.t(S.TracerPid_)) && !state.contains("TracerPid:\t0")) return true;
        } catch (Throwable ignored) {}
        try {
            String maps = readMapsCached();
            if (maps.contains(S.t(S.frida)) || maps.contains(S.t(S.xposed)) || maps.contains(S.t(S.edxposed))) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean detectMitmCert(Context ctx) {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance(S.t(S.AndroidCAStore));
            ks.load(null, null);
            java.util.Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                String lower = alias.toLowerCase();
                if (lower.contains(S.t(S.user)) || lower.contains(S.t(S.mitm)) || lower.contains(S.t(S.burp)) || lower.contains(S.t(S.charles)) || lower.contains(S.t(S.fiddler)) || lower.contains(S.t(S.proxy))) {
                    java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) ks.getCertificate(alias);
                    if (cert != null) {
                        String issuer = cert.getIssuerDN().getName().toLowerCase();
                        if (issuer.contains(S.t(S.user)) || issuer.contains(S.t(S.mitm)) || issuer.contains(S.t(S.burp)) || issuer.contains(S.t(S.charles)) || issuer.contains(S.t(S.fiddler))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean verifyDexChecksums(Context ctx) {
        if (expectedDexCrc.isEmpty()) return false;
        try {
            String apkPath = ctx.getPackageManager().getApplicationInfo(ctx.getPackageName(), 0).sourceDir;
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath);
            for (Map.Entry<String, Long> e : expectedDexCrc.entrySet()) {
                java.util.zip.ZipEntry entry = zip.getEntry(e.getKey());
                if (entry != null && entry.getCrc() != e.getValue()) {
                    zip.close();
                    return true;
                }
            }
            zip.close();
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean verifySignatureMatches(Context ctx) {
        if (expectedSigSha256.isEmpty()) return false;
        try {
            return !expectedSigSha256.equals(verifySignaturePresent(ctx));
        } catch (Throwable ignored) {}
        return false;
    }

    private static String verifySignaturePresent(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            if (pi.signatures != null && pi.signatures.length > 0) {
                Signature sig = pi.signatures[0];
                MessageDigest md = MessageDigest.getInstance(S.t(S.SHA_256));
                byte[] digest = md.digest(sig.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) sb.append(String.format("%02x", b));
                return sb.toString();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String readBuildProps() {
        return readFileString(S.t(S._system_build_prop));
    }

    private static String readFileString(String path) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(path));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static synchronized void put(String key, Boolean value) {
        results.put(key, value);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
