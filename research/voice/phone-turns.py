#!/usr/bin/env python3
"""Voice plan step 4 (D95): timings of upstream's /v1/realtime conversation on the phone, standard library only.

Each mode opens one session set up as the SDK's voice session would be by an app (F166: a voice, a language and an
answering instruction, text and audio out) and prints one JSON line. Times are seconds, from this machine (adb forward).
  turn HOST:PORT Q.wav [paced]   (RT_SAVE=out.wav keeps the reply's speech) one spoken turn; paced sends the audio at real time, as a microphone does, and times
                                 the reply from the commit; otherwise all at once, as F166 did
  cancel HOST:PORT Q.wav         a turn whose reply is cancelled 2 s into its audio: time to response.done; then the
                                 socket is closed and the time until /slots is idle, and a new session's first event
  transcribe HOST:PORT Q.wav     POST /v1/audio/transcriptions alone
  before HOST:PORT Q.wav         paced turn, then the turn's audio transcribed over HTTP, then response.create
                                 (needs a second slot: the session holds one while it is open)
  parallel HOST:PORT Q.wav       paced turn, response.create and the transcription at once (two slots)
  blocked HOST:PORT Q.wav        with the session open, a transcription with a 20 s timeout (one slot: does it wait?)
"""
import base64, json, os, socket, struct, sys, threading, time, urllib.request, uuid, wave

INSTRUCTIONS = "You are a helpful voice assistant. Answer the user's spoken question in your own words."


def ws_connect(host, port):
    s = socket.create_connection((host, port))
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall((f"GET /v1/realtime HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
    head = b""
    while b"\r\n\r\n" not in head:
        head += s.recv(1)
    if b" 101 " not in head.split(b"\r\n")[0]:
        sys.exit(f"handshake failed: {head!r}")
    return s


def ws_send(s, obj):
    data = json.dumps(obj).encode()
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
        if op == 0x9:
            continue
        msg += payload
        if b0 & 0x80:
            return json.loads(msg.decode())


class Session:
    def __init__(self, host, port):
        self.host, self.port = host, port
        t = time.monotonic()
        self.s = ws_connect(host, port)
        created = self.recv()
        self.first_event_s = time.monotonic() - t
        assert created["type"] == "session.created", created
        self.out_rate = created["session"]["audio"]["output"]["format"]["rate"]
        ws_send(self.s, {"type": "session.update", "session": {
            "instructions": INSTRUCTIONS, "output_modalities": ["text", "audio"],
            "audio": {"output": {"voice": "female", "language": "en"}}}})
        while (ev := self.recv())["type"] != "session.updated":
            assert ev["type"] != "error", ev

    def recv(self):
        return ws_recv(self.s)

    def send(self, obj):
        ws_send(self.s, obj)

    def speak(self, pcm, rate, paced):
        step = rate // 10 * 2  # 100 ms of 16-bit samples
        t0 = time.monotonic()
        for k, i in enumerate(range(0, len(pcm), step)):
            if paced:
                time.sleep(max(0.0, t0 + k * 0.1 - time.monotonic()))
            self.send({"type": "input_audio_buffer.append", "audio": base64.b64encode(pcm[i:i + step]).decode()})
        if paced:
            time.sleep(max(0.0, t0 + len(pcm) / 2 / rate - time.monotonic()))
        self.send({"type": "input_audio_buffer.commit"})
        t_commit = time.monotonic()
        while (ev := self.recv())["type"] != "input_audio_buffer.committed":
            assert ev["type"] != "error", ev
        return t_commit, r(time.monotonic() - t_commit)

    def reply(self, t0, cancel_after_audio_s=None):
        """Collects one reply; times from t0. With cancel_after_audio_s, sends response.cancel that long after the first audio."""
        text, n_audio, first_audio, first_text, t_cancel, pcm = [], 0, None, None, None, bytearray()
        while True:
            ev = self.recv()
            t = ev["type"]
            now = time.monotonic()
            if t == "response.output_text.delta":
                first_text = first_text or now - t0
                text.append(ev["delta"])
            elif t == "response.output_audio.delta":
                first_audio = first_audio or now - t0
                chunk = base64.b64decode(ev["delta"])
                n_audio += len(chunk) // 2
                pcm += chunk
                if cancel_after_audio_s is not None and t_cancel is None and now - t0 - first_audio >= cancel_after_audio_s:
                    self.send({"type": "response.cancel"})
                    t_cancel = now
            elif t == "error":
                raise RuntimeError(ev["error"]["message"])
            elif t == "response.done":
                took = now - t0
                audio_s = n_audio / self.out_rate
                out = {"first_text_s": r(first_text), "first_audio_s": r(first_audio), "took_s": r(took),
                       "audio_s": r(audio_s), "status": ev["response"]["status"],
                       "tokens": ev["response"].get("usage", {}).get("output_tokens"), "text": "".join(text)}
                out["rtf"] = r(audio_s / took)  # seconds of speech per second from t0, as F166
                if os.environ.get("RT_SAVE"):
                    with wave.open(os.environ["RT_SAVE"], "wb") as w:
                        w.setnchannels(1); w.setsampwidth(2); w.setframerate(self.out_rate); w.writeframes(bytes(pcm))
                if t_cancel is not None:
                    out["cancel_to_done_s"] = r(now - t_cancel)
                return out

    def close(self):
        self.s.close()


def r(x):
    return None if x is None else round(x, 3)


def transcribe(host, port, wav_bytes, timeout=600):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"q.wav\"\r\n"
            f"Content-Type: audio/wav\r\n\r\n").encode() + wav_bytes + (
            f"\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nen\r\n--{boundary}--\r\n").encode()
    req = urllib.request.Request(f"http://{host}:{port}/v1/audio/transcriptions", data=body,
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    t = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        text = json.loads(resp.read())["text"]
    return {"transcribe_s": r(time.monotonic() - t), "transcript": text}


def slots_idle(host, port):
    with urllib.request.urlopen(f"http://{host}:{port}/slots", timeout=5) as resp:
        return not any(s.get("is_processing") for s in json.loads(resp.read()))


def main():
    mode, hostport, wav_path = sys.argv[1:4]
    paced = len(sys.argv) > 4 and sys.argv[4] == "paced"
    host, port = hostport.split(":")
    port = int(port)
    wav_bytes = open(wav_path, "rb").read()
    with wave.open(wav_path) as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1 and w.getframerate() == 16000, "16-bit mono 16 kHz WAV"
        rate, pcm = w.getframerate(), w.readframes(w.getnframes())
    out = {"mode": mode, "question_s": r(len(pcm) / 2 / rate)}

    if mode == "transcribe":
        out.update(transcribe(host, port, wav_bytes))
    elif mode == "turn":
        s = Session(host, port)
        out["paced"] = paced
        t_commit, out["commit_to_committed_s"] = s.speak(pcm, rate, paced)
        s.send({"type": "response.create"})
        out.update(s.reply(t_commit))
        s.close()
    elif mode == "cancel":
        s = Session(host, port)
        t_commit, _ = s.speak(pcm, rate, True)
        s.send({"type": "response.create"})
        out.update(s.reply(t_commit, cancel_after_audio_s=2.0))
        t_close = time.monotonic()
        s.close()
        while not slots_idle(host, port):
            time.sleep(0.02)
        out["close_to_idle_s"] = r(time.monotonic() - t_close)
        s2 = Session(host, port)
        out["new_session_first_event_s"] = r(s2.first_event_s)
        s2.close()
    elif mode in ("before", "parallel"):
        s = Session(host, port)
        t_commit, _ = s.speak(pcm, rate, True)
        if mode == "before":
            out.update(transcribe(host, port, wav_bytes))
            s.send({"type": "response.create"})
            out.update(s.reply(t_commit))
        else:
            box = {}
            th = threading.Thread(target=lambda: box.update(transcribe(host, port, wav_bytes)))
            s.send({"type": "response.create"})
            th.start()
            out.update(s.reply(t_commit))
            th.join()
            out.update(box)
        s.close()
    elif mode == "blocked":
        s = Session(host, port)
        try:
            out.update(transcribe(host, port, wav_bytes, timeout=20))
            out["blocked"] = False
        except Exception as e:  # a timeout: the session holds the only slot
            out["blocked"] = True
            out["error"] = str(e)
        s.close()
    else:
        sys.exit(f"unknown mode {mode}")
    print(json.dumps(out), flush=True)


main()
