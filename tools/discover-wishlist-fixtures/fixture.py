"""Shared, loopback-only fixture wire helpers and generated public upstream shapes."""
import hashlib
import json
from pathlib import Path
import struct
import urllib.parse
import urllib.request
import zlib

USER='discover-owner'
OTHER='discover-other'
PASSWORD='discover-fixture-password'
ADMIN='discover-admin'

def mbid(n):return f'{n:08x}-1111-4111-8111-111111111111'
IDS={'artist':mbid(100),'partial':mbid(200),'held':mbid(201),'unknown':mbid(202),'arrival':mbid(1),'cancel':mbid(2)}

def request(url,body=None,method=None,headers=None):
    req=urllib.request.Request(url,data=None if body is None else json.dumps(body).encode(),method=method,
        headers={'Content-Type':'application/json',**(headers or {})})
    try:
        with urllib.request.urlopen(req,timeout=35) as r:return json.load(r)
    except Exception as error:
        # HTTP exceptions contain authenticated URLs. Only the type belongs in diagnostics.
        raise RuntimeError('fixture request failed: '+type(error).__name__) from None

def rest_url(base,endpoint,user=USER,password=PASSWORD,**params):
    salt='discover-fixture'
    return base+'/rest/'+endpoint+'?'+urllib.parse.urlencode(dict(u=user,s=salt,t=hashlib.md5((password+salt).encode()).hexdigest(),c='dulcet-discover-fixture',v='1.16.1',f='json',**params))

def rest(base,endpoint,user=USER,**params):
    body=request(rest_url(base,endpoint,user=user,**params))['subsonic-response']
    if body['status']!='ok':raise RuntimeError('fixture REST failed: '+endpoint+' code='+str(body.get('error',{}).get('code')))
    return body

def png():
    def chunk(kind,data):return struct.pack('!I',len(data))+kind+data+struct.pack('!I',zlib.crc32(kind+data))
    pixels=b''.join(b'\x00'+bytes([40,80,160])*64 for _ in range(64))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',64,64,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')

def upstream(path,q):
    """The checked-in file preserves source wire shapes, with generated names and UUIDs."""
    catalog=json.loads((Path(__file__).parent/'upstream.json').read_text())
    credit=[{'name':'Fixture Artist','artist':{'id':IDS['artist'],'name':'Fixture Artist'}}]
    def artist(n):return {'id':mbid(100+n),'name':f'Fixture Artist {n:02d}'}
    def group(n):return {'id':mbid(200+n),'title':f'Fixture Album {n:02d}','first-release-date':'2026-01-01','artist-credit':credit}
    def song(n):return {'id':mbid(n),'title':f'Fixture Song {n:02d}','artist-credit':credit}
    def release(n,later=False):
        tracks=[{'position':i+1,'title':f'Fixture Song {n*100+10+i:02d}','recording':song(n*100+10+i)} for i in range(12)]
        return {'id':mbid(300+n+(100 if later else 0)),'title':f'Fixture Album {n:02d}','date':'2026-02-01' if later else '2026-01-01','artist-credit':credit,'release-group':group(n),'media':[{'position':1,'track-count':12,'tracks':tracks}]}
    if path=='/ws/2/artist':rows=[artist(n) for n in range(24)];key='artists';count='artist-count'
    elif path=='/ws/2/recording':rows=[song(n) for n in range(1,25)];key='recordings';count='recording-count'
    elif path=='/ws/2/release-group':rows=[group(n) for n in range(24)];key='release-groups';count='release-group-count'
    elif path=='/ws/2/release':
        n=int(q['release-group'][0].split('-')[0],16)-200
        rows=[] if n==2 else [release(n),release(n,True)];key='releases';count='release-count'
    elif path.startswith('/ws/2/artist/'):return artist(int(path.rsplit('/',1)[1].split('-')[0],16)-100)
    elif path.startswith('/ws/2/release-group/'):return group(int(path.rsplit('/',1)[1].split('-')[0],16)-200)
    elif path.startswith('/ws/2/release/'):
        n=int(path.rsplit('/',1)[1].split('-')[0],16)-300;return release(n-100 if n>=100 else n,n>=100)
    elif path.startswith('/1/playlist/') and path!='/1/playlist/search':
        index=int(path.rsplit('/',1)[1].split('-')[0],16)-600
        if not 0<=index<24:raise ValueError('unknown fixture playlist')
        answer=catalog['/1/playlist/'+mbid(600)]
        answer['playlist'].update(identifier='https://listenbrainz.org/playlist/'+mbid(600+index),title=f'Fixture Public Mix {index:02d}')
        return answer
    elif path in catalog:return catalog[path]
    else:raise ValueError('unscripted upstream path')
    offset=int(q.get('offset',['0'])[0]);limit=int(q.get('limit',['100'])[0])
    return {count:len(rows),key:rows[offset:offset+limit]}
