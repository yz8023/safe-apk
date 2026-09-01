package com.adfxcbnm.hardeningtool

class StageTracker(
    private val emit: (String, LogType) -> Unit
) {
    private var stageIndex = 0
    private var stageStart = 0L
    private var currentStage = ""
    private var successCount = 0
    private var failedCount = 0
    private var totalMs = 0L
    private val stageDurations = mutableListOf<Pair<String, Long>>()

    fun begin(name: String) {
        stageIndex++
        currentStage = name
        stageStart = System.currentTimeMillis()
        emit("── [$stageIndex] $name", LogType.INFO)
    }

    fun end(status: LogType = LogType.SUCCESS, summary: String? = null) {
        val dur = System.currentTimeMillis() - stageStart
        totalMs += dur
        when (status) {
            LogType.SUCCESS -> successCount++
            LogType.ERROR -> failedCount++
            else -> {}
        }
        val icon = when (status) {
            LogType.SUCCESS -> "✓"
            LogType.ERROR -> "✗"
            LogType.WARNING -> "!"
            LogType.INFO -> "·"
        }
        val extra = summary?.let { " — $it" } ?: ""
        emit("$icon [$stageIndex] $currentStage ($dur ms)$extra", status)
        stageDurations.add(currentStage to dur)
    }

    fun finish() {
        val stageMs = stageDurations.joinToString(", ") { "${it.first}=${it.second}ms" }
        emit(
            "── 汇总: ${stageIndex}个阶段 · 成功$successCount · 失败$failedCount · 总耗时 ${totalMs}ms",
            if (failedCount > 0) LogType.WARNING else LogType.SUCCESS
        )
        if (stageDurations.isNotEmpty()) {
            emit("    耗时明细: $stageMs", LogType.INFO)
        }
    }
}
