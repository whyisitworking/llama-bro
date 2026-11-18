#!/usr/bin/env python3
"""One turn of upstream llama-server's /v1/realtime conversation (D95, voice-mode plan step 1), standard library only.

Sends a spoken question (16-bit mono WAV at 16 kHz) as input_audio_buffer.append events, commits it, asks for a
response and saves the reply's audio (PCM16 at the session's output rate) as a WAV next to its text and timings.
usage: [RT_VOICE=female] [RT_LANG=en] realtime-turn.py HOST:PORT question.wav out-prefix [text,audio | audio | text] [instructions]
"""
import base64, json, os, socket, struct, sys, time, wave

def ws_connect(host, port, path):
    s = socket.create_connection((host, port))
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall((f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
    head = b""
    while b"\r\n\r\n" not in head:
        head += s.recv(1)
    if b" 101 " not in head.split(b"\r\n")[0]:
        sys.exit(f"handshake failed: {head!r}")
    return s

def ws_send(s, text):
    data = text.encode()
    mask = os.urandom(4)
    n = len(data)
    hdr = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else
                           bytes([0x80 | 126]) + struct.pack(">H", n) if n < 65536 else
                           bytes([0x80 | 127]) + struct.pack(">Q", n))
    s.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf

def ws_recv(s):
    msg = b""
    while True:
        b0, b1 = recv_exact(s, 2)
        op, n = b0 & 0x0F, b1 & 0x7F
        if n == 126:
            n = struct.unpack(">H", recv_exact(s, 2))[0]
        elif n == 127:
            n = struct.unpack(">Q", recv_exact(s, 8))[0]
        payload = recv_exact(s, n)
        if op == 0x8:
            raise EOFError
        if op == 0x9:  # ping
            continue
        msg += payload
        if b0 & 0x80:
            return msg.decode()

def main():
    hostport, wav_in, prefix = sys.argv[1:4]
    modalities = (sys.argv[4] if len(sys.argv) > 4 else "text,audio").split(",")
    instructions = sys.argv[5] if len(sys.argv) > 5 else None
    host, port = hostport.split(":")
    with wave.open(wav_in) as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1, "16-bit mono WAV"
        rate, pcm = w.getframerate(), w.readframes(w.getnframes())
    s = ws_connect(host, int(port), "/v1/realtime")
    events = []
    def recv():
        ev = json.loads(ws_recv(s))
        events.append({"t": time.monotonic(), "type": ev.get("type")})
        if ev.get("type") == "error":
            print("error:", ev["error"]["message"])
        return ev
    ev = recv()
    out_rate = ev["session"]["audio"].get("output", {}).get("format", {}).get("rate", 24000)
    session = {"output_modalities": modalities, "audio": {"input": {"format": {"type": "audio/pcm", "rate": rate}}}}
    if instructions:
        session["instructions"] = instructions
    out = {k: v for k, v in (("voice", os.environ.get("RT_VOICE")), ("language", os.environ.get("RT_LANG"))) if v}
    if out:
        session["audio"]["output"] = out
    ws_send(s, json.dumps({"type": "session.update", "session": session}))
    while recv().get("type") not in ("session.updated", "error"):
        pass
    step = rate // 10 * 2  # 100 ms chunks
    for i in range(0, len(pcm), step):
        ws_send(s, json.dumps({"type": "input_audio_buffer.append", "audio": base64.b64encode(pcm[i:i + step]).decode()}))
    ws_send(s, json.dumps({"type": "input_audio_buffer.commit"}))
    t0 = time.monotonic()
    ws_send(s, json.dumps({"type": "response.create"}))
    text, audio, t_audio, t_text = [], bytearray(), None, None
    while True:
        ev = recv()
        t = ev.get("type")
        if t == "response.output_text.delta" or t == "response.output_audio_transcript.delta":
            t_text = t_text or time.monotonic() - t0
            text.append(ev["delta"])
        elif t == "response.output_audio.delta":
            t_audio = t_audio or time.monotonic() - t0
            audio += base64.b64decode(ev["delta"])
        elif t in ("response.done", "error"):
            break
    took = time.monotonic() - t0
    s.close()
    with wave.open(prefix + ".wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(out_rate); w.writeframes(bytes(audio))
    secs = len(audio) / 2 / out_rate
    summary = {"modalities": modalities, "audio_s": round(secs, 2), "took_s": round(took, 2),
               "first_audio_s": t_audio and round(t_audio, 2), "first_text_s": t_text and round(t_text, 2),
               "rtf": round(secs / took, 2) if took else None, "text": "".join(text)}
    json.dump({"summary": summary, "events": events}, open(prefix + ".json", "w"), indent=1)
    print(json.dumps(summary, indent=1))

main()
