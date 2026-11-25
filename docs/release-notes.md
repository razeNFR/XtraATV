# Xtra TV 1.4.7

### New & Polish
- **Adjustable Smoked-Glass Card Opacity & Unified Wallpaper Polish (Zero-Blur for Android TV Boxes)**:
  - Added a **Card opacity** slider (`30–95%`, default `65%`) with a compact live card & pill preview in **Settings > Xtra TV > Customization > Wallpaper > Adjustments**, active **only when a wallpaper is enabled** (leaving the app's default background untouched) and included in Xtra TV backups.
  - **Unified smoked-glass look across every TV screen** when a wallpaper is active:
    - **Home**: stream, video, clip, category, stats, and **See All** cards adopt a dark smoked-glass surface with a zero-blur specular rim and subtle top sheen; the **Hero** banner gets a soft elliptical backdrop halo, brightened accent text, and a matching smoked-glass viewer badge; **Greeting & Clock** becomes frameless with subtle text shadow.
    - **All Grids & Pages**: unified smoked-glass cards across **Followed channels**, **Categories**, **Popular**, **Saved**, **Search**, **Statistics**, **Settings**, and **Channel** pages (**About** & **Schedule**), with consistent smoked-glass header pills (**Refresh**, **Sort**, **Manage**, etc.) on every screen.
  - **Zero per-frame GPU overhead**: subtle top and left readability scrims are pre-baked into cached wallpaper bitmaps with zero real-time blur passes on scrolling lists.
- **Custom Wallpapers — True-Black Dimming, 3-Level Ambient Color Tint & Redesigned "Studio Glass" Phone QR Portal**:
  - **True-black Visibility dimming**: lowering **Visibility** on custom wallpapers now dims luminance cleanly toward pure black instead of adding a gray veil — at `100%`, custom photos keep their full original brightness and OLED contrast.
  - **3-level Ambient Color Tint (`Off` / `Subtle` / `Full`)**: new option in **Wallpaper > Adjustments** for custom images to recolor your photo with the active **Ambient color** — works with both fixed colors and live **Dynamic** ambiance crossfades, and is preserved in Xtra TV backups.
  - **Redesigned "Studio Glass" Phone QR Web Portal**: modern dark mobile interface dynamically tinted with your TV's ambient color, featuring 3 tabs (**Photos**, **URL**, **On TV**), multi-photo picker with client-side 16:9 preview queue and progress bar, live 16:9 URL preview, and an **On TV** gallery to **Apply** or **Delete** custom wallpapers directly from your phone with instant sync on the TV.
  - **Wallpaper Panel Polish**: fixed focus-zoom clipping on the side edges of the **Adjustments** and **Rotation** tabs, and removed the duplicate QR upload button from **Adjustments**.
- **Home — "See All" on Recommended Channels**:
  - Added a **See All** button to the **Recommended channels** row on Home, opening the full live streams grid directly in **Recommended** sort mode without overwriting your saved **Popular** sort or language preferences.
- **Twitch Channel Offline Screen & Auto-Reconnect in Player, Multi-Stream & PiP**:
  - **Main TV Player**: when a live stream goes offline, the buffering spinner disappears immediately and the player displays the streamer's Twitch offline screen (or a dark ambient fallback when unset) with their profile picture, display name, and **Offline** badge.
  - **Automatic live reconnection**: detects when the streamer comes back live (instantly via live events, plus a lightweight fallback when chat is disabled) and automatically reconnects the stream.
  - **Full "When live stream ends" support in Main Player, Multi-Stream & PiP**: works seamlessly with **Return to Home**, **Switch to another live channel** (offline screen shown behind the 10 s countdown; press **Back** to stay and wait for reconnect), **Stay on player**, and automatic raids — Multi-Stream tiles and PiP windows now also show a compact offline screen in **Stay** mode or cleanly close/remove ended streams instead of freezing.

### Install
Download `XtraTV-1.4.7-tv.apk` below and install it over the previous version, your settings and account are kept.
