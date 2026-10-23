---
name: atlas-bootstrap
description: Build or refresh the reliability atlas (.reliability/atlas) of a Spring repository with a deterministic scan of entry points and types. Use before any reliability audit, or when an atlas query reports the atlas missing or behind HEAD.
argument-hint: "[repo-dir]"
allowed-tools:
  - Bash(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
  - PowerShell(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
model: haiku
effort: low
---

Build the atlas so that later questions about entry points and flows can be answered from it
instead of rereading the application.

Scan the current working directory. It is the project the user opened, even when it is a
module inside a larger git repository. Run exactly this one command, with no `cd` and no
checks beforehand. It reports a missing JDK or jar itself.

```text
java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" scan
```

Add `--repo <dir>` only when the user named a directory: `$ARGUMENTS`.

The scan is the whole job. Do not read or grep source files to second-guess it.

Report back in a few lines:

- the commit, the file counts and the entry points per kind;
- every `# warning:` line, each with what it means for coverage. Explain only the warnings
  that actually appeared:
  - **Parse failures** were scanned by regex, so those records are `INFERRED` at best.
  - **Kotlin files** are scanned by regex too: composed annotations and inherited mappings
    are not resolved there.
  - **Missing interfaces** are generated controller interfaces. The project has to be built
    and rescanned before those endpoints are known.
  - **Messaging libraries without consumers** means consumers may be wired in configuration
    or by code the scanner does not recognise. Treat messaging coverage as unknown.
  - **XML-declared endpoints** (CXF, `SimpleUrlHandlerMapping`) are not in the atlas.
  - **Unresolved entry-like annotations.** Annotations from internal libraries can hide whole
    groups of endpoints or listeners. Name the top ones, and point to
    `manifest.json` → `unresolvedAnnotations`.
  - **Controllers extending library classes** may inherit mappings the atlas cannot see.
  - **`@Scheduled` without `@EnableScheduling`, or `@Async` without `@EnableAsync`.** Unless
    the annotation is enabled elsewhere, those methods do not run as scheduled or async.
- the next useful step: `stats` for an overview, the `entrypoint-catalog` skill for
  questions, `domain-cartographer` to map business domains.

If the command fails:

- **`java` not found, or "javac is not available".** The scanner needs a JDK 17+ on `PATH`;
  a JRE is not enough.
- **"Could not find or load main class" or "UnsupportedClassVersionError".** The `java` on
  `PATH` is older than 17. Ask the user to put a JDK 17+ first on `PATH`.

The atlas is a local, generated artifact. Never stage or commit it, and never change the
repository's `.gitignore` unless the user asks.
