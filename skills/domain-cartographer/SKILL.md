---
name: domain-cartographer
description: Map a Spring monolith's packages to business domains (e.g. Billing, Resident, CRM) with confidence and write .reliability/atlas/domains.jsonl, so atlas queries can filter by domain. Use when asked what the business areas are, or before domain-scoped audits.
argument-hint: "[known domain names]"
allowed-tools:
  - Bash(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
  - PowerShell(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
model: sonnet
effort: medium
---

Propose the application's business domains and record them as package-prefix mappings. Every
later audit is scoped by domain, so a wrong boundary misdirects all of them. Show confidence
honestly rather than pretending to know.

Domain names the user already uses: $ARGUMENTS

Start with `java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" stats`. If the
atlas is missing or stale, run the `atlas-bootstrap` skill first.

## Signals

Gather these from the atlas, not by reading the tree. Run each command as one plain
`java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" <command>` call, with no
`cd` and no chaining:

- `packages`, and `packages --depth <N+1>` where one package is large;
- `entrypoints --group-by package` and `entrypoints --kind HTTP --group-by path --depth 2`,
  because route prefixes often name the domain;
- `types --stereotype Entity --group-by package`, which shows who owns which state;
- `entrypoints --kind MESSAGING,SCHEDULED --fields target,symbol`, since queue, topic and job
  names carry domain words;
- module names in `stats`.

Open at most five source files, and only where a package's purpose stays unclear: a
`package-info.java`, or an architecture document in the repository. Never read a package
wholesale.

## Output

Write `.reliability/atlas/domains.jsonl`, one JSON object per line. The fields are exactly
those of this example. `doc domain.schema.json` prints the schema, if needed.

```json
{"domain":"Billing","packages":["com.acme.billing","com.acme.payments"],"confidence":0.9,"signals":["route prefix /api/billing","entities Invoice, Payment","queue invoice-paid"],"uncertain":["com.acme.shared.money"]}
```

- Prefixes are matched longest-first, so a sub-package can belong to a different domain than
  its parent.
- Use an explicit `Platform` or `Shared` domain for cross-cutting code, so it is not left
  unassigned.
- `confidence` is between 0 and 1. List whatever made you unsure under `uncertain`.

Then run `domains`. Its `(unassigned)` row should be zero or explained.

Present the map as a table: domain, packages, entry points, confidence, the main signal. Ask
the user to correct names and boundaries, because they are the authority. Edit the file to
match their corrections; that is all an update takes.
