import json, time, urllib.request, urllib.parse
base="http://127.0.0.1:4913/rest/"
creds={"u":"fixture","p":"fixture-canary-pw","v":"1.16.1","c":"seed","f":"json"}
def call(ep, **args):
    with urllib.request.urlopen(base+ep+"?"+urllib.parse.urlencode(creds|args),timeout=7) as response:
        result=json.load(response)["subsonic-response"]
    assert result["status"]=="ok",ep
    return result
albums=call("getAlbumList2",type="alphabeticalByName",size=50)["albumList2"].get("album",[])
assert len(albums)==8, len(albums)
now=int(time.time()*1000)
for index,album in enumerate(albums):
    for track in call("getAlbum",id=album["id"])["album"]["song"]:
        call("scrobble",id=track["id"],time=now-index*10000,submission="true")
        call("star",id=track["id"])
print("Seeded plays and favorites across",len(albums),"albums")
