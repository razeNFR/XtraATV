# Xtra TV — Deep Links & Intent Reference

Xtra TV registers `android.intent.action.VIEW` for `twitch.tv` and `*.twitch.tv` (`http` and `https`), allowing external apps, custom Android TV launchers (Projectivy, FLauncher, ATV Launcher), button remappers (**tvQuickActions**, **Button Mapper**), **Home Assistant**, and **ADB** scripts to open channels, live streams, VODs, clips, and categories directly.

## Application Identifiers

| Field | Value |
| :--- | :--- |
| **Package name (Release)** | `com.github.razenfr.xtratv` |
| **Package name (Debug)** | `com.github.razenfr.xtratv.debug` |
| **Main Activity** | `com.github.andreyasadchy.xtra.ui.main.MainActivity` |
| **Component** | `com.github.razenfr.xtratv/com.github.andreyasadchy.xtra.ui.main.MainActivity` |
| **Intent Action** | `android.intent.action.VIEW` |

---

## Supported URLs

### 1. Play a Live Stream Directly (`?xtratv_play=1`)

Opens the video player immediately on the specified channel's live stream:

```text
https://www.twitch.tv/<channel_login>?xtratv_play=1
```

Optional query parameters (pre-populated in the HUD while stream metadata loads):

| Parameter | Description | Example |
| :--- | :--- | :--- |
| `xtratv_play` | Must be `1` to start playback immediately instead of opening the channel page | `1` |
| `name` | Channel display name | `Kamet0` |
| `title` | Stream title | `LEC%20Watchparty` |
| `game` | Category / game name | `League%20of%20Legends` |
| `id` | Twitch stream ID | `123456789` |
| `cid` | Twitch channel numeric user ID | `27115917` |
| `img` | Channel avatar image URL | `https://...` |

### 2. Open a Channel Page

Opens the channel's profile screen (live banner, past broadcasts, highlights, and clips):

```text
https://www.twitch.tv/<channel_login>
```

### 3. Play a VOD / Past Broadcast

Opens a VOD directly in the player, with an optional `?t=` timestamp offset:

```text
https://www.twitch.tv/videos/<video_id>
https://www.twitch.tv/videos/<video_id>?t=1h23m45s
https://www.twitch.tv/videos/<video_id>?t=120s
```

### 4. Play a Clip

Both Twitch clip URL formats are supported:

```text
https://clips.twitch.tv/<clip_slug>
https://www.twitch.tv/<channel_login>/clip/<clip_slug>
```

### 5. Open a Category / Game

By Twitch category slug or URL-encoded game name, with an optional `?tl=` tag filter:

```text
https://www.twitch.tv/directory/category/<category_slug>
https://www.twitch.tv/directory/category/just-chatting?tl=French
https://www.twitch.tv/directory/game/Minecraft
https://www.twitch.tv/directory/game/League%20of%20Legends?tl=English
```

### 6. Browse Directory, Popular Streams & Teams

| Target Screen | URL |
| :--- | :--- |
| **Categories / Games directory** | `https://www.twitch.tv/directory` |
| **Popular live streams** | `https://www.twitch.tv/directory/all` |
| **Popular streams filtered by tag** | `https://www.twitch.tv/directory/all/tags/<tag_name>` |
| **Popular streams by Twitch tag ID** | `https://www.twitch.tv/directory/tags/<tag_id>` |
| **Twitch Team channels** | `https://www.twitch.tv/team/<team_name>` |

---

## Integration Examples

### ADB (`adb shell am start`)

```bash
# Launch Xtra TV on the Home screen
adb shell am start -n com.github.razenfr.xtratv/com.github.andreyasadchy.xtra.ui.main.MainActivity

# Start playing a live stream immediately
adb shell am start -a android.intent.action.VIEW \
  -d "https://www.twitch.tv/kamet0?xtratv_play=1" \
  -n com.github.razenfr.xtratv/com.github.andreyasadchy.xtra.ui.main.MainActivity

# Open a streamer's channel page (VODs & clips)
adb shell am start -a android.intent.action.VIEW \
  -d "https://www.twitch.tv/kamet0" \
  -n com.github.razenfr.xtratv/com.github.andreyasadchy.xtra.ui.main.MainActivity

# Open a VOD at 1h 05m 30s
adb shell am start -a android.intent.action.VIEW \
  -d "https://www.twitch.tv/videos/2123456789?t=1h5m30s" \
  -n com.github.razenfr.xtratv/com.github.andreyasadchy.xtra.ui.main.MainActivity

# Open a category
adb shell am start -a android.intent.action.VIEW \
  -d "https://www.twitch.tv/directory/category/just-chatting" \
  -n com.github.razenfr.xtratv/com.github.andreyasadchy.xtra.ui.main.MainActivity
```

### Android Intent URI (tvQuickActions, Button Mapper, Custom Launchers)

```text
intent://www.twitch.tv/kamet0?xtratv_play=1#Intent;scheme=https;action=android.intent.action.VIEW;package=com.github.razenfr.xtratv;component=com.github.razenfr.xtratv/com.github.andreyasadchy.xtra.ui.main.MainActivity;end
```

### Home Assistant (Android TV Remote / ADB Integration)

Using the native **Android TV Remote** (`remote.turn_on`) integration:

```yaml
action: remote.turn_on
target:
  entity_id: remote.android_tv
data:
  activity: "https://www.twitch.tv/kamet0?xtratv_play=1"
```

Or targeting Xtra TV explicitly via `androidtv.adb_command`:

```yaml
action: androidtv.adb_command
target:
  entity_id: media_player.android_tv
data:
  command: >-
    am start -a android.intent.action.VIEW
    -d "https://www.twitch.tv/kamet0?xtratv_play=1"
    -n com.github.razenfr.xtratv/com.github.andreyasadchy.xtra.ui.main.MainActivity
```
