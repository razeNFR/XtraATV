# Xtra TV 1.2.14

### Fixes
- **Back button with the small window (PiP)**: pressing Back on the remote while the small window is open now always hides the player controls first (if shown) and then closes only the small window while keeping the full-screen main stream open, instead of closing both streams and returning to the home screen (including on Android 13 / 14+ devices).
- **In-app update detection, manual updates & install permission**:
  - Comparing the GitHub release version with the installed version (`BuildConfig.VERSION_NAME`) so the update pop-up no longer appears if you already updated the app manually.
  - If the app lacked the *"Install unknown apps"* permission or the installation was interrupted, **Check for updates** in Settings now always finds the update again and lets you re-download it.
  - The *"Install unknown apps"* permission is now requested only when clicking **Update** (instead of colliding with the update dialog), and the download starts automatically once granted.

### Install
Download `XtraTV-1.2.14-tv.apk` below and install it over the previous version, your settings and account are kept.
