package com.example.myv2rayapp.service

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

object Tun2SocksBinary {

    private const val OUT_NAME = "tun2socks" // executable filename (no .so)

    fun prepare(context: Context): File {
        // بهتر از filesDir: کمتر گیر میده و برای باینری‌ها خوبه
        val outDir = context.noBackupFilesDir
        if (!outDir.exists()) outDir.mkdirs()

        val outFile = File(outDir, OUT_NAME)
        if (outFile.exists() && outFile.length() > 0) {
            ensurePerm(outFile)
            return outFile
        }

        val apkPath = context.applicationInfo.sourceDir
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()

        ZipFile(apkPath).use { zip ->
            val entry = abis
                .asSequence()
                .map { abi -> "lib/$abi/libtun2socks.so" }
                .mapNotNull { name -> zip.getEntry(name) }
                .firstOrNull()
                ?: throw IllegalStateException("libtun2socks.so not found in APK for ABIs=$abis")

            zip.getInputStream(entry).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        ensurePerm(outFile)
        return outFile
    }

    private fun ensurePerm(file: File) {
        // Java-level
        file.setReadable(true, false)
        file.setWritable(true, true)
        file.setExecutable(true, false)

        // Native chmod (برای بعضی دیوایس‌ها لازمه)
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "700", file.absolutePath)).waitFor()
        } catch (_: Throwable) {
        }
    }
}
