#!/bin/bash
set -euo pipefail
root="$PWD"
out="$R7_ART_EVIDENCE"
dd="$RUNNER_TEMP/r7-art-dd"
mkdir -p "$out/screens"
udid=
server_pid=
fixture_pid=
cleanup() {
    if [ -n "$udid" ]; then xcrun simctl shutdown "$udid" 2>/dev/null || true; fi
    if [ -n "$fixture_pid" ]; then kill "$fixture_pid" 2>/dev/null || true; fi
    if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; fi
    for photo in "$out/screens/"*.png; do
        if [ -f "$photo" ]; then
            sips -Z 800 -s format jpeg -s formatOptions 50 "$photo" --out "${photo%.png}.jpg" >/dev/null
        fi
    done
}
trap cleanup EXIT
trap 'exit 143' TERM
python3 tools/r7-art-proof/make-corpus.py
python3 tools/r7-art-proof/fixture.py 4913 4914 4915 > "$out/fixture.log" 2>&1 &
fixture_pid=$!
source_url=http://127.0.0.1:4915
env DULCET_MUSIC="$out/music" DULCET_DATA="$RUNNER_TEMP/r7-art-server-data" \
    DULCET_PORT=4913 DULCET_BIND=127.0.0.1 DULCET_ADMIN_USER=fixture DULCET_ADMIN_PASSWORD=fixture-canary-pw \
    DULCET_LISTENBRAINZ_URL="$source_url" DULCET_LISTENBRAINZ_LABS_URL="$source_url" \
    DULCET_MUSICBRAINZ_URL="$source_url" DULCET_COVER_ART_ARCHIVE_URL="$source_url" \
    DULCET_LRCLIB=off DULCET_TEST_MODE=true DULCET_LISTENBRAINZ_TOKEN=stub-token \
    DULCET_INTEGRATION_LIDARR_URL="$source_url" DULCET_INTEGRATION_LIDARR_API_KEY=stub-key \
    server/build/install/dulcet-server/bin/dulcet-server > "$out/server.log" 2>&1 &
server_pid=$!
printf '%s\n' "$server_pid" > "$out/server.pid"
for i in {1..50}; do
    if python3 tools/r7-art-proof/seed.py > "$out/seed.log" 2>&1; then break; fi
    if [ "$i" = 50 ]; then cat "$out/seed.log"; exit 3; fi
    sleep 2
done
udid=$(xcrun simctl list devices booted -j | jq -r '.devices[][] | select(.name=="iPhone 18 Pro" and .state=="Booted") | .udid' | head -n 1)
if [ -z "$udid" ]; then
    udid=$(xcrun simctl list devices available -j | jq -r '.devices | to_entries[] | select(.key|contains("iOS-27")) | .value[] | select(.name=="iPhone 18 Pro") | .udid' | head -n 1)
fi
test -n "$udid"
xcrun simctl bootstatus "$udid" -b
plan=$(find "$dd/Build/Products" -maxdepth 1 -name 'DulcetiOS_*iphonesimulator*.xctestrun' -print -quit)
test -f "$plan"
export TEST_RUNNER_DULCET_UI_TEST_SERVER_URL=http://127.0.0.1:4914
export TEST_RUNNER_DULCET_DESIGN_TOUR_OUT="$out/screens"
xcodebuild test-without-building -xctestrun "$plan" -destination "id=$udid" -parallel-testing-enabled NO \
  -resultBundlePath "$out/art-proof.xcresult" \
  '-only-testing:DulcetiOSUITests/DulcetDesignTourUITests/testRoundSevenArtworkStays' | tee "$out/pilot.log"
