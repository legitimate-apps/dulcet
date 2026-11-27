import http.client
import json
import os
import signal
import sys
import threading
import time
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

base = Path(os.environ["R7_ART_EVIDENCE"])
server_port, proxy_port, source_port = map(int, sys.argv[1:4])
phase = "seed"
phase_lock = threading.Lock()
photos = sorted((base / "music").glob("*/*/cover.jpg"))
names = ["Lumen Drift", "Harbor Lights", "Quiet Engines", "Saltwater Choir", "Glass Animals Club", "Orchard Radio"]
trends = ["Northern Lights", "Green Harbour", "Silver Cloud"]
artists = [(str(uuid.uuid5(uuid.NAMESPACE_URL, name)), name) for name in names + trends]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass
    def send_body(self, status, body, kind="application/json", headers=()):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(status)
        if not any(k.lower() == "content-type" for k, _ in headers):
            self.send_header("Content-Type", kind)
        for key, value in headers:
            if key.lower() not in ("content-length", "transfer-encoding", "connection", "server", "date"):
                self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
            except BrokenPipeError:
                pass
    def output(self, value):
        self.send_body(200, json.dumps(value))

class Source(Handler):
    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        path = url.path
        args = dict(urllib.parse.parse_qsl(url.query))
        if path == "/similar-artists/json":
            return self.output([{"artist_mbid": key, "name": name, "score": 99-i}
                                for i,(key,name) in enumerate(artists[:len(names)])])
        if path == "/1/stats/sitewide/artists":
            return self.output({"payload": {"artists": [{"artist_mbid": key, "artist_name": name, "listen_count": 1000-i}
                for i,(key,name) in enumerate(artists[len(names):])]}})
        if path == "/1/stats/sitewide/release-groups":
            return self.output({"payload": {"release_groups": []}})
        if path == "/1/stats/sitewide/recordings":
            return self.output({"payload": {"recordings": []}})
        if path == "/1/explore/fresh-releases/":
            return self.output({"payload": {"releases": []}})
        if path == "/1/playlist/search":
            return self.output({"playlists": []})
        if path == "/api/v1/artist":
            return self.output([{"id": i + 1, "artistName": name, "foreignArtistId": key,
                                "images": [{"coverType": "poster", "url": f"/MediaCover/{i+1}/poster.jpg?lastWrite=1", "extension": ".jpg"}]}
                                for i,(key,name) in enumerate(artists) if args.get("mbId") in (None,key)])
        if path.startswith("/MediaCover/"):
            index = int(path.split("/")[2]) - 1
            return self.send_body(200, photos[index % len(photos)].read_bytes(), "image/jpeg")
        self.send_body(404, "{}")

class Proxy(Handler):
    def do_GET(self): self.forward()
    def do_HEAD(self): self.forward()
    def do_PUT(self): self.forward()
    def do_DELETE(self): self.forward()
    def do_POST(self):
        global phase
        if self.path == "/art-control/refill":
            with phase_lock: phase = "refill"
            print("CONTROL refill enabled", flush=True)
            return self.output({"refill": True})
        if self.path == "/art-control/stop":
            pid = int((base / "server.pid").read_text())
            os.kill(pid, signal.SIGTERM)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                try:
                    health = http.client.HTTPConnection("127.0.0.1", server_port, timeout=1)
                    health.connect()
                    health.close()
                    time.sleep(0.1)
                except OSError:
                    print("CONTROL real Dulcet Server stopped", flush=True)
                    return self.output({"serverStopped": True})
            return self.send_body(500, "{}")
        self.forward()

    def forward(self):
        url = urllib.parse.urlsplit(self.path)
        args = dict(urllib.parse.parse_qsl(url.query))
        size = int(self.headers.get("Content-Length", "0"))
        payload = self.rfile.read(size) if size else None
        if payload and self.headers.get("Content-Type", "").startswith("application/x-www-form-urlencoded"):
            args.update(urllib.parse.parse_qsl(payload.decode()))
        endpoint = url.path.rsplit("/", 1)[-1].removesuffix(".view")
        shelf = args.get("id")
        artist_shelf = endpoint == "getDulcetDiscoverShelf" and shelf in ("similarArtists", "trendingArtists")
        is_photo = endpoint == "getDulcetDiscoverCoverArt"
        with phase_lock:
            current = phase
        if current == "seed" and is_photo:
            print("EFFECT seed photo misses", flush=True)
            return self.send_body(200, '<svg xmlns="http://www.w3.org/2000/svg"></svg>', "image/svg+xml")
        if current == "refill" and artist_shelf:
            time.sleep(4)
        headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection", "accept-encoding")}
        conn = http.client.HTTPConnection("127.0.0.1", server_port, timeout=25)
        try:
            conn.request(self.command, self.path, body=payload, headers=headers)
            response = conn.getresponse()
            body = response.read()
            if artist_shelf and response.status == 200:
                result = json.loads(body)
                data = result["subsonic-response"].get("dulcetDiscover", {})
                rows = data.get("item", [])
                art_count = sum("artworkId" in row for row in rows)
                if current == "seed" and rows:
                    data["stale"] = True
                    data["ageSeconds"] = 36000
                    # Keep the first ID to exercise a tile whose earlier request failed and whose ID stays the same.
                    for row in rows[1:]:
                        row.pop("artworkId", None)
                    body = json.dumps(result).encode()
                print(f"EFFECT {current} {shelf} rows={len(rows)} artwork={art_count}", flush=True)
            if is_photo or endpoint == "getCoverArt":
                kind = response.getheader("Content-Type", "")
                print(f"EFFECT {current} {endpoint} status={response.status} type={kind} bytes={len(body)} size={args.get('size')}", flush=True)
            return self.send_body(response.status, body, headers=response.getheaders())
        except (OSError, http.client.HTTPException):
            if endpoint == "ping" or artist_shelf or endpoint == "getDulcetHome":
                print(f"EFFECT stopped {endpoint}", flush=True)
            return self.send_body(503, "{}")
        finally:
            conn.close()

source = ThreadingHTTPServer(("127.0.0.1", source_port), Source)
threading.Thread(target=source.serve_forever, daemon=True).start()
ThreadingHTTPServer(("127.0.0.1", proxy_port), Proxy).serve_forever()
