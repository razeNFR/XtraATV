# Xtra TV 1.4.6.9

### New & Polish
- **TV Wallpapers — Procedural Vector Styles + Phone QR Custom Image / URL**:
  - Added **Wallpaper** right below **Ambient color** in **Settings > Xtra TV > Home & Side menu** with an active-style badge (*Default*, *AMOLED Topographic*, *Gaming Prisms*, *Ribbons*, *Aurora*, or *Custom image*).
  - **Ambient-reactive vector wallpapers**: *AMOLED Topographic* (pitch-black `#000000` OLED base with glowing elevation contours), *Gaming Prisms* (3D-lit faceted low-poly shards), *Ribbons* (layered flowing silk waves), and *Aurora* (atmospheric northern-lights curtains) automatically adopt your selected **Ambient color** — including live crossfades in **Dynamic** mode.
  - **Send image or URL from your phone via QR code**: scan the local Wi-Fi QR code from your phone to upload any gallery photo/wallpaper or paste a direct image URL (automatically oriented, scaled up to `1920×1080`, and included in Xtra TV backups).
  - **Unobstructed live-preview side panel**: fades out the settings overlay while open so you can preview styles, **Visibility** (`10–100%`), **Blur** (`0–100%`), and **Reset** live at full brightness.
- **Compact Smoked-Glass Volume Pill in the TV Player**:
  - Pressing **OK** on the **Volume** button in the TV player bar now opens a compact smoked-glass pill anchored directly above the Volume icon instead of covering half the stream with a full-height side panel.
  - **D-pad controls**: **Left / Right** adjusts volume live in **5%** steps, **long-press OK** toggles **Mute / Unmute** instantly, and **OK**, **Down**, **Back**, or **3 seconds** of inactivity closes the pill cleanly while keeping focus on the Volume icon.

### Fixes
- **Fixed Watched Live Streams Appearing as Viewed VODs in "Continue Watching"**:
  - **Ongoing recording filter**: `TvVodHistorySync` now queries Twitch GraphQL `Video.status` and active `owner.stream` metadata to ignore broadcasts still being recorded (`status != RECORDED`, `404_processing` thumbnails, or currently airing `archiveVideo`s).
  - **Local live-session guard**: remembers live broadcasts opened in Xtra TV (single player, stream switcher, PiP, and Multi-Stream) so Twitch's auto-recorded `currentUser.viewedVideos` entry never surfaces on its own in **Continue watching** even after the stream ends, unless you explicitly open that VOD yourself.

### Install
Download `XtraTV-1.4.6.9-tv.apk` below and install it over the previous version, your settings and account are kept.
