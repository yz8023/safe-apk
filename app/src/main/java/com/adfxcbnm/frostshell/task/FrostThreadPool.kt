package com.adfxcbnm.frostshell.task

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object FrostThreadPool {
    private const val CORE_POOL_SIZE = 1
    private const val MAX_POOL_SIZE = 1

    @Volatile
    private var executor: ThreadPoolExecutor = createExecutor()

    private fun createExecutor(): ThreadPoolExecutor {
        return ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue<Runnable>(),
            CustomThreadFactory()
        )
    }

    fun execute(task: Runnable) {
        executor.execute(task)
    }

    fun shutdown() {
        val current = executor
        current.shutdown()
        executor = createExecutor()
    }

    class CustomThreadFactory : ThreadFactory {
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r)
            t.name = "ironshell-" + t.id
            return t
        }
    }
}
