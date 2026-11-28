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
}
