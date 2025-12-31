package com.example.myv2rayapp.service

import android.util.Log
import mobile.Mobile

object GoJNI {
    private const val TAG = "VPN"

    /**
     * Mobile.startLoop expects CONFIG JSON STRING (not a file path/workDir).
     * Returns:
     * - "started" (or null) on success
     * - "error: ..." on failure
     */
    fun startLoop(configJson: String): String? {
        return try {
            val trimmed = configJson.trimStart()
            // جلوگیری از ارسال اشتباهی path مثل /data/user/...
            if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
                val head = trimmed.take(40).replace("\n", "\\n")
                val msg = "error: startLoop expects JSON, got head='$head'"
                Log.e(TAG, msg)
                return msg
            }

            Log.d(TAG, "GoJNI.startLoop(json_len=${configJson.length})")
            val r = Mobile.startLoop(configJson) // ✅ JSON مستقیم
            Log.d(TAG, "GoJNI.startLoop => ${r ?: "null"}")
            r
        } catch (t: Throwable) {
            Log.e(TAG, "GoJNI.startLoop failed", t)
            "error: ${t.message ?: "startLoop-exception"}"
        }
    }

    /**
     * Stops Go loop if running.
     * Returns:
     * - "stopped" / "not running" (or null) depending on your Go code
     * - "error: ..." on failure
     */
    fun stopLoop(): String? {
        return try {
            Log.d(TAG, "GoJNI.stopLoop()")
            val r = Mobile.stopLoop()
            Log.d(TAG, "GoJNI.stopLoop => ${r ?: "null"}")
            r
        } catch (t: Throwable) {
            Log.e(TAG, "GoJNI.stopLoop failed", t)
            "error: ${t.message ?: "stopLoop-exception"}"
        }
    }
}
