---
name: report
description: Build the HTML resilience report of a Spring repository and open it. The page lists every operation (HTTP endpoints, message consumers, scheduled jobs, listeners, startup hooks) with the risk patterns found on its code path and the scan's blind spots. Use when the user asks for the report, an overview page of the application, or where the application can lose or duplicate work.
argument-hint: "[repo-dir]"
allowed-tools:
  - Bash(java -jar "${CLAUDE_PLUGIN_ROOT}/scanner/dist/resilience-atlas.jar" *)
  - PowerShell(java -jar "${CLAUDE_PLUGIN_ROOT}/scanner/dist/resilience-atlas.jar" *)
model: haiku
effort: low
---

Build the report for the current working directory. It is the project the user opened, even
when it is a module inside a larger git repository. Run exactly this one command, with no
`cd` and no checks beforehand. It rescans the code, writes `.reliability/report.html` and
opens it in the browser.

```text
java -jar "${CLAUDE_PLUGIN_ROOT}/scanner/dist/resilience-atlas.jar" report --scan --open
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
  such as Broken, Smells and OK appear only after an audit has traced a flow.
- the next step: trace the top operation with the `critical-flow-tracer` skill.

If the command fails:

- **`java` not found, or "javac is not available".** The scanner needs a JDK 17+ on `PATH`;
  a JRE is not enough.
- **Jar missing.** The plugin was installed without a build. Build it with
  `mvn -f "${CLAUDE_PLUGIN_ROOT}/scanner/pom.xml" package`.
- **"could not open a browser here".** Give the user the url instead.

`.reliability/` is a local, generated directory. Never stage or commit it, and never change
the repository's `.gitignore` unless the user asks.
