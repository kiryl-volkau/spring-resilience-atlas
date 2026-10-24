---
name: critical-flow-tracer
description: Trace one business flow from an entry point (EP id, HTTP path, listener, job or Class.method) through transactions, DB writes, messages, async hand-offs, external calls and the other entry points it reaches, into an evidence-backed flow with a sequence-diagram page. Use before auditing a flow's reliability, or to see how a flow works.
argument-hint: "<EP-id | /http/path | Class.method | queue>"
context: fork
agent: resilience-audit:flow-tracer
allowed-tools:
  - Bash(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
  - PowerShell(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
  - Write(.reliability/flows/**)
  - Edit(.reliability/flows/**)
---

Trace the flow that starts at: $ARGUMENTS

Work in the current directory: the project whose atlas is in `.reliability/atlas`. Never `cd`
elsewhere, not even to the git root of a larger repository.

Run the atlas CLI as one plain command per call:

```text
java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" <command>
```

The same command prints the bundled references, so nothing outside this project has to be read:

| Command | Prints |
|---|---|
| `doc atlas-cli.md` | the CLI reference |
| `doc flow.schema.json` | the flow format, version 2 |
| `doc not-in-atlas.md` | what the atlas cannot see |

1. **Resolve the start.** Find it with `entrypoints` (by id, `--target`, `--class`, `--subkind`
   or `--tag webhook`), then `record <EP-id>`.
   - No start given (nothing after "starts at:" above): run `places --limit 5`. Take the
     first row whose `verdict` is not `REFUTED` or `MITIGATED`, and trace its `first`
     operation. Say in your reply which place you started from, and list the other rows as
     candidates for the next run.
   - Several matches: pick the one the request most plausibly means, and list the others in
     `openQuestions`.
   - No match: stop and say so.
   - Atlas missing or behind: stop and ask for the `atlas-bootstrap` skill.
2. **Trace and write the draft** to `.reliability/flows/drafts/<FLOW-id>.json`, following the
   schema. Name it `FLOW-<domain-or-module>-<short-slug>`, in lower-case kebab. Follow the work
   into the other entry points it reaches: a queue's consumer, an event's listener, the webhook
   that answers an outbound call.
3. **Record it.** Run:

   ```text
   java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" audit record-flow <FLOW-id>
   ```

   It exits 1 and prints a `PROBLEM` line for every schema error, unknown id and evidence line
   that does not match the tree. Fix the draft until it exits 0. It then writes the flow, the
   flows index and the page `.reliability/flows/<FLOW-id>.html`, and prints the numbered steps.
   Quote its `# flow … recorded` and `# page:` lines in your reply.
