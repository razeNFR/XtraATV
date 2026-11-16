# Xtra TV 1.4.3.1

### New
- **"Recommended for you" sort**: when logged in, categories (*Categories → Sort*), the streams of a category, and Top streams can now be sorted the way Twitch recommends them for your account (Twitch's `RELEVANCE` order, the same as *Recommended For You* on the website). The option only shows up when logged in, since Twitch gives the same order as *Viewers* otherwise; the default sort is unchanged.

### Fixes
- **In-app update loop on Google TV / Android TV** (#72): pressing *Update* could keep sending you back to *Install unknown apps* although it was already allowed, without ever installing the update.
  - The *Install unknown apps* settings now open at most once an hour, and this is remembered even when the TV closes the app while its settings are open (before, the app could start again from scratch and open them again, in a loop). When you come back, the download starts by itself.
  - If Android's installer still refuses, the next try opens the downloaded APK with Android's installer directly, the other way Android has to install an app.
  - If both fail, a dialog offers to download the update in the browser, to open the settings, or to try again, and shows what Android answered (Android version, permission state, installer status): a photo of it is enough to report the problem.

### Install
Download `XtraTV-1.4.3.1-tv.apk` below and install it over the previous version, your settings and account are kept.

If the in-app update keeps looping on your TV, install this version once by hand (browser, Downloader or `adb install -r`): the fix takes effect for the updates after it.
