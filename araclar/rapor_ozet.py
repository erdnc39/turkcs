# -*- coding: utf-8 -*-
"""rapor_ozet.py — saglik_rapor.json'u GitHub Actions ozetine cevirir."""
import json
import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

YOL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "saglik_rapor.json")

try:
    with open(YOL, encoding="utf-8") as f:
        r = json.load(f)
except Exception as e:
    print("rapor okunamadi: %s" % e)
    raise SystemExit(0)

print("zaman:", r.get("zaman"))
for k, v in (r.get("ozet") or {}).items():
    print("  %-22s %s" % (k, v))

e = r.get("eylem_gerekli") or []
if e:
    print("\nEYLEM GEREKLI:")
    for x in e:
        print("  - %s: %s" % (x.get("eklenti"), x.get("durum")))
        if x.get("onerilen"):
            print("    oneri: %s" % x.get("onerilen"))

t = r.get("eklenti_testi") or {}
if t:
    print("\nGERCEK EKLENTI TESTI:")
    for ad, d in t.items():
        print("  %-18s %-42s mainpage=%-4s arama=%s" % (
            ad, d.get("durum"), d.get("mainpage", 0), d.get("arama", 0)))
