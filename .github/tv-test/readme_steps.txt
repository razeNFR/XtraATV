# Screenshots for the README (debug build: the "test" lines open the screens directly)
sleep 5
shot readme_menu
key RIGHT 6
shot readme_home
# Search from the side menu
key LEFT 2
key UP 1
key DPAD_CENTER 4
shot readme_search
key BACK 2
# Games, a category and its sort panel
key LEFT 2
key DOWN 1
key DPAD_CENTER 8
shot readme_games
key DPAD_CENTER 10
sleep 6
shot readme_category
key UP 2
keys RIGHT,RIGHT,RIGHT,RIGHT,RIGHT 1
key DPAD_CENTER 3
shot readme_sort
key BACK 2
# Player from the category
key DOWN 1
key DPAD_CENTER 28
test hud 2
shot readme_player
# Multi-stream of 4 streams
test multistream 35
key RIGHT 1
shot readme_multistream
