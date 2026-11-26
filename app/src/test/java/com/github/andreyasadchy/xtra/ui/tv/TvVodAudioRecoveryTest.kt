package com.github.andreyasadchy.xtra.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvVodAudioRecoveryTest {

    private val vodUrl = "https://d1m7jfoe9zdc1j.cloudfront.net/abc123_channel_12345/chunked/index-dvr.m3u8"

    private fun newSession(enabled: Boolean = true): TvVodAudioRecovery.Session =
        TvVodAudioRecovery.Session(
            sessionId = 1L,
            enabledProvider = { enabled },
            publishToUi = false,
        )

    @Test
    fun playlistWithoutMutedSegmentsPassesThroughUnchanged() {
        val session = newSession(enabled = true)
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:10.000,
            0.ts
            #EXTINF:10.000,
            1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        var probeCalls = 0
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { _, _ ->
            probeCalls++
            TvVodAudioRecovery.SegmentMode.UNMUTED
        }

        assertEquals(playlist, out)
        assertEquals(0, probeCalls)
        assertTrue(session.state.recoveredRanges.isEmpty())
        assertTrue(session.state.mutedRanges.isEmpty())
    }

    @Test
    fun livePlaylistUrlsAreStrictlyIgnored() {
        val session = newSession(enabled = true)
        val liveUrl = "https://usher.ttvnw.net/api/channel/hls/test.m3u8"
        val playlist = "#EXTM3U\n#EXTINF:2.0,\n0-muted.ts\n"
        var probeCalls = 0
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(liveUrl, playlist, session) { _, _ ->
            probeCalls++
            TvVodAudioRecovery.SegmentMode.UNMUTED
        }
        assertEquals(playlist, out)
        assertEquals(0, probeCalls)
    }

    @Test
    fun rewritesMutedSegmentsPreservingCrlfPrefixAndQueryParams() {
        val session = newSession(enabled = true)
        val playlist = "#EXTM3U\r\n" +
            "#EXTINF:10.000,\r\n" +
            "0.ts\r\n" +
            "#EXTINF:9.500,\r\n" +
            "sub/1-muted.mp4?token=abc\r\n" +
            "#EXTINF:10.500,\r\n" +
            "sub/2-unmuted.mp4?token=def\r\n" +
            "#EXT-X-ENDLIST"

        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { unmutedUrl, _ ->
            if (unmutedUrl.contains("1-unmuted.mp4") || unmutedUrl.contains("2-unmuted.mp4")) {
                TvVodAudioRecovery.SegmentMode.UNMUTED
            } else {
                TvVodAudioRecovery.SegmentMode.MUTED
            }
        }

        assertTrue("CRLF must be preserved", out.contains("\r\n"))
        assertTrue(out.contains("sub/1-unmuted.mp4?token=abc"))
        assertTrue(out.contains("sub/2-unmuted.mp4?token=def"))
        assertEquals(1, session.state.recoveredRanges.size)
        // Real #EXTINF durations: 10000ms .. (10000 + 9500 + 10500 = 30000ms)
        assertEquals(10_000L to 30_000L, session.state.recoveredRanges.first())
        assertEquals(20_000L, session.state.recoveredDurationMs)
        assertEquals(true, session.state.statusAt(15_000L))
        assertNull(session.state.statusAt(2_000L))
    }

    @Test
    fun probesPerRangeIndependentlyWhenOneRangeRecoverableAndOneMuted() {
        val session = newSession(enabled = true)
        val playlist = buildString {
            appendLine("#EXTM3U")
            // Range 1: segments 1..4 (10s..50s) -> recoverable as PLAIN
            appendLine("#EXTINF:10.000,")
            appendLine("0.ts")
            for (i in 1..4) {
                appendLine("#EXTINF:10.000,")
                appendLine("$i-muted.ts")
            }
            // Unmuted gap: segment 5 (50s..60s)
            appendLine("#EXTINF:10.000,")
            appendLine("5.ts")
            // Range 2: segments 6..9 (60s..100s) -> permanently muted (403 on both)
            for (i in 6..9) {
                appendLine("#EXTINF:10.000,")
                appendLine("$i-muted.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val probedUrls = mutableListOf<String>()
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { unmutedUrl, plainUrl ->
            probedUrls.add(unmutedUrl)
            val segNum = Regex("""/(\d+)-unmuted\.ts""").find(unmutedUrl)?.groupValues?.get(1)?.toInt() ?: -1
            if (segNum in 1..4 && plainUrl.endsWith("/$segNum.ts")) {
                TvVodAudioRecovery.SegmentMode.PLAIN
            } else {
                TvVodAudioRecovery.SegmentMode.MUTED
            }
        }

        // Start, mid, end probed for each range -> 3 + 3 = 6 probes total
        assertEquals(6, probedUrls.size)
        // Range 1 rewritten to plain N.ts
        assertTrue(out.contains("\n1.ts\n"))
        assertTrue(out.contains("\n4.ts\n"))
        // Range 2 stays muted
        assertTrue(out.contains("\n6-muted.ts\n"))
        assertTrue(out.contains("\n9-muted.ts\n"))

        assertEquals(listOf(10_000L to 50_000L), session.state.recoveredRanges)
        assertEquals(listOf(60_000L to 100_000L), session.state.mutedRanges)
        assertEquals(true, session.state.statusAt(25_000L))
        assertEquals(false, session.state.statusAt(75_000L))
    }

    @Test
    fun permanentSegmentFallbackTurnsGreenSegmentRedAndPersistsAcrossReload() {
        val session = newSession(enabled = true)
        val playlist = """
            #EXTM3U
            #EXTINF:10.000,
            0.ts
            #EXTINF:10.000,
            1-muted.ts
            #EXTINF:10.000,
            2-muted.ts
            #EXTINF:10.000,
            3-muted.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val out1 = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { _, _ ->
            TvVodAudioRecovery.SegmentMode.UNMUTED
        }
        assertTrue(out1.contains("2-unmuted.ts"))
        assertEquals(listOf(10_000L to 40_000L), session.state.recoveredRanges)
        assertTrue(session.state.mutedRanges.isEmpty())

        // Simulate 403/404 runtime failure on segment 2 -> marks permanently failed
        session.markSegmentFailed("2.ts")

        // Seekbar state immediately reflects red for segment 2 (20s..30s) and green for 1 & 3
        assertEquals(listOf(10_000L to 20_000L, 30_000L to 40_000L), session.state.recoveredRanges)
        assertEquals(listOf(20_000L to 30_000L), session.state.mutedRanges)
        assertEquals(false, session.state.statusAt(25_000L))

        // On subsequent playlist reload (e.g. live DVR / Adblock VOD refresh), segment 2 stays -muted.ts
        val out2 = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { _, _ ->
            TvVodAudioRecovery.SegmentMode.UNMUTED
        }
        assertTrue(out2.contains("1-unmuted.ts"))
        assertTrue(out2.contains("2-muted.ts"))
        assertFalse(out2.contains("2-unmuted.ts"))
        assertTrue(out2.contains("3-unmuted.ts"))
    }

    @Test
    fun disabledToggleForcesMutedAndDoesNotProbe() {
        val session = newSession(enabled = false)
        val playlist = """
            #EXTM3U
            #EXTINF:10.000,
            1-muted.ts
            #EXTINF:10.000,
            2-unmuted.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        var probeCalls = 0
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { _, _ ->
            probeCalls++
            TvVodAudioRecovery.SegmentMode.UNMUTED
        }

        assertEquals(0, probeCalls)
        assertTrue(out.contains("1-muted.ts"))
        assertTrue(out.contains("2-muted.ts"))
        assertTrue(session.state.recoveredRanges.isEmpty())
        assertEquals(listOf(0L to 20_000L), session.state.mutedRanges)
    }

    @Test
    fun singleBoundaryProbeHitDoesNotMarkWholeRangeGreen() {
        val session = newSession(enabled = true)
        val playlist = buildString {
            appendLine("#EXTM3U")
            for (i in 1..7) {
                appendLine("#EXTINF:10.000,")
                appendLine("$i-muted.mp4")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        // Only segment 1 (edge) returns UNMUTED; middle (4) and end (7) return 403 -> MUTED
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { unmutedUrl, _ ->
            if (unmutedUrl.endsWith("/1-unmuted.mp4")) {
                TvVodAudioRecovery.SegmentMode.UNMUTED
            } else {
                TvVodAudioRecovery.SegmentMode.MUTED
            }
        }

        // Only the verified segment 1 is unmuted; segments 2..7 must remain -muted.mp4 (red, not falsely green)
        assertTrue(out.contains("1-unmuted.mp4"))
        assertTrue(out.contains("2-muted.mp4"))
        assertTrue(out.contains("4-muted.mp4"))
        assertTrue(out.contains("7-muted.mp4"))
        assertEquals(listOf(0L to 10_000L), session.state.recoveredRanges)
        assertEquals(listOf(10_000L to 70_000L), session.state.mutedRanges)
    }
}
