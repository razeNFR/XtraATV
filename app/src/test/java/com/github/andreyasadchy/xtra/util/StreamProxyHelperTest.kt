package com.github.andreyasadchy.xtra.util

import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.StreamProxyHelper.ProxyProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamProxyHelperTest {

    @Test
    fun parsePlainHttpHostAndPort() {
        val parsed = StreamProxyHelper.parse("192.168.1.5", 8080)!!
        assertEquals(ProxyProtocol.HTTP, parsed.protocol)
        assertEquals("192.168.1.5", parsed.host)
        assertEquals(8080, parsed.port)
        assertFalse(parsed.isSocks)
    }

    @Test
    fun parseSocksAndSocks5SchemesWithInlinePort() {
        val parsedTtvLol = StreamProxyHelper.parse("socks://10.0.1.20:1080", null)!!
        assertEquals(ProxyProtocol.SOCKS5, parsedTtvLol.protocol)
        assertEquals("10.0.1.20", parsedTtvLol.host)
        assertEquals(1080, parsedTtvLol.port)
        assertTrue(parsedTtvLol.isSocks)

        val parsedSocks5 = StreamProxyHelper.parse("socks5://us-nyc-wg-socks5-001.relays.mullvad.net", 1080)!!
        assertEquals(ProxyProtocol.SOCKS5, parsedSocks5.protocol)
        assertEquals("us-nyc-wg-socks5-001.relays.mullvad.net", parsedSocks5.host)
        assertEquals(1080, parsedSocks5.port)
    }

    @Test
    fun parseSocks4AndHttpsSchemes() {
        val socks4 = StreamProxyHelper.parse("socks4://10.0.0.2:1080", null)!!
        assertEquals(ProxyProtocol.SOCKS4, socks4.protocol)
        assertEquals("10.0.0.2", socks4.host)
        assertEquals(1080, socks4.port)
        assertTrue(socks4.isSocks)

        val https = StreamProxyHelper.parse("https://proxy.example.com", 443)!!
        assertEquals(ProxyProtocol.HTTPS, https.protocol)
        assertEquals("proxy.example.com", https.host)
        assertEquals(443, https.port)
        assertFalse(https.isSocks)
    }

    @Test
    fun formatStoredHostPreservesHttpCompatibilityAndPrefixesOthers() {
        assertEquals(
            "10.0.1.20" to 8080,
            StreamProxyHelper.formatStoredHost(ProxyProtocol.HTTP, "10.0.1.20", "8080"),
        )
        assertEquals(
            "socks5://10.0.1.20" to 1080,
            StreamProxyHelper.formatStoredHost(ProxyProtocol.SOCKS5, "10.0.1.20", "1080"),
        )
        // Explicit scheme typed in host overrides dropdown selection and extracts inline port
        assertEquals(
            "socks5://10.0.1.20" to 1080,
            StreamProxyHelper.formatStoredHost(ProxyProtocol.HTTP, "socks://10.0.1.20:1080", ""),
        )
        assertEquals(
            "socks4://10.0.1.20" to 1081,
            StreamProxyHelper.formatStoredHost(ProxyProtocol.HTTP, "socks4://10.0.1.20:1081", ""),
        )
        assertEquals(
            "https://proxy.example.com" to 443,
            StreamProxyHelper.formatStoredHost(ProxyProtocol.HTTPS, "proxy.example.com", "443"),
        )
    }

    @Test
    fun formatDisplayLabelShowsProtocolForNonHttp() {
        assertEquals("10.0.1.20:8080", StreamProxyHelper.formatDisplayLabel("10.0.1.20", 8080))
        assertEquals("SOCKS5 · 10.0.1.20:1080", StreamProxyHelper.formatDisplayLabel("socks5://10.0.1.20", 1080))
        assertEquals("SOCKS4 · 10.0.1.20:1080", StreamProxyHelper.formatDisplayLabel("socks4://10.0.1.20", 1080))
        assertEquals("HTTPS · proxy.example.com:443", StreamProxyHelper.formatDisplayLabel("https://proxy.example.com", 443))
    }

    @Test
    fun buildSocks4aRequestEncodesDomainExtension() {
        val packet = StreamProxyHelper.buildSocks4aRequest("gql.twitch.tv", 443, "u1")
        val expected = byteArrayOf(
            0x04, 0x01,
            0x01, 0xBB.toByte(), // port 443
            0x00, 0x00, 0x00, 0x01, // 0.0.0.1 SOCKS4a marker
            'u'.code.toByte(), '1'.code.toByte(), 0x00,
        ) + "gql.twitch.tv".toByteArray(Charsets.UTF_8) + byteArrayOf(0x00)
        assertArrayEquals(expected, packet)
    }

    @Test
    fun streamDisplayLanguageCodeNormalizesProperly() {
        assertEquals("EN", Stream(language = "en").displayLanguageCode)
        assertEquals("FR", Stream(language = "fr").displayLanguageCode)
        assertEquals("PT", Stream(language = "pt-BR").displayLanguageCode)
        assertEquals("ASL", Stream(language = "asl").displayLanguageCode)
        assertNull(Stream(language = "other").displayLanguageCode)
        assertNull(Stream(language = "").displayLanguageCode)
        assertNull(Stream(language = null).displayLanguageCode)
    }

    @Test
    fun endToEndSocks5NoAuthMullvadStyleWorksWithOkHttp() {
        val target = startHttpEchoServer("SOCKS5-NOAUTH-OK")
        val proxy = startSocks5Server(expectedUser = null, expectedPass = null)
        try {
            val client = okhttp3.OkHttpClient.Builder().apply {
                StreamProxyHelper.applyToOkHttpBuilder(
                    builder = this,
                    rawHost = "socks://127.0.0.1",
                    rawPort = proxy.port,
                    user = null,
                    password = null,
                )
            }.build()
            val req = okhttp3.Request.Builder().url("http://127.0.0.1:${target.port}/playlist.m3u8").build()
            client.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("SOCKS5-NOAUTH-OK", resp.body.string())
            }
            assertEquals(1, proxy.handledConnections.get())
        } finally {
            proxy.close()
            target.close()
        }
    }

    @Test
    fun endToEndSocks5WithUsernamePasswordAuthWorksWithOkHttp() {
        val target = startHttpEchoServer("SOCKS5-AUTH-OK")
        val proxy = startSocks5Server(expectedUser = "mullvadUser", expectedPass = "secret123")
        try {
            val client = okhttp3.OkHttpClient.Builder().apply {
                StreamProxyHelper.applyToOkHttpBuilder(
                    builder = this,
                    rawHost = "socks5://127.0.0.1",
                    rawPort = proxy.port,
                    user = "mullvadUser",
                    password = "secret123",
                )
            }.build()
            val req = okhttp3.Request.Builder().url("http://127.0.0.1:${target.port}/gql").build()
            client.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("SOCKS5-AUTH-OK", resp.body.string())
            }
            assertEquals(1, proxy.handledConnections.get())
        } finally {
            proxy.close()
            target.close()
        }
    }

    @Test
    fun endToEndSocks4aWorksWithOkHttp() {
        val target = startHttpEchoServer("SOCKS4A-OK")
        val proxy = startSocks4aServer(expectedUser = "u4")
        try {
            val client = okhttp3.OkHttpClient.Builder().apply {
                StreamProxyHelper.applyToOkHttpBuilder(
                    builder = this,
                    rawHost = "socks4://127.0.0.1",
                    rawPort = proxy.port,
                    user = "u4",
                    password = null,
                )
            }.build()
            val req = okhttp3.Request.Builder().url("http://127.0.0.1:${target.port}/playlist.m3u8").build()
            client.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("SOCKS4A-OK", resp.body.string())
            }
            assertEquals(1, proxy.handledConnections.get())
        } finally {
            proxy.close()
            target.close()
        }
    }

    @Test
    fun endToEndHostFilterRoutesMatchingHostsThroughSocks5AndBypassesOthers() {
        val target = startHttpEchoServer("FILTERED-OK")
        val proxy = startSocks5Server(expectedUser = null, expectedPass = null)
        try {
            // Only proxy "localhost", bypass "127.0.0.1" (simulating playlist vs direct video segments)
            val client = okhttp3.OkHttpClient.Builder().apply {
                StreamProxyHelper.applyToOkHttpBuilder(
                    builder = this,
                    rawHost = "socks5://127.0.0.1",
                    rawPort = proxy.port,
                    user = null,
                    password = null,
                    hostFilterRegex = Regex("^localhost$"),
                )
            }.build()

            client.newCall(okhttp3.Request.Builder().url("http://localhost:${target.port}/master.m3u8").build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("FILTERED-OK", resp.body.string())
            }
            assertEquals(1, proxy.handledConnections.get())

            client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${target.port}/segment.ts").build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("FILTERED-OK", resp.body.string())
            }
            // Direct segment request must NOT hit the SOCKS5 proxy
            assertEquals(1, proxy.handledConnections.get())
        } finally {
            proxy.close()
            target.close()
        }
    }

    @Test
    fun endToEndHostFilterRoutesMatchingHostsThroughSocks4aAndBypassesOthers() {
        val target = startHttpEchoServer("FILTERED4-OK")
        val proxy = startSocks4aServer(expectedUser = null)
        try {
            val client = okhttp3.OkHttpClient.Builder().apply {
                StreamProxyHelper.applyToOkHttpBuilder(
                    builder = this,
                    rawHost = "socks4://127.0.0.1",
                    rawPort = proxy.port,
                    user = null,
                    password = null,
                    hostFilterRegex = Regex("^localhost$"),
                )
            }.build()

            client.newCall(okhttp3.Request.Builder().url("http://localhost:${target.port}/master.m3u8").build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("FILTERED4-OK", resp.body.string())
            }
            assertEquals(1, proxy.handledConnections.get())

            client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${target.port}/segment.ts").build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("FILTERED4-OK", resp.body.string())
            }
            assertEquals(1, proxy.handledConnections.get())
        } finally {
            proxy.close()
            target.close()
        }
    }

    private class LocalTestServer(
        private val serverSocket: java.net.ServerSocket,
        val handledConnections: java.util.concurrent.atomic.AtomicInteger,
        private val thread: Thread,
    ) : AutoCloseable {
        val port: Int get() = serverSocket.localPort
        override fun close() {
            runCatching { serverSocket.close() }
            thread.interrupt()
        }
    }

    private fun startHttpEchoServer(responseBody: String): LocalTestServer {
        val ss = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val t = Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { break }
                Thread {
                    client.use { sock ->
                        count.incrementAndGet()
                        val reader = sock.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bytes = responseBody.toByteArray(Charsets.UTF_8)
                        val headers = "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        sock.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
                        sock.getOutputStream().write(bytes)
                        sock.getOutputStream().flush()
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
        return LocalTestServer(ss, count, t)
    }

    private fun startSocks5Server(expectedUser: String?, expectedPass: String?): LocalTestServer {
        val ss = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val t = Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { break }
                Thread {
                    client.use { sock ->
                        val inp = java.io.DataInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()
                        val ver = inp.readUnsignedByte()
                        if (ver != 0x05) return@use
                        val nMethods = inp.readUnsignedByte()
                        val methods = ByteArray(nMethods)
                        inp.readFully(methods)
                        if (expectedUser != null && expectedPass != null) {
                            if (0x02.toByte() !in methods) {
                                out.write(byteArrayOf(0x05, 0xFF.toByte()))
                                out.flush()
                                return@use
                            }
                            out.write(byteArrayOf(0x05, 0x02))
                            out.flush()
                            val authVer = inp.readUnsignedByte()
                            val uLen = inp.readUnsignedByte()
                            val uBytes = ByteArray(uLen).also { inp.readFully(it) }
                            val pLen = inp.readUnsignedByte()
                            val pBytes = ByteArray(pLen).also { inp.readFully(it) }
                            val ok = authVer == 0x01 &&
                                String(uBytes, Charsets.UTF_8) == expectedUser &&
                                String(pBytes, Charsets.UTF_8) == expectedPass
                            out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
                            out.flush()
                            if (!ok) return@use
                        } else {
                            out.write(byteArrayOf(0x05, 0x00))
                            out.flush()
                        }

                        val reqVer = inp.readUnsignedByte()
                        val cmd = inp.readUnsignedByte()
                        inp.readUnsignedByte() // RSV
                        val atyp = inp.readUnsignedByte()
                        if (reqVer != 0x05 || cmd != 0x01) return@use
                        val destHost = when (atyp) {
                            0x01 -> {
                                val addr = ByteArray(4).also { inp.readFully(it) }
                                java.net.InetAddress.getByAddress(addr).hostAddress!!
                            }
                            0x03 -> {
                                val len = inp.readUnsignedByte()
                                val dom = ByteArray(len).also { inp.readFully(it) }
                                String(dom, Charsets.UTF_8)
                            }
                            0x04 -> {
                                val addr = ByteArray(16).also { inp.readFully(it) }
                                java.net.InetAddress.getByAddress(addr).hostAddress!!
                            }
                            else -> return@use
                        }
                        val destPort = inp.readUnsignedShort()
                        java.net.Socket(destHost, destPort).use { upstream ->
                            count.incrementAndGet()
                            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                            out.flush()
                            relaySockets(sock, upstream)
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
        return LocalTestServer(ss, count, t)
    }

    private fun startSocks4aServer(expectedUser: String?): LocalTestServer {
        val ss = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val t = Thread {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { break }
                Thread {
                    client.use { sock ->
                        val inp = java.io.DataInputStream(sock.getInputStream())
                        val out = sock.getOutputStream()
                        val vn = inp.readUnsignedByte()
                        val cd = inp.readUnsignedByte()
                        val destPort = inp.readUnsignedShort()
                        val ip = ByteArray(4).also { inp.readFully(it) }
                        val user = readNullTerminated(inp)
                        val is4a = ip[0] == 0.toByte() && ip[1] == 0.toByte() && ip[2] == 0.toByte() && ip[3] != 0.toByte()
                        val destHost = if (is4a) readNullTerminated(inp) else java.net.InetAddress.getByAddress(ip).hostAddress!!
                        if (vn != 0x04 || cd != 0x01 || (expectedUser != null && user != expectedUser)) {
                            out.write(byteArrayOf(0x00, 0x5B, 0, 0, 0, 0, 0, 0))
                            out.flush()
                            return@use
                        }
                        java.net.Socket(destHost, destPort).use { upstream ->
                            count.incrementAndGet()
                            out.write(byteArrayOf(0x00, 0x5A, 0, 0, 0, 0, 0, 0))
                            out.flush()
                            relaySockets(sock, upstream)
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
        return LocalTestServer(ss, count, t)
    }

    private fun readNullTerminated(inp: java.io.DataInputStream): String {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b <= 0) break
            buf.write(b)
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    private fun relaySockets(a: java.net.Socket, b: java.net.Socket) {
        val t1 = Thread {
            runCatching { a.getInputStream().copyTo(b.getOutputStream()); b.shutdownOutput() }
        }.apply { isDaemon = true; start() }
        runCatching { b.getInputStream().copyTo(a.getOutputStream()); a.shutdownOutput() }
        runCatching { t1.join(2_000) }
    }
}
