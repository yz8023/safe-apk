package com.adfxcbnm.hardeningtool

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
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
        return try {
            if (uri.scheme == "file") {
                uri.path
            } else {
                val cursor = context.contentResolver.query(uri, arrayOf("_data"), null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex("_data")
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                }
            }
        } catch (e: Exception) { null }
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
}
