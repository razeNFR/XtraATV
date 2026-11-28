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

    @Test
    fun midrollSpliceNeverRollsBackWhenShadowContainsEarlierOrShiftedSegments() {
        val window = TvShadowAdblock.ContinuityWindow()
        val mapPrimary = """#EXT-X-MAP:URI="https://video-edge.cdg02.abs.hls.ttvnw.net/v1/primary_init.mp4""""
        val mapShadow = """#EXT-X-MAP:URI="https://video-edge.cdg02.abs.hls.ttvnw.net/v1/shadow_init.mp4""""

        // Primary plays cleanly 12:00:00 .. 12:00:10 (last segment 12:00:08..12:00:10 -> lastServedEndMs = 12:00:10.000)
        val cleanPrimary = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:6")
            appendLine("#EXT-X-MEDIA-SEQUENCE:100")
            appendLine(mapPrimary)
            for (i in 0..4) {
                val sec = (i * 2).toString().padStart(2, '0')
                appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:$sec.000Z")
                appendLine("#EXTINF:2.000,live")
                appendLine("https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/live_${100 + i}.m4s")
            }
        }
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, cleanPrimary),
            primaryRawText = cleanPrimary,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )

        // Midroll starts at 12:00:10. Shadow has older segments shifted by -1.1s (12:00:03.9, 12:00:05.9, 12:00:07.9)
        // AND aligned segments starting at 12:00:09.900 (delta = -100ms, within 45% and |Δ| < 250ms), 12:00:11.900, 12:00:13.900, 12:00:15.900
        val midrollAd = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:6")
            appendLine("#EXT-X-MEDIA-SEQUENCE:105")
            appendLine(mapPrimary)
            appendLine("""#EXT-X-DATERANGE:ID="stitched-ad-1",CLASS="twitch-stitched-ad",START-DATE="2026-11-26T12:00:10.000Z",DURATION=30.0""")
            for (i in 0..3) {
                val sec = (10 + i * 2).toString().padStart(2, '0')
                appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:$sec.000Z")
                appendLine("#EXTINF:2.000,Amazon|ad_1")
                appendLine("https://d2v02pertd.cloudfront.net/ad/seg_${105 + i}.m4s")
            }
        }
        val shadowRaw = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:6")
            appendLine("#EXT-X-MEDIA-SEQUENCE:500")
            appendLine(mapShadow)
            val pdts = listOf("03.900", "05.900", "07.900", "09.900", "11.900", "13.900", "15.900")
            pdts.forEachIndexed { idx, s ->
                appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:$s" + "Z")
                appendLine("#EXTINF:2.000,live")
                appendLine("https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow_${500 + idx}.m4s")
            }
        }
        val avcMeta = TvShadowAdblock.MasterVariant(
            url = primaryVariantUrl,
            width = 1920,
            height = 1080,
            bandwidth = 6_000_000L,
            codecs = "avc1.64002A,mp4a.40.2",
            frameRate = 60.0,
        )
        val shadowMeta = avcMeta.copy(url = shadowVariantUrl)

        val res = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, midrollAd),
            primaryRawText = midrollAd,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadowRaw),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            primaryVariantMeta = avcMeta,
            shadowVariantMeta = shadowMeta,
        )

        assertEquals("shadow", res.sourceUsed)
        // Must NEVER include overlapping segments 500, 501, 502 (03.9s, 05.9s, 07.9s)
        assertFalse(res.outputPlaylist.contains("shadow_500.m4s"))
        assertFalse(res.outputPlaylist.contains("shadow_501.m4s"))
        assertFalse(res.outputPlaylist.contains("shadow_502.m4s"))
        // Must start at shadow_503 (09.900s, delta = -100ms) and cap at 3 segments (503, 504, 505; NOT 506)
        assertTrue(res.outputPlaylist.contains("shadow_503.m4s"))
        assertTrue(res.outputPlaylist.contains("shadow_504.m4s"))
        assertTrue(res.outputPlaylist.contains("shadow_505.m4s"))
        assertFalse("Must ingest at most 3 segments per refresh", res.outputPlaylist.contains("shadow_506.m4s"))
        assertTrue(res.outputPlaylist.contains("#EXT-X-DISCONTINUITY\n$mapShadow"))
        assertEquals(0L, extractDiscontinuitySequence(res.outputPlaylist))

        val splice = res.spliceEvent
        assertTrue("SpliceEvent must be recorded", splice != null)
        assertEquals(-100L, splice!!.deltaMs)
        assertTrue("Splice |deltaMs| must be < 250ms", kotlin.math.abs(splice.deltaMs!!) < 250L)
        assertTrue(splice.discontinuityEmitted)
        assertEquals("avc1", splice.primaryCodec)
        assertEquals("avc1", splice.shadowCodec)
        assertEquals("fmp4", splice.primaryContainer)
        assertEquals("fmp4", splice.shadowContainer)
    }

    @Test
    fun shadowAheadAndBehindAreHandledWithoutBulkDumpOrRollback() {
        val window = TvShadowAdblock.ContinuityWindow()
        val clean = buildCleanPlaylist(mediaSeq = 100L, startSec = 10, count = 5) // 10..20s -> lastServedEndMs = 20s
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, clean),
            primaryRawText = clean,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )

        val ad = buildFullAdPlaylist(mediaSeq = 105L, startSec = 20, count = 4)

        // Case 1: Shadow is behind (latest segment starts at 18s < 20s - 900ms) -> zero segments appended, no rollback!
        val behindShadow = buildCleanPlaylist(
            mediaSeq = 400L,
            startSec = 10,
            count = 5, // 10, 12, 14, 16, 18
            prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
        )
        val resBehind = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, ad),
            primaryRawText = ad,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, behindShadow),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
        )
        assertFalse("Behind shadow must never roll back into already-played window", resBehind.outputPlaylist.contains("shadow_"))

        // Case 2: Shadow is way ahead with 16 segments starting at 26s -> strictly capped at 3 segments (no bulk 14 dump!)
        val aheadShadow = buildCleanPlaylist(
            mediaSeq = 410L,
            startSec = 26,
            count = 16,
            prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow",
        )
        val resAhead = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, ad),
            primaryRawText = ad,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, aheadShadow),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
        )
        val shadowSegCount = resAhead.outputPlaylist.lines().count { it.contains("shadow_") }
        assertEquals("Ahead shadow must append at most 3 segments per refresh", 3, shadowSegCount)
        assertTrue(resAhead.outputPlaylist.contains("shadow_410.ts"))
        assertTrue(resAhead.outputPlaylist.contains("shadow_412.ts"))
        assertFalse(resAhead.outputPlaylist.contains("shadow_413.ts"))
    }

    @Test
    fun hevcChannelVariantSelectionRequiresMatchingCodecAndBlocksMixedCodecSplice() {
        val shadowMasterAvcOnly = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,FRAME-RATE=60.000,CODECS="avc1.64002A,mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/shadow_1080p60_avc.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,FRAME-RATE=60.000,CODECS="avc1.4D401F,mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/shadow_720p60_avc.m3u8
        """.trimIndent()
        val shadowMasterWithHevc = shadowMasterAvcOnly + "\n" + """
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=2560x1440,FRAME-RATE=60.000,CODECS="hev1.1.6.L150.B0,mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/shadow_1440p60_hevc.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6500000,RESOLUTION=2560x1440,FRAME-RATE=30.000,CODECS="hev1.1.6.L150.B0,mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/shadow_1440p30_hevc.m3u8
        """.trimIndent()

        val primaryHevc = TvShadowAdblock.MasterVariant(
            url = primaryVariantUrl,
            width = 2560,
            height = 1440,
            bandwidth = 8_500_000L,
            codecs = "hev1.1.6.L150.B0,mp4a.40.2",
            frameRate = 60.0,
        )
        val (_, avcOnlyVariants) = TvShadowAdblock.filterMasterAndExtractVariants("https://usher.ttvnw.net/master.m3u8", shadowMasterAvcOnly)
        val (_, hevcVariants) = TvShadowAdblock.filterMasterAndExtractVariants("https://usher.ttvnw.net/master.m3u8", shadowMasterWithHevc)

        // When shadow has only avc1, pickShadowVariant must return null for HEVC primary
        assertEquals(null, TvShadowAdblock.pickShadowVariant(avcOnlyVariants, primaryHevc))

        // When shadow has HEVC 1440p30 and 1440p60, pickShadowVariant must select HEVC 1440p60
        val picked = TvShadowAdblock.pickShadowVariant(hevcVariants, primaryHevc)
        assertTrue(picked != null)
        assertEquals("hevc", picked!!.videoCodecFamily)
        assertEquals(1440, picked.height)
        assertEquals(60.0, picked.frameRate, 0.1)

        // Decision engine must also refuse to splice an avc1 shadow into an hevc primary
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
            primaryVariantMeta = primaryHevc,
        )
        val ad = buildFullAdPlaylist(mediaSeq = 14L, startSec = 8, count = 4)
        val avcShadow = buildCleanPlaylist(mediaSeq = 50L, startSec = 8, count = 4, prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow")
        val blocked = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, ad),
            primaryRawText = ad,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, avcShadow),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            primaryVariantMeta = primaryHevc,
            shadowVariantMeta = avcOnlyVariants.first(),
        )
        assertEquals("hold", blocked.sourceUsed)
        assertFalse(blocked.outputPlaylist.contains("shadow_"))
    }

    @Test
    fun discontinuitySequenceStaysStableAcrossRefreshesUntilDiscontinuityIsEvicted() {
        val window = TvShadowAdblock.ContinuityWindow()
        val clean = buildCleanPlaylist(mediaSeq = 100L, startSec = 0, count = 4)
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, clean),
            primaryRawText = clean,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )

        val ad = buildFullAdPlaylist(mediaSeq = 104L, startSec = 8, count = 4)
        val shadow1 = buildCleanPlaylist(mediaSeq = 500L, startSec = 8, count = 3, prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow")
        val r1 = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, ad),
            primaryRawText = ad,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadow1),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            continuityFloor = TvShadowAdblock.ContinuityFloor(minMediaSequence = 100L, minEndMediaSequence = 104L, minDiscontinuitySequence = 0L, minDiscontinuitySequenceForReset = 0L),
        )
        assertEquals(0L, extractDiscontinuitySequence(r1.outputPlaylist))
        assertTrue(r1.outputPlaylist.contains("#EXT-X-DISCONTINUITY-SEQUENCE:0"))

        // Subsequent refresh while #EXT-X-DISCONTINUITY is still inside the window:
        // prev.discontinuitySequence is STILL 0 (first segment's sequence), even though tail has relativeDiscontinuitySequence=1!
        val shadow2 = buildCleanPlaylist(mediaSeq = 503L, startSec = 14, count = 3, prefix = "https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow")
        val r2 = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariantUrl,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariantUrl, ad),
            primaryRawText = ad,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadow2),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            continuityFloor = TvShadowAdblock.ContinuityFloor(minMediaSequence = 100L, minEndMediaSequence = 107L, minDiscontinuitySequence = 0L, minDiscontinuitySequenceForReset = 1L),
        )
        assertEquals("DISCONTINUITY-SEQUENCE must NOT bump while discontinuity is still in window", 0L, extractDiscontinuitySequence(r2.outputPlaylist))
        assertEquals(100L, extractMediaSequence(r2.outputPlaylist))
    }

    @Test
    fun filterMasterToCodecFamilyStripsHevcWhileKeepingAvcAndAudio() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1440p60"
            #EXT-X-STREAM-INF:BANDWIDTH=8500000,RESOLUTION=2560x1440,FRAME-RATE=60.000,CODECS="hev1.1.6.L150.B0,mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/1440p60_hevc.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,FRAME-RATE=60.000,CODECS="avc1.64002A,mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/1080p60_avc.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=160000,CODECS="mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/audio_only.m3u8
        """.trimIndent()

        val avcOnly = TvShadowAdblock.filterMasterToCodecFamily(master, "avc1")
        assertFalse(avcOnly.contains("1440p60_hevc.m3u8"))
        assertTrue(avcOnly.contains("1080p60_avc.m3u8"))
        assertTrue(avcOnly.contains("audio_only.m3u8"))
    }

    @Test
    fun swapPlusRequestsAvc1UpfrontWhileClassicSwapVodAndProxiesKeepUserCodecs() {
        val userCodecs = "av1,h265,h264"

        // Swap+ active (no proxy): always requests avc1 (1080p60 max H.264) on the 1st request
        assertEquals(
            "avc1",
            com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.resolveSupportedCodecs(
                supportedCodecsOverride = null,
                configuredCodecs = userCodecs,
                swapPlusActive = true,
            ),
        )

        // Classic Swap, VOD, Disabled, Stream Proxy, Multi-Stream (swapPlusActive = false): completely untouched
        assertEquals(
            userCodecs,
            com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.resolveSupportedCodecs(
                supportedCodecsOverride = null,
                configuredCodecs = userCodecs,
                swapPlusActive = false,
            ),
        )

        // Explicit fallback override still wins
        assertEquals(
            "h264",
            com.github.andreyasadchy.xtra.ui.player.ExoPlayerService.resolveSupportedCodecs(
                supportedCodecsOverride = "h264",
                configuredCodecs = userCodecs,
                swapPlusActive = true,
            ),
        )
    }

    @Test
    fun enhancedBroadcastingChannelPrerollSplicesImmediatelyIn1080pAvc1WithoutClassicSwapFallback() {
        // Simulates jltomy/mynthos requested directly with supported_codecs=avc1:
        // primary master returns 1080p60 H.264 (fMP4), shadow warms in 1080p60 H.264 (fMP4),
        // and the very first pre-roll playlist (Amazon stitched-ad) splices cleanly with zero passthrough fallback.
        val masterUrl = "https://usher.ttvnw.net/api/channel/hls/jltomy.m3u8?supported_codecs=avc1"
        val primaryMaster = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6500000,RESOLUTION=1920x1080,FRAME-RATE=60.000,CODECS="avc1.64002A,mp4a.40.2"
            $primaryVariant1080Url
            #EXT-X-STREAM-INF:BANDWIDTH=160000,CODECS="mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/audio_only.m3u8
        """.trimIndent()
        val (_, primaryVariants) = TvShadowAdblock.filterMasterAndExtractVariants(masterUrl, primaryMaster)
        val primary1080 = primaryVariants.first { it.height == 1080 }
        assertEquals("avc1", primary1080.videoCodecFamily)

        val shadow1080 = TvShadowAdblock.MasterVariant(
            url = shadowVariantUrl,
            width = 1920,
            height = 1080,
            bandwidth = 6500000L,
            codecs = "avc1.64002A,mp4a.40.2",
            frameRate = 60.0,
        )

        val initMap = "#EXT-X-MAP:URI=\"https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/init.mp4\""
        val prerollAdFmp4 = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:200
            $initMap
            #EXT-X-DATERANGE:ID="stitched-ad-1795865057-15235000000",CLASS="twitch-stitched-ad",START-DATE="2026-11-28T12:24:17.000Z",DURATION=15.235
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T12:24:17.000Z
            #EXTINF:2.000,Amazon|2488883100494
            https://d2v02pertd.cloudfront.net/ad/seg_200.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T12:24:19.000Z
            #EXTINF:2.000,Amazon|2488883100494
            https://d2v02pertd.cloudfront.net/ad/seg_201.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T12:24:21.000Z
            #EXTINF:2.000,Amazon|2488883100494
            https://d2v02pertd.cloudfront.net/ad/seg_202.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T12:24:23.000Z
            #EXTINF:2.000,Amazon|2488883100494
            https://d2v02pertd.cloudfront.net/ad/seg_203.m4s
        """.trimIndent()
        val shadowCleanFmp4 = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:500
            $initMap
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T12:24:17.000Z
            #EXTINF:2.000,live
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow_500.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T12:24:19.000Z
            #EXTINF:2.000,live
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow_501.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T12:24:21.000Z
            #EXTINF:2.000,live
            https://video-edge.cdg02.abs.hls.ttvnw.net/v1/segment/shadow_502.m4s
        """.trimIndent()

        val window = TvShadowAdblock.ContinuityWindow()
        val decision = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, prerollAdFmp4),
            primaryRawText = prerollAdFmp4,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadowCleanFmp4),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            isInitialPlaylist = true,
            primaryVariantMeta = primary1080,
            shadowVariantMeta = shadow1080,
        )

        assertEquals("shadow", decision.sourceUsed)
        assertEquals(4, decision.adSegmentsSuppressed)
        assertTrue(decision.outputPlaylist.contains("shadow_500.m4s"))
        assertFalse(decision.outputPlaylist.contains("Amazon|"))
    }

    @Test
    fun filterMasterStripsLowerBitrateHevcEvenWhenAvcHasHigherBandwidth() {
        val mixedMaster = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6500000,RESOLUTION=1920x1080,FRAME-RATE=60.000,CODECS="avc1.64002A,mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/1080p60_avc.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5800000,RESOLUTION=2560x1440,FRAME-RATE=60.000,CODECS="hvc1.1.6.L150.B0,mp4a.40.2"
            https://video-weaver.cdg02.hls.ttvnw.net/v1/playlist/1440p60_hevc.m3u8
        """.trimIndent()

        val (filtered, variants) = TvShadowAdblock.filterMasterAndExtractVariants(
            "https://usher.ttvnw.net/api/channel/hls/jltomy.m3u8",
            mixedMaster,
        )
        assertTrue(variants.any { it.videoCodecFamily == "hevc" })
        assertTrue(variants.any { it.videoCodecFamily == "avc1" })

        val avcOnly = TvShadowAdblock.filterMasterToCodecFamily(filtered, "avc1")
        assertTrue(avcOnly.contains("1080p60_avc.m3u8"))
        assertFalse(avcOnly.contains("1440p60_hevc.m3u8"))
    }

    @Test
    fun midrollAfterCleanPrefetchDoesNotShiftMediaSequenceOrReplayPrefetchedSegment() {
        val window = TvShadowAdblock.ContinuityWindow()
        val cleanWithPrefetch = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:980
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:31.938Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_980.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:33.938Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_981.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:35.938Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_982.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:37.938Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_983.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:39.938Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_984.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:41.938Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_985.ts
            #EXT-X-TWITCH-PREFETCH:https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_2173819.ts
            #EXT-X-TWITCH-PREFETCH:https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_2173829.ts
        """.trimIndent()

        val cleanRes = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, cleanWithPrefetch),
            primaryRawText = cleanWithPrefetch,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )
        assertEquals("primary", cleanRes.sourceUsed)

        // Xtra's HlsPlaylistParser exposes 6 regular + 2 prefetch = 8 segments (minEndMediaSequence = 988)
        val floorAfterPrefetch = TvShadowAdblock.ContinuityFloor(
            minMediaSequence = 980L,
            minEndMediaSequence = 988L,
            minDiscontinuitySequence = 0L,
            minDiscontinuitySequenceForReset = 0L,
        )

        val midrollAd = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:982
            #EXT-X-DATERANGE:ID="stitched-ad-777",CLASS="twitch-stitched-ad",START-DATE="2026-11-28T11:51:46.000Z",DURATION=30.0
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:46.000Z
            #EXTINF:2.000,Amazon|ad_777
            https://d2v02pertd.cloudfront.net/ad/ad_986.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:48.000Z
            #EXTINF:2.000,Amazon|ad_777
            https://d2v02pertd.cloudfront.net/ad/ad_987.ts
        """.trimIndent()

        // Shadow shares exact same Twitch segment URLs (including already-prefetched seg_2173819 & seg_2173829)
        val shadowClean = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:985
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:43.938Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_2173819.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:45.938Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_2173829.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:51:47.938Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_2173841.ts
        """.trimIndent()

        val spliceRes = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, midrollAd),
            primaryRawText = midrollAd,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadowClean),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            continuityFloor = floorAfterPrefetch,
        )

        assertEquals("shadow", spliceRes.sourceUsed)
        assertEquals("mediaSequence must NOT shift when clean prefetches are seeded", 980L, extractMediaSequence(spliceRes.outputPlaylist))
        assertEquals(1, Regex("seg_2173819\\.ts").findAll(spliceRes.outputPlaylist).count())
        assertEquals(1, Regex("seg_2173829\\.ts").findAll(spliceRes.outputPlaylist).count())
        assertTrue(spliceRes.outputPlaylist.contains("seg_2173841.ts"))
    }

    @Test
    fun exitSpliceWithNegativeClockDriftSkipsAlreadyPlayedSegmentByUri() {
        val window = TvShadowAdblock.ContinuityWindow()
        val prerollAd = buildFullAdPlaylist(mediaSeq = 995L, startSec = 0, count = 4)
        val shadowLive = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:1000
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:52:10.521Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_2173965.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:52:12.521Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_2173975.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:52:14.521Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_2173987.ts
        """.trimIndent()

        val shadowParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadowLive)
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, prerollAd),
            primaryRawText = prerollAd,
            window = window,
            shadowPlaylist = shadowParsed,
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            isInitialPlaylist = true,
        )

        // Primary returns after ad break with -348ms PDT shift on seg_2173987 (same segment shadow just served!)
        val primaryReturn = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:1002
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:52:16.173Z
            #EXTINF:2.000,live
            https://edge-other.j.cloudfront.hls.ttvnw.net/v1/segment/seg_2173987.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T11:52:18.173Z
            #EXTINF:2.000,live
            https://edge-other.j.cloudfront.hls.ttvnw.net/v1/segment/seg_2173997.ts
        """.trimIndent()
        val primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, primaryReturn)

        var res: TvShadowAdblock.DecisionResult? = null
        for (poll in 1..3) {
            res = TvShadowAdblock.processMediaPlaylistDecision(
                variantUrl = primaryVariant1080Url,
                primaryParsed = primaryParsed,
                primaryRawText = primaryReturn,
                window = window,
                shadowPlaylist = shadowParsed,
                shadowVariantUrl = shadowVariantUrl,
                shadowFresh = true,
            )
        }

        assertEquals("primary", res!!.sourceUsed)
        assertEquals("seg_2173987 must NOT be replayed despite -348ms PDT drift", 1, Regex("seg_2173987\\.ts").findAll(res.outputPlaylist).count())
        assertTrue(res.outputPlaylist.contains("seg_2173997.ts"))
    }

    @Test
    fun cleanPlaybackAfterAdBreakDoesNotDuplicateSegmentWhenPdtDrifts() {
        val window = TvShadowAdblock.ContinuityWindow()
        val ad = buildFullAdPlaylist(mediaSeq = 500L, startSec = 0, count = 4)
        val shadow = buildCleanPlaylist(mediaSeq = 600L, startSec = 0, count = 3, prefix = "https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/shadow")
        val shadowParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadow)
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, ad),
            primaryRawText = ad,
            window = window,
            shadowPlaylist = shadowParsed,
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            isInitialPlaylist = true,
        )

        val cleanPoll1 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:610
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:08.000Z
            #EXTINF:1.317,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_short_1317.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:09.317Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_after_short.ts
        """.trimIndent()
        val parsed1 = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, cleanPoll1)
        for (i in 1..3) {
            TvShadowAdblock.processMediaPlaylistDecision(
                variantUrl = primaryVariant1080Url,
                primaryParsed = parsed1,
                primaryRawText = cleanPoll1,
                window = window,
                shadowPlaylist = shadowParsed,
                shadowVariantUrl = shadowVariantUrl,
                shadowFresh = true,
            )
        }

        // Next clean poll drifts PDT by +700ms on seg_after_short (exceeds 450ms PDT tolerance)
        val cleanPoll2 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:611
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:10.017Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_after_short.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-26T12:00:12.017Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_next_clean.ts
        """.trimIndent()
        val res2 = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, cleanPoll2),
            primaryRawText = cleanPoll2,
            window = window,
            shadowPlaylist = shadowParsed,
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
        )

        assertEquals("primary", res2.sourceUsed)
        assertEquals(1, Regex("seg_after_short\\.ts").findAll(res2.outputPlaylist).count())
        assertTrue(res2.outputPlaylist.contains("seg_next_clean.ts"))
    }

    @Test
    fun uriAnchoredSameStreamMidrollEntryAndExitOmitDiscontinuityEvenWith12sClockSkew() {
        val window = TvShadowAdblock.ContinuityWindow()
        val avcTs = TvShadowAdblock.MasterVariant(
            url = primaryVariant1080Url,
            width = 1920,
            height = 1080,
            bandwidth = 6500000L,
            codecs = "avc1.4D401F,mp4a.40.2",
            frameRate = 60.0,
        )
        val shadowAvcTs = avcTs.copy(url = shadowVariantUrl)

        // Primary has drifted PDT (13:20:18Z) after 20 min of live playback
        val cleanPrimary = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:3672
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:20:16.245Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_3672.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:20:18.245Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_3673.ts
        """.trimIndent()
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, cleanPrimary),
            primaryRawText = cleanPrimary,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
            primaryVariantMeta = avcTs,
        )

        // Mid-roll starts: shadow has +12.6s PDT skew but shares exact origin segment URI seg_3673.ts
        val midrollAd = buildFullAdPlaylist(mediaSeq = 3674L, startSec = 20, count = 3)
        val shadowEntry = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:3673
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:20:30.839Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_3673.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:20:32.839Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_3674.ts
        """.trimIndent()
        val entryRes = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, midrollAd),
            primaryRawText = midrollAd,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadowEntry),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            primaryVariantMeta = avcTs,
            shadowVariantMeta = shadowAvcTs,
        )

        assertEquals("shadow", entryRes.sourceUsed)
        assertTrue(entryRes.outputPlaylist.contains("seg_3674.ts"))
        assertEquals(1, Regex("seg_3673\\.ts").findAll(entryRes.outputPlaylist).count())
        assertFalse("Continuous URI-anchored TS splice must NOT emit EXT-X-DISCONTINUITY", entryRes.outputPlaylist.contains("#EXT-X-DISCONTINUITY\n"))
        assertEquals(false, entryRes.spliceEvent?.discontinuityEmitted)

        // Mid-roll ends: primary returns with -12.6s PDT skew (13:20:20Z vs shadow's 13:20:34Z)
        // sharing seg_3674.ts followed by seg_3675.ts
        val primaryExit = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:3674
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:20:20.245Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_3674.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:20:22.245Z
            #EXTINF:2.000,live
            https://91334ab1a2f2.j.cloudfront.hls.ttvnw.net/v1/segment/seg_3675.ts
        """.trimIndent()
        val primaryExitParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, primaryExit)
        val shadowParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadowEntry)
        var exitRes: TvShadowAdblock.DecisionResult? = null
        for (poll in 1..3) {
            exitRes = TvShadowAdblock.processMediaPlaylistDecision(
                variantUrl = primaryVariant1080Url,
                primaryParsed = primaryExitParsed,
                primaryRawText = primaryExit,
                window = window,
                shadowPlaylist = shadowParsed,
                shadowVariantUrl = shadowVariantUrl,
                shadowFresh = true,
                primaryVariantMeta = avcTs,
                shadowVariantMeta = shadowAvcTs,
            )
        }

        assertEquals("primary", exitRes!!.sourceUsed)
        assertTrue("Primary exit must ingest seg_3675 despite -12.6s PDT skew", exitRes.outputPlaylist.contains("seg_3675.ts"))
        assertEquals(1, Regex("seg_3674\\.ts").findAll(exitRes.outputPlaylist).count())
        assertFalse("Continuous URI-anchored TS exit must NOT emit EXT-X-DISCONTINUITY", exitRes.outputPlaylist.contains("#EXT-X-DISCONTINUITY\n"))
        assertEquals(false, exitRes.spliceEvent?.discontinuityEmitted)
    }

    @Test
    fun clockSkewWithoutSharedUrisReanchorsInsteadOfStallingPlaylist() {
        val window = TvShadowAdblock.ContinuityWindow()
        val preroll = buildFullAdPlaylist(mediaSeq = 4000L, startSec = 0, count = 3)
        val shadow = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:4000
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:22:09.000Z
            #EXTINF:2.000,live
            https://shadow-edge.hls.ttvnw.net/v1/segment/shadow_only_4000.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:22:11.000Z
            #EXTINF:2.000,live
            https://shadow-edge.hls.ttvnw.net/v1/segment/shadow_only_4001.ts
        """.trimIndent()
        val shadowParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadow)
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, preroll),
            primaryRawText = preroll,
            window = window,
            shadowPlaylist = shadowParsed,
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
            isInitialPlaylist = true,
        )

        // Primary returns with completely different URIs AND a -12.6s PDT offset behind shadow
        val primarySkewed = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:4002
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:21:56.400Z
            #EXTINF:2.000,live
            https://primary-edge.hls.ttvnw.net/v1/segment/primary_only_4002.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:21:58.400Z
            #EXTINF:2.000,live
            https://primary-edge.hls.ttvnw.net/v1/segment/primary_only_4003.ts
        """.trimIndent()
        val primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, primarySkewed)
        var exitRes: TvShadowAdblock.DecisionResult? = null
        for (poll in 1..3) {
            exitRes = TvShadowAdblock.processMediaPlaylistDecision(
                variantUrl = primaryVariant1080Url,
                primaryParsed = primaryParsed,
                primaryRawText = primarySkewed,
                window = window,
                shadowPlaylist = shadowParsed,
                shadowVariantUrl = shadowVariantUrl,
                shadowFresh = true,
            )
        }

        assertEquals("primary", exitRes!!.sourceUsed)
        assertTrue("Anti-stall guard must ingest primary live edge instead of freezing playlist", exitRes.outputPlaylist.contains("primary_only_4003.ts"))
        assertTrue("Non-URI-anchored clock-skew reanchor must emit EXT-X-DISCONTINUITY", exitRes.outputPlaylist.contains("#EXT-X-DISCONTINUITY\n"))
        assertEquals(true, exitRes.spliceEvent?.discontinuityEmitted)
    }

    @Test
    fun uriAnchoredFmp4WithDifferentInitMapStillEmitsDiscontinuity() {
        val window = TvShadowAdblock.ContinuityWindow()
        val mapPrimary = "#EXT-X-MAP:URI=\"https://primary-edge.hls.ttvnw.net/v1/segment/init_primary.mp4\""
        val mapShadow = "#EXT-X-MAP:URI=\"https://shadow-edge.hls.ttvnw.net/v1/segment/init_shadow.mp4\""

        val cleanPrimary = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:5000
            $mapPrimary
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:30:00.000Z
            #EXTINF:2.000,live
            https://edge.hls.ttvnw.net/v1/segment/seg_5000.m4s
        """.trimIndent()
        TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, cleanPrimary),
            primaryRawText = cleanPrimary,
            window = window,
            shadowPlaylist = null,
            shadowVariantUrl = null,
            shadowFresh = false,
        )

        val ad = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:5001
            $mapPrimary
            #EXT-X-DATERANGE:ID="stitched-ad-5001",CLASS="twitch-stitched-ad",START-DATE="2026-11-28T13:30:02.000Z",DURATION=15.0
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:30:02.000Z
            #EXTINF:2.000,Amazon|ad_5001
            https://d2v02pertd.cloudfront.net/ad/ad_5001.m4s
        """.trimIndent()
        val shadow = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:5000
            $mapShadow
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:30:00.000Z
            #EXTINF:2.000,live
            https://edge.hls.ttvnw.net/v1/segment/seg_5000.m4s
            #EXT-X-PROGRAM-DATE-TIME:2026-11-28T13:30:02.000Z
            #EXTINF:2.000,live
            https://edge.hls.ttvnw.net/v1/segment/seg_5001.m4s
        """.trimIndent()

        val res = TvShadowAdblock.processMediaPlaylistDecision(
            variantUrl = primaryVariant1080Url,
            primaryParsed = TvShadowAdblock.parseAndClassifyMediaPlaylist(primaryVariant1080Url, ad),
            primaryRawText = ad,
            window = window,
            shadowPlaylist = TvShadowAdblock.parseAndClassifyMediaPlaylist(shadowVariantUrl, shadow),
            shadowVariantUrl = shadowVariantUrl,
            shadowFresh = true,
        )

        assertEquals("shadow", res.sourceUsed)
        assertTrue(res.outputPlaylist.contains("#EXT-X-DISCONTINUITY\n$mapShadow"))
        assertEquals(true, res.spliceEvent?.discontinuityEmitted)
    }
}



