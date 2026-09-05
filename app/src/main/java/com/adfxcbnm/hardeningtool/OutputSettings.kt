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
     * 复制加固产物到目标位置，返回实际落盘位置描述（文件绝对路径或 content:// uri）。
     *
     * 兜底顺序：
     * 1. 先删除可能存在的旧目标文件，再尝试 File.copyTo 直写
     * 2. File 失败时回落 MediaStore.Downloads（Android 10+，覆盖公共 Download 目录）
     * 3. MediaStore 也失败时回落应用专属外部目录（始终可写，无需任何权限）
     * 全部失败返回 null。
     *
     * @return 实际写入位置的描述；null 表示整体失败。
     */
    fun copyOutput(
        context: Context,
        src: File,
        dest: File
    ): String? {
        if (src == dest) return dest.absolutePath
        if (!src.exists() || src.length() <= 0) return null

        // 1) 确保目标可覆盖：尝试删除残留旧文件（scoped storage 下旧文件可能不属于本应用）
        if (dest.exists()) {
            runCatching { dest.delete() }
        }
        try {
            src.copyTo(dest, overwrite = true)
            if (dest.exists() && dest.length() > 0) return dest.absolutePath
        } catch (e: Exception) {
            if (dest.exists() && dest.length() == src.length()) return dest.absolutePath
        }

        // 2) MediaStore.Downloads 兜底（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
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
                val written = resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { ins -> ins.copyTo(out) }
                } ?: return null
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                if (written > 0) return uri.toString()
                resolver.delete(uri, null, null)
            } catch (e: Exception) {
                // fall through to app-private dir
            }
        }

        // 3) 应用专属外部目录兜底（Download 子目录，始终可写）
        return try {
            val appDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir,
                dest.name
            )
            if (appDir.exists()) runCatching { appDir.delete() }
            src.copyTo(appDir, overwrite = true)
            if (appDir.exists() && appDir.length() > 0) appDir.absolutePath else null
        } catch (e: Exception) {
            null
        }
    }
}
