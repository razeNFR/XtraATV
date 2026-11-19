# Xtra TV 1.4.6.1

### Fixes & Performance
- **Ultra-smooth left side-rail navigation (*Switch categories without pressing OK*)**: decoupled the TV side rail from right-side page inflation by keeping top-level sections (*Search*, *Home*, *Categories*, *Popular*, *Following*, *Saved*, *Statistics*, *Settings*) alive in memory via zero-inflation `show` / `hide` transitions, background idle pre-warming of unvisited tabs, `offscreenPageLimit = 1` on TV pagers, and input-aware silent background data refresh (`stale-while-revalidate` after `20s`) so scrolling through categories never freezes or drops frames while always keeping live content fresh.

### Install
Download `XtraTV-1.4.6.1-tv.apk` below and install it over the previous version, your settings and account are kept.
