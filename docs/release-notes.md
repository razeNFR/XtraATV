# Xtra TV 1.3.9

### New features & improvements
- **Experimental Adblock (`Ad blocker (Experimental)`)**: optional TV mode that plays live broadcasts via Twitch's ongoing CDN VOD (`index-dvr.m3u8`) at a ~30s offset to avoid server-side live ads completely, with **zero background live player** running (no VAFT swaps or extra bandwidth), across the full-screen player, Multi-Stream, and Picture-in-Picture.
- **Full Live UX & Auto Chat Sync**: keeps Channel Points, +50 bonus auto-claims, Watch Streaks, Drops, Polls, Predictions, and Raid auto-switches (automatically delayed by ~30s so you hear the streamer finish saying goodbye), preserves VOD rewind while clamping "Return to live" to the ad-free `-30s` edge so it never jumps onto the raw live feed, and automatically syncs live IRC chat (`Auto`) to the exact displayed VOD timestamp.
- **Smart Fallback & Brand-New Stream Upgrade**: automatically falls back to **Ad blocker (Swap)** when a streamer has VOD archiving disabled, and starts brand-new streams (`< 3 min`) on **Swap** before seamlessly switching onto the ad-free VOD as soon as enough segments are recorded.
- **Refined Adblock & Proxy Settings**: renamed VAFT adblock to **Ad blocker (Swap)** (enabled and locked by default unless a custom or HTTP stream proxy is active), enforced mutual exclusion between **Experimental Adblock** and **Proxies**, and moved **Video swap settings** into **Advanced**.

### Install
Download `XtraTV-1.3.9-tv.apk` below and install it over the previous version, your settings and account are kept.
