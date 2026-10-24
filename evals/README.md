# Evals

There are two layers of checks.

## Scanner golden test

This test is deterministic and needs no model and no network:

```bash
mvn -o -q package
java -cp dist/resilience-atlas.jar evals/GoldenTest.java fixtures/spring-golden
```

It scans `fixtures/spring-golden` and compares the result with the hand-written files in
`fixtures/spring-golden/expected/`:

- `entrypoints.tsv`, the entry points;
- `webhooks.tsv`, the webhook verdict and confidence of every tagged HTTP record;
- `not-entrypoints.txt`, symbols that must not appear;
- `signals.tsv`, effects that must or must not be found on an operation's path, with `@tx`
  for "inside a transaction";
- `risk.tsv`, the static attention level and the rule codes that must or must not fire,
  module rules from the fixture's `application.yml` and migrations included.

The command exits 1 on any difference. Each difference is either a scanner bug or a wrong expectation; decide
which against `plugin/schemas/entrypoint-rules.md`.

## Plugin evals

These cases run the skills through `claude plugin eval`, which reads cases only from inside
the plugin, so they live in `plugin/evals/`. Each case seeds an empty workspace from the golden
fixture through `plugin/evals/_shared/seed-golden.sh`, which removes the answer files, and
grades:

- whether the skill fired;
- whether the scanner was used instead of grepping the tree;
- what was produced.

```bash
claude plugin eval plugin --scaffold --allow-tools Bash Write --trust-plugin --no-publish
```

Granting `Bash` requires Claude Code's OS sandbox, which native Windows lacks. Run the suite
under WSL2, Linux or macOS, with a JDK 17+ on `PATH`.

Cases:

| Case | Checks |
|---|---|
| `atlas-bootstrap-builds-atlas` | the atlas comes from `scan`, counts and warnings reach the user |
| `entrypoint-catalog-sqs-consumers` | SQS consumers come from atlas queries, programmatic polling included, with no tree grep |
| `critical-flow-tracer-billing-webhook` | a flow file is written and every evidence line verifies |
| `report-builds-page` | the page comes from the scanner's `report` command, the Stripe webhook ranks first, and levels are not called verdicts |
| `audit-plans-without-spending` | asked to check the hints, the `audit` skill only plans: it prints the cost and the `--yes` command and starts no agent |
| `audit-billing-places` | one message plans and runs the quick check of the billing package under a limit; the seeded defects come out CONFIRMED, the PayPal false positive REFUTED or MITIGATED, and no file under `src/` changes |
| `audit-everything-golden` | the same for every place; the ledger says COMPLETE and names what was not covered |
| `report-shows-verdicts` | with a recorded quick check restored, the report's reply keeps suspected apart from verified |
| `audit-deep-stripe-webhook` | with the golden flow recorded (`seed-golden.sh --golden-flow`), one message plans and runs the deep audit of that flow under a limit: the scenario author and the verifier run, `record-step` stores both steps, the reconciliation loss is a VERIFIED finding, the double charge after a failed commit is design-level, the flow page carries the scenarios, and no file under `src/` changes |

`--tag layer2` runs the four quick-check cases. Their seeds restore `expected/audit/places-sample/`
or build the atlas. The verdicts each seed should produce are in
`fixtures/spring-golden/expected/audit/verdicts-expected.tsv`.

The golden test also runs the quick check's mechanical half without a model. The hypothesis
catalogue must cover every rule. `audit plan`, `next` and `record` must handle
`expected/audit/draft-ok.jsonl`, `draft-bad.jsonl` (both with a BOM and CRLF) and a missing
draft as `audit/AuditStore` and `audit/Record` define; a MITIGATED row whose mitigation is only
PARTIAL and that names a remaining failure must be stored as CONFIRMED. The full plan's size and
estimate must match `expected/audit/plan.tsv`. `expected/places.tsv` pins every place, LOW and
`no-hint` ones included, with rules about a transaction keyed by the method that opens it.

The deep audit's mechanical half runs without a model too, from `expected/audit/deep/`: the
concepts the scanner matches on the golden flow (`concepts.txt`), a scenario draft with faults
and one without (`scenarios-bad.jsonl`, `scenarios-ok.jsonl`, the record output pinned in the
`.txt` beside each), a verification draft (`verification.jsonl`, `.txt`), and the findings,
place rows and operation verdicts they leave (`derived.tsv`). `expected/flows/history/` pins the
snapshot writer (`A.json`, times and commit fixed) and `flow diff` from A to two hand-edited
states (`diff.txt`): one where the reconciliation finding was fixed and one where only its code
changed. `GOLDEN_WRITE=1` rewrites these pins after a change that moves them on purpose; review
the diff before committing.

## Costs measured

| Run | Models | Cost |
|---|---|---|
| Quick check, 40 golden places | Haiku | $0.10 |
| Trace of the billing webhook flow | Opus | $0.51–1.02 |
| Deep audit of the traced flow: scenarios and verification | Fable author, Opus verifier and orchestrator | $3.58 (Fable $2.72) |
| Verification alone, 4 scenarios | Opus | $0.53 |

A headless `claude -p` run ignores a skill's `model:` line, so the orchestrating turns run on the
session's model. Pass `--model haiku` to measure what an interactive run costs; the deep audit's
gate prices the orchestrator on Haiku.

## Smoke test on Windows

`claude plugin eval` with `Bash` needs WSL2, so on native Windows a single skill is checked
with `claude -p` instead. Seed an empty scratch directory with `plugin/evals/_shared/seed-golden.sh`,
whose `--scan` flag also builds the atlas, then run the skill from that directory:

```bash
claude -p "/resilience-audit:critical-flow-tracer the billing webhook" --plugin-dir /path/to/spring-resilience-atlas/plugin \
  --allowedTools "Skill Read Glob Grep Agent" --output-format stream-json --verbose < /dev/null
```

`--allowedTools` deliberately leaves out `Bash`. That proves the skills' own `allowed-tools`
are enough for the scanner. The first run on 2026-10-22 got 12/12 evidence verified, opened 5 of
12 files, and cost $0.51 on Opus.

## Cost

Run `evals/measure-cost.sh` with a repository snapshot and, for the tracer, the flow's
starting point, such as `POST /stripe/webhook`. The script runs each skill through `claude -p` on a snapshot, plus the same catalog question
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

The quick check on the golden fixture, 2026-10-23, `claude -p` with `--plugin-dir` on Windows:

| Run | Model | USD | Turns |
|---|---|---|---|
| `audit`, plan only (16 places, 3 batches; estimate $0.04 + $0.01 for the skill's turns) | Haiku 5.5 | 0.007 | 2 |
| `audit --yes <id>`, 3 `place-checker` agents, 16 places judged, 0 unjudged, every verdict as `verdicts-expected.tsv` says | Haiku 5.5 | 0.044 | 10 |

After the 20 rules of 2026-10-24, the same fixture:

| Run | Model | USD | Turns |
|---|---|---|---|
| `audit`, plan only (40 places, 5 batches; estimate $0.09 + $0.01) | Haiku 5.5 | 0.008 | 2 |
| `audit --yes <id>`, 5 agents, 40 places judged, 0 unjudged; 28 of the 29 verdicts in `verdicts-expected.tsv` matched, and the UNCERTAIN one came from a packet that did not show the event's listener | Haiku 5.5 | 0.102 | 15 |
| the same place re-checked after the packet printed the listener: CONFIRMED | Haiku 5.5 | 0.020 | — |

The skill starts its agents in the foreground. A run whose agents went to the background ended
the skill's turn: the session's default model (Opus) took over, cost ten times as much and was
refused the scanner command that records the drafts.

These runs calibrated `audit/Plan.java`. A checker's own context is about 30k tokens before
the packet, and it writes about 2.4k tokens per place, thinking included. Sub-agents write the
prompt cache at 1.25 times the input price, and the main session at 2 times.

About 34k tokens of every run are the session's base context (system prompt, the user's
installed plugins, the project's CLAUDE.md). The plugin itself adds about 300 tokens of skill
descriptions to every session.

## Local corpus

The local work projects listed in `corpus.md` are evaluated by hand for now, against
snapshots at pinned commits. The list is git-ignored, and their results never enter this
repository.
