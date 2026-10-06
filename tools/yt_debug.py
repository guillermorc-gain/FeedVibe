"""Diagnóstico 2: Shorts, listas UULF/UULV/UUSH, títulos y fechas en español."""
import json, re, urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"
def get(url, lang):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": lang, "Cookie": "SOCS=CAI; CONSENT=YES+cb"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r: return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e: return e.code, ""

def initial_data(html):
    i = html.find("var ytInitialData = ")
    if i < 0: return None
    i += len("var ytInitialData = "); return json.loads(html[i:html.find(";</script>", i)])

def lockups(e, out):
    if isinstance(e, dict):
        for k, v in e.items():
            if k in ("lockupViewModel", "playlistVideoRenderer", "reelItemRenderer", "shortsLockupViewModel") and isinstance(v, dict): out.append((k, v))
            else: lockups(v, out)
    elif isinstance(e, list):
        for x in e: lockups(x, out)
    return out

def strings(x, out):
    if isinstance(x, dict): [strings(y, out) for y in x.values()]
    elif isinstance(x, list): [strings(y, out) for y in x]
    elif isinstance(x, str) and len(x) < 70: out.append(x)
    return out

cid = "UCW3iqZr2cQFYKdO9Kpa97Yw"  # UTBH
base = cid[2:]
# 1) RSS: ¿los Shorts llevan /shorts/ en el enlace?
for q in [f"channel_id={cid}", f"playlist_id=UUSH{base}", f"playlist_id=UULF{base}"]:
    st, xml = get(f"https://www.youtube.com/feeds/videos.xml?{q}", "es-ES")
    links = re.findall(r'<link rel="alternate" href="([^"]+)"', xml)
    print(f"RSS {q}: HTTP {st}, enlaces={len(links)}, con /shorts/={sum('/shorts/' in l for l in links)}, ejemplo={links[1:3]}")
# 2) Listas de reproducción por tipo + 3) títulos/fechas en español
for pl in ["UULF", "UULV", "UUSH", "UU"]:
    st, html = get(f"https://www.youtube.com/playlist?list={pl}{base}&hl=es&gl=ES", "es-ES,es;q=0.9")
    d = initial_data(html) if html else None
    lk = lockups(d, []) if d else []
    print(f"\nLista {pl}: HTTP {st}, elementos primera página={len(lk)}, tipos={sorted(set(k for k,_ in lk))}")
    for k, v in lk[:2]:
        title = (((v.get("metadata") or {}).get("lockupMetadataViewModel") or {}).get("title") or {}).get("content")
        s = strings(v, [])
        print("   ", v.get("contentId"), "|", title)
        print("      fechas:", [x for x in s if "hace" in x.lower() or "ago" in x.lower()][:3], "dur:", [x for x in s if re.match(r"^\d{1,2}(:\d{2}){1,2}$", x.strip())][:2])
