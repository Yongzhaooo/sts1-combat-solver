from pathlib import Path
import tempfile
from workshop_previews import IMAGE, MAX_BYTES, check_image, plan_sync

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    png = root / 'a.png'
    png.write_bytes(b'\x89PNG\r\n\x1a\n' + b'0' * 10)
    assert check_image(png) == png.resolve()
    for name, data in (('big.png', b'\x89PNG\r\n\x1a\n' + b'0' * MAX_BYTES), ('empty.png', b''), ('text.png', b'hello')):
        (root / name).write_bytes(data)
        try:
            check_image(root / name)
        except ValueError:
            pass
        else:
            raise AssertionError(name + ' accepted')

video = (1, '', 'youtube-id')
same = [(IMAGE, 'zh.png', 'u1'), video, (IMAGE, 'en.png', 'u2')]
assert plan_sync(same, ['x/zh.png', 'y/en.png']) == ([], [])
assert plan_sync(same, ['en.png', 'zh.png']) == ([2, 0], ['en.png', 'zh.png'])      # order matters; video kept
assert plan_sync([video], ['zh.png']) == ([], ['zh.png'])
assert plan_sync([], []) == ([], [])
print('workshop previews: ok')
