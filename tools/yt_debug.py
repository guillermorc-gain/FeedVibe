"""Diagnóstico: reproduce las peticiones de FeedVibe a YouTube (lista de subidas + continuaciones)."""
import json, re, sys, urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"
HDR = {"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9", "Cookie": "SOCS=CAI; CONSENT=YES+cb"}

def get(url):
    req = urllib.request.Request(url, headers=HDR)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, r.read().decode("utf-8", "replace")

def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={**HDR, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:500]

def initial_data(html):
    for m in ["var ytInitialData = ", 'window["ytInitialData"] = ', "ytInitialData = "]:
        i = html.find(m)
        if i >= 0:
            i += len(m); j = html.find(";</script>", i)
            return json.loads(html[i:j])

KEYS = {"videoRenderer", "gridVideoRenderer", "playlistVideoRenderer", "lockupViewModel"}
def renderers(e, out, path=""):
    if isinstance(e, dict):
        for k, v in e.items():
            if k in KEYS and isinstance(v, dict): out.append(k)
            else: renderers(v, out, path + "/" + k)
    elif isinstance(e, list):
        for x in e: renderers(x, out, path)
    return out

def continuations(e, out, path=""):
    if isinstance(e, dict):
        for k, v in e.items():
            if k == "continuationCommand" and isinstance(v, dict) and "token" in v: out.append((path + "/" + k, v["token"][:25]))
            if k == "nextContinuationData": out.append((path + "/" + k, str(v.get("continuation"))[:25]))
            continuations(v, out, path + "/" + k)
    elif isinstance(e, list):
        for i, x in enumerate(e): continuations(x, out, path + f"[{i}]")
    return out

SKIP = {"engagementPanels", "header", "frameworkUpdates", "topbar", "sidebar"}
def app_token(e):
    """Lógica nueva de la app: último token dentro de continuationItemRenderer/ViewModel, sin paneles laterales."""
    found = None
    def inside(x):
        if isinstance(x, dict):
            if isinstance(x.get("continuationCommand"), dict) and "token" in x["continuationCommand"]:
                return x["continuationCommand"]["token"]
            for v in x.values():
                t = inside(v)
                if t: return t
        elif isinstance(x, list):
            for v in x:
                t = inside(v)
                if t: return t
    def walk(x):
        nonlocal found
        if isinstance(x, dict):
            for k, v in x.items():
                if k in SKIP: continue
                if k in ("continuationItemRenderer", "continuationItemViewModel"):
                    t = inside(v)
                    if t: found = t
                else:
                    walk(v)
        elif isinstance(x, list):
            for v in x: walk(v)
    walk(e)
    return found

def run(label, url):
    print(f"\n===== {label}: {url}")
    st, html = get(url)
    print("status", st, "len", len(html))
    data = initial_data(html)
    if data is None:
        print("NO ytInitialData; title:", re.search(r"<title>(.*?)</title>", html).group(1)); return
    r = renderers(data, [])
    print("renderers:", {k: r.count(k) for k in set(r)})
    shown = 0
    def show(e):
        nonlocal shown
        if shown >= 3: return
        if isinstance(e, dict):
            for k, v in e.items():
                if k == "lockupViewModel" and isinstance(v, dict) and shown < 3:
                    shown += 1
                    title = (((v.get("metadata") or {}).get("lockupMetadataViewModel") or {}).get("title") or {}).get("content")
                    strs = []
                    def allstr(x):
                        if isinstance(x, dict): [allstr(y) for y in x.values()]
                        elif isinstance(x, list): [allstr(y) for y in x]
                        elif isinstance(x, str) and len(x) < 60: strs.append(x)
                    allstr(v)
                    print("   lockup:", v.get("contentId"), v.get("contentType"), "|", title)
                    print("      ago:", [x for x in strs if "ago" in x], "dur:", [x for x in strs if re.match(r"^\d{1,2}(:\d{2}){1,2}$", x.strip())])
                else: show(v)
        elif isinstance(e, list):
            for x in e: show(x)
    show(data)
    conts = continuations(data, [])
    print("continuations:", len(conts))
    for p, t in conts[-4:]: print("   ", p[-200:], t)
    ver = re.search(r'"INNERTUBE_CLIENT_VERSION":"([^"]+)"', html)
    vis = re.search(r'"VISITOR_DATA":"([^"]+)"', html)
    print("clientVersion", ver and ver.group(1), "visitorData", bool(vis))
    if not conts: return
    token = app_token(data)
    print("app token found:", bool(token))
    total = len(r)
    for page in range(1, 3):
        if not token: break
        body = {"context": {"client": {"clientName": "WEB", "clientVersion": ver.group(1) if ver else "2.20250101.00.00", "hl": "en", "gl": "US",
                                       **({"visitorData": vis.group(1)} if vis else {})}}, "continuation": token}
        st, resp = post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body)
        if not isinstance(resp, dict):
            if page % 5 == 1 or not token: print(f"page {page}: HTTP {st}: {resp}"); break
        rr = renderers(resp, [])
        total += len(rr)
        token = app_token(resp)
        if page % 5 == 1 or not token: print(f"page {page}: HTTP {st} keys={list(resp.keys())[:6]} renderers={ {k: rr.count(k) for k in set(rr)} } next={bool(token)} total={total}")

cid = "UCDoiP7u4X3i_FWrNbi_6wZA"
for handle in ["@UnTioBlancoHetero", "@untioblancohetero"]:
    try:
        st, html = get(f"https://www.youtube.com/{handle}")
        m = re.search(r'<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[\w-]{22})', html)
        print(handle, st, m and m.group(1))
        if m: cid = m.group(1); break
    except Exception as e:
        print(handle, "error", e)
base = cid[2:]
run("UU playlist", f"https://www.youtube.com/playlist?list=UU{base}&hl=en&gl=US")
run("videos tab", f"https://www.youtube.com/channel/{cid}/videos?hl=en&gl=US")
