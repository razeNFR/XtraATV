# Xtra TV 1.4.4

### New
- **Customizable *Other streams* & *Multi-stream* tabs**: choose, reorder, hide, or add any game/category or language tab in *Settings → Xtra TV → Other streams & Multi-stream tabs*, including **Recommended for you** (`RELEVANCE` sort, enabled by default when logged in).
- **Adblock Swap & HTTP Proxy in the player panel**: the *Ad blocker & proxies* panel now explicitly shows **Swap Adblock** (including when active by default) as well as the **HTTP Proxy** toggle, proxy list, and shortcut to proxy settings.
- **Audio normalization in Multi-stream**: when **Audio compressor** is enabled in the base player settings, multi-stream tiles automatically apply the same `DynamicsProcessing` audio normalization on the active audio stream.
- **Smooth live catch-up**: when paused or rewound behind live on a stream, playback speed (`1.25x` / `1.5x`…) becomes available in the player menu and automatically resets to `1.0x` once caught up to the live edge without altering your saved VOD speed.
- **Channel emotes tab**: added a dedicated **Channel** tab in the emote picker (single-stream and multi-stream) grouping the channel's Twitch follower/subscriber emotes and 7TV/BTTV/FFZ channel emotes.

### Fixes
- **Missing channel emotes in the emote menu**: fixed per-channel Twitch emote caching, prevented IRC `emote-sets` from overwriting channel follower emotes, and resolved missing `channelId` from `ROOMSTATE` / GraphQL so channel emotes reliably appear on every stream.
- **Animated emotes freezing in chat**: fixed `Drawable.Callback` garbage collection, absolute `uptimeMillis` frame scheduling delays, shared cached drawable collisions across rows, and `LayerDrawable` overlay emote callbacks so animated GIF/WebP emotes keep playing smoothly.

### Install
Download `XtraTV-1.4.4-tv.apk` below and install it over the previous version, your settings and account are kept.
