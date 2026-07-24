package com.taowen.arglass.driver.xreal.onefamily

import android.net.ConnectivityManager
import android.net.Network
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal object XrealOneNcmTransport {
    const val GLASSES_HOST = "169.254.2.1"
    const val ANDROID_HOST = "169.254.2.10"
    const val CONTROL_PORT = 52_999
    const val IMU_PORT = 52_998
    const val RGB_VIDEO_PORT = 52_995

    @Suppress("DEPRECATION")
    fun findNetwork(manager: ConnectivityManager): Network? = manager.allNetworks.firstOrNull { network ->
        val properties = manager.getLinkProperties(network) ?: return@firstOrNull false
        properties.linkAddresses.any { address -> address.address.hostAddress == ANDROID_HOST }
    }

    inline fun <T> withBoundNetwork(
        manager: ConnectivityManager?,
        onFallback: (String) -> Unit = {},
        block: () -> T,
    ): T {
        manager ?: return block()
        val network = findNetwork(manager)
        if (network == null) {
            onFallback("未找到 $ANDROID_HOST 所在的 Android Network，使用默认路由尝试连接")
            return block()
        }
        val previous = manager.boundNetworkForProcess
        if (!manager.bindProcessToNetwork(network)) {
            onFallback("绑定 USB Ethernet Network 失败，使用默认路由尝试连接")
            return block()
        }
        return try {
            block()
        } finally {
            manager.bindProcessToNetwork(previous)
        }
    }

    fun connectSocket(
        manager: ConnectivityManager?,
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Socket {
        val network = manager?.let(::findNetwork)
        val failures = mutableListOf<String>()

        if (manager != null && network != null) {
            val previous = manager.boundNetworkForProcess
            if (manager.bindProcessToNetwork(network)) {
                try {
                    return connectPlainSocket(host, port, connectTimeoutMs, readTimeoutMs)
                } catch (error: Exception) {
                    failures += "bound process ${error.describe()}"
                } finally {
                    manager.bindProcessToNetwork(previous)
                }
            } else {
                failures += "bindProcessToNetwork returned false"
            }

            try {
                return connectPreparedSocket(
                    network.socketFactory.createSocket() as Socket,
                    host,
                    port,
                    connectTimeoutMs,
                    readTimeoutMs,
                )
            } catch (error: Exception) {
                failures += "network socketFactory ${error.describe()}"
            }
        }

        try {
            return connectPreparedSocket(
                Socket().apply { bind(InetSocketAddress(InetAddress.getByName(ANDROID_HOST), 0)) },
                host,
                port,
                connectTimeoutMs,
                readTimeoutMs,
            )
        } catch (error: Exception) {
            failures += "local $ANDROID_HOST bind ${error.describe()}"
        }

        try {
            return connectPlainSocket(host, port, connectTimeoutMs, readTimeoutMs)
        } catch (error: Exception) {
            failures += "default route ${error.describe()}"
        }

        error("connect $host:$port failed: ${failures.joinToString("; ")}")
    }

    private fun connectPlainSocket(host: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): Socket =
        connectPreparedSocket(Socket(), host, port, connectTimeoutMs, readTimeoutMs)

    private fun connectPreparedSocket(
        socket: Socket,
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Socket {
        socket.apply {
            tcpNoDelay = true
            soTimeout = readTimeoutMs
        }
        return try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun Exception.describe(): String = "${javaClass.simpleName}(${message ?: "no message"})"
}
