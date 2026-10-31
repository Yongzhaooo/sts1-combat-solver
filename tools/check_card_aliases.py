"""Check that the three card-ID mapping files agree for every Silent card.

A game card ID is not always an engine enum name: the Silent's `Strike_G` is `STRIKE_GREEN`,
and a card that was renamed keeps its old ID (`Venomology` is now Alchemize). The mapping
lives in three files that only stay in sync by hand - `steam_mcts.CARD_ALIASES`, the
`ALIASES` table of `Distill2.java`, and the published table `overlay/test/original-game-ids.json`
that the Distill2 check walks. A card missing from any of them dies at the boundary as an
unknown ID instead of being played, or is refused as unimplemented when it is only unmapped.

Reads sources and fixtures only; needs no game and no built runtime.
"""
import argparse
import ast
import gzip
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ENGINE = 'candidates/sts-ironclad-agent/combat_engine/combat4r/engine-source'
SCHEMA = 'overlay/resources/sts1solver/distill2-schema.json'
MANIFEST = 'overlay/coverage_manifest.json'
PUBLISHED = 'overlay/test/original-game-ids.json'
STEAM = 'candidates/sts-ironclad-agent/steam/steam_mcts.py'
DISTILL = 'overlay/src/sts1solver/Distill2.java'
SILENT_FIXTURES = 'fixtures/native/THE_SILENT'


def strip_comments(text):
    """C++ and Java comments out, string and character literals kept."""
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


def read(root, path):
    return strip_comments((root / path).read_text(encoding='utf-8'))


def block(text, open_index):
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
        return set()
    body = block(text, match.end() - 1)
    switch = re.search(r'switch\s*\([^\n{]*\)\s*\{', body)
    if not switch:
        return set()
    return set(re.findall(r'\bcase\s+CardId::(\w+)\s*:', top_level(block(body, switch.end() - 1))))


def array(text, name):
    match = re.search(r'\b' + name + r'\s*(?:\[[^\]]*\])+\s*=?\s*\{(.*?)\};', text, re.S)
    if not match:
        raise ValueError('array not found: ' + name)
    return [x.strip() for x in match.group(1).split(',') if x.strip()]


def card_tables(root):
    """The engine's card tables: enum names and the game card IDs beside them."""
    text = read(root, ENGINE + '/include/constants/Cards.h')
    body = re.search(r'enum class CardId[^{]*\{(.*?)\};', text, re.S).group(1)
    enums = [re.sub(r'=.*', '', x).strip() for x in body.split(',') if x.strip()]
    java = re.findall(r'"([^"]*)"', ','.join(array(text, 'cardStringIds')))
    if len(enums) != len(java):
        raise ValueError('card tables disagree: %d enum names, %d game IDs' % (len(enums), len(java)))
    return dict(zip(java, enums)), dict(zip(enums, java))

def silent_pool(root, enum_to_java):
    """Every card the Silent can have, as game card IDs: reward pool, starter deck, generated cards."""
    pools = read(root, ENGINE + '/include/constants/CardPools.h')
    blob = [x.split('::')[1] for x in array(pools[pools.index('namespace RarityCardPool'):], 'cardBlob')]
    rows = re.search(r'groupOffset\[4\]\[3\]\s*\{(.*?)\};', pools, re.S).group(1)
    offsets = [[int(v) for v in row.split(',') if v.strip()] for row in re.findall(r'\{([\d,\s]+)\}', rows)]
    sizes = [[int(v) for v in row.split(',') if v.strip()] for row in
             re.findall(r'\{([\d,\s]+)\}', re.search(r'groupSize\[4\]\[3\]\s*\{(.*?)\};', pools, re.S).group(1))]
    names = set()
    for rarity in range(3):  # row 1 is THE_SILENT in the engine's character order
        names |= set(blob[offsets[1][rarity]:offsets[1][rarity] + sizes[1][rarity]])
    game = read(root, ENGINE + '/src/game/GameContext.cpp')
    init = block(game, re.search(r'GameContext::initPlayer\(\)\s*\{', game).end() - 1)
    for character, body in re.findall(r'case CharacterClass::(\w+):(.*?)break;', init, re.S):
        if character == 'SILENT':
            names |= set(re.findall(r'deck\.obtain\(\*this,\s*CardId::(\w+)', body))
    manifest = json.loads((root / MANIFEST).read_text(encoding='utf-8'))
    names |= set(manifest['characters']['THE_SILENT']['generated']['missing'])
    for path in sorted((root / ENGINE / 'src/combat/characters').glob('*.cpp')):
        names |= switch_cases(strip_comments(path.read_text(encoding='utf-8')), 'useSilentCard')
    unknown = sorted(name for name in names if name not in enum_to_java)
    if unknown:
        raise ValueError('Silent pool names a card the table does not have: ' + ', '.join(unknown))
    return {enum_to_java[name] for name in names}


def steam_aliases(root):
    """steam_mcts.CARD_ALIASES, read without importing the module (importing it loads the simulator)."""
    tree = ast.parse((root / STEAM).read_text(encoding='utf-8'))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(getattr(t, 'id', None) == 'CARD_ALIASES' for t in node.targets):
            return ast.literal_eval(node.value)
    raise ValueError('steam_mcts.py has no CARD_ALIASES')


def distill_aliases(root):
    """The cards entries of Distill2's ALIASES pairs."""
    found = re.findall(r'\{"cards",\s*"([^"]+)",\s*"([^"]+)"\}', (root / DISTILL).read_text(encoding='utf-8'))
    if not found:
        raise ValueError('Distill2.java has no card aliases')
    return dict(found)


def generic(java_id):
    """The camel-to-upper-snake rule both the importer and the Java model fall back to."""
    value = re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', str(java_id))
    value = value.replace('-', ' ').replace("'", '')
    return re.sub(r'[^A-Za-z0-9]+', '_', value).strip('_').upper()


def fixture_cards(root):
    """The card IDs the recorded Silent fixtures touch, in any pile of any checkpoint."""
    found = set()
    for path in sorted((root / SILENT_FIXTURES).glob('*.json.gz')):
        case = json.loads(gzip.decompress(path.read_bytes()))
        for checkpoint in case['checkpoints']:
            observation = checkpoint['observation']
            found |= {card['id'] for card in observation['deck']}
            found |= {card['id'] for pile in observation['piles'].values() for card in pile}
    return found


def problems(root):
    java_to_enum, enum_to_java = card_tables(root)
    schema = set(json.loads((root / SCHEMA).read_text(encoding='utf-8'))['ids']['cards'])
    published = set(json.loads((root / PUBLISHED).read_text(encoding='utf-8'))['cards'])
    steam, distill = steam_aliases(root), distill_aliases(root)
    manifest = json.loads((root / MANIFEST).read_text(encoding='utf-8'))
    pool = silent_pool(root, enum_to_java)
    fixtures = fixture_cards(root)

    found = []
    for java_id in sorted(pool):
        if java_id not in published:
            found.append('%s: a Silent card, but absent from the published %s' % (java_id, PUBLISHED))
    for java_id in sorted(fixtures - published):
        found.append('%s: touched by a recorded Silent fixture, but absent from the published %s'
                     % (java_id, PUBLISHED))

    for java_id in sorted(pool):
        want = java_to_enum[java_id]
        if want not in schema:
            found.append('%s: the engine calls it %s, but %s has no such card, so Distill2 would refuse it'
                         % (java_id, want, SCHEMA))
        if steam.get(java_id, generic(java_id)) != want:
            found.append('%s: steam_mcts.CARD_ALIASES resolves it to %s, the engine calls it %s'
                         % (java_id, steam.get(java_id, generic(java_id)), want))
        if distill.get(java_id, generic(java_id)) != want:
            found.append('%s: Distill2 ALIASES resolves it to %s, the engine calls it %s'
                         % (java_id, distill.get(java_id, generic(java_id)), want))

    for java_id in sorted(set(steam) & pool):
        if distill.get(java_id) != steam[java_id]:
            found.append('%s: steam_mcts says %s, Distill2 says %s'
                         % (java_id, steam[java_id], distill.get(java_id)))
    for java_id in sorted(set(distill) & pool):
        if steam.get(java_id) != distill[java_id]:
            found.append('%s: Distill2 says %s, steam_mcts says %s'
                         % (java_id, distill[java_id], steam.get(java_id)))

    missing_enums = set().union(*[manifest['characters']['THE_SILENT'][kind]['missing']
                                  for kind in ('reward_pool', 'starter', 'generated')])
    keys = set(manifest['missing_cards'])
    for java_id in sorted(pool):
        if java_to_enum[java_id] in missing_enums and java_id not in keys:
            found.append('%s: unimplemented, but not a key of coverage_manifest missing_cards, so the '
                         'backend would refuse it as an unknown ID instead of as unimplemented' % java_id)
    return found, {'pool': len(pool), 'fixtures': len(fixtures), 'published': len(published),
                   'steam': len(steam), 'distill': len(distill)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    counts = None
    found, counts = problems(parser.parse_args().root.resolve())
    if found:
        for line in found:
            print('FAIL: ' + line)
        sys.exit('%d card-ID mapping problems' % len(found))
    print('PASS: the {steam} steam_mcts aliases and the {distill} Distill2 aliases agree, all {pool} Silent '
          'cards resolve to their engine enum name, and all of them are in the published table of {published} '
          'IDs ({fixtures} of them touched by the recorded Silent fixtures)'.format(**counts))


if __name__ == '__main__':
    main()
