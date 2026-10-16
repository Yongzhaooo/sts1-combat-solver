"""Check that the three supported-character gates list the same characters.

The gates are backend.SUPPORTED_CLASSES, SolverMod.SUPPORTED and the class tests
in OutsidePacket. They use the game's PlayerClass names. Needs no game.
"""
import argparse
import ast
from pathlib import Path
import re
import sys

CLASSES = {'IRONCLAD', 'THE_SILENT', 'DEFECT', 'WATCHER'}


def backend_gate(text):
    for node in ast.parse(text).body:
        if isinstance(node, ast.Assign) and any(getattr(t, 'id', None) == 'SUPPORTED_CLASSES' for t in node.targets):
            return set(ast.literal_eval(node.value))
    raise ValueError('backend.py has no SUPPORTED_CLASSES')


def mod_gate(text):
    found = re.findall(r'\bSUPPORTED\s*=\s*EnumSet\.(?:of|noneOf)\(([^)]*)\)', text)
    if len(found) != 1:
        raise ValueError('SolverMod.java: expected exactly one SUPPORTED = EnumSet.of(...)')
    return {name.rsplit('.', 1)[-1].strip() for name in found[0].split(',') if name.strip()}


def outside_gate(text):
    # Every class test must name the same set; a class test that differs from another is a gap.
    tests = re.findall(r'"(\w+)"\s*\.equals\(\s*string\(\s*game\s*,\s*"class"\s*\)\s*\)', text)
    if not tests:
        raise ValueError('OutsidePacket.java: no class gate found')
    return set(tests)


def gates(root):
    return {
        'overlay/backend.py': backend_gate((root / 'overlay/backend.py').read_text(encoding='utf-8')),
        'SolverMod.java': mod_gate((root / 'overlay/src/sts1solver/SolverMod.java').read_text(encoding='utf-8')),
        'OutsidePacket.java': outside_gate((root / 'overlay/src/sts1solver/OutsidePacket.java').read_text(encoding='utf-8')),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    found = gates(parser.parse_args().root.resolve())
    for name, classes in found.items():
        unknown = classes - CLASSES
        if unknown:
            sys.exit(f'FAIL: {name} names unknown classes {sorted(unknown)}')
    if len({frozenset(v) for v in found.values()}) != 1:
        sys.exit('FAIL: supported-character gates disagree: '
                 + '; '.join(f'{k}={sorted(v)}' for k, v in found.items()))
    print('PASS: all gates support', sorted(next(iter(found.values()))))


if __name__ == '__main__':
    main()
