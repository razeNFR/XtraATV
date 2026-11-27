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

    @Test
    fun bottomDaterangeWithClockDriftAndBareAmazonTitleAreDetectedAsAd() {
        // DATERANGE placed at the bottom of the playlist + 50ms drift between segment PDT and START-DATE
        val bottomDateRangePlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:400
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:00.000Z
            #EXTINF:2.000,live
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_400.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:01.950Z
            #EXTINF:2.000,live
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/stitched_401.ts
            #EXT-X-DATERANGE:ID="stitched-ad-777",CLASS="twitch-stitched-ad",START-DATE="2026-11-26T12:00:02.000Z",DURATION=15.0
        """.trimIndent()

        val parsedBottom = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, bottomDateRangePlaylist)
        assertEquals(TvShadowAdblock.PlaylistVerdict.PARTIAL_AD, parsedBottom.verdict)
        assertEquals(TvShadowAdblock.SegmentClass.CONTENT, parsedBottom.segments[0].classification)
        assertEquals(TvShadowAdblock.SegmentClass.AD, parsedBottom.segments[1].classification)

        // Bare Amazon / Adform / DCM or special-char suffix titles (matches ExoPlayerService.isAdSegment)
        val bareBrandPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:410
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:00.000Z
            #EXTINF:2.000,Amazon
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/s_410.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:02.000Z
            #EXTINF:2.000,Adform|a:b/c=1
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/s_411.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:04.000Z
            #EXTINF:2.000,DCM
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/s_412.ts
        """.trimIndent()

        val parsedBrands = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, bareBrandPlaylist)
        assertEquals(TvShadowAdblock.PlaylistVerdict.FULL_AD, parsedBrands.verdict)
    }

    @Test
    fun adDaterangeWithStreamSourceIsCompletelyDropped() {
        val leakedLine =
            """#EXT-X-DATERANGE:ID="stitched-ad-999",CLASS="twitch-stitched-ad",START-DATE="2026-11-26T12:00:00.000Z",DURATION=30.0,X-TV-TWITCH-STREAM-SOURCE="ad",X-TV-TWITCH-AD-POD-POSITION="1""""
        assertEquals(null, TvShadowAdblock.cleanHeaderLine(leakedLine))
    }

    @Test
    fun prolongedHoldWithoutShadowFallsBackToPassthroughBeforeStarvation() {
        val window = TvShadowAdblock.ContinuityWindow()
        val clean = buildCleanPlaylist(mediaSeq = 10L, startSec = 0, count = 4)
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, clean),
            primaryRawText = clean,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )
        val adRaw = buildFullAdPlaylist(mediaSeq = 14L, startSec = 8)
        val adParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, adRaw)

        // First 3 passes hold cleanly while warming
        repeat(3) {
            val res = TvShadowAdblock.processMediaPlaylistDecision(
                variantUrl = primaryVariantUrl,
                primaryParsed = adParsed,
                primaryRawText = adRaw,
                window = window,
                shadowPlaylist = null,
                shadowVariantUrl = null,
                shadowFresh = false,
            )
            assertEquals("hold", res.sourceUsed)
        }

        // 4th pass exceeds hold cap and hands off cleanly to Classic Swap ("passthrough")
        val handoff = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = adParsed,
            primaryRawText = adRaw,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )
        assertEquals("passthrough", handoff.sourceUsed)
    }

    @Test
    fun incompatibleContainerMapBlocksUnsafeSplice() {
        val window = TvShadowAdblock.ContinuityWindow()
        val fmp4Primary = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-MAP:URI="https://video-edge.cdg02.abs.hls.ttvnw.net/v1/init.mp4"
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:00.000Z
            #EXTINF:2.000,live
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_100.m4s
        """.trimIndent()
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, fmp4Primary),
            primaryRawText = fmp4Primary,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )

        val adRaw = buildFullAdPlaylist(mediaSeq = 101L, startSec = 2)
        val tsShadow = buildCleanPlaylist(
            mediaSeq = 500L,
            startSec = 2,
            count = 3,
            prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
        )
        val res = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, adRaw),
            primaryRawText = adRaw,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, tsShadow),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
        )
        assertEquals("hold", res.sourceUsed)
    }

    @Test
    fun llHlsFmp4DeltaPlaylistDetectsMidrollWithTimingHintAndStripsServerSkip() {
        val deltaPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:1000
            #EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=1.500,CAN-SKIP-UNTIL=30.0
            #EXT-X-PART-INF:PART-TARGET=0.500
            #EXT-X-SKIP:SKIPPED-SEGMENTS=25
            #EXT-X-DATERANGE:ID="stitched-ad-1795777627-6241000000",CLASS="twitch-stitched-ad",START-DATE="2026-11-27T11:07:07.000Z",DURATION=30.0
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/seg_1025.m4s
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/seg_1026.m4s
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/seg_1027.m4s
        """.trimIndent()

        // 2026-11-27T11:07:05.000Z = 1795777625000L; 11:07:07.000Z = 1795777627000L; 11:07:09.000Z = 1795777629000L
        val hint = TvShadowAdblock.PlaylistTimingHint(
            segmentPdtMs = listOf(1795777625000L, 1795777627000L, 1795777629000L),
            fallbackFirstSegmentPdtMs = 1795777625000L,
            extraAdRangesMs = listOf(1795777627000L to 1795777657000L),
            fallbackMapLine = """#EXT-X-MAP:URI="https://video-edge.cdg02.abs.hls.ttvnw.net/v1/init.mp4"""",
        )

        val parsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, deltaPlaylist, hint)
        assertEquals(TvShadowAdblock.PlaylistVerdict.PARTIAL_AD, parsed.verdict)
        assertTrue(parsed.hasActiveAdAtLiveEdge)
        assertEquals(TvShadowAdblock.SegmentClass.UNKNOWN, parsed.segments[0].classification)
        assertEquals(TvShadowAdblock.SegmentClass.AD, parsed.segments[1].classification)
        assertEquals(TvShadowAdblock.SegmentClass.AD, parsed.segments[2].classification)
        assertTrue(parsed.activeMapLine?.contains("init.mp4") == true)
        assertFalse(parsed.headerLines.any { it.contains("#EXT-X-SKIP") || it.contains("#EXT-X-SERVER-CONTROL") || it.contains("#EXT-X-PART-INF") })

        val noSkip = TvShadowAdblock.disableDeltaSkipping(deltaPlaylist)
        assertFalse(noSkip.contains("CAN-SKIP-UNTIL"))
        assertTrue(noSkip.contains("CAN-BLOCK-RELOAD=YES"))
        assertTrue(noSkip.contains("PART-HOLD-BACK=1.500"))
    }

    @Test
    fun historicalAdsInLongSlidingWindowDoNotReloopAfterBreakOrBlockReturn() {
        fun buildSlidingWindowWithHistoricalAds(mediaSeq: Long, tailCleanCount: Int): String = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:6")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$mediaSeq")
            appendLine("""#EXT-X-DATERANGE:ID="stitched-ad-500",CLASS="twitch-stitched-ad",START-DATE="2026-11-26T12:00:00.000Z",DURATION=58.0""")
            for (i in 0 until 29) {
                val sec = i * 2
                val mm = (sec / 60).toString().padStart(2, '0')
                val ss = (sec % 60).toString().padStart(2, '0')
                appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:$mm:$ss.000Z")
                appendLine("#EXTINF:2.000,")
                appendLine("https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/ad_${mediaSeq + i}.m4s")
            }
            for (j in 0 until tailCleanCount) {
                val sec = 58 + j * 2
                val mm = (sec / 60).toString().padStart(2, '0')
                val ss = (sec % 60).toString().padStart(2, '0')
                appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:$mm:$ss.000Z")
                appendLine("#EXTINF:2.000,")
                appendLine("https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_${mediaSeq + 29 + j}.m4s")
            }
        }

        val postAdRaw = buildSlidingWindowWithHistoricalAds(mediaSeq = 1000L, tailCleanCount = 2)
        val postAdParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, postAdRaw)
        assertEquals(TvShadowAdblock.PlaylistVerdict.PARTIAL_AD, postAdParsed.verdict)
        assertFalse("Live edge is already clean live content", postAdParsed.hasActiveAdAtLiveEdge)

        // Scenario A: Right after Classic Swap finished (window reset, not initial playlist) -> must NOT loop into shadow
        val afterClassicSwapWindow = TvShadowAdblock.ContinuityWindow()
        val shadowClean = buildCleanPlaylist(
            mediaSeq = 500L,
            startSec = 50,
            count = 4,
            prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
        )
        val shadowParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadowClean)
        val resAfterClassic = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = postAdParsed,
            primaryRawText = postAdRaw,
            window = afterClassicSwapWindow,
            shadowPlaylist = shadowParsed,
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            isInitialPlaylist = false,
        )
        assertEquals("primary", resAfterClassic.sourceUsed)
        assertEquals(0, resAfterClassic.adSegmentsSuppressed)

        // Scenario B: Returning from active Swap+ shadow while 29 old ads remain in sliding window -> returns to primary after 3 polls
        val activeWindow = TvShadowAdblock.ContinuityWindow()
        val fullAd = buildFullAdPlaylist(mediaSeq = 990L, startSec = 0, count = 4)
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, fullAd),
            primaryRawText = fullAd,
            window = activeWindow,
            shadowPlaylist = shadowParsed,
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            isInitialPlaylist = true,
        )
        var lastRes: TvShadowAdblock.DecisionResult? = null
        for (step in 1..3) {
            val pollRaw = buildSlidingWindowWithHistoricalAds(mediaSeq = 1000L + step, tailCleanCount = 1 + step)
            lastRes = TvShadowAdblock.processMediaPlaylistDecision(
                variantUrl = primaryVariantUrl,
                primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, pollRaw),
                primaryRawText = pollRaw,
                window = activeWindow,
                shadowPlaylist = shadowParsed,
                shadowVariantUrl = shadowVariantUrl,
                shadowFresh = true,
                isInitialPlaylist = false,
            )
        }
        assertEquals("primary", lastRes!!.sourceUsed)
        assertFalse("Old historical ad segments must be stripped when returning to primary", lastRes.outputPlaylist.contains("/ad_"))
        assertTrue(lastRes.outputPlaylist.contains("/live_"))
    }

    @Test
    fun monotonicContinuityFloorPreventsSequenceRegressionOn30To14ShadowSplice() {
        val window = TvShadowAdblock.ContinuityWindow()
        val floor = TvShadowAdblock.ContinuityFloor(
            minMediaSequence = 1000L,
            minEndMediaSequence = 1030L,
            minDiscontinuitySequence = 1L,
        )
        val fullAd = buildFullAdPlaylist(mediaSeq = 1000L, startSec = 0, count = 30)
        val shadow = buildCleanPlaylist(
            mediaSeq = 500L,
            startSec = 60,
            count = 14,
            prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
        )
        val res = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, fullAd),
            primaryRawText = fullAd,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadow),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            continuityFloor = floor,
            isInitialPlaylist = false,
        )
        val emittedSeq = extractMediaSequence(res.outputPlaylist)
        val emittedSegments = res.outputPlaylist.lines().count { it.startsWith("https://") }
        val emittedDisc = extractDiscontinuitySequence(res.outputPlaylist)

        assertEquals("shadow", res.sourceUsed)
        assertTrue("MEDIA-SEQUENCE ($emittedSeq) must be >= previous end (1030)", emittedSeq >= 1030L)
        assertTrue("End sequence (${emittedSeq + emittedSegments}) must never shrink below 1030", emittedSeq + emittedSegments >= 1030L)
        assertTrue("DISCONTINUITY-SEQUENCE ($emittedDisc) must advance past previous (1)", emittedDisc >= 2L)
    }

    @Test
    fun twitchPrefetchEnteringAdDaterangeTriggersShadowBeforeClassicSwap() {
        val window = TvShadowAdblock.ContinuityWindow()

        // Step 1: clean live playback so snapshot is warm
        val cleanBefore = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:200
            #EXT-X-PROGRAM-DATE-TIME:2026-11-27T12:11:59.000Z
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_200.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-27T12:12:01.000Z
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_201.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-27T12:12:03.000Z
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_202.m4s
        """.trimIndent()
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, cleanBefore),
            primaryRawText = cleanBefore,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )

        // Step 2: midroll DATERANGE starts at 12:12:07; regular #EXTINF ends at 12:12:05 (clean),
        // while #EXT-X-TWITCH-PREFETCH segments at 12:12:07 and 12:12:09 are already inside the ad!
        val prefetchAdPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:201
            #EXT-X-DATERANGE:ID="stitched-ad-1795781527-15241000000",CLASS="twitch-stitched-ad",START-DATE="2026-11-27T12:12:07.000Z",DURATION=60.0
            #EXT-X-PROGRAM-DATE-TIME:2026-11-27T12:12:01.000Z
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_201.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-27T12:12:03.000Z
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_202.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-27T12:12:05.000Z
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_203.m4s
            #EXT-X-TWITCH-PREFETCH:https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/pref_ad_204.m4s
            #EXT-X-TWITCH-PREFETCH:https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/pref_ad_205.m4s
        """.trimIndent()

        val parsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, prefetchAdPlaylist)
        assertTrue("Prefetch segments inside stitched-ad must trigger hasPrefetchAd", parsed.hasPrefetchAd)
        assertEquals(2, parsed.prefetchAdCount)
        assertTrue("Live edge must be flagged as active ad", parsed.hasActiveAdAtLiveEdge)

        val shadowRaw = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:900
            #EXT-X-PROGRAM-DATE-TIME:2026-11-27T12:12:07.000Z
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow_900.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-27T12:12:09.000Z
            #EXTINF:2.000,
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow_901.m4s
        """.trimIndent()
        val shadowParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadowRaw)

        val res = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = parsed,
            primaryRawText = prefetchAdPlaylist,
            window = window,
            shadowPlaylist = shadowParsed,
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            isInitialPlaylist = false,
        )

        assertEquals("shadow", res.sourceUsed)
        assertEquals(2, res.adSegmentsSuppressed)
        assertTrue(res.outputPlaylist.contains("live_203.m4s"))
        assertTrue(res.outputPlaylist.contains("#EXT-X-DISCONTINUITY"))
        assertTrue(res.outputPlaylist.contains("shadow_900.m4s"))
        assertFalse(res.outputPlaylist.contains("pref_ad_"))
        assertFalse(res.outputPlaylist.contains("#EXT-X-TWITCH-PREFETCH:"))
        assertFalse(res.outputPlaylist.contains("stitched-ad-"))
    }
}
