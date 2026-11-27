# XtraPlus 1.4.9

### What's New
- **Rebranded to `XtraPlus` (Android TV & Mobile)**:
  - New **XtraPlus** app icon (adaptive + legacy/round), clean **Android TV banner**, and unified **XtraPlus** branding across the app, toolbars, update checker (`razeNFR/XtraPlus`), backups (`XtraPlus-backup.zip`, while still restoring legacy `XtraTV-backup.zip`), README screenshots, and QR companion portals.
  - Keeps the existing Android package ID (`com.github.razenfr.xtratv`) so **1.4.8** users upgrade seamlessly without losing accounts or settings.
- **Swap+ Preroll & Midroll Breakthrough (TV & Mobile)**:
  - Fixed LL-HLS / fMP4 midroll detection (including `#EXT-X-TWITCH-PREFETCH` segments and `#EXT-X-SKIP` delta updates) so **Swap+** intercepts midrolls continuously on the same player at full quality (1080p60 / 1440p) without falling back to Classic Swap.
  - Eliminated post-ad looping on long 60s sliding playlists, enforced strict monotonic `MEDIA-SEQUENCE` / `DISCONTINUITY-SEQUENCE` continuity to prevent ExoPlayer crashes/quality drops, and cleaned LL-HLS control tags in synthesized playlists.
  - Added proactive shadow token renewal, fast single-GET shadow refresh on active ads, smoother cold preroll start offset (no early micro-rebuffering), and fixed the preroll loading overlay getting stuck on startup.
- **Adblock Badge Polish & Cleaner Settings (TV & Mobile)**:
  - **Android TV**: added **`Adblock (Swap+)`** and **Show adblock badge during ads** (enabled by default) to **Settings → Ads & Proxy**, upgraded the **Swap+** badge to display **`Pre-roll ad` / `Mid-roll ad`**, and moved **Hide player during ads** to **Settings → Advanced**.
  - **Mobile**: moved **Show adblock badge during ads** (off by default) to **Settings → Adblock & Proxies**, moved **Hide player during ads** and **Video swap settings** to **Settings → Debug**, and redesigned the mobile adblock badge into a compact **`Ad`** pill with clean top-right spacing that never overlaps player controls.
- **Improved Android TV Box Detection & Manual Interface Mode**:
  - Strengthened auto-detection for non-certified AOSP Android TV boxes (such as **Beelink Amlogic GT1mini-2** on Android 8) that falsely declare touchscreen/battery features.
  - Added a manual **Interface mode** setting (**Automatic / TV / Mobile**) in **Settings → Interface** on both mobile and TV.
- **Modernized Mobile UI, Accent Color & Multi-Account Switcher (Zero Changes to Android TV)**:
  - Added a standalone **Accent color** picker inside **Settings → Theme** (decoupled from Dark / AMOLED / Light / Dynamic themes) tinting streamer names, switches, navigation indicators, tabs, refresh spinners, bottom sheets, and a subtle top ambient glow. Replaced the legacy **Blue** mobile theme (auto-migrated to Dark + Blue accent).
  - Unified mobile top toolbars with the **XtraPlus** brand icon/title, Settings gear, and live circular **Account avatar** opening a Material 3 **Accounts** Bottom Sheet (switch accounts, add account, Guest mode, remove account).
  - Modernized the mobile **Bottom Navigation Bar**, **Category cards** (3:4 posters + native sort sheet), **Followed Channel cards** (live first, live ring, `LIVE` pill, favorite star), **Stream / VOD / Clip cards** (responsive columns, `★ SUB` badge), and **Channel page**.
  - Fixed **Recommended for you** sorting on mobile web login and fixed the mobile bottom navigation bar staying hidden after navigating via long-press (**Channel** / **Category**).
- **Faster Mobile Stream Startup & Seamless Settings Navigation**:
  - Opening **Settings** on mobile no longer pauses or stops a playing stream / mini-player.
  - Shows stream thumbnail during loading, pre-warms stream tokens & TLS on tap, uses a snappy `600 ms` initial buffer, and unlocks **`Adblock (Swap+)`**, **`Adblock (VOD)`**, **Live DVR**, **My statistics**, **Favorites**, **Hidden content**, **DMCA-muted VOD audio recovery**, and **Clip autoplay** on mobile.

### Install
Download the 1.4.9 APK below and install it over the previous version — your settings and account are kept.
