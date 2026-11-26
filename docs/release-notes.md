# Xtra TV 1.4.9

### What's New
- **Modernized Mobile UI & Official XtraTV Branding (Zero Changes to Android TV)**:
  - Added the official **XtraTV** app logo and brand title to mobile top toolbars alongside a unified **Settings** gear icon and a live circular **Account avatar** (or anonymous icon) directly in every toolbar (home, channels, categories, teams — no more 3-dot overflow for settings/account).
  - **Mobile Multi-Account Switcher**: tap the toolbar avatar to open a native Material 3 **Accounts** Bottom Sheet to switch Twitch accounts, add another account, enter Guest / anonymous mode, manage login methods, or remove an account.
  - Modernized the mobile **Bottom Navigation Bar** (`64dp` height, accent-tinted active indicator pill across Dark, Light, Blue & AMOLED themes, crisper active/inactive contrast).
  - Modernized **Category cards** (vertical 3:4 poster grid with viewer overlay pill), **Followed Channel cards** (centered avatar with red live ring, red **`LIVE`** pill, live game/viewers & gold favorite star), **Stream / VOD / Clip cards** (red **`LIVE`** badge on normal & compact stream cards, responsive 2-column & 3+-column scaling so thumbnails always stay visible and titles readable, VOD `?` `404_processing` avatar fallback + purple **`★ SUB`** badge), and the **Channel page** (gradient banner scrim, live avatar ring & `LIVE` badge, rounded live info card, and Material 3 pill **`Watch live`** button).
  - **100% Android TV Isolation**: every visual and preference change is strictly scoped to mobile phones and tablets — the Android TV home screen, side menu, settings, panels, and player HUD remain untouched.
- **Faster Mobile Stream Startup & Loading Thumbnail**:
  - Displays the latest stream thumbnail during loading instead of a black screen (`TvLoadingPicture`).
  - Unlocked stream token & TLS prefetch on tap (`TvStreamPrefetch`), snappy `600 ms` default initial playback buffer (when default `2000 ms` is untouched), and `SurfaceView` bind sync before `prepare()` to avoid decoder resets.
- **Reorganized & Cleaned Mobile Settings**:
  - Removed TV-only settings (remote keys, hero banner, wallpapers, multi-stream, TV OLED dimmer, etc.) from mobile settings and settings search.
  - Reorganized mobile settings into **6 clear categories** with icons and live status summaries: **Appearance & general**, **Adblock & proxies**, **Player**, **Chat**, **Data & personalization**, and **System & about**.
  - Added mobile-only HUD visibility switches inside **Player buttons** (**Live quality badge**, **Show viewer count**) and a **Show adblock badge during ads** option (off by default) in **Player**, plus mobile access to **My statistics**, **Favorite channels**, **Hidden content**, **DMCA-muted VOD audio recovery** (off by default), **VOD history sync**, and **Clip autoplay**.
- **`Adblock (Swap+)` & `Adblock (VOD)` Now Available on Mobile**:
  - Unlocked both **`Adblock (Swap+)`** (continuous Shadow HLS with normal latency & automatic Swap fallback) and **`Adblock (VOD)`** (ongoing CDN VOD playback with synced chat) on mobile phones and tablets.
  - Added a mobile **Adblock & Proxies** Material 3 Bottom Sheet directly in the player with clear **`Active fallback`** indicators, plus the lightweight **`Ad`** badge during breaks.
- **Mobile Player Overhaul & Live DVR Rewind**:
  - Updated player icons to match TV: **`HD`** icon for **Quality** and **Settings gear** icon for **Player menu**, plus a real-time **Quality badge** pill (`1080p60`) alongside viewer count and uptime pills.
  - Replaced the flat grey player overlay on mobile with clean top and bottom gradient scrims so the video stays crisp in the center.
  - Unlocked **Live DVR rewind** on mobile (enabled by default, toggleable in Settings): scrub back on live streams with instant background DVR pre-lookup and jump back to the live edge with the clickable **Go Live** pill.
  - Exposed missing mobile player quick buttons in **Player buttons** settings: **Adblock & Proxies**, **Points & Drops**, **Polls & Predictions**, **Create 30s Clip** (with native Android Share / Copy link / Open in browser), and **Stats for nerds**.
- **Native Mobile Material 3 Bottom Sheets & Long-Press Actions**:
  - Replaced TV-only side panels on mobile with touch-native Material 3 Bottom Sheets for **Accounts**, **About**, **What's new / Changelog**, **Update available**, **My statistics**, **Adblock & Proxies**, **Favorites**, **Hidden content**, **Points & Drops**, and **Polls & Predictions**.
  - **Long-Press Actions Everywhere on Mobile**: long press any stream, followed channel, category, VOD, or clip to **Favorite ★**, **Follow / Unfollow**, **Hide streamer or category**, **Open channel / videos**, **Bookmark / Download**, or **Share**.

### Install
Download `XtraTV-1.4.9-tv.apk` below and install it over the previous version, your settings and account are kept.
