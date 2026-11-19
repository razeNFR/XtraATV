# Xtra TV 1.4.6.1

### Fixes & Polish
- **Faster VOD startup & flicker-free VOD seeking**: bypassed redundant `usher` `403` round-trips on subscriber-restricted VODs by starting directly on CDN playlists from `restricted_bitrates`, prepared VOD playback directly at the saved resume offset with pre-applied resolution hints, sped up live-stream DVR VOD discovery via lightweight `Range` probes on primary servers, and prevented the progress bar from briefly snapping back to `00:00` while buffering after a seek.
- **Self-healing VOD Chat & working *Restart chat* on VODs**: rewrote `ChatReplayManager` on a single main-thread state machine with a `1s` self-healing sync watchdog, exact `contentOffsetSeconds` timing, instant `12s` context preload on start/seek/restart, automatic mini-player/screensaver recovery, and proper VOD startup when clicking **Restart chat**.
- **Muted VOD segments highlighted on the seek bar**: extracts muted audio intervals via Twitch's VOD mute metadata (with HLS playlist fallback) and renders them in coral-red over the VOD timeline while keeping game chapter markers and the scrubber thumb clearly visible.
- **Kodi-style seek skip indicator (`+5:26` / `-17:38`) & smoother D-pad seeking**: displays the exact signed skip offset and target timestamp in a floating pill above the scrubber, smooths long-press seek acceleration into predictable steps, and leaves a brief adjustment window so you can fine-tune jumps on long VODs before seeking once.
- **Cleaner TV Settings descriptions & focused Switch contrast**: fixed oversized line spacing on preference descriptions (`16sp` compact line height with an accent guide bar and no mid-sentence truncation), separated current option status/values into crisp badges without focus-driven row expansion, and added high-contrast styling for unchecked switches on white focused cards.

### Install
Download `XtraTV-1.4.6.1-tv.apk` below and install it over the previous version, your settings and account are kept.
