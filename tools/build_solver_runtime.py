"""Build only the combat advisor runtime; no teacher weights or training bundle."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import os
import sysconfig
import importlib.machinery
import pybind11

root = Path(__file__).resolve().parents[1]
repo = root / 'candidates/sts-ironclad-agent'
runtime = repo / 'runtime'
build = repo / 'build/engine'
platform_options = []
if sys.platform == 'win32':
    platform_options.extend(['-A', 'x64'])
if sys.platform == 'darwin':
    # Keep the core archive and the separately linked overlay on the same deployment target.
    target = os.environ.setdefault('MACOSX_DEPLOYMENT_TARGET', sysconfig.get_config_var('MACOSX_DEPLOYMENT_TARGET') or '11.0')
    platform_options.append('-DCMAKE_OSX_DEPLOYMENT_TARGET=' + target)


def _raw_environment_names() -> list[str]:
    """Every variable name in this process's Win32 environment block, spelling intact.

    os.environ cannot answer this: on Windows it is case-insensitive and collapses
    HTTPS_PROXY and https_proxy into one entry, hiding the duplicate that breaks
    MSBuild (MSB6001). Reading the block directly is the only way to see both.
    """
    if sys.platform != 'win32':
        return list(os.environ)
    import ctypes

    kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel32.GetEnvironmentStringsW.restype = ctypes.POINTER(ctypes.c_wchar)
    kernel32.GetEnvironmentStringsW.argtypes = []
    block = kernel32.GetEnvironmentStringsW()
    names, index = [], 0
    while block[index] != '\x00':
        end = index
        while block[end] != '\x00':
            end += 1
        names.append(block[index:end].split('=', 1)[0])
        index = end + 1
    return names


def msbuild_safe_environ() -> dict[str, str]:
    """The environment with each proxy variable kept in one spelling only.

    MSBuild copies the environment into a StringDictionaryWithComparer, which
    ignores key case, and throws MSB6001 ("已添加项。字典中的关键字") on the second
    spelling -- from ToolTask.GetProcessStartInfo, before cl.exe starts. When the
    victim is cmake's compiler probe the symptom is instead the misleading
    'No CMAKE_CXX_COMPILER could be found'. This machine's MSYS shell injects
    http_proxy/https_proxy/no_proxy alongside the registry's upper-case set.
    """
    names = set(_raw_environment_names())
    upper = {name for name in names if name.isupper()}
    lowered = {name.lower() for name in upper}
    safe = dict(os.environ)
    for name in names:
        if name.lower() in lowered and name not in upper:
            safe.pop(name, None)
    return safe

build_env = msbuild_safe_environ()
subprocess.run(['cmake', '-S', str(repo / 'combat_engine'), '-B', str(build),
                '-DCMAKE_BUILD_TYPE=Release', '-Dpybind11_DIR=' + pybind11.get_cmake_dir(),
                '-DPython_EXECUTABLE=' + sys.executable,
                '-DPYTHON_EXECUTABLE=' + sys.executable,
                '-DSTS_RUNTIME_DIR=' + str(runtime), *platform_options], check=True, env=build_env)
subprocess.run(['cmake', '--build', str(build), '--config', 'Release', '-j', '4'], check=True, env=build_env)
subprocess.run(['ctest', '--test-dir', str(build), '-C', 'Release', '--output-on-failure'], check=True, env=build_env)
modules = {}
for name in ('slaythespire', 'fightsim', 'live_combat_search'):
    paths = [runtime / 'engine' / (name + suffix) for suffix in importlib.machinery.EXTENSION_SUFFIXES
             if (runtime / 'engine' / (name + suffix)).exists()]
    if len(paths) != 1 or paths[0].stat().st_size < 1000:
        raise RuntimeError('Expected one native extension for ' + name)
    modules[paths[0].name] = hashlib.sha256(paths[0].read_bytes()).hexdigest()
(runtime / 'live-manifest.json').write_text(json.dumps({'engine_files': modules}, indent=2), encoding='utf-8')
# The overlay configures its own build tree, so it needs the same cleaned
# environment: it is a separate process and would otherwise inherit the duplicates.
subprocess.run([sys.executable, str(root / 'overlay/build_native.py')], check=True, env=build_env)
subprocess.run([sys.executable, str(root / 'tools/check_solver_runtime.py')], check=True, env=build_env)
print('Combat runtime built; no policy network required.')
