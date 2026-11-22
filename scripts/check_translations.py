#!/usr/bin/env python3
"""Prüft, ob jeder Text in L("…") eine englische Übersetzung in L10nTable.swift hat.
Aufruf im Repo-Wurzelverzeichnis: python3 scripts/check_translations.py (Exit-Code 1 bei Lücken)."""
import pathlib, re, sys

root = pathlib.Path(__file__).resolve().parent.parent
table = (root / "Sources/ListwerkCore/Localization/L10nTable.swift").read_text(encoding="utf-8")
known = {m.group(1) for m in re.finditer(r'^\s*"((?:[^"\\]|\\.)*)":', table, re.M)}
missing = {}
for folder in ("App", "Sources"):
    for f in (root / folder).rglob("*.swift"):
        if f.name.startswith("L10n"):
            continue
        for m in re.finditer(r'\bL\("((?:[^"\\]|\\.)*)"', f.read_text(encoding="utf-8")):
            if m.group(1) not in known:
                missing.setdefault(m.group(1), str(f.relative_to(root)))
for key, where in sorted(missing.items()):
    print(f"::warning file={where}::Keine englische Übersetzung: {key}")
print(f"{len(known)} Übersetzungen, {len(missing)} fehlen.")
sys.exit(1 if missing else 0)
