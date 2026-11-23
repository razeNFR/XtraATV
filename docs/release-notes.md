# Xtra TV 1.4.6.7

### New & Polish
- **100 % `SurfaceView` Hardware-Overlay Mini-Player & smooth Right ↔ Left auto-slide**:
  - Kept the minimized player permanently on its native **`SurfaceView` Hardware Overlay** (eliminating all `SurfaceView` ↔ `TextureView` surface switches and `freeAllBuffers` stalls), fixing the NVIDIA Shield `OMX.Nvidia.h264.decode` / `NvRmStream` green-flash looping video bug as well as `1440p60` `TextureView` display glitches on Amlogic boxes without reloading streams.
  - Replaced ghost transparency with a smooth **`240 ms` horizontal slide** between **Bottom-Right** and **Bottom-Left** (respecting the compact side rail margin, the two-column **Settings** layout, and an anti-ping-pong `180 ms` debounce + hysteresis during fast D-pad scrolling).
  - Added **Auto-move mini player** (**On** by default) in **Settings > Player > Xtra TV Player Settings** so horizontal auto-sliding can be toggled at any time.
- **Refined "Edge Dock" Left / Right Streamer Switch Overlay**:
  - Redesigned `TvSwitchOverlay` into compact smoked-glass **Edge Dock** tabs flush (`0 dp`) against the left and right edges of the screen with an ambiance-accented outer stripe and smooth slide-in/out animations, avoiding overlap with live chat messages.
- **Live Rewind (DVR) & Timeline Seek Preview Improvements**:
  - **Experimental Adblock timeline thumbnails**: wired Experimental Adblock's ongoing CDN VOD (`160p30/index-dvr.m3u8` / fallback variants) into `TvSeekPreview`, bringing live timeline thumbnails even on channels with unlisted/private VODs.
  - **Seamless DVR timeline focus & anti-accidental streamer switch**: entering live-rewind DVR (`KEY_DVR`) in Swap Adblock or Proxy mode now opens the HUD focused directly on the timeline (`progressBar`), and pressing **Left / Right** while rewound (across Swap Adblock, Proxy, and Experimental Adblock) reopens the timeline instead of accidentally switching to the previous/next streamer.
  - **Smooth thumbnails during long-press scrubbing**: coalesced `videoPlayer` seeks (`~140–240 ms` keyframe pacing + immediate snap on key release) and added **LIFO** priority + reusable bitmap cropping for storyboard sprite sheets so thumbnails update continuously while holding **Left / Right**.
- **Tabbed Compact Multi-Stream Panel & Smoked-Glass / Hardware-Blur Side Panels**:
  - Rebuilt the **Multi-Stream** tile menu into a compact `392 dp` 3-tab `SidePanel` (**Display** · **Audio** · **Options** + pinned **Open full** / **Remove** footer) matching **Chat** and **PiP** panels, with live layout, chat, audio mix, and quality controls.
  - Styled TV `SidePanel` menus with translucent smoked glass (`~70 %` opacity, inner rounded corners, subtle `8 %` screen dim) and automatic **Android 12+ native `Window.setBackgroundBlurRadius`** scoped strictly to the panel outline so the live stream remains visible behind and beside menus.
- **Automatic MediaCodec decoder fallback**: enabled `DefaultRenderersFactory.setEnableDecoderFallback(true)` on primary, secondary (ad-free swap), card preview, and seek preview `ExoPlayer` instances.
- **Eliminated live-stream `MediaSession` / Notification / Bluetooth IPC spam**:
  - Deduplicated `MediaMetadata`, `MediaSession` queue, and `PlaybackState` updates across live HLS playlist refreshes and unified channel avatar fetching between `MediaMetadata` and foreground notifications.
- **Lighter card video previews & paused hidden-chat emote animations**:
  - Disabled audio track decoding (`TRACK_TYPE_AUDIO`) on muted card/seek previews, raised hover delay to `250 ms`, fixed `firstFrameListener` cleanup, set `VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF` on `TextureView` secondary players, and paused animated GIF / 7TV / BTTV / FFZ spans while chat is hidden or minimized.
- **Fixed `Cronet` / `HttpEngine` / `OkHttp` leaks & UI-thread SQLite stalls**:
  - Routed `openOkHttp` before allocating native `Cronet` / `HttpEngine` `UrlRequest`s, closed interrupted GZIP `Response`s, cached proxy `CronetEngine` / `HttpEngine` instances in `XtraModule`, replaced `runBlocking` Room writes with FIFO `Mutex` background writes, and guarded HUD header `maxWidth` relayouts.

### Install
Download `XtraTV-1.4.6.7-tv.apk` below and install it over the previous version, your settings and account are kept.
