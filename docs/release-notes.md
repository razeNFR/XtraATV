# Xtra TV 1.4.8

### What's New
- **New `Adblock (Swap+)` Continuous Shadow HLS Engine *(Experimental)***:
  - Maintains a lightweight anonymous shadow HLS playlist on the main fullscreen player to splice out pre-roll and mid-roll ads **without cuts**, with **less quality drop** and **normal live latency** (unlike `Adblock (VOD)`), showing a lightweight **`Ad`** badge during breaks and falling back automatically to `Adblock (Swap)` (`Swap fallback (Swap+ failed)`) if needed.
  - Multi-Stream and PiP stay on classic `Adblock (Swap)` (or `Adblock (VOD)` when enabled) for maximum stability and low resource usage.
  - Renamed Experimental Adblock to **`Adblock (VOD)`** *(Experimental)* everywhere, clarifying that it works everywhere (main player, Multi-Stream, and PiP) with automatic fallback to `Swap+` / `Swap`.
  - **Clearer Adblock & Proxies Panel & Settings**: added concise descriptions under `Adblock (Swap)`, `Adblock (Swap+)`, and `Adblock (VOD)`, replaced confusing duplicate toggles with a clear **`Active fallback`** badge on fallback blockers, and automatically hides Custom Proxy and HTTP Stream Proxy lists in the player panel when toggled off.
- **DMCA-Muted VOD Audio Recovery (VOD, Live DVR Rewind & `Adblock (VOD)`)**:
  - Opt-in toggle in Player Settings (**off by default**, best-effort depending on whether Twitch still keeps the original audio cached) that attempts to restore muted music segments across **classic VODs**, **sub-only VODs**, **live DVR rewind**, **`Adblock (VOD)`**, and **VOD quality switches**.
  - **Green & Red Seek Bar**: shows **green** segments for verified recovered audio and **red** segments for muted audio (always visible even when recovery is off), with per-range majority probing, silent-copy filtering, permanent per-segment fallback memory on HTTP 403/404/410, and a one-shot recovery toast (`Muted audio recovered (~X min)`).
- **Instant Live DVR Rewind via Async Background Pre-Lookup**:
  - 3 seconds after a fullscreen live stream loads (skipped in PiP and Multi-Stream), quietly resolves `index-dvr.m3u8` on a background thread so rewinding or resuming from pause switches to DVR immediately without waiting on *"Looking for VOD..."*.
- **Pure-Black OLED Idle Screen Dimming (SmartTube-Style)**:
  - Protects OLED TVs from burn-in by dimming or turning the screen **pure `#000000` black** (100% default, configurable 100% / 85% / 70% / 50%) after configurable inactivity (**Off / 1 / 2 / 3 / 5 / 10 min**) on Home, menus, or when playback is **paused** — never during active video playback.
  - Releases idle preview players while active and wakes instantly on the first remote press without triggering accidental clicks.
- **1440p & 4K (2160p) Preferred Quality Options**:
  - Added **`2160p60 (4K)`**, **`2160p (4K)`**, **`1440p60`**, and **`1440p`** right after **`Source`** in quality preferences, automatically falling back to `1080p60` or the highest available lower quality when unavailable.
- **Smart TV & Mobile Layout Detection**:
  - Restores the mobile layout for smartphone users while keeping the Android TV interface forced on 100% of Android TVs, Fire TVs, and generic / non-certified AOSP Android TV boxes (no touchscreen, or no telephony & no battery).
- **QR Phone Chat Panel Polish**:
  - Clicking **Disconnect** stops the local Wi-Fi server while keeping the QR panel open so you can restart it with one click whenever needed.

### Install
Download `XtraTV-1.4.8-tv.apk` below and install it over the previous version, your settings and account are kept.
