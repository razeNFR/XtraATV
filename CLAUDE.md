# Xtra TV — notes for Claude

## Done
- Version number in the settings (1.2.0): under the screen name in the left panel of the TV settings, from
  `BuildConfig.VERSION_NAME`, " (debug)" in the debug builds.

## Conventions
- Every new button of the TV player gets its icon in Settings > Player buttons too: add it to the `icons` map of
  `PlayerButtonSettingsFragment` (SettingsActivity.kt). Player icons are filled (an outline only for a state, like the
  heart of Follow).
