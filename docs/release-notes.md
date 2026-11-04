# Xtra TV 1.2.10

### Fixes
- **Screensaver during a stream**: the screensaver of some launchers (Monet) started while a stream, a video or a multi-stream played, and stopped it. Xtra TV now shows its playback to the system like the usual players (audio focus held while it plays, film sound, standard media session), so these screensavers stay away.
- **Stream stopped by a screen shown over the app**: a screen shown over Xtra TV for a moment (a screensaver giving up at once) no longer stops the stream. The app really left still stops it, a few seconds later.
- **Frozen ad picture after an ad**: after an ad replaced by the ad blocker or a proxy, the stream could stay stuck on a picture of the ad. The stream now comes back only once it plays the stream itself (as VAFT does), and if its picture stays still after an ad it starts again at the live edge.
- **"Pause when other apps play audio"**: with this setting on, the stream no longer pauses itself when the ad blocker starts its backup stream.
- **Playback notification**: sent again only when something in it changes, instead of at every refresh of the stream.

### Install
Download `XtraTV-1.2.10-tv.apk` below and install it over the previous version, your settings and account are kept.
