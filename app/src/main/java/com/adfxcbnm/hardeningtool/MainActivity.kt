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
private const val APP_VERSION = "9.7.0"
private const val CONFIG_VERSION = "9.7.0"




data class LogEntry(val time: String, val message: String, val type: LogType)
enum class LogType { INFO, SUCCESS, WARNING, ERROR }

class MutableFeatureItem(val name: String, val category: String, val icon: ImageVector, isSelected: Boolean = true) {
    var isSelected by mutableStateOf(isSelected)
}

data class AppInfo(val name: String, val packageName: String, val icon: Drawable? = null)

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
    var frostKeepClasses by remember { mutableStateOf(prefs.getBoolean("frost_keep_classes", false)) }
    var frostSmaller by remember { mutableStateOf(prefs.getBoolean("frost_smaller", false)) }
    var frostVerifySign by remember { mutableStateOf(prefs.getBoolean("frost_verify_sign", false)) }
    var frostSoRandomization by remember { mutableStateOf(prefs.getBoolean("frost_so_randomization", false)) }
    var frostStringEncrypt by remember { mutableStateOf(prefs.getBoolean("frost_string_encrypt", false)) }
    var frostStringEncryptMinLen by remember { mutableStateOf(prefs.getInt("frost_string_encrypt_min_len", 6)) }
    var frostDisguiseEnabled by remember { mutableStateOf(prefs.getBoolean("frost_disguise_enabled", false)) }
    var frostDisguiseName by remember { mutableStateOf(prefs.getString("frost_disguise_name", "") ?: "") }
    var showDisguiseDialog by remember { mutableStateOf(false) }
    var frostExcludedAbi by remember {
        mutableStateOf(prefs.getStringSet("frost_excluded_abi", emptySet())?.toMutableSet() ?: mutableSetOf())
    }
    var showOutputDirTextDialog by remember { mutableStateOf(false) }
    var outputDirTextInput by remember { mutableStateOf("") }
    var showSignInfoDialog by remember { mutableStateOf(false) }
    var signAliasInput by remember { mutableStateOf("") }
    var signStorePassInput by remember { mutableStateOf("") }
    var signKeyPassInput by remember { mutableStateOf("") }

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

                // Feature Sections
                FeatureSection(
                    title = "加固",
                    subtitle = "",
                    items = hardeningItems,
                    expanded = hardeningExpanded,
                    onToggle = { hardeningExpanded = !hardeningExpanded },
                    accentColor = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(8.dp))

                FeatureSection(
                    title = "保护",
                    subtitle = "",
                    items = protectionItems,
                    expanded = protectionExpanded,
                    onToggle = { protectionExpanded = !protectionExpanded },
                    accentColor = MaterialTheme.colorScheme.tertiary
                )

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
                            signEnabled = signEnabled,
                            signKeystorePath = signKeystorePath.ifEmpty { null },
                            signAlias = signAlias.ifEmpty { null },
                            signStorePass = signStorePass.ifEmpty { null },
                            signKeyPass = signKeyPass.ifEmpty { null }
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
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/ADFXCBNM"))
                            context.startActivity(intent)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("@ADFXCBNM", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
                    icon = Icons.Default.Lock,
                    title = "字符串加密",
                    subtitle = "敏感字符串加密 (L1 string-encrypt)",
                    checked = frostStringEncrypt,
                    onCheckedChange = {
                        frostStringEncrypt = it
                        prefs.edit().putBoolean("frost_string_encrypt", it).apply()
                    }
                )
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
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
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
                                    data, context, enabledFeatures, targetPkg
                                ) { msg -> log("manifest: $msg") }
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
                // 只注入目标APK对应ABI的SO，避免不必要膨胀
                val deviceAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val targetAbis = listOf(deviceAbi, "arm64-v8a", "armeabi-v7a", "x86", "x86_64").distinct()
                val injectedAbis = mutableListOf<String>()
                for (targetAbi in targetAbis) {
                    try {
                        val soAsset = context.assets.open("lib/$targetAbi/libsecurity_check.so")
                        val soBytes = soAsset.use { it.readBytes() }
                        if (soBytes.isNotEmpty()) {
                            val targetName = "lib/$targetAbi/libsecurity_check.so"
                            // 只注入不存在的SO文件，避免覆盖目标APK已有的文件
                            if (!existingEntries.contains(targetName)) {
                                val soEntry = ZipEntry(targetName).apply {
                                    method = ZipEntry.STORED
                                    size = soBytes.size.toLong()
                                    compressedSize = soBytes.size.toLong()
                                    crc = CRC32().apply { update(soBytes) }.value
                                }
                                zos.putNextEntry(soEntry)
                                zos.write(soBytes)
                                zos.closeEntry()
                                injectedAbis.add(targetAbi)
                            }
                        }
                    } catch (e: Exception) {
                        // ABI not available in assets, skip
                    }
                }
                if (injectedAbis.isNotEmpty()) {
                    addLog("SO注入: ${injectedAbis.joinToString(",")}", LogType.SUCCESS)
                } else {
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
                intermediateFile.copyTo(outputFile, overwrite = true)
                addLog("签名已禁用，输出未签名APK", LogType.WARNING)
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
                val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder("adh", signKey, listOf(signCert)).build()
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
                try {
                    signedTmp.copyTo(outputFile, overwrite = true)
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

        if (outputFile.exists() && outputFile.length() > 0) {
            val sizeDiff = outputFile.length() - apkSize
            val diffStr = if (sizeDiff >= 0) "+$sizeDiff" else "$sizeDiff"
            onProgress(1f)
            val elapsed = System.currentTimeMillis() - t0
            val mb = outputFile.length() / (1024f * 1024f)
            addLog("完成: ${String.format("%.2f", mb)}MB, 耗时${elapsed}ms, 增量${diffStr}字节", LogType.SUCCESS)
            if (autoVerify) {
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
            ProcessResult(true, outputFile.absolutePath, outputFile.length(), diffStr, 1f)
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
                onProgress = { p -> onProgress(0.9f + 0.1f * p) }
            )
            outApk!!.delete()
            if (!overlayOk || !outputFile.exists() || outputFile.length() <= 0) {
                addLog("叠加保护层未生成有效输出 APK", LogType.ERROR)
                stages.finish()
                return@withContext ProcessResult(false, "", 0, "", 0f)
            }
            val sizeDiff = outputFile.length() - sourceInputSize
            val diffStr = if (sizeDiff >= 0) "+$sizeDiff" else "$sizeDiff"
            val elapsed = System.currentTimeMillis() - t0
            val mb = outputFile.length() / (1024f * 1024f)
            addLog("引擎完成: ${String.format("%.2f", mb)}MB, 耗时${elapsed}ms, 增量${diffStr}字节", LogType.SUCCESS)
            onProgress(1f)
            stages.finish()
            ProcessResult(true, outputFile.absolutePath, outputFile.length(), diffStr, 1f)
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
    onProgress: (Float) -> Unit
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

                val deviceAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val targetAbis = listOf(deviceAbi, "arm64-v8a", "armeabi-v7a", "x86", "x86_64").distinct()
                val injectedAbis = mutableListOf<String>()
                for (targetAbi in targetAbis) {
                    try {
                        val soBytes = context.assets.open("lib/$targetAbi/libsecurity_check.so").use { it.readBytes() }
                        if (soBytes.isNotEmpty()) {
                            val targetName = "lib/$targetAbi/libsecurity_check.so"
                            if (!existingEntries.contains(targetName)) {
                                val soEntry = ZipEntry(targetName).apply {
                                    method = ZipEntry.STORED
                                    size = soBytes.size.toLong()
                                    compressedSize = soBytes.size.toLong()
                                    crc = CRC32().apply { update(soBytes) }.value
                                }
                                zos.putNextEntry(soEntry)
                                zos.write(soBytes)
                                zos.closeEntry()
                                injectedAbis.add(targetAbi)
                            }
                        }
                    } catch (e: Exception) { }
                }
                if (injectedAbis.isNotEmpty()) addLog("叠加注入: SO ${injectedAbis.joinToString(",")}", LogType.SUCCESS)

                if (!disguiseSoName.isNullOrBlank() && Regex("[A-Za-z0-9_-]+").matches(disguiseSoName.trim())) {
                    val abiAlias = mapOf("arm64-v8a" to "arm64", "armeabi-v7a" to "arm", "x86" to "x86", "x86_64" to "x86_64")
                    val disguiseAbis = mutableListOf<String>()
                    for (targetAbi in targetAbis) {
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
            intermediateFile.copyTo(outputFile, overwrite = true)
            intermediateFile.delete()
            addLog("叠加完成，输出未签名APK (规则注入 ${enabledFeatures.size}项)", LogType.SUCCESS)
            return true
        }
        if (signKey == null || signCert == null) {
            addLog("叠加签名证书不可用", LogType.ERROR)
            return false
        }
        val signedTmp = File(context.cacheDir, "${outputFile.nameWithoutExtension}_signed_${System.currentTimeMillis()}.apk")
        val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder("adh", signKey, listOf(signCert)).build()
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
            signedTmp.copyTo(outputFile, overwrite = true)
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
    val name = keystoreFile.name.lowercase()
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
