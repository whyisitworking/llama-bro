#!/usr/bin/env python3
"""Writes requests.jsonl for the contract test (run.sh): the P0 spike's T2 set (t2.jsonl, unchanged) plus the
cases below. Each line: {"id", "endpoint", "body" | "body_raw", options}. Options, read by run.sh / compare.py:
  needs: ["mmproj"]                 run only when the model has a projector
  cancel_after_chunks: N            stop the stream after N chunks (both runners)
  compare: "skip" | "metrics_names" skip a request whose output depends on timing, or compare metric names only
  compare: "events"                 every data payload, without random ids and timestamps at any depth
  compare_timings: ["cache_n", …]   also compare these timings fields (prompt cache parity)
usage: make-requests.py > requests.jsonl
"""
import json
import os

here = os.path.dirname(os.path.abspath(__file__))
WEATHER = {"type": "function", "function": {"name": "get_weather", "description": "Current weather for a city",
           "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}
TIME = {"type": "function", "function": {"name": "get_time", "description": "Local time in a city",
        "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}


def chat(user, **kw):
    body = {"messages": [{"role": "user", "content": user}] if isinstance(user, str) else user,
            "temperature": 0, "seed": 42, "cache_prompt": False, "stream": True, "max_tokens": 48,
            "chat_template_kwargs": {"enable_thinking": False}}
    body.update(kw)
    return body


out = []
for line in open(os.path.join(here, "t2.jsonl")):
    spec = json.loads(line)
    if spec["id"] == "t2-14-image":
        spec["needs"] = ["mmproj"]
    out.append(spec)


def add(id_, endpoint, body=None, **opts):
    spec = {"id": id_, "endpoint": endpoint}
    if body is not None:
        spec["body"] = body
    spec.update(opts)
    out.append(spec)


# sampling: seeded randomness is deterministic on one build, so non-greedy chains must match too
add("x-01-seeded-sampling", "chat", chat("Name a colour and a fruit.", temperature=0.8, seed=7, top_k=40, top_p=0.9, min_p=0.05))
add("x-02-samplers-order", "chat", chat("Name an animal.", temperature=1.0, seed=3, samplers=["top_k", "temperature"], top_k=20))
add("x-03-penalties", "chat", chat("Repeat the word cat five times.", presence_penalty=1.5, frequency_penalty=0.5, repeat_penalty=1.2, repeat_last_n=64))
add("x-04-max-tokens-1", "chat", chat("Hello", max_tokens=1))
add("x-05-stop-multi", "chat", chat("Count from 1 to 10 separated by commas.", stop=["5", "seven"]))
add("x-06-top-logprobs", "chat", chat("Say yes.", max_tokens=4, logprobs=True, top_logprobs=3))
# reasoning
add("x-07-reasoning-none", "chat", chat("What is 7 times 8?", max_tokens=96, reasoning_format="none", chat_template_kwargs={"enable_thinking": True}))
add("x-08-reasoning-deepseek", "chat", chat("What is 9 plus 4?", max_tokens=96, reasoning_format="deepseek", chat_template_kwargs={"enable_thinking": True}))
add("x-09-reasoning-budget", "chat", chat("Is 97 prime?", max_tokens=128, reasoning_budget_tokens=16, chat_template_kwargs={"enable_thinking": True}))
# tools
add("x-10-tool-choice-none", "chat", chat("What's the weather in Oslo?", tools=[WEATHER], tool_choice="none"))
add("x-11-no-parallel", "chat", chat("Weather and local time in Lima?", tools=[WEATHER, TIME], parallel_tool_calls=False))
add("x-12-tool-choice-named", "chat", chat("Tell me something about Cairo.", tools=[WEATHER, TIME], tool_choice={"type": "function", "function": {"name": "get_time"}}))
# formats
add("x-13-json-schema-nested", "chat", chat("Two books with title and year.", max_tokens=96, response_format={"type": "json_schema", "json_schema": {"name": "books", "schema": {
    "type": "object", "properties": {"books": {"type": "array", "items": {"type": "object", "properties": {"title": {"type": "string"}, "year": {"type": "integer"}}, "required": ["title", "year"]}}}, "required": ["books"]}}}))
add("x-14-grammar", "chat", chat("Answer yes or no: is water wet?", grammar='root ::= "yes" | "no"'))
# non-streamed
add("x-15-nonstream", "chat", chat("Capital of Italy? One word.", stream=False))
add("x-16-nonstream-tool", "chat", chat("Weather in Madrid?", stream=False, tools=[WEATHER]))
add("x-17-completion-oai", "completion_oai", {"prompt": "Once upon a time", "max_tokens": 24, "temperature": 0, "seed": 42, "stream": True})
add("x-18-completion-oai-nonstream", "completion_oai", {"prompt": "The sky is", "max_tokens": 12, "temperature": 0, "seed": 42})
add("x-19-completion-probs", "completion", {"prompt": "1, 2, 3,", "n_predict": 8, "n_probs": 3, "temperature": 0, "seed": 42, "cache_prompt": False})
# other endpoints
add("x-20-detokenize", "detokenize", {"tokens": [9707, 11, 1879, 0]})
add("x-21-count-tokens", "count_tokens", chat("How many tokens is this?", tools=[WEATHER]))
add("x-22-apply-template-tools", "apply_template", {"messages": [{"role": "user", "content": "Hi"}], "tools": [WEATHER], "add_generation_prompt": False})
add("x-23-props", "props", None)
# prompt cache: three turns of one conversation, cache on; the server must reuse the same prefix on both sides
conv = [{"role": "system", "content": "You are terse."}, {"role": "user", "content": "Name a planet."}]
add("x-24-cache-turn1", "chat", chat(conv, cache_prompt=True), compare_timings=["cache_n", "prompt_n"])
conv2 = conv + [{"role": "assistant", "content": "Mars."}, {"role": "user", "content": "Another one."}]
add("x-25-cache-turn2", "chat", chat(conv2, cache_prompt=True), compare_timings=["cache_n", "prompt_n"])
conv3 = conv2 + [{"role": "assistant", "content": "Venus."}, {"role": "user", "content": "And a moon of the first one."}]
add("x-26-cache-turn3", "chat", chat(conv3, cache_prompt=True), compare_timings=["cache_n", "prompt_n"])
# cancel mid-stream: the cancelled reply depends on timing; the next request shows the server state is intact
add("x-27-cancel", "chat", chat("Write a long poem about the sea.", max_tokens=256), cancel_after_chunks=4, compare="skip")
add("x-28-after-cancel", "chat", chat("Name a fish.", cache_prompt=True), compare_timings=["cache_n", "prompt_n"])
add("x-29-slots", "slots", None, compare="skip")
add("x-30-metrics", "metrics", None, compare="metrics_names")
# errors: the error bodies must match too
add("x-31-error-bad-json", "chat", body_raw='{"messages": [')
add("x-32-error-no-messages", "chat", {"temperature": 0})
# llama-server accepts an unknown role and passes it to the template
add("x-33-unknown-role", "chat", {"messages": [{"role": "robot", "content": "hi"}], "max_tokens": 4, "temperature": 0, "seed": 42})
add("x-34-error-exceed-context", "chat", chat("word " * 9000, max_tokens=4))
add("x-35-error-bad-schema", "chat", chat("hi", response_format={"type": "json_schema", "json_schema": {"schema": {"type": "nonsense"}}}))
add("x-36-error-bad-tool-choice", "chat", chat("hi", tools=[WEATHER], tool_choice={"type": "function", "function": {"name": "missing"}}))
add("x-37-error-repeat-last-n", "chat", chat("hi", repeat_last_n=-1))

# PA T2: the sampling fields Kotlin's Sampling gained (llama-server's make_llama_cmpl_schema), seeded so they matter
add("x-38-sampling-dry-xtc", "chat", chat("Tell a very short story.", temperature=0.9, seed=11, dry_multiplier=0.8, dry_base=1.75,
    dry_allowed_length=2, dry_penalty_last_n=-1, dry_sequence_breakers=["\n", ":"], xtc_probability=0.5, xtc_threshold=0.1))
add("x-39-sampling-mirostat", "chat", chat("Describe the sea.", temperature=0.8, seed=12, mirostat=2, mirostat_tau=5.0, mirostat_eta=0.1))
add("x-40-sampling-typical-dynatemp", "chat", chat("Name three birds.", temperature=0.9, seed=13, typical_p=0.9, dynatemp_range=0.3,
    dynatemp_exponent=1.2, top_n_sigma=1.5, min_keep=2))
add("x-41-sampling-adaptive", "chat", chat("Name a city.", temperature=0.9, seed=14, adaptive_target=0.5, adaptive_decay=0.9))
add("x-42-logit-bias-ignore-eos", "chat", chat("Say hello.", max_tokens=12, logit_bias={"Hello": False, "hello": -5.0}, ignore_eos=True))

# PA T6: the routes the C API gained (every route server_register_routes maps for one model); without a FIM, audio,
# decision or LoRA model the answer is the error (or empty list) both servers give
NO_THINK = {"chat_template_kwargs": {"enable_thinking": False}}
add("x-43-infill", "infill", {"input_prefix": "def add(a, b):\n    ", "input_suffix": "\n", "n_predict": 12, "temperature": 0, "seed": 42})
add("x-44-responses", "responses", {"input": "Name a colour.", "max_output_tokens": 16, "temperature": 0, "seed": 42, "stream": True, **NO_THINK}, compare="events")
add("x-45-responses-nonstream", "responses", {"input": [{"role": "user", "content": "Name a fruit."}], "max_output_tokens": 16, "temperature": 0, "seed": 42, **NO_THINK}, compare="events")
add("x-46-responses-input-tokens", "responses_input_tokens", {"input": "How many tokens is this?", "tools": [{"type": "function", "name": "get_weather", "parameters": WEATHER["function"]["parameters"]}]})
add("x-47-messages", "messages", {"max_tokens": 16, "messages": [{"role": "user", "content": "Name a colour."}], "temperature": 0, "seed": 42, "stream": True, **NO_THINK}, compare="events")
add("x-48-messages-nonstream", "messages", {"max_tokens": 16, "system": "Be terse.", "messages": [{"role": "user", "content": "Name a fruit."}], "temperature": 0, "seed": 42, **NO_THINK}, compare="events")
add("x-49-messages-count-tokens", "messages_count_tokens", {"messages": [{"role": "user", "content": "How many tokens is this?"}]})
add("x-50-embeddings-native", "embeddings_native", {"content": "hello"})
add("x-51-tts", "tts", {"input": "hello"})
add("x-52-systemone", "systemone", {"questions": []})
add("x-53-props-set", "props_set", {})
add("x-54-models", "models", None, compare="events")
add("x-55-health", "health", None)
add("x-56-lora-adapters", "lora_adapters", None)
add("x-57-lora-adapters-set", "lora_adapters_set", [])

for spec in out:
    print(json.dumps(spec, ensure_ascii=False))
