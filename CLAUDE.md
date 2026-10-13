# Xtra TV — notes for Claude

## To do in the next update
- Show the version number of the app in the settings (asked by the owner after 1.1.1, place chosen): under the
  screen name in the left panel of the TV settings, instead of "Xtra TV" write "Xtra TV 1.1.1"
  (`tvSettingsSubtitle` in `layout-television/activity_settings.xml`), from `BuildConfig.VERSION_NAME` without the
  `-tv` / `-DEBUG` suffixes, with " (debug)" added in the debug builds. No extra row in the settings lists.
