# Xtra TV 1.4.7.2

### Fixes & Improvements
- **TV Interface Enabled on 100% of Android Boxes**:
  - Forced Android TV UI mode across all app activities and services, fixing generic / non-certified AOSP Android boxes that previously fell back to the mobile layout with 4 bottom icons (zero-cost no-op on certified Android TV / Google TV devices, preserving theme & language settings).
- **Instant Live-Apply for TV Customization & Preview Settings**:
  - Changing **Card size on Home**, **Card size in lists**, **Focused card zoom**, **Compact side menu**, **Truncate viewer count**, **Streamer name display**, **Round avatars**, **Tags**, or **Uptime** now refreshes the Home screen and all open lists immediately — no more force-closing or restarting the app.
  - Global theme and pager options (dark theme, Material 3, rounded corners, reduced padding, compact text, Follow/Game tabs) cleanly refresh the UI automatically as soon as you leave Settings.
- **Multi-Streamer Live Alert Banners Fixed**:
  - Fixed a layout bug where simultaneous live notifications crushed the **`LIVE`** badge down to `"L"` and misaligned streamer names with titles.
  - When **1 to 3 followed streamers** go live at the same time, each now gets its own clean banner (avatar, name, game, title); **4 or more** simultaneous launches are neatly grouped into a summary banner.
- **Persistent QR Phone Live Chat, Web App Shortcut & Streamer Avatar**:
  - **Stable Local URL & Installable Web App**: the QR companion now uses a fixed local port (`8765`, with automatic fallback if busy) and a persistent token, plus a proper Web App Manifest & Xtra TV app icon (`/manifest.json` + `/icon.png`) so **"Add to Home Screen"** on Android/iOS gets the real Xtra TV icon and reconnects without re-scanning.
  - **Regenerate QR Link & Reconnect**: added a **Regenerate QR link** button in the QR panel to instantly rotate the link and revoke previously paired phones, plus smooth automatic/one-tap reconnection in the mobile chat page.
  - **Real Streamer Profile Picture**: the mobile chat header now displays the streamer's actual profile picture (in both single stream and Multi-Stream, updating across switches), with automatic fallback to the initial letter if unavailable.
  - **Optional Auto-Start**: added an optional **Auto-start phone chat** setting (OFF by default, available in the QR panel and Chat settings) to start the local Wi-Fi server automatically whenever you open a live stream or Multi-Stream.
- **Direct Focus on the First Tab in Side Panels**:
  - Opening tabbed TV side panels (**Chat settings**, **PiP settings**, **Multi-Stream menu**, and **Wallpaper panel**) now focuses the first tab right away so you can browse tabs left/right immediately, with crash-safe post-frame focus handling.
- **Lightweight Navigation & Memory Optimizations**:
  - Eliminated redundant double layout passes (`requestLayout() during layout`) when switching tabs/sections with sort bars.
  - Ensured at most **1 live-preview `ExoPlayer`** stays allocated across browsing lists at any time, releasing idle preview players immediately when switching screens, opening a real player / Multi-Stream, or leaving the app.

### Install
Download `XtraTV-1.4.7.2-tv.apk` below and install it over the previous version, your settings and account are kept.
