#!/usr/bin/env bash
# Seeds the empty eval workspace with the golden fixture as a git repository.
# --scan also builds the atlas, for cases that test querying rather than scanning.
# --audit-sample also restores a recorded quick check (stored rows and plan), for cases that read verdicts.
# --golden-flow also records the hand-traced billing webhook flow, for cases that audit a flow without tracing it.
# The answer files (expected/, SEEDED.md) are removed so the model under test never sees them.
set -euo pipefail
plugin_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# The golden fixture lives beside the plugin in the repository and does not ship with it.
cp -R "$plugin_root/../fixtures/spring-golden/." .
scan=false
flow=false
for arg in "$@"; do
  case "$arg" in
    --scan) scan=true ;;
    --audit-sample)
      mkdir -p .reliability/audit
      cp -R expected/audit/places-sample/. .reliability/audit/
      scan=true
      ;;
    --golden-flow)
      mkdir -p .reliability/flows/drafts
      cp expected/flows/FLOW-billing-stripe-webhook.json .reliability/flows/drafts/
      scan=true
      flow=true
      ;;
  esac
done
rm -rf expected SEEDED.md
git init -q
printf '.reliability/\n' > .git/info/exclude
git -c core.autocrlf=false add -A
git -c core.autocrlf=false -c user.email=eval@example.invalid -c user.name=eval commit -qm "golden fixture"
if [[ "$scan" == true ]]; then
  java "$plugin_root/scanner/Launch.java" scan > /dev/null
fi
if [[ "$flow" == true ]]; then
  java "$plugin_root/scanner/Launch.java" audit record-flow FLOW-billing-stripe-webhook > /dev/null
fi
