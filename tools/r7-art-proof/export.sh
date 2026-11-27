#!/bin/bash
set -euo pipefail
out="$R7_ART_EVIDENCE"
shots="$out/screens"
mkdir -p "$shots"
if [ -d "$out/art-proof.xcresult" ] && xcrun xcresulttool export attachments \
    --path "$out/art-proof.xcresult" --output-path "$out/attachments"; then
    python3 - "$out" <<'PY'
import json
from pathlib import Path
import re
import shutil
import sys

out = Path(sys.argv[1])
attachments = out / 'attachments'
for test in json.loads((attachments / 'manifest.json').read_text()):
    for attachment in test['attachments']:
        source = attachments / attachment['exportedFileName']
        name = re.match(r'0[1-5]-(?:listen-now|discover)-[a-z-]+', attachment['suggestedHumanReadableName'])
        if name and source.suffix == '.png':
            shutil.copyfile(source, out / 'screens' / (name.group(0) + '.png'))
PY
fi
for photo in "$shots/"*.png; do
    if [ -f "$photo" ]; then
        sips -Z 800 -s format jpeg -s formatOptions 50 "$photo" --out "${photo%.png}.jpg" >/dev/null
    fi
done
