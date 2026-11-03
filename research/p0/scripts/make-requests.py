#!/usr/bin/env python3
"""Generates the request files for session 1 (spike plan §4) into spike/p0/requests/.

t2.jsonl  output parity: 20 fixed requests, greedy, cache_prompt false
t3-<n>.jsonl  speed: one chat request with a prompt of about n tokens, 128 generated tokens
t5.jsonl  cancel: during an 8k prefill, and during decoding
t7.jsonl  cache: a 10-turn conversation with tool calls, then a switch to another conversation and back
"""
import base64
import json
import os
import struct
import zlib

here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out_dir = os.path.join(here, "requests")
os.makedirs(out_dir, exist_ok=True)

NO_THINK = {"enable_thinking": False}
GREEDY = {"temperature": 0, "seed": 42, "cache_prompt": False, "stream": True}


def png(w=96, h=64):
    """Left half red, right half blue, with a white horizontal bar (no image libraries needed)."""
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            if h // 2 - 4 <= y < h // 2 + 4:
                row += bytes([255, 255, 255])
            elif x < w // 2:
                row += bytes([220, 30, 30])
            else:
                row += bytes([30, 60, 220])
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


WEATHER = {"type": "function", "function": {
    "name": "get_weather", "description": "Current weather for a city",
    "parameters": {"type": "object", "properties": {"city": {"type": "string"},
                   "unit": {"type": "string", "enum": ["celsius", "fahrenheit"]}}, "required": ["city"]}}}
TIME = {"type": "function", "function": {
    "name": "get_time", "description": "Current local time in a city",
    "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}


def chat(rid, messages, **extra):
    body = {"messages": messages, **GREEDY, "max_tokens": 96, "chat_template_kwargs": NO_THINK}
    body.update(extra)
    return {"id": rid, "endpoint": "chat", "body": body}


def user(t):
    return {"role": "user", "content": t}


def asst(t):
    return {"role": "assistant", "content": t}


def write(name, items):
    with open(os.path.join(out_dir, name), "w") as f:
        for it in items:
            f.write(json.dumps(it) + "\n")
    print(f"{name}: {len(items)} requests")


# ---------------------------------------------------------------- T2
image_url = "data:image/png;base64," + base64.b64encode(png()).decode()
t2 = [
    chat("t2-01-plain", [user("What is the capital of Japan? One word.")]),
    chat("t2-02-plain-system", [{"role": "system", "content": "You answer like a pirate."}, user("How are you today?")]),
    chat("t2-03-list", [user("List four planets of the solar system, one per line.")]),
    chat("t2-04-multiturn", [user("My name is Ada."), asst("Nice to meet you, Ada!"), user("What is my name?")]),
    chat("t2-05-multiturn-long", [user("Let's do maths."), asst("Sure, what would you like to calculate?"),
                                  user("What is 17 times 23?"), asst("17 times 23 is 391."), user("And plus 9?")]),
    chat("t2-06-thinking", [user("Is 91 a prime number?")], max_tokens=256, chat_template_kwargs={"enable_thinking": True}),
    chat("t2-07-thinking-short", [user("What is 12 squared?")], max_tokens=256, chat_template_kwargs={"enable_thinking": True}),
    chat("t2-08-tool", [user("What's the weather in Paris?")], tools=[WEATHER]),
    chat("t2-09-tool-parallel", [user("What's the weather and the local time in Tokyo?")], tools=[WEATHER, TIME],
         parallel_tool_calls=True),
    chat("t2-10-tool-result", [user("What's the weather in Paris?"),
                               {"role": "assistant", "content": None, "tool_calls": [{"id": "call_1", "type": "function",
                                "function": {"name": "get_weather", "arguments": "{\"city\":\"Paris\"}"}}]},
                               {"role": "tool", "tool_call_id": "call_1", "content": "{\"temp_c\": 18, \"sky\": \"cloudy\"}"}],
         tools=[WEATHER]),
    chat("t2-11-tool-required", [user("Tell me about Rome.")], tools=[WEATHER], tool_choice="required"),
    chat("t2-12-json-schema", [user("Give me a person named Bob who is 30.")],
         response_format={"type": "json_schema", "json_schema": {"name": "person", "schema": {
             "type": "object", "properties": {"name": {"type": "string"}, "age": {"type": "integer"}},
             "required": ["name", "age"]}}}),
    chat("t2-13-json-object", [user("Return a JSON object with keys a=1 and b=2.")], response_format={"type": "json_object"}),
    chat("t2-14-image", [{"role": "user", "content": [{"type": "text", "text": "Describe the colours in this image."},
                                                       {"type": "image_url", "image_url": {"url": image_url}}]}]),
    chat("t2-15-logprobs", [user("Say hello.")], logprobs=True, top_logprobs=3, max_tokens=8),
    chat("t2-16-stop", [user("Count from 1 to 20 separated by commas.")], stop=["7"]),
    chat("t2-17-long-gen", [user("Write a short story about a lighthouse keeper.")], max_tokens=192),
    {"id": "t2-18-completion", "endpoint": "completion",
     "body": {"prompt": "The three laws of thermodynamics are", "n_predict": 48, **GREEDY}},
    {"id": "t2-19-tokenize", "endpoint": "tokenize", "body": {"content": "Hello, world! Llama-Bro test.", "with_pieces": True}},
    {"id": "t2-20-apply-template", "endpoint": "apply_template",
     "body": {"messages": [user("Hi"), asst("Hello!"), user("Bye")], "chat_template_kwargs": NO_THINK}},
]
write("t2.jsonl", t2)

# ---------------------------------------------------------------- T3
FILLER = ("Record {i}: the warehouse in district {d} received {q} crates of item {s} on day {day}, "
          "and the inspector noted that seal number {seal} was intact. ")


def filler(n_tokens):
    # about 38 tokens per record for Qwen3.5; checked against /tokenize when the requests run
    parts = []
    for i in range(max(1, n_tokens // 38)):
        parts.append(FILLER.format(i=i, d=i % 17, q=(i * 7) % 300, s=(i * 13) % 997, day=i % 365, seal=10000 + i * 31))
    return "".join(parts)


for n in (512, 2048, 8192):
    body = {"messages": [user(filler(n) + "\nSummarise the records above.")], "temperature": 0, "seed": 42,
            "cache_prompt": False, "stream": True, "max_tokens": 128, "ignore_eos": True,
            "chat_template_kwargs": NO_THINK}
    write(f"t3-{n}.jsonl", [{"id": f"t3-{n}", "endpoint": "chat", "body": body}])

# ---------------------------------------------------------------- T5
t5 = [
    {"id": "t5-prefill", "endpoint": "chat", "cancel_after_ms": 3000,
     "body": {"messages": [user(filler(8192) + "\nSummarise.")], "temperature": 0, "seed": 42, "cache_prompt": False,
              "stream": True, "max_tokens": 64, "chat_template_kwargs": NO_THINK}},
    {"id": "t5-decode", "endpoint": "chat", "cancel_after_chunks": 20,
     "body": {"messages": [user("Count from 1 to 500 separated by spaces.")], "temperature": 0, "seed": 42,
              "cache_prompt": False, "stream": True, "max_tokens": 1000, "chat_template_kwargs": NO_THINK}},
]
write("t5.jsonl", t5)

# ---------------------------------------------------------------- T7
# Fixed assistant replies, so the history is the same for both runners whatever the model says.
turns_a = [
    ("Plan a 3-day trip to Paris.", "Day 1: Louvre and Seine walk. Day 2: Montmartre. Day 3: Versailles."),
    ("What's the weather there?", None),  # tool turn
    ("Add a museum on day 2.", "Day 2 now includes the Musee d'Orsay before Montmartre."),
    ("Suggest a dinner place for day 1.", "Try a bistro in Le Marais for dinner on day 1."),
    ("How do I get to Versailles?", "Take the RER C from central Paris to Versailles Chateau Rive Gauche."),
    ("Is the Louvre open on Tuesday?", "No, the Louvre is closed on Tuesdays."),
    ("Then move it to Wednesday.", "Done: the Louvre moves to Wednesday, day 1 becomes Montmartre."),
    ("What's the time in Paris now?", None),  # tool turn
    ("Give me a packing list.", "Comfortable shoes, an umbrella, a power adapter and a light jacket."),
    ("Summarise the final plan.", None),  # last turn: model answers
]
turns_b = [("Explain photosynthesis briefly.", "Plants turn light, water and CO2 into sugar and oxygen."),
           ("And respiration?", None)]


def conversation(turns, prefix, tools):
    reqs, history, call_n = [], [], 0
    for k, (q, a) in enumerate(turns):
        history.append(user(q))
        body = {"messages": list(history), "temperature": 0, "seed": 42, "cache_prompt": True, "stream": True,
                "max_tokens": 48, "tools": tools, "chat_template_kwargs": NO_THINK}
        reqs.append({"id": f"{prefix}-{k + 1:02d}", "endpoint": "chat", "body": body})
        if a is not None:
            history.append(asst(a))
        elif k < len(turns) - 1:
            call_n += 1
            name = "get_weather" if "weather" in q else "get_time"
            cid = f"call_{call_n}"
            history.append({"role": "assistant", "content": None, "tool_calls": [{"id": cid, "type": "function",
                            "function": {"name": name, "arguments": "{\"city\":\"Paris\"}"}}]})
            history.append({"role": "tool", "tool_call_id": cid,
                            "content": "{\"temp_c\": 18}" if name == "get_weather" else "{\"time\": \"14:05\"}"})
            history.append(asst("It's 18 C in Paris." if name == "get_weather" else "It's 14:05 in Paris."))
    return reqs


t7 = conversation(turns_a, "t7-a", [WEATHER, TIME])
t7 += conversation(turns_b, "t7-b", [WEATHER, TIME])
# back to conversation A: resend its last request
again = dict(t7[len(turns_a) - 1])
again["id"] = "t7-a-back"
t7.append(again)
write("t7.jsonl", t7)
