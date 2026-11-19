# Xtra TV 1.4.6.1

### Fixes & Polish
- **Ultra-smooth left side-rail navigation (*Switch categories without pressing OK*) & zero Home shift**: decoupled the TV side rail from right-side page inflation by keeping top-level sections (*Search*, *Home*, *Categories*, *Popular*, *Following*, *Saved*, *Statistics*, *Settings*) alive in memory via zero-inflation `show` / `hide` transitions, background idle pre-warming of unvisited tabs, `offscreenPageLimit = 1` on TV pagers, input-aware silent background data refresh (`stale-while-revalidate` after `20s`), and per-screen horizontal insets so switching between *Home* and other categories never shifts or drops frames.
- **Self-healing VOD Chat & working *Restart chat* on VODs**: rewrote `ChatReplayManager` on a single main-thread state machine with a `1s` self-healing sync watchdog, exact `contentOffsetSeconds` timing, instant `12s` context preload on start/seek/restart, automatic mini-player/screensaver recovery, and proper VOD startup when clicking **Restart chat**.
- **Cleaner TV Settings descriptions & focused Switch contrast**: fixed oversized line spacing on preference descriptions (`16sp` compact line height with an accent guide bar and no mid-sentence truncation), separated current option status/values into crisp badges without focus-driven row expansion, and added high-contrast styling for unchecked switches on white focused cards.

### Install
Download `XtraTV-1.4.6.1-tv.apk` below and install it over the previous version, your settings and account are kept.
