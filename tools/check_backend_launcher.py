"""Compile the real launcher with metadata-only ModTheSpire stubs; no game JAR needed."""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='solver-launcher-') as folder:
    temp = Path(folder)
    # Only the loader's metadata types are substituted; process creation is production code.
    for name, body in {
        'Loader': 'public static ModInfo[] MODINFOS = new ModInfo[0];',
        'ModInfo': 'public String ID; public java.net.URL jarURL;',
    }.items():
        (temp / (name + '.java')).write_text(
            'package com.evacipated.cardcrawl.modthespire; public class ' + name + ' {' + body + '}',
            encoding='utf-8')
    subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-d', str(temp),
                    str(temp / 'Loader.java'), str(temp / 'ModInfo.java'),
                    str(root / 'overlay/src/sts1solver/BackendRuntime.java'),
                    str(root / 'overlay/test/BackendRuntimeCheck.java')], check=True)
    subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', str(temp),
                    'sts1solver.BackendRuntimeCheck', str(Path(sys.executable).absolute())], check=True)
