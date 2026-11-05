"""Check every engine card's energy cost against the game's own bytecode.

`include/constants/Cards.h` holds the costs in `getEnergyCost`, a switch whose
`default: return 1` quietly answers for every enum without a case. A card added
with a cost of 0, 2, 3 or X therefore compiles, dispatches, and plays for 1
energy, and nothing in CI notices: the coverage manifest only tracks whether a
card is implemented, the fixtures import `cost` from the recording instead of
comparing it, and `check_card_aliases.py` only looks at names.

Neutralize was implemented with cost 1 that way -- the Java class passes 0 to
`AbstractCard.<init>` and both recorded Silent fixtures say `cost: 0`.

This check reads the cost the bytecode passes to `AbstractCard.<init>` (and the
absolute cost `upgrade()` passes to `upgradeBaseCost`) for every card of a color,
maps the game ID onto the engine enum through `cardStringIds`, and compares it with
what `getEnergyCost` returns. Only cards the engine implements are compared: an
unimplemented card has no behaviour to get wrong.

Only the base cost is a `getEnergyCost` question, because `CardInstance::upgrade()`
takes the upgrade cost from `getEnergyCost(id, true)`. That argument is ignored by
the switch today, so upgraded costs are reported as a note and do not fail this
check -- otherwise the card that upgrades cheaper would be blamed on the wrong shape.

The class files are game assets and cannot be committed, and javap is not on CI,
so the check is only as strong as its inputs: point `--classes` at a directory
holding `com/megacrit/cardcrawl/cards/<color>/*.class`, or put that same
directory in `STS_GAME_CLASSES` and say nothing. Without either it reports that
it was skipped instead of passing a comparison it never made, and it exits
non-zero unless `--allow-skip` says a missing class tree is expected -- that way
CI, which has no game assets, still runs the step while `check_local.py` on a
machine that does have them cannot silently 0-check the run. That file runs the
CI command verbatim, so its step had no way to pass a path at all: on a machine
with the class tree "local passed" meant "skipped", and looked exactly like
"compared 201 cards". A skip now always prints `SKIP` and its reason.

  python tools/check_card_costs.py --classes E:/sts-devkit/jar-scratch/curlfull
  python tools/check_card_costs.py --color green --classes ... --show
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENGINE = 'candidates/sts-ironclad-agent/combat_engine/combat4r/engine-source'
CARDS_H = ENGINE + '/include/constants/Cards.h'
SCHEMA = 'overlay/resources/sts1solver/distill2-schema.json'

# javap needs the fully qualified name, and the card's color is its package.
PACKAGE = 'com.megacrit.cardcrawl.cards.%s'
COLORS = ('blue', 'green', 'purple', 'red', 'colorless', 'curse', 'status')

# The same directory as `--classes`, for callers that run one fixed command:
# the CI step, and `check_local.py`, which copies the CI command verbatim.
CLASSES_ENV = 'STS_GAME_CLASSES'

_DECL = re.compile(r'^  (?:public|private|protected|static|final|abstract|synchronized|native)\b.*;\s*$')


def strip_comments(text):
    """C++ comments out, string and character literals kept."""
    out, i, n = [], 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == '//':
            i = text.find('\n', i)
            i = n if i < 0 else i
        elif two == '/*':
            end = text.find('*/', i + 2)
            i = n if end < 0 else end + 2
        elif text[i] in '"\'':
            quote = text[i]
            out.append(text[i])
            i += 1
            while i < n:
                out.append(text[i])
                if text[i] == '\\':
                    i += 1
                    if i < n:
                        out.append(text[i])
                elif text[i] == quote:
                    i += 1
                    break
                i += 1
        else:
            out.append(text[i])
            i += 1
    return ''.join(out)


def array(text, name):
    match = re.search(r'\b' + name + r'\s*(?:\[[^\]]*\])+\s*=?\s*\{(.*?)\};', text, re.S)
    if not match:
        raise ValueError('array not found: ' + name)
    return [x.strip() for x in match.group(1).split(',')]


def card_tables(root):
    """engine enum -> game card ID, from the tables that sit side by side in Cards.h."""
    text = strip_comments((root / CARDS_H).read_text(encoding='utf-8'))
    body = re.search(r'enum class CardId[^{]*\{(.*?)\};', text, re.S).group(1)
    enums = [re.sub(r'=.*', '', x).strip() for x in body.split(',') if x.strip()]
    # A trailing comma leaves an empty last element behind the split.
    ids = [x.strip('"') for x in array(text, 'cardStringIds') if x.strip()]
    if len(enums) != len(ids):
        raise ValueError('card tables disagree: %d enum names, %d game IDs' % (len(enums), len(ids)))
    return dict(zip(enums, ids))


def implemented(root):
    """The engine enums the coverage manifest calls implemented, in any pile."""
    manifest = json.loads((root / 'overlay/coverage_manifest.json').read_text(encoding='utf-8'))
    missing = set(manifest['missing_cards'].values())
    known = set(json.loads((root / SCHEMA).read_text(encoding='utf-8'))['ids']['cards'])
    return known - missing


def cost_of_enum(text):
    """engine enum -> the `return` expression getEnergyCost answers for it.

    The switch is a run of `case CardId::X:` lines followed by one `return`, so the
    cases seen since the previous return all share it. An enum with no case is absent
    from the result and falls through to the switch's `default: return 1`.
    """
    body = re.search(r'static constexpr int getEnergyCost\(CardId id, bool upgraded\)\s*\{'
                     r'.*?switch \(id\) \{(.*?)\n\s*\}\s*\n\s*\}', text, re.S)
    if not body:
        raise ValueError('getEnergyCost not found in ' + CARDS_H)
    switch = body.group(1)
    costs = {}
    pending = []
    for line in switch.split('\n'):
        case = re.match(r'\s*case CardId::(\w+):', line)
        if case:
            pending.append(case.group(1))
            continue
        ret = re.match(r'\s*return (.+?);', line)
        if ret:
            group = ret.group(1).strip()
            for enum in pending:
                costs[enum] = group
            pending = []
            continue
    return costs


def methods(text):
    """Declaration line -> that method's text. javap closes a class but never a method.

    javap prints `  public void upgrade();` for the signature and then the body, so the line
    does end in `;`. Splitting on the declaration lines is what keeps each method's
    instructions in its own block.
    """
    lines = text.split('\n')
    starts = [i for i, line in enumerate(lines) if _DECL.match(line) and '(' in line]
    found = {}
    for index, start in enumerate(starts):
        end = starts[index + 1] if index + 1 < len(starts) else len(lines)
        found[lines[start].strip().rstrip(';')] = '\n'.join(lines[start:end])
    return found


def instructions(block):
    """(offset, opcode, operand) per instruction. The operand keeps javap's `// comment`."""
    out = []
    for line in block.split('\n'):
        match = re.match(r'^\s*(\d+):\s+(\S+)(?:\s+(.*?))?\s*$', line)
        if match:
            out.append((int(match.group(1)), match.group(2), (match.group(3) or '').strip()))
    return out


def int_literal(op, arg):
    """The int an instruction pushes, or None when it pushes something else."""
    if op in ('iconst_m1', 'iconst_0', 'iconst_1', 'iconst_2', 'iconst_3', 'iconst_4', 'iconst_5'):
        return -1 if op == 'iconst_m1' else int(op.split('_')[1])
    if op in ('bipush', 'sipush'):
        return int(arg.split('//')[0])
    if op == 'ldc' and '// int ' in arg:
        return int(arg.split('// int ')[1].split()[0])
    return None


def constructor_cost(ctor, stem):
    """The cost the card class passes to AbstractCard.<init>.

    The constructor pushes the image path last, then the cost, so the cost is the int
    pushed after the last string before the super call.
    """
    init = next((offset for offset, op, arg in instructions(ctor)
                 if op == 'invokespecial' and 'AbstractCard."<init>"' in arg), None)
    if init is None:
        raise ValueError('%s: no AbstractCard.<init> call in the constructor' % stem)
    before = [insn for insn in instructions(ctor) if insn[0] < init]
    strings = [i for i, (_, op, arg) in enumerate(before) if op == 'ldc' and '// String ' in arg]
    if not strings:
        raise ValueError('%s: no image-path string before AbstractCard.<init>' % stem)
    for _, op, arg in before[strings[-1] + 1:]:
        value = int_literal(op, arg)
        if value is not None:
            return value
    raise ValueError('%s: the cost argument to AbstractCard.<init> is not an int literal' % stem)


def upgrade_base_cost(upgrade_block, base):
    """The cost the card ends up at after upgrade(), or None when upgrade() keeps it.

    Card classes change cost through `upgradeBaseCost(int)`, not by assigning a field,
    so reading only `putfield` finds nothing and would call every card unchanged.
    """
    if upgrade_block is None:
        return None
    result = base
    for offset, op, arg in instructions(upgrade_block):
        if op != 'invokevirtual' or 'upgradeBaseCost:' not in arg:
            continue
        pushed = pushed_before(upgrade_block, offset)
        if pushed is not None:
            result = pushed
    return result if result != base else None


def pushed_before(block, end):
    """The int literal pushed immediately before offset `end`, or None if a field did."""
    best = None
    for offset, op, arg in instructions(block):
        if offset >= end:
            break
        value = int_literal(op, arg)
        if value is not None:
            best = value
        elif op in ('getfield', 'aload', 'invokevirtual', 'invokespecial', 'invokestatic'):
            best = None
    return best


def read_costs(class_root, package):
    """game card ID -> (cost, upgraded cost), for every class javap can read in that package."""
    directory = class_root.joinpath(*package.split('.'))
    if not directory.is_dir():
        raise ValueError('no class directory for %s at %s' % (package, directory))
    stems = sorted(path.stem for path in directory.glob('*.class'))
    if not stems:
        raise ValueError('no class files for %s at %s' % (package, directory))
    javap = javap_path()
    if javap is None:
        raise ValueError('javap not found; put it on PATH or set STS_JAVAP')
    result = subprocess.run([javap, '-p', '-c', '-constants', '-cp', str(class_root)]
                            + ['%s.%s' % (package, stem) for stem in stems],
                            capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode != 0:
        raise ValueError('javap failed on %s: %s' % (package, result.stderr.strip()[:400]))
    costs = {}
    for block in re.split(r'\n(?=public (?:final )?(?:abstract )?class |\nclass )', result.stdout):
        header = re.search(r'^(?:public (?:final )?(?:abstract )?)?class (\S+)', block, re.M)
        if not header:
            continue
        full = header.group(1)
        stem = full.rsplit('.', 1)[-1]
        text = block
        found = methods(text)
        ctor = found.get('public %s()' % full)
        if ctor is None:
            continue
        game_id = next((arg.split('// String ')[1].strip() for _, op, arg in instructions(ctor)
                        if op == 'ldc' and '// String ' in arg), None)
        if game_id is None:
            continue
        cost = constructor_cost(ctor, stem)
        after = upgrade_base_cost(found.get('public void upgrade()'), cost)
        costs[game_id] = (cost, after if after is not None else cost)
    return costs


def javap_path():
    named = os.environ.get('STS_JAVAP')
    if named:
        return named
    return shutil.which('javap')


def problems(root, class_root, colors):
    """(base cost, upgrade-cost) disagreements, kept apart.

    `getEnergyCost` ignores the `upgraded` argument, while `CardInstance::upgrade()`
    takes the upgrade cost from `getEnergyCost(id, true)`, so an upgraded mismatch
    cannot be fixed here and is reported as a note instead of a failure.
    """
    engine_costs = cost_of_enum(strip_comments((root / CARDS_H).read_text(encoding='utf-8')))
    known = implemented(root)
    java_to_enum = {game_id: enum for enum, game_id in card_tables(root).items()}
    base_found, upgrade_found, compared, skipped, seen = [], [], 0, [], []
    for color in colors:
        try:
            game_costs = read_costs(class_root, PACKAGE % color)
        except ValueError as error:
            skipped.append(str(error))
            continue
        for game_id, (base, upgraded) in sorted(game_costs.items()):
            enum = java_to_enum.get(game_id)
            if enum is None:
                continue
            if enum not in known:
                continue
            expression = engine_costs.get(enum)
            if expression is None:
                expression = '1'  # the switch's default
            # `upgraded ? 2 : 3` is the switch being asked for both; the base cost is
            # always the second branch, so a ternary is read, not rejected.
            ternary = re.fullmatch(r'upgraded\s*\?\s*(-?\d+)\s*:\s*(-?\d+)', expression)
            base_expression = ternary.group(2) if ternary else expression
            compared += 1
            if color not in seen:
                seen.append(color)
            if not re.fullmatch(r'-?\d+', base_expression):
                base_found.append('%s (%s, %s): the game charges %d, getEnergyCost returns `%s`, '
                                  'which this check cannot read' % (game_id, color, enum, base, expression))
                continue
            got = int(base_expression)
            if got != base:
                base_found.append('%s (%s, %s): the game charges %d, getEnergyCost answers %d from `%s`'
                                  % (game_id, color, enum, base, got, expression))
            if upgraded != base:
                upgrade_found.append('%s (%s, %s): the game charges %d upgraded, the base is %d'
                                     % (game_id, color, enum, upgraded, base))
    return base_found, upgrade_found, compared, skipped, seen


def resolve_classes(given):
    """The class tree to compare against, or None when there is none to compare.

    `--classes` wins over the environment so a one-off run can point somewhere else
    without unsetting it. A path that is not a directory counts as no input at all:
    comparing against it would find 0 cards and report every package unreadable,
    which buries the real reason (the path is wrong) under seven SKIP lines.
    """
    from_env = (os.environ.get(CLASSES_ENV) or "").strip() or None
    for candidate, origin in ((given, "--classes"), (from_env, "$" + CLASSES_ENV)):
        if not candidate:
            continue
        path = Path(candidate).expanduser()
        if path.is_dir():
            return path.resolve()
        print("SKIP: %s names %s, which is not a directory" % (origin, path))
        # Not fatal on its own: falling through to no input says the same thing
        # louder (and exits non-zero unless --allow-skip), so a stale value in the
        # environment does not crash a run that only meant to pass --classes.
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--classes', type=Path,
                        help='a directory holding com/megacrit/cardcrawl/cards/<color>/*.class '
                             '(default: the one in STS_GAME_CLASSES)')
    parser.add_argument('--color', action='append', choices=COLORS,
                        help='restrict to one color (repeatable; default: every color present)')
    parser.add_argument('--show', action='store_true', help='print every card that was compared')
    parser.add_argument('--allow-skip', action='store_true',
                        help='exit 0 when there is no class tree to compare against (CI has none)')
    args = parser.parse_args()
    root = args.root.resolve()
    class_root = resolve_classes(args.classes)
    if class_root is None:
        # Printed even when --allow-skip accepts it: a silent exit 0 reads as
        # "201 card costs match", which is the one thing this step must not imply.
        print('SKIP: no --classes directory and no {0} set, so no card cost was '
              'compared against the game'.format(CLASSES_ENV))
        if not args.allow_skip:
            sys.exit('FAIL: no card cost was compared against the game (pass '
                     '--allow-skip to accept that, or set {0})'.format(CLASSES_ENV))
        return
    colors = args.color or COLORS
    found, upgrade_found, compared, skipped, seen = problems(root, class_root, colors)
    for reason in skipped:
        print('SKIP: ' + reason)
    if args.show:
        for line in found + upgrade_found:
            print('DIFF: ' + line)
    if upgrade_found:
        print('NOTE: %d implemented cards change cost on upgrade; getEnergyCost ignores the '
              'upgraded argument, so that is outside this check' % len(upgrade_found))
        if args.show:
            for line in upgrade_found:
                print('NOTE: ' + line)
    if found:
        for line in found:
            print('FAIL: ' + line)
        sys.exit('%d card costs disagree with the game bytecode' % len(found))
    if not compared:
        message = 'no card was compared; %d packages unreadable' % len(skipped)
        sys.exit(0 if args.allow_skip else 'FAIL: ' + message)
    print('PASS: %d implemented card costs match the game bytecode (%s)'
          % (compared, ', '.join(seen)))


if __name__ == '__main__':
    main()
