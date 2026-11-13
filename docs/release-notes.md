# Xtra TV 1.3.8

### Fixes & stability
- **Eliminated silent black-screen dropouts during long live sessions**: disabled Android's mobile headphone-unplug listener (`ACTION_AUDIO_BECOMING_NOISY`) on TV so Bluetooth keyboards, remotes, audio peripherals going to sleep, or HDMI audio resyncs can never silently pause playback in the background while chat stays connected; fixed `MediaSession.onPlay()` so an explicit `Play` command never toggles a playing stream into pause; and added a lightweight automatic live-stream watchdog & resilient token/playlist backoff recovery that gently resumes stalled or idle live streams at live edge without interfering with manual pause, ad blocking, quality changes, or genuine stream endings.

### Install
Download `XtraTV-1.3.8-tv.apk` below and install it over the previous version, your settings and account are kept.
