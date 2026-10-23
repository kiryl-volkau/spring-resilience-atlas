---
name: audit
description: Check the code hints of the resilience report with a cheap model. Plans the check of the report's places, prints its cost and runs it only on --yes <plan-id>. Use when asked to check, verify or audit the hints, the places or the risky operations of the report, for all of them or for a package, module, rule or operation.
argument-hint: "[--yes <plan-id>] [--package P | --module M | --kind K | --rule R | --level HIGH,MEDIUM,LOW,NONE | --places PL-… | --ops EP-…] [--model sonnet] [--max-usd X]"
allowed-tools:
  - Bash(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
  - PowerShell(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
  - Edit(.reliability/audit/drafts/**)
model: haiku
effort: medium
---

You run the quick check of the resilience report: a cheap model checks one hypothesis per
place in the code where a HIGH or MEDIUM hint fired. `--level LOW` adds the low hints, and
`--level NONE` sweeps the operations without any hint that still write, send or call out;
`--places` and `--ops` check what they name at any level. The scanner plans, prices, writes the
evidence packets and records the verdicts; the `place-checker` agents judge. You make no
judgement of your own and never read the application's source.

Work in the current directory, the project the user opened. Run the scanner as one plain
command per call, with no `cd`, variables or chaining:

```text
java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" audit <subcommand> …
```

Arguments: `$ARGUMENTS`

## Which path

- **The arguments contain `--yes <plan-id>`.** Go straight to step 2 with exactly that id.
  Do not run `audit plan` first: planning again replaces the plan, and the confirmed id stops
  working.
- **Otherwise.** Run step 1. Continue to step 2 only when the user's message in this turn
  explicitly asks to run after seeing the estimate, with or without a limit ("plan it and if
  it is under $2 run it"), and the printed estimate meets that limit; then use the id the plan
  printed. In every other case stop after step 1.

## Spending rule

Nothing costs money until a `place-checker` agent starts. Start agents only in step 2, and
only for packet paths that `audit next` printed in this turn. Never pass `--yes` on your own
initiative, never re-invoke this skill, and never compute a cost or a cap yourself: the
scanner's lines are the only numbers you repeat.

## 1. Plan

Run `audit plan` with every argument except `--yes <id>` passed through unchanged, for
example `audit plan --package com.acme.billing`. It rescans first if the sources changed. It
prints the places in scope, then `#` lines with the estimate, the caps and the exact
`--yes` command.

If it prints `# nothing to check`, relay that line and stop. If it fails with
`--domain needs domains.jsonl`, say so and suggest `--package`.

Unless the spending rule allows the run, reply in a few lines, in the user's language, and
stop:

- how many places are in scope, by level as the plan line prints them, and how many were
  skipped as already checked;
- the estimate in tokens and USD, and the cap, from the `#` lines;
- that nothing has been spent;
- the exact command to run this plan, from the last `#` line.

## 2. Run

Loop until `audit next` stops:

1. Run `audit next <plan-id>`. It prints a progress line, then one `packet` line per batch:
   `packet <path> draft <path> batch <id> model <alias>`.
   - A line `stoppedBecause: …` ends the loop: go to step 3.
   - An error (the sources, the atlas or the store changed since the plan) ends the run:
     relay the scanner's message and go to step 3.
2. Launch one `resilience-audit:place-checker` agent per packet line, **all of them in one
   message**, each in the foreground (`run_in_background: false`) so that this turn waits for
   them: the scanner commands of steps 3 and 1 are allowed only while this skill's turn runs.
   Pass the line's `model` as the agent's model. The prompt is exactly
   `Packet: <packet path>. Draft: <draft path>.`, with both paths copied from the line.
3. When every agent of the wave has returned, whether it succeeded, failed or wrote nothing,
   run `audit record <batch>` once for **each** batch of the wave, one after another. Record
   never fails for a bad draft: it stores the verdicts it can accept and marks the rest
   unjudged. Do not retry an agent and do not fix a draft yourself.
4. Print the progress line of the wave in one line, then go to 1.

## 3. Report

Run `report` (no `--scan`). Then reply in the user's language, in a few lines:

- from the last `# recorded` lines and the progress line: places judged, confirmed,
  mitigated, refuted, uncertain and unjudged, and the estimated spend against the cap;
- the record lines' notes, in one line each when present: a verdict recorded as suspected
  because the check named a failure that remains, and checks that opened more files than
  their budget;
- from the `# audit` line of the report: the operations now suspected (shown as Smells),
  checked (quick) and still not audited;
- the report's `file://` url, and the backlog at `.reliability/backlog.md`;
- operations not analysed at all (Kotlin, Groovy, unparsed code), and operations with only
  low hints or none, were not checked unless the plan asked for them; say so with their counts
  from the report, and name `--level LOW` and `--level NONE` as the way to add them;
- if the run stopped early (`stoppedBecause: usd` or `waves`, or an error), say why and that
  running `/resilience-audit:audit` again plans the rest.

Call a confirmed place "suspected": the quick check found the pattern and no mitigation in
the code it read. It is not a verified defect. "Checked (quick)" is not "safe".

## If something fails

- **`java` not found, or "javac is not available".** The scanner needs a JDK 17+ on `PATH`.
- **"plan … is not the current plan".** A newer plan replaced it. Run the skill without
  `--yes` to plan again.
- **An agent cannot write its draft.** Record the batch anyway; its places become unjudged
  and the next plan offers them again.

`.reliability/` is a local, generated directory. Never stage or commit it, and never modify
the application's code.
