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

def run(label, url):
    print(f"\n===== {label}: {url}")
    st, html = get(url)
    print("status", st, "len", len(html))
    data = initial_data(html)
    if data is None:
        print("NO ytInitialData; title:", re.search(r"<title>(.*?)</title>", html).group(1)); return
    r = renderers(data, [])
    print("renderers:", {k: r.count(k) for k in set(r)})
    conts = continuations(data, [])
    print("continuations:", len(conts))
    for p, t in conts[-4:]: print("   ", p[-200:], t)
    ver = re.search(r'"INNERTUBE_CLIENT_VERSION":"([^"]+)"', html)
    vis = re.search(r'"VISITOR_DATA":"([^"]+)"', html)
    print("clientVersion", ver and ver.group(1), "visitorData", bool(vis))
    if not conts: return
    token = None
    # token como lo busca la app: dentro de continuationItemRenderer
    def find_cir(e):
        nonlocal token
        if isinstance(e, dict):
            if "continuationItemRenderer" in e:
                c = continuations(e["continuationItemRenderer"], [])
                if c:
                    # token completo
                    def full(x):
                        if isinstance(x, dict):
                            if "continuationCommand" in x: return x["continuationCommand"]["token"]
                            for v in x.values():
                                f = full(v)
                                if f: return f
                        if isinstance(x, list):
                            for v in x:
                                f = full(v)
                                if f: return f
                    token = full(e["continuationItemRenderer"])
            for v in e.values(): find_cir(v)
        elif isinstance(e, list):
            for v in e: find_cir(v)
    find_cir(data)
    print("app token found:", bool(token))
    total = len(r)
    for page in range(1, 6):
        if not token: break
        body = {"context": {"client": {"clientName": "WEB", "clientVersion": ver.group(1) if ver else "2.20250101.00.00", "hl": "en", "gl": "US",
                                       **({"visitorData": vis.group(1)} if vis else {})}}, "continuation": token}
        st, resp = post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body)
        if not isinstance(resp, dict):
            print(f"page {page}: HTTP {st}: {resp}"); break
        rr = renderers(resp, [])
        total += len(rr)
        token = None
        find_cir(resp)
        print(f"page {page}: HTTP {st} keys={list(resp.keys())[:6]} renderers={ {k: rr.count(k) for k in set(rr)} } next={bool(token)} total={total}")

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
