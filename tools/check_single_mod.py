"""Verify the integrated JAR has one mod identity and one initialization entry."""
import json
from pathlib import Path
import sys
import zipfile

with zipfile.ZipFile(Path(sys.argv[1])) as jar:
    names = jar.namelist()
    assert names.count('ModTheSpire.json') == 1
    metadata = json.loads(jar.read('ModTheSpire.json'))
    assert metadata['modid'] == 'sts1solver'
    assert metadata['dependencies'] == ['basemod']
    for name in ('communicationmod/CommunicationMod.class',
                 'steamstateexport/CombatStatePatch.class',
                 'sts1solver/SolverMod.class', 'licenses/CommunicationMod.txt',
                 'licenses/sts-ironclad-agent.txt',
                 'sts1solver/distill2-schema.json',
                 'sts1solver/distill2-weights.bin'):
        assert name in names, name
    entries = [name for name in names if name.endswith('.class') and
               b'Lcom/evacipated/cardcrawl/modthespire/lib/SpireInitializer;' in jar.read(name)]
    assert entries == ['sts1solver/SolverMod.class'], entries
    assert not any('SmokeProbe' in name for name in names)
print('PASS: one solver mod, one initializer, embedded bridges and licenses, BaseMod-only dependency')
