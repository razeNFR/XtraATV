# Xtra TV 1.4.6.8

### New & Polish
- **New TV Home Row — "Recently Watched Channels" (Last 20 Unique Channels)**:
  - Automatically records a live channel once watched for at least **`30 seconds`** (avoiding pollution while zapping quickly with **Left / Right**), keeping the **20 most recent unique channels** sorted newest-first across restarts and isolated per account.
  - **Filter mode**: choose between **All channels** (default) and **Unfollowed channels only** either in **Settings > Xtra TV > Home & Side menu** or directly from the row's options inside **Home rows**.
  - **Live status & smart action**: checks live status in a single batched GraphQL query — live channels display a glowing live ring, `LIVE` badge, current category, and viewer count, and open the **live stream** directly on **OK**; offline channels open the **channel page** on **OK**.
  - **Long-press OK** on any recent channel card offers standard channel actions (*Watch live*, *Open channel*, *Channel videos*, *Follow*, *Favorite*, *Hide*) alongside **Remove from recent** and **Clear recent channels**.
- **Two-Way Twitch Account VOD History & Resume Position Sync (PC ↔ Mobile ↔ TVs)**:
  - **Cross-device pull**: syncs `currentUser.viewedVideos` on Home load and `video.self.viewingHistory` when opening a VOD so broadcasts started on PC (`twitch.tv`), mobile, or another TV running Xtra TV appear in **Continue watching** and keep VOD progress bars up to date.
  - **Live position push & synced removal**: pushes VOD playback progress to Twitch (`updateUserViewedVideo`) periodically while watching and immediately on pause/close, and resets Twitch's saved position when choosing **Remove from history** or **Watch from the beginning**.
  - Added **Sync VOD history with Twitch** (**On** by default) in **Settings > Xtra TV > TV Player & Multi-stream**, with automatic local-only fallback when logged out or in Guest mode.
- **Redesigned Stream Switcher & Multi-Stream Tab Rail + Android 14 Flicker & Focus Fixes**:
  - **Unified Smoked-Glass Tab Rail**: wrapped category tabs inside a dark smoked-glass capsule rail with an ambiance-accented active tab pill + border when browsing cards and a high-contrast light pill when focused, keeping **Validate (n)** cleanly detached on the left in Multi-Stream mode.
  - **Instant, jump-free tab switching**: silently preloads uncached category tabs in the background when the switcher opens, keeps tab pill widths steady while loading, and updates stream cards smoothly via `DiffUtil` without blank thumbnail flashes.
  - **Fixed Android 14 bottom-edge flash / downward slide**: converted the switcher overlay to an edge-to-edge fullscreen dialog anchored at the bottom and locked the card container to a deterministic constant height (including on empty categories) so the surface never shifts downward when switching tabs.
  - **Native vector icons on all tabs**: every tab in both the **Other streams** switcher and **Multi-stream** picker now displays a matching Xtra TV vector icon (*Suggested*, *Current list*, *Stream Together*, *Followed*, *Favorites*, *Recommended*, *Popular*, *Categories*, *Languages*, and *Search*) tinted automatically for focused, active, and resting states.
  - **Restored Multi-Stream Up → Validate focus**: pressing **Up** from stream cards in Multi-Stream mode now jumps directly to **Validate** as soon as at least 1 extra streamer is selected, while returning to the active category tab otherwise.
- **TV Home Vertical Scrolling Smoothness & Focus Polish (NVIDIA Shield / Android TV)**:
  - **Smoother rapid Up / Down navigation**: replaced competing spring scroll coroutines with a single cancelled scroll job (`180ms` eased glide on single presses and instant lockstep scroll on rapid key repeat), hoisted shared card state flows (`TvFavorites`, `TvPlaybackState`, `TvCoStreamCache`) to the Home root, enabled `LazyColumn` `contentType` row reuse, skipped unused width animations/offscreen GPU layers at rest, and bypassed redundant main-thread `ExoPlayer.stop()` calls when passing over cards before preview start.
  - **Deterministic 1st-card focus on unvisited rows**: pressing **Down** from the full-width **Statistics** banner into **Categories** (or entering any unvisited horizontal row for the first time) now lands cleanly on the **1st card** instead of jumping to the middle of the row, while preserving last-focused card restoration on revisited rows with zero focus-crash risk.

### Install
Download `XtraTV-1.4.6.8-tv.apk` below and install it over the previous version, your settings and account are kept.
