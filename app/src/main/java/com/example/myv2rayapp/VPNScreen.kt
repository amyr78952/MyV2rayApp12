package com.example.myv2rayapp

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.myv2rayapp.data.TokenDataStore
import com.example.myv2rayapp.service.VpnState
import com.example.myv2rayapp.service.XrayVpnService
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

@Composable
fun VPNConnectionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sync state on entry
    LaunchedEffect(Unit) { VpnState.syncFromPrefs(context) }

    val connected by VpnState.connected.collectAsState(initial = false)
    val currentVless by VpnState.currentVless.collectAsState(initial = null)
    val connectedSince by VpnState.connectedSince.collectAsState(initial = null)

    val tokenDataStore = remember { TokenDataStore(context) }
    val accessToken by tokenDataStore.accessToken.collectAsState(initial = null)

    var loading by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<ConfigCodeItem>>(emptyList()) }

    // null => none, -1 => auto, 0..n-1 => manual
    var selectedKey by rememberSaveable { mutableStateOf<Int?>(null) }
    var autoPickedIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    // vpn permission pending
    var pendingVless by rememberSaveable { mutableStateOf<String?>(null) }

    // prevent rapid clicks (UI-level debounce)
    var switching by rememberSaveable { mutableStateOf(false) }

    // Locations page
    var showLocations by rememberSaveable { mutableStateOf(false) }

    // Ping statuses: null unknown, -1 timeout, >=1 ok
    val pingMap = remember { mutableStateMapOf<Int, Long?>() }
    var pingingAll by rememberSaveable { mutableStateOf(false) }

    // small flash when selecting a config
    var selectionFlashKey by rememberSaveable { mutableStateOf(0) }
    var flashOn by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectionFlashKey) {
        if (selectionFlashKey == 0) return@LaunchedEffect
        flashOn = true
        delay(240)
        flashOn = false
    }

    val flashAnim by animateFloatAsState(
        targetValue = if (flashOn) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "flashAnim"
    )

    // session timer
    val sessionSeconds by produceState(initialValue = 0L, connected, connectedSince) {
        value = 0L
        if (!connected) return@produceState
        val start = connectedSince ?: System.currentTimeMillis()
        while (isActive && connected) {
            value = (System.currentTimeMillis() - start) / 1000L
            delay(1000)
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val vless = pendingVless
            pendingVless = null
            if (!vless.isNullOrBlank()) startVpnService(context, vless)
        } else {
            pendingVless = null
            switching = false
        }
    }

    suspend fun refreshStatus() {
        val token = accessToken
        if (token.isNullOrBlank()) return

        loading = true
        val resp = runCatching {
            RetrofitClient.apiService.getStatus("Bearer $token").await()
        }.getOrElse {
            loading = false
            return
        }
        loading = false

        if (resp.isSuccessful) {
            val list = resp.body()?.config_codes ?: emptyList()
            items = list

            // restore selection if app reopened while connected
            if (selectedKey == null && !currentVless.isNullOrBlank()) {
                val idx = list.indexOfFirst { it.config_code == currentVless }
                if (idx >= 0) selectedKey = idx
            }

            // keep selection valid
            when (val k = selectedKey) {
                null -> Unit
                -1 -> {
                    val idx = autoPickedIndex
                    if (idx != null && (idx < 0 || idx >= list.size)) autoPickedIndex = null
                }
                else -> if (k < 0 || k >= list.size) selectedKey = null
            }
        }
    }

    // initial + every 2 minutes
    LaunchedEffect(accessToken) {
        if (accessToken.isNullOrBlank()) return@LaunchedEffect
        refreshStatus()
        while (isActive) {
            delay(120_000)
            refreshStatus()
        }
    }

    // -------- Service actions (single source of truth) --------

    fun doDisconnect() {
        val i = XrayVpnService.disconnectIntent(context, reason = "ui-disconnect")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }

        pendingVless = null
        switching = false
    }


    fun doConnectOrSwitch(vless: String) {
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            pendingVless = vless
            vpnPermissionLauncher.launch(prepare)
        } else {
            startVpnService(context, vless)
        }
    }

    /**
     * UI-level guard only. Service itself is serialized.
     */
    fun switchToVless(vless: String) {
        if (vless.isBlank()) return
        if (switching) return
        if (connected && currentVless == vless) return

        switching = true
        scope.launch {
            try {
                doConnectOrSwitch(vless)
            } finally {
                // just UI debounce; does not affect service correctness
                delay(300)
                switching = false
            }
        }
    }

    data class LatencyResult(val idx: Int, val ms: Long, val ok: Boolean)

    suspend fun autoPickBestConfig(): Pair<Int, Long>? = kotlinx.coroutines.coroutineScope {
        val list = items
        if (list.isEmpty()) return@coroutineScope null

        val jobs = list.mapIndexed { idx, item ->
            async(Dispatchers.IO) {
                val link = item.config_code.orEmpty()
                val hp = parseVlessHostPort(link) ?: return@async LatencyResult(idx, Long.MAX_VALUE, false)
                val ms = measureTcpConnectLatencyMs(hp.first, hp.second, timeoutMs = 1200)
                LatencyResult(idx, ms, ms != Long.MAX_VALUE)
            }
        }
        val results = jobs.awaitAll()

        var bestIdx = -1
        var bestMs = Long.MAX_VALUE
        for (r in results) {
            if (r.ok && r.ms < bestMs) {
                bestMs = r.ms
                bestIdx = r.idx
            }
        }
        if (bestIdx >= 0) bestIdx to bestMs else null
    }

    fun connectSelected() {
        val key = selectedKey ?: return

        if (key == -1) {
            if (switching) return
            switching = true
            scope.launch {
                try {
                    val best = autoPickBestConfig() ?: return@launch
                    autoPickedIndex = best.first
                    val v = items.getOrNull(best.first)?.config_code ?: return@launch
                    switchToVless(v)
                } finally {
                    delay(250)
                    switching = false
                }
            }
            return
        }

        val vless = items.getOrNull(key)?.config_code ?: return
        if (vless.isBlank()) return
        switchToVless(vless)
    }

    // selected config -> Days left only
    val selectedItemForStats: ConfigCodeItem? = run {
        val key = selectedKey
        when {
            key == null -> null
            key == -1 -> autoPickedIndex?.let { items.getOrNull(it) }
            else -> items.getOrNull(key)
        }
    }
    val daysLeftText = selectedItemForStats?.days_left?.toString()?.ifBlank { "-" } ?: "-"

    // ===== Theme =====
    val bgTop = Color(0xFF070A12)
    val bgBottom = Color(0xFF0B1230)
    val glass = Color(0xFF0E1636).copy(alpha = 0.62f)
    val glassStrong = Color(0xFF0E1636).copy(alpha = 0.78f)
    val accent = Color(0xFF00E5FF)
    val accent2 = Color(0xFF7C4DFF)
    val good = Color(0xFF00FF9A)
    val bad = Color(0xFFFF2D95)

    // header animation
    val headerAnim = rememberInfiniteTransition(label = "header")
    val headerGlow by headerAnim.animateFloat(
        initialValue = 0.70f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "headerGlow"
    )

    // button pulse
    val btnAnim = rememberInfiniteTransition(label = "btn")
    val btnPulse by btnAnim.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "btnPulse"
    )

    // responsive spacing
    val cfg = LocalConfiguration.current
    val h = cfg.screenHeightDp
    val topGap = if (h < 680) 10.dp else 18.dp
    val midGap = if (h < 680) 16.dp else 22.dp
    val wideMax = 520.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedContent(
                targetState = showLocations,
                transitionSpec = {
                    ContentTransform(
                        targetContentEnter = fadeIn(tween(220)),
                        initialContentExit = fadeOut(tween(220))
                    )
                },
                label = "pageFade"
            ) { isLocations ->
                if (!isLocations) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = wideMax)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(topGap))

                        HeaderSection(
                            connected = connected,
                            sessionSeconds = sessionSeconds,
                            headerGlow = headerGlow
                        )

                        Spacer(Modifier.height(midGap))

                        PowerSection(
                            connected = connected,
                            loading = loading,
                            switching = switching,
                            pulse = btnPulse,
                            accent = accent,
                            good = good,
                            onClick = { if (connected) doDisconnect() else connectSelected() }
                        )

                        Spacer(Modifier.height(10.dp))

                        Text(
                            text = if (connected) "DISCONNECT" else "CONNECT",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.92f)
                        )

                        Spacer(Modifier.height(midGap))

                        val flashScale = 1f + (0.012f * flashAnim)

                        ConfigSection(
                            glass = glass,
                            loading = loading,
                            label = selectedLabelText(items, selectedKey, autoPickedIndex),
                            modifier = Modifier.graphicsLayer {
                                scaleX = flashScale
                                scaleY = flashScale
                            },
                            onClick = { showLocations = true }
                        )

                        Spacer(Modifier.height(12.dp))

                        DaysLeftSection(
                            glass = glassStrong,
                            daysLeft = daysLeftText
                        )

                        Spacer(Modifier.height(12.dp))

                        if (loading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = accent
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Please wait…", color = Color.White.copy(alpha = 0.85f))
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                    }
                } else {
                    LocationsPageResponsive(
                        items = items,
                        selectedKey = selectedKey,
                        pingMap = pingMap,
                        pingingAll = pingingAll,
                        onBack = { showLocations = false },
                        onSelectKey = { k ->
                            selectedKey = k
                            if (k != -1) autoPickedIndex = null

                            showLocations = false
                            selectionFlashKey += 1

                            // ✅ If connected, switching is just starting service with new config
                            if (!switching && connected) {
                                when {
                                    k == null -> Unit
                                    k == -1 -> scope.launch {
                                        val best = autoPickBestConfig() ?: return@launch
                                        autoPickedIndex = best.first
                                        val v = items.getOrNull(best.first)?.config_code ?: return@launch
                                        switchToVless(v)
                                    }
                                    k >= 0 -> {
                                        val v = items.getOrNull(k)?.config_code
                                        if (!v.isNullOrBlank() && v != currentVless) switchToVless(v)
                                    }
                                }
                            }
                        },
                        onPingTest = {
                            if (items.isEmpty() || pingingAll) return@LocationsPageResponsive
                            pingingAll = true
                            pingMap.clear()

                            scope.launch {
                                val results = kotlinx.coroutines.coroutineScope {
                                    items.mapIndexed { idx, item ->
                                        async(Dispatchers.IO) {
                                            val link = item.config_code.orEmpty()
                                            val hp = parseVlessHostPort(link) ?: return@async (idx to -1L)
                                            val ms = measureTcpConnectLatencyMs(hp.first, hp.second, timeoutMs = 1200)
                                            if (ms == Long.MAX_VALUE) (idx to -1L) else (idx to ms)
                                        }
                                    }.awaitAll()
                                }
                                results.forEach { (idx, ms) -> pingMap[idx] = ms }
                                pingingAll = false
                            }
                        },
                        glass = glassStrong,
                        accent = accent,
                        accent2 = accent2,
                        good = good,
                        bad = bad,
                        wideMax = wideMax
                    )
                }
            }
        }
    }
}

/* ===================== MAIN PAGE SECTIONS ===================== */

@Composable
private fun HeaderSection(
    connected: Boolean,
    sessionSeconds: Long,
    headerGlow: Float
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Lithium VPN",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White.copy(alpha = headerGlow)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (connected) "Connected • Session ${formatDuration(sessionSeconds)}" else "Ready",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.70f)
        )
    }
}

@Composable
private fun PowerSection(
    connected: Boolean,
    loading: Boolean,
    switching: Boolean,
    pulse: Float,
    accent: Color,
    good: Color,
    onClick: () -> Unit
) {
    val btnColor = if (connected) good else accent

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(130.dp)
            .shadow(14.dp, CircleShape)
            .clip(CircleShape)
            .background(btnColor.copy(alpha = 0.20f * pulse))
            .padding(8.dp)
    ) {
        Button(
            onClick = onClick,
            enabled = !loading && !switching,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = btnColor.copy(alpha = 0.95f)),
            modifier = Modifier.size(110.dp)
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = "Power",
                tint = Color.Black,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun ConfigSection(
    glass: Color,
    loading: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    GlassCard(
        glassColor = glass,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !loading) { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Select VPN Location",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.70f)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("›", fontSize = 24.sp, color = Color.White.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun DaysLeftSection(
    glass: Color,
    daysLeft: String
) {
    GlassCard(
        glassColor = glass,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Days left",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.72f)
            )
            Text(
                text = daysLeft,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/* ===================== LOCATIONS PAGE ===================== */

@Composable
private fun LocationsPageResponsive(
    items: List<ConfigCodeItem>,
    selectedKey: Int?,
    pingMap: Map<Int, Long?>,
    pingingAll: Boolean,
    onBack: () -> Unit,
    onSelectKey: (Int?) -> Unit,
    onPingTest: () -> Unit,
    glass: Color,
    accent: Color,
    accent2: Color,
    good: Color,
    bad: Color,
    wideMax: Dp
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = wideMax)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        LocationsTopBarCentered(
            pingingAll = pingingAll,
            hasItems = items.isNotEmpty(),
            accent = accent,
            onPingTest = onPingTest,
            onBack = onBack
        )

        Spacer(Modifier.height(14.dp))

        GlassCard(
            glassColor = glass,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth()) {

                LocationRow(
                    leftStatus = null,
                    rightTitle = "Auto (recommended)",
                    daysLeft = "-",
                    selected = (selectedKey == -1),
                    onClick = { onSelectKey(-1) },
                    good = good,
                    bad = bad
                )

                Divider(color = Color.White.copy(alpha = 0.08f))

                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No locations available", color = Color.White.copy(alpha = 0.70f))
                    }
                } else {
                    items.forEachIndexed { index, item ->
                        val name = item.config_code?.substringAfter("#")?.ifBlank { null }
                            ?: item.server
                            ?: "Location ${index + 1}"

                        val days = item.days_left?.toString()?.ifBlank { "-" } ?: "-"

                        val ping = pingMap[index]
                        val statusText = when (ping) {
                            null -> null
                            -1L -> "TimeOut"
                            else -> "Online"
                        }
                        val statusColor = when (ping) {
                            null -> Color.White.copy(alpha = 0.35f)
                            -1L -> bad
                            else -> good
                        }

                        LocationRow(
                            leftStatus = statusText,
                            leftStatusColor = statusColor,
                            rightTitle = name,
                            daysLeft = days,
                            selected = (selectedKey == index),
                            onClick = { onSelectKey(index) },
                            good = good,
                            bad = bad
                        )

                        if (index != items.lastIndex) Divider(color = Color.White.copy(alpha = 0.08f))
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun LocationsTopBarCentered(
    pingingAll: Boolean,
    hasItems: Boolean,
    accent: Color,
    onPingTest: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "VPN Locations",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onPingTest,
                enabled = !pingingAll && hasItems,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = accent
                ),
                modifier = Modifier.height(40.dp)
            ) {
                if (pingingAll) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text("Ping Test")
            }

            Spacer(Modifier.weight(1f))

            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White.copy(alpha = 0.90f),
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onBack() }
                    .padding(2.dp)
            )
        }
    }
}

@Composable
private fun LocationRow(
    leftStatus: String?,
    leftStatusColor: Color = Color.White.copy(alpha = 0.35f),
    rightTitle: String,
    daysLeft: String,
    selected: Boolean,
    onClick: () -> Unit,
    good: Color,
    bad: Color
) {
    val bg = if (selected) Color(0xFF101B3E).copy(alpha = 0.70f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.width(86.dp),
            horizontalAlignment = Alignment.Start
        ) {
            if (leftStatus.isNullOrBlank()) {
                Text("", color = Color.Transparent, fontSize = 12.sp)
            } else {
                Text(
                    text = leftStatus,
                    color = leftStatusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = rightTitle,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Days Left : $daysLeft",
                color = Color.White.copy(alpha = 0.80f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/* ===================== GLASS CARD ===================== */

@Composable
private fun GlassCard(
    glassColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(glassColor)
            .shadow(10.dp, shape, clip = false)
    ) { content() }
}

/* ===================== HELPERS ===================== */

private fun selectedLabelText(
    items: List<ConfigCodeItem>,
    selectedKey: Int?,
    autoPickedIndex: Int?
): String {
    val key = selectedKey
    return when {
        key == null -> "Tap to choose"
        key == -1 -> {
            val pick = autoPickedIndex
            if (pick != null) {
                val item = items.getOrNull(pick)
                val name = item?.config_code?.substringAfter("#")?.ifBlank { null }
                    ?: item?.server
                    ?: "Auto"
                "Auto (recommended) • $name"
            } else {
                "Auto (recommended)"
            }
        }
        else -> {
            val item = items.getOrNull(key)
            item?.config_code?.substringAfter("#")?.ifBlank { null }
                ?: item?.server
                ?: "Location ${key + 1}"
        }
    }
}

private fun startVpnService(context: android.content.Context, vlessLink: String) {
    val sid = java.util.UUID.randomUUID().toString().take(8)
    val svc = XrayVpnService.connectIntent(context, vlessLink, sid)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(svc)
    } else {
        context.startService(svc)
    }
}


private fun parseVlessHostPort(vlessUri: String): Pair<String, Int>? {
    return try {
        val u = android.net.Uri.parse(vlessUri)
        if (!u.scheme.equals("vless", true)) return null
        val host = u.host ?: return null
        val port = if (u.port != -1) u.port else return null
        host to port
    } catch (_: Exception) {
        null
    }
}

private fun measureTcpConnectLatencyMs(host: String, port: Int, timeoutMs: Int): Long {
    val start = System.nanoTime()
    return try {
        Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
        val end = System.nanoTime()
        ((end - start) / 1_000_000L).coerceAtLeast(1L)
    } catch (_: Exception) {
        Long.MAX_VALUE
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private suspend fun <T> Call<T>.await(): Response<T> =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            try { cancel() } catch (_: Exception) {}
        }
        enqueue(object : Callback<T> {
            override fun onResponse(call: Call<T>, response: Response<T>) {
                cont.resume(response)
            }

            override fun onFailure(call: Call<T>, t: Throwable) {
                val msg = (t.message ?: "Network error")
                val body = msg.toResponseBody("text/plain".toMediaType())
                cont.resume(Response.error(599, body))
            }
        })
    }
