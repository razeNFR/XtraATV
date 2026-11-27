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
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { _, _, _, _ ->
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
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(liveUrl, playlist, session) { _, _, _, _ ->
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

        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { unmutedUrl, _, _, _ ->
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
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { unmutedUrl, plainUrl, _, _ ->
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

        val out1 = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { _, _, _, _ ->
            TvVodAudioRecovery.SegmentMode.UNMUTED
        }
        assertTrue(out1.contains("2-unmuted.ts"))
        assertEquals(listOf(10_000L to 40_000L), session.state.recoveredRanges)
        assertTrue(session.state.mutedRanges.isEmpty())

        // Simulate 403/404 runtime failure on segment 2 -> marks permanently failed (scoped to quality dir)
        val seg2Key = "${vodUrl.substringBeforeLast('/')}/2.ts"
        session.markSegmentFailed(seg2Key)

        // Seekbar state immediately reflects red for segment 2 (20s..30s) and green for 1 & 3
        assertEquals(listOf(10_000L to 20_000L, 30_000L to 40_000L), session.state.recoveredRanges)
        assertEquals(listOf(20_000L to 30_000L), session.state.mutedRanges)
        assertEquals(false, session.state.statusAt(25_000L))

        // On subsequent playlist reload (e.g. live DVR / Adblock VOD refresh), segment 2 stays -muted.ts
        val out2 = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { _, _, _, _ ->
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
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { _, _, _, _ ->
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
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodUrl, playlist, session) { unmutedUrl, _, _, _ ->
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

    @Test
    fun probePrioritizesUnmutedOverPlainAndIgnores403OnPlain() {
        // Case 1: unmuted returns 200 (9.58 MB), plain returns 403 -> MUST choose UNMUTED (never abandon on plain 403)
        val unmutedOk = TvVodAudioRecovery.ProbeMeta(ok = true, httpCode = 200, contentLength = 9_580_000L)
        val plain403 = TvVodAudioRecovery.ProbeMeta(ok = false, httpCode = 403, contentLength = null)
        assertEquals(
            TvVodAudioRecovery.SegmentMode.UNMUTED,
            TvVodAudioRecovery.evaluateProbeCandidates(unmutedOk, plain403),
        )

        // Case 2: both unmuted and plain return 200 -> MUST prioritize UNMUTED
        val plainOk = TvVodAudioRecovery.ProbeMeta(ok = true, httpCode = 200, contentLength = 9_580_000L)
        assertEquals(
            TvVodAudioRecovery.SegmentMode.UNMUTED,
            TvVodAudioRecovery.evaluateProbeCandidates(unmutedOk, plainOk),
        )

        // Case 3: unmuted returns 403, plain returns 200 -> choose PLAIN
        val unmuted403 = TvVodAudioRecovery.ProbeMeta(ok = false, httpCode = 403, contentLength = null)
        assertEquals(
            TvVodAudioRecovery.SegmentMode.PLAIN,
            TvVodAudioRecovery.evaluateProbeCandidates(unmuted403, plainOk),
        )

        // Case 4: unmuted returns 200 with tiny stub (<= 512B), plain returns 403 -> MUTED
        val tinyStub = TvVodAudioRecovery.ProbeMeta(ok = true, httpCode = 200, contentLength = 128L)
        assertEquals(
            TvVodAudioRecovery.SegmentMode.MUTED,
            TvVodAudioRecovery.evaluateProbeCandidates(tinyStub, plain403),
        )
    }

    @Test
    fun realFmp4Vod2942267263Rewrites90SegmentsToUnmutedAndMarksBothRangesGreen() {
        val vodPlaylistUrl = "https://d1m7jfoe9zdc1j.cloudfront.net/abc_2942267263/chunked/index-muted-9VCV0XE0E0.m3u8"
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:10")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("""#EXT-X-MAP:URI="init-0.mp4"""")
            for (i in 0..930) {
                appendLine("#EXTINF:10.000,")
                if (i in 558..629 || i in 900..917) {
                    appendLine("$i-muted.mp4")
                } else {
                    appendLine("$i.mp4")
                }
            }
            appendLine("#EXT-X-ENDLIST")
        }

        val session = newSession(enabled = true)
        val out = TvVodAudioRecovery.rewriteMediaPlaylist(vodPlaylistUrl, playlist, session) { unmutedUrl, plainUrl, _, _ ->
            // Simulate Twitch server for VOD 2942267263: N-unmuted.mp4 -> 200 (9.58 MB), N.mp4 -> 403
            val unmutedMeta = TvVodAudioRecovery.ProbeMeta(
                ok = unmutedUrl.endsWith("-unmuted.mp4"),
                httpCode = 200,
                contentLength = 9_580_000L,
            )
            val plainMeta = TvVodAudioRecovery.ProbeMeta(
                ok = false,
                httpCode = 403,
                contentLength = null,
            )
            assertTrue("Plain URL must resolve into chunked dir: $plainUrl", plainUrl.contains("/chunked/"))
            TvVodAudioRecovery.evaluateProbeCandidates(unmutedMeta, plainMeta)
        }

        val unmutedLines = out.lines().count { it.endsWith("-unmuted.mp4") }
        val remainingMutedLines = out.lines().count { it.endsWith("-muted.mp4") }
        assertEquals("All 90 muted segments (72 + 18) must be rewritten to -unmuted.mp4", 90, unmutedLines)
        assertEquals(0, remainingMutedLines)
        assertTrue(out.contains("558-unmuted.mp4"))
        assertTrue(out.contains("629-unmuted.mp4"))
        assertTrue(out.contains("900-unmuted.mp4"))
        assertTrue(out.contains("917-unmuted.mp4"))
        assertTrue(out.contains("""#EXT-X-MAP:URI="init-0.mp4""""))

        // Range 1: 1:33:00 (5_580_000 ms) -> 1:45:00 (6_300_000 ms)
        // Range 2: 2:30:00 (9_000_000 ms) -> 2:33:00 (9_180_000 ms)
        assertEquals(
            listOf(5_580_000L to 6_300_000L, 9_000_000L to 9_180_000L),
            session.state.recoveredRanges,
        )
        assertTrue(session.state.mutedRanges.isEmpty())
        assertEquals(900_000L, session.state.recoveredDurationMs)
    }

    @Test
    fun qualitySwitchDoesNotReuseFailedCacheFromAnotherQualityFolder() {
        val lowUrl = "https://d1m7jfoe9zdc1j.cloudfront.net/vod/360p30/index-muted-ABC.m3u8"
        val highUrl = "https://d1m7jfoe9zdc1j.cloudfront.net/vod/chunked/index-muted-ABC.m3u8"
        val playlist = "#EXTM3U\n#EXTINF:10.000,\n10-muted.m4s?sig=1\n#EXT-X-ENDLIST\n"
        val session = newSession(enabled = true)

        // Low quality fails -> MUTED
        val outLow = TvVodAudioRecovery.rewriteMediaPlaylist(lowUrl, playlist, session) { _, _, _, _ ->
            TvVodAudioRecovery.SegmentMode.MUTED
        }
        assertTrue(outLow.contains("10-muted.m4s?sig=1"))

        // High quality switches folder -> probes independently and recovers!
        val outHigh = TvVodAudioRecovery.rewriteMediaPlaylist(highUrl, playlist, session) { unmutedUrl, _, _, _ ->
            assertEquals("https://d1m7jfoe9zdc1j.cloudfront.net/vod/chunked/10-unmuted.m4s?sig=1", unmutedUrl)
            TvVodAudioRecovery.SegmentMode.UNMUTED
        }
        assertTrue(outHigh.contains("10-unmuted.m4s?sig=1"))
    }
}
