# Evals

There are two layers of checks.

## Scanner golden test

This test is deterministic and needs no model and no network:

```bash
mvn -o -q -f scanner/pom.xml package
java -cp scanner/dist/resilience-atlas.jar scanner/src/test/java/studio/volkoff/atlas/GoldenTest.java fixtures/spring-golden
```

It scans `fixtures/spring-golden` and compares the result with the hand-written files in
`fixtures/spring-golden/expected/`:

- `entrypoints.tsv`, the entry points;
- `webhooks.tsv`, the webhook verdict and confidence of every tagged HTTP record;
- `not-entrypoints.txt`, symbols that must not appear;
- `signals.tsv`, effects that must or must not be found on an operation's path, with `@tx`
  for "inside a transaction";
- `risk.tsv`, the static attention level and the rule codes that must or must not fire.

The command exits 1 on any difference. Each difference is either a scanner bug or a wrong expectation; decide
which against `schemas/entrypoint-rules.md`.

## Plugin evals

These cases run the skills through `claude plugin eval`. Each case seeds an empty workspace
from the golden fixture through `_shared/seed-golden.sh`, which removes the answer files, and
grades:

- whether the skill fired;
- whether the atlas jar was used instead of grepping the tree;
- what was produced.

```bash
claude plugin eval . --scaffold --allow-tools Bash Write --trust-plugin --no-publish
```

Granting `Bash` requires Claude Code's OS sandbox, which native Windows lacks. Run the suite
under WSL2, Linux or macOS, with a JDK 17+ on `PATH` and the jar built first.

Cases:

| Case | Checks |
|---|---|
| `atlas-bootstrap-builds-atlas` | the atlas comes from `scan`, counts and warnings reach the user |
| `entrypoint-catalog-sqs-consumers` | SQS consumers come from atlas queries, programmatic polling included, with no tree grep |
| `critical-flow-tracer-billing-webhook` | a flow file is written and every evidence line verifies |
| `report-builds-page` | the page comes from the jar's `report` command, the Stripe webhook ranks first, and levels are not called verdicts |

## Smoke test on Windows

`claude plugin eval` with `Bash` needs WSL2, so on native Windows a single skill is checked
with `claude -p` in a seeded scratch directory instead:

```bash
mkdir /tmp/golden && cd /tmp/golden && bash /path/to/spring-resilience-atlas/evals/_shared/seed-golden.sh --scan
claude -p "/resilience-audit:critical-flow-tracer the billing webhook" --plugin-dir <plugin> \
  --allowedTools "Skill Read Glob Grep Agent" --output-format stream-json --verbose < /dev/null
```

`--allowedTools` deliberately leaves out `Bash`. That proves the skills' own `allowed-tools`
are enough for the jar. The first run on 2026-10-22 got 12/12 evidence verified, opened 5 of
12 files, and cost $0.51 on Opus.

## Cost

```bash
evals/measure-cost.sh /path/to/snapshot "POST /stripe/webhook"
```

The script runs each skill through `claude -p` on a snapshot, plus the same catalog question
without the plugin. It prints tokens per model, list-price USD, turns and seconds. On a
subscription the USD column is the API-price equivalent of what a run draws from the plan's
limits.

Results on a Spring Boot 3 application from the local corpus, 2026-10-22 (1.4k Java files):

| Run | Model | USD | Turns | Seconds |
|---|---|---|---|---|
| catalog question without the plugin (grep) | Opus 5.5 | 0.58 | 8 | 36 |
| `atlas-bootstrap` | Haiku 5.5 | 0.008 | 2 | 7 |
| `entrypoint-catalog`, same question | Haiku 5.5 | 0.016 | 3 | 27 |
| `domain-cartographer` | Sonnet 5.5 | 0.32 | 13 | 47 |
| `critical-flow-tracer`, Stripe webhook, 33/33 evidence verified | Opus 5.5 | 0.99 | — | 190 |

About 34k tokens of every run are the session's base context (system prompt, the user's
installed plugins, the project's CLAUDE.md). The plugin itself adds about 300 tokens of skill
descriptions to every session.

## Local corpus

The local work projects listed in `corpus.md` are evaluated by hand for now, against
snapshots at pinned commits. The list is git-ignored, and their results never enter this
repository.
