#!/usr/bin/env python3
"""The official OpenAI and Anthropic Python clients against llamabro-server (sdk/native/tests/contract/server-run.sh
runs it with the venv it keeps, sdk/scripts/tests/server-clients-requirements.txt): each SDK makes one whole and one
streamed call, and one with a wrong key, which must raise that SDK's own AuthenticationError.

usage: server-clients.py URL KEY     (URL: http://127.0.0.1:<port>, the server started with KEY)
"""
import sys

import anthropic
import openai

# the model thinks by default; these calls test the protocol, so they ask for a short plain answer
NO_THINKING = {"chat_template_kwargs": {"enable_thinking": False}}
# anthropic 2.x has no temperature argument; llama-server still reads the field
GREEDY = dict(NO_THINKING, temperature=0)
PROMPT = "Name a colour. One word."


def check(name, ok, detail):
    print(f"{'OK  ' if ok else 'FAIL'} {name}: {detail}")
    return ok


def with_openai(url, key):
    client = openai.OpenAI(base_url=f"{url}/v1", api_key=key, max_retries=0)
    model = client.models.list().data[0].id
    whole = client.chat.completions.create(
        model=model, messages=[{"role": "user", "content": PROMPT}], max_tokens=16, temperature=0, seed=42,
        extra_body=NO_THINKING)
    text = whole.choices[0].message.content or ""
    results = [check("openai whole", bool(text.strip()) and whole.usage.completion_tokens > 0, repr(text))]

    stream = client.chat.completions.create(
        model=model, messages=[{"role": "user", "content": PROMPT}], max_tokens=16, temperature=0, seed=42,
        stream=True, extra_body=NO_THINKING)
    streamed = "".join(c.choices[0].delta.content or "" for c in stream if c.choices)
    results.append(check("openai streamed", streamed == text, repr(streamed)))

    wrong = openai.OpenAI(base_url=f"{url}/v1", api_key=key + "-wrong", max_retries=0)
    try:
        wrong.chat.completions.create(model=model, messages=[{"role": "user", "content": PROMPT}], max_tokens=4)
        results.append(check("openai wrong key", False, "answered"))
    except openai.AuthenticationError as e:
        results.append(check("openai wrong key", e.status_code == 401, f"{type(e).__name__} {e.status_code}"))
    return all(results)


def with_anthropic(url, key):
    client = anthropic.Anthropic(base_url=url, api_key=key, max_retries=0)
    whole = client.messages.create(
        model="llamabro", max_tokens=16, messages=[{"role": "user", "content": PROMPT}], extra_body=GREEDY)
    text = "".join(b.text for b in whole.content if b.type == "text")
    results = [check("anthropic whole", bool(text.strip()) and whole.stop_reason in ("end_turn", "max_tokens"),
                     f"{text!r}, {whole.stop_reason}")]

    with client.messages.stream(
            model="llamabro", max_tokens=16, messages=[{"role": "user", "content": PROMPT}],
            extra_body=GREEDY) as stream:
        streamed = "".join(stream.text_stream)
        final = stream.get_final_message()
    results.append(check("anthropic streamed", streamed == text and final.usage.output_tokens > 0, repr(streamed)))

    wrong = anthropic.Anthropic(base_url=url, api_key=key + "-wrong", max_retries=0)
    try:
        wrong.messages.create(model="llamabro", max_tokens=4, messages=[{"role": "user", "content": PROMPT}])
        results.append(check("anthropic wrong key", False, "answered"))
    except anthropic.AuthenticationError as e:
        results.append(check("anthropic wrong key", e.status_code == 401, f"{type(e).__name__} {e.status_code}"))
    return all(results)


def main(url, key):
    print(f"openai {openai.__version__}, anthropic {anthropic.__version__}")
    ok = with_openai(url, key)
    ok = with_anthropic(url, key) and ok
    print("clients OK" if ok else "clients FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
