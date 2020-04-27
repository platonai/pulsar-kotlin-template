package ai.platon.pulsar.crawl

import ai.platon.pulsar.PulsarContext
import ai.platon.pulsar.PulsarSession
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.options.LoadOptions
import ai.platon.pulsar.persist.WebPage
import kotlinx.coroutines.*
import oshi.SystemInfo
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

open class StreamingCrawler(
        private val urls: Sequence<String>,
        private val options: LoadOptions = LoadOptions.create(),
        val destination: MutableList<WebPage>? = null,
        session: PulsarSession = PulsarContext.createSession(),
        val conf: ImmutableConfig = session.sessionConfig
): Crawler(session) {
    private val concurrency = conf.getInt(CapabilityTypes.FETCH_CONCURRENCY, AppConstants.FETCH_THREADS)
    private val privacyContextManager = session.context.getBean(PrivacyManager::class)
    private val activePrivacyContext get() = privacyContextManager.activeContext
    private val isAppActive get() = isAlive
    private val systemInfo = SystemInfo()
    // OSHI cached the value, so it's fast and safe to be called frequently
    private val availableMemory get() = systemInfo.hardware.memory.available
    private val requiredMemory = 500 * 1024 * 1024L // 500 MiB
    private val numRunning = AtomicInteger()

    open suspend fun run() {
        supervisorScope {
            urls.forEachIndexed { j, url ->
                if (!isAppActive) {
                    return@supervisorScope
                }

                // log.info("$j.\t$url")

                while (isAppActive && activePrivacyContext.isPrivacyLeaked) {
                    log.info("Privacy is leaked, wait for privacy context reset")
                    Thread.sleep(200)
                }

                while (isAppActive && numRunning.get() >= concurrency) {
                    Thread.sleep(200)
                }

                val memoryRemaining = availableMemory - requiredMemory
                while (isAppActive && memoryRemaining < 0) {
                    log.info("$j.\tnumRunning: {}, availableMemory: {}, requiredMemory: {}, shortage: {}",
                            numRunning,
                            Strings.readableBytes(availableMemory),
                            Strings.readableBytes(requiredMemory),
                            Strings.readableBytes(abs(memoryRemaining))
                    )
                    Thread.sleep(200)
                }

                if (!isAppActive) {
                    return@supervisorScope
                }

                numRunning.incrementAndGet()
                val context = Dispatchers.Default + CoroutineName("w")
                launch(context) {
                    val page =session.loadDeferred(url, options)
                    numRunning.decrementAndGet()
                    destination?.add(page)
                }
            }
        }
    }
}