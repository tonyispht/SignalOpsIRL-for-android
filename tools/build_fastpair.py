#!/usr/bin/env python3
"""
Builds app/src/main/assets/btdb/fastpair.tsv: Google Fast Pair model ID -> product name.

    python3 tools/build_fastpair.py [--src fastpair.c]

Google does not publish its Fast Pair model registry. The IDs and names here come from the community list in the
Momentum BLE Spam app (https://github.com/Next-Flip/Momentum-Apps, ble_spam/protocols/fastpair.c), which collected
them from bluetoothdb.com and real devices. Only the ID -> name facts are taken.

Columns: model ID (6 hex digits), name, kind
    audio  earbuds, headphones, speakers (the default)
    watch  smartwatches and bands
    phone  phones that advertise Fast Pair during setup
    spam   IDs used only by BLE spam tools (Flipper Zero, ESP32 "popup" spammers); names are not shown
"""
import argparse
import os
import re
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "btdb", "fastpair.tsv")
URL = "https://raw.githubusercontent.com/Next-Flip/Momentum-Apps/dev/ble_spam/protocols/fastpair.c"

DEV = re.compile(r"test|foocorp|gphones|fake|fast pair headphones|ble-phone|dev board|arduino|android auto|evb|smart setup|"
                 r"smart controller|goodyear|^t10$", re.I)
WATCH = re.compile(r"watch|tag heuer|fitbit|calibre", re.I)
PHONE = re.compile(r"set up your new|galaxy [a-z]\d|^pixel \d", re.I)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", help="local copy of fastpair.c (default: download)")
    a = ap.parse_args()
    if a.src:
        text = open(a.src, encoding="utf-8").read()
    else:
        req = urllib.request.Request(URL, headers={"User-Agent": "SignalOpsIRL-db-builder"})
        text = urllib.request.urlopen(req, timeout=60).read().decode("utf-8")

    rows, seen, section = [], set(), None
    for line in text.splitlines():
        if "// Genuine non-production" in line:
            section = "dev"
        elif "// Phone setup" in line:
            section = "phone"
        elif "// Genuine devices" in line:
            section = "genuine"
        elif "// Custom debug" in line:
            section = "spam"
        m = re.search(r'\{0x([0-9A-Fa-f]{6}),\s*"([^"]*)"\}', line)
        if not m or section in (None, "dev"):
            continue
        mid, name = m.group(1).upper(), m.group(2).strip()
        if mid in seen:
            continue
        if section == "spam":
            kind, name = "spam", "BLE spam popup"
        elif DEV.search(name):
            continue
        elif section == "phone" or PHONE.search(name):
            kind = "phone"
        elif WATCH.search(name):
            kind = "watch"
        else:
            kind = "audio"
        seen.add(mid)
        rows.append((mid, name.replace("\t", " "), kind))

    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        for r in sorted(rows):
            f.write("\t".join(r) + "\n")
    print("wrote %d models to %s" % (len(rows), OUT))


if __name__ == "__main__":
    main()
