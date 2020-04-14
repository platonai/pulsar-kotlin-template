package ai.platon.pulsar.protocol.browser.emulator

import ai.platon.pulsar.browser.driver.BrowserControl
import ai.platon.pulsar.common.FlowState
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.message.MiscMessageWriter
import ai.platon.pulsar.crawl.fetch.FetchResult
import ai.platon.pulsar.crawl.fetch.FetchTask
import ai.platon.pulsar.crawl.protocol.ForwardingResponse
import ai.platon.pulsar.crawl.protocol.Response
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.persist.model.ActiveDomMessage
import ai.platon.pulsar.protocol.browser.driver.ManagedWebDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.apache.commons.lang.IllegalClassException
import org.openqa.selenium.WebDriverException
import java.util.concurrent.ThreadLocalRandom

/**
 * Created by vincent on 18-1-1.
 * Copyright @ 2013-2017 Platon AI. All rights reserved
 */
open class AsyncBrowserEmulator(
        privacyContextManager: BrowserPrivacyContextManager,
        emulateEventHandler: BrowserEmulateEventHandler,
        messageWriter: MiscMessageWriter,
        immutableConfig: ImmutableConfig
): BrowserEmulatorBase(privacyContextManager, emulateEventHandler, messageWriter, immutableConfig) {

    init {
        params.withLogger(log).info()
    }

    /**
     * Fetch a page using a browser which can render the DOM and execute scripts
     *
     * @param task The task to fetch
     * @return The result of this fetch
     * @throws IllegalContextStateException Throw if the browser is closed or the program is closed
     * */
    @Throws(IllegalContextStateException::class, CancellationException::class)
    open suspend fun fetch(task: FetchTask, driver: ManagedWebDriver): FetchResult {
        if (isClosed) {
            return FetchResult(task, ForwardingResponse.canceled(task.page))
        }

        return browseWithDriver(task, driver)
    }

    open fun cancelNow(task: FetchTask) {
        task.cancel()
        driverManager.cancel(task.url)
    }

    open suspend fun cancel(task: FetchTask) {
        task.cancel()
        withContext(Dispatchers.IO) {
            driverManager.cancel(task.url)
        }
    }

    @Throws(IllegalContextStateException::class, CancellationException::class)
    protected open suspend fun browseWithDriver(task: FetchTask, driver: ManagedWebDriver): FetchResult {
        checkState()

        var response: Response? = null
        var exception: Exception? = null

        if (++task.nRetries > fetchMaxRetry) {
            response = ForwardingResponse.retry(task.page, RetryScope.CRAWL)
            return FetchResult(task, response, exception)
        }

        try {
            response = browseWithMinorExceptionsHandled(task, driver)
        } catch (e: CancellationException) {
            exception = e
            response = ForwardingResponse.retry(task.page, RetryScope.CRAWL)
        } catch (e: org.openqa.selenium.NoSuchSessionException) {
            if (!isClosed) {
                log.warn("Web driver session of #{} is closed | {}", driver.id, Strings.simplifyException(e))
            }
            driver.retire()
            exception = e
            response = ForwardingResponse.retry(task.page, RetryScope.CRAWL)
        } catch (e: org.openqa.selenium.WebDriverException) {
            if (e.cause is org.apache.http.conn.HttpHostConnectException) {
                log.warn("Web driver is disconnected - {}", Strings.simplifyException(e))
            } else {
                log.warn("Unexpected WebDriver exception", e)
            }

            driver.retire()
            exception = e
            response = ForwardingResponse.retry(task.page, RetryScope.PROTOCOL)
        } catch (e: org.apache.http.conn.HttpHostConnectException) {
            if (!isClosed) {
                log.warn("Web driver is disconnected - {}", Strings.simplifyException(e))
            }

            driver.retire()
            exception = e
            response = ForwardingResponse.retry(task.page, RetryScope.PROTOCOL)
        } finally {
        }

        return FetchResult(task, response?:ForwardingResponse(exception, task.page), exception)
    }

    @Throws(CancellationException::class)
    private suspend fun browseWithMinorExceptionsHandled(task: FetchTask, driver: ManagedWebDriver): Response {
        checkState(task)
        checkState(driver)

        val browseTask = NavigateTask(task, driver, driverControl)

        try {
            val result = navigateAndInteract(task, driver, browseTask.driverConfig)
            checkState(task)
            checkState(driver)
            browseTask.status = result.protocolStatus
            browseTask.activeDomMessage = result.activeDomMessage
            browseTask.page.activeDomMultiStatus = browseTask.activeDomMessage?.multiStatus
            browseTask.pageSource = driver.pageSource
        } catch (e: org.openqa.selenium.NoSuchElementException) {
            // TODO: when this exception is thrown?
            log.warn(e.message)
            browseTask.status = ProtocolStatus.retry(RetryScope.CRAWL)
        }

        emulateEventHandler.onAfterNavigate(browseTask)

        return ForwardingResponse(browseTask.pageSource, browseTask.status, browseTask.headers, browseTask.page)
    }

    @Throws(CancellationException::class,
            IllegalContextStateException::class,
            IllegalClassException::class,
            WebDriverException::class)
    private suspend fun navigateAndInteract(task: FetchTask, driver: ManagedWebDriver, driverConfig: BrowserControl): InteractResult {
        emulateEventHandler.logBeforeNavigate(task, driverConfig)
        driver.setTimeouts(driverConfig)
        // TODO: handle frames
        // driver.switchTo().frame(1);

        withContext(Dispatchers.IO) {
            numNavigates.mark()
            // tracer?.trace("About to navigate to #{} in {}", task.id, Thread.currentThread().name)
            driver.navigateTo(task.url)
        }

        return interact(InteractTask(task, driverConfig, driver))
    }

    @Throws(CancellationException::class, IllegalContextStateException::class)
    protected open suspend fun interact(task: InteractTask): InteractResult {
        val result = InteractResult(ProtocolStatus.STATUS_SUCCESS, null)

        runScriptTask(task, result) { jsCheckDOMState(task, result) }

        if (result.state.isContinue) {
            runScriptTask(task, result) { jsScrollDown(task, result) }
        }

        if (result.state.isContinue) {
            runScriptTask(task, result) { jsComputeFeature(task, result) }
        }

        return result
    }

    @Throws(CancellationException::class, IllegalContextStateException::class, IllegalClassException::class, WebDriverException::class)
    private suspend fun runScriptTask(task: InteractTask, result: InteractResult, action: suspend () -> Unit) {
        checkState(task.driver)
        checkState(task.fetchTask)

        action()

        result.state = FlowState.CONTINUE
    }

    @Throws(CancellationException::class)
    protected open suspend fun jsCheckDOMState(interactTask: InteractTask, result: InteractResult) {
        checkState()

        var status = ProtocolStatus.STATUS_SUCCESS
        val scriptTimeout = interactTask.driverConfig.scriptTimeout

        val fetchTask = interactTask.fetchTask

        // make sure the document is ready
        val initialScroll = 5
        val maxRound = scriptTimeout.seconds - 5 // leave 5 seconds to wait for script finish

        // TODO: wait for expected data, ni, na, nnum, nst, etc; required element
        val expression = "__utils__.waitForReady($maxRound, $initialScroll)"
        var message: Any? = null
        try {
            var msg: Any? = null
            var i = 0
            while ((msg == null || msg == false) && i++ < maxRound) {
                withContext(Dispatchers.IO) {
                    checkState(interactTask.driver)
                    checkState(fetchTask)
                    msg = interactTask.driver.evaluate(expression)
                    if (msg == null || msg == false) {
                        delay(1_000L)
                    }
                }
            }
            message = msg
        } finally {
            if (message == null) {
                if (!fetchTask.isCanceled && !interactTask.driver.isQuit && !isClosed) {
                    log.warn("Unexpected script result (null) | {}", interactTask.url)
                    status = ProtocolStatus.retry(RetryScope.CRAWL)
                    result.state = FlowState.BREAK
                }
            } else if (message == "timeout") {
                log.debug("Hit max round $maxRound to wait for document | {}", interactTask.url)
            } else if (message is String && message.contains("chrome-error://")) {
                val errorResult = emulateEventHandler.handleChromeError(message)
                status = errorResult.status
                result.activeDomMessage = errorResult.activeDomMessage
                result.state = FlowState.BREAK
            } else {
                log.trace("DOM is ready {} | {}", message.toString().substringBefore("urls"), interactTask.url)
            }
        }

        result.protocolStatus = status
    }

    protected open suspend fun jsScrollDown(interactTask: InteractTask, result: InteractResult) {
        val random = ThreadLocalRandom.current().nextInt(3)
        val scrollDownCount = interactTask.driverConfig.scrollDownCount + random - 1

        val expression = "__utils__.scrollDownN($scrollDownCount)"
        withContext(Dispatchers.IO) {
            checkState(interactTask.driver)
            checkState(interactTask.fetchTask)
            // tracer?.trace("About to scrollDownN #{} in {}", emulateTask.fetchTask.id, Thread.currentThread().name)
            interactTask.driver.evaluate(expression)
        }
    }

    protected open suspend fun jsComputeFeature(interactTask: InteractTask, result: InteractResult) {
        val message = withContext(Dispatchers.IO) {
            checkState(interactTask.driver)
            checkState(interactTask.fetchTask)
            // tracer?.trace("About to compute #{} in {}", emulateTask.fetchTask.id, Thread.currentThread().name)
            interactTask.driver.evaluate("__utils__.compute()")
        }
        if (message is String) {
            result.activeDomMessage = ActiveDomMessage.fromJson(message)
            if (log.isDebugEnabled) {
                log.debug("{} | {}", result.activeDomMessage?.multiStatus, interactTask.url)
            }
        }
    }
}
