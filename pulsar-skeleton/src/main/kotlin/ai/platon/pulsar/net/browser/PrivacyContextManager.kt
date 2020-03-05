package ai.platon.pulsar.net.browser

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.proxy.ProxyManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * TODO: multiple context support
 * */
class PrivacyContextManager(
        /**
         * The web driver pool
         * TODO: web driver pool should be created by a privacy context, not a singleton
         * */
        val driverManager: WebDriverManager,
        val proxyManager: ProxyManager,
        val immutableConfig: ImmutableConfig
) {
    companion object {
        private val globalActiveContext = AtomicReference<BrowserPrivacyContext>()
        val zombieContexts = ConcurrentLinkedQueue<BrowserPrivacyContext>()
    }

    val maxRetry = 2

    val activeContext
        @Synchronized
        get() = getOrCreate()

    fun run(task: FetchTask, browseFun: (FetchTask, ManagedWebDriver) -> FetchResult): FetchResult {
        var result: FetchResult

        var i = 1
        do {
            if (activeContext.isPrivacyLeaked) {
                task.reset()
                reset()
            }

            result = activeContext.run(task) { _, driver ->
                browseFun(task, driver)
            }

            val response = result.response
            if (response.status.isSuccess) {
                activeContext.informSuccess()
            } else if (response.status.isRetry(RetryScope.PRIVACY)) {
                activeContext.informWarning()
            }
        } while (i++ <= maxRetry && activeContext.isPrivacyLeaked)

        return result
    }

    @Synchronized
    fun reset() {
        // we need to freeze all running tasks and reset driver pool and proxy
        val context = globalActiveContext.get()
        context?.use { it.close() }
//        context?.waitUntilClosed()
        globalActiveContext.getAndSet(null)?.let { zombieContexts.add(it) }
    }

    private fun getOrCreate(): BrowserPrivacyContext {
        if (globalActiveContext.get() == null) {
            globalActiveContext.set(BrowserPrivacyContext(driverManager, proxyManager, immutableConfig))
        }
        return globalActiveContext.get()
    }
}