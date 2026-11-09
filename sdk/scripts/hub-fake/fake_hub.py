#!/usr/bin/env python3
"""Deterministic, fault-injectable local Hugging Face fixture."""

import argparse
import hashlib
import json
import os
import random
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

MIB = 1 << 20
ZERO_OID = "0" * 40


def data_chunks(spec):
    if "source" in spec:
        with open(spec["source"], "rb") as source:
            while chunk := source.read(MIB):
                yield chunk
    elif "text" in spec:
        yield spec["text"].encode("utf-8")
    else:
        size = spec["size"]
        block = random.Random(spec.get("seed", 0)).randbytes(MIB)
        for index in range((size + MIB - 1) // MIB):
            offset = index % MIB
            rotated = block[offset:] + block[:offset] if offset else block
            yield rotated[: min(MIB, size - index * MIB)]


class File:
    def __init__(self, path, spec, hashes):
        self.path = path
        self.spec = spec
        self.listing_only = "oid" in spec
        if "source" in spec:
            source = Path(spec["source"])
            info = source.stat()
            self.size = info.st_size
            key = json.dumps(["v2-source", str(source.resolve()), info.st_size, info.st_mtime_ns])
        elif "text" in spec:
            self.size = len(spec["text"].encode("utf-8"))
            key = json.dumps(["v2-text", spec["text"]])
        else:
            self.size = int(spec["size"])
            key = json.dumps(["v2-seed", spec.get("seed", 0), self.size])
        if self.listing_only:
            self.sha1 = self.sha256 = spec["oid"]
        elif key in hashes:
            self.sha1, self.sha256 = hashes[key]
        else:
            sha1, sha256 = hashlib.sha1(), hashlib.sha256()
            sha1.update(f"blob {self.size}\0".encode("ascii"))
            for chunk in data_chunks(spec):
                sha1.update(chunk)
                sha256.update(chunk)
            self.sha1, self.sha256 = sha1.hexdigest(), sha256.hexdigest()
            hashes[key] = [self.sha1, self.sha256]

    def entry(self):
        result = {"type": "file", "oid": self.sha1, "size": self.size, "path": self.path}
        if self.path.endswith(".gguf") and self.spec.get("lfs", True):
            result["lfs"] = {"oid": self.sha256, "size": self.size, "pointerSize": 134}
        return result


class State:
    def __init__(self, corpora, bind, rate):
        self.bind, self.rate, self.initial_rate = bind, rate, rate
        self.lock = threading.RLock()
        self.faults, self.log = [], []
        self.repos, self.blobs = {}, {}
        cache = Path.home() / ".cache/llamabro-fake-hub/hashes.json"
        try:
            hashes = json.loads(cache.read_text())
        except (OSError, ValueError):
            hashes = {}
        for corpus in corpora:
            for name, repo in corpus.get("repos", {}).items():
                files = {spec["path"]: File(spec["path"], spec, hashes) for spec in repo["files"]}
                self.repos[name] = (repo, files)
                for file in files.values():
                    if file.path.endswith(".gguf") and not file.listing_only:
                        self.blobs[file.sha256] = file
        try:
            cache.parent.mkdir(parents=True, exist_ok=True)
            temp = cache.with_suffix(".tmp")
            temp.write_text(json.dumps(hashes, sort_keys=True))
            temp.replace(cache)
        except OSError:
            pass
        self.api_port = self.cdn_port = None

    def fault(self, kind, path=None, host=None, match=None, method=None):
        with self.lock:
            for fault in self.faults:
                if fault.get("kind") != kind or fault.get("times", 1) == 0:
                    continue
                if path is not None and fault.get("path", "*") not in (path, "*"):
                    continue
                if host is not None and fault.get("host") != host:
                    continue
                if match is not None and fault.get("match") != match:
                    continue
                if method is not None and fault.get("method", method) != method:
                    continue
                if fault.get("times", 1) > 0:
                    fault["times"] = fault.get("times", 1) - 1
                return fault
        return None

    def record(self, host, method, path, range_header, auth, status, count):
        with self.lock:
            self.log.append({"host": host, "method": method, "path": path,
                             "range": range_header, "auth": auth,
                             "status": status, "bytes": count})

    def begin_stream(self, host, method, path, range_header, auth, status):
        with self.lock:
            row = {"host": host, "method": method, "path": path,
                   "range": range_header, "auth": auth, "status": status, "bytes": 0}
            self.log.append(row)
            return row

    def stream_bytes(self, row, count):
        with self.lock:
            row["bytes"] = count


def handler_for(state, host):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, _format, *_args):
            pass

        def do_HEAD(self):
            self.handle_request()

        def do_GET(self):
            self.handle_request()

        def do_POST(self):
            self.handle_request()

        def respond(self, status, payload=b"", headers=None):
            if isinstance(payload, (dict, list)):
                payload = json.dumps(payload).encode("utf-8")
                headers = {**(headers or {}), "Content-Type": "application/json"}
            self.send_response(status)
            for key, value in (headers or {}).items():
                self.send_header(key, str(value))
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            sent = 0
            if self.command != "HEAD" and payload:
                try:
                    self.wfile.write(payload)
                    sent = len(payload)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            return status, sent

        def stream(self, file, path, oid, rate):
            range_header = self.headers.get("Range")
            ignored = state.fault("ignore_range", path=path) if range_header else None
            start = 0
            last = file.size - 1
            if range_header and not ignored:
                match_range = re.fullmatch(r"bytes=(\d+)-(\d*)", range_header)
                if not match_range:
                    return self.respond(416, headers={"Content-Range": f"bytes */{file.size}"})
                start = int(match_range.group(1))
                if match_range.group(2):
                    last = min(int(match_range.group(2)), last)
                if start >= file.size or last < start:
                    return self.respond(416, headers={"Content-Range": f"bytes */{file.size}"})
            status = 206 if range_header and not ignored else 200
            length = last - start + 1
            self.send_response(status)
            self.send_header("Content-Length", str(length))
            if not state.fault("no_accept_ranges", path=path, method=self.command):
                self.send_header("Accept-Ranges", "bytes")
            self.send_header("ETag", f'"{oid}"')
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{last}/{file.size}")
            self.end_headers()
            row = state.begin_stream(host, self.command, urlsplit(self.path).path, range_header,
                                     "Authorization" in self.headers, status)
            self.stream_logged = True
            if self.command == "HEAD":
                return status, 0
            stall = state.fault("stall", path=path)
            if stall:
                time.sleep(float(stall["seconds"]))
                self.close_connection = True
                return status, 0
            drop = state.fault("drop", path=path)
            corrupt = state.fault("corrupt", path=path)
            limit = min(length, max(0, int(drop["after_bytes"]) - start)) if drop else length
            sent = 0
            position = 0
            try:
                for chunk in data_chunks(file.spec):
                    end = position + len(chunk)
                    if end <= start:
                        position = end
                        continue
                    part = chunk[max(0, start - position):]
                    if corrupt and position <= int(corrupt["offset"]) < end:
                        offset = int(corrupt["offset"]) - max(start, position)
                        if 0 <= offset < len(part):
                            changed = bytearray(part)
                            changed[offset] ^= 0xFF
                            part = bytes(changed)
                    part = part[: max(0, limit - sent)]
                    if part:
                        self.wfile.write(part)
                        self.wfile.flush()
                        sent += len(part)
                        state.stream_bytes(row, sent)
                        if rate:
                            time.sleep(len(part) / (float(rate) * MIB))
                    position = end
                    if sent >= limit:
                        break
            except (BrokenPipeError, ConnectionResetError):
                pass
            if drop:
                self.close_connection = True
            return status, sent

        def handle_request(self):
            self.stream_logged = False
            parsed = urlsplit(self.path)
            path = unquote(parsed.path)
            auth = "Authorization" in self.headers
            status, count = 404, 0
            try:
                if host == "api":
                    status, count = self.api(path)
                else:
                    status, count = self.cdn(path, auth)
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True
            finally:
                if path not in ("/_log", "/_reset") and not getattr(self, "stream_logged", False):
                    state.record(host, self.command, path, self.headers.get("Range"),
                                 auth, status, count)

        def api(self, path):
            if path == "/_log" and self.command == "GET":
                with state.lock:
                    return self.respond(200, list(state.log))
            if path == "/_control" and self.command == "POST":
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                with state.lock:
                    state.faults.extend(json.loads(body)["faults"])
                return self.respond(200, {})
            if path == "/_reset" and self.command == "POST":
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                with state.lock:
                    state.faults.clear()
                    state.log.clear()
                    state.rate = state.initial_rate
                return self.respond(200, {})
            if path.startswith("/cdn-proxy/"):
                oid = path.removeprefix("/cdn-proxy/")
                return self.respond(302, headers={"Location":
                    f"http://{state.bind}:{state.cdn_port}/blobs/{oid}?sig=fake"})

            match = re.fullmatch(r"/api/models/([^/]+/[^/]+)/(refs|tree/([^/]+))", path)
            if match:
                name, route, commit = match.groups()
                if name not in state.repos:
                    return self.respond(404, {"error": "Repository not found"})
                repo, files = state.repos[name]
                if not self.authorized(name, repo):
                    return self.gated(name)
                kind = "refs" if route == "refs" else "tree"
                fault = state.fault("status", path=path, host="api", match=kind)
                if fault:
                    return self.injected(fault)
                version = repo.get("commit", "a" * 40)
                if kind == "refs":
                    branches = [{"name": branch, "ref": "refs/heads/" + branch,
                                 "targetCommit": version}
                                for branch in repo.get("branches", ["main"])]
                    return self.respond(200, {"branches": branches, "converts": [], "tags": []})
                if commit != version:
                    return self.respond(404, {"error": "Repository not found"})
                entries = [file.entry() for file in files.values()]
                directories = {"/".join(parts[:i]) for file in files.values()
                               for parts in [file.path.split("/")]
                               for i in range(1, len(parts))}
                entries += [{"type": "directory", "oid": ZERO_OID, "size": 0,
                             "path": directory} for directory in directories]
                return self.respond(200, entries if repo.get("tree_order") == "insertion"
                                    else sorted(entries, key=lambda item: item["path"]))

            match = re.fullmatch(r"/([^/]+/[^/]+)/resolve/([^/]+)/(.+)", path)
            if match:
                name, commit, file_path = match.groups()
                if name not in state.repos:
                    return self.respond(404, {"error": "Repository not found"})
                repo, files = state.repos[name]
                if not self.authorized(name, repo):
                    return self.gated(name)
                fault = state.fault("status", host="api", match="resolve")
                if fault:
                    return self.injected(fault)
                if commit != repo.get("commit", "a" * 40) or file_path not in files:
                    return self.respond(404, {"error": "Entry not found"})
                file = files[file_path]
                if file.listing_only:
                    return self.respond(404, {"error": "Entry not found"})
                if file_path.endswith(".gguf"):
                    location = (f"/cdn-proxy/{file.sha256}" if repo.get("redirect") == "relative"
                                else f"http://{state.bind}:{state.cdn_port}/blobs/{file.sha256}?sig=fake")
                    return self.respond(302, headers={"Location": location,
                        "X-Linked-Size": str(file.size), "X-Linked-Etag": f'"{file.sha256}"',
                        "X-Repo-Commit": repo.get("commit", "a" * 40)})
                return self.stream(file, file_path, file.sha1, 0)
            return self.respond(404, {"error": "Repository not found"})

        def authorized(self, name, repo):
            token = repo.get("gated_token")
            return token is None or self.headers.get("Authorization") == f"Bearer {token}"

        def gated(self, name):
            return self.respond(401, {"error": f"Access to model {name} is restricted. "
                "You must have access to it and be authenticated to access it."})

        def injected(self, fault):
            headers = {"Retry-After": str(fault["retry_after"])} if "retry_after" in fault else None
            return self.respond(int(fault["status"]), fault.get("body", {"error": "injected"}), headers)

        def cdn(self, path, auth):
            if auth:
                return self.respond(400, {"error": "Authorization sent to the CDN"})
            match = re.fullmatch(r"/blobs/([0-9a-f]{64})", path)
            if not match or match.group(1) not in state.blobs:
                return self.respond(404, {"error": "Entry not found"})
            fault = state.fault("status", host="cdn", match="blob", method=self.command)
            if fault:
                return self.injected(fault)
            file = state.blobs[match.group(1)]
            slow = state.fault("slow", path=file.path)
            if slow:
                state.rate = float(slow["rate_mib_s"])
            return self.stream(file, file.path, file.sha256, state.rate)

    return Handler


def serve(corpora, bind="127.0.0.1", api_port=0, cdn_port=0, rate=0):
    state = State(corpora, bind, rate)
    api = ThreadingHTTPServer((bind, api_port), handler_for(state, "api"))
    cdn = ThreadingHTTPServer((bind, cdn_port), handler_for(state, "cdn"))
    state.api_port, state.cdn_port = api.server_port, cdn.server_port
    threads = [threading.Thread(target=server.serve_forever, daemon=True)
               for server in (api, cdn)]
    for thread in threads:
        thread.start()
    return state, api, cdn


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", action="append", required=True)
    parser.add_argument("--api-port", type=int, default=0)
    parser.add_argument("--cdn-port", type=int, default=0)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--rate-mib-s", type=float, default=0)
    args = parser.parse_args()
    corpora = [json.loads(Path(path).read_text()) for path in args.corpus]
    _, api, cdn = serve(corpora, args.bind, args.api_port, args.cdn_port, args.rate_mib_s)
    print(json.dumps({"api": api.server_port, "cdn": cdn.server_port}), flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        api.shutdown()
        cdn.shutdown()


if __name__ == "__main__":
    main()
