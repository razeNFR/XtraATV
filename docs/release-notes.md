# Xtra TV 1.4.6.6

### New & Polish
- **Hardware-overlay Mini-Player, 1440p glitch fix & UI performance boost**:
  - Kept the minimized player on its zero-GPU **`SurfaceView` Hardware Overlay** whenever at `100 %` opacity and automatically capped minimized playback to **`≤ 1080p`** (restoring `1440p` / Source immediately when reopening full-screen), fixing display corruption on 1440p60 streams on Amlogic boxes (such as Onn 4K Pro Gen 2).
  - Eliminated the full-screen `ViewGroup` alpha FBO layer during `20 %` mini-player ghost mode, removed the global per-pixel `OnScrollChangedListener`, and restricted `FocusCard` coordinate tracking on **Home** strictly to the single focused card when the mini-player is open.
- **Crisp anti-aliased badges on VOD & Live stream cards**: fixed `TextWithShadow` drawing badge text 7 times on top of itself on TV — VOD and Live badges (*Past broadcast*, views, viewers, duration, uptime, dates) now render in a single anti-aliased pass matching the Home screen while cutting badge text draw calls by `7×`.
- ***Polls (chat hidden)* overlay matching Predictions**: added **Polls (chat hidden)** (**Off** / **On (30s)** / **On (full voting)**) in the live player **Chat > Options** panel and in **Settings > Chat**, displaying active poll choices, live vote percentages/counts, countdown timer, and final winner summary in the top-right overlay while chat is hidden.
- ***Points & Drops* panel refinements**: active Drop campaigns (`< 100 %`) now display as clean non-clickable progress cards (only `100 %` ready-to-claim Drops are clickable buttons), and opening the panel keeps the scroll anchored at the top so your Channel Points balance and header remain visible.
- ***Press `OK` to adjust* Sliders in Settings**: sliders in the two-column **Settings** screen now require pressing **`OK`** before `Left / Right` changes their value (`◀ value ▶` accent badge; `OK` or `Back` confirms), so pressing **Left** while browsing settings returns cleanly to the left category list without accidentally altering slider values.
- **Cleaner Chat toggle**: removed the redundant *"Chat shown"* / *"Chat hidden"* OSD popup when toggling chat visibility.

### Install
Download `XtraTV-1.4.6.6-tv.apk` below and install it over the previous version, your settings and account are kept.
