# Storyboards of the VODs being recorded: what Twitch gives for live channels.
import json, urllib.request, urllib.error

def gql(query):
    request = urllib.request.Request('https://gql.twitch.tv/gql', data=json.dumps({"query": query}).encode(),
                                     headers={'Client-ID': 'kimne78kx3ncx6brgo4mv6wki5h1ko', 'Content-Type': 'application/json'})
    return json.load(urllib.request.urlopen(request))

def get(url):
    try:
        with urllib.request.urlopen(url) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, b''
    except Exception as e:
        return str(e), b''

streams = gql('{streams(first:12, options:{languages:[FR]}){edges{node{id createdAt broadcaster{login}}}}}')
for edge in streams['data']['streams']['edges']:
    node = edge['node']
    login = node['broadcaster']['login']
    data = gql('{user(login:"%s"){videos(first:1,type:ARCHIVE,sort:TIME){edges{node{id createdAt status seekPreviewsURL animatedPreviewURL previewThumbnailURL}}}}}' % login)
    edges = (data.get('data') or {}).get('user', {}).get('videos', {}).get('edges') or []
    video = edges[0]['node'] if edges else None
    print('==', login, 'stream', node['id'], node['createdAt'])
    print('   video', json.dumps(video))
    if not video:
        continue
    urls = []
    if video.get('seekPreviewsURL'):
        urls.append(video['seekPreviewsURL'])
    if video.get('animatedPreviewURL'):
        urls.append(video['animatedPreviewURL'].rsplit('/', 1)[0] + '/' + video['id'] + '-info.json')
    for url in urls:
        status, body = get(url)
        print('   info', status, url)
        if body:
            print('   body', body[:400])
            try:
                entries = json.loads(body)
                entry = entries[0]
                first = url.rsplit('/', 1)[0] + '/' + entry['images'][0]
                last = url.rsplit('/', 1)[0] + '/' + entry['images'][-1]
                print('   sprite first', get(first)[0], first)
                print('   sprite last', get(last)[0], last, 'count', len(entry['images']))
            except Exception as e:
                print('   parse', e)
