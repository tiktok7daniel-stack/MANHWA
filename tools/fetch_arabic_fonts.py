"""MANHWA: fetch the Arabic title fonts from Google Fonts (all OFL), cut them down to Arabic and basic Latin, and
write them to app/src/main/res/font/ar_<key>.ttf plus the credits list. Run from the repo root."""
import os
import re
import subprocess
import sys
import tempfile

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

RAW = "https://raw.githubusercontent.com/google/fonts/main/ofl/"
FONTS = [
    "arefruqaa", "arefruqaaink", "reemkufi", "reemkufifun", "reemkufiink", "rakkas", "lalezar", "jomhuria",
    "katibeh", "lemonada", "marhey", "kufam", "blaka", "blakahollow", "blakaink", "gulzar", "qahiri", "elmessiri",
    "mirza", "amiri", "amiriquran", "cairo", "tajawal", "changa", "harmattan", "lateef", "scheherazadenew",
    "markazitext", "vibes", "alkalami", "badeendisplay", "notokufiarabic", "notonaskharabic", "readexpro",
    "almarai", "mada", "baloobhaijaan2", "handjet", "zain", "ruwudu", "playpensansarabic", "notonastaliqurdu",
]
UNICODES = "U+0020-007E,U+00A0,U+0600-06FF,U+0750-077F,U+08A0-08FF,U+FB50-FDFF,U+FE70-FEFF,U+200C-200F,U+25CC"


def get(url, out):
    r = subprocess.run(["curl", "-sSfL", "-o", out, url])
    return r.returncode == 0


def main(res_dir, credits_out):
    tmp = tempfile.mkdtemp()
    rows = []
    for key in FONTS:
        meta = os.path.join(tmp, key + ".pb")
        if not get(RAW + key + "/METADATA.pb", meta):
            print("no metadata", key)
            continue
        text = open(meta, encoding="utf-8").read()
        name = re.search(r'^name: "([^"]+)"', text, re.M).group(1)
        licence = re.search(r'^license: "([^"]+)"', text, re.M).group(1)
        files = re.findall(r'fonts \{(.*?)\n\}', text, re.S)
        entries = []
        for f in files:
            fn = re.search(r'filename: "([^"]+)"', f).group(1)
            style = re.search(r'style: "([^"]+)"', f).group(1)
            weight = int(re.search(r'weight: (\d+)', f).group(1))
            entries.append((fn, style, weight))
        normal = [e for e in entries if e[1] == "normal"] or entries
        # Titles look best bold: the variable font at 700, else the static file nearest to it
        pick = min(normal, key=lambda e: (0 if "[" in e[0] else 1, abs(e[2] - 700)))
        src = os.path.join(tmp, pick[0])
        if not get(RAW + key + "/" + pick[0].replace("[", "%5B").replace("]", "%5D"), src):
            print("no file", key, pick[0])
            continue
        font = TTFont(src)
        if "fvar" in font:
            axes = {a.axisTag: a for a in font["fvar"].axes}
            limits = {}
            for tag, a in axes.items():
                want = 700 if tag == "wght" else a.defaultValue
                limits[tag] = max(a.minValue, min(a.maxValue, want))
            font = instancer.instantiateVariableFont(font, limits)
            font.save(src)
        out = os.path.join(res_dir, "ar_" + key + ".ttf")
        subprocess.run([
            "pyftsubset", src, "--unicodes=" + UNICODES, "--layout-features=*", "--glyph-names", "--symbol-cmap",
            "--legacy-cmap", "--notdef-glyph", "--notdef-outline", "--recommended-glyphs", "--name-IDs=*",
            "--name-languages=*", "--drop-tables-=COLR,CPAL", "--output-file=" + out,
        ], check=True)
        rows.append((key, name, pick[0], licence, os.path.getsize(out)))
        print(key, name, pick[0], os.path.getsize(out))
    with open(credits_out, "w", encoding="utf-8") as c:
        for key, name, fn, licence, size in rows:
            c.write(f"{key}\t{name}\t{fn}\t{licence}\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
