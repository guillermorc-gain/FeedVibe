"""Diagnóstico 3: canal «HipotesisdePoder» da 404 al actualizar."""
import json, re, urllib.request, urllib.parse

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"
def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "es-ES,es;q=0.9", "Cookie": "SOCS=CAI; CONSENT=YES+cb"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r: return r.status, r.geturl(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e: return e.code, url, ""

ids = set()
for url in ["https://www.youtube.com/@HipotesisdePoder", "https://www.youtube.com/@hipotesisdepoder/videos",
            "https://www.youtube.com/c/HipotesisdePoder", "https://www.youtube.com/user/HipotesisdePoder"]:
    st, final, html = get(url)
    cid = re.findall(r'"(?:externalId|channelId|browseId)":"(UC[\w-]{22})"', html)
    title = re.search(r'<meta property="og:title" content="([^"]+)"', html)
    print(f"{url}: HTTP {st} -> {final} | títulos={title.group(1) if title else None} | ids={sorted(set(cid))[:5]}")
    ids.update(cid[:1])

st, _, html = get("https://www.youtube.com/results?search_query=" + urllib.parse.quote("Hipótesis de Poder") + "&sp=EgIQAg%253D%253D")
found = re.findall(r'"channelId":"(UC[\w-]{22})".{0,400}?"simpleText":"([^"]+)"', html)
print("Búsqueda:", HTTP := st, found[:6])
ids.update(c for c, _ in found[:3])

for cid in sorted(ids):
    base = cid[2:]
    for u in [f"https://www.youtube.com/feeds/videos.xml?channel_id={cid}", f"https://www.youtube.com/feeds/videos.xml?playlist_id=UU{base}",
              f"https://www.youtube.com/channel/{cid}/videos", f"https://www.youtube.com/playlist?list=UU{base}"]:
        st, final, body = get(u)
        n = body.count("<entry>") or body.count('"videoId"')
        title = re.search(r'<title>([^<]+)</title>', body)
        print(f"  {u}: HTTP {st} elementos≈{n} título={title.group(1)[:60] if title else None}")
