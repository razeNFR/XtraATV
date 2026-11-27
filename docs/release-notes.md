# XtraPlus 1.4.9

### What's New
- **Rebranded to `XtraPlus` (Mobile & Android TV)**:
  - New **XtraPlus** app icon (adaptive + round/legacy), clean **Android TV banner**, and unified **XtraPlus** branding across the app, toolbars, update checker (`razeNFR/XtraPlus`), backups (`XtraPlus-backup.zip`, while still restoring legacy `XtraTV-backup.zip`), and QR companion portals.
  - Keeps the existing Android package ID (`com.github.razenfr.xtratv`) so **1.4.8** users upgrade seamlessly without losing accounts or settings.
- **Swap+ Midroll Reliability Improvements (TV & Mobile)**:
  - Fixed leaked `stitched-ad` `#EXT-X-DATERANGE` tags that could falsely trigger Classic Swap on top of an active Swap+ splice.
  - Added proactive shadow token renewal before Twitch's ~15-minute expiration, fast single-GET shadow refresh on midroll arrival, active-midroll backoff bypass, and a capped hold fallback (`MAX_HOLD_PASSES`) + container compatibility check so midrolls no longer stall, cut out, or restart the player.
- **Improved Android TV Box Detection & Manual Interface Mode**:
  - Strengthened auto-detection for non-certified AOSP Android TV boxes (such as **Beelink Amlogic GT1mini-2** on Android 8) that falsely declare touchscreen/battery features (checks HDMI-CEC and no-camera + no-vibrator + Ethernet/TV-box hardware/dummy battery).
  - Added a manual **Interface mode** setting (**Automatic / TV / Mobile**) in **Settings → Interface** on both mobile and TV so any device can switch interface in one tap.
- **Modernized Mobile UI, Accent Color & Branding (Zero Changes to Android TV)**:
  - Added a standalone **Accent color** picker inside **Settings → Theme** (decoupled from Dark / AMOLED / Light / Dynamic themes) that tints streamer names, navigation indicators, tabs, refresh spinners, bottom sheets, and a subtle top ambient glow on dark/AMOLED screens. Removed the legacy **Blue** theme on mobile (automatically migrated to Dark + Blue accent).
  - Added the official **XtraPlus** app logo and brand title to mobile top toolbars alongside a unified **Settings** gear icon and a live circular **Account avatar** (or anonymous icon) directly in every toolbar.
  - **Mobile Multi-Account Switcher**: tap the toolbar avatar to open a native Material 3 **Accounts** Bottom Sheet to switch Twitch accounts, add another account, enter Guest / anonymous mode, or remove an account.
  - Modernized the mobile **Bottom Navigation Bar**, **Category cards** (vertical 3:4 poster grid), **Followed Channel cards** (live channels sorted first, red live ring, `LIVE` pill, gold favorite star), **Stream / VOD / Clip cards** (responsive 1 / 2 / 3+ column density, VOD `?` `404_processing` fallback + purple `★ SUB` badge), and the **Channel page**.
  - Fixed **Recommended for you** sorting on mobile web login and added a native sort sheet on mobile Categories.
- **Faster Mobile Stream Startup & Seamless Settings Navigation**:
  - Opening **Settings** on mobile no longer pauses or stops a running stream / mini-player.
  - Displays the latest stream thumbnail during loading (`TvLoadingPicture`), unlocks stream token & TLS prefetch on tap (`TvStreamPrefetch`), uses a snappy `600 ms` default initial playback buffer, and synchronizes `SurfaceView` binding before `prepare()`.
- **Reorganized Mobile Settings & Adblock (`Swap+` / `VOD`)**:
  - Reorganized mobile settings into **6 clear categories** with mobile-specific Adblock descriptions (no TV Multi-Stream/PiP references), mobile HUD toggles (**Live quality badge**, **Show viewer count**, **Show adblock badge during ads**), **My statistics**, **Favorites**, **Hidden content**, **DMCA-muted VOD audio recovery**, **VOD history sync**, and **Clip autoplay**.
  - Unlocked **`Adblock (Swap+)`**, **`Adblock (VOD)`**, **Live DVR rewind** with compact **Live** pill, and native Material 3 Bottom Sheets & long-press actions everywhere on mobile.

### Install
Download the 1.4.9 APK below and install it over the previous version — your settings and account are kept.
