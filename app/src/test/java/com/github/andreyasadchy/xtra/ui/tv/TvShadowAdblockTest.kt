package com.github.andreyasadchy.xtra.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvShadowAdblockTest {

    private val primaryVariantUrl = "https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/primary_1440p.m3u8"
    private val primaryVariant1080Url = "https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/primary_1080p.m3u8"
    private val shadowVariantUrl = "https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/shadow_1440p.m3u8"

    private fun buildCleanPlaylist(
        mediaSeq: Long,
        startSec: Int,
        count: Int = 5,
        prefix: String = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live",
    ): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$mediaSeq")
        for (i in 0 until count) {
            val sec = startSec + i * 2
            val mm = (sec / 60).toString().padStart(2, '0')
            val ss = (sec % 60).toString().padStart(2, '0')
            appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:$mm:$ss.000Z")
            appendLine("#EXTINF:2.000,live")
            appendLine("${prefix}_${mediaSeq + i}.ts")
        }
    }

    private fun buildFullAdPlaylist(mediaSeq: Long, startSec: Int, count: Int = 4): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$mediaSeq")
        appendLine("#EXT-X-DATERANGE:ID=\"stitched-ad-123\",CLASS=\"twitch-stitched-ad\",START-DATE=\"2026-11-26T12:00:00.000Z\",DURATION=30.0")
        for (i in 0 until count) {
            val sec = startSec + i * 2
            val mm = (sec / 60).toString().padStart(2, '0')
            val ss = (sec % 60).toString().padStart(2, '0')
            appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:$mm:$ss.000Z")
            appendLine("#EXTINF:2.000,Amazon|ad_12345")
            appendLine("https://d2v02pertd.cloudfront.net/ad/seg_${mediaSeq + i}.ts")
        }
    }

    private fun buildPartialAdPlaylist(mediaSeq: Long, startSec: Int): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$mediaSeq")
        for (i in 0..1) {
            val sec = startSec + i * 2
            val mm = (sec / 60).toString().padStart(2, '0')
            val ss = (sec % 60).toString().padStart(2, '0')
            appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:$mm:$ss.000Z")
            appendLine("#EXTINF:2.000,live")
            appendLine("https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_${mediaSeq + i}.ts")
        }
        appendLine("#EXT-X-DATERANGE:ID=\"stitched-ad-999\",CLASS=\"twitch-stitched-ad\",START-DATE=\"2026-11-26T12:00:10.000Z\",DURATION=15.0")
        for (i in 2..3) {
            val sec = startSec + i * 2
            val mm = (sec / 60).toString().padStart(2, '0')
            val ss = (sec % 60).toString().padStart(2, '0')
            appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:$mm:$ss.000Z")
            appendLine("#EXTINF:2.000,Brand|ad_999")
            appendLine("https://d2v02pertd.cloudfront.net/ad/seg_${mediaSeq + i}.ts")
        }
    }

    private fun extractMediaSequence(playlist: String): Long =
        Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlist)?.groupValues?.get(1)?.toLong() ?: -1L

    private fun extractDiscontinuitySequence(playlist: String): Long =
        Regex("""#EXT-X-DISCONTINUITY-SEQUENCE:(\d+)""").find(playlist)?.groupValues?.get(1)?.toLong() ?: 0L

    @Test
    fun cleanPlaylistBeforeSplicePassesThroughUntouched() {
        val window = TvShadowAdblock.ContinuityWindow()
        val raw = buildCleanPlaylist(mediaSeq = 100L, startSec = 0) +
            "#EXT-X-TWITCH-PREFETCH:https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_105.ts\n"
        val parsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, raw)

        assertEquals(TvShadowAdblock.PlaylistVerdict.CLEAN, parsed.verdict)

        val res = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = parsed,
            primaryRawText = raw,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )

        assertEquals("primary", res.sourceUsed)
        assertEquals(0, res.adSegmentsSuppressed)
        assertEquals(raw, res.outputPlaylist)
        assertTrue(window.hasSeenContent)
    }

    @Test
    fun prerollFullAdSplicesShadowCleanlyWithoutPrefetch() {
        val window = TvShadowAdblock.ContinuityWindow()
        val adRaw = buildFullAdPlaylist(mediaSeq = 200L, startSec = 0)
        val adParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, adRaw)
        assertEquals(TvShadowAdblock.PlaylistVerdict.FULL_AD, adParsed.verdict)

        val shadowRaw = buildCleanPlaylist(
            mediaSeq = 500L,
            startSec = 0,
            prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
        ) + "#EXT-X-TWITCH-PREFETCH:https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow_505.ts\n"
        val shadowParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadowRaw)
        assertEquals(TvShadowAdblock.PlaylistVerdict.CLEAN, shadowParsed.verdict)

        val res = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = adParsed,
            primaryRawText = adRaw,
            window = window,
            shadowPlaylist = shadowParsed,
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
        )

        assertEquals("shadow", res.sourceUsed)
        assertEquals(4, res.adSegmentsSuppressed)
        assertTrue(res.outputPlaylist.contains("shadow_500.ts"))
        assertFalse(res.outputPlaylist.contains("cloudfront.net/ad/"))
        assertFalse(res.outputPlaylist.contains("stitched-ad"))
        assertFalse("Synthesized playlist must never emit #EXT-X-TWITCH-PREFETCH", res.outputPlaylist.contains("#EXT-X-TWITCH-PREFETCH:"))
    }

    @Test
    fun midrollPartialAdSplicesShadowAndReturnsAfterCleanStreak() {
        val window = TvShadowAdblock.ContinuityWindow()

        // Step 1: initial clean primary (passed through untouched, snapshot recorded)
        val clean1 = buildCleanPlaylist(mediaSeq = 100L, startSec = 0, count = 4)
        val r1 = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, clean1),
            primaryRawText = clean1,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )
        assertEquals("primary", r1.sourceUsed)
        val seq1 = extractMediaSequence(r1.outputPlaylist)

        // Step 2: mid-roll partial ad while shadow is fresh -> seeds from snapshot and splices shadow
        val partial = buildPartialAdPlaylist(mediaSeq = 104L, startSec = 8)
        val shadow1 = buildCleanPlaylist(
            mediaSeq = 800L,
            startSec = 10,
            count = 5,
            prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
        )
        val r2 = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, partial),
            primaryRawText = partial,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadow1),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
        )
        assertEquals("shadow", r2.sourceUsed)
        assertTrue(r2.adSegmentsSuppressed > 0)
        assertTrue(r2.outputPlaylist.contains("#EXT-X-DISCONTINUITY"))
        assertFalse(r2.outputPlaylist.contains("cloudfront.net/ad/"))
        assertTrue(extractMediaSequence(r2.outputPlaylist) >= seq1)

        // Step 3: 3 consecutive clean primary playlists -> returns smoothly to primary
        var lastResult = r2
        for (step in 1..3) {
            val startSec = 20 + step * 6
            val cleanNext = buildCleanPlaylist(mediaSeq = 110L + step * 3, startSec = startSec, count = 4)
            val shadowNext = buildCleanPlaylist(
                mediaSeq = 810L + step * 3,
                startSec = startSec,
                count = 4,
                prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
            )
            lastResult = TvShadowAdblock.processMediaPlaylistDecision(
                variantUrl = primaryVariantUrl,
                primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, cleanNext),
                primaryRawText = cleanNext,
                window = window,
                shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadowNext),
                shadowVariantUrl = shadowVariantUrl,
                shadowFresh = true,
            )
        }
        assertEquals("primary", lastResult.sourceUsed)
    }

    @Test
    fun missingShadowHoldsWindowDuringMidrollAndPassthroughOnColdPreroll() {
        // Cold preroll without shadow -> passthrough so VAFT fallback catches it
        val coldWindow = TvShadowAdblock.ContinuityWindow()
        val adRaw = buildFullAdPlaylist(mediaSeq = 10L, startSec = 0)
        val coldRes = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, adRaw),
            primaryRawText = adRaw,
            window = coldWindow,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )
        assertEquals("passthrough", coldRes.sourceUsed)

        // Warm window (has clean snapshot) + mid-roll ad without fresh shadow -> seeds from snapshot & holds clean window
        val warmWindow = TvShadowAdblock.ContinuityWindow()
        val clean = buildCleanPlaylist(mediaSeq = 10L, startSec = 0, count = 4)
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, clean),
            primaryRawText = clean,
            window = warmWindow,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )
        val holdRes = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, adRaw),
            primaryRawText = adRaw,
            window = warmWindow,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )
        assertEquals("hold", holdRes.sourceUsed)
        assertFalse(holdRes.outputPlaylist.contains("cloudfront.net/ad/"))
    }

    @Test
    fun discontinuitySequenceIncrementsWhenDiscontinuitySegmentEvicted() {
        val window = TvShadowAdblock.ContinuityWindow()

        // Seed 4 primary segments
        val clean1 = buildCleanPlaylist(mediaSeq = 1L, startSec = 0, count = 4)
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, clean1),
            primaryRawText = clean1,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )

        // Splice to shadow (introduces #EXT-X-DISCONTINUITY on segment #5)
        val ad = buildFullAdPlaylist(mediaSeq = 5L, startSec = 8)
        val shadow = buildCleanPlaylist(
            mediaSeq = 50L,
            startSec = 8,
            count = 3,
            prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
        )
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, ad),
            primaryRawText = ad,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadow),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
        )

        // Push enough subsequent shadow batches to evict past the spliced segment (> MAX_WINDOW_SIZE)
        var latestOutput = ""
        for (batch in 1..15) {
            val startSec = 16 + batch * 6
            val nextShadow = buildCleanPlaylist(
                mediaSeq = 60L + batch * 3,
                startSec = startSec,
                count = 3,
                prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
            )
            latestOutput = TvShadowAdblock.processMediaPlaylistDecision(
                variantUrl = primaryVariantUrl,
                primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, ad),
                primaryRawText = ad,
                window = window,
                shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, nextShadow),
                shadowVariantUrl = shadowVariantUrl,
                shadowFresh = true,
            ).outputPlaylist
        }

        assertTrue(
            "Expected EXT-X-DISCONTINUITY-SEQUENCE >= 1 after evicting spliced segment, got:\n$latestOutput",
            extractDiscontinuitySequence(latestOutput) >= 1L,
        )
    }

    @Test
    fun qualitySwitchAfterSplicePreservesMonotonicMediaSequence() {
        val window = TvShadowAdblock.ContinuityWindow()
        val p1 = buildCleanPlaylist(mediaSeq = 500L, startSec = 0, count = 4)
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, p1),
            primaryRawText = p1,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )
        // Trigger a splice so ContinuityWindow is active
        val ad = buildFullAdPlaylist(mediaSeq = 504L, startSec = 8)
        val shadow = buildCleanPlaylist(
            mediaSeq = 900L,
            startSec = 8,
            count = 3,
            prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
        )
        val rSplice = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, ad),
            primaryRawText = ad,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadow),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
        )
        val seqBefore = extractMediaSequence(rSplice.outputPlaylist)

        // Switch variant URL to 1080p with lower upstream sequence number
        val p2 = buildCleanPlaylist(mediaSeq = 490L, startSec = 16, count = 4)
        val r2 = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, p2),
            primaryRawText = p2,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )
        val seqAfter = extractMediaSequence(r2.outputPlaylist)
        assertTrue("MEDIA-SEQUENCE must stay monotonic across quality switch ($seqBefore -> $seqAfter)", seqAfter >= seqBefore)
    }

    @Test
    fun vodUrlsAndVodPlaylistsAreStrictlyExcluded() {
        assertTrue(TvShadowAdblock.isVodOrExcludedUrl("https://d1m7jfoe9zdc1j.cloudfront.net/abc/chunked/index-dvr.m3u8"))
        assertTrue(TvShadowAdblock.isVodOrExcludedUrl("https://vod-secure.twitch.tv/123/chunked/index-muted-XYZ.m3u8"))
        assertTrue(TvShadowAdblock.isVodOrExcludedUrl("https://usher.ttvnw.net/vod/123456.m3u8"))
        assertFalse(TvShadowAdblock.isVodOrExcludedUrl("https://usher.ttvnw.net/api/channel/hls/shroud.m3u8"))

        val vodText = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:10.0,\n0.ts\n#EXT-X-ENDLIST\n"
        assertTrue(TvShadowAdblock.isVodPlaylistText(vodText))
        assertFalse(TvShadowAdblock.isVodPlaylistText(buildCleanPlaylist(1L, 0)))
    }

    @Test
    fun masterPlaylistStripsPortraitAndPreserves1440pHevc() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="portrait_1080p60",NAME="portrait"
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=2560x1440,CODECS="hev1.1.6.L150.B0,mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/1440p60.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/1080p60.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=720x1280,IVS-GROUPS="portrait"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/portrait.m3u8
        """.trimIndent()

        val (filtered, variants) = TvShadowAdblock.filterMasterAndExtractVariants(
            "https://usher.ttvnw.net/api/channel/hls/test.m3u8",
            master,
        )

        assertTrue(filtered.contains("1440p60.m3u8"))
        assertTrue(filtered.contains("1080p60.m3u8"))
        assertFalse(filtered.contains("portrait.m3u8"))
        assertEquals(2, variants.size)
        assertEquals(1440, variants[0].height)
        assertEquals(1080, variants[1].height)
    }

    @Test
    fun daterangeMidrollClassifiesLiveTitledSegmentsAsAd() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:300
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:00.000Z
            #EXTINF:2.000,live
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_300.ts
            #EXT-X-DATERANGE:ID="stitched-ad-555",CLASS="twitch-stitched-ad",START-DATE="2026-11-26T12:00:02.000Z",DURATION=15.0
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:02.000Z
            #EXTINF:2.000,live
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/stitched_301.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:04.000Z
            #EXTINF:2.000,live
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/stitched_302.ts
        """.trimIndent()

        val parsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, playlist)
        assertEquals(TvShadowAdblock.PlaylistVerdict.PARTIAL_AD, parsed.verdict)
        assertEquals(TvShadowAdblock.SegmentClass.CONTENT, parsed.segments[0].classification)
        assertEquals(TvShadowAdblock.SegmentClass.AD, parsed.segments[1].classification)
        assertEquals(TvShadowAdblock.SegmentClass.AD, parsed.segments[2].classification)
    }
}
