"""Diagnóstico 6: programas a la carta de TuneIn (episodios y enlace de descarga) y tipos de emisión."""
import json, urllib.request, urllib.parse
UA = "Mozilla/5.0 (Linux; Android 14) FeedVibe"
def get(url):
    sep = '&' if '?' in url else '?'
    url = url.replace("http://", "https://") + f"{sep}render=json&locale=es"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=30) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"error": str(e)}
B = "https://opml.radiotime.com/"
for pid in ["p1032181", "p1246967", "p665573"]:
    d = get(B + f"Tune.ashx?c=pbrowse&id={pid}")
    print("\n== pbrowse", pid, "==\n", json.dumps(d, ensure_ascii=False)[:2500])
    d2 = get(B + f"Browse.ashx?c=topics&id={pid}")
    print("\n== topics", pid, "==\n", json.dumps(d2, ensure_ascii=False)[:2000])
# Tipos de emisión de varias emisoras populares
d = get(B + "Browse.ashx?id=r100416&filter=s:popular")
ids = []
def walk(x):
    if isinstance(x, dict):
        g = x.get("guide_id", "")
        if g.startswith("s"): ids.append(g)
        for v in x.values(): walk(v)
    elif isinstance(x, list):
        for v in x: walk(v)
walk(d.get("body"))
for gid in ids[:15]:
    t = get(B + f"Tune.ashx?id={gid}&formats=mp3,aac,ogg,hls")
    print(gid, [(b.get("url"), b.get("bitrate"), b.get("media_type")) for b in (t.get("body") or [])])
