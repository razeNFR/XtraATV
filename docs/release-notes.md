# Xtra TV 1.4.3

### Fixes
- **Settings & data backup / restore**: fixed backup and restore failing on Android 10+ (Scoped Storage / system file picker `content://` URIs, overwriting existing backups after an app reset, and in-memory preferences overwriting restored settings on restart), and included TV favorite channels and watch statistics in the backup archive.
- **Buffer settings safety**: prevented an ExoPlayer startup crash loop when custom buffer or live playback speed values violate Media3 bounds (e.g. `Min buffer` set lower than `Rebuffer` or `Buffer for playback`).

### Install
Download `XtraTV-1.4.3-tv.apk` below and install it over the previous version, your settings and account are kept.
