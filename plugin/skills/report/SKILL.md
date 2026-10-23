---
name: report
description: Build the HTML resilience report of a Spring repository and open it. The page lists every operation (HTTP endpoints, message consumers, scheduled jobs, listeners, startup hooks) with the risk patterns found on its code path and the scan's blind spots. Use when the user asks for the report, an overview page of the application, or where the application can lose or duplicate work.
argument-hint: "[repo-dir]"
allowed-tools:
  - Bash(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
  - PowerShell(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
model: haiku
effort: low
---

Build the report for the current working directory. It is the project the user opened, even
when it is a module inside a larger git repository. Run exactly this one command, with no
`cd` and no checks beforehand. It rescans the code, writes `.reliability/report.html` and
opens it in the browser.

```text
java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" report --scan --open
```

Add `--repo <dir>` only when the user named a directory: `$ARGUMENTS`.

The command is the whole job. Do not read or grep source files to second-guess it, and do
not rerun it.

Reply in a few lines, in the user's language:

- the report's path and its `file://` url from the `# url:` line;
- the number of operations, and the counts per attention level from `# static attention:`.
  Operations counted as "not analysed" have no level at all, because their code was never
  read. Never describe them as safe or as "none";
- the top rows of the ranked table (at most five): level, operation, handler and first
  pattern, one line each;
- the blind spots from the `# blind spot:` lines, one short clause each;
- one sentence on what the levels mean. A level is a static hint computed from the code
  path: it says where to look first. It is not a verdict that something is broken. Statuses
  such as Broken, Smells and OK appear only after an audit.
- if the `# audit` line shows results, relay its counts. Smells there are mostly places the
  quick check suspects, not verified defects; "checked (quick)" is not "safe". Mention
  `.reliability/backlog.md` when the line shows suspected places.
- the next step: `/resilience-audit:audit` checks the hints with a cheap model and prints its
  cost before spending anything; the `critical-flow-tracer` skill traces one operation in depth.

If the command fails:

- **`java` not found, or "javac is not available".** The scanner needs a JDK 17+ on `PATH`;
  a JRE is not enough.
- **"Could not find or load main class" or "UnsupportedClassVersionError".** The `java` on
  `PATH` is older than 17. Ask the user to put a JDK 17+ first on `PATH`.
- **"could not open a browser here".** Give the user the url instead.

`.reliability/` is a local, generated directory. Never stage or commit it, and never change
the repository's `.gitignore` unless the user asks.
