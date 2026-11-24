"""A small UI Automator driver over adb: dump the screen, find a node by text, description or id, tap, type, wait.

It drives the real app from outside its process, as a person would, so it can force-stop it, read logcat and take
pictures between steps. Every call is on one device (`serial`).
"""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Node:
    text: str
    desc: str
    rid: str
    cls: str
    clickable: bool
    bounds: tuple  # x1, y1, x2, y2

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    @property
    def tag(self):
        return self.rid.split("/")[-1] if self.rid else ""


class Phone:
    def __init__(self, serial: str, package: str, shots: Path):
        self.serial = serial
        self.package = package
        self.shots = shots
        shots.mkdir(parents=True, exist_ok=True)

    # ---- adb -------------------------------------------------------------------------------------------------
    def adb(self, *args: str, check: bool = True, timeout: int = 120) -> str:
        done = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and done.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {done.stderr.strip()}")
        return done.stdout

    def shell(self, command: str, **kw) -> str:
        return self.adb("shell", command, **kw)

    # ---- the screen ------------------------------------------------------------------------------------------
    def dump(self) -> list:
        """The screen as nodes; retried, since a screen that is still animating cannot be dumped."""
        for _ in range(10):  # a screen that changes fast (a reply streaming) is not idle: try again
            out = self.shell("uiautomator dump /sdcard/e2e.xml 2>&1", check=False)
            if "dumped" in out:
                break
            time.sleep(0.4)
        else:
            return []
        xml = self.adb("exec-out", "cat", "/sdcard/e2e.xml", check=False)
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            return []
        nodes = []
        for el in root.iter("node"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", el.get("bounds", ""))
            if not m:
                continue
            nodes.append(
                Node(
                    el.get("text", ""),
                    el.get("content-desc", ""),
                    el.get("resource-id", ""),
                    el.get("class", ""),
                    el.get("clickable") == "true",
                    tuple(int(g) for g in m.groups()),
                )
            )
        return nodes

    @staticmethod
    def match(nodes, text=None, desc=None, tag=None, cls=None, exact=False):
        def ok(have, want):
            if want is None:
                return True
            return have == want if exact else want.lower() in have.lower()

        return [
            n
            for n in nodes
            if ok(n.text, text) and ok(n.desc, desc) and (tag is None or n.tag == tag) and (cls is None or n.cls.endswith(cls))
            and (text is not None or desc is not None or tag is not None or cls is not None)
        ]

    def find(self, **kw):
        found = self.match(self.dump(), **kw)
        return found[0] if found else None

    def wait(self, timeout: float = 30, poll: float = 1.0, what: str = "", **kw) -> Node:
        deadline = time.time() + timeout
        while time.time() < deadline:
            node = self.find(**kw)
            if node:
                return node
            time.sleep(poll)
        raise TimeoutError(f"did not see {what or kw} within {timeout:.0f} s")

    def gone(self, timeout: float = 30, poll: float = 1.0, **kw) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if not self.find(**kw):
                return True
            time.sleep(poll)
        return False

    # ---- input -----------------------------------------------------------------------------------------------
    def tap_xy(self, x: int, y: int):
        self.shell(f"input tap {x} {y}")

    def tap(self, node: Node):
        self.tap_xy(*node.center)

    def tap_text(self, text: str, timeout: float = 20, exact: bool = False):
        self.tap(self.wait(timeout, text=text, exact=exact))

    def tap_desc(self, desc: str, timeout: float = 20):
        self.tap(self.wait(timeout, desc=desc))

    def long_press(self, node: Node, millis: int = 900):
        x, y = node.center
        self.shell(f"input swipe {x} {y} {x} {y} {millis}")

    def swipe(self, x1, y1, x2, y2, millis: int = 300):
        self.shell(f"input swipe {x1} {y1} {x2} {y2} {millis}")

    def type_text(self, text: str):
        self.shell("input text " + subprocess.list2cmdline([text.replace(" ", "%s")]).replace("'", "\\'"))

    def key(self, name: str):
        self.shell(f"input keyevent {name}")

    def back(self):
        self.key("KEYCODE_BACK")

    # ---- the app and the system --------------------------------------------------------------------------------
    def start(self):
        # the main activity by name: a debug build has a second launcher entry (LeakCanary's) that `monkey` may pick
        self.shell(f"am start -n {self.package}/com.suhel.llamabro.demo.MainActivity >/dev/null 2>&1")

    def force_stop(self):
        self.shell(f"am force-stop {self.package}")

    def top_activity(self) -> str:
        out = self.shell("dumpsys activity activities | grep topResumedActivity", check=False)
        return out.strip().splitlines()[0] if out.strip() else ""

    def logcat_clear(self):
        self.adb("logcat", "-c")

    def logcat(self, tag: str) -> str:
        return self.adb("logcat", "-d", "-s", tag, check=False)

    def shot(self, name: str) -> Path:
        path = self.shots / f"{name}.png"
        path.write_bytes(subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout)
        return path
