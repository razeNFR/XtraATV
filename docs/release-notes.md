# Xtra TV 1.3.4

### What's new
- **Customizable remote shortcuts & extra remote buttons**: choose the exact action of **Left**, **Right**, **Up**, **Down**, and **Down (long press)** when player controls are hidden (e.g. cycle chat position, show/hide chat, open chat settings, switch streams, open other live streams, play/pause, mute/unmute, video quality, technical stats), and bind any extra button on your TV remote (color buttons, Info, Guide, Channels, digits, media keys…) with live key capture in *Xtra TV settings → Playback* (#59).
- **Double-press Back to exit**: enabled by default to prevent accidental exits, pressing **Back** on the Home screen or in the side menu at the root now shows a confirmation toast and requires a second press within 2 seconds to close the app; configurable in *Xtra TV settings → Playback* (#60).
- **Always-on player clock & customization**: the player clock can now be set to **Hidden**, **With player controls**, or **Always over stream**, with customizable corner position (top/bottom left/right), size (small, normal, large), opacity (20%–100%), and optional dark pill background in *Xtra TV settings → Playback* (#61).
- **Customizable Home screen rows & new rows**: reorder (with Left / Right or options menu), show, or hide any row on TV Home, pin any specific Twitch category as a live row (via *+ Add a category row* with search/voice & language filter, or by holding **OK** on any category card), add per-language rows, and enable 4 new optional rows (*Favorite channels live*, *Recommended channels*, *Top clips from followed channels*, and *Followed categories*) in *Xtra TV settings → Home*.

### Fixes
- **Quality restoration after ads (Ad-block)**: fixed video quality sometimes staying stuck on a low resolution after a VAFT ad swap when on `Auto` or a manual quality by isolating the backup ad-free player's bandwidth meter (and handling VPNs properly), keeping the main stream polling live during ads, preventing track/buffering callbacks during ad swaps from overwriting your chosen quality, and explicitly re-applying your selected quality when returning to the main stream.

### Install
Download `XtraTV-1.3.4-tv.apk` below and install it over the previous version, your settings and account are kept.
