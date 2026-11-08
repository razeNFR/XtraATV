# Xtra TV 1.2.13

### New
- **Stream details on the selected multi-stream frame**: the selected stream now shows its viewers, video quality, live duration and a scrolling single-line title over a soft bottom gradient, fading out after 5 seconds with the frame and controls.
- **Same-category suggestions and search in the multi-stream picker**: **Suggested** now lists live streams from the same category sorted by viewers, and a **Search** tab lets you find any live channel directly.
- **Scrolling category titles**: long game and category names now scroll smoothly on the focused card instead of being cut off.
- **Settings backup by file or QR code**: settings can now be saved directly to the TV and transferred to or from a phone over Wi-Fi by scanning a QR code (without the Twitch account).
- **About section in Settings**: new **About** (`À propos`) menu showing the app version, credits (`XtraTV forked from https://github.com/crackededed/Xtra by razeN with AI`), device information, update check, and a QR code to the GitHub repository.

### Fixes
- **Audio normalization during ad-blocking**: audio normalization (`DynamicsProcessing`) now stays active when switching to the backup player (Proxy / VAFT) during an ad break.
- **Faster ad-block transitions & reliable "Back to live…" progress bar**: synced the background main player directly to the clean live edge before switching back so there is no old frame, no lag, and the "Back to live…" badge never gets stuck.
- **Geo-blocked streams on proxies (HTTP 403)**: proxies returning HTTP 403 on a geo-blocked channel are now skipped immediately for that channel only (falling back to direct connection / VAFT without delay) without marking the proxy as offline for other channels.
- **Twitch WebView login on TV**: removed the unreachable *"Having trouble logging in?"* button on TV during WebView sign-in and added automatic scrolling/focus + D-pad scrolling so the **Authorize** button can always be reached.
- **Settings backup on TVs without a file picker**: backing up or restoring settings no longer fails with "No supported app installed".

### Install
Download `XtraTV-1.2.13-tv.apk` below and install it over the previous version, your settings and account are kept.
