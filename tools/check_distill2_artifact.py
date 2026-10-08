"""Check the platform-independent frozen model shipped inside the solver JAR."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
resources = root / 'overlay/resources/sts1solver'
weights = (resources / 'distill2-weights.bin').read_bytes()
schema = json.loads((resources / 'distill2-schema.json').read_text(encoding='utf-8'))
assert schema['schema_id'] == '31c15b49f3131c57cf59a427a798e1274f558c52a22fce68618676459bf05f34'
assert schema['source_sha256'] == '90b937349181b165a1d3281f03481e92e221c915869627ef6e9fa9e4e094fa2b'
assert weights[:8] == b'D2MLP001' and len(weights) == 8 + 4 * (384 * 11195 + 384 + 384 + 1)
assert hashlib.sha256(weights).hexdigest() == '0549b3a86ec87ada40cd656ca1300ccb17057c8980c43ce13480f9caede5dbaf'
print('PASS: frozen distill2 artifact, schema and weights')
