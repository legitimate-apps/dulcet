# Playback reference fixture

`navidrome-reference.mp3` is **“Life of Riley” by Kevin MacLeod**
(incompetech.com), licensed under
[Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/).

The 7,550,103-byte file is the same reference asset served by the disposable
Navidrome conformance environment. Its realistic duration, tags, bitrate, and
byte layout are required to reproduce AVFoundation's production request pattern.

`silence-reference.mp3` is an eight-second, stereo, 44.1 kHz silent negative control
created for Dulcet. Reproduce it with:

```sh
ffmpeg -hide_banner -loglevel error -f lavfi -i anullsrc=r=44100:cl=stereo \
  -t 8 -c:a libmp3lame -b:a 128k -map_metadata -1 silence-reference.mp3
```

The playback integration test independently decodes both fixtures, then measures
music and silence through the same engine post-effects PCM observer. Silence must
produce real frames and buffers; an observer that never fires must fail.
