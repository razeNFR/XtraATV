# XtraPlus 1.4.9.1

### Fixes & Improvements
- **Swap+ Smooth Splicing & Zero Rollbacks (Classic Swap untouched)**:
  - Aligned primary and shadow playlists strictly on wall-clock timestamps (`#EXT-X-PROGRAM-DATE-TIME` + cumulative durations) so **Swap+** never serves a segment starting before the end of the last served segment (within 45% tolerance), eliminating −2 s / −4 s rollbacks and audio timestamp discontinuities at ad entry and exit.
  - Capped ingestion strictly at 3 new segments per refresh (no bulk jumps), maintained a strictly increasing internal `MEDIA-SEQUENCE`, fixed `#EXT-X-DISCONTINUITY-SEQUENCE` header tracking across refreshes, and re-emitted `#EXT-X-DISCONTINUITY` + `#EXT-X-MAP` cleanly on every source transition.
  - Enforced strict **codec, container, resolution, and frame-rate** matching between primary and shadow variants (`CODECS` / `RESOLUTION` / `FRAME-RATE`), automatically aligning both sides to **H.264 (`avc1`)** on HEVC/AV1 channels when no matching shadow codec exists rather than switching hardware decoders mid-playback.
  - Requested the **Swap+** primary stream with `playerType="embed"` (automatic fallback to `"site"` on HTTP 403 / forbidden token) to reduce pre-roll ads while leaving Classic Swap completely untouched.
  - Added lightweight diagnostic logs at each splice transition and ad-break end for device verification.
- **DMCA-Muted VOD Audio Recovery Fixed**:
  - Prioritized `N-unmuted.<ext>` over plain `N.<ext>` in parallel probing (ignoring HTTP 403 on plain segments), removed false rejections from strict byte-delta checks, stripped debug body interceptors on probe requests, and added 1-byte `Range` fallback on HTTP 405.
  - Scoped recovery cache keys per quality directory so ABR quality switches probe independently, prevented GraphQL muted-range rounding from painting red over recovered green seekbar segments, tightened live-vs-VOD playlist isolation, and added full diagnostic logging (detected count, probed URLs/HTTP codes/sizes, chosen mode, first 3 rewritten URLs, and any per-segment fallback).

### Install
Download the 1.4.9.1 APK below and install it over the previous version — your settings and account are kept.
