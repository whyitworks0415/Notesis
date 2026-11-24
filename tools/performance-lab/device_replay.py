"""Scripts for the tablet's own `uinput` tool: a pen and a touchscreen that go
through the same input pipeline as the S Pen and fingers, so writing, scrolling
and pinching can be repeated exactly between two builds.

    python tools/performance-lab/device_replay.py write write.json
    adb push write.json /data/local/tmp/
    adb shell dumpsys gfxinfo com.notesis.bench reset
    adb shell uinput /data/local/tmp/write.json
    adb shell dumpsys gfxinfo com.notesis.bench framestats

Install `./gradlew assembleBench` (com.notesis.bench) for this: it sits beside
the real app, is optimised like a release and can be profiled with simpleperf,
so nothing touches the owner's notes. Run `adb shell cmd package compile -m
speed-profile -f com.notesis.bench` first, or the numbers are an interpreter's.

Coordinates are the screen's, landscape. The panel's own axes are portrait
(Galaxy Tab S8 Ultra: 1848 x 2960, turned 90 degrees); change PANEL for another
tablet. The virtual devices are removed when the script ends.
"""
import json
import math
import sys

PANEL_W, PANEL_H = 1848, 2960

lines = []


def cmd(**k):
    lines.append(json.dumps(k))


def delay(ms):
    cmd(id=1, command='delay', duration=int(ms))


def inject(*events):
    cmd(id=1, command='inject', events=list(events))


def panel(x, y):
    """A landscape screen point on the portrait panel."""
    return int(round(PANEL_W - 1 - y)), int(round(x))


def axis(code, maximum):
    return {"code": code, "info": {"value": 0, "minimum": 0, "maximum": maximum, "fuzz": 0, "flat": 0, "resolution": 0}}


def register_pen():
    cmd(id=1, command='register', name='Notesis Test Pen', vid=0x1234, pid=0x5678, bus='usb',
        configuration=[
            {"type": "UI_SET_EVBIT", "data": ["EV_KEY", "EV_ABS"]},
            {"type": "UI_SET_KEYBIT", "data": ["BTN_TOUCH", "BTN_TOOL_PEN", "BTN_STYLUS"]},
            {"type": "UI_SET_ABSBIT", "data": ["ABS_X", "ABS_Y", "ABS_PRESSURE"]},
            {"type": "UI_SET_PROPBIT", "data": ["INPUT_PROP_DIRECT"]},
        ],
        abs_info=[axis("ABS_X", PANEL_W - 1), axis("ABS_Y", PANEL_H - 1), axis("ABS_PRESSURE", 4095)])
    delay(800)


def register_touch():
    cmd(id=1, command='register', name='Notesis Test Touch', vid=0x1234, pid=0x5679, bus='usb',
        configuration=[
            {"type": "UI_SET_EVBIT", "data": ["EV_KEY", "EV_ABS"]},
            {"type": "UI_SET_KEYBIT", "data": ["BTN_TOUCH"]},
            {"type": "UI_SET_ABSBIT", "data": ["ABS_MT_SLOT", "ABS_MT_TRACKING_ID", "ABS_MT_POSITION_X",
                                                "ABS_MT_POSITION_Y", "ABS_MT_TOUCH_MAJOR"]},
            {"type": "UI_SET_PROPBIT", "data": ["INPUT_PROP_DIRECT"]},
        ],
        abs_info=[axis("ABS_MT_SLOT", 9), axis("ABS_MT_TRACKING_ID", 65535), axis("ABS_MT_POSITION_X", PANEL_W - 1),
                  axis("ABS_MT_POSITION_Y", PANEL_H - 1), axis("ABS_MT_TOUCH_MAJOR", 255)])
    delay(800)


def stroke(points, step_ms=4):
    """One pen stroke through screen points, a sample every [step_ms] (240 Hz by default)."""
    x, y = panel(*points[0])
    inject("EV_KEY", "BTN_TOOL_PEN", 1, "EV_ABS", "ABS_X", x, "EV_ABS", "ABS_Y", y, "EV_SYN", "SYN_REPORT", 0)
    delay(30)
    inject("EV_KEY", "BTN_TOUCH", 1, "EV_ABS", "ABS_PRESSURE", 1500, "EV_SYN", "SYN_REPORT", 0)
    for i, point in enumerate(points[1:], 1):
        delay(step_ms)
        x, y = panel(*point)
        pressure = int(1500 + 1000 * math.sin(math.pi * i / len(points)))
        inject("EV_ABS", "ABS_X", x, "EV_ABS", "ABS_Y", y, "EV_ABS", "ABS_PRESSURE", pressure, "EV_SYN", "SYN_REPORT", 0)
    delay(step_ms)
    inject("EV_ABS", "ABS_PRESSURE", 0, "EV_KEY", "BTN_TOUCH", 0, "EV_SYN", "SYN_REPORT", 0)
    delay(20)
    inject("EV_KEY", "BTN_TOOL_PEN", 0, "EV_SYN", "SYN_REPORT", 0)


def handwriting(rows, x0, x1, y0, row_gap=100, speed=900.0, step_ms=4, gap_ms=120):
    """Rows of looped words, [speed] px a second, the way a hand moves along a line."""
    for row in range(rows):
        y = y0 + row * row_gap
        x = x0
        while x < x1 - 200:
            width = 260
            steps = max(8, int(width * 2.2 / speed * 1000 / step_ms))
            points = [(x + i / steps * width + 18 * math.cos(i / steps * 6 * math.pi + math.pi),
                       y - 28 * math.sin(i / steps * 6 * math.pi)) for i in range(steps + 1)]
            stroke(points, step_ms)
            delay(gap_ms)
            x += width + 60


def touch(frames, step_ms=8):
    """Fingers through [frames], each a list of (x, y), one per finger."""
    events = []
    for slot, point in enumerate(frames[0]):
        x, y = panel(*point)
        events += ["EV_ABS", "ABS_MT_SLOT", slot, "EV_ABS", "ABS_MT_TRACKING_ID", 700 + slot,
                   "EV_ABS", "ABS_MT_TOUCH_MAJOR", 6, "EV_ABS", "ABS_MT_POSITION_X", x, "EV_ABS", "ABS_MT_POSITION_Y", y]
    inject(*(events + ["EV_KEY", "BTN_TOUCH", 1, "EV_SYN", "SYN_REPORT", 0]))
    for frame in frames[1:]:
        delay(step_ms)
        events = []
        for slot, point in enumerate(frame):
            x, y = panel(*point)
            events += ["EV_ABS", "ABS_MT_SLOT", slot, "EV_ABS", "ABS_MT_POSITION_X", x, "EV_ABS", "ABS_MT_POSITION_Y", y]
        inject(*(events + ["EV_SYN", "SYN_REPORT", 0]))
    delay(step_ms)
    events = []
    for slot in range(len(frames[0])):
        events += ["EV_ABS", "ABS_MT_SLOT", slot, "EV_ABS", "ABS_MT_TRACKING_ID", -1]
    inject(*(events + ["EV_KEY", "BTN_TOUCH", 0, "EV_SYN", "SYN_REPORT", 0]))


def fling(dy, x=1480, y=1400, steps=12):
    touch([[(x, y + dy * i / steps)] for i in range(steps + 1)])


def pinch(factor, cx=1480, cy=924, start=1400, steps=40):
    """Two fingers along the screen's width, [start] px apart, ending [factor] times that."""
    frames = []
    for i in range(steps + 4):
        half = start / 2 * (1 + (factor - 1) * min(i, steps) / steps)
        frames.append([(cx - half, cy), (cx + half, cy)])
    touch(frames)


if __name__ == '__main__':
    kind, path = sys.argv[1], sys.argv[2]
    if kind == 'write':
        register_pen()
        handwriting(rows=10, x0=930, x1=2090, y0=600)
    elif kind == 'scroll':
        register_touch()
        for _ in range(6):
            fling(-1000)
            delay(700)
        for _ in range(6):
            fling(1000)
            delay(700)
    elif kind == 'pinch':
        register_touch()
        pinch(float(sys.argv[3]) if len(sys.argv) > 3 else 0.4)
    else:
        sys.exit('write | scroll | pinch [factor]')
    delay(300)
    with open(path, 'w') as out:
        out.write('\n'.join(lines) + '\n')
