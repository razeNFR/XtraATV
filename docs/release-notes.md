# Xtra TV 1.2.6

### New
- **Chat delay** (chat panel of the player and chat settings, off at first): the stream is often a few seconds behind the chat, which can spoil what is coming.
  - **Auto** follows the latency of the stream: each message shows up with the moment it is about. It uses the times of Twitch (not the clock of the TV) and follows the delay when it changes (another stream, a proxy, a pause). The panel shows the delay measured.
  - **Manual**: a slider from 1 to 60 s.
  - Your own messages show up at once, the recent messages loaded with the stream are not delayed, the delay never goes over 60 s, and when the latency is not known the chat shows its messages without delay (it never gets stuck).

### Changes
- **Chat panel icon**: a chat bubble with a gear, so it is clearly the settings of the chat.

### Install
Download `XtraTV-1.2.6-tv.apk` below and install it over the previous version, your settings and account are kept.
