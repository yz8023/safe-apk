package com.adfxcbnm.frostshell.task

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object FrostThreadPool {
    private const val CORE_POOL_SIZE = 2

    @Volatile
    private var executor: ThreadPoolExecutor = createExecutor()

    private fun createExecutor(): ThreadPoolExecutor {
        val maxPoolSize = maxOf(2, Runtime.getRuntime().availableProcessors())
        return ThreadPoolExecutor(
            CORE_POOL_SIZE,
            maxPoolSize,
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
