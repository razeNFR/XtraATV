# Xtra TV 1.2.9

### New
- **Proxies only during ads**: custom proxies can now be used only while an ad plays (Ads and proxies > Custom proxies > Use, or OK held on the Proxy button of the player). The stream comes straight from Twitch the rest of the time: low latency and 1440p. If a proxy is offline, stops answering or lets the ad through, the next one is used, then the usual ad blocker. The status of the player shows the name of the proxy (for example "Proxy EU Luminous").
- **Custom proxies, safer**: in both modes the proxies found offline are skipped (checked in the background when a stream starts, and for 10 minutes after one fails), and in the "Always" mode, when an ad comes through the proxy, the other proxies are tried first, then the ad blocker.
- **Chat notices**: subscriptions, gifts, raids and announcements get a short translated line, smaller, with an icon, instead of the English sentence of Twitch.
- **Deleted messages**: a message deleted by a moderator (or of a user timed out or banned) is now greyed out and struck through in place, or hidden with "Show deleted messages" off, instead of a new line.
- **Replies to you**: an answer to your message or a mention of your name is highlighted with the accent color and a bar on its left (Chat > Highlight replies and mentions).
- **Ad blocking progress**: a thin bar on the ad blocker badge shows about how far the ad break is (when Twitch gives its length).
- **New accent colors**: light blue, turquoise, mint, amber, coral, lavender and white, picked from a list with a dot of each color.
- **Settings icons**: every setting of the right column has an icon, to find it at a glance.

### Changes
- **Long stream titles**: on the focused card (home, followed, lists, stream banner of the player), a title too long for the card scrolls after a second.
- **New streams**: while Twitch has not made the thumbnail of a stream yet, the picture of the channel is shown instead of the grey camera (cards and picture while a stream loads).
- **Left / right in the player**: quick switches no longer get stuck on the whole HUD nor move back in the timeline; left / right only seek once you opened the HUD yourself.
- **New public proxy**: twitch-al.nadeko.net is added to the custom proxies (at the end of the list, on).
- **Settings**: left from a setting goes back to its category, right comes back to the same setting, down on the last row stays there.
- **Ads and proxies**: the 1440p warning is gone from the top; what each option means for the 1440p is now written on the ad blocker and on the custom proxies.

### Install
Download `XtraTV-1.2.9-tv.apk` below and install it over the previous version, your settings and account are kept.
