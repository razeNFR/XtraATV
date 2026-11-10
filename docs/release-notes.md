# Xtra TV 1.2.14

### Fixes & Improvements
- **Back button with the small window (PiP)**: pressing Back on the remote while the small window is open now always hides the player controls first (if shown) and then closes only the small window while keeping the full-screen main stream open, instead of closing both streams and returning to the home screen (including on Android 13 / 14+ devices).
- **In-app update detection, manual updates & install permission**:
  - Comparing the GitHub release version with the installed version (`BuildConfig.VERSION_NAME`) so the update pop-up no longer appears if you already updated the app manually.
  - If the app lacked the *"Install unknown apps"* permission or the installation was interrupted, **Check for updates** in Settings now always finds the update again and lets you re-download it.
  - The *"Install unknown apps"* permission is now requested only when clicking **Update** (instead of colliding with the update dialog), and the download starts automatically once granted.
- **Player HUD & option panels**:
  - Opening an option panel from the HUD (Chat layout, Small window, Dimming, Proxy, Settings, Quality…) now keeps the HUD visible underneath with its auto-hide timer paused, instead of hiding and fading back in when closing the panel. The chat and small window stay properly lifted above the HUD while in a bottom corner and move back down naturally when the HUD hides.
  - Opening the **Chat layout** panel while the chat is hidden no longer forces the chat to open; you can configure the chat layout while keeping the chat hidden.
  - Pressing a HUD button to open an option panel now immediately hides the button's tooltip overlay so it does not remain visible behind the panel.
- **DVR / VOD playback from a live stream**:
  - Added a **LIVE** button next to the playback controls when watching a live stream's VOD archive (DVR) to jump right back to the live broadcast; the button is hidden on regular VODs without an active live stream.
  - Fixed the progress bar jumping back to `00:00` and taking several seconds to move to the chosen position when going back in time into a live stream's VOD, and avoided waiting for the chat replay lookup when the archive video ID is already known from the live storyboard.
- **Home screen improvements**:
  - **Latest videos from followed channels**: cards now display the watch progress bar and resume from where you left off, support the long-press actions menu (*Resume*, *Play from the beginning*, *Remove from history*), and include a new **Open channel** action (also added to the *Continue watching* row).
  - **Focus restoration**: returning to the Home screen after opening a stream, VOD, channel, or category now restores the D-pad focus to the exact card you came from (or falls back safely if that card is no longer in the row).
  - **VOD progress bars**: inset slightly from the bottom and side edges of the thumbnail with rounded corners (`2dp`) on both the Home screen and video lists so the focus border never covers the progress bar.
- **Categories & Followed Channels**:
  - **Categories performance**: pre-blurred category card backgrounds are now cached in memory instead of running live GPU `RenderEffect` blur passes on every frame while scrolling, and hidden tag views are no longer inflated on TV, eliminating scroll lag on the Categories page.
  - **Followed Channels focus**: replaced the translucent card surface and disabled hardware elevation shadow outlines on focus to remove the dark rectangular box behind focused channel cards.
- **Search screen redesign on TV**:
  - Replaced the legacy mobile search header (grey band, divider line, back arrow, 3-dots menu) with the unified TV header (search bar, voice input, clock, and section pills).
  - Removed the unnecessary **Refresh** button from the Search screen.
  - Channel search results now use the same 6-column avatar card design as **Followed Channels** (with live ring, `LIVE` badge, and favorite star).
  - Restyled recent search history items as TV pills and made the **Delete** (trash) button reachable with D-pad Right.
- **Settings navigation & responsiveness**:
  - Pressing D-pad Down at the bottom of the right-hand Settings lists (*Xtra TV*, *Player*, etc.) now stays at the bottom of the list instead of jumping back into the left category column.
  - Reduced the focus-switch debounce delay from `300–400ms` to `60ms` (and removed pager transition delay on tab switch) for instant, responsive navigation across Settings categories, tabs, and the side menu when *"Change page without OK"* is enabled.
- **Home statistics (`TvStats`)**:
  - Watch time now continues to be counted while the ad blocker's backup player is active, and is also counted in Multi-stream (once per 30s tick for the active/focused stream, never multiplied by the number of screens).
  - The streams counter is no longer incremented when restarting the same stream (toggling proxy, returning to live from DVR).
  - Backup proxies and player types are now only counted when they actually start playing without ads.

### Install
Download `XtraTV-1.2.14-tv.apk` below and install it over the previous version, your settings and account are kept.
