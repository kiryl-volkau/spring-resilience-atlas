# Atlas CLI

Every skill queries the atlas through one command, which runs the scanner from the plugin's
sources. It needs a JDK 17+ on `PATH` and nothing else. The first run compiles the scanner,
which takes a few seconds; the build is cached until the plugin changes.

```text
java "${CLAUDE_PLUGIN_ROOT}/scanner/Launch.java" --cache "${CLAUDE_PLUGIN_DATA}" <command> [options]
```

It works on the current directory, the project the user opened, even when that is a module
inside a larger git repository. Pass `--repo <dir>` only when the user names another
directory. The atlas lives in `<dir>/.reliability/atlas/`.

Run each query as one plain command in exactly this form: no `cd`, no shell variables, no
`&&` or `;` chains, no pipes. Skills pre-approve only the plain form. A compound command
needs a permission prompt, and in non-interactive runs it is refused.

## Output contract

- Lines starting with `#` come first:
  - the staleness line `# atlas @<sha> (HEAD <sha>, clean)`, or a warning that the atlas is
    behind;
  - the match count and paging hint, such as `# 312 entrypoints match (...); showing 1-50; next: --offset 50`.
- Lists print as TSV with a header row. `record` prints JSON, and `--format count` prints a
  bare number.
- Zero matches exits 0 and suggests the closest values. Exit code 2 means a usage error or a
  missing atlas; the message on stderr names the fix.
- Ordering is deterministic. Ids (`EP-…`) are stable across scans while the entry point is
  unchanged.

Read the count line before paging. When a query matches hundreds of rows, narrow it or use
`--group-by` instead of paging through everything.

## Commands

| Command | Use |
|---|---|
| `scan` | Build or refresh the atlas (`--repo`, `--atlas`). Takes seconds |
| `report [--scan] [--open]` | Write `.reliability/report.html`: every operation with its attention level, the risk patterns and evidence on its code path, and the blind spots. `--scan` rebuilds the atlas first, `--open` opens the page. Reads `.reliability/audit/` verdicts and findings when they exist (`--audit`, `--flows`, `--out`, `--title`). Prints the counts and the ten highest-ranked operations |
| `stats` | Build facts, counts by kind and status, the largest modules, warnings |
| `entrypoints` | List entry points; filters below. The `params` column holds the `params` and `headers` conditions that tell HTTP handlers on one path and verb apart |
| `types` | Types: `--implements X`, `--annotated A`, `--stereotype Service`, `--name TEXT`, `--package P` |
| `record <id>` | The full JSON record of an entry point (`EP-…`) or a type (fully qualified name) |
| `packages [--depth N]` | Package tree with type and entry-point counts |
| `domains` | Domains from `domains.jsonl`, with entry-point counts |
| `verify-evidence <file>` | Check every `{file, line, snippet}` in a flow or finding document against the working tree. Exits 1 and prints `FAILED` on any `MISMATCH`, `MALFORMED`, `TRIVIAL` or `MISSING_FILE` line, on a hop or boundary without evidence, or when there is no evidence at all |
| `doc <name>` | Print a bundled reference: `atlas-cli.md`, `not-in-atlas.md`, `entrypoint-rules.md`, `entrypoint`/`domain`/`flow`/`finding`/`place-verdict` `.schema.json` |
| `places [--limit N] [--level HIGH,MEDIUM] [--rule CODE]` | The report's places in its fix-first order: id, level, rule, method, location, operation count, the first operation and its target, and the quick-check verdict when one is recorded. Reads only |
| `audit plan [filters]` | Plan the quick check of the report's places (a rule code at the method enclosing its evidence, or at the method that opens the transaction for a rule about one; HIGH and MEDIUM unless `--level` says otherwise). Rescans when the sources changed, skips places with a current verdict, writes `.reliability/audit/plan.json` and prints the places, the estimate in tokens and USD, the caps and the plan id. Spends nothing |
| `audit next <plan-id> [--batches N]` | Issue the next N batches (default 4) of that plan: write their evidence packets and count their estimate as spent. Exits 2 when the sources, the atlas or the store changed since the plan; prints `stoppedBecause: done\|usd\|waves` when nothing more may be issued |
| `audit record <batch-id>` | Validate the checker's draft for a batch, verify its evidence, store one row per place (`places/<batch>.jsonl`; UNJUDGED with a reason when a row is missing or rejected) and rebuild `places.jsonl`, `verdicts.jsonl`, `coverage.json` and `.reliability/backlog.md`. Never fails for a bad draft |

`audit plan` filters: `--places PL-a,PL-b`, `--ops EP-a,EP-b`, `--rule CODE`, `--level HIGH,MEDIUM`,
and every entry-point filter below (a place is in scope when any of its operations matches).
`--level LOW` adds the low hints; `--level NONE` adds one `no-hint` place per operation that
writes, sends or calls out without any rule firing, for a sweep that names the rule it finds.
`--places` and `--ops` select at every level unless `--level` is given. Each packet prints the
methods around the evidence and, for a rule about a transaction, the method that opens it. It
also names, without printing them, the repositories the path writes through, their entities
and the Flyway or Liquibase migrations that name the entity's table, so a checker can look for
a unique constraint.
Options: `--model haiku|sonnet|opus|fable` (default haiku), `--batch N` places per batch
(default 8), `--max-usd X` (default twice the estimate), `--waves N` (default 12),
`--include-current` (re-check places that already have a current quick-check verdict), and
`--audit DIR` (default `.reliability/audit`). A verdict goes stale when the method around its
evidence, the effects the rule cited, the mitigation signals on the path or a file the checker
opened change.

## Entry-point filters

| Flag | Match |
|---|---|
| `--kind HTTP,MESSAGING,SCHEDULED,EVENT,LIFECYCLE,SOAP,JOB,WEBSOCKET,GRAPHQL,GRPC,ACTUATOR,ROUTER,PORTLET` | exact, comma for any-of |
| `--tag webhook,oauth-callback,webhook-management,unauthenticated-write` | the webhook verdict in `attrs.webhook` of HTTP records |
| `--subkind SQS,KAFKA` | substring, comma for any-of (`GET`, `CRON`, `TRANSACTIONAL_EVENT_LISTENER`, …) |
| `--status STATIC,INFERRED,UNKNOWN`, `--origin SOURCE,GENERATED,KOTLIN,GROOVY,FALLBACK` | exact |
| `--module PREFIX`, `--package PREFIX` | prefix of the module path / class name |
| `--class TEXT`, `--target TEXT`, `--file TEXT`, `--via TEXT` | substring |
| `--path-prefix /api/billing` | prefix of the target |
| `--domain NAME` | needs `domains.jsonl` |
| `--group-by module\|package\|class\|path\|kind\|subkind\|status\|domain [--depth N]` | counts instead of rows; `path` groups HTTP targets by their first N segments (default 2) |
| `--fields id,target,+attrs` / `--fields all` | projection (`+x` adds to the defaults) |
| `--limit N` (default 50, max 500), `--offset N`, `--format tsv\|jsonl\|count` | paging and format |

In Git Bash, an argument starting with `/` can be rewritten into a Windows path. The CLI
undoes that rewrite using Git Bash's `EXEPATH`, or the default install path when `EXEPATH` is
missing. `--target api/billing` without the leading slash is always safe.

## Record fields

The full definition is printed by `doc entrypoint-rules.md`. The fields that matter most when
reasoning:

- **`status`.** `STATIC` was read directly from source. `INFERRED` was derived through
  inheritance, a programmatic pattern, Kotlin, Groovy or the regex fallback. `UNKNOWN` holds a value
  that could not be evaluated, shown as `<expr>` inside the target.
- **`via`.** How the record was derived, such as `inherited:OrderApi`, `meta:@JsonGet`,
  `programmatic:receiveMessage` or `missing-interface:OwnersApi`.
- **`placeholder`.** True when the target holds `${…}` or `#{…}`. The real value comes from
  configuration.
- **`attrs`.** Extra attributes:
  - `async`, `phase`, `initialDelay`, `groupId`, `conditions`;
  - `disabled` for `cron = "-"`;
  - `schedulerLock`, `disallowConcurrent`, `retryableTopic`;
  - `handler` for functional routes;
  - `webhook`, which holds `{verdict, confidence, score, signals}`.

What the atlas cannot see is listed by `doc not-in-atlas.md`.

## Effects

`scan` also writes `effects.jsonl`, one row per entry point:
`{id, analysed, methods, files, truncated, unresolvedCalls, beyondReach, entryReturns, effects[]}`.

- **The walk.** It starts at the entry method and covers the methods of its class that it
  calls, plus the methods of up to three injected beans.
  - A receiver's type comes from a declaration: a local, a parameter, a field, a static type
    name, or `new X()`. Calls on anything else count as `unresolvedCalls`.
  - Same-arity overloads are told apart by the types of the arguments passed.
  - Lambdas and anonymous classes handed to executors or after-commit callbacks run outside the
    caller's transaction. `TransactionTemplate` callbacks run inside one.
- **Each effect** is `{signal, detail, file, line, symbol, hop, inTx, inReadOnlyTx?, txOwner?, txOwnerAt?}`.
  - `hop` is the number of bean boundaries crossed.
  - `inTx` means a read-write transaction is open at that point. `inReadOnlyTx` means a
    read-only one is.
  - `txOwner` is the `Type.method` that opened that transaction (with `@Transactional` or a
    `TransactionTemplate`), and `txOwnerAt` its `file:line`.
- **Signals:**
  - `async-handoff`: an executor, `@Async`, `CompletableFuture.*Async`, a new thread or an
    Akka `tell`. Its `detail` starts with `timer ` for `schedule*`, and ends in "(waited for in
    the same method)" when the result is joined.
  - `in-memory-state`: a collection or counter of a bean, an assigned value field, or an HTTP
    session attribute without Spring Session.
  - `local-file`.
  - `db-write`, `message-send`, `external-call` (HTTP, SOAP ports, SDK calls, object storage,
    e-mail) and `event-publish`.
  - `transactional`.
  - Mitigations: `retry`, `lock` (including row locks) and `idempotency-check`.
  - `self-invocation`: a proxy annotation bypassed by a call through `this`, or put on an init
    callback.
- **Not analysed.** Kotlin, Groovy and regex-fallback records have `analysed: false` and no effects.
