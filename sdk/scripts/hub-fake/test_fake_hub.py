"""Contract tests for the two-host fake Hub fixture."""

import hashlib
import json
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class FakeHubTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.source = Path(cls.temp.name) / "source.bin"
        cls.source.write_bytes(b"source file bytes")
        cls.token = "hf_" + "a" * 34
        corpus = {"repos": {
            "test/flat": {"files": [
                {"path": "nested/model.gguf", "seed": 1, "size": 4096},
                {"path": "README.md", "text": "hello"},
                {"path": "source.gguf", "source": str(cls.source)},
                {"path": "listing.gguf", "size": 1, "oid": "0" * 39 + "1"}
            ]},
            "test/relative": {"redirect": "relative", "files": [
                {"path": "model.gguf", "seed": 2, "size": 4096}]},
            "test/gated": {"gated_token": cls.token, "files": [
                {"path": "model.gguf", "seed": 3, "size": 4096}]},
            "test/stream": {"files": [
                {"path": "model.gguf", "seed": 4, "size": 4 * 1048576}]}
        }}
        cls.corpus = Path(cls.temp.name) / "corpus.json"
        cls.corpus.write_text(json.dumps(corpus))
        cls.process = subprocess.Popen(
            [sys.executable, str(Path(__file__).with_name("fake_hub.py")),
             "--corpus", str(cls.corpus), "--api-port", "0", "--cdn-port", "0"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        ports = json.loads(cls.process.stdout.readline())
        cls.api = f"http://127.0.0.1:{ports['api']}"
        cls.cdn = f"http://127.0.0.1:{ports['cdn']}"
        cls.commit = "a" * 40
        cls.no_redirect = build_opener(NoRedirect())

    @classmethod
    def tearDownClass(cls):
        cls.process.terminate()
        cls.process.wait(timeout=5)
        cls.temp.cleanup()

    def setUp(self):
        self.request("POST", self.api + "/_reset", {})

    def request(self, method, url, data=None, headers=None, follow=True, timeout=4):
        payload = json.dumps(data).encode() if data is not None else None
        req = Request(url, data=payload, method=method,
                      headers={**({"Content-Type": "application/json"} if data is not None else {}),
                               **(headers or {})})
        opener = urlopen if follow else self.no_redirect.open
        try:
            with opener(req, timeout=timeout) as response:
                return response.status, dict(response.headers), response.read()
        except HTTPError as error:
            with error:
                return error.code, dict(error.headers), error.read()

    def resolve(self, repo="test/flat", name="nested/model.gguf", **kwargs):
        return self.request("GET", f"{self.api}/{repo}/resolve/{self.commit}/{name}", **kwargs)

    def blob_url(self):
        status, headers, _ = self.resolve(follow=False)
        self.assertEqual(302, status)
        return headers["Location"]

    def control(self, *faults):
        self.request("POST", self.api + "/_control", {"faults": list(faults)})

    def test_refs_tree_and_unknown_routes(self):
        self.assertEqual([], json.loads(self.request("GET", self.api + "/_log")[2]))
        status, _, body = self.request("GET", self.api + "/api/models/test/flat/refs")
        self.assertEqual(200, status)
        self.assertEqual(self.commit, json.loads(body)["branches"][0]["targetCommit"])
        status, _, body = self.request("GET", self.api + f"/api/models/test/flat/tree/{self.commit}?recursive=true")
        self.assertEqual(200, status)
        tree = json.loads(body)
        self.assertEqual(sorted(x["path"] for x in tree), [x["path"] for x in tree])
        self.assertEqual("directory", next(x for x in tree if x["path"] == "nested")["type"])
        model = next(x for x in tree if x["path"] == "nested/model.gguf")
        self.assertEqual(4096, model["lfs"]["size"])
        self.assertEqual(134, model["lfs"]["pointerSize"])
        listing = next(x for x in tree if x["path"] == "listing.gguf")
        self.assertEqual("0" * 39 + "1", listing["lfs"]["oid"])
        self.assertEqual(404, self.request("GET", self.api + "/api/models/no/repo/refs")[0])
        self.assertEqual(404, self.request("GET", self.api + "/api/models/test/flat/tree/bad")[0])
        self.assertEqual(404, self.resolve(name="missing.gguf")[0])
        self.assertEqual(404, self.resolve(name="listing.gguf")[0])

    def test_resolve_head_plain_and_source(self):
        status, headers, body = self.request(
            "HEAD", f"{self.api}/test/flat/resolve/{self.commit}/nested/model.gguf", follow=False)
        self.assertEqual((302, b""), (status, body))
        self.assertEqual(self.commit, headers["X-Repo-Commit"])
        self.assertEqual("4096", headers["X-Linked-Size"])
        status, headers, body = self.resolve(name="README.md")
        self.assertEqual((200, b"hello"), (status, body))
        git_oid = hashlib.sha1(b"blob 5\0" + body).hexdigest()
        self.assertEqual('"' + git_oid + '"', headers["ETag"])
        self.assertEqual("bytes", headers["Accept-Ranges"])
        status, _, body = self.resolve(name="README.md", headers={"Range": "bytes=2-"})
        self.assertEqual((206, b"llo"), (status, body))
        self.assertEqual((200, self.source.read_bytes()),
                         (self.resolve(name="source.gguf")[0], self.resolve(name="source.gguf")[2]))

    def test_cdn_range_416_auth_and_log(self):
        url = self.blob_url()
        status, headers, body = self.request("GET", url)
        self.assertEqual((200, 4096), (status, len(body)))
        self.assertEqual(hashlib.sha256(body).hexdigest(), headers["ETag"].strip('"'))
        status, headers, part = self.request("GET", url, headers={"Range": "bytes=10-"})
        self.assertEqual((206, body[10:]), (status, part))
        self.assertEqual("bytes 10-4095/4096", headers["Content-Range"])
        self.assertEqual(206, self.request("GET", url, headers={"Range": "bytes=0-"})[0])
        self.assertEqual(416, self.request("GET", url, headers={"Range": "bytes=4096-"})[0])
        self.assertEqual(400, self.request("GET", url, headers={"Authorization": "Bearer secret"})[0])
        self.assertEqual((200, b""),
                         (self.request("HEAD", url)[0], self.request("HEAD", url)[2]))
        log = json.loads(self.request("GET", self.api + "/_log")[2])
        self.assertTrue(any(x["host"] == "cdn" and x["auth"] and x["status"] == 400 for x in log))
        self.assertTrue(any(x["range"] == "bytes=10-" and x["bytes"] == 4086 for x in log))
        self.assertFalse(any("secret" in json.dumps(x) for x in log))

    def test_cdn_log_exposes_inflight_bytes(self):
        self.control({"kind": "slow", "path": "model.gguf", "rate_mib_s": 1})
        status, headers, _ = self.request("GET", self.api +
            f"/test/stream/resolve/{self.commit}/model.gguf", follow=False)
        self.assertEqual(302, status)
        worker = threading.Thread(target=lambda: self.request("GET", headers["Location"], timeout=10))
        worker.start()
        seen_inflight = False
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            rows = json.loads(self.request("GET", self.api + "/_log")[2])
            if any(row["host"] == "cdn" and 0 < row["bytes"] < 4 * 1048576
                   for row in rows) and worker.is_alive():
                seen_inflight = True
                break
            time.sleep(0.1)
        worker.join(timeout=10)
        self.assertTrue(seen_inflight)

    def test_gated_and_relative_redirect(self):
        for route in (f"/api/models/test/gated/refs",
                      f"/api/models/test/gated/tree/{self.commit}",
                      f"/test/gated/resolve/{self.commit}/model.gguf"):
            self.assertEqual(401, self.request("GET", self.api + route)[0])
            self.assertEqual(200 if "resolve" not in route else 302,
                             self.request("GET", self.api + route,
                                          headers={"Authorization": "Bearer " + self.token},
                                          follow=False)[0])
        status, headers, _ = self.resolve(repo="test/relative", name="model.gguf", follow=False)
        self.assertEqual(302, status)
        self.assertTrue(headers["Location"].startswith("/cdn-proxy/"))
        status, headers, _ = self.request("GET", self.api + headers["Location"], follow=False)
        self.assertEqual(302, status)
        self.assertTrue(headers["Location"].startswith(self.cdn))
        self.assertEqual(4096, len(self.resolve(repo="test/relative", name="model.gguf")[2]))

    def test_faults_status_ignore_range_corrupt_drop_stall_slow(self):
        url = self.blob_url()
        original = self.request("GET", url)[2]
        self.control({"kind": "status", "host": "api", "match": "refs", "status": 429,
                      "retry_after": 7})
        status, headers, _ = self.request("GET", self.api + "/api/models/test/flat/refs")
        self.assertEqual((429, "7"), (status, headers["Retry-After"]))
        self.assertEqual(200, self.request("GET", self.api + "/api/models/test/flat/refs")[0])
        self.control({"kind": "status", "host": "api", "match": "tree", "status": 503},
                     {"kind": "status", "host": "api", "match": "resolve", "status": 503},
                     {"kind": "status", "host": "cdn", "match": "blob", "status": 503})
        self.assertEqual(503, self.request("GET", self.api + f"/api/models/test/flat/tree/{self.commit}")[0])
        self.assertEqual(503, self.resolve(follow=False)[0])
        self.assertEqual(503, self.request("GET", url)[0])
        self.control({"kind": "ignore_range", "path": "nested/model.gguf"})
        self.assertEqual((200, original),
                         (lambda x: (x[0], x[2]))(self.request("GET", url, headers={"Range": "bytes=2-"})))
        self.control({"kind": "corrupt", "path": "nested/model.gguf", "offset": 2})
        changed = self.request("GET", url)[2]
        self.assertEqual(original[2] ^ 0xFF, changed[2])
        self.assertEqual(original[:2] + original[3:], changed[:2] + changed[3:])
        self.control({"kind": "drop", "path": "nested/model.gguf", "after_bytes": 100})
        with self.assertRaises(Exception):
            self.request("GET", url)
        log = json.loads(self.request("GET", self.api + "/_log")[2])
        self.assertTrue(any(x["host"] == "cdn" and x["bytes"] == 100 for x in log))
        self.control({"kind": "stall", "path": "nested/model.gguf", "seconds": 0.1})
        with self.assertRaises(Exception):
            self.request("GET", url, timeout=1)
        self.control({"kind": "slow", "rate_mib_s": 1000})
        self.assertEqual(original, self.request("GET", url)[2])

    def test_fault_body_and_missing_accept_ranges(self):
        self.control({"kind": "status", "host": "api", "match": "refs", "status": 200, "body": []})
        status, _, body = self.request("GET", self.api + "/api/models/test/flat/refs")
        self.assertEqual((200, []), (status, json.loads(body)))
        url = self.blob_url()
        self.assertEqual("bytes", self.request("HEAD", url)[1]["Accept-Ranges"])
        self.control({"kind": "no_accept_ranges", "path": "nested/model.gguf", "method": "HEAD"})
        self.assertNotIn("Accept-Ranges", self.request("HEAD", url)[1])
        self.assertEqual("bytes", self.request("HEAD", url)[1]["Accept-Ranges"])


if __name__ == "__main__":
    unittest.main()
