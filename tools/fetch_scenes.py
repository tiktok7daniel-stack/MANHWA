"""
MANHWA: fetches background scenes for the manhwa pages from free-to-use sources only, and adds them to the app.

Sources (no key, no account):
  - Openverse (openverse.org), CC0 and public-domain pictures only.
  - NASA Image Library (images.nasa.gov): NASA's pictures are public domain.

Each picture is checked (large enough, dark enough for text on top), cropped to a phone's shape, made small (WebP),
sorted into a colour family by its hue, and written to res/drawable-nodpi. A Kotlin list of them and a credits page
(source, author, licence of each) are written next to them.
"""
import colorsys
import io
import json
import os
import sys
import time
import urllib.parse
import urllib.request

from PIL import Image, ImageStat

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res/drawable-nodpi")
KOTLIN = os.path.join(ROOT, "app/src/main/java/eu/kanade/presentation/manga/components/GeneratedScenes.kt")
CREDITS = os.path.join(ROOT, "docs/SCENES_CREDITS.md")
MANIFEST = os.path.join(ROOT, "tools/scenes.json")
TARGET = int(os.environ.get("SCENES_TARGET", "100"))
UA = {"User-Agent": "MANHWA-app-scenes/1.0 (personal reader app; github.com/tiktok7daniel-stack/MANHWA)"}

OPENVERSE_QUERIES = [
    "fantasy castle night", "ancient temple ruins", "moonlit ruins", "dark fantasy landscape", "gothic cathedral interior",
    "misty mountains night", "foggy forest night", "crystal cave", "starry sky mountains", "aurora borealis",
    "milky way night", "old library", "castle silhouette moon", "volcano night", "stormy sky lightning",
    "neon city night", "abandoned temple", "dark ocean night", "snowy mountains night", "ancient columns",
]
NASA_QUERIES = ["nebula", "galaxy", "star cluster", "aurora", "supernova"]


def get(url, timeout=30):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def openverse(query):
    params = urllib.parse.urlencode({
        "q": query, "license": "cc0,pdm", "size": "large", "mature": "false", "page_size": 20,
    })
    try:
        data = json.loads(get(f"https://api.openverse.org/v1/images/?{params}"))
    except Exception as e:
        print("openverse failed", query, e)
        return []
    out = []
    for r in data.get("results", []):
        out.append({
            "url": r.get("url"), "source": r.get("foreign_landing_url") or r.get("url"),
            "title": r.get("title") or query, "creator": r.get("creator") or "unknown",
            "license": f'{(r.get("license") or "").upper()} {r.get("license_version") or ""}'.strip(),
        })
    return out


def nasa(query):
    try:
        data = json.loads(get("https://images-api.nasa.gov/search?" + urllib.parse.urlencode({"q": query, "media_type": "image"})))
    except Exception as e:
        print("nasa failed", query, e)
        return []
    out = []
    for item in data.get("collection", {}).get("items", [])[:30]:
        links = item.get("links") or []
        meta = (item.get("data") or [{}])[0]
        if not links:
            continue
        thumb = links[0].get("href", "")
        out.append({
            "url": thumb.replace("~thumb", "~large"), "source": f'https://images.nasa.gov/details/{meta.get("nasa_id", "")}',
            "title": meta.get("title", query), "creator": meta.get("center", "NASA"), "license": "Public domain (NASA)",
        })
    return out


def family(img):
    small = img.resize((48, 48))
    hues = []
    for r, g, b in small.getdata():
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if s > 0.25 and v > 0.18:
            hues.append(h * 360)
    if len(hues) < 48 * 48 * 0.12:
        return "MONO"
    hues.sort()
    h = hues[len(hues) // 2]
    if 200 <= h < 250:
        return "BLUE"
    if 250 <= h < 335:
        return "PURPLE"
    if 160 <= h < 200:
        return "TEAL"
    if 70 <= h < 160:
        return "GREEN"
    return "WARM"


def prepare(raw):
    img = Image.open(io.BytesIO(raw)).convert("RGB")
    w, h = img.size
    if min(w, h) < 900:
        return None, "small"
    # A phone's shape, cut from the middle
    tw = min(w, int(h * 9 / 16))
    th = min(h, int(tw * 16 / 9))
    left, top = (w - tw) // 2, (h - th) // 2
    img = img.crop((left, top, left + tw, top + th)).resize((720, 1280), Image.LANCZOS)
    mean = sum(ImageStat.Stat(img.convert("L")).mean) / 1
    if mean > 135:
        return None, "bright"
    return img, None


def main():
    os.makedirs(RES, exist_ok=True)
    # Earlier runs: keep their pictures, add new ones after them
    done = []
    if os.path.exists(MANIFEST):
        done = json.load(open(MANIFEST))
    seen = {d["url"] for d in done}
    candidates = []
    for q in OPENVERSE_QUERIES:
        candidates += openverse(q)
        time.sleep(1.5)
    for q in NASA_QUERIES:
        candidates += nasa(q)
        time.sleep(0.5)
    print("candidates", len(candidates))
    counts = {}
    for c in candidates:
        if len(done) >= TARGET:
            break
        url = c.get("url")
        if not url or url in seen:
            continue
        seen.add(url)
        try:
            img, why = prepare(get(url, timeout=40))
        except Exception as e:
            print("skip", url, e)
            continue
        if img is None:
            print("skip", why, url)
            continue
        fam = family(img)
        # No more than a third of the scenes in one family
        if counts.get(fam, 0) >= max(12, TARGET // 3):
            continue
        counts[fam] = counts.get(fam, 0) + 1
        name = f"scene_{len(done) + 1:03d}"
        img.save(os.path.join(RES, name + ".webp"), "WEBP", quality=62, method=6)
        done.append({**c, "name": name, "family": fam})
        print("kept", name, fam, url)
    json.dump(done, open(MANIFEST, "w"), indent=1)
    lines = [
        "package eu.kanade.presentation.manga.components",
        "",
        "import eu.kanade.tachiyomi.R",
        "",
        "/** MANHWA: the scenes fetched by tools/fetch_scenes.py (free to use; see docs/SCENES_CREDITS.md). Generated. */",
        "object GeneratedScenes {",
        "    val all: List<BackdropScene> = listOf(",
    ]
    for d in done:
        lines.append(f'        BackdropScene("{d["name"].upper()}", R.drawable.{d["name"]}, SceneFamily.{d["family"]}),')
    lines += ["    )", "}", ""]
    open(KOTLIN, "w").write("\n".join(lines))
    with open(CREDITS, "w") as f:
        f.write("# Background scenes: credits\n\nFree to use: CC0, public domain, or NASA (public domain). Fetched by `tools/fetch_scenes.py`.\n\n")
        f.write("| File | Title | Author | Licence | Source |\n|---|---|---|---|---|\n")
        for d in done:
            t = (d["title"] or "").replace("|", " ")[:80]
            f.write(f'| {d["name"]} | {t} | {d["creator"]} | {d["license"]} | {d["source"]} |\n')
    print("total", len(done), counts)
    return 0 if done else 1


if __name__ == "__main__":
    sys.exit(main())
