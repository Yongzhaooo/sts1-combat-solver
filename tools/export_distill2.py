"""Export the frozen out-of-combat student to a JDK-only runtime format.

This is a release-time tool. Players never need PyTorch, NumPy, or Python for
student inference; the existing bundled Python remains for combat search.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys

import numpy as np
import torch


SOURCE_SHA256 = '90b937349181b165a1d3281f03481e92e221c915869627ef6e9fa9e4e094fa2b'
SCHEMA_ID = '31c15b49f3131c57cf59a427a798e1274f558c52a22fce68618676459bf05f34'
WEIGHT_NAMES = ('net.0.weight', 'net.0.bias', 'net.2.weight', 'net.2.bias')


def enum_ids(header: Path, enum: str) -> dict[str, int]:
    text = header.read_text(encoding='utf-8')
    body = re.search(r'enum class\s+' + enum + r'\s*(?::[^\{]+)?\{([^}]*)\}', text, re.S)
    if body is None:
        raise ValueError('Missing simulator enum ' + enum)
    body = re.sub(r'//[^\n]*', '', body.group(1))
    names = [part.strip().split('=')[0].strip() for part in body.split(',') if part.strip()]
    return {name: index for index, name in enumerate(names)}


def export(source: Path, output: Path) -> None:
    if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError('Frozen distill2 checkpoint SHA-256 mismatch')
    checkpoint = torch.load(source, map_location='cpu', weights_only=False)
    schema = checkpoint['schema']
    if checkpoint['format'] != 'distill2-v1' or schema['schema_id'] != SCHEMA_ID:
        raise ValueError('Unexpected distill2 model or feature schema')
    state = checkpoint['state']
    width = checkpoint['width']
    assert width == 384 and tuple(state['net.0.weight'].shape) == (384, 11195)
    assert tuple(state['net.2.weight'].shape) == (1, 384)
    metadata = {key: schema[key] for key in (
        'schema_id', 'OBS_DIM', 'BASE_OBS_DIM', 'DESC_DIM', 'CARD_CAP',
        'deck_offset', 'W_ACTION', 'W_CARD', 'OFF_CARD', 'OFF_CARD_UPGRADE',
        'base_dim', 'context_dim', 'special_scale', 'scalar_indices',
        'card_types', 'nonstarter_attacks', 'offsets')}
    metadata['width'] = width
    metadata['source_sha256'] = SOURCE_SHA256
    constants = Path(__file__).resolve().parents[1] / (
        'candidates/sts-ironclad-agent/combat_engine/combat4r/engine-source/include/constants')
    metadata['ids'] = {
        'cards': enum_ids(constants / 'Cards.h', 'CardId'),
        'relics': enum_ids(constants / 'Relics.h', 'RelicId'),
        'potions': enum_ids(constants / 'Potions.h', 'Potion'),
        'events': enum_ids(constants / 'Events.h', 'Event'),
    }
    if [len(metadata['ids'][kind]) for kind in ('cards', 'relics', 'potions')] != [371, 181, 44]:
        raise ValueError('Simulator IDs drifted from the frozen model')
    output.mkdir(parents=True, exist_ok=True)
    (output / 'distill2-schema.json').write_text(
        json.dumps(metadata, separators=(',', ':'), ensure_ascii=False), encoding='utf-8')
    with (output / 'distill2-weights.bin').open('wb') as dest:
        dest.write(b'D2MLP001')
        for key in WEIGHT_NAMES:
            values = state[key].detach().cpu().contiguous().view(-1).tolist()
            dest.write(struct.pack('>' + 'f' * len(values), *values))
    sys.path.insert(0, str(source.parent.parent))
    from distill2_model import Student
    student = Student(schema, width)
    student.load_state_dict(state)
    student.eval()
    observation = np.zeros(schema['OBS_DIM'], dtype=np.float32)
    observation[[0, 1, 2, 4, 8, 32, 33, 34, schema['BASE_OBS_DIM']+5]] = (
        .71, .8, .26, .5, .2, 1, .3, .4, 1)
    observation[schema['deck_offset']+2] = .15
    observation[schema['deck_offset']+3] = .05
    observation[schema['deck_offset']+14] = .2
    extra = np.asarray([.71, .21, .4, .4, .2, .5, .2, .4, .6, .5,
                        .5, .1, .25, .45, .05, 0, .1, 1, 0, 0], dtype=np.float32)
    descriptors = np.zeros((3, schema['DESC_DIM']), dtype=np.float32)
    offsets = schema['offsets']
    for i, (kind, card) in enumerate(((3, 1), (3, 7), (10, -1))):
        descriptors[i, kind] = 1
        descriptors[i, offsets['OFF_SCREEN']+4] = 1
        descriptors[i, offsets['OFF_RAW_INDEX']] = i / 32
        if card >= 0:
            descriptors[i, offsets['OFF_CARD']+card] = 1
            descriptors[i, offsets['OFF_CARD_UPGRADE']] = i / 1000
        else:
            descriptors[i, offsets['OFF_PASS']] = 1
    routes = np.zeros((3, 26), dtype=np.float32)
    routes[0, :3] = (.5, 1, .25)
    routes[1, 4] = .4
    packet = {'observation': observation, 'extra': extra,
              'descriptors': descriptors, 'routes': routes,
              'context': np.concatenate((descriptors.mean(axis=0, dtype=np.float32),
                                         descriptors.max(axis=0))), 'bits': [1, 2, 3]}
    with torch.inference_mode():
        scores = student(*student.tensors(packet)).tolist()
    sparse = lambda row: [[int(i), float(row[i])] for i in np.flatnonzero(row)]
    fixture = {'observation': sparse(observation), 'extra': extra.tolist(),
               'descriptors': [sparse(row) for row in descriptors],
               'routes': [sparse(row) for row in routes], 'scores': scores, 'best': int(np.argmax(scores))}
    (output.parents[1] / 'test/distill2-reference.json').write_text(
        json.dumps(fixture, separators=(',', ':')), encoding='utf-8')
    print(output / 'distill2-weights.bin')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--output', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'overlay/resources/sts1solver')
    args = parser.parse_args()
    export(args.source, args.output)
