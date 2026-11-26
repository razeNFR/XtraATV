# Xtra TV 1.4.8

### What's New
- **New `Adblock (Swap+)` Continuous Shadow HLS Engine *(Experimental)***:
  - Maintains a lightweight anonymous shadow HLS playlist per active channel and splices out pre-roll and mid-roll ad segments seamlessly inside the HLS playlist with proper `#EXT-X-DISCONTINUITY` and `#EXT-X-DISCONTINUITY-SEQUENCE` tracking — **preserving 1440p / HEVC / AV1** on the primary stream (works in both single stream and Multi-Stream, with automatic fallback to `Adblock (Swap)` if needed).
  - Renamed Experimental Adblock to **`Adblock (VOD)`** everywhere with clear descriptions explaining its ~30s delay, automatic fallback to `Swap+` / `Swap`, and experimental status.
  - **Cleaner Adblock & Proxies Panel**: added concise descriptions under `Adblock (Swap)`, `Adblock (Swap+)`, and `Adblock (VOD)`, and automatically hides the Custom Proxy and HTTP Stream Proxy lists in the player panel when they are toggled off.
- **DMCA-Muted VOD Audio Recovery (VOD, Live DVR Rewind & `Adblock (VOD)`)**:
  - Automatically restores muted music segments when the unmuted original (`-unmuted` or plain segment) is still cached on Twitch's CDN — working across **classic VODs**, **live DVR rewind**, and **`Adblock (VOD)`** (configurable toggle in Player Settings, enabled by default).
  - **Green & Red Seek Bar**: shows **green** segments on the progress bar for recovered audio and **red** segments for permanently muted audio, with per-range probing (start/mid/end), permanent per-segment fallback memory on HTTP 403/404/410, and a one-shot recovery toast (`Muted audio recovered (~X min)`).
- **Instant Live DVR Rewind via Async Background Pre-Lookup**:
  - 3 seconds after a live stream starts (in `Swap`, `Swap+`, or Proxy mode; skipped in PiP and Multi-Stream), Xtra TV quietly resolves the ongoing `index-dvr.m3u8` VOD on a background thread so rewinding or resuming from pause switches to DVR immediately without waiting on *"Looking for VOD..."*.
  - Strictly keyed by `(channel, streamId, createdAt)` and automatically cancelled/cleared on channel switch.
- **Pure-Black OLED Idle Screen Dimming (SmartTube-Style)**:
  - Protects OLED TVs from burn-in by dimming or turning the screen **pure `#000000` black** (100% default, configurable 100% / 85% / 70% / 50%) after configurable inactivity (**Off / 1 / 2 / 3 / 5 / 10 min**) on Home, menus, or when video playback is **paused** — never during active playback.
  - Releases idle preview players while active and wakes instantly on the first remote press (consuming that press to avoid accidental clicks).
- **1440p & 4K (2160p) Preferred Quality Options**:
  - Added **`2160p60 (4K)`**, **`2160p (4K)`**, **`1440p60`**, and **`1440p`** right after **`Source`** in quality preferences, with automatic fallback to `1080p60` or the highest available lower quality when a stream does not offer higher resolutions.

### Install
Download `XtraTV-1.4.8-tv.apk` below and install it over the previous version, your settings and account are kept.
