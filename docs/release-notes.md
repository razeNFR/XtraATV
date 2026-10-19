# Xtra TV 1.2.3

### New
- **Small window settings**: while a small window is open, a new "Small window" button in the controls of the player opens its panel: its corner (4 corners), its size (small, medium, large), the sound (the big stream, the small window or both, with a balance between them), swap the streams, close it. Everything moves at once and is kept for the next time.
- **Live banner**: while the app is open, a banner at the top of the screen says when a streamer you follow goes live (with the category and the title). It can be turned off, and its time on screen chosen (3 to 10 s), in the Xtra TV settings, "Live notifications". Nothing runs when the app is closed.
- **Back keeps the sound like Home**: Back on the first screen of the app (side menu) no longer closes the stream of the mini player or of the sound only mode when the sound is kept.
- **Start page with the house**: in Settings > Interface > Navigation tabs, "Home" is now in the list and the tab with the house is the page the app opens on (Home at first). The house of the other tab lists (Following, Saved, Channel, Category, Search) is the tab opened first on that page.
- **Hide the side menu when the app starts** (Xtra TV settings, "Home and look"): the side menu stays closed and the start page is shown at once.
- **Ad blocker badge**: it says if the ad being replaced is a pre-roll (start of the stream) or a mid-roll.

### Changes
- **One setting for the sound when leaving the app**: the 3 switches (mini player, sound only, full screen) become one choice, "Sound when leaving the app (Home or Back)": never, only in the mini player or sound only, or always (full screen too). Your previous choice is kept.
- The **Theme** setting is removed on TV (it was made for phones); a theme changed before goes back to the one of Xtra TV.
- No house on an unchecked tab in the tab lists: unchecking the tab that had it gives it to the first checked tab.
- The old "Start on Following page" setting (made for phones, it did nothing on TV) is hidden on TV: the house of the navigation tabs does it.

### Fixes
- **No more black screen when switching streams**: the pictures of the streams on each side are loaded in advance, and until the picture of the next stream is there the last picture of the stream left stays on the screen.
- **Back with the small window**: Back hides the controls first, it closes the small window only once they are hidden (it closed it at once, and the focus got lost with its button).
- **Ad pictures when switching streams**: switching streams (left / right, other streams, small window) could show a picture of an ad of the stream left and one of the stream opened. The stream left stops before anything of its ad can come back on the screen, and the picture of the next stream stays until its own video (not an ad) is shown. The protections against the crash when switching quickly are kept.

### Install
Download `XtraTV-1.2.3-tv.apk` below and install it over the previous version, your settings and account are kept.
