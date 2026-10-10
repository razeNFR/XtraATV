# Xtra TV

<img src="docs/icon.png" align="left" width="110"/>

**Xtra TV** is a Twitch player and browser made for **Android TV**, built on top of [Xtra](https://github.com/crackededed/Xtra).
Every screen was reworked for the remote: a leanback side menu, big cards with live previews, a TV player with a DVR timeline,
an ad blocker that works like the VAFT userscript, multi-stream, voice search and much more.

<br clear="left"/>

<p align="center">
  <img src="docs/screenshots/home.jpg" width="49%"/>
  <img src="docs/screenshots/player.jpg" width="49%"/>
</p>

## Download

Get the latest APK from the [Releases](https://github.com/razeNFR/XtraATV/releases/latest) page.

On a TV, the easiest way is the **Downloader** app (or any file manager): open the release page, download the APK and install it
(allow the installation of unknown apps for that app). From a computer: `adb install XtraTV.apk`.

Xtra TV has its own package name, it installs next to the original Xtra. The app checks this repository for updates.

## Features

### A real TV interface
- **Home made for TV** (Jetpack Compose): rows of **followed channels live**, **popular streams** and **categories**, with a "See all" tile at the end of each row and a refresh button.
- **Choose the languages of the Popular row** in the settings, to see streamers in the languages you want.
- **Leanback side menu**: Search, Home, Games, Popular, Following, Saved, Settings and Account. It opens with left or back and folds away when you go into a screen.
- **Live preview** of the stream in the focused card after half a second, muted, without ads.
- **Big cards** with clean titles, viewer count and uptime badges, channel avatar and category.
- **Google TV like scrolling**: the focused row stays at the same place, a part of the rows before and after is always in sight, the focus never jumps away, even when holding the key.
- **Focus that is never lost**: coming back from a stream, a channel or a category, the focus returns to the card you were on. Back far down a list brings you to its top.
- **Long press OK on a card**: open the channel, open the category, more options.
- **TV header** on every list: refresh, sections (selected with OK only) and the sort pill. Up from the list lands on refresh, left opens the menu.
- **Sort and filter panels** on the side of the screen, with the focus on the current choice.
- **Search bar with the mic built in**: speak and the words show up while you talk, the search starts right away, like the YouTube app. The keyboard only opens with OK.
- **Channel page** with the live stream, its videos, clips and chat.
- **Settings fully usable with the D-pad**, options that make no sense on a TV are hidden, lists are reordered with up / down buttons.
- **Proper TV banner and icon**, translated into the 13 languages of the app.

### Player
- **TV controls**: every button reachable with the D-pad, tooltips, a quality badge that shows the real quality on screen (also while the ad blocker plays its backup stream).
- **Go back in time in a live stream**: the timeline covers the whole broadcast, moving back plays the VOD being recorded at that moment, going to the end comes back to live.
- **Thumbnails on the timeline** while seeking, in VODs and in the past of a live stream (Twitch storyboards).
- **Smart D-pad seeking**: one press is 10 seconds, holding the key goes faster and faster depending on the length of the video.
- **Sub-only VODs** play too.
- **Chat as an overlay**, glued to the bottom right corner, lifted above the controls when they show.
- **Chat layout panel**: position (4 corners), width, height, distance from the edge and background opacity, changed live.
- **Write in the chat with the remote**: a message box with **voice dictation** built in, no system popup.
- **Switch stream** without leaving the player: list of the streams of the category you came from, channel up / down, and an option to switch streamer with left / right while the controls are hidden.
- **Multi-stream**: up to 4 streams on screen at once, the focused one plays the sound.
- **Stats for nerds**: resolution, frame rate, codec, bitrate, buffer, latency, dropped frames.
- **Frame rate matching** (option): the TV switches to the refresh rate of the stream when it starts and keeps it matched while playing.
- **Best quality in Auto**: on Wi-Fi / Ethernet the highest quality is played, the adaptive choice only comes back after the stream buffers. Twitch low latency otherwise keeps Auto stuck at 720p.
- **1440p streams** (Enhanced Broadcasting) on the channels that have it, see below.
- Back closes the player, picture-in-picture is off by default on TV.

### Ad blocker (like VAFT)
- When an ad starts, the player switches to the **ad-free stream of another player type** (embed, popout, autoplay), like the VAFT userscript, then comes back to the main stream when the ad is over.
- A small **"Blocking ads" box** shows at the top right while it works.
- It also **backs up the proxies**: when a proxy lets an ad through, the ad blocker takes over.
- The **live previews** and every stream of the **multi-stream** are ad-free too.
- On by default, in the main settings.

## 1440p (Enhanced Broadcasting)

Twitch only gives the 1440p qualities to its website client with a logged in account. To get them:
1. Log in with your Twitch account in the app (the twitch.tv login page). The website token is saved with it.
2. Keep **"Include token for streams"** on (default).
3. Your TV must decode **H.265 (HEVC) or AV1** in hardware.

## Screenshots

| | |
|:-:|:-:|
| ![Side menu](docs/screenshots/menu.jpg) | ![Home](docs/screenshots/home.jpg) |
| Leanback side menu | Home with rows and live preview |
| ![Category](docs/screenshots/category.jpg) | ![Sort panel](docs/screenshots/sort.jpg) |
| A category with the TV header | Sort and filters panel |
| ![Player](docs/screenshots/player.jpg) | ![Multi-stream](docs/screenshots/multistream.jpg) |
| Player with the chat overlay | Multi-stream of 4 streams |
| ![Chat layout](docs/screenshots/chat_layout.jpg) | ![Stats for nerds](docs/screenshots/stats.jpg) |
| Chat layout panel | Stats for nerds |
| ![Channel](docs/screenshots/channel.jpg) | ![Search](docs/screenshots/search.jpg) |
| Channel page | Search with the mic |

## Building

```
./gradlew assembleRelease
```
The APK is in `app/build/outputs/apk/release/`.

## Credits

Xtra TV is a fork of [Xtra](https://github.com/crackededed/Xtra) (originally by [AndreyAsadchy](https://github.com/AndreyAsadchy/Xtra)).
The ad blocker follows the logic of the [VAFT userscript](https://github.com/pixeltris/TwitchAdSolutions).

## License

Xtra TV is licensed under the [GNU Affero General Public License v3.0](LICENSE).
