package com.adfxcbnm.hardeningtool

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 加固任务的共享状态（单例）。
 * 任务由 [HardeningService] 在前台 Service 中执行，通过本对象把
 * 日志/进度/结果回传给 UI；UI 崩溃或进入后台后重建时从本对象恢复状态。
 */
object HardeningSession {

    data class UiState(
        val isRunning: Boolean = false,
        val taskId: Long = 0L,
        val progress: Float = 0f,
        val logs: List<LogEntry> = emptyList(),
        val apkName: String = "",
        val result: ProcessResult? = null
    )

    /** 待执行任务的参数快照，启动 Service 前写入。 */
    data class PendingTask(
        val useFrostEngine: Boolean,
        val apkUri: Uri,
        val apkName: String,
        val hardening: List<String>,
        val protection: List<String>,
        val timestampedOutput: Boolean,
        val autoVerify: Boolean,
        val frostOptions: FrostEngineOptions,
        val signEnabled: Boolean,
        val signKeystorePath: String?,
        val signAlias: String?,
        val signStorePass: String?,
        val signKeyPass: String?
    )

    @Volatile
    var pendingTask: PendingTask? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var serial = 0L

    fun begin(apkName: String) {
        serial = System.currentTimeMillis()
        _state.value = UiState(isRunning = true, taskId = serial, progress = 0f, apkName = apkName)
    }

    fun addLog(message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
            message,
            type
        )
        _state.update { st -> st.copy(logs = (st.logs + entry).takeLast(200)) }
    }

    fun setProgress(p: Float) {
        _state.update { it.copy(progress = p) }
    }

    fun finish(result: ProcessResult) {
        _state.update { st -> st.copy(isRunning = false, result = result, progress = result.progress) }
    }

    fun clearResult() {
        _state.update { it.copy(result = null) }
    }
}