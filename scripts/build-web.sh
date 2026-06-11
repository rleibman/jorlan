#!/usr/bin/env bash
# Build the Jorlan web frontend.
# NOTE: stLib publishLocal is deliberately NOT included here — it takes several
# minutes and must be run manually by the developer when stLib sources change.
# Run from the repository root.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "Building web frontend (full-opt)..."
sbtn --error "web/dist"
echo "Web bundle written to dist/"
