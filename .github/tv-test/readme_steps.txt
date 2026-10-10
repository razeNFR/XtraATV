# Screenshots for the README
sleep 5
shot readme_menu
key RIGHT 6
shot readme_home
key DOWN 4
shot readme_home_games
# Games, a category and its sort panel
key LEFT 2
key DOWN 1
key DPAD_CENTER 8
shot readme_games
key DPAD_CENTER 10
key RIGHT 1
key LEFT 1
sleep 3
shot readme_category
key UP 1
keys RIGHT,RIGHT,RIGHT,RIGHT,RIGHT 1
key DPAD_CENTER 3
shot readme_sort_panel
key BACK 2
# Player from the category
key DOWN 1
key DPAD_CENTER 30
shot readme_player
key DPAD_CENTER 2
shot readme_player_hud
tapid statsForNerds 0
sleep 3
key DPAD_CENTER 2
shot readme_stats
tapid statsForNerds 0
tapid chatLayoutSettings 0
sleep 2
shot readme_chat_layout
key BACK 2
# Multi-stream of 4 streams
key DPAD_CENTER 2
tapid multiStream 0
sleep 4
shot readme_multistream_pick
tapclass android.widget.CheckedTextView 1
tapclass android.widget.CheckedTextView 2
tapclass android.widget.CheckedTextView 3
shot readme_multistream_picked
taptext Watch 0
sleep 30
shot readme_multistream
key BACK 3
key BACK 3
# Channel page
longkey DPAD_CENTER 3
key DPAD_CENTER 10
shot readme_channel
key BACK 3
key BACK 3
# Search with the mic
shot readme_after_back
key LEFT 2
keys UP,UP,UP,UP,UP,UP,UP,UP 1
key DPAD_CENTER 5
shot readme_search
