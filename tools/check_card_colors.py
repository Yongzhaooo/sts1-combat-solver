"""Every class card's color in Cards.h matches the class it belongs to.

The class card pools in CardPools.h (cardBlob: red, green, blue, purple) and the four starter decks are
the reference. growth_gate.h and card transforms read these colors, so a mistagged Defect card silently
turned off the rescue beam's early stop (Compile Driver and Buffer were tagged Watcher).
"""
import re
from pathlib import Path

D = Path(__file__).resolve().parents[1] / 'candidates/sts-ironclad-agent/combat_engine/combat4r/engine-source/include/constants'
cards = (D / 'Cards.h').read_text(encoding='utf-8').split('\n')
names = re.findall(r'"([A-Z_0-9]+)"', next(l for l in cards if 'cardEnumStrings[]' in l))
colors = [x.strip().replace('CardColor::', '') for x in next(l for l in cards if 'cardColors[]' in l)
          .split('{', 1)[1].rsplit('}', 1)[0].split(',') if x.strip()]
assert len(names) == len(colors), (len(names), len(colors))
color = dict(zip(names, colors))
blob = re.findall(r'CardId::([A-Z_0-9]+)', next(l for l in (D / 'CardPools.h').read_text(encoding='utf-8').split('\n')
                                                 if 'static constexpr CardId cardBlob[] {' in l))
offsets = [0, 72, 143, 214, len(blob)]
expected = {name: cls for i, cls in enumerate(('RED', 'GREEN', 'BLUE', 'PURPLE')) for name in blob[offsets[i]:offsets[i + 1]]}
for cls, starters in {'RED': ('STRIKE_RED', 'DEFEND_RED', 'BASH'), 'GREEN': ('STRIKE_GREEN', 'DEFEND_GREEN', 'NEUTRALIZE', 'SURVIVOR'),
                      'BLUE': ('STRIKE_BLUE', 'DEFEND_BLUE', 'ZAP', 'DUALCAST'), 'PURPLE': ('STRIKE_PURPLE', 'DEFEND_PURPLE', 'ERUPTION', 'VIGILANCE')}.items():
    expected.update(dict.fromkeys(starters, cls))
wrong = [(name, color[name], cls) for name, cls in expected.items() if color[name] != cls]
assert not wrong, wrong
print('PASS: %d class cards carry their class color' % len(expected))
