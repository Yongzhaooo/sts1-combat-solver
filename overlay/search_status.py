"""Transport deadlines; the Java client packages the same contract resource."""
import json
import os
from pathlib import Path

CONTRACT = json.loads((Path(__file__).parent / 'resources/search_contract.json').read_text(encoding='utf-8'))

# An interrupt drains in 0.03-0.2 s (measured on a 3-bottle fan-out at budgets 8000 and 128000); this leaves ~50x headroom for larger Boss states.
SOFT_MARGIN_SECONDS = 10


def search_timeout(budget):
    # STS_SEARCH_TIMEOUT_SECONDS shortens both deadlines for protocol checks.
    if os.environ.get('STS_SEARCH_TIMEOUT_SECONDS'):
        return float(os.environ['STS_SEARCH_TIMEOUT_SECONDS'])
    return CONTRACT['deep_timeout_seconds' if budget >= 128000 else 'standard_timeout_seconds']


def soft_deadline(limit):
    """Seconds after which a running solve is interrupted to return its best plan before the hard kill."""
    return limit - min(SOFT_MARGIN_SECONDS, limit / 2)
