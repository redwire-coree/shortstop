#!/usr/bin/env python3
"""UI helpers for device-test.sh: read a `uiautomator dump` and find elements by text or content description.
usage: ui.py find DUMP.xml REGEX   -> prints "x y" of the centre of the first match (exit 1 if none)
       ui.py texts DUMP.xml        -> prints every text / content-desc that is on screen"""
import re, sys
import xml.etree.ElementTree as ET

def nodes(path):
    try:
        root = ET.parse(path).getroot()
    except Exception:
        return
    for n in root.iter("node"):
        label = (n.get("text") or "").strip() or (n.get("content-desc") or "").strip()
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if label and m:
            x1, y1, x2, y2 = map(int, m.groups())
            yield label, (x1 + x2) // 2, (y1 + y2) // 2, x2 - x1, y2 - y1

if __name__ == "__main__":
    cmd, path = sys.argv[1], sys.argv[2]
    if cmd == "find":
        rx = re.compile(sys.argv[3], re.I)
        for label, cx, cy, w, h in nodes(path):
            if rx.search(label) and w > 0 and h > 0:
                print(cx, cy); sys.exit(0)
        sys.exit(1)
    if cmd == "texts":
        for label, cx, cy, w, h in nodes(path):
            print(f"{label!r} @({cx},{cy}) {w}x{h}")
