package com.alex193a.rootmypixel.feature.install

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alex193a.rootmypixel.R
import com.alex193a.rootmypixel.core.Result
import com.alex193a.rootmypixel.data.AppPreferences
import com.alex193a.rootmypixel.domain.model.DeviceSnapshot
import com.alex193a.rootmypixel.domain.model.InstallPhase
import com.alex193a.rootmypixel.domain.model.InstallUiState
import com.alex193a.rootmypixel.domain.model.TargetProfile
import com.alex193a.rootmypixel.domain.model.VerifiedPayloads
import com.alex193a.rootmypixel.domain.usecase.DownloadPayloadsUseCase
import com.alex193a.rootmypixel.domain.usecase.ResolveTargetUseCase
import com.alex193a.rootmypixel.shizuku.ExploitService
import com.alex193a.rootmypixel.shizuku.IExploitService
import com.alex193a.rootmypixel.utils.NativeProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

data class TargetCatalogUiState(
    val loading: Boolean = false,
    val profiles: List<TargetProfile> = emptyList(),
    val error: String? = null,
)

class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val resolveTargetUseCase: ResolveTargetUseCase by lazy {
        get(ResolveTargetUseCase::class.java)
    }
    private val downloadPayloadsUseCase: DownloadPayloadsUseCase by lazy {
        get(DownloadPayloadsUseCase::class.java)
    }

    private val mutableState = MutableStateFlow(InstallUiState())
    private val mutableTargetCatalog = MutableStateFlow(TargetCatalogUiState())
    private var discoveryJob: Job? = null
    private var installJob: Job? = null

    val state: StateFlow<InstallUiState> = mutableState.asStateFlow()
    val targetCatalog: StateFlow<TargetCatalogUiState> = mutableTargetCatalog.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (installJob?.isActive == true) return
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val probe = NativeProbe.run()
                val deviceInfo = NativeProbe.readDeviceSnapshot()
                if (NativeProbe.isKernelSuActive()) {
                    mutableState.value = InstallUiState(
                        phase = InstallPhase.Installed,
                        message = app.getString(R.string.status_ksu_active),
                        probeOutput = probe,
                        log = probe,
                    )
                    return@launch
                }
                val snapshot = DeviceSnapshot(
                    kernelRelease = deviceInfo.kernelRelease,
                    kernelVersion = deviceInfo.kernelVersion,
                    buildDisplay = deviceInfo.buildDisplay,
                    sdkVersion = deviceInfo.sdkVersion,
                    abi = deviceInfo.abi,
                    pageSize = deviceInfo.pageSize,
                    model = deviceInfo.model,
                    device = deviceInfo.device,
                )
                val result = resolveTargetUseCase(snapshot)
                when (result) {
                    is Result.Success -> {
                        val profile = result.data
                        mutableState.value = InstallUiState(
                            phase = InstallPhase.Ready,
                            message = app.getString(R.string.status_not_installed),
                            probeOutput = probe,
                            log = "$probe\n${app.getString(
                                R.string.log_profile, profile.profileId)}",
                        )
                    }
                    is Result.Error -> {
                        mutableState.value = InstallUiState(
                            phase = InstallPhase.Failed,
                            message = app.getString(R.string.status_support_failed),
                            probeOutput = probe,
                            log = "$probe\n[-] ${result.error.message}",
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Failed,
                    message = app.getString(R.string.status_support_failed),
                    log = "[-] ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    fun install(profileId: String? = null, permissiveOnly: Boolean = false) {
        if (installJob?.isActive == true ||
            mutableState.value.phase == InstallPhase.Installed) return
        discoveryJob?.cancel()

        installJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = InstallUiState(
                phase = InstallPhase.Checking,
                probeOutput = mutableState.value.probeOutput,
            )
            try {
                setPhase(InstallPhase.Checking, app.getString(R.string.status_checking))
                val deviceInfo = NativeProbe.readDeviceSnapshot()

                val snapshot = DeviceSnapshot(
                    kernelRelease = deviceInfo.kernelRelease,
                    kernelVersion = deviceInfo.kernelVersion,
                    buildDisplay = deviceInfo.buildDisplay,
                    sdkVersion = deviceInfo.sdkVersion,
                    abi = deviceInfo.abi,
                    pageSize = deviceInfo.pageSize,
                    model = deviceInfo.model,
                    device = deviceInfo.device,
                )

                val profile = when {
                    profileId != null -> {
                        when (val r = resolveTargetUseCase(profileId)) {
                            is Result.Success -> r.data
                            is Result.Error ->
                                throw IllegalStateException(r.error.message)
                        }
                    }
                    else -> {
                        when (val r = resolveTargetUseCase(snapshot)) {
                            is Result.Success -> r.data
                            is Result.Error ->
                                throw IllegalStateException(r.error.message)
                        }
                    }
                }
                appendLog(app.getString(R.string.log_profile, profile.profileId))

                setPhase(InstallPhase.Downloading, "Preparing payloads…")
                val payloads = when (
                    val r = downloadPayloadsUseCase(profile) { appendLog("[*] $it") }
                ) {
                    is Result.Success -> r.data
                    is Result.Error ->
                        throw IllegalStateException(r.error.message)
                }
                appendLog("Payloads extracted from APK")

                val useShizuku = AppPreferences.shizukuMode(app)
                if (useShizuku) {
                    require(hasShizukuPermission()) {
                        app.getString(R.string.error_shizuku_required)
                    }
                }
                appendLog(
                    app.getString(
                        if (useShizuku) R.string.log_exec_mode_shizuku
                        else R.string.log_exec_mode_app
                    )
                )

                setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit))
                executeExploit(payloads, useShizuku)

                if (permissiveOnly) {
                    setPhase(InstallPhase.Installed, "SELinux permissive + root shell ready")
                    appendLog("Install complete — permissive mode, KernelSU skipped")
                } else {
                    setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_loading_ksu))
                    installKernelSu(payloads)

                    setPhase(InstallPhase.Installed, app.getString(R.string.status_ksu_active))
                    appendLog(app.getString(R.string.log_install_complete))
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                appendLog("[-] ${error.message ?: error.javaClass.simpleName}")
                setPhase(InstallPhase.Failed, app.getString(R.string.status_install_failed))
            }
        }
    }

    // --- Shizuku UserService helpers ---

    private data class ShizukuServiceHandle(
        val service: IExploitService,
        val conn: ServiceConnection,
    )

    private fun bindExploitService(): ShizukuServiceHandle? {
        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ExploitService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("exploit_service")
            .version(1)

        var service: IExploitService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IExploitService.Stub.asInterface(binder)
                synchronized(this) {
                    (this as Object).notifyAll()
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        Shizuku.bindUserService(args, conn)

        // Wait up to 5 seconds for connection
        synchronized(conn as Object) {
            if (service == null) {
                try {
                    (conn as Object).wait(5000)
                } catch (_: InterruptedException) {
                }
            }
        }

        val svc = service ?: run {
            Shizuku.unbindUserService(args, conn, true)
            return null
        }
        return ShizukuServiceHandle(svc, conn)
    }

    private fun unbindExploitService(handle: ShizukuServiceHandle) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ExploitService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("exploit_service")
            .version(1)
        Shizuku.unbindUserService(args, handle.conn, true)
    }

    // --- Exploit execution ---

    private suspend fun executeExploit(payloads: VerifiedPayloads, useShizuku: Boolean) {
        if (useShizuku) executeExploitViaShizuku(payloads) else executeExploitInApp(payloads)
        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    /**
     * Environment shared by both launch paths.
     *
     * Both entries are boot-scoped facts the payload cannot safely rediscover on
     * a retry, because a failed attempt leaves the kernel's boot_id sysctl
     * pointing at kernel memory until reboot:
     *  - RMG_ASHMEM_PATH: the real /dev/ashmem<boot_id> node. Without it a retry
     *    derives a bogus name and dies much later on the unopenable default.
     *  - KASLR_BASE: a base already observed this boot, which lets the payload
     *    skip the slide. The slide is the only stage that panics the device when
     *    it loses its race, so a retry should never run it twice.
     */
    private fun payloadEnv(): Map<String, String> = buildMap {
        AppPreferences.ashmemPath(app)?.let { put("RMG_ASHMEM_PATH", it) }
        AppPreferences.kaslrBase(app)?.let {
            put("KASLR_BASE", it)
            appendLog(app.getString(R.string.log_kaslr_reused, it))
        }
    }

    /**
     * Remember a base the payload just derived, so the next attempt this boot can
     * skip the slide. Logged as "slide-kaslr-ok pid=… base=<hex> slide=…", and
     * the payload parses KASLR_BASE with strtoull(…, 0), so it needs the 0x form.
     */
    private fun harvestKaslrBase(rawLog: String) {
        if (AppPreferences.kaslrBase(app) != null) return
        val base = KASLR_BASE_PATTERN.find(rawLog)?.groupValues?.getOrNull(1) ?: return
        AppPreferences.setKaslrBase(app, "0x$base")
        appendLog(app.getString(R.string.log_kaslr_cached, base))
    }

    /**
     * Runs the payload in the app's own domain, without Shizuku.
     *
     * The helper is executed straight out of nativeLibraryDir — an app uid may exec there,
     * but not in /data/local/tmp, which is shell-owned — and it dlopens the payload extracted
     * to filesDir. Nothing in the 6.1 chain needs shell: the root child is forked from this
     * process before its creds are patched (root.c spawn_root_child), so it captures this uid
     * and the su daemon authorises it alongside RMG_APP_UID (su_daemon.c is_allowed_uid).
     * Everything after the cred patch runs as root with SELinux permissive, which is why the
     * daemon can still place its socket under /data/local/tmp.
     */
    private suspend fun executeExploitInApp(payloads: VerifiedPayloads) {
        val helper = File(app.applicationInfo.nativeLibraryDir, HELPER_LIB_NAME)
        require(helper.exists() && helper.canExecute()) {
            app.getString(R.string.error_helper_unavailable)
        }

        val logFile = File(app.filesDir, EXPLOIT_LOG_NAME)
        logFile.delete()

        val uid = Process.myUid().toString()
        val process = ProcessBuilder(
            helper.absolutePath,
            "--run-payload",
            payloads.exploit.absolutePath,
            helper.absolutePath,
            logFile.absolutePath,
        )
            .redirectErrorStream(true)
            .apply {
                environment()["RMG_CLIENT_UID"] = uid
                environment()["RMG_APP_UID"] = uid
                environment().putAll(payloadEnv())
            }
            .start()

        try {
            val logPrefix = mutableState.value.log
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""

            while (process.isAlive) {
                val currentLog = logFile.readTextOrEmpty()
                if (currentLog != lastRawLog) {
                    publishLog(logPrefix, currentLog)
                    // Harvested mid-run: an attempt that derives a base and then
                    // dies has still paid the slide's cost, and the next one
                    // should not pay it again.
                    harvestKaslrBase(currentLog)
                    lastRawLog = currentLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                val now = SystemClock.elapsedRealtime()
                require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                    app.getString(R.string.error_exploit_stalled)
                }
                require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                    app.getString(R.string.error_exploit_timeout)
                }
                delay(LOG_POLL_INTERVAL)
            }

            val exitCode = process.waitFor()
            // The payload writes to the log file; the runner itself only reports loader errors
            // (a failed dlopen of the payload lands here, not in the log).
            val runnerOutput = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val finalLog = logFile.readTextOrEmpty()
            if (finalLog.isNotBlank()) {
                publishLog(logPrefix, finalLog)
            }
            if (runnerOutput.isNotBlank()) {
                appendLog(runnerOutput.take(2000))
            }

            require(exitCode == 0) {
                app.getString(
                    R.string.error_payload_exit,
                    exitCode,
                    runnerOutput.takeIf(String::isNotBlank)?.let { " ($it)" } ?: "",
                )
            }
            require(finalLog.contains("done=1") && finalLog.contains("root=1")) {
                app.getString(R.string.error_success_marker)
            }
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    private fun File.readTextOrEmpty(): String =
        runCatching { if (exists()) readText() else "" }.getOrDefault("")

    /** Mirror the payload log into filesDir so the export action can reach it in both modes. */
    private fun persistLog(contents: String) {
        runCatching { File(app.filesDir, EXPLOIT_LOG_NAME).writeText(contents) }
    }

    private suspend fun executeExploitViaShizuku(payloads: VerifiedPayloads) {
        val helper = File(app.applicationInfo.nativeLibraryDir, HELPER_LIB_NAME)
        require(helper.exists()) { app.getString(R.string.error_helper_unavailable) }

        val handle = bindExploitService()
            ?: throw IllegalStateException("Failed to bind Shizuku UserService")

        try {
            val logPrefix = mutableState.value.log
            handle.service.startExploit(
                payloads.exploit.readBytes(),
                helper.readBytes(),
                "/data/local/tmp/exploit.log",
                payloadEnv().map { (name, value) -> "$name=$value" }.toTypedArray(),
            )

            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""

            while (handle.service.isRunning) {
                val remoteLog = handle.service.getLog()
                val fileLog = handle.service.exec("cat /data/local/tmp/exploit.log 2>/dev/null || true")
                val currentLog = if (fileLog.length > remoteLog.length) fileLog else remoteLog

                if (currentLog != lastRawLog) {
                    publishLog(logPrefix, currentLog)
                    harvestKaslrBase(currentLog)
                    lastRawLog = currentLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                val now = SystemClock.elapsedRealtime()
                require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                    app.getString(R.string.error_exploit_stalled)
                }
                require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                    app.getString(R.string.error_exploit_timeout)
                }
                delay(LOG_POLL_INTERVAL)
            }

            val exitCode = handle.service.waitFor()
            val finalLog = handle.service.exec("cat /data/local/tmp/exploit.log 2>/dev/null || true")
            if (finalLog.isNotBlank()) {
                publishLog(logPrefix, finalLog)
                persistLog(finalLog)
            }

            require(exitCode == 0) {
                app.getString(R.string.error_payload_exit, exitCode, "")
            }
            require(finalLog.contains("done=1") && finalLog.contains("root=1")) {
                app.getString(R.string.error_success_marker)
            }
        } finally {
            unbindExploitService(handle)
        }
    }

    // Shizuku helpers

    private fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() &&
            Shizuku.isPreV11().not() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
            Shizuku.getUid() == 2000
        } catch (_: Exception) {
            false
        }
    }

    // --- KernelSU ---

    private fun installKernelSu(payloads: VerifiedPayloads) {
        val ksudSource = payloads.kernelSu.absolutePath
        val ksudDest = "/data/local/tmp/ksud-pixel"
        val helper = File(app.applicationInfo.nativeLibraryDir, HELPER_LIB_NAME)

        // 1. Wait for daemon to be ready
        awaitDaemonSocket()
        diagnoseDaemon()

        // 2. Stage ksud via daemon root (cp + chmod + chown)
        appendLog("[*] Staging ReSukiSU binary...")
        val stageCmd = "cp '$ksudSource' $ksudDest && chmod 755 $ksudDest && " +
            "chown root:root $ksudDest"
        var stageSuccess = false
        for (attempt in 1..5) {
            val result = runHelper(helper, "-c", stageCmd)
            if (result.code == 0) {
                val verify = runHelper(helper, "-c", "ls -la $ksudDest")
                if (verify.output.contains("rwxr-xr-x") ||
                    verify.output.contains("-rwxr-xr-x")) {
                    appendLog("ReSukiSU staged: ${verify.output.trim()}")
                    stageSuccess = true
                    break
                }
            }
            appendLog("[!] Stage attempt $attempt: code=${result.code} ${result.output.take(120)}")
            Thread.sleep(1000)
        }
        require(stageSuccess) {
            app.getString(R.string.error_ksu_stage, "stage failed after 5 attempts")
        }

        // 3. Execute late-load via daemon root
        appendLog("[*] Triggering KernelSU late-load (kmi=${payloads.kmi})...")
        val lateResult = runHelper(helper, "-c",
            "$ksudDest late-load --kmi ${payloads.kmi}")
        if (lateResult.output.isNotBlank()) {
            appendLog(lateResult.output.take(2000))
        }

        // 4. Verify KSU is actually loaded (check multiple paths)
        var ksuActive = false
        for (i in 1..10) {
            val check = runHelper(helper, "-c",
                "test -e /dev/kernelsu && echo KSU_OK || " +
                "test -e /sys/kernel/kernelsu && echo KSU_OK || " +
                "test -e /data/adb/ksu && echo KSU_OK || " +
                "echo KSU_NOT_FOUND")
            if (check.output.contains("KSU_OK")) {
                appendLog("[+] KernelSU verified (attempt $i): ${check.output.take(60)}")
                ksuActive = true
                break
            }
            Thread.sleep(500)
        }
        require(ksuActive) {
            app.getString(
                R.string.error_ksu_verify,
                lateResult.code,
                lateResult.output.take(200)
            )
        }
        appendLog(app.getString(R.string.log_ksu_control_verified))
    }

    private fun runHelper(helper: File, vararg arguments: String): CommandResult {
        for (attempt in 1..5) {
            val process = ProcessBuilder(listOf(helper.absolutePath) + arguments)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val result = CommandResult(process.waitFor(), output.trim())

            val transient = result.output.contains("No such file or directory") ||
                result.output.contains("Connection refused") ||
                result.code == 127
            if (!transient || attempt == 5) {
                return result
            }
            Thread.sleep(1500)
        }
        return CommandResult(1, "runHelper: exhausted retries")
    }

    private fun awaitDaemonSocket() {
        val sock = File("/data/local/tmp/temp_su.sock")
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sock.exists()) return
            Thread.sleep(500)
        }
    }

    private fun diagnoseDaemon() {
        try {
            val helper = File(app.applicationInfo.nativeLibraryDir, HELPER_LIB_NAME)
            if (!helper.exists()) {
                appendLog("[diag] helper binary missing")
                return
            }
            val suCheck = runHelper(helper, "-c",
                "ls -la /apex/com.android.virt/bin/su /data/local/tmp/su 2>/dev/null || echo 'not found'")
            appendLog("[diag] su binaries: ${suCheck.output.take(200)}")

            val sockCheck = File("/data/local/tmp/temp_su.sock")
            appendLog("[diag] socket file: ${if (sockCheck.exists()) "present" else "NOT FOUND"}")

            val logCheck = runHelper(helper, "-c",
                "cat /data/local/tmp/su_daemon.log 2>/dev/null || echo 'empty'")
            appendLog("[diag] daemon log: ${logCheck.output.take(300)}")
        } catch (e: Exception) {
            appendLog("[diag] error: ${e.message}")
        }
    }

    fun softReboot() {
        viewModelScope.launch(Dispatchers.IO) {
            val helper = File(app.applicationInfo.nativeLibraryDir, HELPER_LIB_NAME)
            if (!helper.exists()) return@launch
            val result = runHelper(helper, "-c",
                "killall -9 system_server 2>/dev/null; true")
            appendLog("[*] Soft reboot triggered (exit ${result.code})")
        }
    }

    // --- UI helpers ---

    private fun setPhase(phase: InstallPhase, message: String) {
        mutableState.value = mutableState.value.copy(phase = phase, message = message)
        appendLog("[*] $message")
    }

    private fun publishLog(prefix: String, rawLog: String) {
        mutableState.value = mutableState.value.copy(
            log = listOf(prefix, rawLog)
                .filter(String::isNotBlank)
                .joinToString("\n")
                .takeLast(MAX_LOG_CHARS),
        )
    }

    private fun appendLog(line: String) {
        val cleanLine = line.trim()
        if (cleanLine.isBlank()) return
        mutableState.value = mutableState.value.copy(
            log = (mutableState.value.log + "\n" + cleanLine)
                .trim()
                .takeLast(MAX_LOG_CHARS),
        )
    }

    data class CommandResult(val code: Int, val output: String)

    companion object {
        private const val HELPER_LIB_NAME = "libcve43499root.so"
        private const val EXPLOIT_LOG_NAME = "exploit.log"
        private val KASLR_BASE_PATTERN = Regex("slide-kaslr-ok[^\\n]*\\bbase=([0-9a-f]{16})\\b")
        private const val EXPLOIT_STALL_MILLIS = 600_000L
        private const val EXPLOIT_TOTAL_MILLIS = 1_800_000L
        private const val MAX_LOG_CHARS = 5 * 1024 * 1024
        private val LOG_POLL_INTERVAL = 250.milliseconds
    }
}
