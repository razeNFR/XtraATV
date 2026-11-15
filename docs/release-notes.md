# Xtra TV 1.4.0

### ✨ Highlights
- **True pause & time-shift resume on live streams**: pause any live stream and resume right where you left off — even several minutes later — with automatic chat synchronization and a **Live** button in the player controls to jump back to real time whenever you want.
- **Unified Adblock & Proxy player panel**: the player's **Adblock & Proxy** button now opens a single quick panel to toggle **Ad blocker (Experimental)** or manage **custom HLS proxies** on the fly without leaving your stream.

### New features & improvements
- **Highlighted users in chat**: highlight messages from friends, moderators, or favorite regulars with a distinct warm gold highlight in busy chats. Manage your list in *Settings → Chat → Highlighted users* (supports comma-separated usernames) or directly from the player's **Accounts & bots** chat panel (from active chatters or manual entry). Highlighted users' messages are also kept when *Slow chat* filtering is active.
- **Configurable OK (short & long press) & Small Window shortcuts**: customize both **OK** and **OK (long press)** in *Directional pad shortcuts*, and assign **Small window: swap streams**, **Small window: settings**, or **Small window: close** to any D-pad direction or custom remote button (short or long press). When the small window is closed, small-window shortcuts automatically fall back to standard player controls. *(Thanks to @kvart714 in #73 for the OK short-press shortcut contribution!)*
- **Removed hardcoded Fast-Forward live action**: pressing the remote's Fast-Forward / Next media button on a live stream no longer triggers an unintended jump unless explicitly assigned in *Custom remote buttons* (fast-forward on VODs and clips is unchanged).
- **Calibrated stream latency & synchronized chat**:
  - Calibrated stream latency (*Stats for nerds*) and auto chat delay against Twitch's server clock across standard, Swap, Proxy, and Experimental Adblock modes — eliminating `0.0s` or negative latency values when a TV box's system clock is slightly off.
  - Synchronized chat right from the first second of stream playback under **Ad blocker (Experimental)** so ahead-of-video messages never appear early.
- **Fixes & stability**:
  - Improved live pause & resume in standard / Swap adblock mode: short pauses (up to ~48s) resume instantly in memory at `1.0x` speed without jumping to live or allocating extra buffer RAM, while longer pauses seamlessly resume at the exact paused timestamp.
  - Fixed automatic video quality (`Auto`) occasionally dropping resolution right after rewinding or seeking in VODs and time-shifted streams.
  - Fixed an infinite loop on the *"Install unknown apps"* permission screen on some Android TV / Google TV / Fire OS devices when installing an app update, with a direct browser/settings fallback if Android blocks package installation.

### Install
Download `XtraTV-1.4.0-tv.apk` below and install it over the previous version, your settings and account are kept.
