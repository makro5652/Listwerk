#!/usr/bin/env python3
"""Erzeugt shared/themes/*.json und shared/palettes/*.json.

Catppuccin-Werte stammen unveraendert aus scripts/catppuccin-palette.json
(Quelle: https://github.com/catppuccin/palette, MIT-Lizenz). Die uebrigen
Themes und die Farbkarten-Palette sind eigene Definitionen.
"""
import json, pathlib, colorsys

root = pathlib.Path(__file__).resolve().parent.parent
pal = json.loads((root / "scripts/catppuccin-palette.json").read_text())
themes_dir = root / "shared/themes"
pal_dir = root / "shared/palettes"
themes_dir.mkdir(parents=True, exist_ok=True)
pal_dir.mkdir(parents=True, exist_ok=True)

ACCENTS = ["rosewater", "flamingo", "pink", "mauve", "red", "maroon", "peach",
           "yellow", "green", "teal", "sky", "sapphire", "blue", "lavender"]
FLAVORS = ["latte", "frappe", "macchiato", "mocha"]
NAMES = {"latte": "Latte", "frappe": "Frappé", "macchiato": "Macchiato", "mocha": "Mocha"}

def hexof(flavor, color):
    return pal[flavor]["colors"][color]["hex"].lower()

def write(path, obj):
    path.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n")

for f in FLAVORS:
    c = lambda n: hexof(f, n)
    write(themes_dir / f"catppuccin-{f}.json", {
        "id": f"catppuccin-{f}",
        "name": f"Catppuccin {NAMES[f]}",
        "isDark": bool(pal[f]["dark"]),
        "license": "Catppuccin palette, MIT License, https://github.com/catppuccin/palette",
        "colors": {
            "background": c("base"), "sidebar": c("mantle"),
            "surface": c("crust" if f == "latte" else "surface0"),  # Latte: surface0 verfehlt WCAG AA (4,05:1) für Nebentext
            "surfaceElevated": c("surface1"),
            "textPrimary": c("text"), "textSecondary": c("subtext1"),
            "accent": c("blue"), "separator": c("surface2"),
            "success": c("green"), "warning": c("yellow"), "danger": c("red"),
        },
    })

write(themes_dir / "papier.json", {
    "id": "papier", "name": "Papier", "isDark": False,
    "colors": {
        "background": "#fbfaf7", "sidebar": "#f1eee6", "surface": "#ffffff",
        "surfaceElevated": "#f7f4ec", "textPrimary": "#1f1d1a",
        "textSecondary": "#55524b", "accent": "#2f6fde", "separator": "#d8d3c6",
        "success": "#2f8a3e", "warning": "#b36b00", "danger": "#c4291c",
    },
})
write(themes_dir / "graphit.json", {
    "id": "graphit", "name": "Graphit", "isDark": True,
    "colors": {
        "background": "#1b1d1f", "sidebar": "#141516", "surface": "#25282b",
        "surfaceElevated": "#2f3337", "textPrimary": "#eceeef",
        "textSecondary": "#b4babf", "accent": "#5db0f0", "separator": "#3b4045",
        "success": "#5cc26f", "warning": "#e0a53a", "danger": "#f0766a",
    },
})

# Eintragsfarben, Palette 1: Catppuccin. Gespeichert wird der Latte-Wert.
write(pal_dir / "catppuccin.json", {
    "id": "catppuccin", "name": "Catppuccin",
    "storedVariant": "latte",
    "colors": [
        {"id": a, "name": a.capitalize(), **{f: hexof(f, a) for f in FLAVORS}}
        for a in ACCENTS
    ],
})

# Eintragsfarben, Palette 2: eigene Farbkarten (bewusst ohne Markenbezug).
KARTEN = [
    ("mohnrot", "Mohnrot", "#e03c31"), ("koralle", "Koralle", "#ff6f59"),
    ("mandarine", "Mandarine", "#f28c28"), ("honig", "Honig", "#f2b134"),
    ("zitrone", "Zitrone", "#f7dc4a"), ("limette", "Limette", "#b5d334"),
    ("wiese", "Wiese", "#5db04b"), ("tanne", "Tanne", "#1f7a5a"),
    ("lagune", "Lagune", "#17a2a2"), ("himmel", "Himmel", "#4fb3e8"),
    ("kobalt", "Kobalt", "#2f6fde"), ("indigo", "Indigo", "#4b4fbf"),
    ("veilchen", "Veilchen", "#8a5cd0"), ("orchidee", "Orchidee", "#c45bc8"),
    ("fuchsia", "Fuchsia", "#e0457b"), ("rose", "Rosé", "#f29ab2"),
    ("terrakotta", "Terrakotta", "#b5651d"), ("kaffee", "Kaffee", "#7b4b2a"),
    ("schiefer", "Schiefer", "#5b6770"), ("graphit", "Graphit", "#2e3338"),
]

def lighten(hexv, amount=0.22):
    r, g, b = (int(hexv[i:i+2], 16) / 255 for i in (1, 3, 5))
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    l = min(1.0, l + (1 - l) * amount)
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    return "#%02x%02x%02x" % (round(r*255), round(g*255), round(b*255))

write(pal_dir / "farbkarten.json", {
    "id": "farbkarten", "name": "Farbkarten", "storedVariant": "hex",
    "colors": [{"id": i, "name": n, "hex": h, "hexDark": lighten(h)} for i, n, h in KARTEN],
})
print("ok:", sorted(p.name for p in themes_dir.iterdir()), sorted(p.name for p in pal_dir.iterdir()))

# Kopie als Swift-Package-Ressource (shared/ bleibt die kanonische Quelle).
import shutil
res = root / "Sources/ListwerkCore/Resources"
for sub in ("themes", "palettes"):
    dst = res / sub
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(root / "shared" / sub, dst)
print("Ressourcen kopiert nach", res)
