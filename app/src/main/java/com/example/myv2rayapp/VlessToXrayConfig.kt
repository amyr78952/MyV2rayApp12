package com.example.myv2rayapp.service

import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object VlessToXrayConfig {
    private const val TAG = "VlessToXrayConfig"

    fun build(vlessUri: String, socksPort: Int): String {
        // نمونه شما: vless://UUID@host:port?type=tcp&security=none#name
        val uri = java.net.URI(vlessUri.replace("vless://", "scheme://"))
        val userInfo = uri.userInfo ?: error("Missing UUID")
        val host = uri.host ?: error("Missing host")
        val port = if (uri.port > 0) uri.port else error("Missing port")

        // query
        val q = (uri.query ?: "")
            .split("&")
            .filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to v
            }

        val type = q["type"] ?: "tcp"
        val security = q["security"] ?: "none"

        Log.d(TAG, "build: uuid=$userInfo host=$host port=$port type=$type security=$security socksPort=$socksPort")

        // ✅ JSON کاملاً معتبر، بدون کامنت
        return """
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "tag": "socks-in",
      "listen": "127.0.0.1",
      "port": $socksPort,
      "protocol": "socks",
      "settings": { "udp": true }
    }
  ],
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "$host",
            "port": $port,
            "users": [
              { "id": "$userInfo", "encryption": "none" }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "$type",
        "security": "$security"
      }
    },
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "rules": [
      { "type": "field", "outboundTag": "proxy", "network": "tcp,udp" }
    ]
  }
}
""".trimIndent()
    }
}
