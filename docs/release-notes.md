# Xtra TV 1.4.1

### ✨ Highlights
- **Channel Points & Twitch Drops auto-collection across all playback modes**: fixed Channel Points watch-time progression and automatic `+50` bonus chest collection on Android TV accounts, and added **automatic Twitch Drops progression & claiming** (*Settings → Chat → Claim Twitch Drops automatically*, enabled by default) across all playback modes (Standard, Adblock Swap, Proxy, and Ad blocker Experimental).
- **Exact stream latency & zero-lag Auto chat delay**: refined live latency measurement (*Stats for nerds*) to match Twitch's official Low-Latency player without `+4s` prefetch inflation or `1s` oscillations, and calibrated **Auto chat delay** so ultra-low-latency streams display chat in real time (`0.0s` extra delay) while still synchronizing automatically whenever latency increases or the stream is paused.

### Improvements & fixes
- **Channel Points & Twitch Drops**:
  - Authenticated PubSub (`Hermes`) and watch-time tracking natively with Android TV sign-in credentials.
  - Updated `minute-watched` tracking with current Twitch category metadata and lightweight `0`-byte presence verification so both Channel Points and multi-tier Twitch Drops progress reliably across Standard, Swap, Proxy, and Experimental Adblock modes without extra memory or bandwidth usage.
  - Added real-time Drop auto-claim (`user-drop-events`) plus automatic inventory checks so earned Drops are claimed immediately and subsequent campaign tiers unlock automatically.
- **Pause & live timeline in Ad blocker (Experimental)**:
  - Pressing **Pause** under **Ad blocker (Experimental)** now immediately displays the paused timestamp, smoothly advances total stream duration second-by-second, surfaces the **Live** button, and keeps time-shifted playback controls visible upon resuming until returning to live — matching Proxy and Swap modes.
  - Synchronized the live stream uptime badge with the broadcast timeline duration across all playback modes (including **Ad blocker (Experimental)** and server clock calibration) so both counters match to the exact second.
- **Stream latency & Auto chat delay**:
  - Locked Twitch server clock calibration to millisecond precision once acquired so coarse HTTP `Date` headers no longer cause `1s` latency jumps.
  - Accounted for chatter baseline viewing latency in **Auto** chat delay mode and updated the chat panel indicator to display `0.0s` once measured instead of staying on *"Measuring…"*.

### Install
Download `XtraTV-1.4.1-tv.apk` below and install it over the previous version, your settings and account are kept.
