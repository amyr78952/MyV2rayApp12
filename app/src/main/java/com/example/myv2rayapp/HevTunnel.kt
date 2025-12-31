package com.example.myv2rayapp.service

import android.util.Log

object HevTunnel {
    private const val TAG = "HevTunnel"

    init {
        Log.d(TAG, "init: loading libhev_jni ...")
        System.loadLibrary("hev_jni")
        Log.d(TAG, "init: loaded libhev_jni OK")
    }

    fun startFromString(cfg: String, tunFd: Int): Int {
        Log.d(TAG, "startFromString: ENTER cfg_len=${cfg.length} tunFd=$tunFd")
        val rc = nativeStartFromString(cfg, tunFd)
        Log.d(TAG, "startFromString: EXIT rc=$rc")
        return rc
    }

    fun stop(): Int {
        Log.d(TAG, "stop: calling nativeStop ...")
        val rc = nativeStop()
        Log.d(TAG, "stop: nativeStop rc=$rc")
        return rc
    }

    fun lastRc(): Int {
        val v = nativeLastRc()
        Log.d(TAG, "lastRc: $v")
        return v
    }

    private external fun nativeStartFromString(cfg: String, tunFd: Int): Int
    private external fun nativeStop(): Int
    private external fun nativeLastRc(): Int
}
