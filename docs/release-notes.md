# Xtra TV 1.4.6.7

### New & Polish
- **100 % `SurfaceView` Hardware-Overlay Mini-Player & smooth Right ↔ Left auto-slide**:
  - Kept the minimized player permanently on its native **`SurfaceView` Hardware Overlay** (eliminating all `SurfaceView` ↔ `TextureView` surface switches and `freeAllBuffers` stalls), fixing the NVIDIA Shield `OMX.Nvidia.h264.decode` / `NvRmStream` green-flash looping video bug as well as `1440p60` `TextureView` display glitches on Amlogic boxes without reloading streams.
  - Replaced ghost transparency with a smooth **`240 ms` horizontal slide** between **Bottom-Right** and **Bottom-Left** (respecting the compact side rail margin, the two-column **Settings** layout, and an anti-ping-pong `180 ms` debounce + hysteresis during fast D-pad scrolling).
  - Added **Auto-move mini player** (**On** by default) in **Settings > Player > Xtra TV Player Settings** so horizontal auto-sliding can be toggled at any time.
- **Automatic MediaCodec decoder fallback**: enabled `DefaultRenderersFactory.setEnableDecoderFallback(true)` on both primary and secondary (ad-free swap) `ExoPlayer` instances as well as card previews, allowing seamless fallback to a secondary or software decoder if a hardware codec instance fails.
- **Eliminated live-stream `MediaSession` / Notification / Bluetooth IPC spam**:
  - Deduplicated `MediaMetadata`, `MediaSession` queue, and `PlaybackState` updates across live HLS playlist refreshes so channel bitmaps and metadata are no longer re-serialized over Binder IPC and Bluetooth AVRCP every `1–2 s`.
  - Unified channel avatar fetching between `MediaMetadata` and foreground notifications so concurrent calls no longer cancel each other's download or briefly reuse a previous channel's artwork.
- **Lighter card video previews & secondary `TextureView` players**:
  - Disabled audio track decoding (`TRACK_TYPE_AUDIO`) on muted card previews and seek previews (dynamically re-enabling it when preview sound is turned on) to avoid spawning unused AAC decoders and `AudioTrack`s while browsing, increased the preview hover delay to `250 ms`, and fixed a leaked `Player.Listener` when leaving a card before its first frame.
  - Set `VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF` on `TextureView`-backed secondary players (card previews, PiP, Multi-Stream, seek preview) to stop `SurfaceFlinger` `IGraphicBufferProducer` frame-rate errors.
- **Paused animated chat emotes while chat is hidden**: stopped animated GIF / 7TV / BTTV / FFZ emote drawables when hiding chat or minimizing the player and resumed them cleanly when chat is shown again, saving UI-thread CPU and GPU work while keeping live messages, Polls, and Predictions running normally.
- **Fixed `Cronet` / `HttpEngine` / `OkHttp` native & GZIP resource leaks**:
  - Checked `OkHttp` playlist-proxy routing before allocating native `Cronet` / `HttpEngine` `UrlRequest` instances (fixing `FinalizerDaemon` `dispose` warnings), closed orphaned GZIP `Response` bodies on interrupted calls, and cached proxy `CronetEngine` / `HttpEngine` instances in `XtraModule` instead of creating new native thread pools per stream.
- **Removed UI-thread SQLite stalls & redundant HUD relayouts**:
  - Replaced main-thread `runBlocking` Room writes in playback services with FIFO-serialized background writes (`Mutex` + `Dispatchers.IO`), and guarded player header `maxWidth` updates to avoid duplicate `requestLayout()` passes during layout.

### Install
Download `XtraTV-1.4.6.7-tv.apk` below and install it over the previous version, your settings and account are kept.
