package com.example.myv2rayapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.myv2rayapp.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class XrayVpnService : VpnService() {

    companion object {
        private const val TAG = "VPN"

        private const val NOTIF_CHANNEL_ID = "vpn_channel"
        private const val NOTIF_ID = 1001

        private const val SOCKS_PORT = 10808
        private const val TUN_MTU = 1500
        private const val TUN_ADDR = "10.0.0.2"
        private const val TUN_PREFIX = 24

        const val EXTRA_VLESS_URI = "vless"
        const val EXTRA_SESSION_ID = "sessionId"
        const val ACTION_DISCONNECT = "action_disconnect"

        fun connectIntent(ctx: Context, vless: String, sessionId: String): Intent =
            Intent(ctx, XrayVpnService::class.java).apply {
                putExtra(EXTRA_VLESS_URI, vless)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }

        fun disconnectIntent(ctx: Context, reason: String = "ui-disconnect"): Intent =
            Intent(ctx, XrayVpnService::class.java).apply {
                action = ACTION_DISCONNECT
                putExtra("reason", reason)
            }
    }

    private sealed class Cmd {
        data class Connect(val vless: String, val sessionId: String) : Cmd()
        data class Stop(val reason: String) : Cmd()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cmdCh = Channel<Cmd>(capacity = Channel.BUFFERED)

    private var tunPfd: ParcelFileDescriptor? = null
    private var activeSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        VpnState.syncFromPrefs(this)
        ensureNotifChannel()
        startFg("Starting…")

        serviceScope.launch {
            Log.d(TAG, "cmdLoop: started")
            for (cmd in cmdCh) {
                Log.d(TAG, "cmdLoop: got cmd=$cmd")
                try {
                    when (cmd) {
                        is Cmd.Connect -> handleConnect(cmd)
                        is Cmd.Stop -> stopAll(cmd.reason, clearVless = true)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "cmdLoop: Command failed cmd=$cmd", t)
                    VpnState.setConnected(this@XrayVpnService, false)
                    VpnState.setCurrentVless(this@XrayVpnService, null)
                    VpnState.setFsm(
                        this@XrayVpnService,
                        VpnFsmState.Error(t.message ?: "error"),
                        "cmd-failed"
                    )
                    startFg("Error: ${t.message ?: "error"}")
                    stopAll("cmd-failed", clearVless = true)
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: ENTER")
        serviceScope.cancel()

        runCatching { stopAllBlocking("onDestroy") }
            .onFailure { Log.w(TAG, "onDestroy: stopAllBlocking failed", it) }

        Log.d(TAG, "onDestroy: EXIT")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action} startId=$startId")

        if (intent?.action == ACTION_DISCONNECT) {
            val reason = intent.getStringExtra("reason") ?: "ui-disconnect"
            Log.d(TAG, "onStartCommand: Stop reason=$reason")
            cmdCh.trySend(Cmd.Stop(reason))
            return Service.START_STICKY
        }

        val vless = intent?.getStringExtra(EXTRA_VLESS_URI)
        val sid = intent?.getStringExtra(EXTRA_SESSION_ID)

        if (!vless.isNullOrBlank()) {
            val realSid = sid ?: UUID.randomUUID().toString().take(8)
            Log.d(TAG, "onStartCommand: Connect sid=$realSid vless=${vless.take(80)}...")
            cmdCh.trySend(Cmd.Connect(vless = vless, sessionId = realSid))
        } else {
            Log.d(TAG, "onStartCommand: missing vless -> ignore")
        }

        return Service.START_STICKY
    }

    private fun sanitizeJsonConfig(raw: String): String {
        var s = raw
        if (s.isNotEmpty() && s[0] == '\uFEFF') s = s.substring(1)
        s = s.trimStart()
        if (s.startsWith("//") || s.startsWith("/*")) {
            throw IllegalStateException("config.json is not valid JSON (starts with comment). Remove comments.")
        }
        if (!(s.startsWith("{") || s.startsWith("["))) {
            throw IllegalStateException("config.json is not valid JSON (first char='${s.firstOrNull()}').")
        }
        return s
    }

    private suspend fun handleConnect(cmd: Cmd.Connect) {
        val sid = cmd.sessionId
        val vless = cmd.vless

        Log.d(TAG, "handleConnect: ENTER sid=$sid")
        activeSessionId = sid

        VpnState.setCurrentVless(this, vless)
        VpnState.setFsm(this, VpnFsmState.Starting(sid), "connect")
        startFg("Connecting…")

        // ✅ خیلی مهم: stop باید sync باشد (نه launch)، تا stopLoop بعداً startLoop را نکُشد
        Log.d(TAG, "handleConnect: stopAll pre-connect cleanup (SYNC)")
        stopAllBlocking("pre-connect cleanup")

        // assets
        copyAssetIfNeeded("geoip.dat")
        copyAssetIfNeeded("geosite.dat")

        // build json
        val jsonRaw = VlessToXrayConfig.build(vless, SOCKS_PORT)
        val json = sanitizeJsonConfig(jsonRaw)

        // برای دیباگ (اختیاری)
        val configFile = File(filesDir, "config.json")
        configFile.writeText(json)
        Log.d(TAG, "handleConnect: wrote config.json len=${json.length} path=${configFile.absolutePath}")
        Log.d(TAG, "handleConnect: config.json first80=${json.take(80)}")

        // ✅ قبل از startLoop یک stopLoop sync دیگر هم می‌زنیم که مطمئن باشیم هیچ چیزی درحال اجرا نیست
        withContext(Dispatchers.IO) {
            val r = GoJNI.stopLoop()
            Log.d(TAG, "handleConnect: pre-start stopLoop => ${r ?: "null"}")
        }

        // ✅ نکته کلیدی: StartLoop در mobile.go «خود JSON» را می‌خواهد، نه workDir
        Log.d(TAG, "handleConnect: GoJNI.startLoop(json_len=${json.length})")
        val startRes = withContext(Dispatchers.IO) { GoJNI.startLoop(json) }
        Log.d(TAG, "handleConnect: GoJNI startLoop => ${startRes ?: "null"}")
        if (startRes != null && startRes.startsWith("error:", ignoreCase = true)) {
            throw IllegalStateException(startRes)
        }

        // ✅ صبر کن socks واقعاً بالا بیاد
        waitForSocksUp(timeoutMs = 7000)

        // ✅ بعد TUN را بساز
        Log.d(TAG, "handleConnect: buildTun")
        tunPfd = buildTun()
        val pfd = tunPfd ?: error("TUN establish failed: tunPfd=null")
        Log.d(TAG, "handleConnect: TUN established fd=${pfd.fd}")

        // ✅ HEV
        Log.d(TAG, "handleConnect: startHev")
        startHev(pfd)

        VpnState.setConnected(this, true)
        VpnState.setFsm(this, VpnFsmState.Connected(sid), "connected")
        startFg("Connected")

        Log.d(TAG, "handleConnect: EXIT sid=$sid CONNECTED")
    }


    private fun buildTun(): ParcelFileDescriptor {
        Log.d(TAG, "buildTun: begin")

        val builder = Builder()
            .setSession("LithiumVPN")
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDR, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")

        runCatching {
            builder.addDisallowedApplication(packageName)
            Log.d(TAG, "buildTun: addDisallowedApplication OK")
        }.onFailure {
            Log.w(TAG, "buildTun: addDisallowedApplication failed", it)
        }

        val pfd = builder.establish() ?: error("builder.establish() returned null")
        Log.d(TAG, "buildTun: established fd=${pfd.fd}")
        return pfd
    }

    private suspend fun waitForSocksUp(timeoutMs: Long = 7000) = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastErr: Throwable? = null

        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 350)
                }
                Log.d(TAG, "waitForSocksUp: SOCKS is UP on 127.0.0.1:$SOCKS_PORT")
                return@withContext
            } catch (t: Throwable) {
                lastErr = t
                delay(120)
            }
        }
        throw IllegalStateException("SOCKS not ready on 127.0.0.1:$SOCKS_PORT, last=${lastErr?.message}")
    }

    private suspend fun startHev(pfd: ParcelFileDescriptor) = withContext(Dispatchers.IO) {
        val tunFd = pfd.fd

        // ✅ UDP باید 'udp' باشد (نه 'tcp') تا Xray socks inbound reject نکند
        val cfg = """
tunnel:
  name: tun0
  mtu: $TUN_MTU
  multi-queue: false
  ipv4: 198.18.0.1
  ipv6: 'fc00::1'

socks5:
  port: $SOCKS_PORT
  address: 127.0.0.1
  udp: 'udp'

misc:
  log-level: debug
  log-file: stderr
""".trimIndent()

        Log.d(TAG, "startHev: cfg_len=${cfg.length}\n$cfg")

        val rc = HevTunnel.startFromString(cfg, tunFd)
        Log.d(TAG, "startHev: startFromString rc=$rc")

        val deadline = System.currentTimeMillis() + 1500
        var last = HevTunnel.lastRc()
        while (System.currentTimeMillis() < deadline && last == -9999) {
            delay(100)
            last = HevTunnel.lastRc()
        }
        Log.d(TAG, "startHev: lastRc(after wait)=$last")

        if (last != -9999 && last != 0) throw IllegalStateException("HEV failed lastRc=$last")
    }

    private fun stopHev(reason: String) {
        Log.d(TAG, "stopHev: reason=$reason")
        runCatching { HevTunnel.stop() }
            .onFailure { Log.w(TAG, "stopHev: nativeStop failed", it) }
        Log.d(TAG, "stopHev: done")
    }

    // ✅ مهم: suspend + stopLoop sync (بدون launch)
    private suspend fun stopAll(reason: String, clearVless: Boolean) {
        Log.d(TAG, "stopAll: reason=$reason")

        stopHev(reason)

        tunPfd?.let { p ->
            Log.d(TAG, "stopAll: closing tunPfd fd=${p.fd}")
            runCatching { p.close() }
            tunPfd = null
        }

        withContext(Dispatchers.IO) {
            runCatching {
                val r = GoJNI.stopLoop()
                Log.d(TAG, "stopAll: GoJNI stopLoop => ${r ?: "null"}")
            }.onFailure {
                Log.w(TAG, "stopAll: GoJNI stopLoop failed", it)
            }
        }

        VpnState.setConnected(this, false)
        if (clearVless) VpnState.setCurrentVless(this, null)

        Log.d(TAG, "stopAll: DONE reason=$reason")
    }

    private fun stopAllBlocking(reason: String) = runBlocking {
        Log.d(TAG, "stopAllBlocking: reason=$reason")

        stopHev(reason)

        tunPfd?.let { p ->
            Log.d(TAG, "stopAllBlocking: closing tunPfd fd=${p.fd}")
            runCatching { p.close() }
            tunPfd = null
        }

        withContext(Dispatchers.IO) {
            runCatching {
                val r = GoJNI.stopLoop()
                Log.d(TAG, "stopAllBlocking: GoJNI stopLoop => ${r ?: "null"}")
            }.onFailure {
                Log.w(TAG, "stopAllBlocking: GoJNI stopLoop failed", it)
            }
        }

        VpnState.setConnected(this@XrayVpnService, false)
        VpnState.setCurrentVless(this@XrayVpnService, null)

        Log.d(TAG, "stopAllBlocking: DONE reason=$reason")
    }

    // ===== Notification =====

    private fun createNotification(state: String): Notification {
        ensureNotifChannel()
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentText(state)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startFg(state: String) {
        val notification = createNotification(state)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN foreground service"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun copyAssetIfNeeded(name: String) {
        val out = File(filesDir, name)
        if (out.exists() && out.length() > 0) {
            Log.d(TAG, "copyAssetIfNeeded: $name exists len=${out.length()}")
            return
        }
        Log.d(TAG, "copyAssetIfNeeded: copying $name")
        assets.open(name).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        Log.d(TAG, "copyAssetIfNeeded: copied $name len=${out.length()}")
    }
}
