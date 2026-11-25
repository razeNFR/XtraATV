# Xtra TV 1.4.7

### New & Polish
- **Adjustable Smoked-Glass Card Opacity & Unified Wallpaper Polish (Zero-Blur for Android TV Boxes)**:
  - Added a **Card opacity** slider (`30–95%`, default `65%`) with a compact live card & pill preview in **Settings > Xtra TV > Customization > Wallpaper > Adjustments**, applied **only when a wallpaper is active** (leaving the app's default background appearance strictly untouched) and saved in Xtra TV backups.
  - **Unified translucent smoked-glass surfaces across all TV screens** when a wallpaper is enabled:
    - **Home**: `HomeCardCapsule` cards, **Stats** card, and **See All** cards adopt a neutral dark smoked-glass base with a zero-blur vertical specular rim and subtle top sheen; **Refresh**, **Stats badge**, **Hero** buttons, and the **Hero** viewer badge switch to unified smoked-glass pills, **Greeting & Clock** becomes frameless with soft text shadow, **Hero** accent text is brightened for legibility, and a soft elliptical halo sits seamlessly behind the left Hero text block.
    - **XML Grids**: **Followed channels** (subtle opacity hierarchy between live and offline channels), **Categories & Followed categories** (lightened card shade overlay so blurred box art and wallpaper peek through), **Popular**, **Saved**, and **Search** (channels + recent search pills).
    - **Statistics & Settings**: removed the extra full-screen dark wash on **Statistics** and linked **Statistics** tiles/panels and **Settings** columns to the card opacity slider.
    - **Channel page & Headers**: **About** and **Schedule** cards switch to smoked glass for crisp legibility over bright wallpapers, `TvHeader` titles/clock/sort receive a subtle text shadow, and all header pills (**Refresh**, **Sort**, **Manage**, etc.) share a unified smoked-glass style across every screen.
  - **Zero per-frame GPU overhead**: subtle top and left readability scrims are pre-baked once into cached wallpaper bitmaps with zero real-time blur passes on scrolling cards.
- **Custom Wallpapers: True-Black Dimming, 3-Level Ambient Color Tint & Redesigned "Studio Glass" Phone QR Portal**:
  - **True-black Visibility dimming**: fixed custom wallpaper **Visibility** so lowering it darkens luminance smoothly toward pure black instead of applying a muddy gray veil — at `100%`, custom photos retain full original brightness and OLED black contrast.
  - **3-level Ambient Color Tint (`Off` / `Subtle` / `Full`)**: new option in **Wallpaper > Adjustments** for custom images that grades your photo to match the active **Ambient color** — works with both fixed colors and live **Dynamic** ambiance crossfades, and is included in Xtra TV backups.
  - **Redesigned "Studio Glass" Phone QR Web Portal**: modern dark mobile interface dynamically tinted with the TV's ambient color, featuring 3 tabs (**Photos**, **URL**, **On TV**), styled multi-photo picker with client-side 16:9 preview queue and progress bar, live 16:9 URL preview, and an **On TV** gallery to **Apply** or **Delete** custom wallpapers directly from your phone with instant live sync on the TV.
- **Home — "See All" on Recommended Channels**:
  - Added a **See All** card to the **Recommended channels** row on Home, opening the full live streams grid directly in **Recommended** sort mode without overwriting your saved **Popular** sort or language preferences.
- **Twitch Channel Offline Screen & Auto-Reconnect in Player, Multi-Stream & PiP**:
  - **Main TV Player**: when a live stream goes offline, the buffering spinner disappears immediately and the player displays the streamer's actual **Twitch offline screen** (`offlineImageURL`, or a dark banner/ambient fallback when unset) with their **profile picture**, **username**, and **Offline** status badge.
  - **Automatic live reconnection**: detects when the streamer comes back live (instantly via Hermes `stream-up`, plus a lightweight background check when chat is disabled) and automatically reconnects the stream while keeping the offline screen visible until the streamer returns or you leave the player.
  - **Seamless integration with "When live stream ends"**: works cleanly alongside **Return to Home**, **Switch to another live channel** (offline screen shown behind the 10 s countdown; pressing **Back** to stay keeps the offline screen and auto-reconnect active), **Stay on player**, and automatic raids.
  - **Multi-Stream & PiP support**: both Multi-Stream tiles and PiP windows now honor **When live stream ends** — in **Stay on player** mode they show a compact Twitch offline screen and auto-reconnect if the streamer returns; in **Return to Home** / **Switch** modes they cleanly remove the ended tile or close the ended PiP window after the grace period instead of staying frozen on a still frame.

### Install
Download `XtraTV-1.4.7-tv.apk` below and install it over the previous version, your settings and account are kept.
