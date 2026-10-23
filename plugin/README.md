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
| `/resilience-audit:critical-flow-tracer` | Traces one business flow into a flow file where every claim has file-and-line evidence |

A level on the report (High, Medium, Low) is a code hint that says where to look first. It is
not a verdict, and an operation without a hint is not declared safe.

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
