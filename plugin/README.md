# Resilience Audit

A Claude Code plugin that audits large Spring Boot applications for reliability failure
modes: work lost after the caller was answered, dual writes, unsafe retries, non-durable
async hand-offs, scheduler races and remote calls held inside database transactions.

A deterministic scan builds an **atlas** of every entry point: HTTP endpoints, message
consumers, scheduled jobs, event listeners, startup hooks, SOAP, GraphQL, gRPC and portlets.
It then follows each operation's code path and records what it writes, sends and hands off.
The report shows where to look first, and the audit skills open only the code needed to
prove or disprove one hypothesis for one flow.

![The report on the synthetic golden fixture](assets/report.png)

## Install

You need Claude Code and a JDK 17 or newer on `PATH`. The scanner parses Java with the JDK's
own compiler, so a JRE is not enough.

In a Claude Code session:

```text
/plugin install resilience-audit --marketplace kiryl-volkau/spring-resilience-atlas
```

Then open a Spring repository in Claude Code and run `/resilience-audit:report`. The skills
need a local shell and a JDK, so they work in Claude Code, not in claude.ai chat.

## Skills

| Skill | What it does |
|---|---|
| `/resilience-audit:report` | Scans the repository and opens an HTML page with every operation, the risk patterns on its code path and the scan's blind spots |
| `/resilience-audit:atlas-bootstrap` | Builds or refreshes the atlas in `.reliability/atlas/` |
| `/resilience-audit:entrypoint-catalog` | Answers questions about endpoints, consumers, jobs and listeners from the atlas instead of grepping |
| `/resilience-audit:domain-cartographer` | Maps packages to business domains so queries and audits can filter by domain |
| `/resilience-audit:critical-flow-tracer` | Traces one business flow across the entry points it reaches into a flow where every step has file-and-line evidence, drawn as a page with a sequence diagram |
| `/resilience-audit:flow` | Lists the traced flows or opens one flow's page: the diagram, its business parts, the hints on it and, after a deep audit, the ways it breaks |
| `/resilience-audit:audit` | Checks the report's high and medium hints with a cheap model, one hypothesis per place in the code. It first prints the plan and its estimated cost and spends nothing; `--yes <plan-id>` runs exactly that plan within a hard cap. Filters such as `--package` or `--rule` check a selection; `--level LOW` adds the low hints and `--level NONE` sweeps operations without a hint that still write, send or call out. Results appear in the report and in `.reliability/backlog.md`. `--deep` audits whole flows instead: it writes failure scenarios on a traced flow, has a second model try to disprove the severe ones, and draws them on the flow's page, with the same plan, estimate and `--yes` gate |

A level on the report (High, Medium, Low) is a code hint that says where to look first. It is
not a verdict, and an operation without a hint is not declared safe. A place the quick check
confirms is "suspected", not verified; "Checked (quick)" is not "safe".

Most hints come from an operation's call path. A few come from its module instead: the
`application` config (graceful shutdown, Open Session in View, Kafka auto-commit, `ddl-auto`,
Feign timeouts) and the newest migration. Such a place is the config key or the migration
file, it lists every operation the fact reaches, and it does not raise their own levels.

### Cost of the quick check

The report costs nothing beyond one short Haiku turn. The quick check runs one `place-checker`
agent (Haiku by default) per batch of up to eight places; the plan prints its token and dollar
estimate before anything runs, and the run stops at the cap (twice the estimate unless you set
`--max-usd`). Places already checked are not checked again until their code changes. On the
plugin's test application, 40 places cost $0.10 on Haiku, close to the estimate.

### Cost of the deep audit

A deep audit costs more because it uses strong models. On the plugin's test application,
one flow cost $3.58: the failure scenarios on Fable $2.72, the verification and the
orchestration on Opus. Tracing a flow first adds about $1 on Opus. The plan prints its estimate
per flow before anything runs, and `--deep-model opus` writes the scenarios on Opus for
accounts without Fable. The plan audits at most three flows unless you set `--max-flows`.

The cap applies to the plugin's estimate of each batch or deep step, counted when it starts. The
plugin cannot see your bill, so the real cost can differ from the estimate.

The agents can read files and write their own draft under `.reliability/audit/drafts/`; they
cannot run commands. They are told never to modify your code, but Claude Code cannot restrict
a subagent's writes by path, so review the session's file changes as usual. Claude Code may ask
once per session to allow those draft writes.

## What the plugin runs

- **The scanner, from source.** Skills run `java <plugin>/scanner/Launch.java`. On first use
  it compiles the bundled scanner sources with the JDK's compiler into the plugin's data
  directory (`~/.resilience-atlas` when that is unavailable) and reuses the build until the
  plugin changes. The plugin ships no compiled code.
- **Local files only.** The scanner reads the source files of the repository you opened and
  writes only `.reliability/` inside it. `report --open` opens the generated page in your
  browser.
- **No network.** Nothing is downloaded or sent anywhere, and there is no telemetry. Claude
  reads the scanner's output, and audit skills open the files needed for one hypothesis, as
  in any Claude Code session.

## License

Copyright 2026 Kiryl Volkau. Licensed under the [Apache License, Version 2.0](LICENSE).
