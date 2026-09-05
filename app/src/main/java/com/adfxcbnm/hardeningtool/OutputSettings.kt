package com.adfxcbnm.hardeningtool

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

object OutputSettings {
    private const val PREF = "adfxcbnm_settings"
    const val KEY_CUSTOM_DIR = "output_dir"
    const val KEY_SAF_URI = "output_dir_saf_uri"
    const val KEY_OUTPUT_TO_SOURCE = "output_to_source"

    fun getCustomDir(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_DIR, null)?.takeIf { it.isNotBlank() }

    fun setCustomDir(context: Context, path: String?) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_DIR, path ?: "").apply()
    }

    fun getOutputToSource(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_OUTPUT_TO_SOURCE, false)

    fun setOutputToSource(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OUTPUT_TO_SOURCE, enabled).apply()
    }

    fun getSafUri(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_SAF_URI, null)?.takeIf { it.isNotBlank() }

    fun setSafUri(context: Context, uri: String?) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_SAF_URI, uri ?: "").apply()
    }

    fun isWritable(path: String): Boolean {
        return try {
            val dir = File(path)
            if (!dir.exists() && !dir.mkdirs()) return false
            val probe = File(dir, ".adh_probe_${System.currentTimeMillis()}")
            val ok = probe.createNewFile()
            if (ok) probe.delete()
            ok && dir.canWrite()
        } catch (e: Exception) { false }
    }

    fun resolveApkPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        try {
            val cursor = context.contentResolver.query(uri, arrayOf("_data"), null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex("_data")
                    if (idx >= 0 && !c.isNull(idx)) {
                        val p = c.getString(idx)
                        if (!p.isNullOrBlank()) return p
                    }
                }
            }
        } catch (e: Exception) { }
        return runCatching { resolveDocumentIdPath(context, uri) }.getOrNull()
    }

    private fun resolveDocumentIdPath(context: Context, uri: Uri): String? {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null
        val docId = DocumentsContract.getDocumentId(uri)
        val idx = docId.indexOf(':')
        if (idx < 0) return null
        val type = docId.substring(0, idx)
        val rel = docId.substring(idx + 1)
        return when (type) {
            "primary" -> File(Environment.getExternalStorageDirectory(), rel).absolutePath
            "raw" -> if (rel.startsWith("/")) rel else null
            else -> null
        }
    }

    fun safTreeToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            val type = parts.getOrNull(0)
            val rel = parts.getOrNull(1) ?: ""
            when (type) {
                "primary" -> File(Environment.getExternalStorageDirectory(), rel).absolutePath
                else -> null
            }
        } catch (e: Exception) { null }
    }

    fun defaultDir(context: Context): File =
        File(context.getExternalFilesDir(null), "ADFXCBNM")

    fun getOutputDir(context: Context, sourceApkPath: String?): Pair<File, String> {
        if (getOutputToSource(context)) {
            if (sourceApkPath != null) {
                val srcDir = File(sourceApkPath).parentFile
                if (srcDir != null && srcDir.exists() && srcDir.canWrite()) {
                    return Pair(srcDir, "源文件路径")
                }
            }
        } else {
            getCustomDir(context)?.let { custom ->
                if (isWritable(custom)) return Pair(File(custom), "自定义目录")
            }
            if (sourceApkPath != null) {
                val srcDir = File(sourceApkPath).parentFile
                if (srcDir != null && srcDir.exists() && srcDir.canWrite()) {
                    return Pair(srcDir, "来源APK目录")
                }
            }
        }
        val fallback = defaultDir(context)
        if (!fallback.exists()) fallback.mkdirs()
        return Pair(fallback, "默认目录")
    }

    /**
     * 复制加固产物到目标文件。优先直接 File.copyTo；
     * 若目标位于公共共享目录(如 /storage/emulated/0/Download)因 scoped storage 限制
     * 无法以 File 方式覆盖(ENOENT/EACCES)，回退 MediaStore.Downloads 写入。
     * @return 实际落盘的 [File]，若经 MediaStore 落盘则路径可能为 content:// 对应位置返回 null 同时通过 [fallbackPath] 回传。
     */
    fun copyOutput(
        context: Context,
        src: File,
        dest: File,
        onFallback: ((String) -> Unit)? = null
    ): File? {
        if (src == dest) return dest
        try {
            src.copyTo(dest, overwrite = true)
            return dest
        } catch (e: Exception) {
            // File 写入共享目录失败，尝试 MediaStore.Downloads
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, dest.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri: Uri = resolver.insert(collection, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { ins -> ins.copyTo(out) }
                } ?: return null
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                onFallback?.invoke(uri.toString())
                null
            } catch (e2: Exception) {
                null
            }
        }
    }
}
