package ai.platon.pulsar.browser.driver.chrome

import ai.platon.pulsar.browser.driver.chrome.ChromeLauncherConfiguration.Companion.CHROME_BINARIES
import ai.platon.pulsar.browser.driver.chrome.impl.ChromeServiceImpl
import ai.platon.pulsar.common.AppPaths
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.reflect.Field
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.component1
import kotlin.collections.component2

class ChromeLauncherConfiguration {
    var startupWaitTime = DEFAULT_STARTUP_WAIT_TIME
    var shutdownWaitTime = DEFAULT_SHUTDOWN_WAIT_TIME
    var threadWaitTime = THREAD_JOIN_WAIT_TIME
    var userDataDirPath = AppPaths.CHROME_TMP_DIR

    companion object {
        /** Default startup wait time in seconds.  */
        val DEFAULT_STARTUP_WAIT_TIME = Duration.ofSeconds(60)
        /** Default shutdown wait time in seconds.  */
        val DEFAULT_SHUTDOWN_WAIT_TIME = Duration.ofSeconds(60)
        /** 5 seconds wait time for threads to stop.  */
        val THREAD_JOIN_WAIT_TIME = Duration.ofSeconds(5)

        val CHROME_BINARIES = arrayOf(
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser",
                "/usr/bin/google-chrome-stable",
                "/usr/bin/google-chrome",
                "/opt/google/chrome/",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary",
                "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe"
        )
    }
}

/** Chrome argument.  */
@kotlin.annotation.Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class ChromeParameter(val value: String)

class ChromeOptions {
    @ChromeParameter("headless")
    var headless = true
    @ChromeParameter("remote-debugging-port")
    var remoteDebuggingPort = 0
    @ChromeParameter("no-default-browser-check")
    var noDefaultBrowserCheck = true
    @ChromeParameter("no-first-run")
    var noFirstRun = true
    @ChromeParameter(USER_DATA_DIR_ARGUMENT)
    var userDataDir = AppPaths.CHROME_TMP_DIR.toString()
    @ChromeParameter("incognito")
    var incognito = true
    @ChromeParameter("disable-gpu")
    var disableGpu = true
    @ChromeParameter("hide-scrollbars")
    var hideScrollbars = true
    @ChromeParameter("mute-audio")
    var muteAudio = true
    @ChromeParameter("disable-background-networking")
    var disableBackgroundNetworking = true
    @ChromeParameter("disable-background-timer-throttling")
    var disableBackgroundTimerThrottling = true
    @ChromeParameter("disable-client-side-phishing-detection")
    var disableClientSidePhishingDetection = true
    @ChromeParameter("disable-default-apps")
    var disableDefaultApps = true
    @ChromeParameter("disable-extensions")
    var disableExtensions = true
    @ChromeParameter("disable-hang-monitor")
    var disableHangMonitor = true
    @ChromeParameter("disable-popup-blocking")
    var disablePopupBlocking = true
    @ChromeParameter("disable-prompt-on-repost")
    var disablePromptOnRepost = true
    @ChromeParameter("disable-sync")
    var disableSync = true
    @ChromeParameter("disable-translate")
    var disableTranslate = true
    @ChromeParameter("metrics-recording-only")
    var metricsRecordingOnly = true
    @ChromeParameter("safebrowsing-disable-auto-update")
    var safebrowsingDisableAutoUpdate = true

    val additionalArguments: MutableMap<String, Any?> = mutableMapOf()

    fun toMap(): Map<String, Any?> {
        val args = ChromeOptions::class.java.declaredFields
                .filter { it.annotations.any { it is ChromeParameter } }
                .onEach { it.isAccessible = true }
                .associateTo(mutableMapOf()) { it.getAnnotation(ChromeParameter::class.java).value to it.get(this) }

        args.putAll(additionalArguments)

        return args
    }

    fun toList(): List<String> {
        return toList(toMap())
    }

    private fun toList(args: Map<String, Any?>): List<String> {
        val result = ArrayList<String>()
        for ((key, value) in args) {
            if (value != null && false != value) {
                if (true == value) {
                    result.add("--$key")
                } else {
                    result.add("--$key=$value")
                }
            }
        }
        return result
    }

    companion object {
        const val USER_DATA_DIR_ARGUMENT = "user-data-dir"
    }
}

class ProcessLauncher {
    @Throws(IOException::class)
    fun launch(program: String, args: List<String>): Process {
        val arguments = mutableListOf<String>()
        arguments.add(program)
        arguments.addAll(args)
        val processBuilder = ProcessBuilder()
                .command(arguments)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
        return processBuilder.start()
    }

    fun isExecutable(binaryPath: String): Boolean {
        val path = Paths.get(binaryPath)
        return isExecutable(path)
    }

    private fun isExecutable(path: Path): Boolean {
        return Files.isRegularFile(path) && Files.isReadable(path) && Files.isExecutable(path)
    }
}

class ChromeLauncher(
        private val processLauncher: ProcessLauncher = ProcessLauncher(),
        private val shutdownHookRegistry: ShutdownHookRegistry = RuntimeShutdownHookRegistry(),
        private val configuration: ChromeLauncherConfiguration = ChromeLauncherConfiguration()
) : AutoCloseable {

    companion object {
        const val ENV_CHROME_PATH = "CHROME_PATH"
        private val DEVTOOLS_LISTENING_LINE_PATTERN = Pattern.compile("^DevTools listening on ws:\\/\\/.+:(\\d+)\\/")
    }

    private val log = LoggerFactory.getLogger(ChromeLauncher::class.java)
    private var chromeProcess: Process? = null
    private var userDataDirPath: Path = configuration.userDataDirPath
    private val shutdownHookThread = Thread { this.close() }

    fun launch(chromeBinaryPath: Path, options: ChromeOptions): ChromeService {
        val port = launchChromeProcess(chromeBinaryPath, options)
        return ChromeServiceImpl(port)
    }

    fun launch(options: ChromeOptions): ChromeService {
        return launch(searchChromeBinary(), options)
    }

    fun launch(headless: Boolean): ChromeService {
        return launch(searchChromeBinary(), ChromeOptions().also { it.headless = headless })
    }

    fun launch(): ChromeService {
        return launch(true)
    }

    /**
     * Returns the chrome binary path.
     *
     * @return Chrome binary path.
     */
    private fun searchChromeBinary(): Path {
        val envChrome = System.getProperty(ENV_CHROME_PATH)
        if (envChrome != null) {
            return if (processLauncher.isExecutable(envChrome)) {
                Paths.get(envChrome).toAbsolutePath()
            } else throw RuntimeException("CHROME_PATH is not executable | $envChrome")
        }

        return CHROME_BINARIES.firstOrNull { processLauncher.isExecutable(it) }
                ?.let { Paths.get(it).toAbsolutePath() }
                ?:throw RuntimeException("Could not find chrome binary! Try setting CHROME_PATH environment value")
    }

    override fun close() {
        val process = chromeProcess?:return
        chromeProcess = null
        if (process.isAlive) {
            process.destroy()
            try {
                if (!process.waitFor(configuration.shutdownWaitTime.seconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(configuration.shutdownWaitTime.seconds, TimeUnit.SECONDS)
                }
                log.info("Chrome process is closed")
            } catch (e: InterruptedException) {
                log.error("Interrupted while waiting for chrome process to shutdown", e)
                process.destroyForcibly()
            } finally {
                FileUtils.deleteQuietly(userDataDirPath.toFile())
            }

            try {
                shutdownHookRegistry.remove(shutdownHookThread)
            } catch (e: IllegalStateException) { // Ignore this exceptions; We're removing hook even we're still in shutdown.
            }
        }
    }

    /**
     * Returns an exit value. This is just proxy to [Process.exitValue].
     *
     * @return Exit value of the process if exited.
     * @throws [IllegalThreadStateException] if the subprocess has not yet terminated. [     ] If the process hasn't even started.
     */
    fun exitValue(): Int {
        checkNotNull(chromeProcess) { "Chrome process has not been started" }
        return chromeProcess!!.exitValue()
    }

    /**
     * Tests whether the subprocess is alive. This is just proxy to [Process.isAlive].
     *
     * @return True if the subprocess has not yet terminated.
     * @throws IllegalThreadStateException if the subprocess has not yet terminated.
     */
    val isAlive: Boolean
        get() = chromeProcess != null && chromeProcess!!.isAlive

    /**
     * Launches a chrome process given a chrome binary and its arguments.
     *
     * @param chromeBinary Chrome binary path.
     * @param chromeOptions Chrome arguments.
     * @return Port on which devtools is listening.
     * @throws IllegalStateException If chrome process has already been started.
     * @throws ChromeProcessException If an I/O error occurs during chrome process start.
     * @throws ChromeProcessTimeoutException If timeout expired while waiting for chrome to start.
     */
    @Throws(ChromeProcessException::class)
    private fun launchChromeProcess(chromeBinary: Path, chromeOptions: ChromeOptions): Int {
        check(!isAlive) { "Chrome process has already been started started." }
        shutdownHookRegistry.register(shutdownHookThread)
        val arguments = chromeOptions.toList()

        log.info("Launching chrome process {} with arguments {}", chromeBinary, arguments)
        return try {
            chromeProcess = processLauncher.launch(chromeBinary.toString(), arguments)
            waitForDevToolsServer(chromeProcess!!)
        } catch (e: IOException) { // Unsubscribe from registry on exceptions.
            shutdownHookRegistry.remove(shutdownHookThread)
            throw ChromeProcessException("Failed starting chrome process.", e)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /**
     * Waits for DevTools server is up on chrome process.
     *
     * @param process Chrome process.
     * @return DevTools listening port.
     * @throws ChromeProcessTimeoutException If timeout expired while waiting for chrome process.
     */
    @Throws(ChromeProcessTimeoutException::class)
    private fun waitForDevToolsServer(process: Process): Int {
        var port = 0
        val chromeOutput = StringBuilder()
        val readLineThread = Thread {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                // Wait for DevTools listening line and extract port number.
                var line: String
                while (reader.readLine().also { line = it } != null) {
                    val matcher = DEVTOOLS_LISTENING_LINE_PATTERN.matcher(line)
                    if (matcher.find()) {
                        port = matcher.group(1).toInt()
                        break
                    }
                    chromeOutput.appendln(line)
                }
            }
        }
        readLineThread.start()

        try {
            readLineThread.join(configuration.startupWaitTime.toMillis())
            if (port == 0) {
                close(readLineThread)
                throw ChromeProcessTimeoutException("Timeout to waiting for chrome to start. Chrome output: \n>>>$chromeOutput")
            }
        } catch (e: InterruptedException) {
            close(readLineThread)
            log.error("Interrupted while waiting for dev tools server.", e)
            throw RuntimeException("Interrupted while waiting for dev tools server.", e)
        }
        return port
    }

    private fun close(thread: Thread) {
        try {
            thread.join(configuration.threadWaitTime.toMillis())
        } catch (ignored: InterruptedException) {}
    }

    interface ShutdownHookRegistry {
        fun register(thread: Thread) {
            Runtime.getRuntime().addShutdownHook(thread)
        }

        fun remove(thread: Thread) {
            Runtime.getRuntime().removeShutdownHook(thread)
        }
    }

    class RuntimeShutdownHookRegistry : ShutdownHookRegistry
}