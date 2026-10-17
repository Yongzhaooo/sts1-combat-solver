"""Transport deadlines; the Java client packages the same contract resource."""
import json
from pathlib import Path

CONTRACT = json.loads((Path(__file__).parent / 'resources/search_contract.json').read_text(encoding='utf-8'))


def search_timeout(budget):
    return CONTRACT['deep_timeout_seconds' if budget >= 128000 else 'standard_timeout_seconds']
