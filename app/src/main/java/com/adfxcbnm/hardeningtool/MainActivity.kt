package com.adfxcbnm.hardeningtool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.adfxcbnm.hardeningtool.ui.theme.AndroidHardeningToolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.io.*
import java.security.*
import java.security.cert.X509Certificate
import java.util.zip.*

private const val MAX_LOG_ENTRIES = 200
private const val BUFFER_SIZE = 8192
private val APP_VERSION: String = BuildConfig.VERSION_NAME
private val CONFIG_VERSION: String = BuildConfig.VERSION_NAME




data class LogEntry(val time: String, val message: String, val type: LogType)
enum class LogType { INFO, SUCCESS, WARNING, ERROR }

class MutableFeatureItem(val name: String, val category: String, val icon: ImageVector, isSelected: Boolean = true) {
    var isSelected by mutableStateOf(isSelected)
}

data class AppInfo(val name: String, val packageName: String, val icon: Drawable? = null)

/**
 * 已保存的签名配置。每个 profile 在 filesDir/saved_signings/ 下保留独立 keystore 副本
 * （keystorePath 指向该副本的绝对路径），并记录别名与双密码。
 */
data class SigningProfile(
    val name: String,
    val keystorePath: String,
    val alias: String,
    val storePass: String,
    val keyPass: String
) {
    fun toJson(): String {
        return "{\"name\":${jsonStr(name)},\"keystore\":${jsonStr(keystorePath)},\"alias\":${jsonStr(alias)},\"store\":${jsonStr(storePass)},\"key\":${jsonStr(keyPass)}}"
    }

    companion object {
        fun fromJson(raw: String): SigningProfile? = try {
            val o = org.json.JSONObject(raw)
            SigningProfile(
                name = o.optString("name", ""),
                keystorePath = o.optString("keystore", ""),
                alias = o.optString("alias", ""),
                storePass = o.optString("store", ""),
                keyPass = o.optString("key", "")
            )
        } catch (e: Exception) {
            null
        }
    }
}

private fun jsonStr(s: String): String {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

private fun sanitizeFileName(name: String): String {
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return cleaned.ifEmpty { "signing" }
}

/**
 * 混淆字典词根：用于「默认字典」模式，生成形似常见库/框架标识符的词条（可作类/方法/字段重命名参考）。
 */
private val DICT_STEMS = arrayOf(
    "util", "core", "model", "view", "viewmodel", "widget", "layout", "resource", "config", "manager",
    "helper", "adapter", "holder", "loader", "cache", "store", "task", "worker", "thread", "handler",
    "callback", "listener", "observer", "provider", "consumer", "factory", "builder", "strategy",
    "service", "controller", "repository", "database", "network", "socket", "http", "transport",
    "security", "encrypt", "decrypt", "hash", "sign", "verify", "auth", "session", "token",
    "image", "media", "audio", "video", "player", "render", "surface", "texture", "bitmap", "canvas",
    "paint", "color", "font", "style", "theme", "animation", "transition", "gesture", "touch", "scroll",
    "activity", "fragment", "dialog", "toast", "menu", "navigation", "router", "page", "screen", "window",
    "recycler", "listener", "binder", "delegate", "dispatcher", "executor", "scheduler", "timer", "clock",
    "metric", "report", "logger", "printer", "serializer", "parser", "converter", "transformer",
    "filter", "matcher", "scanner", "tokenizer", "sorter", "searcher", "indexer", "compressor",
    "packer", "unpacker", "encrypter", "decrypter", "signer", "verifier", "extractor", "importer"
)

private const val DEFAULT_DICT_COUNT = 200

/**
 * 混淆字典：默认模式用词根组合生成拟真词条；生成模式用随机字符词条。导出为文本文件到系统下载目录。
 * @param useDefault true=默认词根组合，false=随机生成
 * @return 结果描述
 */
private fun generateObfuscationDict(context: Context, count: Int, minLen: Int, useDefault: Boolean): String {
    return try {
        val random = java.security.SecureRandom()
        val words = LinkedHashSet<String>()
        if (useDefault) {
            var guard = 0
            while (words.size < count && guard < count * 8) {
                guard++
                val parts = when (random.nextInt(3)) {
                    0 -> arrayOf(DICT_STEMS[random.nextInt(DICT_STEMS.size)])
                    1 -> arrayOf(DICT_STEMS[random.nextInt(DICT_STEMS.size)], DICT_STEMS[random.nextInt(DICT_STEMS.size)])
                    else -> arrayOf(DICT_STEMS[random.nextInt(DICT_STEMS.size)], DICT_STEMS[random.nextInt(DICT_STEMS.size)], DICT_STEMS[random.nextInt(DICT_STEMS.size)])
                }
                val word = buildString {
                    parts.forEachIndexed { idx, stem ->
                        val base = if (idx == 0) stem else stem.replaceFirstChar { it.uppercase() }
                        append(base)
                    }
                    if (random.nextInt(3) == 0) append(random.nextInt(1000))
                }
                if (word.length >= minLen) words.add(word)
            }
        } else {
            val lower = "abcdefghijklmnopqrstuvwxyz".toCharArray()
            val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray()
            val digits = "0123456789".toCharArray()
            val all = lower + upper + digits + '_'
            while (words.size < count) {
                val length = minLen + random.nextInt(12)
                val sb = StringBuilder(length)
                for (i in 0 until length) sb.append(all[random.nextInt(all.size)])
                words.add(sb.toString())
            }
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
        val content = buildString {
            append("# obfuscation dictionary generated at $ts\n")
            append("# mode=${if (useDefault) "default" else "random"} count=${words.size} minLen=$minLen\n")
            for (w in words) append(w).append('\n')
        }
        val src = File(context.cacheDir, "obfuscation_dict_$ts.txt")
        src.writeText(content)
        val dest = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir,
            "obfuscation_dict_$ts.txt"
        )
        val saved = OutputSettings.copyOutput(context, src, dest)
        src.delete()
        if (saved != null) {
            "已生成 ${words.size} 个词条（${if (useDefault) "默认词根" else "随机"}）并导出: $saved"
        } else {
            "已生成 ${words.size} 个词条（${if (useDefault) "默认词根" else "随机"}），但导出失败"
        }
    } catch (e: Exception) {
        "字典生成失败: ${e.message}"
    }
}

private const val SAVED_SIGNINGS_KEY = "saved_signings"

/**
 * 混淆字典生成 v2（ArkProtector 移植）：按分组+复杂度+长度生成词条。
 * @return Pair(预览前20条, 生成摘要)
 */
private fun generateObfuscationDictV2(
    context: Context,
    count: Int,
    minLen: Int,
    maxLen: Int,
    complexity: Int,
    groups: Set<Int>,
    useCustomUnicode: Boolean,
    uStart: Int,
    uEnd: Int,
    useCustomSymbols: Boolean,
    symbols: String
): Pair<String, String> {
    return try {
        val gen = ObfuscationDictGenerator()
        gen.setEnabledGroups(groups)
        gen.setComplexity(complexity)
        gen.setLengthRange(minLen, maxLen)
        if (useCustomUnicode && uStart > 0 && uEnd >= uStart) {
            gen.setCustomUnicodeRange(uStart, uEnd)
        }
        if (useCustomSymbols && symbols.isNotEmpty()) {
            gen.setCustomSymbolChars(symbols)
        }
        val words = gen.generate(count)
        if (words.isEmpty()) {
            return "" to "生成失败：没有可用的词条（请至少勾选一个分组）"
        }
        val preview = words.take(20).joinToString("\n")
        val summary = "已生成 ${words.size} 个词条（分组:${groups.sorted().joinToString(",")} 复杂度:${ObfuscationDictGenerator.getComplexityName(complexity)}）"
        preview to summary
    } catch (e: Exception) {
        "生成失败: ${e.message}" to ""
    }
}

/**
 * 导出字典为文本文件到下载目录。
 * @return 导出结果描述
 */
private fun exportDictToFile(
    context: Context,
    words: List<String>,
    groupsStr: String,
    complexity: Int,
    minLen: Int,
    maxLen: Int
): String {
    return try {
        if (words.isEmpty()) return "没有可导出的词条"
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
        val content = buildString {
            append("# obfuscation dict v2\n")
            append("# generated at $ts\n")
            append("# groups=$groupsStr complexity=$complexity minLen=$minLen maxLen=$maxLen\n")
            for (w in words) append(w).append('\n')
        }
        val src = File(context.cacheDir, "obfuscation_dict_$ts.txt")
        src.writeText(content)
        val dest = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir,
            "obfuscation_dict_$ts.txt"
        )
        val saved = OutputSettings.copyOutput(context, src, dest)
        src.delete()
        if (saved != null) {
            "已导出 ${words.size} 个词条: $saved"
        } else {
            "已生成 ${words.size} 个词条，但导出失败"
        }
    } catch (e: Exception) {
        "导出失败: ${e.message}"
    }
}

private fun loadSigningProfiles(prefs: android.content.SharedPreferences): List<SigningProfile> {
    val raw = prefs.getString(SAVED_SIGNINGS_KEY, "") ?: ""
    if (raw.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(raw)
        val list = ArrayList<SigningProfile>(arr.length())
        for (i in 0 until arr.length()) {
            SigningProfile.fromJson(arr.getString(i))?.let { list.add(it) }
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveSigningProfiles(prefs: android.content.SharedPreferences, profiles: List<SigningProfile>) {
    val arr = org.json.JSONArray()
    for (p in profiles) arr.put(p.toJson())
    prefs.edit().putString(SAVED_SIGNINGS_KEY, arr.toString()).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        enableEdgeToEdge()
        setContent {
            AndroidHardeningToolTheme { MainScreen() }
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                val logDir = File(filesDir, "logs")
                if (!logDir.exists()) logDir.mkdirs()
                val logFile = File(logDir, "crash.log")
                val fw = java.io.FileWriter(logFile, true)
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                fw.write("[" + sdf.format(java.util.Date()) + "] FATAL on " + thread.name + "\n")
                fw.write("  " + ex.javaClass.name + ": " + ex.message + "\n")
                for (element in ex.stackTrace.take(30)) {
                    fw.write("    at " + element.toString() + "\n")
                }
                var cause = ex.cause
                while (cause != null) {
                    fw.write("  Caused by: " + cause.javaClass.name + ": " + cause.message + "\n")
                    for (element in cause.stackTrace.take(10)) {
                        fw.write("    at " + element.toString() + "\n")
                    }
                    cause = cause.cause
                }
                fw.write("  Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n")
                fw.write("  Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")\n")
                fw.write("  ABI: " + android.os.Build.SUPPORTED_ABIS.joinToString(", ") + "\n")
                fw.close()
            } catch (ignored: Throwable) {}
            defaultHandler?.uncaughtException(thread, ex)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedApkUri by remember { mutableStateOf<Uri?>(null) }
    var selectedApkName by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var logs by remember { mutableStateOf(listOf<LogEntry>()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf(listOf<AppInfo>()) }
    var isLoadingApps by remember { mutableStateOf(false) }
    var showResultDialog by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultPath by remember { mutableStateOf("") }
    var resultSize by remember { mutableStateOf("") }
    var showAbout by remember { mutableStateOf(false) }
    var showLogSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("adfxcbnm_settings", Context.MODE_PRIVATE) }
    var verboseLogs by remember { mutableStateOf(prefs.getBoolean("verbose_logs", true)) }
    var timestampedOutput by remember { mutableStateOf(prefs.getBoolean("timestamped_output", false)) }
    var autoVerify by remember { mutableStateOf(prefs.getBoolean("auto_verify", true)) }
    var rememberSelection by remember { mutableStateOf(prefs.getBoolean("remember_selection", false)) }
    var useFrostEngine by remember { mutableStateOf(prefs.getBoolean("use_frost_engine", false)) }

    var outputDirCustom by remember { mutableStateOf(OutputSettings.getCustomDir(context)) }
    var outputToSource by remember { mutableStateOf(OutputSettings.getOutputToSource(context)) }
    val toggleOutputToSource: () -> Unit = {
        val newVal = !outputToSource
        outputToSource = newVal
        OutputSettings.setOutputToSource(context, newVal)
    }
    var signEnabled by remember { mutableStateOf(prefs.getBoolean("sign_enabled", true)) }
    var signKeystorePath by remember { mutableStateOf(prefs.getString("sign_keystore_path", "") ?: "") }
    var signAlias by remember { mutableStateOf(prefs.getString("sign_alias", "") ?: "") }
    var signStorePass by remember { mutableStateOf(prefs.getString("sign_store_pass", "") ?: "") }
    var signKeyPass by remember { mutableStateOf(prefs.getString("sign_key_pass", "") ?: "") }
    var savedSignings by remember { mutableStateOf(loadSigningProfiles(prefs)) }
    var showSaveSigningDialog by remember { mutableStateOf(false) }
    var savedSigningNameInput by remember { mutableStateOf("") }
    var frostKeepClasses by remember { mutableStateOf(prefs.getBoolean("frost_keep_classes", false)) }
    var frostSmaller by remember { mutableStateOf(prefs.getBoolean("frost_smaller", false)) }
    var frostVerifySign by remember { mutableStateOf(prefs.getBoolean("frost_verify_sign", false)) }
    var frostSoRandomization by remember { mutableStateOf(prefs.getBoolean("frost_so_randomization", false)) }
    var frostStringEncrypt by remember { mutableStateOf(prefs.getBoolean("frost_string_encrypt", false)) }
    var frostStringEncryptMinLen by remember { mutableStateOf(prefs.getInt("frost_string_encrypt_min_len", 6)) }
    var codeObfExpanded by remember { mutableStateOf(false) }
    var showDictDialog by remember { mutableStateOf(false) }
    var dictGenerateCount by remember { mutableStateOf(200) }
    var dictGenerateMinLen by remember { mutableStateOf(6) }
    var dictLastInfo by remember { mutableStateOf("") }
    var dictImportUri by remember { mutableStateOf<Uri?>(null) }
    var dictImportedWords by remember { mutableStateOf(listOf<String>()) }
    var dictImportInfo by remember { mutableStateOf("") }
    var dictEnabledGroups by remember {
        mutableStateOf((prefs.getString("dict_enabled_groups", "1,2,3") ?: "1,2,3")
            .split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableSet())
    }
    var dictComplexity by remember { mutableStateOf(prefs.getInt("dict_complexity", 0)) }
    var dictCountText by remember { mutableStateOf(prefs.getInt("obfuscation_dict_count", 3000).toString()) }
    var dictMinLenText by remember { mutableStateOf(prefs.getInt("dict_min_len", 4).toString()) }
    var dictMaxLenText by remember { mutableStateOf(prefs.getInt("dict_max_len", 12).toString()) }
    var dictUseCustomUnicode by remember { mutableStateOf(prefs.getBoolean("dict_use_custom_unicode", false)) }
    var dictUnicodeStartText by remember { mutableStateOf(prefs.getInt("dict_custom_unicode_start", 0).toString()) }
    var dictUnicodeEndText by remember { mutableStateOf(prefs.getInt("dict_custom_unicode_end", 0).toString()) }
    var dictUseCustomSymbols by remember { mutableStateOf(prefs.getBoolean("dict_use_custom_symbols", false)) }
    var dictSymbolsText by remember {
        mutableStateOf(prefs.getString("dict_custom_symbols", "") ?: "")
    }
    var dictUIEnabled by remember { mutableStateOf(true) }
    var dictShowCustomUnicode by remember { mutableStateOf(false) }
    var dictShowCustomSymbols by remember { mutableStateOf(false) }
    var frostMethodFilterEnabled by remember { mutableStateOf(prefs.getBoolean("frost_method_filter_enabled", false)) }
    var frostMethodFilterRules by remember {
        mutableStateOf(prefs.getString("frost_method_filter_rules", "") ?: "")
    }
    var showMethodFilterDialog by remember { mutableStateOf(false) }
    var methodFilterDialogTab by remember { mutableStateOf(0) }
    var methodFilterHelpExpanded by remember { mutableStateOf(false) }
    var methodHitSummary by remember { mutableStateOf("") }
    var methodHitLoading by remember { mutableStateOf(false) }
    var methodScanResult by remember { mutableStateOf<ApkMethodScanner.ScanResult?>(null) }
    var methodFilterPresetName by remember { mutableStateOf("") }
    var methodFilterPresetList by remember { mutableStateOf(listOf<MethodRuleTemplate.Preset>()) }
    var showMethodBrowser by remember { mutableStateOf(false) }
    var frostDisguiseEnabled by remember { mutableStateOf(prefs.getBoolean("frost_disguise_enabled", false)) }
    var frostDisguiseName by remember { mutableStateOf(prefs.getString("frost_disguise_name", "") ?: "") }
    var showDisguiseDialog by remember { mutableStateOf(false) }
    var frostExcludedAbi by remember {
        mutableStateOf(prefs.getStringSet("frost_excluded_abi", emptySet())?.toMutableSet() ?: mutableSetOf())
    }
    var frostDexHeaderObfuscation by remember { mutableStateOf(prefs.getBoolean("frost_dex_header_obfuscation", false)) }
    var frostClassShuffle by remember { mutableStateOf(prefs.getBoolean("frost_class_shuffle", false)) }
    var frostDebugRemoval by remember { mutableStateOf(prefs.getBoolean("frost_debug_removal", false)) }
    var frostGotoInsertion by remember { mutableStateOf(prefs.getBoolean("frost_goto_insertion", false)) }
    var frostArithmeticObfuscation by remember { mutableStateOf(prefs.getBoolean("frost_arithmetic_obfuscation", false)) }
    var frostControlFlow by remember { mutableStateOf(prefs.getBoolean("frost_control_flow", false)) }
    var frostCallIndirection by remember { mutableStateOf(prefs.getBoolean("frost_call_indirection", false)) }
    var frostMethodOverload by remember { mutableStateOf(prefs.getBoolean("frost_method_overload", false)) }
    var frostFieldRename by remember { mutableStateOf(prefs.getBoolean("frost_field_rename", false)) }
    var frostClassRename by remember { mutableStateOf(prefs.getBoolean("frost_class_rename", false)) }
    var showOutputDirTextDialog by remember { mutableStateOf(false) }
    var outputDirTextInput by remember { mutableStateOf("") }
    var showSignInfoDialog by remember { mutableStateOf(false) }
    var signAliasInput by remember { mutableStateOf("") }
    var signStorePassInput by remember { mutableStateOf("") }
    var signKeyPassInput by remember { mutableStateOf("") }
    var showSigningToolDialog by remember { mutableStateOf(false) }
    var signToolKeystorePath by remember { mutableStateOf("") }
    var signToolStorePass by remember { mutableStateOf("") }
    var signToolAlias by remember { mutableStateOf("") }
    var signToolInfo by remember { mutableStateOf<com.adfxcbnm.hardeningtool.SigningTool.KeystoreInfo?>(null) }
    var signToolLoading by remember { mutableStateOf(false) }
    var signToolError by remember { mutableStateOf("") }
    var signToolTab by remember { mutableStateOf(0) }
    var signToolConvertMsg by remember { mutableStateOf("") }
    var signToolConvertOk by remember { mutableStateOf(false) }
    var signToolGenAlias by remember { mutableStateOf("") }
    var signToolGenStorePass by remember { mutableStateOf("") }
    var signToolGenKeyPass by remember { mutableStateOf("") }
    var signToolGenKeyAlg by remember { mutableStateOf("RSA") }
    var signToolGenKeySize by remember { mutableStateOf(2048) }
    var signToolGenValidity by remember { mutableStateOf("36500") }
    var signToolGenNotBefore by remember { mutableStateOf(formatDateOffsetDays(-1)) }
    var signToolGenNotAfter by remember { mutableStateOf(formatDateOffsetDays(36500 - 1)) }
    var signToolGenCn by remember { mutableStateOf("") }
    var signToolGenOu by remember { mutableStateOf("") }
    var signToolGenO by remember { mutableStateOf("") }
    var signToolGenL by remember { mutableStateOf("") }
    var signToolGenSt by remember { mutableStateOf("") }
    var signToolGenC by remember { mutableStateOf("CN") }
    var signToolGenResult by remember { mutableStateOf<com.adfxcbnm.hardeningtool.SigningTool.GeneratedKeystore?>(null) }
    var methodFilterInput by remember { mutableStateOf("") }

    var hardeningExpanded by remember { mutableStateOf(false) }
    var protectionExpanded by remember { mutableStateOf(false) }

    val hardeningItems = remember {
        listOf(
            MutableFeatureItem("签名校验", "hardening", Icons.Default.VerifiedUser),
            MutableFeatureItem("防调试检测", "hardening", Icons.Default.BugReport),
            MutableFeatureItem("防Hook检测", "hardening", Icons.Default.Security),
            MutableFeatureItem("防注入保护", "hardening", Icons.Default.Block),
            MutableFeatureItem("防内存Dump", "hardening", Icons.Default.SaveAlt),
            MutableFeatureItem("防代理检测", "hardening", Icons.Default.WifiOff)
        )
    }

    val protectionItems = remember {
        listOf(
            MutableFeatureItem("完整性校验", "protection", Icons.Default.CheckCircle),
            MutableFeatureItem("运行时保护", "protection", Icons.Default.PlayArrow),
            MutableFeatureItem("内存保护", "protection", Icons.Default.Storage),
            MutableFeatureItem("网络安全", "protection", Icons.Default.Wifi),
            MutableFeatureItem("ROOT检测", "protection", Icons.Default.AdminPanelSettings),
            MutableFeatureItem("模拟器检测", "protection", Icons.Default.PhoneAndroid),
            MutableFeatureItem("Xposed检测", "protection", Icons.Default.Extension),
            MutableFeatureItem("Frida检测", "protection", Icons.Default.Search),
            MutableFeatureItem("Magisk检测", "protection", Icons.Default.FolderSpecial),
            MutableFeatureItem("调试器检测", "protection", Icons.Default.DeveloperBoard),
            MutableFeatureItem("代码注入检测", "protection", Icons.Default.Input),
            MutableFeatureItem("速度检测", "protection", Icons.Default.Speed),
            MutableFeatureItem("多开检测", "protection", Icons.Default.CopyAll),
            MutableFeatureItem("SSL证书校验", "protection", Icons.Default.Security),
            MutableFeatureItem("数据防泄漏", "protection", Icons.Default.LeakAdd),
            MutableFeatureItem("日志保护", "protection", Icons.Default.Article),
            MutableFeatureItem("应用签名校验", "protection", Icons.Default.Fingerprint),
            MutableFeatureItem("WebView安全", "protection", Icons.Default.Language),
            MutableFeatureItem("文件访问控制", "protection", Icons.Default.Folder),
            MutableFeatureItem("应用组件保护", "protection", Icons.Default.Widgets),
            MutableFeatureItem("环境密钥检测", "protection", Icons.Default.Key),
            MutableFeatureItem("SELinux检测", "protection", Icons.Default.Shield)
        )
    }

    fun addLog(message: String, type: LogType = LogType.INFO) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLog = LogEntry(time, message, type)
        // 确保在主线程更新UI状态
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            logs = (logs + newLog).takeLast(MAX_LOG_ENTRIES)
        }
    }

    fun addDetail(message: String) {
        if (verboseLogs) addLog(message, LogType.INFO)
    }

    val enableSoRandomization: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            frostDisguiseEnabled = false
            prefs.edit().putBoolean("frost_disguise_enabled", false).apply()
            addLog("壳SO随机化已开启，伪装加固已自动关闭（互斥）", LogType.INFO)
        }
        frostSoRandomization = enabled
        prefs.edit().putBoolean("frost_so_randomization", enabled).apply()
    }
    val enableDisguise: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            frostSoRandomization = false
            prefs.edit().putBoolean("frost_so_randomization", false).apply()
            if (frostDisguiseName.isBlank()) {
                frostDisguiseName = "jiagu"
                prefs.edit().putString("frost_disguise_name", "jiagu").apply()
                addLog("伪装加固已开启，使用默认厂商指纹 jiagu(360加固)，壳SO随机化已关闭（互斥）", LogType.INFO)
            } else {
                addLog("伪装加固已开启，壳SO随机化已自动关闭（互斥）", LogType.INFO)
            }
        }
        frostDisguiseEnabled = enabled
        prefs.edit().putBoolean("frost_disguise_enabled", enabled).apply()
    }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            logs = emptyList()
            selectedApkUri = uri
            selectedApkName = uri.lastPathSegment?.substringAfterLast("/") ?: "unknown.apk"
            addLog("已选择APK: $selectedApkName", LogType.SUCCESS)
        }
    }

    val outputDirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = OutputSettings.safTreeToPath(uri)
            if (path != null && OutputSettings.isWritable(path)) {
                OutputSettings.setCustomDir(context, path)
                outputDirCustom = path
                addLog("输出目录已设置: $path", LogType.SUCCESS)
            } else {
                outputDirTextInput = ""
                showOutputDirTextDialog = true
                addLog("所选目录无法解析为可写路径，请手动输入", LogType.WARNING)
            }
        }
    }

    val signKeystorePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val ext = uri.lastPathSegment?.substringAfterLast(".", "keystore") ?: "keystore"
                val target = File(context.filesDir, "custom_sign_keystore.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.exists() && target.length() > 0) {
                    signKeystorePath = target.absolutePath
                    prefs.edit().putString("sign_keystore_path", target.absolutePath).apply()
                    addLog("已选择签名keystore: ${target.name}", LogType.SUCCESS)
                } else {
                    addLog("keystore文件读取失败", LogType.WARNING)
                }
            } catch (e: Exception) {
                addLog("导入keystore失败: ${e.message}", LogType.ERROR)
            }
        }
    }

    val dictImporterLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val rows = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readLines()
                } ?: emptyList()
                val words = rows
                    .map { it.trim() }
                    .filter {
                        it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//")
                    }
                    .map { it.removePrefix("\uFEFF") }
                    .toMutableSet()
                    .toList()
                if (words.isEmpty()) {
                    dictImportInfo = "导入失败：文件中没有有效的词条"
                } else {
                    dictImportedWords = words
                    dictImportInfo = "已导入 ${words.size} 个词条（示例: ${words.take(5).joinToString(", ")}）"
                    prefs.edit()
                        .putStringSet("dict_imported_words", LinkedHashSet(words))
                        .putBoolean("obfuscation_dict", true)
                        .apply()
                    addLog("混淆字典导入成功: ${words.size} 词条", LogType.SUCCESS)
                }
            } catch (e: Exception) {
                dictImportInfo = "导入失败: ${e.message}"
                addLog("混淆字典导入失败: ${e.message}", LogType.ERROR)
            }
        }
    }

    val signToolPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val ext = uri.lastPathSegment?.substringAfterLast(".", "p12") ?: "p12"
                val target = File(context.filesDir, "signing_tool_keystore.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.exists() && target.length() > 0) {
                    signToolKeystorePath = target.absolutePath
                    signToolInfo = null
                    signToolError = ""
                    addLog("签名工具: 已加载 keystore ${target.name}", LogType.SUCCESS)
                } else {
                    signToolError = "keystore 文件读取失败"
                }
            } catch (e: Exception) {
                signToolError = "导入失败: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!rememberSelection) return@LaunchedEffect
        val hardSel = prefs.getString("hardening_sel", "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        val protSel = prefs.getString("protection_sel", "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        if (hardSel.isNotEmpty() && protSel.isNotEmpty()) {
            hardeningItems.forEach { it.isSelected = it.name in hardSel }
            protectionItems.forEach { it.isSelected = it.name in protSel }
        } else {
            hardeningItems.forEach { it.isSelected = true }
            protectionItems.forEach { it.isSelected = true }
            prefs.edit().remove("hardening_sel").remove("protection_sel").apply()
        }
    }

    LaunchedEffect(rememberSelection, hardeningItems.map { it.isSelected }.joinToString(","), protectionItems.map { it.isSelected }.joinToString(",")) {
        if (!rememberSelection) return@LaunchedEffect
        prefs.edit()
            .putString("hardening_sel", hardeningItems.filter { it.isSelected }.map { it.name }.joinToString(","))
            .putString("protection_sel", protectionItems.filter { it.isSelected }.map { it.name }.joinToString(","))
            .apply()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            // Content
            Column(
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Spacer(Modifier.height(8.dp))

                // Top Bar - Settings + About
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showAbout = true }) {
                        Icon(Icons.Default.Info, contentDescription = "关于", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                // APK Selection Card
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("选择APK", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { apkPickerLauncher.launch("application/vnd.android.package-archive") },
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("文件选择", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = {
                                    if (isLoadingApps) return@OutlinedButton
                                    isLoadingApps = true
                                    addLog("正在加载已安装应用列表...", LogType.INFO)
                                    scope.launch {
                                        installedApps = getInstalledApps(context)
                                        isLoadingApps = false
                                        showAppPicker = true
                                        addLog("已加载 ${installedApps.size} 个用户应用", LogType.SUCCESS)
                                    }
                                },
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("已安装应用", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (selectedApkName != null) {
                            Spacer(Modifier.height(10.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        selectedApkName!!,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.tertiary,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    IconButton(
                                        onClick = {
                                            selectedApkUri = null
                                            selectedApkName = null
                                            addLog("已取消APK选择", LogType.WARNING)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "取消选择", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 签名开关卡片
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("加固后签名", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                signEnabled = !signEnabled
                                prefs.edit().putBoolean("sign_enabled", signEnabled).apply()
                                addLog(if (signEnabled) "签名已开启" else "签名已关闭，将输出未签名APK", LogType.INFO)
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("加固产物自动签名", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "关闭后输出未签名APK，需自行签名",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = signEnabled,
                                onCheckedChange = {
                                    signEnabled = it
                                    prefs.edit().putBoolean("sign_enabled", it).apply()
                                    addLog(if (it) "签名已开启" else "签名已关闭，将输出未签名APK", LogType.INFO)
                                }
                            )
                        }
                        if (signEnabled) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (signKeystorePath.isNotEmpty())
                                    "自定义keystore: ${File(signKeystorePath).name}"
                                else
                                    "默认调试keystore (adh_debug.p12)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 函数抽取卡片
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("函数抽取", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    frostMethodFilterEnabled = !frostMethodFilterEnabled
                                    prefs.edit().putBoolean("frost_method_filter_enabled", frostMethodFilterEnabled).apply()
                                    if (frostMethodFilterEnabled) {
                                        methodFilterInput = frostMethodFilterRules
                                        methodFilterDialogTab = 0
                                        showMethodFilterDialog = true
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("仅抽取指定函数", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (frostMethodFilterEnabled) {
                                        "方法级抽取 (已配置${frostMethodFilterRules.lineSequence().filter { it.isNotBlank() }.count()}条规则)，未命中函数保留明文"
                                    } else {
                                        "方法级抽取：只抽取关键函数，其余保留"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = frostMethodFilterEnabled,
                                onCheckedChange = {
                                    frostMethodFilterEnabled = it
                                    prefs.edit().putBoolean("frost_method_filter_enabled", it).apply()
                                    if (it) {
                                        methodFilterInput = frostMethodFilterRules
                                        methodFilterDialogTab = 0
                                        showMethodFilterDialog = true
                                    }
                                }
                            )
                        }
                        if (frostMethodFilterEnabled) {
                            Spacer(Modifier.height(8.dp))
                            val ruleCount = frostMethodFilterRules.lineSequence().filter { it.isNotBlank() }.count()
                            if (ruleCount > 0) {
                                val firstRule = frostMethodFilterRules.lineSequence().filter { it.isNotBlank() }.firstOrNull().orEmpty()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "$ruleCount 条",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        firstRule.take(48) + if (firstRule.length > 48) "…" else "",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            OutlinedButton(
                                onClick = {
                                    methodFilterInput = frostMethodFilterRules
                                    methodFilterDialogTab = 0
                                    showMethodFilterDialog = true
                                },
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("配置抽取规则", fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Feature Sections
                FeatureSection(
                    title = "加固",
                    subtitle = "",
                    items = hardeningItems,
                    expanded = hardeningExpanded,
                    onToggle = {
                        hardeningExpanded = !hardeningExpanded
                        if (hardeningExpanded) {
                            protectionExpanded = false
                            codeObfExpanded = false
                        }
                    },
                    accentColor = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(8.dp))

                FeatureSection(
                    title = "保护",
                    subtitle = "",
                    items = protectionItems,
                    expanded = protectionExpanded,
                    onToggle = {
                        protectionExpanded = !protectionExpanded
                        if (protectionExpanded) {
                            hardeningExpanded = false
                            codeObfExpanded = false
                        }
                    },
                    accentColor = MaterialTheme.colorScheme.tertiary
                )

                Spacer(Modifier.height(8.dp))

                // 代码混淆卡片（字符串加密 + 字典 + DEX pass 开关，设置页同步管理）
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = !codeObfExpanded
                                    codeObfExpanded = next
                                    if (next) {
                                        hardeningExpanded = false
                                        protectionExpanded = false
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("代码混淆", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "字符串加密 + DEX pass 混淆（点击${if (codeObfExpanded) "收起" else "展开"}配置）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = frostStringEncrypt,
                                onCheckedChange = {
                                    frostStringEncrypt = it
                                    prefs.edit().putBoolean("frost_string_encrypt", it).apply()
                                }
                            )
                        }
                        if (codeObfExpanded) {
                            Divider()
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("最小加密长度", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    OutlinedTextField(
                                        value = frostStringEncryptMinLen.toString(),
                                        onValueChange = { text ->
                                            val v = text.toIntOrNull()
                                            if (v != null && v > 0) {
                                                frostStringEncryptMinLen = v
                                                prefs.edit().putInt("frost_string_encrypt_min_len", v).apply()
                                            }
                                        },
                                        modifier = Modifier.width(72.dp).height(44.dp),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                OutlinedButton(
                                    onClick = { showDictDialog = true },
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("混淆字典生成器 v2（21 分组 / 预览 / 导出 / 导入）", fontSize = 13.sp)
                                }
                                if (dictLastInfo.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        dictLastInfo,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("代码混淆 (DEX pass)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                SettingRow(
                                    icon = Icons.Default.Shuffle,
                                    title = "类顺序打乱",
                                    subtitle = "随机重排类定义 (class-shuffle)",
                                    checked = frostClassShuffle,
                                    onCheckedChange = {
                                        frostClassShuffle = it
                                        prefs.edit().putBoolean("frost_class_shuffle", it).apply()
                                    }
                                )
                                SettingRow(
                                    icon = Icons.Default.Code,
                                    title = "DEX 头部混淆",
                                    subtitle = "填充 header 并重算 SHA-1/Adler32 (dex-header)",
                                    checked = frostDexHeaderObfuscation,
                                    onCheckedChange = {
                                        frostDexHeaderObfuscation = it
                                        prefs.edit().putBoolean("frost_dex_header_obfuscation", it).apply()
                                    }
                                )
                                SettingRow(
                                    icon = Icons.Default.Delete,
                                    title = "移除 Debug 信息",
                                    subtitle = "剥离行号/局部变量 (debug-removal)",
                                    checked = frostDebugRemoval,
                                    onCheckedChange = {
                                        frostDebugRemoval = it
                                        prefs.edit().putBoolean("frost_debug_removal", it).apply()
                                    }
                                )
                                SettingRow(
                                    icon = Icons.Default.South,
                                    title = "Goto 插入混淆",
                                    subtitle = "方法头插入无意义 goto 跳转 (goto-insertion)",
                                    checked = frostGotoInsertion,
                                    onCheckedChange = {
                                        frostGotoInsertion = it
                                        prefs.edit().putBoolean("frost_goto_insertion", it).apply()
                                    }
                                )
                                SettingRow(
                                    icon = Icons.Default.Calculate,
                                    title = "算术混淆",
                                    subtitle = "ADD_INT 等价序列替换 + 假分支 (arithmetic)",
                                    checked = frostArithmeticObfuscation,
                                    onCheckedChange = {
                                        frostArithmeticObfuscation = it
                                        prefs.edit().putBoolean("frost_arithmetic_obfuscation", it).apply()
                                    }
                                )
                                SettingRow(
                                    icon = Icons.Default.Router,
                                    title = "控制流混淆",
                                    subtitle = "方法头部拓宽与 goto 干扰 (control-flow)",
                                    checked = frostControlFlow,
                                    onCheckedChange = {
                                        frostControlFlow = it
                                        prefs.edit().putBoolean("frost_control_flow", it).apply()
                                    }
                                )
                                SettingRow(
                                    icon = Icons.Default.Call,
                                    title = "调用间接化",
                                    subtitle = "方法入口注入间接跳转 (call-indirection)",
                                    checked = frostCallIndirection,
                                    onCheckedChange = {
                                        frostCallIndirection = it
                                        prefs.edit().putBoolean("frost_call_indirection", it).apply()
                                    }
                                )
                                SettingRow(
                                    icon = Icons.Default.Layers,
                                    title = "方法重载混淆",
                                    subtitle = "注入同签名 dummy 方法 (method-overload)",
                                    checked = frostMethodOverload,
                                    onCheckedChange = {
                                        frostMethodOverload = it
                                        prefs.edit().putBoolean("frost_method_overload", it).apply()
                                    }
                                )
                                SettingRow(
                                    icon = Icons.Default.DriveFileRenameOutline,
                                    title = "字段重命名",
                                    subtitle = "非敏感字段随机改名，引用精确重映射 (field-rename)",
                                    checked = frostFieldRename,
                                    onCheckedChange = {
                                        frostFieldRename = it
                                        prefs.edit().putBoolean("frost_field_rename", it).apply()
                                    }
                                )
                                SettingRow(
                                    icon = Icons.Default.Category,
                                    title = "类重命名",
                                    subtitle = "跨 dex 全局类名随机化，引用/字符串反射保护同步重映射 (class-rename)",
                                    checked = frostClassRename,
                                    onCheckedChange = {
                                        frostClassRename = it
                                        prefs.edit().putBoolean("frost_class_rename", it).apply()
                                    }
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // FrostShell Engine Toggle
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("FrostShell 引擎", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text("使用完整加固引擎（dex指令抽取+native加密+重新签名）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = useFrostEngine,
                                onCheckedChange = {
                                    useFrostEngine = it
                                    prefs.edit().putBoolean("use_frost_engine", it).apply()
                                    addLog(if (it) "已切换到 FrostShell 引擎模式" else "已切换到普通加固模式", LogType.INFO)
                                }
                            )
                        }
                        Divider()
                        EngineOptionRow(
                            icon = Icons.Default.Casino,                            title = "壳SO随机化",
                            subtitle = "每次加固随机化壳SO名称，防特征识别",
                            checked = frostSoRandomization,
                            onCheckedChange = { enableSoRandomization(it) }
                        )
                        EngineOptionRow(
                            icon = Icons.Default.VisibilityOff,
                            title = "伪装加固",
                            subtitle = "注入厂商特征SO，伪装为知名加固方案",
                            checked = frostDisguiseEnabled,
                            onCheckedChange = { enableDisguise(it) }
                        )
                        if (frostDisguiseEnabled) {
                            EngineOptionRow(
                                icon = Icons.Default.List,
                                title = "伪装SO名称",
                                subtitle = if (frostDisguiseName.isBlank()) "未选择，点击选择厂商预设或自定义" else "lib$frostDisguiseName.so",
                                checked = false,
                                onCheckedChange = {},
                                showSwitch = false,
                                onClick = { showDisguiseDialog = true }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Process Button
                Button(
                    onClick = {
                        if (selectedApkUri == null) {
                            addLog("请先选择APK文件", LogType.WARNING)
                            return@Button
                        }
                        val selectedHardening = hardeningItems.filter { it.isSelected }.map { it.name }
                        val selectedProtection = protectionItems.filter { it.isSelected }.map { it.name }
                        if (selectedHardening.isEmpty() && selectedProtection.isEmpty()) {
                            addLog("请至少选择一项功能", LogType.WARNING)
                            return@Button
                        }
                        if (rememberSelection) {
                            prefs.edit()
                                .putString("hardening_sel", selectedHardening.joinToString(","))
                                .putString("protection_sel", selectedProtection.joinToString(","))
                                .apply()
                        }
                        addLog("开始处理: ${selectedHardening.size}项加固 + ${selectedProtection.size}项保护", LogType.INFO)
                        isProcessing = true
                        progress = 0f
                        logs = emptyList()
                        val frostOptions = FrostEngineOptions(
                            keepClasses = frostKeepClasses,
                            smaller = frostSmaller,
                            verifySign = frostVerifySign,
                            soRandomization = frostSoRandomization,
                            disguiseSoName = if (frostDisguiseEnabled && frostDisguiseName.isNotBlank()) frostDisguiseName.trim() else null,
                            excludedAbi = if (frostExcludedAbi.isEmpty()) null else frostExcludedAbi.toList(),
                            stringEncrypt = frostStringEncrypt,
                            stringEncryptMinLen = frostStringEncryptMinLen,
                            stringEncryptKeywords = if (frostStringEncrypt) loadStringEncryptKeywords(context) else null,
                            dexHeaderObfuscation = frostDexHeaderObfuscation,
                            classShuffle = frostClassShuffle,
                            debugRemoval = frostDebugRemoval,
                            gotoInsertion = frostGotoInsertion,
                            arithmeticObfuscation = frostArithmeticObfuscation,
                            controlFlow = frostControlFlow,
                            callIndirection = frostCallIndirection,
                            methodOverload = frostMethodOverload,
                            fieldRename = frostFieldRename,
                            classRename = frostClassRename,
                            signEnabled = signEnabled,
                            signKeystorePath = signKeystorePath.ifEmpty { null },
                            signAlias = signAlias.ifEmpty { null },
                            signStorePass = signStorePass.ifEmpty { null },
                            signKeyPass = signKeyPass.ifEmpty { null },
                            extractMethodRules = if (frostMethodFilterEnabled) {
                                frostMethodFilterRules.lineSequence()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .toList()
                            } else null
                        )
                        scope.launch {
                            val result = if (useFrostEngine) {
                                processFrostShellApk(
                                    context, selectedApkUri!!, selectedApkName!!,
                                    selectedHardening, selectedProtection,
                                    ::addLog, ::addDetail,
                                    onProgress = { p ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            progress = p
                                        }
                                    },
                                    timestampedOutput = timestampedOutput,
                                    autoVerify = autoVerify,
                                    frostOptions = frostOptions
                                )
                            } else {
                                processApk(
                                context, selectedApkUri!!, selectedApkName!!,
                                selectedHardening, selectedProtection,
                                ::addLog, ::addDetail,
                                onProgress = { p ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        progress = p
                                    }
                                },
                                timestampedOutput = timestampedOutput,
                                autoVerify = autoVerify,
                                signEnabled = signEnabled,
                                signKeystorePath = signKeystorePath.ifEmpty { null },
                                signAlias = signAlias.ifEmpty { null },
                                signStorePass = signStorePass.ifEmpty { null },
                                signKeyPass = signKeyPass.ifEmpty { null }
                            )
                            }
                            isProcessing = false
                            progress = 1f
                            showResultDialog = true
                            resultSuccess = result.success
                            resultMessage = if (result.success) "加固完成" else "加固失败"
                            resultPath = result.path
                            resultSize = result.sizeDiff
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !isProcessing,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("处理中...", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开始加固", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Log Card
                val scrollState = rememberScrollState()
                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                }
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("处理日志", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                if (logs.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            "${logs.size}条",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Row {
                                if (logs.isNotEmpty()) {
                                    IconButton(onClick = { showLogSheet = true }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.OpenInFull, contentDescription = "展开日志", modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(onClick = {
                                        val logText = logs.joinToString("\n") { "[${it.time}] ${it.message}" }
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("log", logText))
                                    }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "复制日志", modifier = Modifier.size(16.dp))
                                    }
                                }
                                IconButton(onClick = { logs = emptyList() }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = "清空", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .verticalScroll(scrollState)
                            ) {
                                if (logs.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            "选择APK并点击加固按钮开始",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                } else {
                                    logs.forEachIndexed { index, entry ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Icon(
                                                imageVector = when (entry.type) {
                                                    LogType.SUCCESS -> Icons.Default.CheckCircle
                                                    LogType.WARNING -> Icons.Default.Warning
                                                    LogType.ERROR -> Icons.Default.Error
                                                    LogType.INFO -> Icons.Default.Info
                                                },
                                                contentDescription = null,
                                                tint = when (entry.type) {
                                                    LogType.SUCCESS -> MaterialTheme.colorScheme.primary
                                                    LogType.WARNING -> MaterialTheme.colorScheme.tertiary
                                                    LogType.ERROR -> MaterialTheme.colorScheme.error
                                                    LogType.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.size(14.dp).padding(top = 1.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = entry.message,
                                                color = when (entry.type) {
                                                    LogType.SUCCESS -> MaterialTheme.colorScheme.primary
                                                    LogType.WARNING -> MaterialTheme.colorScheme.tertiary
                                                    LogType.ERROR -> MaterialTheme.colorScheme.error
                                                    LogType.INFO -> MaterialTheme.colorScheme.onSurface
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Progress Bar
                        if (isProcessing) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // Log Bottom Sheet (fullscreen expanded view)
    if (showLogSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLogSheet = false },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("处理日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "${logs.size}条",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Row {
                        TextButton(onClick = {
                            val logText = logs.joinToString("\n") { "[${it.time}] ${it.message}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("log", logText))
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("复制")
                        }
                        TextButton(onClick = { logs = emptyList() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清空")
                        }
                        IconButton(onClick = { showLogSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    val sheetScrollState = rememberScrollState()
                    LaunchedEffect(logs.size) {
                        if (logs.isNotEmpty()) sheetScrollState.animateScrollTo(sheetScrollState.maxValue)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(450.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .verticalScroll(sheetScrollState)
                    ) {
                        if (logs.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            logs.forEachIndexed { index, entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = when (entry.type) {
                                            LogType.SUCCESS -> Icons.Default.CheckCircle
                                            LogType.WARNING -> Icons.Default.Warning
                                            LogType.ERROR -> Icons.Default.Error
                                            LogType.INFO -> Icons.Default.Info
                                        },
                                        contentDescription = null,
                                        tint = when (entry.type) {
                                            LogType.SUCCESS -> MaterialTheme.colorScheme.primary
                                            LogType.WARNING -> MaterialTheme.colorScheme.tertiary
                                            LogType.ERROR -> MaterialTheme.colorScheme.error
                                            LogType.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(14.dp).padding(top = 1.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = entry.message,
                                        color = when (entry.type) {
                                            LogType.SUCCESS -> MaterialTheme.colorScheme.primary
                                            LogType.WARNING -> MaterialTheme.colorScheme.tertiary
                                            LogType.ERROR -> MaterialTheme.colorScheme.error
                                            LogType.INFO -> MaterialTheme.colorScheme.onSurface
                                        },
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // App Picker Bottom Sheet
    if (showAppPicker) {
        ModalBottomSheet(
            onDismissRequest = { showAppPicker = false },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "选择已安装应用",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "仅显示用户安装的应用，点击选择",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                if (isLoadingApps) {
                    Box(modifier = Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.height(400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(installedApps) { app ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(12.dp),
                                onClick = {
                                    showAppPicker = false
                                    try {
                                        val pm = context.packageManager
                                        val appInfo = pm.getApplicationInfo(app.packageName, 0)
                                        val apkPath = appInfo.sourceDir
                                        val apkFile = File(apkPath)
                                        selectedApkUri = Uri.fromFile(apkFile)
                                        selectedApkName = "${app.name}.apk"
                                        logs = emptyList()
                                        addLog("已选择: ${app.name} (${app.packageName})", LogType.SUCCESS)
                                    } catch (e: Exception) {
                                        addLog("获取APK路径失败: ${e.message}", LogType.ERROR)
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    app.icon?.let { icon ->
                                        Image(
                                            bitmap = icon.toBitmap(40, 40).asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(app.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Result Dialog
    if (showResultDialog) {
        AlertDialog(
            onDismissRequest = { showResultDialog = false },
            icon = {
                Surface(
                    color = if (resultSuccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (resultSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (resultSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            },
            title = { Text(if (resultSuccess) "加固成功" else "加固失败", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(resultMessage, style = MaterialTheme.typography.bodyMedium)
                    if (resultSuccess && resultPath.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("输出路径:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(2.dp))
                                Text(resultPath, fontSize = 11.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("大小变化: $resultSize", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (resultSuccess) {
                    FilledTonalButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val dir = resultPath.substringBeforeLast("/")
                        clipboard.setPrimaryClip(ClipData.newPlainText("path", dir))
                        showResultDialog = false
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制目录")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { openResultApk(context, resultPath) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("打开APK")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showResultDialog = false }) { Text("关闭") }
            }
        )
    }

    // About Dialog
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "v$APP_VERSION",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("输出路径:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "${context.getExternalFilesDir(null)?.absolutePath}/ADFXCBNM/",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("闪退日志:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val logPath = "${context.filesDir.absolutePath}/logs/"
                            clipboard.setPrimaryClip(ClipData.newPlainText("log_path", logPath))
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${context.filesDir.absolutePath}/logs/",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text("点击复制路径", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))
                    Text("Telegram频道:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Forinxy"))
                            context.startActivity(intent)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("@Forinxy", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("MT论坛主页:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://bbs.binmt.cc/home.php?mod=space&uid=128752"))
                            context.startActivity(intent)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("@Forinxy", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("关闭") }
            }
        )
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                SettingRow(
                    icon = Icons.Default.Description,
                    title = "详细日志",
                    subtitle = "显示注入过程的技术细节",
                    checked = verboseLogs,
                    onCheckedChange = {
                        verboseLogs = it
                        prefs.edit().putBoolean("verbose_logs", it).apply()
                    }
                )
                SettingRow(
                    icon = Icons.Default.Schedule,
                    title = "文件名加时间戳",
                    subtitle = "避免覆盖历史产物",
                    checked = timestampedOutput,
                    onCheckedChange = {
                        timestampedOutput = it
                        prefs.edit().putBoolean("timestamped_output", it).apply()
                    }
                )
                SettingRow(
                    icon = Icons.Default.Verified,
                    title = "完成后自动校验",
                    subtitle = "验证清单/DEX/SO/配置注入完整性",
                    checked = autoVerify,
                    onCheckedChange = {
                        autoVerify = it
                        prefs.edit().putBoolean("auto_verify", it).apply()
                    }
                )
                SettingRow(
                    icon = Icons.Default.History,
                    title = "记忆功能勾选",
                    subtitle = "下次启动恢复上次选择",
                    checked = rememberSelection,
                    onCheckedChange = {
                        rememberSelection = it
                        prefs.edit().putBoolean("remember_selection", it).apply()
                    }
                )

                Spacer(Modifier.height(16.dp))
                Text("输出目录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(
                            text = when {
                                outputToSource -> "源文件路径（所选APK所在目录）"
                                outputDirCustom != null -> outputDirCustom!!
                                else -> "默认（跟随所选APK所在目录）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { toggleOutputToSource() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("输出到源文件路径", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "将加固产物输出到所选APK所在目录",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = outputToSource,
                                onCheckedChange = { toggleOutputToSource() }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { outputDirPickerLauncher.launch(null) },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("选择目录", fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    outputDirTextInput = outputDirCustom ?: ""
                                    showOutputDirTextDialog = true
                                },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("手动输入", fontSize = 13.sp)
                            }
                            TextButton(
                                onClick = {
                                    OutputSettings.setCustomDir(context, null)
                                    OutputSettings.setSafUri(context, null)
                                    outputDirCustom = null
                                    addLog("输出目录已恢复默认", LogType.INFO)
                                },
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("恢复默认", fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("FrostShell 引擎选项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                SettingRow(
                    icon = Icons.Default.TrendingUp,
                    title = "保留部分类",
                    subtitle = "跳过部分类加固，提升启动速度 (keep-classes)",
                    checked = frostKeepClasses,
                    onCheckedChange = {
                        frostKeepClasses = it
                        prefs.edit().putBoolean("frost_keep_classes", it).apply()
                    }
                )
                SettingRow(
                    icon = Icons.Default.Compress,
                    title = "瘦身",
                    subtitle = "以兼容性/性能换取更小产物 (smaller)",
                    checked = frostSmaller,
                    onCheckedChange = {
                        frostSmaller = it
                        prefs.edit().putBoolean("frost_smaller", it).apply()
                    }
                )
                SettingRow(
                    icon = Icons.Default.Fingerprint,
                    title = "运行时验签",
                    subtitle = "加固包运行时校验签名 (verify-sign)",
                    checked = frostVerifySign,
                    onCheckedChange = {
                        frostVerifySign = it
                        prefs.edit().putBoolean("frost_verify_sign", it).apply()
                    }
                )
                SettingRow(
                    icon = Icons.Default.FavoriteBorder,
                    title = "仅抽取指定函数",
                    subtitle = if (frostMethodFilterEnabled) {
                        "方法级抽取 (已配置${frostMethodFilterRules.lineSequence().filter { it.isNotBlank() }.count()}条规则)，未命中函数保留明文"
                    } else {
                        "方法级抽取：只抽取关键函数，其余保留 (extract-method-filter)"
                    },
                    checked = frostMethodFilterEnabled,
                    onCheckedChange = {
                        frostMethodFilterEnabled = it
                        prefs.edit().putBoolean("frost_method_filter_enabled", it).apply()
                        if (it) {
                            methodFilterInput = frostMethodFilterRules
                            methodFilterDialogTab = 0
                            showMethodFilterDialog = true
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text("代码混淆（字符串加密与 DEX pass 开关请在首页「代码混淆」卡片中管理）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("剔除 ABI（未勾选的将保留）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    allAbis.forEach { abi ->
                        val excluded = frostExcludedAbi.contains(abi)
                        FilterChip(
                            selected = excluded,
                            onClick = {
                                val newSet = frostExcludedAbi.toMutableSet()
                                if (excluded) newSet.remove(abi) else newSet.add(abi)
                                if (newSet.size < allAbis.size) {
                                    frostExcludedAbi = newSet
                                    prefs.edit().putStringSet("frost_excluded_abi", newSet).apply()
                                } else {
                                    addLog("不能剔除全部 ABI，至少保留一个", LogType.WARNING)
                                }
                            },
                            label = { Text(abi, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("签名设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (signEnabled) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(
                                text = if (signKeystorePath.isNotEmpty())
                                    "自定义keystore: ${File(signKeystorePath).name}"
                                else
                                    "默认调试keystore (adh_debug.p12)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { signKeystorePickerLauncher.launch(arrayOf("*/*")) },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("选择keystore", fontSize = 13.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        signAliasInput = signAlias
                                        signStorePassInput = signStorePass
                                        signKeyPassInput = signKeyPass
                                        showSignInfoDialog = true
                                    },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("别名/密码", fontSize = 13.sp)
                                }
                                TextButton(
                                    onClick = {
                                        signKeystorePath = ""
                                        signAlias = ""
                                        signStorePass = ""
                                        signKeyPass = ""
                                        prefs.edit()
                                            .remove("sign_keystore_path")
                                            .remove("sign_alias")
                                            .remove("sign_store_pass")
                                            .remove("sign_key_pass")
                                            .apply()
                                        addLog("已清除自定义签名，使用默认keystore", LogType.INFO)
                                    },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("清除", fontSize = 13.sp)
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    val cur = if (signKeystorePath.isNotEmpty()) File(signKeystorePath).name else "默认调试keystore"
                                    savedSigningNameInput = if (signAlias.isNotBlank()) signAlias else cur
                                    showSaveSigningDialog = true
                                },
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("保存当前签名为预设", fontSize = 13.sp)
                            }
                            if (savedSignings.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "已保存签名 (点击切换)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                savedSignings.forEach { profile ->
                                    val active = profile.keystorePath == signKeystorePath && profile.alias == signAlias
                                    Surface(
                                        color = if (active) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                signKeystorePath = profile.keystorePath
                                                signAlias = profile.alias
                                                signStorePass = profile.storePass
                                                signKeyPass = profile.keyPass
                                                prefs.edit()
                                                    .putString("sign_keystore_path", signKeystorePath)
                                                    .putString("sign_alias", signAlias)
                                                    .putString("sign_store_pass", signStorePass)
                                                    .putString("sign_key_pass", signKeyPass)
                                                    .apply()
                                                addLog("已切换到签名: ${profile.name}", LogType.INFO)
                                            }.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.VpnKey,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    profile.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val aliasShown = if (profile.alias.isNotBlank()) "alias=${profile.alias}" else "alias=自动"
                                                Text(
                                                    aliasShown + " · " + File(profile.keystorePath).name,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    val updated = savedSignings.filterNot { it.name == profile.name }
                                                    runCatching { File(profile.keystorePath).delete() }
                                                    savedSignings = updated
                                                    saveSigningProfiles(prefs, updated)
                                                    addLog("已删除签名预设: ${profile.name}", LogType.INFO)
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "删除",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    signToolKeystorePath = signKeystorePath
                                    signToolStorePass = signStorePass
                                    signToolAlias = signAlias
                                    signToolTab = 0
                                    signToolInfo = null
                                    signToolError = ""
                                    showSigningToolDialog = true
                                },
                                modifier = Modifier.fillMaxWidth().height(36.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("签名工具 · 查看/生成 keystore", fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showDictDialog) {
        AlertDialog(
            onDismissRequest = { showDictDialog = false },
            title = { Text("混淆字典生成器 v2") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "21 个字符分组多选 + 复杂度 + 自定义 Unicode/符号，生成可作类/字段重命名参考的词典；支持导出或导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    // ===== 分组多选 =====
                    Text("字符分组（可多选）：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    ObfuscationDictGenerator.getAllGroups().forEach { (groupId, groupName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dictEnabledGroups = if (dictEnabledGroups.contains(groupId)) {
                                        (dictEnabledGroups - groupId).toMutableSet()
                                    } else {
                                        (dictEnabledGroups + groupId).toMutableSet()
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = dictEnabledGroups.contains(groupId),
                                onCheckedChange = { checked ->
                                    dictEnabledGroups = if (checked) (dictEnabledGroups + groupId).toMutableSet() else (dictEnabledGroups - groupId).toMutableSet()
                                },
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(groupName, style = MaterialTheme.typography.bodySmall)
                                val preview = ObfuscationDictGenerator.getGroupPreview(groupId)
                                if (preview.isNotEmpty()) {
                                    Text(
                                        "#$groupId  " + preview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // ===== 复杂度 =====
                    Text("复杂度：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "低", 1 to "中", 2 to "高").forEach { (level, label) ->
                            FilterChip(
                                selected = dictComplexity == level,
                                onClick = { dictComplexity = level },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // ===== 词条数量 + 长度 =====
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = dictCountText,
                            onValueChange = { dictCountText = it.filter { c -> c.isDigit() } },
                            label = { Text("词条数", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1.2f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = dictMinLenText,
                            onValueChange = { dictMinLenText = it.filter { c -> c.isDigit() } },
                            label = { Text("最短", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(0.8f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = dictMaxLenText,
                            onValueChange = { dictMaxLenText = it.filter { c -> c.isDigit() } },
                            label = { Text("最长", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(0.8f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ===== 自定义 Unicode 区间 =====
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { dictShowCustomUnicode = !dictShowCustomUnicode },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = dictUseCustomUnicode, onCheckedChange = { dictUseCustomUnicode = it }, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("自定义 Unicode 区间", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                        Text(if (dictShowCustomUnicode) "▲" else "▼", style = MaterialTheme.typography.bodySmall)
                    }
                    if (dictShowCustomUnicode) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(start = 32.dp)) {
                            OutlinedTextField(
                                value = dictUnicodeStartText,
                                onValueChange = { dictUnicodeStartText = it.filter { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } },
                                label = { Text("起始(十六进制)", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = dictUnicodeEndText,
                                onValueChange = { dictUnicodeEndText = it.filter { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } },
                                label = { Text("结束(十六进制)", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // ===== 自定义符号 =====
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { dictShowCustomSymbols = !dictShowCustomSymbols },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = dictUseCustomSymbols, onCheckedChange = { dictUseCustomSymbols = it }, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("自定义符号", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                        Text(if (dictShowCustomSymbols) "▲" else "▼", style = MaterialTheme.typography.bodySmall)
                    }
                    if (dictShowCustomSymbols) {
                        OutlinedTextField(
                            value = dictSymbolsText,
                            onValueChange = { dictSymbolsText = it },
                            label = { Text("输入自定义符号字符", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.fillMaxWidth().padding(start = 32.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                    }

                    // ===== 导入信息 =====
                    if (dictImportInfo.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            dictImportInfo,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // ===== 预览 =====
                    if (dictLastInfo.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("预览（前 20 条）：", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                dictLastInfo,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 25
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { dictImporterLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                        enabled = dictUIEnabled
                    ) {
                        Text("导入")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                dictUIEnabled = false
                                val count = (dictCountText.toIntOrNull() ?: 3000).coerceIn(100, 50000)
                                val minLen = (dictMinLenText.toIntOrNull() ?: 4).coerceIn(1, 32)
                                val maxLen = (dictMaxLenText.toIntOrNull() ?: 12).coerceIn(1, 32)
                                val complex = dictComplexity
                                val groups = dictEnabledGroups.toSet()
                                val useCustomUnicode = dictUseCustomUnicode
                                val uStart = dictUnicodeStartText.toIntOrNull(16) ?: 0
                                val uEnd = dictUnicodeEndText.toIntOrNull(16) ?: 0
                                val useCustomSymbols = dictUseCustomSymbols
                                val syms = dictSymbolsText
                                val info = withContext(Dispatchers.IO) {
                                    generateObfuscationDictV2(
                                        context, count, minLen, maxLen, complex, groups,
                                        useCustomUnicode, uStart, uEnd, useCustomSymbols, syms
                                    )
                                }
                                dictLastInfo = info.first
                                dictImportInfo = info.second
                                dictUIEnabled = true
                            }
                        },
                        enabled = dictUIEnabled
                    ) {
                        Text("预览")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                dictUIEnabled = false
                                val count = (dictCountText.toIntOrNull() ?: 3000).coerceIn(100, 50000)
                                val minLen = (dictMinLenText.toIntOrNull() ?: 4).coerceIn(1, 32)
                                val maxLen = (dictMaxLenText.toIntOrNull() ?: 12).coerceIn(1, 32)
                                val groupsStr = dictEnabledGroups.sorted().joinToString(",")
                                val complex = dictComplexity
                                val exportInfo = withContext(Dispatchers.IO) {
                                    val gen = ObfuscationDictGenerator()
                                    gen.setEnabledGroups(dictEnabledGroups)
                                    gen.setComplexity(complex)
                                    gen.setLengthRange(minLen, maxLen)
                                    if (dictUseCustomUnicode) {
                                        gen.setCustomUnicodeRange(
                                            dictUnicodeStartText.toIntOrNull(16) ?: 0,
                                            dictUnicodeEndText.toIntOrNull(16) ?: 0
                                        )
                                    }
                                    if (dictUseCustomSymbols && dictSymbolsText.isNotEmpty()) {
                                        gen.setCustomSymbolChars(dictSymbolsText)
                                    }
                                    val words = gen.generate(count)
                                    val cleanWords = words.filter { it.isNotBlank() }
                                    prefs.edit()
                                        .putString("dict_enabled_groups", groupsStr)
                                        .putInt("dict_complexity", complex)
                                        .putInt("obfuscation_dict_count", count)
                                        .putInt("dict_min_len", minLen)
                                        .putInt("dict_max_len", maxLen)
                                        .putBoolean("dict_use_custom_unicode", dictUseCustomUnicode)
                                        .putInt("dict_custom_unicode_start", dictUnicodeStartText.toIntOrNull(16) ?: 0)
                                        .putInt("dict_custom_unicode_end", dictUnicodeEndText.toIntOrNull(16) ?: 0)
                                        .putBoolean("dict_use_custom_symbols", dictUseCustomSymbols)
                                        .putString("dict_custom_symbols", dictSymbolsText)
                                        .putBoolean("obfuscation_dict", true)
                                        .putStringSet("dict_imported_words", LinkedHashSet(cleanWords))
                                        .apply()
                                    exportDictToFile(context, words, groupsStr, complex, minLen, maxLen)
                                }
                                dictLastInfo = ""
                                dictImportInfo = exportInfo
                                dictUIEnabled = true
                            }
                        },
                        enabled = dictUIEnabled
                    ) {
                        Text("导出")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDictDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (showSaveSigningDialog) {
        AlertDialog(
            onDismissRequest = { showSaveSigningDialog = false },
            title = { Text("保存当前签名为预设") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "将当前 keystore/别名/密码保存为预设，可在签名设置区快速切换。keystore 会被复制到应用私有目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = savedSigningNameInput,
                        onValueChange = { savedSigningNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("预设名称") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = savedSigningNameInput.trim()
                        if (name.isEmpty()) {
                            addLog("预设名称不能为空", LogType.WARNING)
                            return@TextButton
                        }
                        val existing = savedSignings.firstOrNull { it.name == name }
                        if (existing != null) {
                            addLog("预设名称已存在: $name", LogType.WARNING)
                            return@TextButton
                        }
                        var srcPath = signKeystorePath
                        if (srcPath.isEmpty()) {
                            val debugKs = File(context.filesDir, "adh_debug.p12")
                            if (debugKs.exists()) srcPath = debugKs.absolutePath
                        }
                        val storedPath = if (srcPath.isNotEmpty()) {
                            val srcFile = File(srcPath)
                            val dir = File(context.filesDir, "saved_signings")
                            if (!dir.exists()) dir.mkdirs()
                            val dst = File(dir, sanitizeFileName(name) + "." + srcFile.extension.ifEmpty { "keystore" })
                            val copied = try {
                                srcFile.copyTo(dst, overwrite = true)
                                dst.absolutePath
                            } catch (e: Exception) {
                                null
                            }
                            copied
                        } else null
                        val profile = SigningProfile(
                            name = name,
                            keystorePath = storedPath ?: srcPath,
                            alias = signAlias,
                            storePass = signStorePass,
                            keyPass = signKeyPass
                        )
                        if (storedPath == null && profile.keystorePath.isEmpty()) {
                            addLog("当前没有可用的 keystore，无法保存", LogType.WARNING)
                            return@TextButton
                        }
                        val updated = savedSignings + profile
                        savedSignings = updated
                        saveSigningProfiles(prefs, updated)
                        addLog("已保存签名预设: $name", LogType.SUCCESS)
                        showSaveSigningDialog = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveSigningDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showSignInfoDialog) {
        AlertDialog(
            onDismissRequest = { showSignInfoDialog = false },
            title = { Text("签名别名/密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "自定义keystore的别名与密码。留空时使用keystore中第一个别名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = signAliasInput,
                        onValueChange = { signAliasInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("别名 (alias)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = signStorePassInput,
                        onValueChange = { signStorePassInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("存储密码 (store password)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = signKeyPassInput,
                        onValueChange = { signKeyPassInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密钥密码 (key password)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    signAlias = signAliasInput.trim()
                    signStorePass = signStorePassInput
                    signKeyPass = signKeyPassInput
                    prefs.edit()
                        .putString("sign_alias", signAlias)
                        .putString("sign_store_pass", signStorePass)
                        .putString("sign_key_pass", signKeyPass)
                        .apply()
                    addLog("签名别名/密码已保存", LogType.SUCCESS)
                    showSignInfoDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSignInfoDialog = false }) { Text("取消") }
            }
        )
    }

    // ===== 签名工具对话框：查看/生成 keystore =====
    if (showSigningToolDialog) {
        AlertDialog(
            onDismissRequest = { showSigningToolDialog = false },
            title = { Text("签名工具", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Tab 切换
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = signToolTab == 0,
                            onClick = { signToolTab = 0 },
                            label = { Text("查看签名信息", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = signToolTab == 1,
                            onClick = { signToolTab = 1 },
                            label = { Text("生成 keystore", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = signToolTab == 2,
                            onClick = { signToolTab = 2 },
                            label = { Text("格式转换", fontSize = 12.sp) }
                        )
                    }

                    if (signToolTab == 0) {
                        // ===== 查看签名信息 =====
                        Text(
                            "选择 keystore 文件，输入密码（及别名）后读取证书签名信息。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { signToolPickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (signToolKeystorePath.isNotEmpty())
                                    File(signToolKeystorePath).name
                                else
                                    "选择 keystore / jks / p12",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedTextField(
                            value = signToolStorePass,
                            onValueChange = { signToolStorePass = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("存储密码 (store password)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = signToolAlias,
                            onValueChange = { signToolAlias = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("别名 (alias, 可选)") },
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                signToolError = ""
                                signToolInfo = null
                                signToolLoading = true
                                val path = signToolKeystorePath
                                val pass = signToolStorePass
                                val alias = signToolAlias
                                scope.launch {
                                    val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching {
                                            com.adfxcbnm.hardeningtool.SigningTool.loadKeystoreInfo(
                                                File(path), pass, alias
                                            )
                                        }.getOrNull()
                                    }
                                    signToolLoading = false
                                    if (info == null) {
                                        signToolError = "读取失败：文件不存在 / 密码错误 / 格式不支持（支持 p12/pfx/jks/keystore/ks/bks）"
                                    } else {
                                        signToolInfo = info
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            enabled = signToolKeystorePath.isNotEmpty() && !signToolLoading,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (signToolLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("读取中...", fontSize = 13.sp)
                            } else {
                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("读取签名信息", fontSize = 13.sp)
                            }
                        }
                        if (signToolError.isNotEmpty()) {
                            Text(signToolError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        signToolInfo?.let { info ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                    InfoRow("文件", "${info.file} (${info.type})")
                                    InfoRow("别名", info.alias)
                                    InfoRow("Subject", info.subject)
                                    InfoRow("Issuer", info.issuer)
                                    InfoRow("有效期", "${info.notBefore} ~ ${info.notAfter}")
                                    InfoRow("签名算法", info.signatureAlg)
                                    InfoRow("密钥", "${info.keyAlg} ${info.keySize}")
                                    InfoRow("SHA1", info.sha1)
                                    InfoRow("SHA256", info.sha256)
                                    InfoRow("MD5", info.md5)
                                    Text(
                                        "全部别名: ${info.aliases.joinToString(", ")}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (signToolTab == 1) {
                        // ===== 生成 keystore =====
                        Text(
                            "自定义证书内容生成 keystore（PKCS12），或一键生成 RSA 4096 + 100 年有效期的超强签名。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                signToolError = ""
                                signToolGenResult = null
                                val target = File(context.filesDir, "custom_sign_keystore.p12")
                                scope.launch {
                                    val gk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        com.adfxcbnm.hardeningtool.SigningTool.generateProKeystore(
                                            target, "adh"
                                        ) { err -> signToolError = err }
                                    }
                                    signToolGenResult = gk
                                    if (gk != null) {
                                        signKeystorePath = gk.file.absolutePath
                                        signStorePass = gk.storePass
                                        signKeyPass = gk.keyPass
                                        signAlias = gk.alias
                                        prefs.edit()
                                            .putString("sign_keystore_path", gk.file.absolutePath)
                                            .putString("sign_store_pass", gk.storePass)
                                            .putString("sign_key_pass", gk.keyPass)
                                            .putString("sign_alias", gk.alias)
                                            .apply()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("一键生成超强签名 (RSA 4096 / 100年 / 随机强密码)", fontSize = 12.sp)
                        }
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("自定义生成", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        OutlinedTextField(
                            value = signToolGenAlias,
                            onValueChange = { signToolGenAlias = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("别名 (alias)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = signToolGenStorePass,
                            onValueChange = { signToolGenStorePass = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("存储密码 (store password)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = signToolGenKeyPass,
                            onValueChange = { signToolGenKeyPass = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("密钥密码 (key password)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = signToolGenCn,
                                onValueChange = { signToolGenCn = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("CN 通用名") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = signToolGenC,
                                onValueChange = { signToolGenC = it },
                                modifier = Modifier.width(70.dp),
                                label = { Text("国家 C") },
                                singleLine = true
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = signToolGenO,
                                onValueChange = { signToolGenO = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("组织 O") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = signToolGenOu,
                                onValueChange = { signToolGenOu = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("部门 OU") },
                                singleLine = true
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = signToolGenL,
                                onValueChange = { signToolGenL = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("城市 L") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = signToolGenSt,
                                onValueChange = { signToolGenSt = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("省份 ST") },
                                singleLine = true
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("密钥算法", fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("RSA", "EC").forEach { alg ->
                                    FilterChip(
                                        selected = signToolGenKeyAlg == alg,
                                        onClick = { signToolGenKeyAlg = alg },
                                        label = { Text(alg, fontSize = 12.sp) }
                                    )
                                }
                            }
                        }
                        if (signToolGenKeyAlg == "RSA") {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(2048, 3072, 4096).forEach { size ->
                                    FilterChip(
                                        selected = signToolGenKeySize == size,
                                        onClick = { signToolGenKeySize = size },
                                        label = { Text(size.toString(), fontSize = 12.sp) }
                                    )
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(256, 384, 521).forEach { size ->
                                    FilterChip(
                                        selected = signToolGenKeySize == size,
                                        onClick = { signToolGenKeySize = size },
                                        label = { Text("P-$size", fontSize = 12.sp) }
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = signToolGenNotBefore,
                                onValueChange = { signToolGenNotBefore = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("生成日期 yyyy-MM-dd") },
                                placeholder = { Text("留空=昨天") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = signToolGenNotAfter,
                                onValueChange = { signToolGenNotAfter = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("到期日期 yyyy-MM-dd") },
                                placeholder = { Text("留空=有效期天数") },
                                singleLine = true
                            )
                        }
                        Text(
                            "已自动生成标准日期；可手动覆盖：",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("快捷", fontSize = 12.sp)
                            listOf(3650, 10000, 36500).forEach { days ->
                                FilterChip(
                                    selected = signToolGenValidity == days.toString(),
                                    onClick = {
                                        signToolGenValidity = days.toString()
                                        signToolGenNotBefore = formatDateOffsetDays(-1L)
                                        signToolGenNotAfter = formatDateOffsetDays((days - 1).toLong())
                                    },
                                    label = { Text("${days / 365}年", fontSize = 12.sp) }
                                )
                            }
                        }
                        Button(
                            onClick = {
                                signToolError = ""
                                signToolGenResult = null
                                val alias = signToolGenAlias.trim().ifEmpty { "adh" }
                                val storePass = signToolGenStorePass.ifEmpty { "android" }
                                val keyPass = signToolGenKeyPass.ifEmpty { storePass }
                                val cn = signToolGenCn.trim().ifEmpty { "Android App" }
                                val target = File(context.filesDir, "custom_sign_keystore.p12")
                                val nb = signToolGenNotBefore.trim().let { if (it.isNotEmpty()) parseDateMillis(it) else null }
                                val na = signToolGenNotAfter.trim().let { if (it.isNotEmpty()) parseDateMillis(it) else null }
                                if (nb != null && na != null && na <= nb) {
                                    signToolError = "到期日期必须晚于生成日期"
                                } else {
                                val spec = com.adfxcbnm.hardeningtool.SigningTool.KeystoreSpec(
                                    alias, storePass, keyPass,
                                    signToolGenKeyAlg, signToolGenKeySize,
                                    signToolGenValidity.toIntOrNull() ?: 36500,
                                    cn, signToolGenOu.trim(), signToolGenO.trim(),
                                    signToolGenL.trim(), signToolGenSt.trim(), signToolGenC.trim().ifEmpty { "CN" },
                                    nb, na
                                )
                                scope.launch {
                                    val gk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        com.adfxcbnm.hardeningtool.SigningTool.generateKeystore(
                                            target, spec
                                        ) { err -> signToolError = err }
                                    }
                                    signToolGenResult = gk
                                    if (gk != null) {
                                        signKeystorePath = gk.file.absolutePath
                                        signStorePass = storePass
                                        signKeyPass = gk.keyPass
                                        signAlias = gk.alias
                                        prefs.edit()
                                            .putString("sign_keystore_path", gk.file.absolutePath)
                                            .putString("sign_store_pass", storePass)
                                            .putString("sign_key_pass", gk.keyPass)
                                            .putString("sign_alias", gk.alias)
                                            .apply()
                                        signToolStorePass = storePass
                                    }
                                }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("生成 keystore 并设为签名密钥", fontSize = 13.sp)
                        }
                        if (signToolError.isNotEmpty()) {
                            Text(signToolError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        signToolGenResult?.let { gk ->
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                    Text("生成成功", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    Text(gk.file.absolutePath, fontSize = 11.sp)
                                    gk.info?.let { info ->
                                        InfoRow("别名", info.alias)
                                        InfoRow("Subject", info.subject)
                                        InfoRow("有效期至", info.notAfter)
                                        InfoRow("SHA256", info.sha256)
                                    }
                                    Text(
                                        if (gk.info?.keyAlg == "RSA") "已设为当前签名密钥" else "已设为当前签名密钥",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (signToolTab == 2) {
                        // ===== 格式转换 =====
                        Text(
                            "将任意 keystore/jks/p12/pk8 转换为其他格式并可用作签名密钥。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { signToolPickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (signToolKeystorePath.isNotEmpty())
                                    File(signToolKeystorePath).name
                                else
                                    "选择 jks / p12 / pk8 / pem 源文件",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedTextField(
                            value = signToolStorePass,
                            onValueChange = { signToolStorePass = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("密码 (store/key, 可留空)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = signToolAlias,
                            onValueChange = { signToolAlias = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("别名 (alias, 可选)") },
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    signToolConvertMsg = ""
                                    val path = signToolKeystorePath
                                    val pass = signToolStorePass
                                    val alias = signToolAlias
                                    scope.launch {
                                        val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            runCatching {
                                                val sf = File(path)
                                                val bksOut = File(sf.parentFile ?: context.filesDir, sf.name.substringBeforeLast('.') + ".bks")
                                                val ok = SigningTool.convertKeystoreFormat(
                                                    sf, pass, alias.ifBlank { null }, bksOut, "BKS", pass, pass,
                                                    srcKeyPass = pass
                                                ) { e -> signToolConvertMsg = e }
                                                if (ok) "转换为 BKS: ${bksOut.absolutePath}" else ""
                                            }.getOrElse { e -> "失败: ${e.message}" }
                                        }
                                        signToolConvertOk = out.startsWith("转换为 BKS")
                                        if (signToolConvertOk && out.isNotEmpty()) {
                                            signKeystorePath = out.removePrefix("转换为 BKS: ")
                                            signAlias = alias
                                            signStorePass = pass
                                            signKeyPass = pass
                                            prefs.edit()
                                                .putString("sign_keystore_path", signKeystorePath)
                                                .putString("sign_alias", signAlias)
                                                .putString("sign_store_pass", pass)
                                                .putString("sign_key_pass", pass)
                                                .apply()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("转 BKS 并设为签名密钥", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    signToolConvertMsg = ""
                                    val path = signToolKeystorePath
                                    val pass = signToolStorePass
                                    val alias = signToolAlias
                                    scope.launch {
                                        val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            runCatching {
                                                val sf = File(path)
                                                val p12Out = File(sf.parentFile ?: context.filesDir, sf.name.substringBeforeLast('.') + ".p12")
                                                val ok = SigningTool.convertToP12(
                                                    sf, pass, alias.ifBlank { null }, p12Out, pass, pass
                                                ) { e -> signToolConvertMsg = e }
                                                if (ok) "转换为 P12: ${p12Out.absolutePath}" else ""
                                            }.getOrElse { e -> "失败: ${e.message}" }
                                        }
                                        signToolConvertOk = out.startsWith("转换为 P12")
                                        if (signToolConvertOk && out.isNotEmpty()) {
                                            signKeystorePath = out.removePrefix("转换为 P12: ")
                                            signStorePass = pass
                                            prefs.edit()
                                                .putString("sign_keystore_path", signKeystorePath)
                                                .putString("sign_store_pass", pass)
                                                .apply()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("转 P12 并设为签名密钥", fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                signToolConvertMsg = ""
                                val path = signToolKeystorePath
                                val pass = signToolStorePass
                                val alias = signToolAlias
                                scope.launch {
                                    val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        runCatching {
                                            val sf = File(path)
                                            val base = sf.name.substringBeforeLast('.').ifEmpty { sf.name }
                                            val pk8Out = File(sf.parentFile ?: context.filesDir, "$base.pk8")
                                            val pemOut = File(sf.parentFile ?: context.filesDir, "$base.x509.pem")
                                            val ok = SigningTool.exportPk8Pem(
                                                sf, pass, alias.ifBlank { null }, pk8Out, pemOut
                                            ) { e -> signToolConvertMsg = e }
                                            if (ok) "导出 PK8/PEM:\n${pk8Out.absolutePath}\n${pemOut.absolutePath}" else ""
                                        }.getOrElse { e -> "失败: ${e.message}" }
                                    }
                                    signToolConvertOk = out.startsWith("导出 PK8")
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("导出 PK8/PEM", fontSize = 13.sp)
                        }
                        if (signToolConvertMsg.isNotEmpty()) {
                            Text(
                                signToolConvertMsg,
                                color = if (signToolConvertOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSigningToolDialog = false
                    signToolInfo = null
                    signToolGenResult = null
                }) { Text("完成") }
            }
        )
    }

    if (showOutputDirTextDialog) {
        AlertDialog(
            onDismissRequest = { showOutputDirTextDialog = false },
            title = { Text("手动输入输出目录") },
            text = {
                Column {
                    Text(
                        "输入绝对路径，例如 /storage/emulated/0/ADFXCBNM。路径不可写或无法创建时，流程将回退默认目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = outputDirTextInput,
                        onValueChange = { outputDirTextInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("/storage/emulated/0/ADFXCBNM") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = outputDirTextInput.trim()
                    if (p.isNotEmpty() && OutputSettings.isWritable(p)) {
                        OutputSettings.setCustomDir(context, p)
                        outputDirCustom = p
                        addLog("输出目录已设置: $p", LogType.SUCCESS)
                        showOutputDirTextDialog = false
                    } else {
                        addLog("路径不可写或无法创建，请重新输入", LogType.WARNING)
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showOutputDirTextDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDisguiseDialog) {
        val disguisePresets = remember {
            try {
                val json = context.assets.open("so_name_presets.json").bufferedReader().use { it.readText() }
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    Triple(obj.getString("title"), obj.getString("name"), obj.getString("category"))
                }
            } catch (e: Exception) { emptyList() }
        }
        var disguiseCustomInput by remember { mutableStateOf(frostDisguiseName) }
        AlertDialog(
            onDismissRequest = { showDisguiseDialog = false },
            title = { Text("伪装SO名称") },
            text = {
                Column {
                    Text(
                        "选择厂商预设（伪装为对应加固方案），或在底部输入自定义名称。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    val grouped = disguisePresets.groupBy { it.third }
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                        grouped.forEach { (category, presets) ->
                            item(key = "cat_$category") {
                                Text(
                                    category,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(presets.size, key = { idx -> "${category}_$idx" }) { idx ->
                                val (title, name, _) = presets[idx]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            frostDisguiseName = name
                                            prefs.edit().putString("frost_disguise_name", name).apply()
                                            showDisguiseDialog = false
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = frostDisguiseName == name, onClick = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(title, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "lib$name.so",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = disguiseCustomInput,
                        onValueChange = { disguiseCustomInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自定义名称") },
                        placeholder = { Text("例如 myshell") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val custom = disguiseCustomInput.trim()
                    if (custom.isNotEmpty() && Regex("[A-Za-z0-9_-]+").matches(custom)) {
                        frostDisguiseName = custom
                        prefs.edit().putString("frost_disguise_name", custom).apply()
                        showDisguiseDialog = false
                    } else {
                        addLog("自定义SO名称仅支持字母/数字/下划线/中划线", LogType.WARNING)
                    }
                }) { Text("使用自定义") }
            },
            dismissButton = {
                TextButton(onClick = { showDisguiseDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showMethodFilterDialog) {
        LaunchedEffect(showMethodFilterDialog) {
            methodFilterPresetList = MethodRuleTemplate.getCustomPresets(context)
            methodFilterPresetName = ""
            methodFilterInput = frostMethodFilterRules
        }

        fun statHits(rulesText: String) {
            val uri = selectedApkUri ?: run {
                methodHitSummary = "未选择 APK，无法统计命中数"
                return
            }
            if (methodHitLoading) return
            methodHitLoading = true
            methodHitSummary = ""
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    methodScanResult?.let { runCatching { it } }?.getOrNull()
                        ?: runCatching {
                            val path = OutputSettings.resolveApkPath(context, uri)
                            val file = if (path != null && File(path).exists()) File(path)
                            else {
                                val tmp = File(context.cacheDir, "rule_hit_scan.apk")
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    tmp.outputStream().use { out -> input.copyTo(out) }
                                }
                                tmp
                            }
                            ApkMethodScanner.scan(file).also { methodScanResult = it }
                        }.getOrNull()
                }
                methodHitLoading = false
                if (result == null) {
                    methodHitSummary = "扫描失败"
                } else {
                    val hits = ApkMethodScanner.countHits(result.entries, rulesText)
                    val pct = if (result.entries.isEmpty()) 0 else (hits * 100 / result.entries.size)
                    methodHitSummary = "当前规则将抽取 $hits / ${result.entries.size} 个方法 (约 $pct%)"
                }
            }
        }
        LaunchedEffect(methodFilterInput, methodFilterDialogTab) {
            if (methodFilterDialogTab == 0 && methodFilterInput.isNotBlank()) {
                statHits(methodFilterInput)
            }
        }
        AlertDialog(
            onDismissRequest = { showMethodFilterDialog = false },
            title = { Text("仅抽取指定函数") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Tab 切换：规则编辑 / 方案模板
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = methodFilterDialogTab == 0,
                            onClick = { methodFilterDialogTab = 0 },
                            label = { Text("规则编辑", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = methodFilterDialogTab == 1,
                            onClick = { methodFilterDialogTab = 1 },
                            label = { Text("方案模板", fontSize = 12.sp) }
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    if (methodFilterDialogTab == 0) {
                        // ===== Tab0：规则编辑 =====
                        OutlinedTextField(
                            value = methodFilterInput,
                            onValueChange = { methodFilterInput = it },
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            label = { Text("方法抽取规则（每行一条）") },
                            placeholder = { Text("Lcom/example/MainActivity;.onCreate\nLcom/example/SecretApi;.*\n.*vip.*") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        Spacer(Modifier.height(6.dp))
                        // 实时统计
                        val lines = methodFilterInput.lineSequence().filter { it.isNotBlank() }.toList()
                        Text(
                            buildString {
                                append("共 ${lines.size} 条")
                                val exact = lines.count { !it.contains('*') && !it.startsWith("regex:") }
                                val wildcard = lines.count { it.contains('*') && !it.startsWith("regex:") }
                                val regex = lines.count { it.startsWith("regex:") }
                                if (exact > 0) append(" · 精确 $exact")
                                if (wildcard > 0) append(" · 通配 $wildcard")
                                if (regex > 0) append(" · 正则 $regex")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        // 命中统计
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (methodHitLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text("统计中...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (methodHitSummary.isNotEmpty()) {
                                Text(
                                    methodHitSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (methodHitSummary.startsWith("当前规则")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            TextButton(onClick = { statHits(methodFilterInput) }, modifier = Modifier.height(30.dp)) {
                                Text("重新统计", fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // 语法说明（可折叠）
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { methodFilterHelpExpanded = !methodFilterHelpExpanded }) {
                            Icon(
                                if (methodFilterHelpExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("规则语法说明", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (methodFilterHelpExpanded) {
                            Text(
                                MethodRuleTemplate.FORMAT_HELP,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (selectedApkUri == null) {
                                    addLog("请先选择 APK 再浏览其方法", LogType.WARNING)
                                } else {
                                    showMethodBrowser = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("浏览应用方法/类（搜索勾选生成规则）")
                        }
                    } else {
                        // ===== Tab1：方案模板 =====
                        Text("内置关键词模板：保存常用关键词规则到输入框", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    val kw = listOf("vip", "data", "info", "time", "login", "premium", "auth", "token", "user", "account", "verify", "pay", "check", "config", "session", "profile", "decrypt", "encrypt")
                                    val rules = kw.joinToString("\n") { ".*$it.*" }
                                    methodFilterInput = if (methodFilterInput.isBlank()) rules
                                    else methodFilterInput.trimEnd() + "\n" + rules
                                    methodFilterDialogTab = 0
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("生成常用关键词") }
                            Button(
                                onClick = {
                                    val kw = listOf("login", "account", "password", "secret", "key", "encrypt", "decrypt", "verify", "pay", "vip", "premium")
                                    val rules = kw.joinToString("\n") { ".*$it.*" }
                                    methodFilterInput = rules
                                    methodFilterDialogTab = 0
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("敏感业务关键词") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = methodFilterPresetName,
                                onValueChange = { methodFilterPresetName = it },
                                modifier = Modifier.weight(1f).height(56.dp),
                                label = { Text("方案名") }
                            )
                            Button(onClick = {
                                if (methodFilterPresetName.isNotBlank() && methodFilterInput.isNotBlank()) {
                                    MethodRuleTemplate.savePreset(context, methodFilterPresetName.trim(), methodFilterInput)
                                    methodFilterPresetList = MethodRuleTemplate.getCustomPresets(context)
                                    addLog("方案已保存: ${methodFilterPresetName.trim()}", LogType.SUCCESS)
                                }
                            }) { Text("保存方案") }
                        }
                        Spacer(Modifier.height(8.dp))
                        val allPresets = MethodRuleTemplate.BUILTIN_PRESETS + methodFilterPresetList
                        Text("方案列表（点击加载并统计命中数，右侧 X 删除自定义）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.height(200.dp)) {
                            items(allPresets, key = { "${it.isBuiltin}_${it.name}" }) { preset ->
                                val ruleCount = preset.rules.lineSequence().filter { it.isNotBlank() }.count()
                                val isCurrent = preset.rules.trim() == methodFilterInput.trim()
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            methodFilterInput = preset.rules
                                            methodFilterDialogTab = 0
                                            statHits(preset.rules)
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = if (isCurrent) ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ) else ButtonDefaults.outlinedButtonColors()
                                    ) {
                                        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                "${if (preset.isBuiltin) "☆ " else ""}${preset.name}",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                "$ruleCount 条规则",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (!preset.isBuiltin) {
                                        IconButton(onClick = {
                                            MethodRuleTemplate.deletePreset(context, preset.name)
                                            methodFilterPresetList = MethodRuleTemplate.getCustomPresets(context)
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "删除", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    frostMethodFilterRules = methodFilterInput.trim()
                    prefs.edit().putString("frost_method_filter_rules", frostMethodFilterRules).apply()
                    showMethodFilterDialog = false
                    addLog(
                        if (frostMethodFilterRules.isBlank()) "方法过滤已清空（全量抽取）" else "方法过滤规则已保存: ${frostMethodFilterRules.trim().lineSequence().count { it.isNotBlank() }}条",
                        LogType.INFO
                    )
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showMethodFilterDialog = false }) { Text("取消") }
            }
        )
    }

    if (showMethodBrowser) {
        MethodBrowserDialog(
            apkUri = selectedApkUri,
            apkName = selectedApkName,
            onDismiss = { showMethodBrowser = false },
            onAppendRules = { newRules ->
                if (newRules.isNotBlank()) {
                    methodFilterInput = if (methodFilterInput.isBlank()) newRules
                    else methodFilterInput.trimEnd() + "\n" + newRules
                    addLog("已追加 ${newRules.lineSequence().count { it.isNotBlank() }} 条规则", LogType.INFO)
                }
            },
            onError = { msg -> addLog(msg, LogType.ERROR) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureSection(
    title: String,
    subtitle: String,
    items: List<MutableFeatureItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    accentColor: Color
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column {
            Surface(
                color = Color.Transparent,
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = accentColor.copy(alpha = 0.12f),
                            shape = CircleShape,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (title == "加固") Icons.Default.Shield else Icons.Default.Security,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (subtitle.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        val selectedCount = items.count { it.isSelected }
                        if (selectedCount > 0) {
                            Surface(
                                color = accentColor.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "$selectedCount",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
                    items.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowItems.forEach { item ->
                                FeatureChip(
                                    item = item,
                                    modifier = Modifier.weight(1f),
                                    accentColor = accentColor
                                )
                            }
                            repeat(2 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showSwitch: Boolean = true,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailingHint: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailingHint != null) {
            Text(trailingHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.padding(end = 6.dp))
        }
        if (showSwitch) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showSwitch: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showSwitch) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(
            value,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 解析 yyyy-MM-dd 为毫秒（本地时区当天 00:00），失败返回 null */
private fun parseDateMillis(s: String): Long? {
    return try {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        df.isLenient = false
        df.parse(s)?.time
    } catch (e: Exception) {
        null
    }
}

/** 返回距今天 offsetDays 天的标准日期（yyyy-MM-dd），用于签名日期自动预填 */
private fun formatDateOffsetDays(offsetDays: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_YEAR, offsetDays.toInt())
    return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureChip(item: MutableFeatureItem, modifier: Modifier = Modifier, accentColor: Color = MaterialTheme.colorScheme.primary) {
    FilterChip(
        selected = item.isSelected,
        onClick = { item.isSelected = !item.isSelected },
        label = {
            Text(
                item.name,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        },
        modifier = modifier.height(32.dp),
        leadingIcon = {
            Icon(
                item.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accentColor.copy(alpha = 0.15f),
            selectedLabelColor = accentColor
        )
    )
}

suspend fun getInstalledApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val apps = pm.getInstalledApplications(0)
    apps.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        .sortedBy { pm.getApplicationLabel(it).toString() }
        .map { app ->
            AppInfo(
                name = pm.getApplicationLabel(app).toString(),
                packageName = app.packageName,
                icon = try { pm.getApplicationIcon(app) } catch (e: Exception) { null }
            )
        }
}

data class ProcessResult(
    val success: Boolean,
    val path: String,
    val size: Long,
    val sizeDiff: String,
    val progress: Float,
    val dexCount: Int = 0,
    val soCount: Int = 0,
    val hasArsc: Boolean = false
)

/**
 * 直接打开加固产物 APK（触发系统安装流程）。
 * resultPath 可能是文件绝对路径或 content:// URI。
 * content URI 直接使用；文件路径经 FileProvider 转为可共享的 content URI。
 */
private fun openResultApk(context: Context, path: String) {
    try {
        if (path.isBlank()) return
        val uri: Uri = if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            val apkFile = File(path)
            if (!apkFile.exists() || apkFile.length() <= 0) return
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "openResultApk failed: ${e.message}")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("path", path))
        android.widget.Toast.makeText(context, "打开失败，路径已复制: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}





private fun calculateSha256(data: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(data).joinToString("") { "%02x".format(it) }
}

private fun calculateCrc32(data: ByteArray): Long {
    val crc = CRC32()
    crc.update(data)
    return crc.value
}

private fun streamCopyZipEntry(zis: ZipInputStream, zos: ZipOutputStream, entry: ZipEntry, name: String): Long {
    val newEntry = ZipEntry(name)
    val stored = entry.method == ZipEntry.STORED && entry.size >= 0 && entry.crc != -1L
    if (stored) {
        newEntry.method = ZipEntry.STORED
        newEntry.size = entry.size
        newEntry.compressedSize = entry.size
        newEntry.crc = entry.crc
    } else {
        newEntry.method = ZipEntry.DEFLATED
    }
    zos.putNextEntry(newEntry)
    val buf = ByteArray(65536)
    var n: Int
    val crc = CRC32()
    while (zis.read(buf).also { n = it } > 0) {
        zos.write(buf, 0, n)
        crc.update(buf, 0, n)
    }
    zos.closeEntry()
    return crc.value
}

private fun ByteArray.indexOf(sub: ByteArray): Int {
    if (sub.isEmpty() || size < sub.size) return -1
    for (i in 0..size - sub.size) {
        var match = true
        for (j in sub.indices) {
            if (this[i + j] != sub[j]) { match = false; break }
        }
        if (match) return i
    }
    return -1
}



private fun verifyHardenedApk(file: File, addDetail: (String) -> Unit): Pair<Int, Int> {
    var pass = 0
    var total = 0
    try {
        val zip = ZipFile(file)
        // 1. 清单注入检查
        total++
        val manifestEntry = zip.getEntry("AndroidManifest.xml")
        if (manifestEntry != null) {
            val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            val target = "SecurityCheckProvider".toByteArray(Charsets.UTF_8)
            val target16 = "SecurityCheckProvider".toByteArray(Charsets.UTF_16LE)
            val found = bytes.indexOf(target) >= 0 || bytes.indexOf(target16) >= 0
            if (found) {
                addDetail("  ✓ 清单: SecurityCheckProvider 已注入")
                pass++
            } else {
                addDetail("  ✗ 清单: SecurityCheckProvider 未找到")
            }
        } else {
            addDetail("  ✗ 清单: AndroidManifest.xml 缺失")
        }
        // 2. DEX注入检查
        total++
        val dexCount = zip.entries().asSequence().count { it.name.matches(Regex("classes\\d*\\.dex")) }
        if (dexCount >= 2) {
            addDetail("  ✓ DEX: 共${dexCount}个classes*.dex")
            pass++
        } else {
            addDetail("  ✗ DEX: 仅${dexCount}个，注入可能不完整")
        }
        // 3. SO注入检查
        total++
        val soAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").filter {
            zip.getEntry("lib/$it/libsecurity_check.so") != null
        }
        if (soAbis.isNotEmpty()) {
            addDetail("  ✓ SO: 已注入 ${soAbis.joinToString(",")}")
            pass++
        } else {
            addDetail("  ✗ SO: 未找到native库")
        }
        // 4. 配置文件检查
        total++
        val cfgEntry = zip.getEntry("assets/features.cfg")
        if (cfgEntry != null) {
            val cfg = zip.getInputStream(cfgEntry).use { String(it.readBytes(), Charsets.UTF_8) }
            val hasFeatures = cfg.lines().any { it.startsWith("features=") && it.length > 9 }
            val hasSig = cfg.lines().any { it.startsWith("signature_sha256=") && it.length > 18 }
            val hasCrc = cfg.lines().any { (it.startsWith("dex_crc=") || it.startsWith("dex_crc_entry=")) && it.length > 8 }
            if (hasFeatures && hasSig && hasCrc) {
                addDetail("  ✓ 配置: features+签名基线+dexCRC 齐全")
                pass++
            } else {
                addDetail("  ✓ 配置: 已注入 (features=$hasFeatures,签名基线=$hasSig,dexCRC=$hasCrc)")
                pass++
            }
        } else {
            addDetail("  ✗ 配置: features.cfg 缺失")
        }
        // 5. 签名检查
        total++
        val hasSigFiles = zip.entries().asSequence().any { it.name.startsWith("META-INF/") && (it.name.endsWith(".RSA") || it.name.endsWith(".SF")) }
        if (hasSigFiles) {
            addDetail("  ✓ 签名: META-INF签名文件存在")
            pass++
        } else {
            addDetail("  ✗ 签名: 未找到签名文件")
        }
        zip.close()
    } catch (e: Exception) {
        addDetail("  ✗ 校验异常: ${e.message}")
    }
    return Pair(pass, total)
}

private suspend fun processApk(
    context: Context,
    apkUri: Uri,
    apkName: String,
    hardening: List<String>,
    protection: List<String>,
    addLog: (String, LogType) -> Unit,
    detailLog: (String) -> Unit,
    onProgress: (Float) -> Unit,
    timestampedOutput: Boolean,
    autoVerify: Boolean,
    signEnabled: Boolean,
    signKeystorePath: String?,
    signAlias: String?,
    signStorePass: String?,
    signKeyPass: String?
): ProcessResult = withContext(Dispatchers.IO) {
    val t0 = System.currentTimeMillis()
    val stages = StageTracker(addLog)
    lateinit var outputFile: File
    var tempFile: File? = null
    var successfulOutput: String? = null
    var signedFileSize: Long = 0
    try {
        val sourceApkPath = OutputSettings.resolveApkPath(context, apkUri)
        val (outputDir, dirSource) = OutputSettings.getOutputDir(context, sourceApkPath)
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            addLog("无法创建输出目录: ${outputDir.absolutePath}", LogType.ERROR)
            return@withContext ProcessResult(false, "", 0, "", 0f)
        }
        addLog("输出目录($dirSource): ${outputDir.absolutePath}", LogType.INFO)

        val baseName = apkName.replace(".apk", "")
        val suffix = if (timestampedOutput) "_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}" else ""
        outputFile = File(outputDir, "${baseName}_hardened${suffix}.apk")
        tempFile = File(outputDir, "${baseName}_temp_${System.currentTimeMillis()}.apk")

        // Step 1: Stream APK to temp file (supports large APKs without OOM)
        stages.begin("读取源APK")
        val inputStream = context.contentResolver.openInputStream(apkUri)
        if (inputStream == null) {
            stages.end(LogType.ERROR, "无法读取")
            addLog("无法读取源APK文件", LogType.ERROR)
            return@withContext ProcessResult(false, "", 0, "", 0f)
        }
        var apkSize: Long = 0
        try {
            FileOutputStream(tempFile).use { fos ->
                val buf = ByteArray(65536)
                var n: Int
                while (inputStream.read(buf).also { n = it } > 0) {
                    fos.write(buf, 0, n)
                    apkSize += n
                }
            }
            inputStream.close()
        } catch (e: Exception) {
            stages.end(LogType.ERROR, e.message)
            addLog("读取APK失败: ${e.message}", LogType.ERROR)
            return@withContext ProcessResult(false, "", 0, "", 0f)
        }
        stages.end(LogType.SUCCESS, "${apkSize / 1024}KB")
        onProgress(0.05f)
        detailLog("APK大小: ${apkSize / 1024 / 1024}MB")

        // Step 2: Resolve target package name
        val targetPkg = try {
            context.packageManager.getPackageArchiveInfo(tempFile.absolutePath, 0)?.packageName ?: ""
        } catch (e: Exception) { "" }
        detailLog("目标包名: ${targetPkg.ifEmpty { "未知" }}")
        detailLog("输出: ${outputFile.name}")
        val log = detailLog

        // Step 3: Load signing keystore once
        var certSha256 = ""
        var signKey: PrivateKey? = null
        var signCert: X509Certificate? = null
        if (signEnabled) {
            try {
                if (!signKeystorePath.isNullOrBlank()) {
                    val customKsFile = File(signKeystorePath)
                    if (!customKsFile.exists()) {
                        addLog("自定义签名keystore不存在: ${customKsFile.absolutePath}，回退默认签名", LogType.WARNING)
                    } else {
                        val storePass = signStorePass?.toCharArray() ?: "android".toCharArray()
                        val keyPass = signKeyPass?.toCharArray() ?: storePass
                        val pair = loadSigningKeyPair(customKsFile, signAlias, storePass, keyPass)
                        if (pair != null) {
                            signKey = pair.first
                            signCert = pair.second
                            detailLog("使用自定义签名keystore: ${customKsFile.name} (alias=${signAlias?.takeIf { it.isNotBlank() } ?: "自动"})")
                        } else {
                            addLog("自定义keystore解码失败(支持 p12/pfx/jks/keystore/bks)，回退默认签名", LogType.WARNING)
                        }
                    }
                }
                if (signKey == null) {
                    val keystoreFile = File(context.filesDir, "adh_debug.p12")
                    if (!keystoreFile.exists()) {
                        generateDebugKeystore(keystoreFile, addLog)
                    }
                    val keyStore = java.security.KeyStore.getInstance("PKCS12")
                    FileInputStream(keystoreFile).use { fis ->
                        keyStore.load(fis, "android".toCharArray())
                    }
                    val alias0 = keyStore.aliases().nextElement()
                    signKey = keyStore.getKey(alias0, "android".toCharArray()) as PrivateKey
                    signCert = keyStore.getCertificate(alias0) as X509Certificate
                }
                certSha256 = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(signCert!!.encoded)
                    .joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                addLog("读取签名证书失败，跳过防二次打包基线: ${e.message}", LogType.WARNING)
            }
        } else {
            detailLog("签名已禁用，将输出未签名APK")
        }
        if (certSha256.isNotEmpty()) detailLog("签名基线: ${certSha256.take(16)}...")

        val allFeatures = hardening + protection

        // Step 4: Scan entries using ZipFile (random access, memory efficient)
        stages.begin("分析源APK")
        val integrityHashes = mutableMapOf<String, String>()
        var dexCount = 0
        var soCount = 0
        val existingEntries = mutableSetOf<String>()
        ZipFile(tempFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                existingEntries.add(entry.name)
                if (!entry.isDirectory) {
                    if (entry.name.endsWith(".dex")) dexCount++
                    if (entry.name.endsWith(".so")) soCount++
                    if (entry.name.endsWith(".dex") || entry.name == "AndroidManifest.xml") {
                        val md = MessageDigest.getInstance("SHA-256")
                        zip.getInputStream(entry).use { ins ->
                            val buf = ByteArray(65536)
                            var n: Int
                            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
                        }
                        integrityHashes[entry.name] = md.digest().joinToString("") { "%02x".format(it) }
                    }
                }
            }
        }
        onProgress(0.2f)
        detailLog("源APK: DEX=${dexCount}个, SO=${soCount}个, 条目=${existingEntries.size}个")
        stages.end(LogType.SUCCESS, "DEX=${dexCount} SO=${soCount}")

        val manifestJson = buildProtectionJson(allFeatures, apkSize, integrityHashes)
        val configBytes = generateProtectionConfig(allFeatures)
        onProgress(0.4f)

        var manifestName = "assets/protection_manifest.json"
        var manifestSuffix = 1
        while (existingEntries.contains(manifestName)) { manifestName = "assets/protection_manifest_$manifestSuffix.json"; manifestSuffix++ }

        var configName = "assets/protection_config.dat"
        var configSuffix = 1
        while (existingEntries.contains(configName)) { configName = "assets/protection_config_$configSuffix.dat"; configSuffix++ }

         val entriesToAdd = linkedSetOf(manifestName, configName, "assets/features.cfg")

        val deviceAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val targetAbis = listOf(deviceAbi, "arm64-v8a", "armeabi-v7a", "x86", "x86_64").distinct()
        val soTargets = targetAbis.filter { abi ->
            try {
                context.assets.open("lib/$abi/libsecurity_check.so").use { it.readBytes() }.isNotEmpty()
            } catch (e: Exception) { false }
        }
        entriesToAdd.addAll(soTargets.map { "lib/$it/libsecurity_check.so" })

        val enabledFeatures = allFeatures.mapNotNull { PROTECTION_FEATURE_MAP[it] }
        detailLog("═══ 已选功能: ${enabledFeatures.size}项 ═══")
        detailLog("── 加固 ──")
        hardening.forEach { detailLog("  ✓ $it") }
        detailLog("── 保护 ──")
        protection.forEach { detailLog("  ✓ $it") }

        var manifestModified = false
        val dexCrcMap = linkedMapOf<String, Long>()

        // Create intermediate file for first pass output
        val intermediateFile = File(outputDir, "${baseName}_intermediate_${System.currentTimeMillis()}.apk")

        stages.begin("注入加固资源")
        // First pass: copy all files with modifications from tempFile to intermediateFile
        ZipInputStream(FileInputStream(tempFile).buffered()).use { zis ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(intermediateFile), BUFFER_SIZE)).use { zos ->
                var entry = zis.nextEntry

                while (entry != null) {
                    val name = entry.name
                    if (name.isNotEmpty() && !entriesToAdd.contains(name)) {
                        if (name == "AndroidManifest.xml") {
                            val data = zis.readBytes()
                            try {
                                val modResult = AndroidManifestModifier.modifyManifest(
                                    data, context, enabledFeatures, targetPkg,
                                    log = { msg -> log("manifest: $msg") },
                                    appAttrs = emptyList()
                                )
                                manifestModified = modResult.modified
                                if (manifestModified) {
                                    val authority = "com.adfxcbnm.authority" + if (targetPkg.isNotEmpty()) ".$targetPkg" else ""
                                    addLog("清单注入: SecurityCheckProvider", LogType.SUCCESS)
                                    log("authority: $authority")
                                } else {
                                    addLog("清单注入失败", LogType.WARNING)
                                }
                                val outData = modResult.data
                                val newEntry = ZipEntry(name)
                                newEntry.method = ZipEntry.DEFLATED
                                zos.putNextEntry(newEntry)
                                zos.write(outData)
                                zos.closeEntry()
                            } catch (e: Exception) {
                                addLog("清单注入失败: ${e.message}", LogType.WARNING)
                                val newEntry = ZipEntry(name)
                                newEntry.method = ZipEntry.DEFLATED
                                zos.putNextEntry(newEntry)
                                zos.write(data)
                                zos.closeEntry()
                            }
                        } else {
                            val crc = streamCopyZipEntry(zis, zos, entry, name)
                            if (Regex("classes\\d*\\.dex").matches(name)) {
                                dexCrcMap[name] = crc
                            }
                        }
                    }
                    entry = zis.nextEntry
                }

                // Inject config files
                zos.putNextEntry(ZipEntry(manifestName).apply { method = ZipEntry.DEFLATED })
                zos.write(manifestJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(configName).apply { method = ZipEntry.DEFLATED })
                zos.write(configBytes)
                zos.closeEntry()

                // Inject SecurityCheckProvider DEX
                var injectedDexSize = 0
                try {
                    val dexAsset = context.assets.open("security_check.dex")
                    val dexBytes = dexAsset.use { it.readBytes() }
                    if (dexBytes.isNotEmpty()) {
                        var secDexName = "classes2.dex"
                        var secDexSuffix = 2
                        while (existingEntries.contains(secDexName)) {
                            secDexName = "classes${secDexSuffix}.dex"
                            secDexSuffix++
                        }
                        zos.putNextEntry(ZipEntry(secDexName).apply { method = ZipEntry.DEFLATED })
                        zos.write(dexBytes)
                        zos.closeEntry()
                        dexCrcMap[secDexName] = CRC32().apply { update(dexBytes) }.value
                        injectedDexSize = dexBytes.size
                        addLog("DEX注入: $secDexName (${dexBytes.size}字节)", LogType.SUCCESS)
                    }
                } catch (e: Exception) {
                    addLog("DEX注入异常: ${e.message}", LogType.WARNING)
                }

                // Inject native libraries for each ABI
                // 覆盖注入最新版SO，确保与新DEX匹配
                val injectedAbis = mutableListOf<String>()
                val replacedAbis = mutableListOf<String>()
                for (targetAbi in soTargets) {
                    try {
                        val soAsset = context.assets.open("lib/$targetAbi/libsecurity_check.so")
                        val soBytes = soAsset.use { it.readBytes() }
                        if (soBytes.isNotEmpty()) {
                            val targetName = "lib/$targetAbi/libsecurity_check.so"
                            val existed = existingEntries.contains(targetName)
                            val soEntry = ZipEntry(targetName).apply {
                                method = ZipEntry.STORED
                                size = soBytes.size.toLong()
                                compressedSize = soBytes.size.toLong()
                                crc = CRC32().apply { update(soBytes) }.value
                            }
                            zos.putNextEntry(soEntry)
                            zos.write(soBytes)
                            zos.closeEntry()
                            if (existed) replacedAbis.add(targetAbi) else injectedAbis.add(targetAbi)
                        }
                    } catch (e: Exception) {
                        // ABI not available in assets, skip
                    }
                }
                if (injectedAbis.isNotEmpty()) {
                    addLog("SO注入: ${injectedAbis.joinToString(",")}", LogType.SUCCESS)
                }
                if (replacedAbis.isNotEmpty()) {
                    addLog("SO注入: 已存在并覆盖为最新版 ${replacedAbis.joinToString(",")}", LogType.SUCCESS)
                }
                if (injectedAbis.isEmpty() && replacedAbis.isEmpty()) {
                    addLog("SO跳过，运行时使用纯Java检测", LogType.INFO)
                }

                // Inject feature config with cert baseline and dex CRC
                val featureConfig = buildFeatureConfig(enabledFeatures, certSha256, dexCrcMap)
                zos.putNextEntry(ZipEntry("assets/features.cfg").apply { method = ZipEntry.DEFLATED })
                zos.write(featureConfig)
                zos.closeEntry()

                // Log injection status
                detailLog("═══ 注入状态 ═══")
                detailLog("  ${if (manifestModified) "✓" else "✗"} 清单: SecurityCheckProvider")
                detailLog("  ${if (injectedDexSize > 0) "✓" else "✗"} DEX: classes2.dex (${injectedDexSize}字节)")
                detailLog("  ${if (injectedAbis.isNotEmpty()) "✓" else "✗"} SO: ${injectedAbis.joinToString(",").ifEmpty { "跳过" }}")
                detailLog("  ✓ 配置: features.cfg (${enabledFeatures.size}项功能)")
                detailLog("  ${if (certSha256.isNotEmpty()) "✓" else "✗"} 签名基线: ${certSha256.take(16)}...")
                detailLog("  ✓ DEX CRC: ${dexCrcMap.size}个基线")
                stages.end(
                    LogType.SUCCESS,
                    "清单${if (manifestModified) "✓" else "✗"} DEX=${injectedDexSize}字节 SO=${injectedAbis.joinToString(",").ifEmpty { "无" }}"
                )
            }
        }
        onProgress(0.6f)

        if (!signEnabled) {
            if (!intermediateFile.exists() || intermediateFile.length() <= 0) {
                addLog("中间文件不存在或为空", LogType.ERROR)
                return@withContext ProcessResult(false, "", 0, "", 0f)
            }
            stages.begin("跳过签名")
            try {
                val saved = OutputSettings.copyOutput(context, intermediateFile, outputFile)
                signedFileSize = intermediateFile.length()
                if (saved != null) {
                    addLog("签名已禁用，输出未签名APK", LogType.WARNING)
                    successfulOutput = saved
                } else {
                    addLog("签名已禁用，但输出落盘失败", LogType.ERROR)
                    return@withContext ProcessResult(false, "", 0, "", 0f)
                }
                stages.end(LogType.WARNING, "未签名")
            } catch (copyErr: Exception) {
                stages.end(LogType.ERROR, copyErr.message)
                addLog("复制输出失败: ${copyErr.message}", LogType.ERROR)
                return@withContext ProcessResult(false, "", 0, "", 0f)
            }
        } else {
            if (signKey == null || signCert == null) {
                addLog("签名证书不可用，无法签名", LogType.ERROR)
                return@withContext ProcessResult(false, "", 0, "", 0f)
            }
            stages.begin("签名")
            try {
                try {
                    Class.forName("com.android.apksig.ApkSigner")
                } catch (e: ClassNotFoundException) {
                    addLog("apksig库不可用，无法签名", LogType.ERROR)
                    return@withContext ProcessResult(false, "", 0, "", 0f)
                }
                val outputParent = outputFile.parentFile
                if (outputParent != null && !outputParent.exists() && !outputParent.mkdirs()) {
                    addLog("输出目录不存在且创建失败: ${outputParent.absolutePath}", LogType.ERROR)
                    return@withContext ProcessResult(false, "", 0, "", 0f)
                }
                if (outputFile.exists()) {
                    runCatching { outputFile.delete() }
                        .onFailure { addLog("清理旧输出文件失败: ${it.message}", LogType.WARNING) }
                }
val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(
                    signAlias?.takeIf { it.isNotBlank() } ?: "adh",
                    signKey, listOf(signCert)
                ).build()
                val signedTmp = File(context.cacheDir, "${baseName}_signed_${System.currentTimeMillis()}.apk")
                val apkSigner = com.android.apksig.ApkSigner.Builder(listOf(signerConfig))
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setInputApk(intermediateFile)
                    .setOutputApk(signedTmp)
                    .setMinSdkVersion(26)
                    .build()
                apkSigner.sign()
                if (!signedTmp.exists() || signedTmp.length() <= 0) {
                    addLog("签名输出为空", LogType.ERROR)
                    return@withContext ProcessResult(false, "", 0, "", 0f)
                }
                signedFileSize = signedTmp.length()
                try {
                    val saved = OutputSettings.copyOutput(context, signedTmp, outputFile)
                    if (saved != null) {
                        successfulOutput = saved
                    } else {
                        addLog("复制输出失败", LogType.ERROR)
                    }
                } catch (copyErr: Exception) {
                    addLog("签名完成但复制到输出路径失败: ${copyErr.message}", LogType.ERROR)
                    throw copyErr
                } finally {
                    runCatching { if (signedTmp.exists()) signedTmp.delete() }
                }
                addLog("签名完成 (v1+v2)", LogType.SUCCESS)
                stages.end(LogType.SUCCESS)
            } catch (signErr: Exception) {
                stages.end(LogType.ERROR, signErr.message)
                addLog("签名失败: ${signErr.javaClass.simpleName}: ${signErr.message}", LogType.ERROR)
                signErr.stackTrace.take(5).forEach { addLog("  at ${it.className}.${it.methodName}:${it.lineNumber}", LogType.ERROR) }
                return@withContext ProcessResult(false, "", 0, "", 0f)
            }
        }
        onProgress(0.9f)

        tempFile?.let { if (it.exists()) it.delete() }
        if (intermediateFile.exists()) intermediateFile.delete()

        val deliveredPath = successfulOutput
        if (deliveredPath != null) {
            val realSize = signedFileSize
            val sizeDiff = realSize - apkSize
            val diffStr = if (sizeDiff >= 0) "+$sizeDiff" else "$sizeDiff"
            onProgress(1f)
            val elapsed = System.currentTimeMillis() - t0
            val mb = realSize / (1024f * 1024f)
            val viaMediaStore = deliveredPath.startsWith("content://")
            val logPath = if (viaMediaStore) deliveredPath else outputFile.absolutePath
            addLog("完成: ${String.format("%.2f", mb)}MB, 耗时${elapsed}ms, 增量${diffStr}字节${if (viaMediaStore) "，已保存至系统下载" else ""}", LogType.SUCCESS)
            if (autoVerify && !viaMediaStore) {
                stages.begin("自动校验")
                val (pass, total) = verifyHardenedApk(outputFile, detailLog)
                if (pass == total) {
                    stages.end(LogType.SUCCESS, "$pass/$total")
                    addLog("校验通过: $pass/$total", LogType.SUCCESS)
                } else {
                    stages.end(LogType.WARNING, "$pass/$total")
                    addLog("校验异常: $pass/$total 通过", LogType.WARNING)
                }
            }
            stages.finish()
            ProcessResult(true, logPath, realSize, diffStr, 1f)
        } else {
            addLog("输出文件不存在或为空", LogType.ERROR)
            stages.finish()
            ProcessResult(false, "", 0, "", 0f)
        }
    } catch (e: Exception) {
        addLog("处理失败: ${e.message}", LogType.ERROR)
        ProcessResult(false, "", 0, "", 0f)
    }
}

private suspend fun processFrostShellApk(
    context: Context,
    apkUri: Uri,
    apkName: String,
    hardening: List<String>,
    protection: List<String>,
    addLog: (String, LogType) -> Unit,
    detailLog: (String) -> Unit,
    onProgress: (Float) -> Unit,
    timestampedOutput: Boolean,
    autoVerify: Boolean,
    frostOptions: FrostEngineOptions
): ProcessResult = withContext(Dispatchers.IO) {
    val t0 = System.currentTimeMillis()
    val stages = StageTracker(addLog)
    try {
        stages.begin("引擎初始化")
        if (!FrostShellEngine.prepare(context)) {
            stages.end(LogType.ERROR, "初始化失败")
            addLog("FrostShell 引擎初始化失败", LogType.ERROR)
            return@withContext ProcessResult(false, "", 0, "", 0f)
        }
        stages.end(LogType.SUCCESS)

        val sourceApkPath = OutputSettings.resolveApkPath(context, apkUri)
        val (outputDir, dirSource) = OutputSettings.getOutputDir(context, sourceApkPath)
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            addLog("无法创建输出目录: ${outputDir.absolutePath}", LogType.ERROR)
            return@withContext ProcessResult(false, "", 0, "", 0f)
        }
        addLog("输出目录($dirSource): ${outputDir.absolutePath}", LogType.INFO)
        val baseName = apkName.replace(".apk", "")
        val suffix = if (timestampedOutput) "_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}" else ""
        val outputFile = File(outputDir, "${baseName}_engine${suffix}.apk")

        stages.begin("读取源APK")
        val inputStream = context.contentResolver.openInputStream(apkUri)
        if (inputStream == null) {
            stages.end(LogType.ERROR, "无法读取")
            addLog("无法读取源APK文件", LogType.ERROR)
            return@withContext ProcessResult(false, "", 0, "", 0f)
        }
        val inputFile = File(context.cacheDir, "frostshell_input_${System.currentTimeMillis()}.apk")
        try {
            FileOutputStream(inputFile).use { fos ->
                val buf = ByteArray(65536)
                var n: Int
                while (inputStream.read(buf).also { n = it } > 0) {
                    fos.write(buf, 0, n)
                }
            }
            inputStream.close()
        } catch (e: Exception) {
            stages.end(LogType.ERROR, e.message)
            addLog("读取APK失败: ${e.message}", LogType.ERROR)
            return@withContext ProcessResult(false, "", 0, "", 0f)
        }
        stages.end(LogType.SUCCESS, "${inputFile.length() / 1024}KB")

        addLog("FrostShell 引擎启动: $apkName", LogType.INFO)
        if (frostOptions.keepClasses) detailLog("启用: 保留部分类(keep-classes)")
        if (frostOptions.smaller) detailLog("启用: 瘦身(smaller)")
        if (frostOptions.verifySign) detailLog("启用: 运行时验签(verify-sign)")
        if (frostOptions.soRandomization) detailLog("启用: 壳SO随机化")
        if (frostOptions.stringEncrypt) detailLog("启用: 字符串加密(L1, minLen=${frostOptions.stringEncryptMinLen}, keywords=${frostOptions.stringEncryptKeywords?.size ?: 0})")
        if (!frostOptions.extractMethodRules.isNullOrEmpty()) detailLog("启用: 仅抽取指定函数(${frostOptions.extractMethodRules.size}条规则)")
        frostOptions.disguiseSoName?.let { detailLog("启用: 伪装加固(lib$it.so)") }
        frostOptions.excludedAbi?.let { detailLog("启用: 剔除ABI ${it.joinToString(",")}") }
        if (frostOptions.soRandomization) {
            val randResult = SoNameRandomizer.randomize(File(context.filesDir, "shell-files"))
            if (randResult.renamed.isNotEmpty() && randResult.errors.isEmpty()) {
                detailLog("壳SO随机化: " + randResult.renamed.entries.joinToString(",") { "lib${it.key}.so→lib${it.value}.so" })
                addLog("壳SO随机化完成 (${randResult.renamed.size}个名称)", LogType.SUCCESS)
            } else if (randResult.errors.isNotEmpty()) {
                addLog("壳SO随机化失败: ${randResult.errors.joinToString(";")}", LogType.WARNING)
            }
        }
        frostOptions.disguiseSoName?.let { baseName ->
            val disguiserResult = SoNameDisguiser.disguise(File(context.filesDir, "shell-files"), baseName)
            if (disguiserResult.renamed.isNotEmpty() && disguiserResult.errors.isEmpty()) {
                val oldLabel = disguiserResult.oldName ?: "?"
                detailLog("壳SO伪装改名: $oldLabel→lib$baseName.so " + disguiserResult.renamed.filter { it != "classes.dex" }.joinToString(","))
                addLog("壳SO伪装改名完成: 壳库更名为 lib$baseName.so (${disguiserResult.renamed.size}项)", LogType.SUCCESS)
            } else if (disguiserResult.errors.isNotEmpty()) {
                addLog("壳SO伪装改名失败: ${disguiserResult.errors.joinToString(";")}", LogType.WARNING)
            }
        }
        val engineLog = com.adfxcbnm.frostshell.util.FrostLogUtils.logListener
        com.adfxcbnm.frostshell.util.FrostLogUtils.logListener = { line ->
            addLog(line, LogType.INFO)
        }
        var outApk: File? = null
        var engineError: String? = null
        try {
            onProgress(0.3f)
            stages.begin("引擎加固")
            outApk = FrostShellEngine.protectApk(inputFile.absolutePath, outputDir, frostOptions)
            onProgress(0.9f)
            if (outApk != null) {
                stages.end(LogType.SUCCESS, outApk.name)
            } else {
                stages.end(LogType.ERROR, "无输出")
            }
        } catch (e: Exception) {
            engineError = e.message
            stages.end(LogType.ERROR, e.message)
            addLog("引擎异常: ${e.javaClass.simpleName}: ${e.message}", LogType.ERROR)
            e.stackTrace.take(8).forEach { addLog("  at ${it.className}.${it.methodName}:${it.lineNumber}", LogType.ERROR) }
        } finally {
            com.adfxcbnm.frostshell.util.FrostLogUtils.logListener = engineLog
        }
        val sourceInputSize = inputFile.length()
        inputFile.delete()
        if (outApk != null && outApk!!.exists() && outApk!!.length() > 0) {
            var deliveredPath: String? = null
            val overlayOk = overlayProtectionLayer(
                context = context,
                inputFile = outApk!!,
                outputFile = outputFile,
                hardening = hardening,
                protection = protection,
                signEnabled = frostOptions.signEnabled,
                signKeystorePath = frostOptions.signKeystorePath,
                signAlias = frostOptions.signAlias,
                signStorePass = frostOptions.signStorePass,
                signKeyPass = frostOptions.signKeyPass,
                disguiseSoName = frostOptions.disguiseSoName,
                addLog = addLog,
                detailLog = detailLog,
                onProgress = { p -> onProgress(0.9f + 0.1f * p) },
                onOutputSaved = { path -> deliveredPath = path }
            )
            outApk!!.delete()
            if (!overlayOk) {
                addLog("叠加保护层未生成有效输出 APK", LogType.ERROR)
                stages.finish()
                return@withContext ProcessResult(false, "", 0, "", 0f)
            }
            val viaMediaStore = deliveredPath != null && deliveredPath!!.startsWith("content://")
            val realSize = runCatching {
                if (deliveredPath != null && deliveredPath!!.startsWith("content://")) {
                    val resolver = context.contentResolver
                    resolver.query(
                        Uri.parse(deliveredPath),
                        arrayOf(android.provider.OpenableColumns.SIZE),
                        null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (idx >= 0 && !c.isNull(idx)) c.getLong(idx)
                            else outputFile.length()
                        } else outputFile.length()
                    } ?: outputFile.length()
                } else if (deliveredPath != null) {
                    File(deliveredPath).length()
                } else {
                    outputFile.length()
                }
            }.getOrDefault(outputFile.length())
            val sizeDiff = realSize - sourceInputSize
            val diffStr = if (sizeDiff >= 0) "+$sizeDiff" else "$sizeDiff"
            val elapsed = System.currentTimeMillis() - t0
            val mb = realSize / (1024f * 1024f)
            addLog("引擎完成: ${String.format("%.2f", mb)}MB, 耗时${elapsed}ms, 增量${diffStr}字节${if (viaMediaStore) "，已保存至系统下载" else ""}", LogType.SUCCESS)
            onProgress(1f)
            stages.finish()
            ProcessResult(true, deliveredPath ?: outputFile.absolutePath, realSize, diffStr, 1f)
        } else {
            addLog("引擎未生成有效输出 APK${if (engineError != null) ": $engineError" else ""}", LogType.ERROR)
            stages.finish()
            ProcessResult(false, "", 0, "", 0f)
        }
    } catch (e: Exception) {
        addLog("FrostShell 引擎处理失败: ${e.message}", LogType.ERROR)
        ProcessResult(false, "", 0, "", 0f)
    }
}

private fun overlayProtectionLayer(
    context: Context,
    inputFile: File,
    outputFile: File,
    hardening: List<String>,
    protection: List<String>,
    signEnabled: Boolean,
    signKeystorePath: String?,
    signAlias: String?,
    signStorePass: String?,
    signKeyPass: String?,
    disguiseSoName: String?,
    addLog: (String, LogType) -> Unit,
    detailLog: (String) -> Unit,
    onProgress: (Float) -> Unit,
    onOutputSaved: ((String) -> Unit)? = null
): Boolean {
    return try {
        val allFeatures = hardening + protection
        val enabledFeatures = allFeatures.mapNotNull { PROTECTION_FEATURE_MAP[it] }
        detailLog("叠加细则: ${enabledFeatures.size}项 → ${enabledFeatures.joinToString(",")}")

        // Resolve signature for features.cfg baseline
        var certSha256 = ""
        var signKey: PrivateKey? = null
        var signCert: X509Certificate? = null
        if (signEnabled) {
            try {
                if (!signKeystorePath.isNullOrBlank()) {
                    val customKsFile = File(signKeystorePath)
                    if (customKsFile.exists()) {
                        val storePass = signStorePass?.toCharArray() ?: "android".toCharArray()
                        val keyPass = signKeyPass?.toCharArray() ?: storePass
                        val pair = loadSigningKeyPair(customKsFile, signAlias, storePass, keyPass)
                        if (pair != null) {
                            signKey = pair.first
                            signCert = pair.second
                        } else {
                            addLog("自定义keystore解码失败(支持 p12/pfx/jks/keystore/bks)，回退默认签名", LogType.WARNING)
                        }
                    }
                }
                if (signKey == null) {
                    val keystoreFile = File(context.filesDir, "adh_debug.p12")
                    if (!keystoreFile.exists()) generateDebugKeystore(keystoreFile, addLog)
                    val keyStore = java.security.KeyStore.getInstance("PKCS12")
                    FileInputStream(keystoreFile).use { fis -> keyStore.load(fis, "android".toCharArray()) }
                    val alias0 = keyStore.aliases().nextElement()
                    signKey = keyStore.getKey(alias0, "android".toCharArray()) as PrivateKey
                    signCert = keyStore.getCertificate(alias0) as X509Certificate
                }
                certSha256 = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(signCert!!.encoded)
                    .joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                addLog("读取签名证书失败，跳过签名基线: ${e.message}", LogType.WARNING)
            }
        }

        val targetPkg = try {
            context.packageManager.getPackageArchiveInfo(inputFile.absolutePath, 0)?.packageName ?: ""
        } catch (e: Exception) { "" }
        detailLog("目标包名: ${targetPkg.ifEmpty { "未知" }}")

        // Scan existing entries
        val integrityHashes = mutableMapOf<String, String>()
        val existingEntries = mutableSetOf<String>()
        ZipFile(inputFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                existingEntries.add(entry.name)
                if (!entry.isDirectory && (entry.name.endsWith(".dex") || entry.name == "AndroidManifest.xml")) {
                    val md = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { ins ->
                        val buf = ByteArray(65536)
                        var n: Int
                        while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
                    }
                    integrityHashes[entry.name] = md.digest().joinToString("") { "%02x".format(it) }
                }
            }
        }
        val dexCrcMap = linkedMapOf<String, Long>()

        val manifestJson = buildProtectionJson(allFeatures, inputFile.length(), integrityHashes)
        val configBytes = generateProtectionConfig(allFeatures)

        var manifestName = "assets/protection_manifest.json"
        var manifestSuffix = 1
        while (existingEntries.contains(manifestName)) { manifestName = "assets/protection_manifest_$manifestSuffix.json"; manifestSuffix++ }
        var configName = "assets/protection_config.dat"
        var configSuffix = 1
        while (existingEntries.contains(configName)) { configName = "assets/protection_config_$configSuffix.dat"; configSuffix++ }

        val entriesToAdd = linkedSetOf(manifestName, configName, "assets/features.cfg")

        val deviceAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val targetAbis = listOf(deviceAbi, "arm64-v8a", "armeabi-v7a", "x86", "x86_64").distinct()
        val soTargets = targetAbis.filter { abi ->
            try {
                context.assets.open("lib/$abi/libsecurity_check.so").use { it.readBytes() }.isNotEmpty()
            } catch (e: Exception) { false }
        }
        val soTargetNames = soTargets.map { "lib/$it/libsecurity_check.so" }
        entriesToAdd.addAll(soTargetNames)

        var manifestModified = false
        val intermediateFile = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_intermediate.apk")
        ZipInputStream(FileInputStream(inputFile).buffered()).use { zis ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(intermediateFile), 65536)).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.isNotEmpty() && !entriesToAdd.contains(name)) {
                        if (name == "AndroidManifest.xml") {
                            val data = zis.readBytes()
                            val modResult = AndroidManifestModifier.modifyManifest(data, context, enabledFeatures, targetPkg)
                            manifestModified = modResult.modified
                            if (manifestModified) {
                                val authority = "com.adfxcbnm.authority" + if (targetPkg.isNotEmpty()) ".$targetPkg" else ""
                                addLog("叠加注入: SecurityCheckProvider authority=$authority", LogType.SUCCESS)
                            } else {
                                addLog("叠加注入: 清单未修改(可能已注入)", LogType.WARNING)
                            }
                            val newEntry = ZipEntry(name)
                            newEntry.method = ZipEntry.DEFLATED
                            zos.putNextEntry(newEntry)
                            zos.write(modResult.data)
                            zos.closeEntry()
                        } else {
                            val crc = streamCopyZipEntry(zis, zos, entry, name)
                            if (Regex("classes\\d*\\.dex").matches(name)) dexCrcMap[name] = crc
                        }
                    }
                    entry = zis.nextEntry
                }

                zos.putNextEntry(ZipEntry(manifestName).apply { method = ZipEntry.DEFLATED })
                zos.write(manifestJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry(configName).apply { method = ZipEntry.DEFLATED })
                zos.write(configBytes)
                zos.closeEntry()

                var injectedDexSize = 0
                try {
                    val dexBytes = context.assets.open("security_check.dex").use { it.readBytes() }
                    if (dexBytes.isNotEmpty()) {
                        var secDexName = "classes2.dex"
                        var secDexSuffix = 2
                        while (existingEntries.contains(secDexName)) {
                            secDexName = "classes${secDexSuffix}.dex"
                            secDexSuffix++
                        }
                        zos.putNextEntry(ZipEntry(secDexName).apply { method = ZipEntry.DEFLATED })
                        zos.write(dexBytes)
                        zos.closeEntry()
                        dexCrcMap[secDexName] = CRC32().apply { update(dexBytes) }.value
                        injectedDexSize = dexBytes.size
                        addLog("叠加注入: DEX $secDexName (${dexBytes.size}字节)", LogType.SUCCESS)
                    }
                } catch (e: Exception) {
                    addLog("叠加注入: DEX异常 ${e.message}", LogType.WARNING)
                }

                val injectedAbis = mutableListOf<String>()
                val replacedAbis = mutableListOf<String>()
                for (targetAbi in soTargets) {
                    try {
                        val soBytes = context.assets.open("lib/$targetAbi/libsecurity_check.so").use { it.readBytes() }
                        if (soBytes.isNotEmpty()) {
                            val targetName = "lib/$targetAbi/libsecurity_check.so"
                            val existed = existingEntries.contains(targetName)
                            val soEntry = ZipEntry(targetName).apply {
                                method = ZipEntry.STORED
                                size = soBytes.size.toLong()
                                compressedSize = soBytes.size.toLong()
                                crc = CRC32().apply { update(soBytes) }.value
                            }
                            zos.putNextEntry(soEntry)
                            zos.write(soBytes)
                            zos.closeEntry()
                            if (existed) replacedAbis.add(targetAbi) else injectedAbis.add(targetAbi)
                        }
                    } catch (e: Exception) { }
                }
                if (injectedAbis.isNotEmpty()) addLog("叠加注入: SO ${injectedAbis.joinToString(",")}", LogType.SUCCESS)
                if (replacedAbis.isNotEmpty()) addLog("叠加注入: SO 已存在并覆盖为最新版 ${replacedAbis.joinToString(",")}", LogType.SUCCESS)

                if (!disguiseSoName.isNullOrBlank() && Regex("[A-Za-z0-9_-]+").matches(disguiseSoName.trim())) {
                    val abiAlias = mapOf("arm64-v8a" to "arm64", "armeabi-v7a" to "arm", "x86" to "x86", "x86_64" to "x86_64")
                    val disguiseAbis = mutableListOf<String>()
                    for (targetAbi in soTargets) {
                        val disguiseEntryName = "lib/$targetAbi/lib$disguiseSoName.so"
                        if (existingEntries.contains(disguiseEntryName)) continue
                        val abiDirName = abiAlias[targetAbi] ?: targetAbi
                        try {
                            val soBytes = context.assets.open("frostshell/libs/$abiDirName/lib${"8012d9ae47c7f010"}.so").use { it.readBytes() }
                            val soEntry = ZipEntry(disguiseEntryName).apply {
                                method = ZipEntry.STORED
                                size = soBytes.size.toLong()
                                compressedSize = soBytes.size.toLong()
                                crc = CRC32().apply { update(soBytes) }.value
                            }
                            zos.putNextEntry(soEntry)
                            zos.write(soBytes)
                            zos.closeEntry()
                            disguiseAbis.add(targetAbi)
                        } catch (e: Exception) {
                            try {
                                val shellLibsRoot = File(com.adfxcbnm.frostshell.util.FrostFileUtils.getExecutablePath(), "shell-files/libs")
                                val realSo = File(shellLibsRoot, abiDirName).listFiles()
                                    ?.firstOrNull { it.isFile && it.name.endsWith(".so") } ?: continue
                                val soBytes = realSo.readBytes()
                                val soEntry = ZipEntry(disguiseEntryName).apply {
                                    method = ZipEntry.STORED
                                    size = soBytes.size.toLong()
                                    compressedSize = soBytes.size.toLong()
                                    crc = CRC32().apply { update(soBytes) }.value
                                }
                                zos.putNextEntry(soEntry)
                                zos.write(soBytes)
                                zos.closeEntry()
                                disguiseAbis.add(targetAbi)
                            } catch (e2: Exception) { }
                        }
                    }
                    if (disguiseAbis.isNotEmpty()) {
                        val signatureAssets = disguiseAssociateAssets(disguiseSoName)
                        for (sigAsset in signatureAssets) {
                            try {
                                val sigData = context.assets.open("fingerprints/$sigAsset").use { it.readBytes() }
                                if (sigData.isNotEmpty()) {
                                    zos.putNextEntry(ZipEntry("assets/$sigAsset").apply { method = ZipEntry.STORED })
                                    zos.write(sigData)
                                    zos.closeEntry()
                                }
                            } catch (e: Exception) { }
                        }
                        addLog("伪装注入: lib$disguiseSoName.so → ${disguiseAbis.joinToString(",")}，指纹${signatureAssets.size}项", LogType.SUCCESS)
                    } else {
                        addLog("伪装注入: lib$disguiseSoName.so 未找到可用壳SO源", LogType.WARNING)
                    }
                } else if (!disguiseSoName.isNullOrBlank()) {
                    addLog("伪装注入失败: 名称\"$disguiseSoName\"含非法字符(仅字母数字_)", LogType.ERROR)
                }

                val featureConfig = buildFeatureConfig(enabledFeatures, certSha256, dexCrcMap)
                zos.putNextEntry(ZipEntry("assets/features.cfg").apply { method = ZipEntry.DEFLATED })
                zos.write(featureConfig)
                zos.closeEntry()
            }
        }
        onProgress(0.85f)
        detailLog("叠加状态: 清单${if (manifestModified) "✓" else "✗"} 配置=${enabledFeatures.size}项")
        inputFile.delete()

        if (!signEnabled) {
            val saved = OutputSettings.copyOutput(context, intermediateFile, outputFile)
            intermediateFile.delete()
            addLog(
                "叠加完成，输出未签名APK (规则注入 ${enabledFeatures.size}项)${if (saved != null) " @ $saved" else "，落盘失败"}",
                if (saved != null) LogType.SUCCESS else LogType.ERROR
            )
            if (saved != null) onOutputSaved?.invoke(saved)
            return saved != null
        }
        if (signKey == null || signCert == null) {
            addLog("叠加签名证书不可用", LogType.ERROR)
            return false
        }
        val signedTmp = File(context.cacheDir, "${outputFile.nameWithoutExtension}_signed_${System.currentTimeMillis()}.apk")
        val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(
            signAlias?.takeIf { it.isNotBlank() } ?: "adh",
            signKey, listOf(signCert)
        ).build()
        val apkSigner = com.android.apksig.ApkSigner.Builder(listOf(signerConfig))
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setInputApk(intermediateFile)
            .setOutputApk(signedTmp)
            .setMinSdkVersion(26)
            .build()
        apkSigner.sign()
        if (!signedTmp.exists() || signedTmp.length() <= 0) {
            addLog("叠加签名输出为空", LogType.ERROR)
            return false
        }
        try {
            val saved = OutputSettings.copyOutput(context, signedTmp, outputFile)
            if (saved != null) onOutputSaved?.invoke(saved)
        } finally {
            runCatching { if (signedTmp.exists()) signedTmp.delete() }
        }
        intermediateFile.delete()
        addLog("叠加完成并签名 (规则注入 ${enabledFeatures.size}项)", LogType.SUCCESS)
        true
    } catch (e: Exception) {
        addLog("叠加保护层失败: ${e.javaClass.simpleName}: ${e.message}", LogType.ERROR)
        e.stackTrace.take(5).forEach { addLog("  at ${it.className}.${it.methodName}:${it.lineNumber}", LogType.ERROR) }
        false
    }
}

private fun disguiseAssociateAssets(soName: String): List<String> {
    val lower = soName.lowercase()
    val envMarkers = linkedMapOf(
        "jiagu" to listOf("vender_marker_360.bin", "market_cn.bin"),
        "tup" to listOf("vender_marker_tencent.bin"),
        "shell-super" to listOf("vender_marker_tencent.bin"),
        "baiduprotect" to listOf("vender_marker_baidu.bin"),
        "ijiami" to listOf("vender_marker_ijiami.bin"),
        "SecShell" to listOf("vender_marker_bangcle.bin"),
        "zhizhu" to listOf("vender_marker_baidu.bin")
    )
    return envMarkers[lower]?.let { it + "finger_marker.bin" } ?: listOf("finger_marker.bin")
}

/**
 * 从 assets/string_presets.json 读取 L1 字符串加密关键词集合。
 * 格式：JSONArray[{title, keywords: [], category}]。
 */
private fun loadStringEncryptKeywords(context: Context): Set<String> {
    return try {
        val json = context.assets.open("string_presets.json").bufferedReader().use { it.readText() }
        val arr = org.json.JSONArray(json)
        val set = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val keywords = obj.getJSONArray("keywords")
            for (j in 0 until keywords.length()) {
                set.add(keywords.getString(j))
            }
        }
        set
    } catch (e: Exception) {
        emptySet()
    }
}

private fun loadSigningKeyPair(
    keystoreFile: File,
    aliasHint: String?,
    storePass: CharArray,
    keyPass: CharArray
): Pair<PrivateKey, X509Certificate>? {
    try {
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
    } catch (t: Throwable) {
        // BKS 分支不可用，仍可尝试 PKCS12/JKS
    }
    val name = keystoreFile.name.lowercase()

    // pk8 / pem 形式（AOSP 平台签名）：.pk8 私钥 + 同名 .pem 证书
    if (name.endsWith(".pk8") || name.endsWith(".key") || name.endsWith(".pem")) {
        val pemFile = if (name.endsWith(".pk8") || name.endsWith(".key")) {
            val base = name.removeSuffix(name.substringAfterLast('.')).trimEnd('.')
            File(keystoreFile.parentFile, "$base.x509.pem")
        } else keystoreFile
        val pk8File = if (name.endsWith(".pem")) {
            val base = name.removeSuffix(name.substringAfterLast('.')).trimEnd('.')
            File(keystoreFile.parentFile, "$base.pk8").takeIf { it.exists() }
                ?: File(keystoreFile.parentFile, "$base.key").takeIf { it.exists() }
                ?: return null
        } else keystoreFile
        if (!pemFile.exists()) return null
        val sigKey = SigningTool.loadKeysFromPk8Pem(
            pk8File, pemFile, if (keyPass.isNotEmpty()) String(keyPass) else null
        ) ?: return null
        return Pair(sigKey.privateKey, sigKey.certificate)
    }

    // JKS：优先纯解析（Android 无 JKS provider），失败再走 KeyStore 全类型尝试
    if (name.endsWith(".jks") || name.endsWith(".keystore") || name.endsWith(".ks")) {
        val sigKey = SigningTool.loadKeysFromJks(keystoreFile, String(storePass), aliasHint)
        if (sigKey != null) return Pair(sigKey.privateKey, sigKey.certificate)
    }

    val extType = when {
        name.endsWith(".jks") || name.endsWith(".keystore") || name.endsWith(".ks") -> "JKS"
        name.endsWith(".bks") -> "BKS"
        else -> "PKCS12"
    }
    val types = linkedSetOf(extType, "PKCS12", "JKS", "BKS")
    for (type in types) {
        try {
            val keyStore = java.security.KeyStore.getInstance(type)
            FileInputStream(keystoreFile).use { fis -> keyStore.load(fis, storePass) }
            val alias = aliasHint?.takeIf { it.isNotBlank() }
                ?: keyStore.aliases().toList().firstOrNull { keyStore.isKeyEntry(it) }
                ?: continue
            val key = keyStore.getKey(alias, keyPass) as? PrivateKey ?: continue
            val cert = keyStore.getCertificate(alias) as? X509Certificate ?: continue
            return Pair(key, cert)
        } catch (e: Exception) {
            // try next keystore type
        }
    }
    return null
}

private fun generateDebugKeystore(keystoreFile: File, addLog: (String, LogType) -> Unit) {
    try {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        val cert = generateSelfSignedCertificate(keyPair)

        val keyStore = java.security.KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("adh", keyPair.private, "android".toCharArray(), arrayOf(cert))

        keystoreFile.parentFile?.mkdirs()
        java.io.FileOutputStream(keystoreFile).use { fos ->
            keyStore.store(fos, "android".toCharArray())
        }
        addLog("签名密钥已生成", LogType.INFO)
    } catch (e: Exception) {
        addLog("密钥生成失败: ${e.message}", LogType.ERROR)
        throw e
    }
}

private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
    val now = System.currentTimeMillis()
    val notBefore = now - 24 * 60 * 60 * 1000L
    val notAfter = now + 365L * 24 * 60 * 60 * 1000L

    val issuer = "CN=ADFXCBNM Debug,O=ADFXCBNM,C=CN"
    val subject = "CN=ADFXCBNM Debug,O=ADFXCBNM,C=CN"
    val serial = java.math.BigInteger(64, java.security.SecureRandom())

    val tbsCert = buildTbsCertificate(issuer, subject, serial, notBefore, notAfter, keyPair.public)

    val sig = Signature.getInstance("SHA256withRSA")
    sig.initSign(keyPair.private)
    sig.update(tbsCert)
    val signatureBytes = sig.sign()

    val certDer = buildFullCertificate(tbsCert, signatureBytes)

    val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
    return certFactory.generateCertificate(java.io.ByteArrayInputStream(certDer)) as X509Certificate
}

private fun buildTbsCertificate(issuer: String, subject: String, serial: java.math.BigInteger, notBefore: Long, notAfter: Long, publicKey: PublicKey): ByteArray {
    val version = asn1ExplicitTag(0, asn1Integer(byteArrayOf(0x02)))
    val serialBytes = asn1Integer(serial.toByteArray())
    val sigAlg = asn1Sequence(asn1Oid("1.2.840.113549.1.1.11") + asn1Null())
    val issuerDn = buildDN(issuer)
    val subjectDn = buildDN(subject)
    val validity = asn1Sequence(asn1UtcTime(notBefore) + asn1UtcTime(notAfter))
    val subjectPublicKeyInfo = buildSubjectPublicKeyInfo(publicKey)
    val extensions = asn1ExplicitTag(3, asn1Sequence(buildKeyUsageExtension() + buildBasicConstraintsExtension()))

    val tbsContent = version + serialBytes + sigAlg + issuerDn + validity + subjectDn + subjectPublicKeyInfo + extensions
    return asn1Sequence(tbsContent)
}

private fun buildKeyUsageExtension(): ByteArray {
    val keyUsageBits = byteArrayOf(0xA0.toByte(), 0x00)
    val keyUsageValue = asn1BitString(keyUsageBits)
    return asn1Sequence(asn1Oid("2.5.29.15") + asn1OctetString(keyUsageValue))
}

private fun buildBasicConstraintsExtension(): ByteArray {
    val bcValue = asn1Sequence(asn1Boolean(true) + asn1Integer(byteArrayOf(0x00)))
    return asn1Sequence(asn1Oid("2.5.29.19") + asn1OctetString(bcValue))
}

private fun buildFullCertificate(tbsCert: ByteArray, signature: ByteArray): ByteArray {
    val sigAlg = asn1Sequence(asn1Oid("1.2.840.113549.1.1.11") + asn1Null())
    val sigBits = asn1BitString(signature)
    return asn1Sequence(tbsCert + sigAlg + sigBits)
}

private fun buildDN(dn: String): ByteArray {
    val parts = dn.split(",").map { it.trim() }
    val rdnList = mutableListOf<ByteArray>()
    for (part in parts) {
        val kv = part.split("=")
        if (kv.size == 2) {
            val oid = when (kv[0]) {
                "CN" -> "2.5.4.3"
                "O" -> "2.5.4.10"
                "C" -> "2.5.4.6"
                else -> "2.5.4.3"
            }
            val value = asn1PrintableString(kv[1])
            rdnList.add(asn1Set(asn1Sequence(asn1Oid(oid) + value)))
        }
    }
    return asn1Sequence(rdnList.reduceOrNull { a, b -> a + b } ?: ByteArray(0))
}

private fun buildSubjectPublicKeyInfo(publicKey: PublicKey): ByteArray {
    val rsaKey = publicKey as java.security.interfaces.RSAPublicKey
    val modulus = rsaKey.modulus.toByteArray()
    val exponent = rsaKey.publicExponent.toByteArray()

    val modulusInt = asn1Integer(modulus)
    val exponentInt = asn1Integer(exponent)
    val rsaPublicKey = asn1Sequence(modulusInt + exponentInt)
    val keyBits = asn1BitString(rsaPublicKey)

    val algId = asn1Sequence(asn1Oid("1.2.840.113549.1.1.1") + asn1Null())
    return asn1Sequence(algId + keyBits)
}

private fun asn1Sequence(content: ByteArray): ByteArray = byteArrayOf(0x30) + encodeAsn1Length(content.size) + content
private fun asn1Set(content: ByteArray): ByteArray = byteArrayOf(0x31) + encodeAsn1Length(content.size) + content
private fun asn1Integer(value: ByteArray): ByteArray {
    var v = value
    while (v.size > 1 && v[0] == 0x00.toByte() && (v[1].toInt() and 0x80) == 0) {
        v = v.copyOfRange(1, v.size)
    }
    if ((v[0].toInt() and 0x80) != 0) {
        v = byteArrayOf(0x00) + v
    }
    return byteArrayOf(0x02) + encodeAsn1Length(v.size) + v
}

private fun asn1Oid(oid: String): ByteArray {
    val parts = oid.split(".").map { it.toLong() }
    val encoded = mutableListOf<Byte>()
    encoded.add((parts[0] * 40 + parts[1]).toByte())
    for (i in 2 until parts.size) {
        var value = parts[i]
        val bytes = mutableListOf<Byte>()
        if (value == 0L) {
            bytes.add(0)
        } else {
            while (value > 0) {
                bytes.add(0, ((value and 0x7F) or if (bytes.isEmpty()) 0x00 else 0x80).toByte())
                value = value shr 7
            }
        }
        encoded.addAll(bytes)
    }
    return byteArrayOf(0x06) + encodeAsn1Length(encoded.size) + encoded.toByteArray()
}

private fun asn1BitString(value: ByteArray): ByteArray {
    val content = byteArrayOf(0x00) + value
    return byteArrayOf(0x03) + encodeAsn1Length(content.size) + content
}

private fun asn1PrintableString(value: String): ByteArray {
    val bytes = value.toByteArray(Charsets.US_ASCII)
    return byteArrayOf(0x13) + encodeAsn1Length(bytes.size) + bytes
}

private fun asn1UtcTime(millis: Long): ByteArray {
    val df = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
    df.timeZone = java.util.TimeZone.getTimeZone("UTC")
    val timeStr = df.format(java.util.Date(millis))
    return byteArrayOf(0x17) + encodeAsn1Length(timeStr.length) + timeStr.toByteArray(Charsets.US_ASCII)
}

private fun asn1Null(): ByteArray = byteArrayOf(0x05, 0x00)
private fun asn1Boolean(value: Boolean): ByteArray = byteArrayOf(0x01, 0x01, if (value) 0xFF.toByte() else 0x00)
private fun asn1OctetString(value: ByteArray): ByteArray = byteArrayOf(0x04) + encodeAsn1Length(value.size) + value

private fun asn1ExplicitTag(tag: Int, content: ByteArray): ByteArray {
    return byteArrayOf((0xA0 or tag).toByte()) + encodeAsn1Length(content.size) + content
}

private fun encodeAsn1Length(length: Int): ByteArray {
    if (length < 128) {
        return byteArrayOf(length.toByte())
    }
    val bytes = mutableListOf<Byte>()
    var v = length
    while (v > 0) {
        bytes.add(0, (v and 0xFF).toByte())
        v = v shr 8
    }
    return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
}





private val PROTECTION_FEATURE_MAP = mapOf(
    // 加固（6项）
    "签名校验" to "sig_verify",
    "防调试检测" to "anti_debug",
    "防Hook检测" to "anti_hook",
    "防注入保护" to "anti_inject",
    "防内存Dump" to "anti_dump",
    "防代理检测" to "anti_proxy",
    // 保护（22项）
    "完整性校验" to "integrity",
    "运行时保护" to "runtime_protect",
    "内存保护" to "mem_protect",
    "网络安全" to "net_secure",
    "ROOT检测" to "root_detect",
    "模拟器检测" to "emu_detect",
    "Xposed检测" to "xposed_detect",
    "Frida检测" to "frida_detect",
    "Magisk检测" to "magisk_detect",
    "调试器检测" to "debugger_detect",
    "代码注入检测" to "code_inject",
    "速度检测" to "speed_check",
    "多开检测" to "multi_instance",
    "SSL证书校验" to "ssl_pinning",
    "数据防泄漏" to "data_leak",
    "日志保护" to "log_protect",
    "应用签名校验" to "app_sig",
    "WebView安全" to "webview_secure",
    "文件访问控制" to "file_control",
    "应用组件保护" to "component_protect",
    "环境密钥检测" to "env_testkeys",
    "SELinux检测" to "env_selinux",
    "USB调试检测" to "usb_debug_detect",
    "无障碍劫持检测" to "accessibility_hack",
    "模拟位置检测" to "mock_location"
)

private fun buildProtectionJson(allFeatures: List<String>, apkSize: Long, integrityHashes: Map<String, String>): String {
    val sizeHash = "${apkSize.hashCode().toUInt().toString(16)}"
    val featuresJson = allFeatures.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
    val hashesJson = integrityHashes.entries.joinToString(",") { "\"${it.key}\":\"${it.value.take(16)}\"" }
    return """{"version":"$APP_VERSION","timestamp":${System.currentTimeMillis()},"apk_size":$apkSize,"apk_hash":"$sizeHash","features":[$featuresJson],"integrity":{$hashesJson}}"""
}

private fun generateProtectionConfig(allFeatures: List<String>): ByteArray {
    val featureMask = allFeatures.mapIndexed { idx, f -> (f.hashCode() and 0xFF).toLong() shl ((idx % 8) * 8) }.fold(0L) { acc, v -> acc or v }
    val config = ByteArray(128)
    val magic = "ADFXCBNM_CFG_V9603".toByteArray()
    System.arraycopy(magic, 0, config, 0, magic.size)
    config[16] = (allFeatures.size and 0xFF).toByte()
    for (i in 0 until 8) {
        config[17 + i] = ((featureMask shr (i * 8)) and 0xFF).toByte()
    }
    val hardeningCount = allFeatures.count { h -> listOf("签名校验", "防调试检测", "防Hook检测", "防注入保护", "防内存Dump", "防代理检测").contains(h) }
    val protectionCount = allFeatures.size - hardeningCount
    config[25] = (hardeningCount and 0xFF).toByte()
    config[26] = (protectionCount and 0xFF).toByte()
    config[27] = 0x01
    return config
}

private fun buildFeatureConfig(
    features: List<String>,
    certSha256: String,
    dexCrcs: Map<String, Long>
): ByteArray {
    val sb = StringBuilder()
      sb.appendLine("# ADFXCBNM Feature Configuration v$CONFIG_VERSION")
      sb.appendLine("version=$CONFIG_VERSION")
    sb.appendLine("timestamp=${System.currentTimeMillis()}")
    sb.appendLine("count=${features.size}")
    sb.appendLine("features=${features.joinToString(",")}")
    for (f in features) {
        sb.appendLine("$f=enabled")
    }
    if (certSha256.isNotEmpty()) {
        sb.appendLine("signature_sha256=$certSha256")
    }
    if (dexCrcs.isNotEmpty()) {
        for ((name, crc) in dexCrcs) {
            sb.appendLine("dex_crc_entry=${name}:${crc}")
        }
    }
    return sb.toString().toByteArray(Charsets.UTF_8)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MethodBrowserDialog(
    apkUri: Uri?,
    apkName: String?,
    onDismiss: () -> Unit,
    onAppendRules: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanResult by remember { mutableStateOf<ApkMethodScanner.ScanResult?>(null) }
    var query by remember { mutableStateOf("") }
    var regexMode by remember { mutableStateOf(false) }
    var onlyNonEmpty by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var mode by remember { mutableStateOf(0) }

    val filtered = remember(scanResult, query, regexMode, selected, mode, scanError, loading, apkUri, apkName) {
        val r = scanResult
        if (r == null) emptyList()
        else ApkMethodScanner.search(r.entries, query, regexMode)
    }

    fun refresh() {
        val uri = apkUri ?: return
        scope.launch(Dispatchers.IO) {
            loading = true
            scanError = null
            try {
                val path = OutputSettings.resolveApkPath(context, uri)
                val file = if (path != null && File(path).exists()) File(path)
                else {
                    val tmp = File(context.cacheDir, "scan_${apkName ?: "apk"}.apk")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    }
                    tmp
                }
                val result = ApkMethodScanner.scan(file)
                scanResult = result
            } catch (e: Exception) {
                scanError = e.message ?: "扫描失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(apkUri, apkName) { refresh() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("浏览应用方法/类") },
        text = {
            Column {
                if (loading) {
                    Box(modifier = Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("正在解析 DEX...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (scanError != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("扫描失败: $scanError", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { refresh() }) { Text("重试") }
                    }
                } else {
                    val r = scanResult
                    if (r == null) {
                        Text("无数据")
                    } else {
                        Text(
                            "共 ${r.classCount} 个类、${r.methodCount} 个方法。搜索后勾选，追加为抽取规则。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            label = { Text("搜索 类名/方法名") },
                            singleLine = true
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = regexMode,
                                    onCheckedChange = { regexMode = it }
                                )
                                Text("正则", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = onlyNonEmpty,
                                    onCheckedChange = { onlyNonEmpty = it }
                                )
                                Text("仅非空方法", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "选中 ${selected.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = mode == 0,
                                onClick = { mode = 0 },
                                label = { Text("方法精确") }
                            )
                            FilterChip(
                                selected = mode == 1,
                                onClick = { mode = 1 },
                                label = { Text("类+全部方法") }
                            )
                            FilterChip(
                                selected = mode == 2,
                                onClick = { mode = 2 },
                                label = { Text("关键词后缀") }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.height(260.dp)) {
                            items(filtered.take(500), key = { it.uniqueKey }) { entry ->
                                if (!onlyNonEmpty || entry.methodName != "<init>" && entry.methodName != "<clinit>") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = entry.uniqueKey in selected,
                                            onCheckedChange = {
                                                selected = if (it) selected + entry.uniqueKey
                                                else selected - entry.uniqueKey
                                            }
                                        )
                                        Text(
                                            entry.display,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                        if (filtered.size > 500) {
                            Text(
                                "仅显示前 500 条，请细化搜索",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val entryByKey = scanResult?.entries?.associateBy { it.uniqueKey } ?: emptyMap()
                                    val selectedEntries = selected.mapNotNull { entryByKey[it] }
                                    val rules: List<String> = when (mode) {
                                        0 -> selectedEntries.sortedBy { it.display }.map { it.toRule() }
                                        1 -> selectedEntries.map {
                                            val idx = it.className.length
                                            it.className + ".*"
                                        }
                                        else -> {
                                            val words = LinkedHashSet<String>()
                                            selectedEntries.forEach { entry ->
                                                val word = Regex("^([a-zA-Z0-9_]+)").find(entry.methodName)?.groupValues?.get(0) ?: entry.methodName
                                                if (word.length >= 2) words.add(word)
                                            }
                                            words.sorted().map { ".*$it.*" }
                                        }
                                    }
                                    onAppendRules(rules.joinToString("\n"))
                                    onDismiss()
                                },
                                enabled = selected.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("将选中追加为规则")
                            }
                            TextButton(onClick = { selected = emptySet() }) { Text("清空勾选") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}
