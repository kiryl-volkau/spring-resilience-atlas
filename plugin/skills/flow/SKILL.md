---
name: flow
description: List the traced business flows of a Spring repository or open one flow's page, with its sequence diagram, business parts, the code hints on it and, after a deep audit, the scenarios in which it breaks; also snapshot the deep audit's findings and compare with an earlier snapshot. Use when the user asks to see, open or list flows, how a traced flow works, or what was fixed or is new since the last audit.
argument-hint: "[FLOW-id | EP-id | path | --diff [snapshot] | --snapshot]"
allowed-tools:
  - Bash(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
  - PowerShell(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
model: haiku
effort: low
---

Work in the current directory, with one plain command per call and no `cd`:

```text
java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" <command>
```

The user asked for: `$ARGUMENTS`

- **Nothing named:** run `flow list`. It prints one row per recorded flow: id, whether the code
  changed since its trace (`STALE`), steps, segments, hints on it, title.
- **A `FLOW-…` id:** run `flow page <FLOW-id> --open`. It rebuilds the page from the stored data
  and opens it.
- **`--snapshot`:** run `flow snapshot`. It records the flows and the deep audit's findings as
  they stand, in `.reliability/history/`; a deep audit also takes one when it finishes.
- **`--diff`, optionally with a snapshot file name:** run `flow diff` (the newest snapshot
  against the code as it is now) or `flow diff <snapshot>`. Its first line counts the findings
  fixed, new, still open, changed and not audited again, and disproved; one line per finding
  follows. A finding counts as fixed only after a later deep audit of its flow; one whose code
  changed without a new audit is "changed, not audited again", not fixed.
- **An `EP-…` id, a path or a class:** run `flow list` and pick the flow whose entry points
  include that operation. If one does, open its page as above. If none does, do not trace it
  yourself: say that no flow covers it yet and give the command that traces it,
  `/resilience-audit:critical-flow-tracer <EP-id or path>`, which runs on a strong model and
  costs about one dollar.

Do not read source files. The commands are the whole job.

Reply in at most 8 lines, in the user's language:

- the page's `file://` url from the `# page:` line, the list of flows, the snapshot path, or the
  `# since` line of the diff with its fixed and new findings by title;
- for a flow marked `STALE`: the code under it changed since the trace, so the diagram may be
  out of date; re-tracing refreshes it;
- what the page shows: the sequence diagram (click an arrow for its line of code), the
  business parts, the hints and verdicts on its steps, and the failure scenarios once a deep
  audit has run;
- the next command, if any.
