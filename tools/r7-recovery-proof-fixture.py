from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from urllib.error import URLError
from urllib.parse import urlsplit
from urllib.request import urlopen
import json
import os
import signal
import subprocess
import sys
import time

root = Path(sys.argv[1]).resolve()
root.mkdir(parents=True, exist_ok=True)
music = root / "music" / "Velvet Harbor" / "Tide Lines"
music.mkdir(parents=True, exist_ok=True)
artist = "5b11f4ce-a62d-471e-81fc-a69a8278c7da"
album = "ddbf733c-1182-42a2-9bae-059afcbc3b93"
track = "f978d934-9403-416f-9085-54c008938333"
subprocess.run([
    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
    "sine=frequency=440:duration=4", "-metadata", "title=Crossing Lights",
    "-metadata", "artist=Velvet Harbor", "-metadata", "albumartist=Velvet Harbor",
    "-metadata", "album=Tide Lines", "-metadata", "track=1",
    "-metadata", f"MUSICBRAINZ_ARTISTID={artist}",
    "-metadata", f"MUSICBRAINZ_ALBUMARTISTID={artist}",
    "-metadata", f"MUSICBRAINZ_RELEASEGROUPID={album}", str(music / "01-Crossing Lights.flac"),
], check=True)

class Source(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        values = {
            "/1/stats/sitewide/artists": {"artists": [
                {"artist_mbid": artist, "artist_name": "Velvet Harbor", "listen_count": 100}]},
            "/1/stats/sitewide/release-groups": {"release_groups": [
                {"release_group_mbid": album, "release_group_name": "Tide Lines", "artist_name": "Velvet Harbor", "listen_count": 100}]},
            "/1/stats/sitewide/recordings": {"recordings": [
                {"recording_mbid": track, "track_name": "Crossing Lights", "artist_name": "Velvet Harbor", "listen_count": 100}]},
            "/1/explore/fresh-releases/": {"releases": []},
        }
        document = {"payload": values[path]} if path in values else None
        if path == "/1/playlist/search":
            document = {"playlists": []}
        data = json.dumps(document if document is not None else []).encode()
        self.send_response(200 if document is not None else 404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

source = ThreadingHTTPServer(("127.0.0.1", 4908), Source)
Thread(target=source.serve_forever, daemon=True).start()
source_url = "http://127.0.0.1:4908"
env = dict(os.environ, JAVA_OPTS="-Xmx256m", DULCET_ADMIN_USER="fixture",
    DULCET_ADMIN_PASSWORD="fixture-canary-pw", DULCET_TEST_MODE="true",
    DULCET_LISTENBRAINZ_URL=source_url, DULCET_LISTENBRAINZ_LABS_URL=source_url,
    DULCET_MUSICBRAINZ_URL=source_url, DULCET_COVER_ART_ARCHIVE_URL=source_url)
binary = Path("server/build/install/dulcet-server/bin/dulcet-server").resolve()
settings = [f"--music={root / 'music'}", f"--data={root / 'data'}", "--port=4907",
            "--bind=127.0.0.1", "--lrclib=off"]
rest = "http://127.0.0.1:4907/rest/"
query = "?u=fixture&p=fixture-canary-pw&v=1.16.1&c=recovery-fixture&f=json"
server = None

def stop():
    global server
    if server is None:
        return
    if server.poll() is None:
        server.terminate()
        try:
            server.wait(timeout=5)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
    print(f"STOPPED pid={server.pid} rc={server.returncode}", flush=True)
    server = None

def start(timeout=18):
    global server
    if server is not None and server.poll() is None:
        return True
    with (root / "server.log").open("ab") as log:
        server = subprocess.Popen([str(binary), "serve", *settings], env=env, stdout=log, stderr=log)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and server.poll() is None:
        try:
            with urlopen(rest + "ping" + query, timeout=0.5) as response:
                if json.load(response)["subsonic-response"]["status"] == "ok":
                    print(f"READY pid={server.pid}", flush=True)
                    return True
        except (OSError, URLError):
            time.sleep(0.15)
    stop()
    return False

class Control(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path == "/__dulcet/outage?state=on":
            stop()
            code = 200
        elif self.path == "/__dulcet/outage?state=off":
            code = 200 if start() else 503
        else:
            code = 404
        self.send_response(code)
        self.end_headers()

def interrupted(*_):
    raise SystemExit(0)

signal.signal(signal.SIGTERM, interrupted)
signal.signal(signal.SIGINT, interrupted)
try:
    if not start(timeout=120):
        raise RuntimeError("Dulcet Server failed startup")
    with ThreadingHTTPServer(("127.0.0.1", 4909), Control) as control:
        control.serve_forever()
finally:
    stop()
    source.shutdown()
    source.server_close()
