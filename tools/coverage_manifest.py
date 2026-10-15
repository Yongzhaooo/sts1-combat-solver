"""Derive which cards and class relics the combat simulator implements, per character.

Reads the engine sources only (no build, no game):
  - reward pools, starter decks, colorless pool and card tables from constants/ and GameContext.cpp
  - implemented cards = `case CardId::X` labels of the card switches in BattleContext.cpp,
    of the per-character dispatch functions in src/combat/characters/, and of cases that
    overlay/build_native.py injects into the shared switch
  - hooked class relics = relic ids named in combat sources (src/combat, include/combat)
Writes overlay/coverage_manifest.json. With --check it fails if the committed file is stale or if
a character that overlay/backend.py supports has any gap. The backend loads the file to refuse a
combat that contains a missing item before search starts.
"""
import argparse
import ast
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ENGINE = 'candidates/sts-ironclad-agent/combat_engine/combat4r/engine-source'
MANIFEST = 'overlay/coverage_manifest.json'
CHARACTERS = ['IRONCLAD', 'THE_SILENT', 'DEFECT', 'WATCHER']  # CharacterClass / CardColor order
# Generated cards are not in any pool, so which character makes them is declared here. Each
# name must be a SPECIAL-rarity card of the engine; add the owner when a character gains one.
GENERATED = {
    'IRONCLAD': [],
    'THE_SILENT': ['SHIV'],
    'DEFECT': [],
    'WATCHER': ['MIRACLE', 'SMITE', 'SAFETY', 'INSIGHT', 'BETA', 'OMEGA', 'EXPUNGER',
                'THROUGH_VIOLENCE', 'BECOME_ALMIGHTY', 'FAME_AND_FORTUNE', 'LIVE_FOREVER'],
}
# The shared switch for each card type; a case in the wrong one would never run for that card.
TYPE_FUNCTIONS = {'ATTACK': 'useAttackCard', 'SKILL': 'useSkillCard', 'POWER': 'usePowerCard'}
CHARACTER_FUNCTIONS = ['useSilentCard', 'useDefectCard', 'useWatcherCard']


def strip_comments(text):
    out, i, n = [], 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == '//':
            while i < n and text[i] != '\n':
                i += 1
        elif two == '/*':
            end = text.find('*/', i + 2)
            i = n if end < 0 else end + 2
        elif text[i] in '"\'':
            quote, start = text[i], i
            i += 1
            while i < n and text[i] != quote:
                i += 2 if text[i] == '\\' else 1
            i += 1
            out.append(text[start:i])
        else:
            out.append(text[i])
            i += 1
    return ''.join(out)


def read(path):
    return strip_comments((ROOT / path).read_text(encoding='utf-8'))


def block(text, open_index):
    """Text between the brace at open_index and its match."""
    depth = 0
    for i in range(open_index, len(text)):
        depth += (text[i] == '{') - (text[i] == '}')
        if depth == 0:
            return text[open_index + 1:i]
    raise ValueError('unbalanced braces')


def top_level(body):
    """body with everything inside nested braces blanked, so only depth-0 text remains."""
    out, depth = [], 0
    for ch in body:
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
        out.append(ch if depth == 0 and ch not in '{}' else ' ')
    return ''.join(out)


def switch_cases(text, function):
    """CardId case labels directly inside the first switch of `function`."""
    match = re.search(r'\b' + re.escape(function) + r'\s*\([^)]*\)\s*\{', text)
    if not match:
        raise ValueError('function not found: ' + function)
    body = block(text, match.end() - 1)
    switch = re.search(r'switch\s*\([^\n{]*\)\s*\{', body)
    if not switch:
        raise ValueError('no switch in ' + function)
    labels = top_level(block(body, switch.end() - 1))
    return set(re.findall(r'\bcase\s+CardId::(\w+)\s*:', labels))


def array(text, name):
    match = re.search(r'\b' + name + r'\s*(?:\[[^\]]*\])+\s*=?\s*\{(.*?)\};', text, re.S)
    if not match:
        raise ValueError('array not found: ' + name)
    return [x.strip() for x in match.group(1).split(',') if x.strip()]


def enum_names(text, name):
    body = re.search(r'enum class ' + name + r'[^{]*\{(.*?)\};', text, re.S).group(1)
    return [re.sub(r'=.*', '', x).strip() for x in body.split(',') if x.strip()]


def strings(text, name):
    return re.findall(r'"((?:[^"\\]|\\.)*)"', ','.join(array(text, name)))


def derive():
    cards_h = read(ENGINE + '/include/constants/Cards.h')
    cards = enum_names(cards_h, 'CardId')
    colors = [x.split('::')[1] for x in array(cards_h, 'cardColors')]
    rarities = [x.split('::')[1] for x in array(cards_h, 'cardRarities')]
    types = [x.split('::')[1] for x in array(cards_h, 'cardTypes')]
    java = array(cards_h, 'cardStringIds')
    java = [re.sub(r'^"|"$', '', x) for x in java]
    assert len(cards) == len(colors) == len(rarities) == len(types) == len(java), 'card tables disagree'
    index = {name: i for i, name in enumerate(cards)}

    # Implemented cards, with the function that runs them.
    battle = read(ENGINE + '/src/combat/BattleContext.cpp')
    by_type = {t: switch_cases(battle, f) for t, f in TYPE_FUNCTIONS.items()}
    # `marker=` lines only locate the insertion point; the other case labels in that script are injected code.
    injected = {m for line in (ROOT / 'overlay/build_native.py').read_text(encoding='utf-8').splitlines()
                if not line.lstrip().startswith('marker=') for m in re.findall(r'\bcase\s+CardId::(\w+)\s*:', line)}
    for name in injected:  # build_native.py puts its cases into the skill switch
        by_type['SKILL'].add(name)
    anywhere = set()
    for directory in sorted((ROOT / ENGINE / 'src/combat/characters').glob('*.cpp')):
        text = strip_comments(directory.read_text(encoding='utf-8'))
        for function in CHARACTER_FUNCTIONS:
            if re.search(r'\b' + function + r'\s*\([^)]*\)\s*\{', text):  # a definition, not a call
                anywhere |= switch_cases(text, function)

    def implemented(name):
        return name in anywhere or name in by_type.get(types[index[name]], ())

    for name in set().union(*by_type.values(), anywhere):
        assert name in index, 'case label is not a CardId: ' + name

    # Pools.
    pools = read(ENGINE + '/include/constants/CardPools.h')
    blob = [x.split('::')[1] for x in array(pools[pools.index('namespace RarityCardPool'):], 'cardBlob')]
    offsets = [[int(v) for v in row.split(',') if v.strip()] for row in
               re.findall(r'\{([\d,\s]+)\}', re.search(r'groupOffset\[4\]\[3\]\s*\{(.*?)\};', pools, re.S).group(1))]
    sizes = [[int(v) for v in row.split(',') if v.strip()] for row in
             re.findall(r'\{([\d,\s]+)\}', re.search(r'groupSize\[4\]\[3\]\s*\{(.*?)\};', pools, re.S).group(1))]
    reward = {c: [] for c in CHARACTERS}
    for color, character in enumerate(CHARACTERS):
        for rarity in range(3):
            reward[character] += blob[offsets[color][rarity]:offsets[color][rarity] + sizes[color][rarity]]
    colorless_text = pools[pools.index('namespace ColorlessRarityCardPool'):]
    colorless_blob = [x.split('::')[1] for x in array(colorless_text, 'colorlessCardBlob')]
    colorless_sizes = [int(x) for x in array(colorless_text, 'colorlessGroupSize')]
    colorless = colorless_blob[:sum(colorless_sizes)]

    # Starter decks from GameContext::initPlayer.
    game = read(ENGINE + '/src/game/GameContext.cpp')
    init = block(game, re.search(r'GameContext::initPlayer\(\)\s*\{', game).end() - 1)
    starter, starter_relic = {}, {}
    for character, body in re.findall(r'case CharacterClass::(\w+):(.*?)break;', init, re.S):
        key = 'THE_SILENT' if character == 'SILENT' else character
        starter[key] = list(dict.fromkeys(re.findall(r'deck\.obtain\(\*this,\s*CardId::(\w+)', body)))
        starter_relic[key] = re.findall(r'obtainRelic\(RelicId::(\w+)\)', body)

    for character, names in GENERATED.items():
        for name in names:
            assert rarities[index[name]] == 'SPECIAL', f'{name} is not a SPECIAL card'

    # Class relics: starter plus relics that only this character's pools contain.
    relics_h = read(ENGINE + '/include/constants/Relics.h')
    relics = enum_names(relics_h, 'RelicId')
    relic_java = [re.sub(r'^"|"$', '', x) for x in array(relics_h, 'relicIds')]
    assert len(relics) - 1 <= len(relic_java) <= len(relics), 'relic tables disagree'
    battle_relics = set(relics[:relics.index('ASTROLABE')])  # the header's "out of battle" section starts here
    relic_pools = read(ENGINE + '/include/constants/RelicPools.h')
    namespaces = ['Ironclad', 'Silent', 'Defect', 'Watcher']
    pool = {}
    for character, namespace in zip(CHARACTERS, namespaces):
        start = relic_pools.index('namespace ' + namespace)
        body = block(relic_pools, relic_pools.index('{', start))
        pool[character] = set(re.findall(r'RelicId::(\w+)', body)) | set(starter_relic[character])
    hooks = set()
    for path in sorted((ROOT / ENGINE).glob('src/combat/**/*.cpp')) + sorted((ROOT / ENGINE).glob('include/combat/**/*.h')):
        hooks |= set(re.findall(r'\b(?:R|RelicId)::(\w+)', strip_comments(path.read_text(encoding='utf-8'))))
    class_relics = {c: sorted(pool[c] - set().union(*(pool[o] for o in CHARACTERS if o != c))
                              | set(starter_relic[c])) for c in CHARACTERS}

    def section(names, check=implemented):
        names = list(dict.fromkeys(names))
        missing = sorted(n for n in names if not check(n))
        return {'total': len(names), 'implemented': len(names) - len(missing), 'missing': missing}

    result = {'schema': 'sts1-coverage/1', 'generated_by': 'tools/coverage_manifest.py', 'characters': {}}
    for character in CHARACTERS:
        relic_names = [r for r in class_relics[character] if r in battle_relics]
        result['characters'][character] = {
            'reward_pool': section(reward[character]),
            'starter': section(starter[character]),
            'generated': section(GENERATED[character]),
            'class_relics': section(relic_names, lambda r: r in hooks),
        }
    result['colorless'] = {'reward_pool': section(colorless)}
    cards_missing = sorted({n for c in result['characters'].values() for s in (c['reward_pool'], c['starter'], c['generated'])
                            for n in s['missing']} | set(result['colorless']['reward_pool']['missing']))
    relics_missing = sorted({n for c in result['characters'].values() for n in c['class_relics']['missing']})
    # Java ids as the game reports them, for the backend's pre-search refusal.
    result['missing_cards'] = {java[index[n]]: n for n in cards_missing}
    result['missing_relics'] = {relic_java[relics.index(n)]: n for n in relics_missing}
    return result


def supported_characters():
    for node in ast.parse((ROOT / 'overlay/backend.py').read_text(encoding='utf-8')).body:
        if isinstance(node, ast.Assign) and any(getattr(t, 'id', None) == 'SUPPORTED_CLASSES' for t in node.targets):
            return list(ast.literal_eval(node.value))
    raise ValueError('backend.py has no SUPPORTED_CLASSES')


def render(manifest):
    return json.dumps(manifest, indent=2, ensure_ascii=False) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--check', action='store_true', help='fail if the committed manifest is stale or a supported character has gaps')
    check = parser.parse_args().check
    manifest = derive()
    path = ROOT / MANIFEST
    if not check:
        path.write_text(render(manifest), encoding='utf-8', newline='\n')
        print('wrote', MANIFEST)
        return
    committed = path.read_text(encoding='utf-8').replace('\r\n', '\n') if path.exists() else None
    if committed != render(manifest):
        sys.exit(f'FAIL: {MANIFEST} is stale; run python tools/coverage_manifest.py and commit the result')
    gaps = []
    for character in supported_characters():
        for name, section in manifest['characters'][character].items():
            gaps += [f'{character} {name}: {item}' for item in section['missing']]
    gaps += [f'colorless: {item}' for item in manifest['colorless']['reward_pool']['missing']]
    if gaps:
        sys.exit('FAIL: a supported character has gaps:\n  ' + '\n  '.join(gaps))
    print('PASS: manifest is current;', ', '.join(supported_characters()), 'has no gaps')


if __name__ == '__main__':
    main()
