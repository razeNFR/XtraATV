# Xtra TV 1.3.2

### What's new
- **Slow chat**: limit fast chats to **1, 2, 4 or 6 msg/s** from the player chat panel (right under *Position*) or Chat settings, while always keeping broadcaster, moderator, VIP, staff, redeem/bits and own/reply messages.
- **Chat panel**: *Chat delay* is now right below *Slow chat* with a concise latency line, and *Reset* also resets *Slow chat*.
- **Chat restrictions & feedback**: the *Write a message* button now displays a small lock icon on restricted channels (*followers-only* / *subscribers-only*), unlocks automatically in real time as soon as the restriction is lifted or follow wait time expires, and shows a clear Toast whenever a message cannot be sent or is rejected by Twitch.
- **Inline emotes in composer**: emotes inserted in the TV chat input field are now rendered directly as images instead of raw text codes, with single-press Backspace deletion.
- **Edge-to-edge Home rows & side menu**: Home horizontal rows glide all the way to the rail and screen edge with a smooth horizontal gradient fade, and the side menu is slightly wider with clean rounded corners over the colored ambiance glow.
- **PiP window positions**: added **Top center**, **Middle left**, **Center**, **Middle right**, and **Bottom center** positions for the small stream window alongside the 4 corners.
- **About & Discord**: added a **Discord** entry (`discord.gg/SgKqrW73P4`) with QR code in *Settings → About*.
- **Player defaults**: custom proxies are now off by default (VAFT handles ads out of the box), the *Proxy* button is enabled by default in the player, and *Dimming* is off by default.
- **Ad-block badge**: VAFT sources now show their resolution in the badge (e.g. `autoplay (360p)`).

### Fixes
- **Stream playback & decoder recovery**: release previous stream and background preview decoders cleanly before opening a new stream, wait for TV surface readiness, and automatically fall back to H.264 1080p if an AV1/HEVC/1440p hardware decoder fails.
- **Android TV 14 in-app update**: open Android TV 14's *Install unknown apps* / *Security* settings directly before downloading an update instead of triggering `PackageInstaller`'s broken *Settings* button crash.
- **Dynamic ambiance color**: warm skin tones, tungsten lighting and neutral dark thumbnails no longer skew the dynamic ambiance background toward muddy brown.
- **About dialog layout**: made the *About* panel scrollable with a compact QR card so the *Close* button text is never clipped on 1080p TV screens.
- **Multi-stream & switcher**: local followed channels now appear in *Followed live* and *Suggested* without logging in, and followed live channels are listed before category streams in *Suggested* (#53).
- **Ad-block fallback**: check all segments of the backup player's first playlist and fall back to hiding the ad if every backup source fails.

### Install
Download `XtraTV-1.3.2-tv.apk` below and install it over the previous version, your settings and account are kept.
