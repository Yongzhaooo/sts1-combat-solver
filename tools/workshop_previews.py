"""List, add or sync a Steam Workshop item's additional preview images (the gallery under the main image).

  python tools/workshop_previews.py --item ID list
  python tools/workshop_previews.py --item ID add IMG [IMG ...] [--note TEXT]
  python tools/workshop_previews.py --item ID sync IMG [IMG ...] [--note TEXT]

sync makes the item's image previews exactly IMG, in order, and changes nothing when they already match by file
name. Works for any game (--app, default Slay the Spire 646570); the Steam client must run, logged in as an owner
of the item. SteamCMD and the game's mod-uploader only set the main preview, so this calls the Steamworks flat API
(ISteamUGC AddItemPreviewFile / RemoveItemPreview) through ctypes. The steam_api library comes from --steam-api, or
from the steamworks4j 1.9.0 jar on Maven Central (Steamworks SDK redistributable), checked against its pinned SHA-1.
"""
import argparse
import ctypes as C
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import time
import urllib.request
import zipfile

JAR_URL = 'https://repo1.maven.org/maven2/com/code-disaster/steamworks4j/steamworks4j/1.9.0/steamworks4j-1.9.0.jar'
JAR_SHA1 = 'd69b7f7540745a8d569a5400554e34d42e035da8'
LIB = {'win32': 'steam_api64.dll', 'darwin': 'libsteam_api.dylib'}.get(sys.platform, 'libsteam_api.so')
MAX_BYTES = 1024 * 1024          # Steam rejects a larger preview
IMAGE = 0                        # k_EItemPreviewType_Image
MAGIC = (b'\x89PNG\r\n\x1a\n', b'\xff\xd8\xff', b'GIF87a', b'GIF89a')
RESULT_OK = 1


def check_image(path):
    path = Path(path)
    if not path.is_file():
        raise ValueError('%s is not a file' % path)
    if not 0 < path.stat().st_size <= MAX_BYTES:
        raise ValueError('%s is %d bytes; Steam takes 1 to %d' % (path, path.stat().st_size, MAX_BYTES))
    with open(path, 'rb') as f:
        if not f.read(8).startswith(MAGIC):
            raise ValueError('%s is not PNG, JPEG or GIF' % path)
    return path.resolve()


def plan_sync(existing, wanted):
    """existing: [(type, original file name, url)] in gallery order; wanted: image paths.
    Returns (preview indices to remove, highest first, paths to add); both empty when the images already match."""
    images = [i for i, row in enumerate(existing) if row[0] == IMAGE]
    if [existing[i][1] for i in images] == [Path(p).name for p in wanted]:
        return [], []
    return sorted(images, reverse=True), list(wanted)


def steam_api(path, cache):
    if path:
        return Path(path)
    lib = Path(cache) / LIB
    if not lib.is_file():
        data = urllib.request.urlopen(JAR_URL, timeout=120).read()
        if hashlib.sha1(data).hexdigest() != JAR_SHA1:
            sys.exit('steamworks4j jar SHA-1 differs from the pinned value')
        lib.parent.mkdir(parents=True, exist_ok=True)
        jar = lib.parent / 'steamworks4j-1.9.0.jar'
        jar.write_bytes(data)
        with zipfile.ZipFile(jar) as z:
            lib.write_bytes(z.read(LIB))
    return lib


class SubmitResult(C.Structure):     # SubmitItemUpdateResult_t, callback 3404
    _fields_ = [('result', C.c_int), ('needs_legal', C.c_bool), ('item', C.c_uint64)]


class QueryCompleted(C.Structure):   # SteamUGCQueryCompleted_t, callback 3401
    _fields_ = [('handle', C.c_uint64), ('result', C.c_int), ('returned', C.c_uint32), ('total', C.c_uint32),
                ('cached', C.c_bool), ('cursor', C.c_char * 256)]


class Steam:
    def __init__(self, lib, app):
        self.api, self.app = C.CDLL(str(lib)), app
        u64, u32, p = C.c_uint64, C.c_uint32, C.c_void_p
        sig = dict(SteamAPI_Init=(C.c_bool,), SteamAPI_RunCallbacks=(None,), SteamAPI_Shutdown=(None,),
                   SteamAPI_SteamUGC_v016=(p,), SteamAPI_SteamUtils_v010=(p,),
                   SteamAPI_ISteamUtils_IsAPICallCompleted=(C.c_bool, p, u64, C.POINTER(C.c_bool)),
                   SteamAPI_ISteamUtils_GetAPICallResult=(C.c_bool, p, u64, p, C.c_int, C.c_int, C.POINTER(C.c_bool)),
                   SteamAPI_ISteamUGC_CreateQueryUGCDetailsRequest=(u64, p, C.POINTER(u64), u32),
                   SteamAPI_ISteamUGC_SetReturnAdditionalPreviews=(C.c_bool, p, u64, C.c_bool),
                   SteamAPI_ISteamUGC_SendQueryUGCRequest=(u64, p, u64),
                   SteamAPI_ISteamUGC_GetQueryUGCNumAdditionalPreviews=(u32, p, u64, u32),
                   SteamAPI_ISteamUGC_GetQueryUGCAdditionalPreview=(C.c_bool, p, u64, u32, u32, C.c_char_p, u32,
                                                                    C.c_char_p, u32, C.POINTER(C.c_int)),
                   SteamAPI_ISteamUGC_ReleaseQueryUGCRequest=(C.c_bool, p, u64),
                   SteamAPI_ISteamUGC_StartItemUpdate=(u64, p, u32, u64),
                   SteamAPI_ISteamUGC_AddItemPreviewFile=(C.c_bool, p, u64, C.c_char_p, C.c_int),
                   SteamAPI_ISteamUGC_RemoveItemPreview=(C.c_bool, p, u64, u32),
                   SteamAPI_ISteamUGC_SubmitItemUpdate=(u64, p, u64, C.c_char_p))
        for name, (res, *args) in sig.items():
            f = getattr(self.api, name)
            f.restype, f.argtypes = res, args
            setattr(self, name.rsplit('_', 1)[-1] if name.startswith('SteamAPI_ISteam') else name, f)

    def __enter__(self):
        # steam_api reads the app id from steam_appid.txt in the working directory.
        self.cwd, self.tmp = os.getcwd(), tempfile.TemporaryDirectory()
        Path(self.tmp.name, 'steam_appid.txt').write_text(str(self.app))
        os.chdir(self.tmp.name)
        if not self.api.SteamAPI_Init():
            self._leave()
            sys.exit('SteamAPI_Init failed: is the Steam client running and logged in?')
        self.ugc, self.utils = self.SteamAPI_SteamUGC_v016(), self.SteamAPI_SteamUtils_v010()
        return self

    def __exit__(self, *exc):
        self.SteamAPI_Shutdown()
        self._leave()

    def _leave(self):
        os.chdir(self.cwd)
        self.tmp.cleanup()

    def wait(self, call, struct, kind):
        failed = C.c_bool()
        for _ in range(3000):
            self.SteamAPI_RunCallbacks()
            if self.IsAPICallCompleted(self.utils, call, C.byref(failed)):
                out = struct()
                if not self.GetAPICallResult(self.utils, call, C.byref(out), C.sizeof(out), kind, C.byref(failed)) or failed.value:
                    sys.exit('Steam call result %d failed' % kind)
                return out
            time.sleep(0.1)
        sys.exit('timed out waiting for Steam callback %d' % kind)

    def previews(self, item):
        q = self.CreateQueryUGCDetailsRequest(self.ugc, (C.c_uint64 * 1)(item), 1)
        self.SetReturnAdditionalPreviews(self.ugc, q, True)
        r = self.wait(self.SendQueryUGCRequest(self.ugc, q), QueryCompleted, 3401)
        try:
            if r.result != RESULT_OK or r.returned != 1:
                sys.exit('item query failed: EResult %d, %d results' % (r.result, r.returned))
            rows = []
            for i in range(self.GetQueryUGCNumAdditionalPreviews(self.ugc, q, 0)):
                url, name, kind = C.create_string_buffer(2048), C.create_string_buffer(1024), C.c_int()
                self.GetQueryUGCAdditionalPreview(self.ugc, q, 0, i, url, 2048, name, 1024, C.byref(kind))
                rows.append((kind.value, name.value.decode('utf-8', 'replace'), url.value.decode('utf-8', 'replace')))
            return rows
        finally:
            self.ReleaseQueryUGCRequest(self.ugc, q)

    def update(self, item, remove, add, note):
        h = self.StartItemUpdate(self.ugc, self.app, item)
        for i in remove:
            if not self.RemoveItemPreview(self.ugc, h, i):
                sys.exit('RemoveItemPreview(%d) refused' % i)
        for path in add:
            if not self.AddItemPreviewFile(self.ugc, h, str(path).encode('utf-8'), IMAGE):
                sys.exit('AddItemPreviewFile refused %s' % path)
        r = self.wait(self.SubmitItemUpdate(self.ugc, h, note.encode('utf-8') if note else None), SubmitResult, 3404)
        if r.needs_legal:
            print('note: this account still has to accept the Steam Workshop legal agreement')
        if r.result != RESULT_OK:
            sys.exit('SubmitItemUpdate failed: EResult %d' % r.result)


def show(rows):
    print('%d additional preview(s)' % len(rows))
    for i, (kind, name, url) in enumerate(rows):
        print('  %d  type %d  %s  %s' % (i, kind, name or '-', url))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--item', type=int, required=True, help='Workshop item id')
    ap.add_argument('--app', type=int, default=646570, help='consumer app id (default: Slay the Spire)')
    ap.add_argument('--steam-api', help='steam_api library to load instead of the pinned steamworks4j copy')
    ap.add_argument('--cache', default=Path(__file__).resolve().parents[1] / 'build' / 'steam-api')
    ap.add_argument('--note', help='change note for the update')
    ap.add_argument('command', choices=('list', 'add', 'sync'))
    ap.add_argument('images', nargs='*')
    a = ap.parse_args()
    images = [check_image(p) for p in a.images]
    if a.command != 'list' and not images:
        ap.error('%s needs at least one image' % a.command)
    with Steam(steam_api(a.steam_api, a.cache), a.app) as steam:
        rows = steam.previews(a.item)
        remove, add = plan_sync(rows, images) if a.command == 'sync' else ([], images)
        if remove or add:
            steam.update(a.item, remove, add, a.note)
            rows = steam.previews(a.item)
        elif a.command == 'sync':
            print('previews already match; nothing submitted')
        show(rows)


if __name__ == '__main__':
    main()
