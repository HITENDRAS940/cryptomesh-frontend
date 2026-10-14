package com.cryptomesh.frontend.logging

import android.util.Log

object MeshLog {
    const val TAG = "CryptoMesh"

    fun auth(message: String) {
        log("AUTH", "🔐 $message")
    }

    fun packet(message: String) {
        log("PACKET", "📦 $message")
    }

    fun transfer(message: String) {
        log("TRANSFER", "🚀 $message")
    }

    fun ble(message: String) {
        log("BLE", "📡 $message")
    }

    fun warn(category: String, message: String) {
        val formatted = "[$category] ⚠️ $message"
        try {
            Log.w(TAG, formatted)
        } catch (_: Throwable) {
            println("$TAG $formatted")
        }
    }

    fun error(category: String, message: String, throwable: Throwable? = null) {
        val formatted = "[$category] ❌ $message"
        try {
            Log.e(TAG, formatted, throwable)
        } catch (_: Throwable) {
            println("$TAG $formatted")
            throwable?.printStackTrace()
        }
    }

    private fun log(category: String, message: String) {
        val formatted = "[$category] $message"
        try {
            Log.i(TAG, formatted)
        } catch (_: Throwable) {
            println("$TAG $formatted")
        }
    }
}
