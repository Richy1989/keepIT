#!/usr/bin/env python3
"""Drives the emulator by what is on screen, for the phone screenshots. See README.md in this folder.

    adb-ui.py ls                      everything on screen that has a text or a description
    adb-ui.py tap TEXT [N]            tap the Nth thing (from 0) with that text or description
    adb-ui.py long TEXT [N]           long-press it
    adb-ui.py wait TEXT [SECONDS]     wait until it is on screen (20 s unless told otherwise)
    adb-ui.py shot FILE               save the screen as a PNG
    adb-ui.py demo on|off             a clean status bar: 9:41, full Wi-Fi and battery, no icons

tap and long take --contains to match part of a text. Texts are found in a `uiautomator dump`, so
a tap lands wherever the control is, at any font size or density. Scrolling and typing are plain
adb: `adb shell input swipe ...`, `adb shell input text ...`.

Needs only Python and the SDK's adb (ANDROID_HOME, ANDROID_SDK_ROOT, the PATH, or ~/Android/Sdk).
"""
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def find_adb():
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if root and os.path.exists(os.path.join(root, "platform-tools", "adb")):
            return os.path.join(root, "platform-tools", "adb")
    return shutil.which("adb") or os.path.expanduser("~/Android/Sdk/platform-tools/adb")


ADB = find_adb()


def adb(*args, binary=False):
    out = subprocess.run([ADB, *args], capture_output=True, check=False).stdout
    return out if binary else out.decode("utf-8", "replace")


def nodes():
    # A dump fails while the screen is animating, so try a few times.
    for _ in range(4):
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        start, end = raw.find("<?xml"), raw.rfind("</hierarchy>")
        if start >= 0 and end > start:
            break
        time.sleep(0.7)
    else:
        raise SystemExit("no ui dump")
    found = []
    for n in ET.fromstring(raw[start:end + len("</hierarchy>")]).iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        found.append({
            "text": n.get("text", ""), "desc": n.get("content-desc", ""),
            "cls": n.get("class", "").split(".")[-1], "click": n.get("clickable") == "true",
            "box": (x1, y1, x2, y2), "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
        })
    return found


def find(label, index=0, exact=True):
    hits = [n for n in nodes() if (label == n["text"] or label == n["desc"]) or
            (not exact and (label in n["text"] or label in n["desc"]))]
    if len(hits) <= index:
        raise SystemExit(f"not on screen: {label!r} (index {index}, {len(hits)} found)")
    return hits[index]


def demo(*extras):
    adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", *extras)


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "ls":
        for n in nodes():
            if n["text"] or n["desc"]:
                print(f'{n["cx"]:5} {n["cy"]:5}  {n["cls"]:18} {"C" if n["click"] else " "} '
                      f'text={n["text"]!r} desc={n["desc"]!r} box={n["box"]}')
    elif cmd in ("tap", "long") and args:
        exact = "--contains" not in args
        args = [a for a in args if a != "--contains"]
        n = find(args[0], int(args[1]) if len(args) > 1 else 0, exact)
        x, y = str(n["cx"]), str(n["cy"])
        if cmd == "tap":
            adb("shell", "input", "tap", x, y)
        else:
            adb("shell", "input", "swipe", x, y, x, y, "900")
        print(x, y, n["box"])
    elif cmd == "wait" and args:
        deadline = time.time() + (float(args[1]) if len(args) > 1 else 20)
        while time.time() < deadline:
            if any(args[0] in (n["text"], n["desc"]) for n in nodes()):
                return
            time.sleep(0.8)
        raise SystemExit(f"never appeared: {args[0]!r}")
    elif cmd == "shot" and args:
        png = adb("exec-out", "screencap", "-p", binary=True)
        with open(args[0], "wb") as f:
            f.write(png)
    elif cmd == "demo" and args and args[0] == "on":
        adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
        demo("-e", "command", "enter")
        demo("-e", "command", "clock", "-e", "hhmm", "0941")
        demo("-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4",
             "-e", "mobile", "hide", "-e", "fully", "true")
        demo("-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false")
        demo("-e", "command", "notifications", "-e", "visible", "false")
        demo("-e", "command", "status", "-e", "location", "hide", "-e", "alarm", "hide",
             "-e", "volume", "hide", "-e", "bluetooth", "hide")
        # The icons fade in: a screenshot taken at once catches the Wi-Fi symbol half lit.
        time.sleep(2)
    elif cmd == "demo" and args and args[0] == "off":
        demo("-e", "command", "exit")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
