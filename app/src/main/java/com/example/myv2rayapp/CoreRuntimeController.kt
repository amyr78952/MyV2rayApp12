package com.example.myv2rayapp.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.LondonX.tun2socks.Tun2Socks
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class CoreRuntimeController(
    private val appContext: Context,
    private val filesDir: File,
    private val cacheDir: File,
) {
    data class StartParams(
        val tunPfd: ParcelFileDescriptor,
        val xrayBin: File,
        val configFile: File,
        val socksHost: String = "127.0.0.1",
        val socksPort: Int,
        val mtu: Int,
        val ip4: String,
        val ip6: String = "",
        val netmask: String,
        val socks5Udp: Boolean = true,
    )

    private val mutex = Mutex()

    private var tunPfd: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null

    private val t2sTerminateCalled = AtomicBoolean(false)

    // ✅ single scope for tun2socks jobs (no per-start scope)
    private val t2sScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // tun2socks lifecycle
    private var t2sJob: Job? = null
    private var t2sDone: CompletableDeferred<Unit>? = null

    // ✅ init once (global-ish) to avoid "initialization before done"
    companion object {
        private const val TAG = "CoreRuntime"

        private val INIT_ONCE = AtomicBoolean(false)
        private val INIT_MUTEX = Mutex()
    }

    private suspend fun ensureTun2SocksInitialized() {
        if (INIT_ONCE.get()) return
        INIT_MUTEX.withLock {
            if (INIT_ONCE.get()) return
            runCatching { Tun2Socks.initialize(appContext) }
                .onFailure { Log.e(TAG, "Tun2Socks.initialize failed", it) }
            INIT_ONCE.set(true)
        }
    }

    suspend fun start(params: StartParams) = mutex.withLock {
        stopLocked("pre-start cleanup")

        tunPfd = params.tunPfd

        // 1) start xray
        xrayProcess = startXrayProcess(params.xrayBin, params.configFile)

        // 2) wait socks ready
        val ready = waitForLocalPort(params.socksHost, params.socksPort, 8_000)
        if (!ready) {
            stopLocked("socks not ready")
            throw IllegalStateException("SOCKS ${params.socksHost}:${params.socksPort} not ready")
        }

        // 3) start tun2socks
        startTun2SocksJob(params)
    }

    suspend fun stop(reason: String = "stop()") = mutex.withLock {
        stopLocked(reason)
    }

    // ---------------- internals ----------------

    private suspend fun startTun2SocksJob(params: StartParams) {
        val pfd = params.tunPfd

        // ✅ ensure init only once
        ensureTun2SocksInitialized()

        t2sTerminateCalled.set(false)
        val done = CompletableDeferred<Unit>()
        t2sDone = done

        val job = t2sScope.launch {
            Log.d(TAG, "Tun2Socks job start fd=${pfd.fd}")
            try {
                // blocking call; stopTun2Socks should unblock it
                Tun2Socks.startTun2Socks(
                    Tun2Socks.LogLevel.INFO,
                    pfd,
                    params.mtu,
                    params.socksHost,
                    params.socksPort,
                    params.ip4,
                    params.ip6,
                    params.netmask,
                    params.socks5Udp,
                    emptyList()
                )
                Log.d(TAG, "Tun2Socks returned normally")
            } catch (t: Throwable) {
                Log.e(TAG, "Tun2Socks crashed", t)
            } finally {
                done.complete(Unit)
                Log.d(TAG, "Tun2Socks job finished")
            }
        }

        t2sJob = job
    }

    private suspend fun stopLocked(reason: String) {
        Log.d(TAG, "stopLocked: $reason")

        // 1) stop tun2socks FIRST and WAIT properly
        if (t2sJob != null && t2sTerminateCalled.compareAndSet(false, true)) {
            runCatching { Tun2Socks.stopTun2Socks() }
                .onFailure { Log.w(TAG, "Tun2Socks.stopTun2Socks failed", it) }
        }

        // wait up to 8s for tun2socks to fully finish (slightly longer)
        val done = t2sDone
        if (done != null) {
            withTimeoutOrNull(8_000) { done.await() }
                ?: Log.w(TAG, "Tun2Socks did not finish in time")
        }

        // cancel job (best effort)
        runCatching { t2sJob?.cancelAndJoin() }
        t2sJob = null
        t2sDone = null

        // ✅ important: native teardown may be async
        delay(450)

        // 2) stop xray process
        val p = xrayProcess
        if (p != null) {
            runCatching { p.destroy() }
            val exited = waitProcessExit(p, 2_500)
            if (!exited) {
                Log.w(TAG, "XRAY still alive -> destroyForcibly")
                runCatching { p.destroyForcibly() }
                waitProcessExit(p, 2_500)
            }
        }
        xrayProcess = null

        // 3) close tun fd LAST
        runCatching { tunPfd?.close() }
        tunPfd = null

        // ✅ small extra cooldown for fast switch
        delay(250)
    }

    private fun startXrayProcess(xrayBin: File, configFile: File): Process {
        if (!xrayBin.exists()) throw IllegalStateException("xray not found: ${xrayBin.absolutePath}")

        val pb = ProcessBuilder(
            xrayBin.absolutePath,
            "run",
            "-c",
            configFile.absolutePath
        )
            .directory(filesDir)
            .redirectErrorStream(true)

        applySafeEnv(pb)

        val p = pb.start()
        pipeLog("XRAY", p)
        checkProcessSoon("XRAY", p)
        return p
    }

    private fun waitProcessExit(p: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasExited(p) != null) return true
            try { Thread.sleep(60) } catch (_: Exception) {}
        }
        return hasExited(p) != null
    }

    private fun hasExited(p: Process): Int? =
        try { p.exitValue() } catch (_: IllegalThreadStateException) { null }

    private fun waitForLocalPort(host: String, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s -> s.connect(InetSocketAddress(host, port), 250) }
                return true
            } catch (_: Exception) {
                try { Thread.sleep(80) } catch (_: Exception) {}
            }
        }
        return false
    }

    private fun applySafeEnv(pb: ProcessBuilder) {
        pb.environment().apply {
            this["HOME"] = filesDir.absolutePath
            this["TMPDIR"] = cacheDir.absolutePath
            this["TMP"] = cacheDir.absolutePath
            this["TEMP"] = cacheDir.absolutePath
            this["XDG_CACHE_HOME"] = cacheDir.absolutePath
            this["XDG_CONFIG_HOME"] = filesDir.absolutePath
            this["XRAY_LOCATION_ASSET"] = filesDir.absolutePath
            this["V2RAY_LOCATION_ASSET"] = filesDir.absolutePath
            this["XRAY_LOCATION_CONFIG"] = filesDir.absolutePath
        }
    }

    private fun pipeLog(tag: String, p: Process) {
        Thread {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { Log.d(tag, it) }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun checkProcessSoon(name: String, p: Process) {
        Thread {
            try {
                Thread.sleep(900)
                val exited = hasExited(p)
                if (exited != null) Log.e(TAG, "$name exited early, code=$exited")
                else Log.d(TAG, "$name is alive")
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }
}
