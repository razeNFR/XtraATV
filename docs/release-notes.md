# Xtra TV 1.2.1

### Changes
- **Faster stream start**: the access of Twitch is asked as soon as OK is pressed, the connections to the servers of the video are opened while the player opens, and the stream starts with less video waited for. Left / right in the player keeps the player open and the streams next to the one watched are prepared in advance, switching is quicker. Nothing changes with a proxy or the ad blocker.
- **No more black screen while a stream loads**: the newest picture of the stream is shown until its video comes (the last picture of its preview when it played), also in the small window and the multi-stream.
- **Thumbnails up to date**: the thumbnails of the streams are loaded again every minute, instead of old copies.
- **The player remembers the last button you were on**: after the other streams row, the timeline or a panel, the focus comes back to it, also from one stream to the next.
- **Chat of the broadcast when going back in a live**: the chat replay plays with the video, like on a VOD.
- **Channel page**: the focus is on the first video when it opens (else on the first button).
- **Lists of streams up to date**: the streamers that stopped no longer show in the other streams row or when picking a stream of a multi-stream.
- **Small window hint**: the other streams row says that holding OK opens a stream in a small window, and the stream already watched can't be opened in it.

### Fixes
- **Low quality in the multi-stream**, there since the multi-stream came: its streams always stayed on the "autoplay" stream of Twitch, limited to 360p / 480p. They now play the normal stream at the best quality for their size on the screen (up to 1080p or 1440p with 2 streams and for the big stream of the focus mode, 720p in a grid of 3 or 4). The ad blocker uses the "autoplay" stream only while an ad plays, then goes back to the normal one.
- The **Refresh** button of the home no longer keeps the focus: down goes back to the streams, and the focus never lands on it by itself (from the side menu it goes to the first stream, back from a stream to the stream watched).

### Install
Download `XtraTV-1.2.1-tv.apk` below and install it over the previous version, your settings and account are kept.
