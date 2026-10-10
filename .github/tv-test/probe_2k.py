# Which qualities Twitch gives (no account) depending on the client, the player type and the codecs: looking for 1440p.
import json, re, random, urllib.parse, urllib.request

WEB = "kimne78kx3ncx6brgo4mv6wki5h1ko"
APP = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"

def gql(cid, body):
    req = urllib.request.Request("https://gql.twitch.tv/gql", data=json.dumps(body).encode(), headers={"Client-Id": cid, "Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=15))

def get(url):
    return urllib.request.urlopen(url, timeout=15).read().decode()

res = gql(WEB, {"query": "query{streams(first:50){edges{node{broadcaster{login}}}}}"})
logins = [e["node"]["broadcaster"]["login"] for e in res["data"]["streams"]["edges"]]
print("channels:", len(logins))
q = "query PlaybackAccessToken($login: String!, $platform: String!, $playerType: String!) { streamPlaybackAccessToken(channelName: $login, params: {platform: $platform, playerBackend: \"mediaplayer\", playerType: $playerType}) { value signature } }"
summary = {}

def heights_for(login, cid, pt, codecs):
    t = gql(cid, {"query": q, "variables": {"login": login, "platform": "web", "playerType": pt}})["data"]["streamPlaybackAccessToken"]
    url = "https://usher.ttvnw.net/api/v2/channel/hls/%s.m3u8?" % login + urllib.parse.urlencode({
        "allow_source": "true", "allow_audio_only": "true", "fast_bread": "true", "include_unavailable": "true",
        "p": random.randint(0, 9999999), "platform": "web", "playlist_include_framerate": "true",
        "sig": t["signature"], "supported_codecs": codecs, "token": t["value"]})
    pl = get(url)
    return sorted({int(h) for h in re.findall(r"RESOLUTION=\d+x(\d+)", pl)}), sorted(set(re.findall(r'CODECS="([^"]+)"', pl)))

# first pass: the website client, every codec
found = []
for login in logins:
    try:
        h, c = heights_for(login, WEB, "site", "av1,h265,h264")
    except Exception as e:
        print("error", login, e)
        continue
    if h and h[-1] > 1080:
        found.append(login)
        print("2K", login, h, c)
print("channels with 2K (web, site, av1,h265,h264):", found)
# second pass: what removes it
for login in found[:3]:
    for cid_name, cid in (("web", WEB), ("app", APP)):
        for pt in ("site", "popout", "embed"):
            for codecs in ("av1,h265,h264", "h265,h264", "h264"):
                try:
                    h, c = heights_for(login, cid, pt, codecs)
                    print(login, cid_name, pt, codecs, "max", h[-1] if h else None, c)
                except Exception as e:
                    print(login, cid_name, pt, codecs, "error", e)
