"""Explicit runtime boundary shared by platform packagers and worker reloads."""

# Collection consumers can ship the search core without the Mod protocol or policy.
SEARCH_FILES = ('solver_search.py', 'plan_reduction.py', 'recovery.py')
MOD_FILES = ('backend.py', 'auto_policy.py', 'diagnostics.py', 'coverage_manifest.json',
             'search_status.py', 'worker_policy.py', 'resources/search_contract.json', 'deep_gate.py',
             # pi_base v2.8 executor tiers: per-act/room budgets, move prior, elite deep gate
             'headless_tiers.json', 'elite_deep_gate.json', 'nn/scorer-v2.bin')
OVERLAY_FILES = SEARCH_FILES + MOD_FILES + ('runtime_files.py',)

REPO = 'candidates/sts-ironclad-agent'
BRIDGE_FILES = (
    *(f'{REPO}/steam/{name}' for name in
      ('live_search.py', 'selection_import.py', 'rng_contract.py', 'steam_mcts.py')),
    *(f'{REPO}/sim_patch/parity/{name}' for name in ('adapter.py', 'core.py', '__init__.py')),
    *(f'{REPO}/sim_patch/alignment/tests/{name}' for name in ('compare_cards.py', 'compare_powers.py')),
)
PYTHON_FILES = tuple('overlay/' + name for name in OVERLAY_FILES) + BRIDGE_FILES
