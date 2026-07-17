#!/usr/bin/env bash
# Launch AerialPod from its project venv (created from the miniforge python3 —
# that's fine; main.py preloads the matching libssl before Qt needs TLS).
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -x .venv/bin/aerialpod ]]; then
    echo "venv missing — creating it..."
    python3 -m venv .venv
    .venv/bin/pip install -e '.[dev]'
fi

exec .venv/bin/aerialpod "$@"
