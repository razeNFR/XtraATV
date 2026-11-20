# Xtra TV 1.4.6.2

### New & Polish
- **`Back (long press)` shortcut & *Open minimized player* action**: added **Back (long press)** to *Directional pad shortcuts* (defaulting to **Open minimized player**) so you can instantly reopen a mini-player or audio-only stream into full screen from anywhere in the app without going back through the side menu, and made **Open minimized player** & **Stop stream** assignable to any directional or custom remote key.
- ***Switch chat display mode* remote shortcut**: added a dedicated remote shortcut action to cycle chat display modes (**Overlay** → **Next to the stream** → **Under the stream**) on the fly, while preserving each mode's own preferred corner/side/band position independently.
- **Grouped *Short press* / *Long press* D-pad shortcuts panel**: organized *Directional pad shortcuts* into clear **Short press** and **Long press** sections for faster setup.
- **Smart mini-player focus dodge**: smoothly glides the TV mini-player between the bottom-right and top-right corners with hysteresis whenever the focused card or setting row enters its area across both Compose and RecyclerView screens.
- **App-wide Twitch-style HUD-aware bottom-center OSD pill**: unified every `Toast` notification across the entire TV app (adblock video-swap, proxy switching, remote shortcuts, follow/unfollow, favorites, blacklist, sleep timer, multi-stream, polls/predictions, channel points, and backup/settings feedback) into a sleek bottom-center OSD pill with contextual icons that stays at the bottom on menus/dialogs or when player controls are hidden, and automatically lifts above the player HUD when controls are shown (while keeping the top-right adblock progress badge untouched).
- **Reliable cross-Android Backup & Restore with true file overwrite**: added automatic file-access permission verification on **Save to the TV** / **Restore from the TV** across Android 7 through Android 14+ (guiding the user to the exact system permission screen with TV-ROM fallbacks and auto-resuming once granted), atomic `.tmp` + `fsync` in-place overwrite of `Download/XtraTV-backup.zip` without `(1).zip` duplicates, strict all-or-nothing archive validation, and safe playback-service shutdown before Room SQLite replacement.

### Install
Download `XtraTV-1.4.6.2-tv.apk` below and install it over the previous version, your settings and account are kept.
