#!/usr/bin/env bash
# Seeds the empty eval workspace with the golden fixture as a git repository.
# --scan also builds the atlas, for cases that test querying rather than scanning.
# The answer files (expected/, SEEDED.md) are removed so the model under test never sees them.
set -euo pipefail
plugin_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cp -R "$plugin_root/fixtures/spring-golden/." .
rm -rf expected SEEDED.md
git init -q
git -c core.autocrlf=false add -A
git -c core.autocrlf=false -c user.email=eval@example.invalid -c user.name=eval commit -qm "golden fixture"
if [[ "${1:-}" == "--scan" ]]; then
  java "$plugin_root/scanner/Launch.java" scan > /dev/null
fi
