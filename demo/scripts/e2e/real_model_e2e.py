#!/usr/bin/env python3
"""The demo app end to end on the phone, with real models from huggingface.co, driven from outside.

usage: real_model_e2e.py [--serial S] [--only 1,2,3] [--out DIR] [--fresh APK]

Steps (each timed, screenshotted, and asserted on structure, not on the model's words):
  1  first open -> Qwen3.5 0.8B -> Get -> send before the download ends -> a reply; seconds from first open to the first token
  2  think it through at Quick and at Deep: the fold "Thought for <n> s"
  3  an image to Qwen3.5 0.8B: the reply names its colour
  4  get Gemma 3 4B; force-stop during the download; relaunch: it resumes (the hub's log shows a 206) and finishes
  5  switch to Gemma 3 4B mid-chat (the divider and a reply); edit the first message; regenerate
  6  Chat list layout; find the chat by search; share a reply (the share sheet appears)
  7  remove Gemma 3 4B: its chat shows the missing-model line
Airplane mode is never toggled. Pictures of the photo picker are never kept (it shows the phone's own gallery).
"""
import argparse
import json
import os
import re
import struct
import sys
import time
import zlib
from pathlib import Path

from phone import Phone

PACKAGE = "com.suhel.llamabro.demo.debug"  # the debug build, beside any installed release
results = {}
log_lines = []


def say(message: str):
    line = f"[{time.strftime('%H:%M:%S')}] {message}"
    print(line, flush=True)
    log_lines.append(line)


# ---- helpers on top of the driver ------------------------------------------------------------------------------
def composer_field(p: Phone):
    return p.wait(30, what="the message box", cls="EditText")


def type_in_composer(p: Phone, text: str, append: bool = False):
    """Types [text] into the message box (after what it holds, when [append]) and reads the box back: `adb input text`
    can drop or reorder characters (the keyboard's suggestions meet fast injected keys), so a mismatch clears the box and
    types the whole line again."""
    target = composer_field(p).text + text if append else text
    for attempt in range(4):
        p.tap(composer_field(p))
        time.sleep(0.4)
        p.key("KEYCODE_MOVE_END")
        p.shell("input keyevent " + " ".join(["KEYCODE_DEL"] * (len(composer_field(p).text) + 10)))
        p.type_text(target)
        time.sleep(0.7)
        now = composer_field(p).text
        if now == target:
            return
        say(f"  typing came out as {now!r}, not {target!r}: again")
    raise AssertionError(f"could not type {target!r} into the message box")


def send(p: Phone):
    p.tap_desc("Send")


def reply_started(p: Phone):
    nodes = p.dump()
    return bool(p.match(nodes, tag="streaming_reply") or p.match(nodes, tag="reply_message"))


STUCK_AFTER_S = 100  # a Qwen3.5 0.8B reply is done in well under this; a streaming screen is also hard to dump, so Stop is looked for patiently
STUCK_AFTER_S_DEFAULT = 100


def wait_reply_done(p: Phone, timeout: float, replies_before: int = 0) -> int:
    """Waits for a reply to appear and the answer to finish; returns the replies on screen.

    Done is: Stop was seen (a reply was coming) and Send is back, on two polls in a row. The count of reply rows on screen
    is no guide on its own: a long reply scrolls the earlier ones out of the list, so it may never rise. A reply so quick
    that Stop was never seen is caught by the count, when it does rise.
    """
    started = time.time()
    deadline = started + timeout
    seen_busy = False
    quiet = 0
    while time.time() < deadline:
        nodes = p.dump()
        if nodes:  # a failed dump (the screen is changing too fast) is no evidence either way
            replies = len(p.match(nodes, tag="reply_message"))
            busy = bool(p.match(nodes, desc="Stop"))
            seen_busy = seen_busy or busy
            if replies > replies_before and not busy and not p.match(nodes, tag="streaming_reply"):
                return replies
            # Stop may never be caught by a poll when a reply is quick: after a few seconds, Send back and no Stop is enough
            settled = seen_busy or time.time() - started > 8
            quiet = quiet + 1 if (settled and not busy and p.match(nodes, desc="Send")) else 0
            if quiet >= 2:
                return replies
        time.sleep(1.5)
    raise TimeoutError("the reply did not finish")


def text_within(nodes, container) -> str:
    """The text of the nodes drawn inside [container]'s bounds (a row's own text is in its children)."""
    x1, y1, x2, y2 = container.bounds
    inside = [n for n in nodes if n.text and n.bounds[0] >= x1 and n.bounds[2] <= x2 and n.bounds[1] >= y1 and n.bounds[3] <= y2]
    return " ".join(n.text for n in inside)


def finish_or_stop(p: Phone, label: str, replies_before: int = 0, timeout: float = STUCK_AFTER_S_DEFAULT) -> bool:
    """Waits for the reply to finish. A reply still coming after [timeout] is a small model that will not stop (F113):
    that is recorded as partial, Stop is pressed and must end it, and the journey goes on. Returns whether it finished."""
    try:
        wait_reply_done(p, timeout, replies_before)
        return True
    except TimeoutError:
        now = p.dump()
        if now and not p.match(now, desc="Stop") and p.match(now, desc="Send"):
            return True  # it had finished; the polls just did not agree
        p.shot(f"{label}-still-going")
        p.tap(p.wait(60, what="Stop", desc="Stop", poll=0.5))
        assert p.gone(60, desc="Stop"), f"{label}: Stop did not end the reply"
        results[f"{label}_partial"] = True
        say(f"  {label}: still going after {timeout:.0f} s; Stop pressed and ended it (partial)")
        return False


def count_replies(p: Phone) -> int:
    return len(p.match(p.dump(), tag="reply_message"))


def fresh_install(p: Phone, apk: str):
    p.adb("uninstall", PACKAGE, check=False)
    p.adb("install", "-g", "-r", apk)
    say(f"installed {Path(apk).name} on {p.serial}")


OUT = Path("/tmp/e2e")


# ---- the steps -------------------------------------------------------------------------------------------------
def step1(p: Phone):
    say("step 1: first open (Models) -> Qwen3.5 0.8B -> Get -> ask before the download ends")
    p.force_stop()
    p.logcat_clear()
    opened = time.time()
    p.start()
    # a fresh install starts on the Models page, whose empty state first run is (models-page design §2)
    p.wait(60, what="the Models page", tag="models")
    p.shot("1-first-run")
    p.tap(p.wait(20, text="Qwen3.5 0.8B", exact=True))
    get = p.wait(30, what="the card's Get", tag="get_button")
    p.shot("1-swift-sheet")
    p.tap(get)
    say("tapped Get on Qwen3.5 0.8B's card")
    # the chat opens with the question held until the model is on the phone
    type_in_composer(p, "What can you do?")
    send(p)
    asked = time.time()
    p.wait(30, what="the held note", text="will answer the moment it's here")
    p.shot("1-held")
    say(f"question held {asked - opened:.1f} s after first open; waiting for the download and the first token")
    first_token = None
    deadline = time.time() + 15 * 60
    while time.time() < deadline:
        if reply_started(p):
            first_token = time.time()
            break
        time.sleep(1)
    if first_token is None:
        raise TimeoutError("no reply within 15 minutes")
    results["first_open_to_first_token_s"] = round(first_token - opened, 1)
    results["question_to_first_token_s"] = round(first_token - asked, 1)
    say(f"first token {first_token - opened:.1f} s after first open")
    finish_or_stop(p, "step1", 0, 300)
    done = time.time()
    results["first_open_to_finished_reply_s"] = round(done - opened, 1)
    nodes = p.dump()
    replies = p.match(nodes, tag="reply_message")
    p.shot("1-answered")
    assert replies, "no reply row"
    reply_text = text_within(nodes, replies[-1])
    results["step1_reply_chars"] = len(reply_text)
    assert len(reply_text.strip()) >= 3, f"the reply is empty: {reply_text!r}"
    say(f"step 1 done: reply finished {done - opened:.1f} s after first open")


def set_think(p: Phone, level: str):
    """Opens the Think menu from the bulb in the bar's button row (its description starts with "Think") and picks [level]."""
    bulb = p.wait(20, what="the Think bulb", desc="Think")  # "Think" or "Think · Quick": a substring match
    p.tap(bulb)
    p.wait(20, text="THINK BEFORE ANSWERING")
    p.tap_text(level, exact=True)
    time.sleep(1.2)


def step2(p: Phone):
    say("step 2: think it through at Quick and at Deep")
    for level in ("Quick", "Deep"):
        set_think(p, level)
        before = count_replies(p)
        type_in_composer(p, "How many R letters are in strawberry? Think it through.")
        send(p)
        started = time.time()
        try:
            wait_reply_done(p, STUCK_AFTER_S, before)
        except TimeoutError:
            # a reply still coming after STUCK_AFTER_S is a model that does not stop (F113): keep the evidence, press
            # Stop (which the journey also needs to work), and go on; the step is then only partly done
            nodes = p.dump()
            text = " ".join(n.text for n in nodes if n.text)
            p.shot(f"2-think-{level.lower()}-still-going")
            (OUT / f"2-think-{level.lower()}-still-going.txt").write_text(text)
            p.tap(p.wait(60, what="Stop", desc="Stop", poll=0.5))
            gone = p.gone(60, desc="Stop")
            p.shot(f"2-think-{level.lower()}-stopped")
            results[f"think_{level.lower()}_still_going_after_s"] = STUCK_AFTER_S
            results[f"think_{level.lower()}_stop_worked"] = gone
            results[f"think_{level.lower()}_partial"] = True
            assert gone, "Stop did not end the reply"
            after = p.dump()
            assert any(
                re.match(r"(Thinking|Thought for|Stopped)", n.text) for n in after
            ), "no thought fold or 'Stopped' after Stop"
            say(f"  {level}: still going after {STUCK_AFTER_S} s; Stop pressed, worked={gone}")
            continue
        took = time.time() - started
        nodes = p.dump()
        fold = [n for n in nodes if re.search(r"Thought for \d+ s", n.text + n.desc)]
        p.shot(f"2-think-{level.lower()}")
        results[f"think_{level.lower()}_reply_s"] = round(took, 1)
        results[f"think_{level.lower()}_fold"] = fold[0].text or fold[0].desc if fold else None
        assert fold, f"no 'Thought for <n> s' fold at {level}"
        say(f"  {level}: reply in {took:.1f} s, fold '{fold[0].text or fold[0].desc}'")
    set_think(p, "Off")


def make_png(path: Path, w=640, h=480, rgb=(30, 90, 200)):
    row = b"\x00" + bytes(rgb) * w
    raw = row * h
    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def ensure_chat(p: Phone):
    """A chat screen to type in: the open one, else a new chat (a step may start from the Chat list)."""
    hide_keyboard(p)
    if not p.find(tag="chat_screen"):
        node = p.find(text="New chat", exact=True) or p.find(desc="New chat")
        if node:
            p.tap(node)
            p.wait(15, what="a new chat", tag="chat_screen")


def step3(p: Phone, out: Path):
    say("step 3: an image to Qwen3.5 0.8B")
    ensure_chat(p)
    set_think(p, "Off")
    png = out / "e2e-blue.png"
    make_png(png)
    p.adb("push", str(png), "/sdcard/Pictures/e2e-blue.png")
    p.shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/e2e-blue.png >/dev/null 2>&1")
    time.sleep(3)
    try:
        before = count_replies(p)
        p.tap_desc("Add a photo")
        # the picker shows the phone's own gallery: no picture of it is kept
        first = p.wait(30, what="a picture in the picker", desc="Photo taken on")
        p.tap(first)
        done = p.find(text="Done") or p.wait(5, what="Done", text="Done")
        p.tap(done)
        p.wait(20, what="the attached photo", desc="Attached photo")
        p.shot("3-attached")
        type_in_composer(p, "What colour is this picture?")
        send(p)
        finish_or_stop(p, "step3", before)
        p.shot("3-answered")
        # a reply arriving is not enough: the model must name the picture's colour (F119, F120)
        nodes = p.dump()
        replies = p.match(nodes, tag="reply_message")
        reply = text_within(nodes, replies[-1]).lower() if replies else ""
        results["step3_names_blue"] = re.search(r"\bblue\b", reply) is not None
        assert results["step3_names_blue"], f"step 3: the reply does not name the picture's colour (blue): {reply!r}"
        say("step 3 done: the reply names the picture's colour")
    finally:
        p.shell("content delete --uri content://media/external/images/media --where \"_display_name LIKE 'e2e-blue%'\"", check=False)
        p.shell("rm -f /sdcard/Pictures/e2e-blue.png", check=False)


def step4(p: Phone):
    say("step 4: get Gemma 3 4B, force-stop during the download, relaunch, resume")
    p.tap(p.wait(20, tag="model_title"))
    p.wait(20, text="ON THIS PHONE")
    p.tap_text("Get more models")
    p.wait(20, what="Models", tag="models")
    p.tap(p.wait(20, text="Gemma 3 4B", exact=True))
    get = p.wait(30, what="Get Gemma 3 4B", text="Get Gemma 3 4B")
    p.shot("4-writer-sheet")
    p.tap(get)
    # the download starts; wait for a few percent, then kill the app
    p.wait(60, what="the download under way", text="Downloading")
    time.sleep(25)
    progress = [n.text for n in p.dump() if "Downloading" in n.text or "%" in n.text]
    p.shot("4-downloading")
    say(f"force-stopping during the download ({progress[:2]})")
    p.logcat_clear()
    p.force_stop()
    time.sleep(3)
    p.start()
    time.sleep(10)
    # the app is back; find the download in the library and wait for it to finish
    nodes = p.dump()
    if not p.match(nodes, tag="library"):
        open_drawer(p)
        tap_main(p, "Models")
    p.wait(30, what="Models", tag="models")
    # logcat's ring buffer is 256 KiB: read the hub's log early and as we go, or the resume's lines roll off
    hub_lines: set = set()
    hub_lines.update(p.logcat("LlamaBroHub").splitlines())
    deadline = time.time() + 20 * 60
    polls = 0
    while time.time() < deadline:
        polls += 1
        if polls % 6 == 0:
            hub_lines.update(p.logcat("LlamaBroHub").splitlines())
        nodes = p.dump()
        on_phone = p.match(nodes, text="Gemma 3 4B", exact=True) and not p.match(nodes, text="DOWNLOADING")
        if on_phone:
            break
        time.sleep(5)
    else:
        raise TimeoutError("Gemma 3 4B did not finish after the relaunch")
    hub_lines.update(p.logcat("LlamaBroHub").splitlines())
    got206 = sorted(l for l in hub_lines if " 206" in l)
    results["resume_206_lines"] = len(got206)
    results["resume_log_sample"] = got206[:2]
    p.shot("4-writer-arrived")
    assert got206, "the hub's log shows no 206 after the relaunch"
    say(f"step 4 done: resumed with a 206 ({len(got206)} lines), Gemma 3 4B is on the phone")


def step5(p: Phone):
    say("step 5: switch to Gemma 3 4B mid-chat; edit the first message; regenerate")
    if not p.find(tag="chat_screen"):
        # from the library: back to the chat
        p.back()
        p.wait(20, what="the chat", tag="chat_screen")
    # a chat on Qwen3.5 0.8B first, so that Gemma 3 4B is a switch in the middle of a chat
    p.tap_desc("New chat")
    time.sleep(2)
    if not p.find(text="Qwen3.5 0.8B", exact=True):
        p.tap(p.wait(20, tag="model_title"))
        p.tap(p.wait(20, text="Qwen3.5 0.8B", exact=True))
        time.sleep(1.5)
    type_in_composer(p, "Say hi in three words.")
    send(p)
    finish_or_stop(p, "step5-swift", 0)
    before = count_replies(p)
    p.tap(p.wait(20, tag="model_title"))
    p.tap(p.wait(20, text="Gemma 3 4B", exact=True))
    p.wait(20, what="the switch divider", text="Switched to Gemma 3 4B")
    p.shot("5-switched")
    type_in_composer(p, "Say hello in five words.")
    send(p)
    finish_or_stop(p, "step5-writer", before, 300)
    p.shot("5-writer-replied")
    # edit the first message: long-press it, Edit, change, send
    first = p.match(p.dump(), tag="user_message")[0]
    p.long_press(first)
    p.tap_text("Edit", exact=True)
    p.wait(20, what="the edit line", text="Editing your message")
    type_in_composer(p, " Then add one sentence about why.", append=True)
    send(p)
    before = count_replies(p)
    time.sleep(3)
    finish_or_stop(p, "step5-edit", 0, 300)
    p.shot("5-edited")
    assert p.gone(10, text="Editing your message"), "the edit line did not go away"
    nodes = p.dump()
    first_user = p.match(nodes, tag="user_message")[0]
    edited = text_within(nodes, first_user)
    results["edited_message"] = edited
    assert "Then add one sentence about why." in edited, f"the edit did not reach the message: {edited!r}"
    # regenerate the last reply: the reply it replaces is read first, so that a regenerate that did something shows either
    # a reply coming (Stop) or a different reply (a quick model can answer between two polls)
    def last_reply_text() -> str:
        nodes = p.dump()
        replies = p.match(nodes, tag="reply_message")
        return text_within(nodes, replies[-1]) if replies else ""

    before_text = last_reply_text()
    regen = p.wait(30, what="Regenerate", desc="Regenerate reply")
    p.tap(regen)
    deadline = time.time() + 90
    started = False
    while time.time() < deadline and not started:
        nodes = p.dump()
        if nodes and (p.match(nodes, desc="Stop") or (last_reply_text() not in ("", before_text))):
            started = True
        else:
            time.sleep(1)
    results["regenerate_started"] = started
    assert started, "the regenerate did nothing: no reply coming and the reply is unchanged"
    finish_or_stop(p, "step5-regenerate", 0, 300)
    p.shot("5-regenerated")
    say("step 5 done: the divider, a reply from Gemma 3 4B, an edit and a regenerate")


def hide_keyboard(p: Phone):
    if "mInputShown=true" in p.shell("dumpsys input_method | grep mInputShown", check=False):
        p.back()
        time.sleep(1)


def go_top(p: Phone):
    """Back until a main screen's tabs show (Chat list layout) or the chat's own header does. A dump that fails (the screen
    is changing) is no reason to press Back; and if Back took us out of the app, the app is started again."""
    hide_keyboard(p)
    for _ in range(6):
        if PACKAGE not in p.top_activity():
            p.start()
            time.sleep(3)
        nodes = p.dump()
        if not nodes:
            time.sleep(1)
            continue
        if p.match(nodes, desc="Close sheet"):
            p.tap_desc("Close sheet")
        elif p.match(nodes, text="Settings", exact=True) or p.match(nodes, desc="Chats"):
            return
        else:
            p.back()
        time.sleep(1)


def open_drawer(p: Phone):
    """Gets to where Models and Settings are: the bottom bar of the Chat list layout (text tabs), else the Sidebar
    layout's drawer, whose actions are icons at its foot, known by their descriptions ("Models, 2 on this phone")."""
    go_top(p)
    if p.find(tag="chat_screen") and p.find(desc="Back"):  # the Chat list layout's chat has a back arrow
        p.tap_desc("Back")
        p.wait(10, what="the Chat list", tag="chat_list")
    if p.find(text="Settings", exact=True) and not p.find(desc="Chats") and not p.find(tag="chat_screen"):
        return  # the bottom bar is showing
    if not p.find(desc=DRAWER_MODELS):
        p.tap_desc("Chats")
        p.wait(10, what="the drawer", desc=DRAWER_MODELS)


DRAWER_MODELS = "Models, "  # the drawer's Models icon: "Models, <count> on this phone" (desc matches a substring)


def tap_main(p: Phone, name: str):
    """Taps Models or Settings where open_drawer left them: a bottom bar tab by its text, else the drawer's icon."""
    tab = p.find(text=name, exact=True)
    p.tap(tab) if tab else p.tap_desc(DRAWER_MODELS if name == "Models" else name)


def step6(p: Phone):
    say("step 6: Chat list layout, find the chat by search, share a reply")
    open_drawer(p)
    tap_main(p, "Settings")
    p.tap_text("Appearance")
    p.tap(p.wait(20, tag="layout_chat_list"))
    time.sleep(2)
    p.back()
    time.sleep(1.5)
    if not p.find(cls="EditText"):
        # the Chat list layout's bottom bar, else one more back (the sidebar layout)
        bar = p.find(text="Chats", exact=True)
        p.tap(bar) if bar else p.back()
    field = p.wait(30, what="the Chat list's search", cls="EditText")
    p.shot("6-chat-list")
    p.tap(field)
    p.key("KEYCODE_MOVE_END")
    p.shell("input keyevent " + " ".join(["KEYCODE_DEL"] * 30))  # whatever an earlier try left in the field
    p.type_text("strawberry")
    time.sleep(1.5)
    # the chat's title is its first message (on a fresh install "What can you do?"), the search finds it by any message
    hit = None
    for _ in range(20):
        rows = [n for n in p.dump() if re.search(r"R letters|What can you do", n.text)]
        if rows:
            hit = rows[0]
            break
        time.sleep(1)
    assert hit, "the search found no chat for 'strawberry'"
    p.shot("6-search")
    p.tap(hit)
    p.wait(30, what="the chat", tag="chat_screen")
    # a reply with text (the stopped one before it has none)
    nodes = p.dump()
    with_text = [n for n in p.match(nodes, tag="reply_message") if "words/s" in text_within(nodes, n)]
    assert with_text, "no finished reply with text to share"
    reply = with_text[-1]
    p.long_press(reply)
    p.shot("6-message-menu")
    p.tap_text("Share")
    time.sleep(4)
    top = p.top_activity()
    chooser = "Chooser" in top or "ResolverActivity" in top or "Share" in top
    p.shot("6-share-sheet")
    results["share_top_activity"] = top[-80:]
    assert chooser, f"no share sheet: {top}"
    p.back()
    say("step 6 done: the share sheet appeared")


def step7(p: Phone, model: str = "Gemma 3 4B", chat: str = "Say hi in three words"):
    say(f"step 7: remove {model}; its chat shows the missing-model line")
    open_drawer(p)
    tap_main(p, "Models")
    p.wait(20, what="Models", tag="models")
    writer = p.wait(20, text=model, exact=True)
    x, y = writer.center
    p.swipe(1300, y, 200, y, 400)
    p.wait(20, text=f"Remove {model}?")
    p.shot("7-remove-ask")
    p.tap_text("Remove", exact=True)
    assert p.gone(30, text=model, exact=True), f"{model} is still listed"
    p.back()
    p.wait(20, what="the Chat list", tag="chat_list")
    # the chat that used Gemma 3 4B (step 5's); the search from step 6 may still filter the list
    field = p.find(cls="EditText")
    if field and field.text:
        p.tap(field)
        p.key("KEYCODE_MOVE_END")
        p.shell("input keyevent " + " ".join(["KEYCODE_DEL"] * 30))
        hide_keyboard(p)
    p.tap(p.wait(20, text=chat))
    line = p.wait(30, what="the missing-model line", text="isn't on this phone")
    p.shot("7-missing")
    say(f"step 7 done: '{line.text}'")


def tour(p: Phone, suffix: str):
    """The screens a person lands on most, pictured in the theme now showing (Chat list layout, bottom bar)."""
    open_drawer(p)
    p.tap_text("Chats", exact=True)
    p.wait(15, what="the Chat list", cls="EditText")
    p.shot(f"8-chat-list-{suffix}")
    p.tap(p.wait(15, what="a chat", text=chat))
    p.wait(20, what="the chat", tag="chat_screen")
    time.sleep(1)
    p.shot(f"8-chat-{suffix}")
    p.tap_desc("Back")
    open_drawer(p)
    tap_main(p, "Models")
    p.wait(15, what="Models", tag="models")
    p.shot(f"8-models-{suffix}")
    p.tap_text("Get more models")
    p.wait(15, what="Models", tag="models")
    p.shot(f"8-get-models-{suffix}")
    p.tap(p.wait(15, what="the Gemma 3 4B card", text=model, exact=True))
    p.wait(15, what="the Gemma 3 4B sheet", text="Get Gemma 3 4B", exact=False)
    time.sleep(1)
    p.shot(f"8-writer-sheet-{suffix}")
    go_top(p)
    open_drawer(p)
    tap_main(p, "Settings")
    p.wait(15, what="Settings", text="Appearance")
    p.shot(f"8-settings-{suffix}")


def set_theme(p: Phone, name: str):
    open_drawer(p)
    tap_main(p, "Settings")
    p.tap_text("Appearance")
    p.tap(p.wait(15, what="the theme switch", tag="theme_switch"))  # focus the control
    seg = [n for n in p.match(p.dump(), text=name, exact=True)]
    assert seg, f"no {name} theme segment"
    p.tap(seg[0])
    time.sleep(1.5)
    p.back()


def step8(p: Phone):
    say("step 8: the main screens in both themes")
    tour(p, "system")
    set_theme(p, "Light")
    tour(p, "light")
    set_theme(p, "Dark")
    tour(p, "dark")
    set_theme(p, "System")
    say("step 8 done: pictures in the system, light and dark themes")


STEPS = {1: step1, 2: step2, 4: step4, 5: step5, 6: step6, 7: step7, 8: step8}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("DEMO_DEVICE"), required="DEMO_DEVICE" not in os.environ)
    ap.add_argument("--only", default="1,2,3,4,5,6,7,8")
    ap.add_argument("--out", default="/tmp/e2e")
    ap.add_argument("--fresh", help="uninstall and install this APK first")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    global OUT
    OUT = out
    p = Phone(args.serial, PACKAGE, out / "shots")
    if args.fresh:
        fresh_install(p, args.fresh)
    failed = None
    if not args.only.startswith("1"):
        p.start()  # a step other than the first starts from a running app
        time.sleep(4)
    for n in [int(x) for x in args.only.split(",")]:
        started = time.time()
        try:
            if n == 3:
                step3(p, out)
            else:
                STEPS[n](p)
            results[f"step{n}_seconds"] = round(time.time() - started, 1)
        except Exception as e:  # noqa: BLE001
            failed = f"step {n}: {type(e).__name__}: {e}"
            say("FAILED " + failed)
            if n != 3:  # step 3 is in the photo picker, which shows the phone's own gallery: no picture of it is kept
                p.shot(f"FAILED-step{n}")
            break
    (out / "results.json").write_text(json.dumps(results, indent=2))
    (out / "log.txt").write_text("\n".join(log_lines))
    say("results: " + json.dumps(results))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
