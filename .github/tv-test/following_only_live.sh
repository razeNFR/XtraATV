#!/bin/bash
# Prepares the app for the "Following with only the Live tab" case:
# Following tabs = only Live, and a local follow of a channel that is live right now (no account on the emulator).
PKG=com.github.andreyasadchy.xtra.debug
OUT=${1:-tv-out}
W=$(mktemp -d)
adb shell am force-stop $PKG
sleep 2
# a live channel from Twitch
curl -s https://gql.twitch.tv/gql -H "Client-Id: kimne78kx3ncx6brgo4mv6wki5h1ko" \
  -d '{"query":"query{streams(first:3){edges{node{broadcaster{id login displayName profileImageURL(width:300)}}}}}"}' > $W/live.json
cat $W/live.json | head -c 600; echo
for f in database database-wal database-shm; do
  adb exec-out run-as $PKG cat databases/$f > $W/$f 2>/dev/null
done
adb exec-out run-as $PKG cat shared_prefs/${PKG}_preferences.xml > $W/prefs.xml
python3 - "$W" <<'PY'
import json, sqlite3, sys, os, re
w = sys.argv[1]
for f in ("database-wal", "database-shm"):
    p = os.path.join(w, f)
    if os.path.exists(p) and os.path.getsize(p) == 0:
        os.remove(p)
edges = json.load(open(os.path.join(w, "live.json")))["data"]["streams"]["edges"]
db = sqlite3.connect(os.path.join(w, "database"))
for e in edges:
    b = e["node"]["broadcaster"]
    db.execute("INSERT INTO local_follows (userId, userLogin, userName, channelLogo) VALUES (?, ?, ?, ?)", (b["id"], b["login"], b["displayName"], b.get("profileImageURL")))
db.commit()
db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
db.execute("PRAGMA journal_mode=DELETE")
db.close()
print("local follows:", [e["node"]["broadcaster"]["login"] for e in edges])
p = os.path.join(w, "prefs.xml")
s = open(p).read()
s = re.sub(r'\s*<string name="ui_following_tabs">.*?</string>', '', s)
s = s.replace("</map>", '    <string name="ui_following_tabs">0:0:0,1:1:1,2:0:0,3:0:0</string>\n</map>')
open(p, "w").write(s)
PY
adb shell run-as $PKG rm -f databases/database-wal databases/database-shm
adb shell "run-as $PKG sh -c 'cat > databases/database'" < $W/database
adb shell "run-as $PKG sh -c 'cat > shared_prefs/${PKG}_preferences.xml'" < $W/prefs.xml
adb shell run-as $PKG cat shared_prefs/${PKG}_preferences.xml | grep -i follow
adb logcat -c
adb shell am start -n $PKG/com.github.andreyasadchy.xtra.ui.main.MainActivity
sleep 15
