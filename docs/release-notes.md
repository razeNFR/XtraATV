# Xtra TV 1.2.14

### Fixes & Improvements
- **Back button with the small window (PiP)**: pressing Back on the remote while the small window is open now always hides the player controls first (if shown) and then closes only the small window while keeping the full-screen main stream open, instead of closing both streams and returning to the home screen (including on Android 13 / 14+ devices).
- **In-app update detection, manual updates & install permission**:
  - Comparing the GitHub release version with the installed version (`BuildConfig.VERSION_NAME`) so the update pop-up no longer appears if you already updated the app manually.
  - If the app lacked the *"Install unknown apps"* permission or the installation was interrupted, **Check for updates** in Settings now always finds the update again and lets you re-download it.
  - The *"Install unknown apps"* permission is now requested only when clicking **Update** (instead of colliding with the update dialog), and the download starts automatically once granted.
- **Player HUD when opening an option**: opening an option panel from the HUD (Chat layout, Small window, Dimming, Proxy, Settings, Quality…) now keeps the HUD visible underneath with its auto-hide timer paused, instead of hiding and fading back in when closing the panel. The chat and small window stay properly lifted above the HUD while in a bottom corner and move back down naturally when the HUD hides.
- **DVR / VOD timeline when rewinding from a live stream**: fixed the progress bar jumping back to `00:00` and taking several seconds to move to the chosen position when going back in time into a live stream's VOD, and avoided waiting for the chat replay lookup when the archive video ID is already known from the live storyboard.
- **Home statistics (`TvStats`)**:
  - Watch time now continues to be counted while the ad blocker's backup player is active, and is also counted in Multi-stream (once per 30s tick for the active/focused stream, never multiplied by the number of screens).
  - The streams counter is no longer incremented when restarting the same stream (toggling proxy, returning to live from DVR).
  - Backup proxies and player types are now only counted when they actually start playing without ads.

### Install
Download `XtraTV-1.2.14-tv.apk` below and install it over the previous version, your settings and account are kept.
