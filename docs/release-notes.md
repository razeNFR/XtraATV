# Xtra TV 1.4.6.9

### New & Polish
- **New "Customization" Category in Settings > Xtra TV**:
  - Dedicated **Customization** section grouping **Ambient color**, **Wallpaper**, **Card sizes** (Home & grids), **Featured banner**, **Home greeting**, **Stats badge**, **Compact side rail**, and **Side menu stats**, keeping **Home & Side menu** focused on rows, favorites, hidden content, and recent channels.
- **TV Wallpapers — Studio & OLED Shaders, Visual Gallery, Multi-Photo Phone QR & Auto-Rotation**:
  - **10 ambient-reactive procedural wallpapers**: 6 ACES-tonemapped **Studio & OLED shaders** (*Velvet Drape*, *Dichroic Glass*, *Glass Rings*, *Starry Night OLED*, *Twilight Dunes*, and *Northern Lights*) + 4 geometric vector styles (*AMOLED Topographic*, *Gaming Prisms*, *Ribbons*, and *Aurora*), all dynamically tinted by your active **Ambient color** (including live **Dynamic** crossfades).
  - **Unlimited custom images & persistent multi-upload QR portal**: scan the local Wi-Fi QR code once from your phone to select multiple gallery photos at once (`multiple`) or send several photos and image URLs in a row without closing the QR window; all custom wallpapers are preserved in Xtra TV backups.
  - **Visual 16:9 Gallery & Auto-Rotation playlist**: tabbed smoked-glass side panel (*Gallery*, *Rotation*, *Adjustments*) with live unobstructed background preview, **Auto-rotation** timer (*Every launch*, *5 min*, *10 min*, *30 min*, *1 h*, *3 h*) + **Shuffle** across any checked mix of built-in styles and custom photos, per-image **Long-press OK** options/delete, **Visibility** (`10–100%`), and **Blur** (`0–100%`).
- **Configurable Live Preview & Card Zoom Delay**:
  - Added **Preview & zoom delay** in **Settings > Xtra TV > Previews & Live alerts** (`0.25 s` default, `0.5 s`, `1 s`, `1.5 s`, `2 s`, `3 s`, `5 s`) to control how long focus stays on a card before starting the live video preview and card expansion.
- **Compact Smoked-Glass Volume Pill & Direct Long-Press Mute in the TV Player**:
  - Pressing **OK** on **Volume** opens a compact smoked-glass pill directly above the Volume icon with a bright active focus border, circular slider thumb (`●`), and `‹` `›` D-pad arrows (**Left / Right** adjusts volume in **5%** steps; **OK**, **Down**, **Back**, or **3 s** closes it).
  - **Hold OK directly on the Volume button** (even when the pill is closed, as well as when open) to toggle **Mute / Unmute** instantly.
- **Unified Smoked-Glass Cards & High-Contrast Readability Over Wallpapers**:
  - Lightweight zero-blur smoked-glass dark capsules (`1dp` rim at rest, crisp white border on focus) unify thumbnails and metadata across **Home** cards (streams, multi-streams, continue watching, videos, clips, categories, and channel tiles), **Popular / Categories / Following** grids, and **Statistics** panels.
  - Added rounded smoked-glass column panels in **Settings**, frameless soft-feathered **Home** spotlight banner, pinned footer in the **Phone QR** upload modal, and focus-scale edge padding in the **Wallpaper** gallery.

### Fixes
- **Restored Video Swap Adblock Across Player, Multi-Stream, PiP & Previews + Fixed Post-Ad Black Screen**:
  - Video Swap backup streams (`android/autoplay`, `web/picture-by-picture`, `web/embed`, `web/thunderdome`, `web/popout`) now request playback tokens **anonymously** across the **main player**, **Multi-Stream**, **PiP**, and **live card previews** (stripping the account OAuth token that caused Twitch to stitch account-targeted ads onto backups) while keeping the main stream authenticated in the background for Channel Points, Drops, and Sub status.
  - Added immediate pre-roll bypass & periodic silent backup re-probing during active ad pods, prevented main hardware decoder thrashing when falling back to ad-hiding, and added rendered-frame stall recovery when returning to live.
- **Fixed Random Stream Reopening or "Playing" Badge After Exiting & Restarting the App**:
  - When **Keep sound when leaving the app** is disabled, leaving or closing Xtra TV now stops playback, clears `TvPlaybackState`, and purges saved playback state immediately in `onStop()` / exit instead of waiting for a delayed callback that Android TV's process freezer could interrupt; cold starts always open cleanly on Home.
- **Fixed Watched Live Streams Appearing as Viewed VODs in "Continue Watching"**:
  - **Ongoing recording filter**: `TvVodHistorySync` queries Twitch GraphQL `Video.status` and active `owner.stream` metadata to ignore broadcasts still being recorded (`status != RECORDED`, `404_processing` thumbnails, or currently airing `archiveVideo`s).
  - **Local live-session guard**: remembers live broadcasts opened in Xtra TV (single player, stream switcher, PiP, and Multi-Stream) so Twitch's auto-recorded `currentUser.viewedVideos` entry never surfaces on its own in **Continue watching** even after the stream ends, unless you explicitly open that VOD yourself.

### Install
Download `XtraTV-1.4.6.9-tv.apk` below and install it over the previous version, your settings and account are kept.
