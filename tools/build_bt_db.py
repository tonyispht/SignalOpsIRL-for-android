#!/usr/bin/env python3
"""
Builds the offline Bluetooth database that ships inside the Signal OPS IRL app.

    python3 tools/build_bt_db.py            # download fresh lists, then build
    python3 tools/build_bt_db.py --cache DIR  # reuse files already downloaded into DIR

Output (app/src/main/assets/btdb/):
    companies.tsv   Bluetooth SIG company identifiers   (manufacturer data ID -> company)
    uuid16.tsv      16-bit service UUIDs: SIG services, member (company) UUIDs, SDO UUIDs
    appearance.tsv  GAP appearance values (category + subcategory names)
    oui.tsv         IEEE MAC prefixes (MA-L 24-bit, MA-M 28-bit, MA-S 36-bit) -> vendor,
                    used only for Bluetooth devices that advertise a public (fixed) address
    VERSION         build date and entry counts

Sources (public lists, refreshed by this script):
    Bluetooth SIG Assigned Numbers  https://bitbucket.org/bluetooth-SIG/public
    IEEE Registration Authority     https://standards-oui.ieee.org/

Only Bluetooth data is built. No Wi-Fi tables.
"""
import argparse
import csv
import datetime
import io
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "btdb")

SIG = "https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/"
SOURCES = {
    "company_identifiers.yaml": SIG + "company_identifiers/company_identifiers.yaml",
    "service_uuids.yaml": SIG + "uuids/service_uuids.yaml",
    "member_uuids.yaml": SIG + "uuids/member_uuids.yaml",
    "sdo_uuids.yaml": SIG + "uuids/sdo_uuids.yaml",
    "appearance_values.yaml": SIG + "core/appearance_values.yaml",
    "oui.csv": "https://standards-oui.ieee.org/oui/oui.csv",
    "mam.csv": "https://standards-oui.ieee.org/oui28/mam.csv",
    "oui36.csv": "https://standards-oui.ieee.org/oui36/oui36.csv",
}


def fetch(name, cache):
    path = os.path.join(cache, name) if cache else None
    if path and os.path.exists(path):
        with open(path, "rb") as f:
            return f.read().decode("utf-8", "replace")
    url = SOURCES[name]
    print("downloading", url, file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": "SignalOpsIRL-db-builder"})
    with urllib.request.urlopen(req, timeout=180) as r:
        data = r.read()
    if path:
        os.makedirs(cache, exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
    return data.decode("utf-8", "replace")


def clean(s):
    s = s.replace("\t", " ").replace("\r", " ").replace("\n", " ")
    s = re.sub(r"\s+", " ", s).strip()
    # SIG names sometimes end with stray dots ("Amazon.com Services, Inc..")
    s = re.sub(r"\.{2,}$", ".", s)
    return s


# ---- tiny YAML readers (the SIG files are flat lists; no PyYAML needed) -------------------------

ITEM = re.compile(r"^\s*-\s*(\w+):\s*(.*)$")
KV = re.compile(r"^\s*(\w+):\s*(.*)$")


def unquote(v):
    v = v.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
        q = v[0]
        v = v[1:-1]
        if q == "'":
            v = v.replace("''", "'")
    return v


def yaml_list(text, key_field):
    """[{field: value}] for '- key_field: ...' items (one level, which is all these files use)."""
    out, cur = [], None
    for line in text.splitlines():
        if line.lstrip().startswith("#"):
            continue
        m = ITEM.match(line)
        if m and m.group(1) == key_field:
            cur = {key_field: unquote(m.group(2))}
            out.append(cur)
            continue
        m = KV.match(line)
        if m and cur is not None:
            cur[m.group(1)] = unquote(m.group(2))
    return out


def appearance_rows(text):
    """(category, subcategory, category name, subcategory name); indentation separates the two levels."""
    rows, cat, cat_name, sub = [], None, None, None
    for line in text.splitlines():
        if line.lstrip().startswith("#") or not line.strip():
            continue
        s = line.strip()
        if s.startswith("- category:"):
            cat = int(unquote(s.split(":", 1)[1]), 16)
            cat_name, sub = None, None
        elif s.startswith("- value:"):
            sub = int(unquote(s.split(":", 1)[1]), 16)
        elif s.startswith("name:"):
            n = clean(unquote(s.split(":", 1)[1]))
            if sub is None:
                cat_name = n
                rows.append((cat, 0, cat_name, ""))
            else:
                rows.append((cat, sub, cat_name, n))
    return rows


# ---- builders -----------------------------------------------------------------------------------

def build_companies(cache):
    items = yaml_list(fetch("company_identifiers.yaml", cache), "value")
    out = {}
    for it in items:
        try:
            v = int(it["value"], 16)
        except (KeyError, ValueError):
            continue
        n = clean(it.get("name", ""))
        if n:
            out[v] = n
    return out


def build_uuid16(cache):
    out = {}
    for fname, kind in (("service_uuids.yaml", "service"), ("sdo_uuids.yaml", "sdo"), ("member_uuids.yaml", "member")):
        for it in yaml_list(fetch(fname, cache), "uuid"):
            try:
                u = int(it["uuid"], 16)
            except (KeyError, ValueError):
                continue
            n = clean(it.get("name", ""))
            if n and u not in out:
                out[u] = (n, kind)
    return out


def build_oui(cache):
    out = {}
    for fname in ("oui.csv", "mam.csv", "oui36.csv"):
        text = fetch(fname, cache)
        for row in csv.reader(io.StringIO(text)):
            if len(row) < 3 or not row[0].startswith(("MA-", "IAB")):
                continue
            key = row[1].strip().upper()
            if not re.fullmatch(r"[0-9A-F]{6,9}", key):
                continue
            name = clean(row[2])
            if not name or name.lower() == "private":
                continue
            out[key] = name
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", help="folder to keep/reuse the downloaded source files")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)

    companies = build_companies(a.cache)
    uuids = build_uuid16(a.cache)
    appearance = appearance_rows(fetch("appearance_values.yaml", a.cache))
    oui = build_oui(a.cache)

    with open(os.path.join(OUT, "companies.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for k in sorted(companies):
            f.write("%04X\t%s\n" % (k, companies[k]))
    with open(os.path.join(OUT, "uuid16.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for k in sorted(uuids):
            n, kind = uuids[k]
            f.write("%04X\t%s\t%s\n" % (k, n, kind))
    with open(os.path.join(OUT, "appearance.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for cat, sub, cn, sn in appearance:
            f.write("%04X\t%s\t%s\n" % ((cat << 6) | sub, cn, sn))
    with open(os.path.join(OUT, "oui.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for k in sorted(oui):
            f.write("%s\t%s\n" % (k, oui[k]))

    today = datetime.date.today().isoformat()
    with open(os.path.join(OUT, "VERSION"), "w", encoding="utf-8", newline="\n") as f:
        f.write("%s\tcompanies=%d\tuuid16=%d\tappearance=%d\toui=%d\n"
                % (today, len(companies), len(uuids), len(appearance), len(oui)))
    print("built %s: %d companies, %d UUIDs, %d appearance values, %d MAC prefixes"
          % (today, len(companies), len(uuids), len(appearance), len(oui)))


if __name__ == "__main__":
    main()
