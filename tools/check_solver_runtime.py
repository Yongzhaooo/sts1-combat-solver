"""Check the exported backend's real imports and engine identity in a fresh process."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'overlay'))
from backend import Advisor

advisor = Advisor()
assert advisor.search.runtime == (root / 'candidates/sts-ironclad-agent/runtime').resolve()
assert Path(advisor.native_search.__file__).resolve().is_relative_to(root / 'overlay/build/native')
print('PASS: exported advisor imports its own engine and native extension')
