# Xtra TV 1.3.9

### ✨ Highlight — Experimental Ad Blocker
- **Ad blocker (Experimental)**: new mode in *Settings → Ads & Proxies* that avoids ads without interruptions or quality drops using a slight ~30s stream delay and automatic chat synchronization (including full chat replay when rewinding on the timeline). Works across the main player, Multi-Stream, and Picture-in-Picture, and automatically falls back to **Ad blocker (Swap)** with a discreet `Swap` badge when unsupported on a broadcast.

### New features & improvements
- **Ads & Proxies refinements**: renamed the classic blocker to **Ad blocker (Swap)**, added automatic mutual exclusion with custom/HTTP proxies, moved **Video swap settings** to **Advanced**, and kept the **Live** HUD button accurate with the ~30s offset.
- **Home screen options**: new option to load **all followed live channels** directly in the *Followed live* row (with smooth progressive scrolling and no *See all* cap), plus a new optional **Offline channels** row in *Home rows*.
- **Following improvements**: sort **Following → Live** by *Viewers* or *Alphabetical* order, and add or remove **Favorites (★)** directly on offline channels in **Following → Channels** (via long-press OK).
- **Side menu options**: optional **Close side menu with Back** setting, and an option to hide **Statistics** from the side menu.
- **Long-press remote shortcuts & Go to live**: assign both a **short press** and a **long press** to custom remote buttons, configure **Long press Up** on the player (defaults to opening a live notification), and map **Go to live** to any remote shortcut.
- **Predictions popup without chat**: new option in the player's *Chat settings* to display **prediction started/ended popups** even when chat is hidden.
- **Physical / Bluetooth keyboard support**: new option in *Settings → Interface* to **disable the on-screen keyboard** when focusing text fields while typing directly with a hardware keyboard.
- **Deep links documentation**: added [`docs/deeplinks.md`](deeplinks.md) covering all supported URLs, ADB commands, Intent URIs, and Home Assistant examples.

### Install
Download `XtraTV-1.3.9-tv.apk` below and install it over the previous version, your settings and account are kept.
