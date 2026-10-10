# Screenshots for the README (debug build: the "test" lines open the screens directly)
sleep 5
key RIGHT 4
# Player from the first card of the home
key DPAD_CENTER 30
test hud 2
shot readme_player_hud
test chat_layout 3
shot readme_chat_layout
key BACK 2
test stats 4
shot readme_stats
# Multi-stream of 4 streams
test multistream 40
shot readme_multistream
