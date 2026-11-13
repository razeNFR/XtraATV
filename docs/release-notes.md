# Xtra TV 1.3.6

### What's new
- **`720p30` multi-stream option & Balanced profile**: added **`720p30`** (caps both video height at `720p` and frame rate at `30fps` to halve hardware decoder load compared to `720p60`, falling back cleanly to `480p30` when a channel does not offer a `720p30` transcode) in *Custom* and per-stream *This stream* selectors, plus a new **Balanced** profile (`Max` on 2 streams · `720p60` on 3 streams · `720p30` on 4 streams).
- **Discreet ad-block badge in Multi-Stream**: each tile now displays a compact, softly pulsing shield badge (`Ad`) in its top-right corner while an ad is being bypassed on a backup stream, disappearing automatically once the main stream resumes.
- **Full cumulative changelog in in-app updates**: when updating across multiple versions (e.g. `1.3.1 → 1.3.6`), the TV update dialog now displays the release notes of **every** intermediate version you missed instead of only the latest release.

### Fixes
- **Move chat remote shortcuts (`Next / Previous position`)**: cycling overlay chat position with a mapped remote button now steps smoothly through all **8** positions around the screen (all 4 corners **and** all 4 mid-edge positions) instead of skipping mid-screen positions.

### Install
Download `XtraTV-1.3.6-tv.apk` below and install it over the previous version, your settings and account are kept.
