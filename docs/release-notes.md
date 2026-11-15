# Xtra TV 1.4.0

### ✨ Highlights
- **True pause & time-shift resume on live streams**: pause any live stream and resume right where you left off — even several minutes later — with automatic chat synchronization and a **Live** button in the player controls to jump back to real time whenever you want.
- **Unified Adblock & Proxy player panel**: the player's **Adblock & Proxy** button now opens a single quick panel to toggle **Ad blocker (Experimental)** or manage **custom HLS proxies** on the fly without leaving your stream.

### New features & improvements
- **Configurable OK (short & long press) & Small Window shortcuts**: customize both **OK** and **OK (long press)** in *Directional pad shortcuts*, and assign **Small window: swap streams**, **Small window: settings**, or **Small window: close** to any D-pad direction or custom remote button (short or long press). When the small window is closed, small-window shortcuts automatically fall back to standard player controls. *(Thanks to @keyweck in #73 for the OK short-press shortcut contribution!)*
- **Removed hardcoded Fast-Forward live action**: pressing the remote's Fast-Forward / Next media button on a live stream no longer triggers an unintended jump unless explicitly assigned in *Custom remote buttons* (fast-forward on VODs and clips is unchanged).
- **Experimental Adblock chat sync from launch**: chat messages are now held and synchronized right from the first second of stream playback so ahead-of-video messages no longer appear before the stream catches up.
- **Fixes & stability**:
  - Fixed automatic video quality (`Auto`) occasionally dropping resolution right after rewinding or seeking in VODs and time-shifted streams.
  - Fixed an infinite loop on the *"Install unknown apps"* permission screen on some Android TV / Google TV / Fire OS devices when installing an app update, with a direct browser/settings fallback if Android blocks package installation.

### Install
Download `XtraTV-1.4.0-tv.apk` below and install it over the previous version, your settings and account are kept.
