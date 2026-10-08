"""Diagnóstico 4: catálogo Radio Browser (España): comunidades, estilos y datos de emisora."""
import json, urllib.request, urllib.parse
UA = "FeedVibe/1.0 (diagnóstico)"
def get(path):
    for host in ["de1.api.radio-browser.info", "fi1.api.radio-browser.info", "nl1.api.radio-browser.info", "all.api.radio-browser.info"]:
        try:
            req = urllib.request.Request(f"https://{host}{path}", headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                return host, json.loads(r.read().decode())
        except Exception as e:
            print("  fallo", host, e)
    return None, None

h, states = get("/json/states/Spain/?hidebroken=true&order=stationcount&reverse=true")
print("HOST", h, "estados:", len(states or []))
for s in (states or [])[:60]: print("  ", s.get("name"), s.get("stationcount"))
h, st = get("/json/stations/search?" + urllib.parse.urlencode({"countrycode": "ES", "hidebroken": "true", "order": "clickcount", "reverse": "true", "limit": 40}))
print("\nemisoras ES:", len(st or []))
for x in (st or [])[:40]:
    print("  ", x.get("name"), "|", x.get("state"), "|", x.get("tags")[:50], "|", x.get("codec"), x.get("bitrate"), "| hls", x.get("hls"))
print(json.dumps((st or [{}])[0], ensure_ascii=False, indent=1)[:2500])
h, tags = get("/json/tags?order=stationcount&reverse=true&limit=60&hidebroken=true")
print("\ntags:", [t.get("name") for t in (tags or [])])
h, st2 = get("/json/stations/search?" + urllib.parse.urlencode({"countrycode": "ES", "state": "Balearic Islands", "hidebroken": "true", "limit": 10}))
print("\nBaleares:", [(x.get("name"), x.get("state")) for x in (st2 or [])])
h, same = get("/json/stations/search?" + urllib.parse.urlencode({"name": "Cadena SER", "countrycode": "ES", "hidebroken": "true", "limit": 15}))
print("\nVariantes Cadena SER:", [(x.get("name"), x.get("codec"), x.get("bitrate"), x.get("url_resolved")[:60]) for x in (same or [])])
