"""Diagnóstico 7: emisoras locales por coordenadas en TuneIn (Baleares, islas)."""
import json, urllib.request, urllib.parse
UA = "Mozilla/5.0 (Linux; Android 14) FeedVibe"
def get(url):
    sep = '&' if '?' in url else '?'
    url = url + f"{sep}render=json&locale=es"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=30) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"error": str(e)}
def names(d):
    out = []
    def walk(x):
        if isinstance(x, dict):
            if x.get("item") == "station" or str(x.get("guide_id","")).startswith("s"): out.append((x.get("text"), x.get("subtext")))
            if "text" in x and x.get("type") == "link": out.append(("LINK", x.get("text"), x.get("URL")))
            for v in x.values(): walk(v)
        elif isinstance(x, list):
            for v in x: walk(v)
    walk(d.get("body")); return out
B = "https://opml.radiotime.com/"
for place, ll in [("Palma", "39.5696,2.6502"), ("Ibiza", "38.9067,1.4206"), ("Menorca", "39.8885,4.2658"), ("Granada", "37.1773,-3.5986")]:
    d = get(B + f"Browse.ashx?c=local&latlon={ll}")
    print("\n==", place, "latlon ==", d.get("head"))
    for n in names(d)[:25]: print("  ", n)
d = get(B + "Search.ashx?query=Mallorca&types=station")
print("\n== buscar Mallorca ==")
for n in names(d)[:20]: print("  ", n)
d = get(B + "Browse.ashx?id=r100416&pivot=name&filter=country")
print("\n== España por nombre ==", json.dumps(d, ensure_ascii=False)[:1500])
