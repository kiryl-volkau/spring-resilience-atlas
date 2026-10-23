---
name: entrypoint-catalog
description: Answer questions about a Spring app's entry points (HTTP endpoints, webhooks, queue consumers, scheduled jobs, event listeners, startup hooks, SOAP, WebSocket, GraphQL, gRPC) from the reliability atlas, by domain, module or package. Use instead of grepping for controllers or listeners.
argument-hint: "[question]"
allowed-tools:
  - Bash(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
  - PowerShell(java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" *)
model: haiku
effort: medium
---

Answer the question from the atlas: $ARGUMENTS

Run every query as one plain command, exactly in this form, with no `cd`, variables or
chaining (only this form is pre-approved):

```text
java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" <command> [options]
```

**Start with `stats`.**

- It shows whether the atlas exists and is current, the counts per kind, the domains, and the
  warnings about what the scan could not see.
- If the atlas is missing, or behind `HEAD` with changed sources, run the `atlas-bootstrap`
  skill first. Do not answer from a stale atlas without saying so.

**The atlas is the source of truth for what it covers.** Do not grep or read the repository to
enumerate controllers, listeners or jobs: that costs far more and finds less. Open source files
only when the user asks what one specific entry point does, and then only a few. For anything
deeper, suggest the `critical-flow-tracer` skill.

Filters for `entrypoints`:

| Flag | Match |
|---|---|
| `--kind HTTP,MESSAGING,SCHEDULED,EVENT,LIFECYCLE,SOAP,JOB,WEBSOCKET,GRAPHQL,GRPC,ACTUATOR,ROUTER` | exact, comma for any-of |
| `--subkind GET,SQS,CRON,PROGRAMMATIC` | any-of substring |
| `--status STATIC,INFERRED,UNKNOWN` | exact |
| `--tag webhook,oauth-callback,webhook-management,unauthenticated-write` | the webhook tag on HTTP records |
| `--module`, `--package` | prefix |
| `--class`, `--target`, `--file`, `--via` | substring |
| `--path-prefix`, `--domain` | prefix of the target / domain name |

Shaping the output: `--group-by module|package|path|kind|subkind|status|domain`,
`--format count|tsv|jsonl`, `--fields +attrs` (adds to the default columns), and
`--limit N --offset N`.

- **Size the answer first.** Narrow with filters, `--format count` or `--group-by` before
  listing rows. Page with `--offset` only when the user needs every row.
- **Domains.** When `stats` lists domains, filter with `--domain`. Otherwise narrow with
  `--package`, `--module` or `--path-prefix`, say that domains are not mapped yet, and suggest
  `domain-cartographer`.
- **Details.** `record <EP-id>` gives one full record: attributes, `via`, conditions and the
  webhook signals.
- **Full reference.** `doc atlas-cli.md` prints the CLI reference, and `doc not-in-atlas.md`
  lists what the atlas cannot see.

In the answer:

- Give each entry point as `EP-id`, the target, `Class.method` and `file:line`, so the user can
  open it. When the `params` column is set, write it after the target in brackets, such as
  `POST /orders [format=csv]`: it is what tells handlers on the same path and verb apart.
- Call out:
  - `INFERRED` rows: derived through inheritance, programmatic wiring, Kotlin, Groovy or the
    regex fallback;
  - `UNKNOWN` rows: a value the scanner could not evaluate;
  - targets with `${...}` placeholders, whose real value comes from configuration;
  - `attrs.disabled`, which marks a schedule that never runs.
- Carry over every `stats` warning that limits the answer. Examples: unresolved library
  annotations, Kotlin, Groovy, parse failures, and a missing `@EnableScheduling`.
- Never present an empty result as proof that something does not exist. Say which filters
  were applied. If the question touches something in `doc not-in-atlas.md`, such as XML
  wiring, Spring Integration flows or Kotlin and Groovy precision, say so and how to check.
