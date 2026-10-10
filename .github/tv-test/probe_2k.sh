#!/bin/bash
# Which qualities Twitch gives (no account) depending on the player type and the codecs: looking for 1440p.
CID=kimne78kx3ncx6brgo4mv6wki5h1ko
logins=$(curl -s https://gql.twitch.tv/gql -H "Client-Id: $CID" -d '{"query":"query{streams(first:60){edges{node{broadcaster{login}}}}}"}' | python3 -c "import json,sys; print(' '.join(e['node']['broadcaster']['login'] for e in json.load(sys.stdin)['data']['streams']['edges']))")
found=0
for login in $logins; do
  for pt in site popout embed; do
    tok=$(curl -s https://gql.twitch.tv/gql -H "Client-Id: $CID" -d "{\"operationName\":\"PlaybackAccessToken_Template\",\"query\":\"query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!, \$platform: String!) {  streamPlaybackAccessToken(channelName: \$login, params: {platform: \$platform, playerBackend: \\\"mediaplayer\\\", playerType: \$playerType}) @include(if: \$isLive) { value signature } videoPlaybackAccessToken(id: \$vodID, params: {platform: \$platform, playerBackend: \\\"mediaplayer\\\", playerType: \$playerType}) @include(if: \$isVod) { value signature }}\",\"variables\":{\"isLive\":true,\"login\":\"$login\",\"isVod\":false,\"vodID\":\"\",\"playerType\":\"$pt\",\"platform\":\"web\"}}")
    sig=$(echo "$tok" | python3 -c "import json,sys; d=json.load(sys.stdin)['data']['streamPlaybackAccessToken']; print(d['signature'])" 2>/dev/null)
    val=$(echo "$tok" | python3 -c "import json,sys,urllib.parse; d=json.load(sys.stdin)['data']['streamPlaybackAccessToken']; print(urllib.parse.quote(d['value']))" 2>/dev/null)
    [ -z "$sig" ] && continue
    for codecs in "av1,h265,h264" "h265,h264" "h264"; do
      pl=$(curl -s "https://usher.ttvnw.net/api/v2/channel/hls/$login.m3u8?allow_source=true&allow_audio_only=true&fast_bread=true&include_unavailable=true&p=1234&platform=web&playlist_include_framerate=true&sig=$sig&supported_codecs=$codecs&token=$val")
      res=$(echo "$pl" | grep -o 'RESOLUTION=[0-9x]*' | sort -u | tr '\n' ' ')
      cod=$(echo "$pl" | grep -o 'CODECS="[^"]*"' | sort -u | tr '\n' ' ')
      if echo "$res" | grep -q "x1440\|x2160"; then
        found=1
        echo "2K $login playerType=$pt codecs=$codecs -> $res $cod"
      fi
    done
  done
done
echo "probe done, found=$found"
