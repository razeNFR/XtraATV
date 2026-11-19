# Xtra TV 1.4.6.1

### Fixes & Polish
- **Ultra-smooth left side-rail navigation (*Switch categories without pressing OK*) & zero Home shift**: decoupled the TV side rail from right-side page inflation by keeping top-level sections (*Search*, *Home*, *Categories*, *Popular*, *Following*, *Saved*, *Statistics*, *Settings*) alive in memory via zero-inflation `show` / `hide` transitions, background idle pre-warming of unvisited tabs, `offscreenPageLimit = 1` on TV pagers, input-aware silent background data refresh (`stale-while-revalidate` after `20s`), and per-screen horizontal insets so switching between *Home* and other categories never shifts or drops frames.
- **Self-healing VOD Chat & working *Restart chat* on VODs**: rewrote `ChatReplayManager` on a single main-thread state machine with a `1s` self-healing sync watchdog, exact `contentOffsetSeconds` timing, instant `12s` context preload on start/seek/restart, automatic mini-player/screensaver recovery, and proper VOD startup when clicking **Restart chat**.
- **Muted VOD segments highlighted on the seek bar**: extracts muted audio intervals via Twitch's VOD mute metadata (with HLS playlist fallback) and renders them in coral-red over the VOD timeline while keeping game chapter markers and the scrubber thumb clearly visible.
- **Kodi-style seek skip indicator (`+5:26` / `-17:38`) & smoother D-pad seeking**: displays the exact signed skip offset and target timestamp in a floating pill above the scrubber, smooths long-press seek acceleration into predictable steps, and leaves a brief adjustment window so you can fine-tune jumps on long VODs before seeking once.
- **Cleaner TV Settings descriptions & focused Switch contrast**: fixed oversized line spacing on preference descriptions (`16sp` compact line height with an accent guide bar and no mid-sentence truncation), separated current option status/values into crisp badges without focus-driven row expansion, and added high-contrast styling for unchecked switches on white focused cards.

### Install
Download `XtraTV-1.4.6.1-tv.apk` below and install it over the previous version, your settings and account are kept.
