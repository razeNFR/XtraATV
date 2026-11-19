# Xtra TV 1.4.6

### New
- **Channel, Category & Keyword/Tag Blocking (*Hidden content*)**: hide unwanted channels, categories/games, or stream title/tag keywords across TV Home, Categories, Popular, and Following feeds — either in 1 click by holding **OK** on any stream or category card, or from the new **Hidden content** 3-tab panel (*Channels*, *Categories*, *Keywords / Tags*, with voice input support) in **Xtra TV settings**, stored per Twitch account and included in backups.
- **Interactive Polls & Predictions on TV**: a temporary **Poll & Prediction** button automatically appears in the player controls strictly while a Twitch Poll or Channel Points Prediction is live on the current stream, opening a real-time interactive side panel with countdown bars, live vote/pool percentages, 1-click Poll voting (including extra Channel Points votes when enabled), and Channel Points Prediction wagering (*100 / 500 / 1K / 5K / 25% / All-in / Custom*).
- **Integrated Side Rail Pages (*Search*, *Statistics*, *Settings*) & Unified Compose TV Settings**: **Search**, **Statistics**, and **Settings** are now first-class root pages inside the main TV navigation container — working seamlessly with **Switch categories without pressing OK** (hover preview without stealing focus), keeping the minimized floating player visible on screen across every page, and introducing a unified 2-column Google TV Compose **Settings** screen with instant opening, 60fps category scrolling, and global settings search.
- **2-Stream Multi-Stream *Stacked Center* Layout**: added a dedicated 2-stream layout stacking both `16:9` streams vertically in the center with full-height chats open on the left and right sides (alongside the side-by-side layout), plus a 1-click **Swap left / right chats** toggle.
- **Warm Light Filter (*Night Shift*)**: added a warm amber color-temperature slider (`0–80%`) directly inside the **Screen dimming** menu, independent of and combinable with screen dimming (`0–90%`).
- **Predictions (chat hidden) — Full Voting Mode**: upgraded **Predictions (chat hidden)** in the Chat layout panel to a 3-choice selector: *Disabled*, *On (30s)*, and *On (full voting)* — keeping the prediction banner visible while voting is open, hiding it as soon as bets lock, and showing final results for `15s` when resolved.
- **Restart Player & Restart Chat Shortcuts**: added **Restart player** and **Restart chat** to customizable D-pad and remote key shortcuts, plus a **Restart chat** action in the Live Chat layout panel and VOD/Clip settings menu.
- **UI & Leanback Polish**:
  - **Smooth side-rail transitions**: subtle `120 ms` crossfade when switching sections from the TV side rail.
  - **Dynamic Channel page ambient glow**: opening a streamer's page on TV derives a soft ambient background tint from their channel banner or avatar without flickering when browsing their VODs and clips.
  - **Discreet *Playing* / *PiP* badges on stream cards**: stream thumbnails on TV Home, stream grids, and the Streamer Switcher display a discreet **Playing** or **PiP** (`PiP 1` / `PiP 2`) pill when active.
  - **Direct category long-press menu & cleaner stream card menu**: holding **OK** on a category card now opens its actions panel directly (no intermediate *More actions* popup), and stream card long-press menus no longer clutter browse screens with *Create clip (30s)* or *Open on phone (QR code)* (kept inside the player).

### Fixes
- **Bluetooth keyboard / mouse wake-up blackout**: waking or reconnecting a Bluetooth keyboard, air-mouse, or controller no longer recreates the activity, blacks out the video player, or restarts the chat.
- **VOD chat freezing after long skips or pauses**: seeking far ahead or resuming after a long pause in a VOD now cleanly cancels stale fetches, resets replay state, and immediately resumes chat messages at the new timestamp.
- **Channel Follow silently reverting on specific channels**: parsed Twitch's `followUser.error` (`FORBIDDEN` / verification required) response instead of falsely reporting success, immediately rolling back optimistic follow state and displaying a clear explanation toast.
- **Multi-streamer Live notification banner**: fixed text wrapping/splitting and missing avatars when multiple followed streamers go live simultaneously, displaying up to 3 overlapping circular profile pictures and a crisp single-line `LIVE` badge.
- **Settings backup file overwrite**: exporting a backup to a folder that already contains `xtra_backup.zip` now overwrites the existing archive in place instead of creating `(01)` / `(1)` duplicates.
- **Side-rail focus & Settings smoothness**: fixed side-rail focus restoration when pressing Left/Back from **Statistics** or **Settings**, prewarmed XML preference trees in the background for instant **Settings** opening, and eliminated mini-freezes when scrolling the left category list.

### Install
Download `XtraTV-1.4.6-tv.apk` below and install it over the previous version, your settings and account are kept.
