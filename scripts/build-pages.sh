#!/usr/bin/env bash
#
# Render PRIVACY.md into docs/ for GitHub Pages.
#
# The Markdown is the single source of truth — a privacy policy that exists in
# two places will eventually say two different things, and the published one is
# what you are held to.
#
#   scripts/build-pages.sh            # render, refusing while placeholders remain
#   scripts/build-pages.sh --force    # render anyway, to preview the styling
#
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1
[ "${1:-}" = "--help" ] && { sed -n '3,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

python3 "$REPO/scripts/render_pages.py" "$REPO" "$FORCE"
