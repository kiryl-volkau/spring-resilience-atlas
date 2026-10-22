#!/usr/bin/env bash
# Measures what each skill costs on a real repository: tokens per model, list-price USD,
# turns and wall time, plus the same catalog question asked without the plugin.
#
#   evals/measure-cost.sh <repo-snapshot> "<flow start for the tracer, e.g. POST /stripe/webhook>"
#   SKIP_BASELINE=1 skips the no-plugin run when it was already measured.
#
# Run it on a snapshot (git clone), never on a working tree: the skills write .reliability/.
# Results go to <repo-snapshot>.cost/ next to the snapshot.
# On a subscription the USD figures are the API-price equivalent of what the run draws from
# the plan's usage limits, not a charge.
set -uo pipefail
export MSYS_NO_PATHCONV=1
# Native Windows programs (claude.exe, java.exe) need C:/... paths, not Git Bash's /c/...
winpath() { (cd "$1" && (pwd -W 2>/dev/null || pwd)); }
plugin="$(winpath "$(dirname "$0")/..")"
repo="$(winpath "$1")"
flow="${2:-}"
out="$repo.cost"
mkdir -p "$out"

run() {
  local name="$1" tools="$2" prompt="$3"
  shift 3
  echo "running $name" >&2
  (cd "$repo" && claude -p "$prompt" --allowedTools "$tools" --output-format json "$@" \
    < /dev/null > "$out/$name.json" 2> "$out/$name.err")
}

question="Which scheduled jobs and message or event consumers does this application have, grouped by module? Answer in at most 25 lines."

# The baseline runs first, before any atlas exists that it could find and read.
if [[ -z "${SKIP_BASELINE:-}" ]]; then
  run 1-baseline-no-plugin "Read Glob Grep" "$question"
fi
run 2-atlas-bootstrap "Skill" "/resilience-audit:atlas-bootstrap" --plugin-dir "$plugin"
if [[ ! -f "$repo/.reliability/atlas/manifest.json" ]]; then
  echo "the plugin did not load or the scan failed; see $out/2-atlas-bootstrap.json" >&2
  exit 1
fi
run 3-entrypoint-catalog "Skill" "/resilience-audit:entrypoint-catalog $question" --plugin-dir "$plugin"
run 4-domain-cartographer "Skill Write" "/resilience-audit:domain-cartographer" --plugin-dir "$plugin"
if [[ -n "$flow" ]]; then
  run 5-critical-flow-tracer "Skill Read Glob Grep Agent" "/resilience-audit:critical-flow-tracer $flow" --plugin-dir "$plugin"
fi

java -cp "$plugin/scanner/dist/resilience-atlas.jar" "$plugin/evals/CostReport.java" "$out"
