---
name: critical-flow-tracer
description: Trace one business flow from an entry point (EP id, HTTP path, listener, job or Class.method) through transactions, DB writes, messages, async hand-offs and external calls into an evidence-backed flow file. Use before auditing a flow's reliability.
argument-hint: "<EP-id | /http/path | Class.method | queue>"
context: fork
agent: resilience-audit:flow-tracer
allowed-tools:
  - Bash(java -jar "${CLAUDE_PLUGIN_ROOT}/scanner/dist/resilience-atlas.jar" *)
  - PowerShell(java -jar "${CLAUDE_PLUGIN_ROOT}/scanner/dist/resilience-atlas.jar" *)
  - Write(.reliability/flows/**)
  - Edit(.reliability/flows/**)
---

Trace the flow that starts at: $ARGUMENTS

Work in the current directory: the project whose atlas is in `.reliability/atlas`. Never `cd`
elsewhere, not even to the git root of a larger repository.

Run the atlas CLI as one plain command per call:

```text
java -jar "${CLAUDE_PLUGIN_ROOT}/scanner/dist/resilience-atlas.jar" <command>
```

The same jar prints the bundled references, so nothing outside this project has to be read:

| Command | Prints |
|---|---|
| `doc atlas-cli.md` | the CLI reference |
| `doc flow.schema.json` | the flow file format |
| `doc not-in-atlas.md` | what the atlas cannot see |

1. **Resolve the start.** Find it with `entrypoints` (by id, `--target`, `--class`, `--subkind`
   or `--tag webhook`), then `record <EP-id>`.
   - Several matches: pick the one the request most plausibly means, and list the others in
     `openQuestions`.
   - No match: stop and say so.
   - Atlas missing or behind: stop and ask for the `atlas-bootstrap` skill.
2. **Trace and write.** Write the flow to `.reliability/flows/<FLOW-id>.json`, following the
   schema. Name it `FLOW-<domain-or-module>-<short-slug>`, in lower-case kebab.
3. **Verify the evidence.** Before you finish, run:

   ```text
   java -jar "${CLAUDE_PLUGIN_ROOT}/scanner/dist/resilience-atlas.jar" verify-evidence .reliability/flows/<FLOW-id>.json
   ```

   It exits non-zero and prints `FAILED` when any line is `MISMATCH`, `MALFORMED`, `TRIVIAL`
   or `MISSING_FILE`, or when a hop or boundary has no evidence. Fix the file until the command
   exits 0, then quote its summary line in your reply.
