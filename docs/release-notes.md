# Xtra TV 1.4.7.1

### New & Improvements
- **Redesigned Smoked-Glass Chat Composer & "Most Used" Emotes Tab**:
  - **Compact Smoked-Glass Chat Pill**: writing a chat message in the live player or Multi-Stream now opens a sleek smoked-glass pill anchored right above the bottom player controls (matching the Volume pill, without darkening the stream), with an upward-opening Emotes drawer.
  - **Dedicated "Most Used" Emotes Tab**: tracks lifetime usage for Twitch, 7TV, BTTV, and FFZ emotes — **counted only when a message is actually sent successfully** (never on preview or selection), automatically sorted from most to least used with lifetime usage badges, provider color indicators, live hover details, full TV remote navigation, persistent storage across restarts, and automatic backup/restore support.
- **Smoother Navigation & Faster Stream Startup on Android TV Boxes**:
  - **Fluid Scrolling on Lists & Categories**: optimized thumbnail, box-art, blur backdrop, and avatar decoding sizes for TV with dedicated in-memory caching and disk caching, removing heavy cold-load stutter while preserving smoked-glass transparency and live refreshes.
  - **Faster Stream Startup**: lowered initial TV playback buffer startup threshold and deferred third-party emote loading slightly so streams render their first frame faster without touching ad-blocker behavior.
  - **Fluid Left Menu Hover ("Navigate without OK")**: faster hover settle (`130 ms`) with instant section transitions and no redundant fade animations or stale network reloads while moving through the left navigation bar.
- **Player, Visuals & UI Polish**:
  - **HUD & Switch Overlay Auto-Hide Timing**: when opening or switching a stream, the player HUD peek and left/right stream-switch overlays now wait for the first video frame to render before starting their 2.5 s auto-hide countdown (with a 12 s safety fallback).
  - **Refined Night Shift**: replaced the harsh yellow tint with a calibrated cinema-amber warmth curve and subtle black-contrast compensation for comfortable night viewing with zero GPU overhead.
  - **Modernized Left Navigation Bar**: kept the familiar collapsible rail and gliding focus pill while adding a subtle top ambient wash, hairline group dividers, an avatar ring with online dot, a left accent bar on the active section, and subtle hierarchy guide lines on sub-items.
  - **Categories Language-Aware Broadcaster Count**: category cards now clarify `(global)` when a TV stream language filter is active, and opened category pages display the live language-filtered broadcaster count (e.g., `18 broadcasters (FR)` / `30+ broadcasters (FR)`).
  - **Hero Live Preview Clarity**: slightly reduced edge transparency on the Home Hero live preview video over custom wallpapers so streams remain crisp while blending smoothly.
  - **Statistics Period Tabs ("Navigate without OK")**: the **7 days**, **30 days**, and **All time** tabs now switch automatically on focus when "Navigate without OK" is enabled (while **Reset** remains click-only for safety).
  - **Unified Refresh Button**: on the app's default background, the **Refresh** header button now matches Home's sleek dark pill style across every screen.

### Install
Download `XtraTV-1.4.7.1-tv.apk` below and install it over the previous version, your settings and account are kept.
