"""Diagnóstico 5: catálogo público de TuneIn (opml.radiotime.com): regiones, estilos, emisión y programación."""
import json, urllib.request, urllib.parse
UA = "Mozilla/5.0 (Linux; Android 14) FeedVibe"
def get(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "es-ES"})
        with urllib.request.urlopen(req, timeout=30) as r:
            t = r.read().decode("utf-8", "replace")
            try: return json.loads(t)
            except Exception: return t
    except Exception as e:
        return f"ERROR {e}"
B = "https://opml.radiotime.com/"
def items(d):
    out = []
    def walk(x):
        if isinstance(x, dict):
            if "text" in x and ("URL" in x or "guide_id" in x): out.append(x)
            for v in x.values(): walk(v)
        elif isinstance(x, list):
            for v in x: walk(v)
    walk(d.get("body") if isinstance(d, dict) else d); return out
def show(title, d, n=40):
    print("\n==", title, "==")
    if not isinstance(d, dict): print(str(d)[:500]); return []
    it = items(d)
    for x in it[:n]: print("  ", {k: x.get(k) for k in ("type","text","guide_id","URL","subtext","bitrate","formats","reliability","item","image","now_playing_id","current_track","genre_id","preset_id") if x.get(k) is not None})
    return it
root = get(B + "Browse.ashx?render=json&locale=es")
r = show("raíz", root)
loc = next((x for x in r if x.get("key") == "location" or "ubic" in x.get("text","").lower() or "location" in x.get("text","").lower()), None)
print("LOC", loc)
if loc:
    eu = show("ubicaciones", get(loc["URL"] + "&render=json&locale=es"))
    europe = next((x for x in eu if "europ" in x.get("text","").lower()), None)
    if europe:
        countries = show("europa", get(europe["URL"] + "&render=json&locale=es"), 80)
        es = next((x for x in countries if x.get("text","").lower() in ("españa","spain")), None)
        if es:
            regs = show("España", get(es["URL"] + "&render=json&locale=es"), 60)
            bal = next((x for x in regs if "bale" in x.get("text","").lower()), None)
            if bal:
                cities = show("Baleares", get(bal["URL"] + "&render=json&locale=es"), 60)
                palma = next((x for x in cities if "palma" in x.get("text","").lower()), None)
                if palma: show("Palma", get(palma["URL"] + "&render=json&locale=es"), 40)
mus = next((x for x in r if "music" in x.get("key","") or "música" in x.get("text","").lower()), None)
if mus: show("música (estilos)", get(mus["URL"] + "&render=json&locale=es"), 60)
s = show("buscar Cadena 100", get(B + "Search.ashx?render=json&locale=es&query=" + urllib.parse.quote("Cadena 100")), 10)
st = next((x for x in s if x.get("item") == "station" or str(x.get("guide_id","")).startswith("s")), None)
if st:
    gid = st["guide_id"]
    print("\nTUNE", json.dumps(get(B + f"Tune.ashx?render=json&formats=mp3,aac,ogg,flash,html,hls&id={gid}"), ensure_ascii=False)[:1500])
    print("\nDESCRIBE", json.dumps(get(B + f"Describe.ashx?render=json&locale=es&id={gid}"), ensure_ascii=False)[:1500])
    print("\nPROGRAMAS", json.dumps(get(B + f"Browse.ashx?render=json&locale=es&c=programs&id={gid}"), ensure_ascii=False)[:1200])
    print("\nHORARIO", json.dumps(get(B + f"Browse.ashx?render=json&locale=es&c=schedule&id={gid}"), ensure_ascii=False)[:1500])
s2 = show("buscar RNE", get(B + "Search.ashx?render=json&locale=es&query=" + urllib.parse.quote("Radio Nacional")), 6)
st2 = next((x for x in s2 if str(x.get("guide_id","")).startswith("s")), None)
if st2:
    print("\nHORARIO RNE", json.dumps(get(B + f"Browse.ashx?render=json&locale=es&c=schedule&id={st2['guide_id']}"), ensure_ascii=False)[:2500])
