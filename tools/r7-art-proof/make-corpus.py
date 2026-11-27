from pathlib import Path
import colorsys, os, subprocess, uuid
base=Path(os.environ["R7_ART_EVIDENCE"])
for n in range(8):
    album=base/"music"/f"Fixture Artist {n+1}"/f"Fixture Album {n+1}"
    album.mkdir(parents=True, exist_ok=True)
    edge=360
    palette = [bytes(round(v*255) for v in colorsys.hsv_to_rgb((n/8+i*.12)%1, .65+.07*i, .95-.14*i)) for i in range(4)]
    pixels=b"".join(palette[((x+y*(n+1))//(27+n*5)+(x//(43+n)))%4] for y in range(edge) for x in range(edge))
    art=album/"cover.jpg"
    if not art.exists():
        subprocess.run(["ffmpeg","-v","error","-f","image2pipe","-c:v","ppm","-i","-","-frames:v","1","-threads","1","-update","1",str(art)],
            input=f"P6\n{edge} {edge}\n255\n".encode()+pixels,check=True)
    for i in range(1,4):
        song=album/f"{i:02} Track {i}.flac"
        if song.exists():continue
        meta={"title":f"Track {n+1}.{i}","artist":f"Fixture Artist {n+1}","albumartist":f"Fixture Artist {n+1}",
             "album":f"Fixture Album {n+1}","track":i,"date":"2024",
             "MUSICBRAINZ_ARTISTID":uuid.uuid5(uuid.NAMESPACE_URL,f"r7-artist-{n}"),
             "MUSICBRAINZ_ALBUMID":uuid.uuid5(uuid.NAMESPACE_URL,f"r7-release-{n}"),
             "MUSICBRAINZ_RELEASEGROUPID":uuid.uuid5(uuid.NAMESPACE_URL,f"r7-group-{n}"),
             "MUSICBRAINZ_TRACKID":uuid.uuid5(uuid.NAMESPACE_URL,f"r7-track-{n}-{i}")}
        tags=[word for k,v in meta.items() for word in ["-metadata",f"{k}={v}"]]
        subprocess.run(["ffmpeg","-v","error","-f","lavfi","-i",f"sine=frequency={220+30*n+11*i}:duration=6",
                        "-c:a","flac","-threads","1",*tags,str(song)],check=True)
