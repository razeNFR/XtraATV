package com.github.andreyasadchy.xtra.util

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.io.DataInputStream
import java.io.IOException
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

/**
 * Parses and applies HTTP, HTTPS, SOCKS5 (`socks://`, `socks5://`), and SOCKS4 (`socks4://`)
 * stream proxy settings across OkHttp, Cronet, and HttpEngine without requiring a DB schema change.
 */
object StreamProxyHelper {

    enum class ProxyProtocol(val scheme: String, val displayName: String) {
        HTTP("http", "HTTP"),
        HTTPS("https", "HTTPS"),
        SOCKS5("socks5", "SOCKS5"),
        SOCKS4("socks4", "SOCKS4"),
    }

    data class ParsedProxy(
        val protocol: ProxyProtocol,
        val host: String,
        val port: Int?,
    ) {
        val isSocks: Boolean
            get() = protocol == ProxyProtocol.SOCKS5 || protocol == ProxyProtocol.SOCKS4
    }

    private val SCHEME_PREFIX = Regex("^(https?|socks5h?|socks4a?|socks)://", RegexOption.IGNORE_CASE)

    /**
     * Parses a stored or user-entered proxy host (optionally prefixed with `http://`, `https://`,
     * `socks://`, `socks5://`, `socks4://` and optional `:port`) together with an optional port.
     */
    fun parse(rawHost: String?, rawPort: Int?): ParsedProxy? {
        val trimmed = rawHost?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val match = SCHEME_PREFIX.find(trimmed)
        val protocol = when (match?.groupValues?.get(1)?.lowercase()) {
            "https" -> ProxyProtocol.HTTPS
            "socks", "socks5", "socks5h" -> ProxyProtocol.SOCKS5
            "socks4", "socks4a" -> ProxyProtocol.SOCKS4
            else -> ProxyProtocol.HTTP
        }

        val withoutScheme = if (match != null) trimmed.substring(match.value.length) else trimmed
        val authority = withoutScheme.substringBefore('/').trim()
        if (authority.isEmpty()) return null

        var host = authority
        var resolvedPort = rawPort

        if (authority.startsWith("[")) {
            val closeBracket = authority.indexOf(']')
            if (closeBracket > 1) {
                host = authority.substring(1, closeBracket)
                val remainder = authority.substring(closeBracket + 1)
                if (resolvedPort == null && remainder.startsWith(":")) {
                    resolvedPort = remainder.substring(1).toIntOrNull()
                }
            }
        } else {
            val firstColon = authority.indexOf(':')
            val lastColon = authority.lastIndexOf(':')
            if (firstColon > 0 && firstColon == lastColon) {
                val maybePort = authority.substring(lastColon + 1).toIntOrNull()
                if (maybePort != null && maybePort in 1..65535) {
                    host = authority.substring(0, lastColon).trim()
                    if (resolvedPort == null) {
                        resolvedPort = maybePort
                    }
                }
            }
        }

        if (host.isEmpty()) return null
        return ParsedProxy(protocol = protocol, host = host, port = resolvedPort)
    }

    /**
     * Formats user dialog input into the stored `(host, port)` pair.
     * Explicit schemes typed in [rawHost] (e.g. `socks://10.0.1.20:1080`) override [selectedProtocol].
     * Plain HTTP proxies are stored without a scheme prefix for full backward compatibility.
     */
    fun formatStoredHost(
        selectedProtocol: ProxyProtocol,
        rawHost: String?,
        rawPort: String?,
    ): Pair<String?, Int?> {
        val trimmedHost = rawHost?.trim().orEmpty()
        val parsedPort = rawPort?.trim()?.toIntOrNull()
        if (trimmedHost.isEmpty()) return null to parsedPort

        val explicitScheme = SCHEME_PREFIX.containsMatchIn(trimmedHost)
        val parsed = parse(trimmedHost, parsedPort) ?: return trimmedHost to parsedPort
        val effectiveProtocol = if (explicitScheme) parsed.protocol else selectedProtocol
        val storedHost = when (effectiveProtocol) {
            ProxyProtocol.HTTP -> parsed.host
            ProxyProtocol.HTTPS, ProxyProtocol.SOCKS5, ProxyProtocol.SOCKS4 -> "${effectiveProtocol.scheme}://${parsed.host}"
        }
        return storedHost to parsed.port
    }

    /** Formats a user-facing label such as `SOCKS5 · 10.0.1.20:1080` or `proxy.example.com:8080`. */
    fun formatDisplayLabel(rawHost: String?, rawPort: Int?): String {
        val parsed = parse(rawHost, rawPort) ?: return rawHost.orEmpty().ifBlank { "—" }
        val hostPort = buildString {
            append(parsed.host)
            parsed.port?.let { append(":$it") }
        }
        return if (parsed.protocol == ProxyProtocol.HTTP) {
            hostPort
        } else {
            "${parsed.protocol.displayName} · $hostPort"
        }
    }

    /**
     * Configures [builder] with the appropriate proxy settings for HTTP, HTTPS, SOCKS5, or SOCKS4.
     * When [hostFilterRegex] is provided, only requests whose host matches it are sent through the proxy.
     */
    fun applyToOkHttpBuilder(
        builder: OkHttpClient.Builder,
        rawHost: String?,
        rawPort: Int?,
        user: String?,
        password: String?,
        hostFilterRegex: Regex? = null,
    ): Boolean {
        val parsed = parse(rawHost, rawPort) ?: return false
        val port = parsed.port ?: return false

        when (parsed.protocol) {
            ProxyProtocol.HTTP, ProxyProtocol.HTTPS -> {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(parsed.host, port))
                applyProxyOrSelector(builder, proxy, hostFilterRegex)
                if (parsed.protocol == ProxyProtocol.HTTPS) {
                    builder.socketFactory(HttpsProxySocketFactory(parsed.host, port))
                }
                if (!user.isNullOrBlank() && !password.isNullOrBlank()) {
                    builder.proxyAuthenticator { _, response ->
                        response.request.newBuilder().header(
                            "Proxy-Authorization",
                            Credentials.basic(user, password),
                        ).build()
                    }
                }
            }
            ProxyProtocol.SOCKS5 -> {
                if (!user.isNullOrBlank() && !password.isNullOrBlank()) {
                    SocksProxyAuthenticator.register(parsed.host, port, user, password)
                }
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(parsed.host, port))
                applyProxyOrSelector(builder, proxy, hostFilterRegex)
            }
            ProxyProtocol.SOCKS4 -> {
                // SOCKS4a tunnels directly over a custom SocketFactory; keep OkHttp's proxy as NO_PROXY.
                builder.proxy(Proxy.NO_PROXY)
                builder.socketFactory(Socks4aSocketFactory(parsed.host, port, user, hostFilterRegex))
            }
        }
        return true
    }

    private fun applyProxyOrSelector(
        builder: OkHttpClient.Builder,
        proxy: Proxy,
        hostFilterRegex: Regex?,
    ) {
        if (hostFilterRegex == null) {
            builder.proxy(proxy)
        } else {
            builder.proxySelector(
                object : ProxySelector() {
                    override fun select(u: URI): List<Proxy> {
                        val h = u.host ?: return listOf(Proxy.NO_PROXY)
                        return if (hostFilterRegex.matches(h)) {
                            listOf(proxy, Proxy.NO_PROXY)
                        } else {
                            listOf(Proxy.NO_PROXY)
                        }
                    }

                    override fun connectFailed(u: URI, sa: SocketAddress, e: IOException) {}
                }
            )
        }
    }

    /**
     * Thread-safe JVM [Authenticator] for SOCKS5 username/password authentication.
     */
    private object SocksProxyAuthenticator : Authenticator() {
        private val installed = AtomicBoolean(false)
        private val credentials = ConcurrentHashMap<String, PasswordAuthentication>()

        fun register(host: String, port: Int, user: String, password: String) {
            val auth = PasswordAuthentication(user, password.toCharArray())
            credentials["${host.lowercase()}:$port"] = auth
            runCatching {
                InetAddress.getByName(host).hostAddress?.let { ip ->
                    credentials["${ip.lowercase()}:$port"] = auth
                }
            }
            if (installed.compareAndSet(false, true)) {
                setDefault(this)
            }
        }

        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val port = requestingPort
            val host = requestingHost?.lowercase()
            if (host != null) {
                credentials["$host:$port"]?.let { return it }
            }
            val siteIp = requestingSite?.hostAddress?.lowercase()
            if (siteIp != null) {
                credentials["$siteIp:$port"]?.let { return it }
            }
            return null
        }
    }

    /**
     * Wraps connections to an HTTPS proxy in TLS while keeping direct (NO_PROXY) connections plain.
     */
    private class HttpsProxySocketFactory(
        private val proxyHost: String,
        private val proxyPort: Int,
    ) : SocketFactory() {
        private val plainFactory: SocketFactory = getDefault()
        private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

        private fun isProxyTarget(host: String?, port: Int): Boolean {
            if (port != proxyPort || host.isNullOrBlank()) return false
            if (host.equals(proxyHost, ignoreCase = true)) return true
            return runCatching {
                InetAddress.getByName(proxyHost).hostAddress.equals(host, ignoreCase = true)
            }.getOrDefault(false)
        }

        override fun createSocket(): Socket = ProxyAwareTlsSocket()

        override fun createSocket(host: String, port: Int): Socket =
            if (isProxyTarget(host, port)) sslFactory.createSocket(host, port)
            else plainFactory.createSocket(host, port)

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            if (isProxyTarget(host, port)) sslFactory.createSocket(host, port, localHost, localPort)
            else plainFactory.createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket =
            if (isProxyTarget(host.hostAddress, port) || isProxyTarget(host.hostName, port)) {
                sslFactory.createSocket(host, port)
            } else {
                plainFactory.createSocket(host, port)
            }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            if (isProxyTarget(address.hostAddress, port) || isProxyTarget(address.hostName, port)) {
                sslFactory.createSocket(address, port, localAddress, localPort)
            } else {
                plainFactory.createSocket(address, port, localAddress, localPort)
            }

        private inner class ProxyAwareTlsSocket : Socket() {
            private var delegate: Socket? = null

            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val inet = endpoint as? InetSocketAddress
                if (inet != null && isProxyTarget(inet.hostString, inet.port)) {
                    val raw = Socket()
                    raw.connect(inet, timeout)
                    val tls = sslFactory.createSocket(raw, proxyHost, proxyPort, true) as javax.net.ssl.SSLSocket
                    tls.startHandshake()
                    delegate = tls
                } else {
                    super.connect(endpoint, timeout)
                }
            }

            override fun getInputStream() = delegate?.inputStream ?: super.getInputStream()
            override fun getOutputStream() = delegate?.outputStream ?: super.getOutputStream()
            override fun close() {
                delegate?.close()
                super.close()
            }
            override fun isConnected() = delegate?.isConnected ?: super.isConnected()
            override fun isBound() = delegate?.isBound ?: super.isBound()
            override fun isClosed() = delegate?.isClosed ?: super.isClosed()
            override fun getInetAddress() = delegate?.inetAddress ?: super.getInetAddress()
            override fun getPort() = delegate?.port ?: super.getPort()
            override fun getLocalAddress() = delegate?.localAddress ?: super.getLocalAddress()
            override fun getLocalPort() = delegate?.localPort ?: super.getLocalPort()
            override fun getRemoteSocketAddress() = delegate?.remoteSocketAddress ?: super.getRemoteSocketAddress()
            override fun setSoTimeout(timeout: Int) {
                delegate?.soTimeout = timeout
                super.setSoTimeout(timeout)
            }
            override fun getSoTimeout() = delegate?.soTimeout ?: super.getSoTimeout()
            override fun setTcpNoDelay(on: Boolean) {
                delegate?.tcpNoDelay = on
                super.setTcpNoDelay(on)
            }
        }
    }

    /**
     * Lightweight SOCKS4a [SocketFactory] supporting remote DNS resolution and optional user ID.
     */
    private class Socks4aSocketFactory(
        private val proxyHost: String,
        private val proxyPort: Int,
        private val userId: String?,
        private val hostFilterRegex: Regex?,
    ) : SocketFactory() {

        private fun shouldProxy(targetHost: String?): Boolean {
            if (hostFilterRegex == null) return true
            if (targetHost.isNullOrBlank()) return false
            return hostFilterRegex.matches(targetHost)
        }

        override fun createSocket(): Socket = Socks4aSocket()

        override fun createSocket(host: String, port: Int): Socket {
            val s = Socks4aSocket()
            s.connect(InetSocketAddress.createUnresolved(host, port), 10_000)
            return s
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket(host, port)

        override fun createSocket(host: InetAddress, port: Int): Socket {
            val s = Socks4aSocket()
            s.connect(InetSocketAddress(host, port), 10_000)
            return s
        }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            createSocket(address, port)

        private inner class Socks4aSocket : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val inet = endpoint as? InetSocketAddress
                    ?: throw IOException("Unsupported socket address: $endpoint")
                val targetHost = inet.hostString
                if (!shouldProxy(targetHost)) {
                    super.connect(endpoint, timeout)
                    return
                }
                super.connect(InetSocketAddress(proxyHost, proxyPort), timeout)
                performSocks4aHandshake(this, targetHost, inet.port, userId.orEmpty())
            }
        }
    }

    internal fun buildSocks4aRequest(targetHost: String, targetPort: Int, userId: String): ByteArray {
        val userBytes = userId.toByteArray(Charsets.UTF_8)
        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        val packet = ByteArray(8 + userBytes.size + 1 + hostBytes.size + 1)
        var pos = 0
        packet[pos++] = 0x04 // VN: SOCKS4
        packet[pos++] = 0x01 // CD: CONNECT
        packet[pos++] = ((targetPort shr 8) and 0xFF).toByte()
        packet[pos++] = (targetPort and 0xFF).toByte()
        // DSTIP: 0.0.0.1 signals SOCKS4a domain name extension
        packet[pos++] = 0x00
        packet[pos++] = 0x00
        packet[pos++] = 0x00
        packet[pos++] = 0x01
        System.arraycopy(userBytes, 0, packet, pos, userBytes.size)
        pos += userBytes.size
        packet[pos++] = 0x00
        System.arraycopy(hostBytes, 0, packet, pos, hostBytes.size)
        pos += hostBytes.size
        packet[pos] = 0x00
        return packet
    }

    private fun performSocks4aHandshake(socket: Socket, targetHost: String, targetPort: Int, userId: String) {
        val out = socket.getOutputStream()
        out.write(buildSocks4aRequest(targetHost, targetPort, userId))
        out.flush()
        val reply = ByteArray(8)
        DataInputStream(socket.getInputStream()).readFully(reply)
        val status = reply[1].toInt() and 0xFF
        if (status != 0x5A) {
            runCatching { socket.close() }
            throw IOException("SOCKS4a connection rejected by proxy (status=0x${status.toString(16)})")
        }
    }
}
