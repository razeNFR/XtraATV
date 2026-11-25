# Xtra TV 1.4.7

### New & Polish
- **Adjustable Smoked-Glass Card Opacity & Unified Wallpaper Polish (Zero-Blur for Android TV Boxes)**:
  - Added a **Card opacity** slider (`30–95%`, default `65%`) with a compact live card & pill preview in **Settings > Xtra TV > Customization > Wallpaper > Adjustments**, applied **only when a wallpaper is active** (leaving the app's default background appearance strictly untouched) and saved in Xtra TV backups.
  - **Unified translucent smoked-glass surfaces across all TV screens** when a wallpaper is enabled:
    - **Home**: `HomeCardCapsule` cards, **Stats** card, and **See All** cards adopt a neutral dark smoked-glass base with a zero-blur vertical specular rim and subtle top sheen; **Refresh**, **Stats badge**, and **Hero** buttons switch to airy translucent pills, **Greeting & Clock** becomes frameless with soft text shadow, and a gentle radial readability scrim sits behind the left Hero text block.
    - **XML Grids**: **Followed channels** (subtle opacity hierarchy between live and offline channels), **Categories & Followed categories** (lightened card shade overlay so blurred box art and wallpaper peek through), **Popular**, **Saved**, and **Search** (channels + recent search pills).
    - **Statistics & Settings**: removed the extra full-screen dark wash on **Statistics** and linked **Statistics** tiles/panels and **Settings** columns to the card opacity slider.
    - **Channel page**: **About** and **Schedule** cards switch to smoked glass for crisp legibility over bright wallpapers, and `TvHeader` titles/clock/sort receive a subtle text shadow.
  - **Zero per-frame GPU overhead**: subtle top and left readability scrims are pre-baked once into cached wallpaper bitmaps with zero real-time blur passes on scrolling cards.
- **Twitch Channel Offline Screen & Auto-Reconnect in Player, Multi-Stream & PiP**:
  - **Main TV Player**: when a live stream goes offline, the buffering spinner disappears immediately and the player displays the streamer's actual **Twitch offline screen** (`offlineImageURL`, or a dark banner/ambient fallback when unset) with their **profile picture**, **username**, and **Offline** status badge.
  - **Automatic live reconnection**: detects when the streamer comes back live (instantly via Hermes `stream-up`, plus a lightweight background check when chat is disabled) and automatically reconnects the stream while keeping the offline screen visible until the streamer returns or you leave the player.
  - **Seamless integration with "When live stream ends"**: works cleanly alongside **Return to Home**, **Switch to another live channel** (offline screen shown behind the 10 s countdown; pressing **Back** to stay keeps the offline screen and auto-reconnect active), **Stay on player**, and automatic raids.
  - **Multi-Stream & PiP support**: both Multi-Stream tiles and PiP windows now honor **When live stream ends** — in **Stay on player** mode they show a compact Twitch offline screen and auto-reconnect if the streamer returns; in **Return to Home** / **Switch** modes they cleanly remove the ended tile or close the ended PiP window after the grace period instead of staying frozen on a still frame.

### Install
Download `XtraTV-1.4.7-tv.apk` below and install it over the previous version, your settings and account are kept.
