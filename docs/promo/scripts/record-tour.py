#!/usr/bin/env python3
"""Records the phone footage of the promo video: one take of the real app, driven by a script.

    record-tour.py SERVER_URL

SERVER_URL is the server as the emulator reaches it (http://10.0.2.2:PORT for the host's
localhost). It must be seeded by seed-demo.sh and seed-promo.sh and not used since: the tour ticks
two items and recolours a note, so a second take on the same server would show them done already.
The app (org.hyperstarit.keepitapp) must be installed; the script clears it, signs in as Anna and
turns on the emulator's demo status bar before it records.

It writes public/tour.mp4 (30 fps, constant) and src/tour-marks.json: the seconds into the
footage at which each step began, and where on the screen the faces of the shared note ended up. The video cuts by those marks rather than by fixed times, so a
new take with different timing needs no change to the video's code.

Taps go by the text on screen, through scripts/screenshots/adb-ui.py.
"""
import importlib.util
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PROMO = os.path.dirname(HERE)
REPO = os.path.dirname(os.path.dirname(PROMO))

ADB_UI = os.path.join(REPO, "scripts", "screenshots", "adb-ui.py")
sys.dont_write_bytecode = True  # no __pycache__ beside adb-ui.py
_spec = importlib.util.spec_from_file_location("adb_ui", ADB_UI)
ui = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ui)

PACKAGE = "org.hyperstarit.keepitapp"
DEVICE_FILE = "/sdcard/keepit-tour.mp4"
# screenrecord starts encoding a moment after it is launched; the marks are shifted by this much.
STARTUP = 0.4


def tap_at(x, y):
    ui.adb("shell", "input", "tap", str(x), str(y))


def sign_in(server):
    """A cleared app, signed in as Anna, on the notes screen with everything fetched."""
    ui.adb("shell", "pm", "clear", PACKAGE)
    ui.adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    ui.adb("shell", "appops", "set", PACKAGE, "SCHEDULE_EXACT_ALARM", "allow")
    ui.adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    deadline = time.time() + 30
    while not any(n["text"] == "Server URL" for n in ui.nodes()):
        if time.time() > deadline:
            raise SystemExit("the sign-in screen never appeared")
        time.sleep(1)
    field = ui.find("Server URL")
    tap_at(field["cx"], field["cy"])
    # Text typed before the field has focus is lost, and Tab moves on to the next field.
    for i, text in enumerate([server, "anna@example.com", "Demo1234#demo"]):
        time.sleep(1.2 if i == 0 else 0.6)
        if i:
            ui.adb("shell", "input", "keyevent", "61")
            time.sleep(0.6)
        ui.adb("shell", "input", "text", text)
    time.sleep(0.6)
    ui.adb("shell", "input", "keyevent", "111")  # hides the keyboard
    time.sleep(1.2)
    button = ui.find("Sign in")
    tap_at(button["cx"], button["cy"])
    deadline = time.time() + 40
    while not any(n["text"] == "Weekend in Puglia" for n in ui.nodes()):
        if time.time() > deadline:
            raise SystemExit("never signed in")
        time.sleep(1)
    refresh = ui.find("Refresh")
    tap_at(refresh["cx"], refresh["cy"])
    time.sleep(6)  # photos and faces arrive after the notes
    subprocess.run([sys.executable, ADB_UI, "demo", "on"], check=True)  # 9:41, full battery


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    sign_in(sys.argv[1])
    ui.adb("shell", "rm", "-f", DEVICE_FILE)

    recorder = subprocess.Popen([ui.ADB, "shell", "screenrecord", "--bit-rate", "24000000", DEVICE_FILE])
    start = time.monotonic() + STARTUP
    marks = {}

    def mark(name):
        marks[name] = round(time.monotonic() - start, 2)

    # Finding a control reads the screen, which takes a second or two, so each step finds first,
    # then marks, then acts: a mark is the moment the screen starts to change.
    def step(name, label, at=lambda n: (n["cx"], n["cy"])):
        n = ui.find(label)
        mark(name)
        tap_at(*at(n))

    def checkbox(n):  # a checklist row's checkbox sits left of its text field
        return n["box"][0] - 63, n["cy"]

    def circle(n):  # a colour swatch's circle sits above its label
        return n["cx"], n["cy"] - 106

    time.sleep(1.0)
    mark("grid")
    time.sleep(2.5)

    step("open", "Groceries")
    time.sleep(1.6)
    step("tick", "Oat milk", at=checkbox)
    time.sleep(0.8)
    step("tick2", "Tomatoes", at=checkbox)
    time.sleep(1.2)

    step("colour", "Background color")
    time.sleep(1.0)
    step("sage", "Sage", at=circle)
    time.sleep(1.2)
    mark("sheetClosed")
    ui.adb("shell", "input", "keyevent", "4")
    time.sleep(1.0)

    step("close", "Save and close")
    time.sleep(2.0)

    mark("scroll")
    ui.adb("shell", "input", "swipe", "540", "1900", "540", "560", "1400")
    time.sleep(1.2)
    lisbon = ui.find("Lisbon trip")
    # Bring the card's footer, where the faces are, to the middle of the screen.
    footer = lisbon["cy"] + 520
    if footer > 1400:
        mark("scroll2")
        ui.adb("shell", "input", "swipe", "540", str(footer), "540", "1300", "900")
        time.sleep(1.2)
    mark("faces")
    # Where the faces ended up, for the video's close-up of them.
    faces = next(n for n in ui.nodes() if n["desc"].startswith("Shared with"))
    marks["facesBox"] = list(faces["box"])
    time.sleep(3.0)
    mark("end")

    subprocess.run([ui.ADB, "shell", "pkill", "-INT", "screenrecord"], check=False)
    recorder.wait(timeout=15)
    time.sleep(1.0)

    raw = os.path.join(PROMO, "public", "tour-raw.mp4")
    out = os.path.join(PROMO, "public", "tour.mp4")
    subprocess.run([ui.ADB, "pull", DEVICE_FILE, raw], check=True, capture_output=True)
    # screenrecord writes a frame only when the screen changes. Constant 30 fps, padded at the end,
    # lets the video seek anywhere in it.
    subprocess.run([
        "ffmpeg", "-loglevel", "error", "-y", "-i", raw,
        "-vf", "fps=30,tpad=stop_mode=clone:stop_duration=4",
        "-c:v", "libx264", "-crf", "14", "-preset", "slow", "-pix_fmt", "yuv420p", "-an", out,
    ], check=True)
    os.remove(raw)

    with open(os.path.join(PROMO, "src", "tour-marks.json"), "w") as f:
        json.dump(marks, f, indent=2)
        f.write("\n")
    print(json.dumps(marks))


if __name__ == "__main__":
    main()
