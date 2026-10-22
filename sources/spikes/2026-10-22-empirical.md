# Entry-point scanner spike: regex vs AST, measured on real Spring code

Date: 2026-10-22. Time-boxed empirical spike for the `resilience-audit` plugin
(`entrypoints.jsonl`). All work is under the session scratchpad; nothing in the
project repository was modified.

## 1. Setup

| Item | Value |
|---|---|
| OS / tools | Windows 11, JDK 26.0.2, Maven 3.9.11, Python 3.14.0 (`py -I`), ripgrep 14.1.1, git |
| Prototype A (regex) | `prototypes/regex_scan.py`, Python stdlib only, two passes (constants + meta-annotations, then scan), comment stripping, class-level + method-level concatenation, constant resolution by `Class.NAME` (file basename) and by simple name |
| Prototype B (AST) | `prototypes/ast-scan/` (Maven, JavaParser 3.27.1, `BLEEDING_EDGE` level, parallel parse), global index of types / constants / meta-annotations, class-level prefixes inherited from interfaces and superclasses, projection of interface- and abstract-declared mappings onto implementations, nested-type and static-import constant resolution |
| Prototype C (parse-only) | `prototypes/javac-parse/JavacParse.java`: javac Tree API (`com.sun.source`, no dependencies) parse time, failure rate and annotation count, to test parser choice |
| Helpers | `prototypes/diff_results.py` (diff + root-cause buckets), `prototypes/sizes.py` (output-size projections), `prototypes/fixture/` (synthetic hard cases, my own code) |
| Outputs | `scratchpad/out/*.jsonl`, `*.tsv`, `*.err` |

Environment problems met on the way (all relevant for the real scanner):

- Cloning into the scratchpad failed with `Filename too long` until `git -c core.longpaths=true` was used; the scratchpad path alone is ~170 characters. The scanner must handle long paths on Windows (it reads, not writes, so `Files.walk` is fine, but any script that shells out to git needs `core.longpaths`).
- The user's `~/.m2/settings.xml` routes every download to an unreachable corporate Nexus mirror; the Maven build only worked with a scratchpad `settings.xml` (`-s`). A scanner that needs a dependency download at first run will fail on such machines; a zero-dependency scanner (javac Tree API) or a vendored jar will not.
- JavaParser 3.26.4 and 3.27.1 both top out at `LanguageLevel.JAVA_21`; thingsboard compiles with `maven.compiler.source=25` and uses Java 22 unnamed variables (`_`). 11 files failed to parse; 2 entry points were lost. javac's Tree API on the same JDK parses all of them.

## 2. Corpus

All cloned `--depth 1` into `scratchpad/corpus/<name>/`, read-only, never built.

| Repo | Commit | Main Java files (excl. test) | All Java | Kotlin | pom.xml modules | Why |
|---|---|---|---|---|---|---|
| spring-petclinic/spring-petclinic-rest | `afc8fc1d3b8ec8de69414482b3bd70a1da78cb95` | 87 | 109 | 0 | 1 | small; OpenAPI-generated controller interfaces |
| jhipster/jhipster-sample-app | `6b000b5d23a36c45e01472471b84a44fa2464044` | 81 | 136 | 0 | 1 | medium-style monolith, literal mappings, `@Async`, `@Scheduled` |
| alibaba/nacos (added) | `ef52650006d1086bb1007a223e35875cb63ec146` | 3,039 | 5,321 | 0 | 63 | multi-module; paths built from constants in nested classes and interfaces |
| thingsboard/thingsboard | `7bfc408636664df1e1bdc28ea148998783b9fe5c` | 5,494 (21.4 MB) | 6,922 | 0 | 109 | large multi-module, Kafka, 33 `@Scheduled`, 108 event listeners, custom composed annotations, Java 25 |
| synthetic fixture (mine) | n/a | 8 | 8 | 0 | 1 | interfaces, nested constants, static imports, meta-annotations, arrays, placeholders, `@Schedules`, Kafka/SQS, router functions, Quartz |

Nacos was added after the first thingsboard run showed that thingsboard uses only
literal paths, so it could not exercise constant resolution.

## 3. Results

### 3.1 Counts and timing (warm cache, second run)

| Repo | Prototype | Files | Wall | http | messaging | scheduled | event | async | other | Total |
|---|---|---|---|---|---|---|---|---|---|---|
| petclinic-rest | A regex | 87 | 0.08 s | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| petclinic-rest | B AST | 87 | 0.24 s | 1 | 0 | 0 | 0 | 0 | 0 | 1 |
| jhipster-sample | A regex | 81 | 0.10 s | 38 | 0 | 1 | 0 | 5 | 0 | 44 |
| jhipster-sample | B AST | 81 | 0.25 s | 38 | 0 | 1 | 0 | 5 | 0 | 44 |
| nacos | A regex | 3,039 | 3.9 s | 433 | 0 | 15 | 2 | 0 | 0 | 450 |
| nacos | B AST | 3,039 | 2.0 s (parse 1.6 s) | 433 | 0 | 15 | 2 | 0 | 0 | 450 |
| thingsboard | A regex | 5,494 | 6.9 s (first cold run 33 s) | 922 | 0 | 34 | 109 | 0 | 0 | 1,065 |
| thingsboard | B AST | 5,494 (5,483 parsed) | 3.3 s (parse 2.5 s, index 0.3 s, scan 0.2 s) | 922 | 0 | 33 | 108 | 0 | 0 | 1,063 |
| thingsboard | C javac parse-only | 5,494 (0 failures) | 4.8 s (parse 1.3 s, task setup 3.4 s) | 1,130 mapping/listener annotations counted | | | | | | |
| nacos | C javac parse-only | 3,039 (0 failures) | 2.9 s (parse 0.9 s) | 510 annotations | | | | | | |

Observations:

- The AST scanner is not slower than the regex one at this size: JavaParser parses
  21 MB of Java in 2.5 s on 5.5k files with a parallel stream; the Python scanner is
  bound by its own comment stripper and regexes. A cold file cache dominated the first
  regex run (33 s).
- Neither repository has a single `@KafkaListener`, `@JmsListener`, `@RabbitListener`
  or `@SqsListener`, `RouterFunction`, Quartz job or Spring Batch job, although
  thingsboard is Kafka-based. Its consumers are hand-written (`TbQueueConsumer`
  abstraction, `KafkaConsumer` wrapped in own classes). Annotation scanning alone
  gives zero messaging entry points for a messaging-heavy application. The
  synthetic fixture confirms both prototypes do detect the annotations when present.
- petclinic-rest: 37 operations in `openapi.yml`, 9 controllers implement
  `*Api` interfaces generated by `openapi-generator` (`interfaceOnly=true`) into
  `target/generated-sources/openapi`. Without a build there is no annotation in the
  source tree; both prototypes find only `RootRestControllerV1.redirectToSwagger`.
  This is a 97% false-negative rate for either approach on this style of codebase.

### 3.2 Agreement between A and B

| Repo | A | B | Exact agreement | Only A | Only B | After reading source |
|---|---|---|---|---|---|---|
| petclinic-rest | 1 | 1 | 1 | 0 | 0 | both right, both miss the 36 generated-interface endpoints |
| jhipster-sample | 44 | 44 | 44 | 0 | 0 | identical |
| nacos | 450 | 450 | 280 | 170 | 170 | B right in all 170 (verified by reading the constants) |
| thingsboard | 1,065 | 1,063 | 1,053 | 12 | 10 | B right in 9 (`@Scheduled` attributes), A right in 2 (parse failures), 1 cosmetic |
| fixture | 29 | 34 | 11 | 18 | 23 | B right in all; A has 11 unresolved paths, 1 wrong prefix, 1 false positive, 6 misses |

### 3.3 Disagreement root causes

**R1. Simple-name constant fallback picks the wrong definition (A wrong, 168 records, nacos).**
Nacos defines `ADMIN_PATH` six times in nested classes of `com.alibaba.nacos.ai.constant.Constants`
(`Constants.A2A.ADMIN_PATH = "/v3/admin/ai/a2a"`, `Constants.Agent.ADMIN_PATH = "/v3/admin/ai/agents"`, ...).
A cannot resolve `Constants.Agent.ADMIN_PATH` (it keys constants by file basename only), falls back to
the bare name and takes the first definition seen. 295 of 2,158 constant names in Nacos are defined
more than once (`TYPE` 17 times, `NAME` 8, `ADMIN_PATH` 6, `CONSOLE_PATH` 6).
Examples (A → B, B verified against `ai/src/main/java/com/alibaba/nacos/ai/constant/Constants.java:138,156,305,345,364`):
- `ai/.../controller/AgentAdminController.java:88` `/v3/admin/ai/a2a` → `/v3/admin/ai/agents`
- `ai/.../controller/PromptAdminController.java:95` `/v3/admin/ai/a2a` → `/v3/admin/ai/prompt`
- `ai/.../controller/AiCapabilityClientController.java:68` `/v3/client/ai/agents` → `/v3/client/ai/capabilities`
A did mark all 168 as `INFERRED`, which is the one thing that makes the regex approach survivable:
the value is wrong but the record says so. 14 controller files, 39% of Nacos HTTP paths, are affected.

**R2. Order-dependent eager constant evaluation (A UNKNOWN, 2 records, nacos).**
`AddressServerConstants.ADDRESS_SERVER_REQUEST_URL` (an interface field, line 68) is
`UtilsAndCommons.NACOS_SERVER_CONTEXT + UtilsAndCommons.NACOS_SERVER_VERSION + "/as"`; A evaluated it
while indexing, before `UtilsAndCommons.java` was read, and stored `UNKNOWN`. B resolves lazily and
yields `/nacos/v1/as/nodes` for `address/.../AddressServerClusterController.java:88,170`. Fixable in a
regex scanner (lazy evaluation), but it shows how each resolution rule becomes a hand-written mini
compiler in the regex approach.

**R3. `@Scheduled` attributes that are constants or non-string literals (B right, 9 records, thingsboard).**
A records the expression text (`TbClusterStore.TICK_INTERVAL_MINUTES`,
`RANDOM_DELAY_INTERVAL_MS_EXPRESSION`) and ignores `timeUnit`; B resolves them:
- `application/.../service/license/NonProductionUptimeService.java:43` → `fixedRate=5, timeUnit=MINUTES`
- `application/.../service/ttl/AgentAppEventCleanUpService.java:37` → `initialDelayString=#{T(org.apache.commons.lang3.RandomUtils).nextLong(0, ${sql.ttl.agent_app_events.execution_interval_ms:86400000})}`
- `application/.../service/ttl/ApiKeysCleanUpService.java:30` (same SpEL pattern; note the `initialDelayString` placeholder has no default while the `fixedDelayString` one has, a real finding the scanner should expose)
B's prototype marks the three integer-constant cases `UNKNOWN` because its evaluator lacks integer literals; the value it prints is nevertheless right. The real scanner needs numeric and enum evaluation.

**R4. Parser does not support the target's Java version (A right, 2 records, thingsboard).**
JavaParser rejects `_` as a reserved keyword in 11 files (Java 22 unnamed variables). Lost:
- `application/.../service/agent/bulk/DefaultAgentBulkActionProcessingService.java:128` `@Scheduled(fixedDelayString = "${queue.agent.bulk-ops-stuck-check-interval-ms:600000}")`
- `application/.../service/subscription/DefaultSubscriptionManagerService.java:142` `@EventListener` for `OtherServiceShutdownEvent`
The regex scanner does not care about syntax it does not understand. The javac Tree API on the installed JDK parsed all 5,494 files with 0 errors.

**R5. Class-level prefix declared on an interface or superclass (fixture; B right).**
Spring (5.1+) merges `@RequestMapping` from interfaces. `OrderController implements OrderApi` where
`OrderApi` carries `@RequestMapping(Paths.Orders.BASE)`: A emits `/export` and `/multi`; B emits
`/api/v1/orders/export` (`INFERRED`, via meta-annotation `@JsonGet`) and `/api/v1/orders/multi`.
A also prefixed nothing for a nested `@RestController` class (`/inner` instead of `/nested/inner`)
and emitted the nested class-level mapping itself as a bogus endpoint record.

**R6. Mappings declared on interfaces, inherited by implementations (fixture; B right).**
A reports `OrderApi.get` etc. on the interface only (flagged `on_interface`). B reports the interface
record and a projected `INFERRED` record per implementing class with `inherited_from`. In petclinic-rest
this is the only mechanism that could produce the 36 missing endpoints, and only if generated sources are present.

**R7. Nested-type and static-import constants (fixture + nacos; B right after a 5-line fix).**
`Paths.Orders.BASE`, `import static fx.api.Paths.Users.BY_ID`, interface constants without
`static final`: A yields `<Paths.Orders.BASE>/<Paths.Orders.BY_ID>` (UNKNOWN); B yields
`/api/v1/orders/{id}`. The first B version also failed on nested types; the fix was a scoped lookup in
the type index, which the regex approach has no equivalent for.

**R8. Attribute arrays and container annotations (fixture; B right).**
`@KafkaListener(topics = {TOPIC, "orders.updated"})`: A → `<TOPIC, "orders.updated">` UNKNOWN; B →
`orders.created,orders.updated` STATIC (resolving `TOPIC = "orders." + "created"`).
`@Schedules({@Scheduled(fixedRate = 10), @Scheduled(cron = "0 * * * * *")})`: A → `{}` UNKNOWN; B → both triggers.
`@RequestMapping(path = {"/search", "/find"}, method = {GET, POST})`: both right (one record per path).

**R9. Composed (meta-) annotations (both right on real code, B more complete).**
thingsboard defines `@AfterStartUp` and `@AfterContextReady` (`common/queue/.../util/`) as
meta-annotations of `@EventListener`; both prototypes picked them up (A via a regex over `@interface`
headers, B via the type index). In the fixture B carries the meta-annotation's default path and HTTP
method (`@JsonGet` → GET) but not the meta `@Scheduled` cron (`@HourlyJob` → empty trigger): a
prototype gap, not an approach limit.

**R10. Cosmetic: string escapes (1 record).** `RedirectController.java:17` path
`/{path:^(?!api$)...[^\\.]*}/**`: A keeps the source escape, B the runtime string. Either is fine if documented.

**Prototype bugs found and fixed during the spike (not approach differences):** B emitted
`GET,RequestMethod` for `method = RequestMethod.GET` (145 false disagreements on thingsboard before the
fix); B treated enum `timeUnit` as unresolvable; A reported simple-name fallbacks as STATIC.

### 3.4 Manually checked sample (thingsboard, B output)

- 30 random HTTP endpoints (`random.seed(42)`): 30/30 correct, including class-level + method-level
  concatenation (`@RequestMapping("/api/v1")` + `/{deviceToken}/claim`), `params` filters ignored
  correctly, and `ImageController.java:239` `IMAGE_URL + "/preview"` resolved to
  `/api/images/{type}/{key}/preview`. 0 false positives, 0 wrong paths. Regex agreed on all 30.
- All 33 `@Scheduled`: 33/33 correct values; the 3 with integer constants (`ACTIVATION_RETRY_INTERVAL_MINUTES`,
  `ENFORCEMENT_INTERVAL_MINUTES`, `TICK_INTERVAL_MINUTES`) were resolved (5, 5, 15) but flagged UNKNOWN by the
  prototype's evaluator (see R3). 30 of 33 are `fixedDelayString="${...}"` placeholders, 11 use SpEL
  random initial delays; a scanner that drops placeholders would lose almost all scheduler information.
- 10 random event listeners: 10/10 correct, 4 of them via the composed `@AfterStartUp`.
- Known false negatives in B: 2 (R4). Known false negatives in A: 0 on thingsboard; on nacos A has 168 wrong
  values + 2 unresolved (R1, R2). Estimated per-approach error on the HTTP kind:

| | thingsboard (literal style) | nacos (constant style) | petclinic (generated) |
|---|---|---|---|
| A regex, wrong or unresolved path | 0 / 922 | 170 / 433 (39%) | 36 / 37 missed |
| B AST, wrong or unresolved path | 0 / 922; 2 records missed by parser | 0 / 433 | 36 / 37 missed |

### 3.5 Output size (thingsboard, 1,063 records; tokens = bytes / 4)

| View | Bytes | ~Tokens | Lines | Content |
|---|---|---|---|---|
| full JSONL (all fields) | 409,083 | 102,270 | 1,063 | kind, file, line, cls, method, module, anno, level, status, http_method, path, trigger, event, ... |
| compact TSV | 189,342 | 47,335 | 1,064 | kind, file:line, Class.method, target, status |
| minimal TSV | 99,534 | 24,883 | 1,064 | kind, module, Class.method, target |
| per-class summary | 12,129 | 3,032 | 231 | class, module, kinds, count, common path prefix |
| per-package summary | 4,306 | 1,076 | 95 | package × kind counts |
| per-module summary | 487 | 121 | 18 | module × kind counts |

Nacos (450 records): 168,323 / 76,262 / 35,767 / 5,066 / 1,386 / 302 bytes for the same views.
Average full record is 385 bytes (~96 tokens); the `file` path alone is 35-40% of it because
`application/src/main/java/org/thingsboard/server/controller/` repeats on every line. Even the
compact TSV of one large repository (~47k tokens) is too big to load into a skill; the per-class
view (~3k tokens) plus filtered queries is the usable shape.

### 3.6 Incremental rescan

thingsboard, 114 non-test Java files changed in the last 20 commits (`git fetch --deepen=30`, then `git diff --name-only HEAD~20 HEAD -- '*.java'`):

| | Time | Result | Caveat |
|---|---|---|---|
| A regex, `--files` list | 0.19 s | 76 records | no cross-file constants for the subset (irrelevant on thingsboard, wrong on nacos) |
| B AST, file list only | 0.49 s + ~0.2 s JVM start | 76 records | same caveat: the global type/constant/interface index is not available |
| B AST, full rescan | 3.3 s | 1,063 | ~0.6 ms per file: a 50k-file monolith is ~30 s, still acceptable per commit |
| C javac, full parse | 4.8 s | | 3.4 s of it is `JavacTask` setup over 5,494 file objects; batching per module would cut it |

Conclusion: incremental scanning is cheap only if the index (type → file, constant values,
interface → implementations, meta-annotations) is persisted next to the atlas and files are re-indexed
by content hash. The dependency direction is known (a changed constants file invalidates every file
that imports it), so a correct incremental scan is "changed files + files importing a changed type".
For repositories up to ~10k files a full rescan is simpler and fast enough.

### 3.7 Hybrid pre-filter

A plain-text pre-filter over the annotation names (`@...Mapping|@Scheduled|@EventListener|...|@interface`)
selects 253 of 5,494 thingsboard files (4.6%) and 113 of 3,039 Nacos files (3.7%) in 1.0 s / 0.6 s
(Python; ripgrep is faster). Files that define String constants are 450 / 557. Parsing only
candidates + constant-defining files would cut AST work by ~85% but needs the transitive closure
for constants referenced across files; given that full parse is 1.3-2.5 s, the pre-filter is not
worth its complexity for repositories of this size. It is worth it for a ripgrep-based *candidate
discovery* step whose misses are measurable (R4-style: parse failures fall back to the regex result).

### 3.8 Kotlin

No Kotlin files in any of the four repositories (`*.kt` = 0). Handling if present:
- JavaParser and javac cannot parse Kotlin at all; the AST scanner would silently skip `.kt` files.
- The regex scanner works unchanged on Kotlin annotation syntax (`@GetMapping("/x") fun get()`), with
  the same resolution limits plus Kotlin-specific ones (`const val` in `companion object`, string
  templates, trailing lambdas). Accuracy would be roughly the nacos-style regex accuracy.
- A real Kotlin AST needs `kotlin-compiler-embeddable` (~60 MB) or `tree-sitter-kotlin`; neither is
  justified until a target with Kotlin appears. Recommendation: count `.kt` files in the manifest,
  scan them with the regex path, mark every record `INFERRED`, and raise a coverage-ledger warning.

## 4. Recommendation for the real scanner

**Language and parser.** Java, using the JDK's own javac Tree API (`com.sun.source.util.JavacTask.parse()`,
`TreeScanner`) rather than JavaParser:
- zero dependencies, so no Maven download at first run (the corporate-mirror failure in this spike is
  what a real user would hit) and a single-file `java Scanner.java` or a small jar vendored in the plugin;
- always supports the syntax of the JDK the target itself compiles with (JavaParser lost 11 files and
  2 entry points on Java 25 code; it lags each Java release by months);
- comparable speed (1.3 s parse for 5.5k files; task setup can be batched per module).
Keep JavaParser as the fallback only if the javac API's `--enable-preview` handling proves awkward;
in either case: **a file that fails to parse must produce a `UNKNOWN` file-level record and be
re-scanned by the regex path**, never dropped silently.

**What must be resolved for accuracy (all demonstrated to matter):**
1. Class-level + method-level concatenation, including the class-level prefix inherited from interfaces
   and superclasses (R5), nested `@RestController` classes.
2. Path, topic and cron constants: `static final String` in classes, implicit constants in interfaces,
   nested types (`Constants.Agent.ADMIN_PATH`), static imports, `+` concatenation, cross-file and
   lazily evaluated (R1, R2, R7). Resolution must be scoped (imports, package, enclosing types);
   never resolve by bare simple name across the repository.
3. `value` / `path` / `topics` / `queues` arrays: one record per element (R8).
4. Composed annotations defined in the repo, transitively (`@AfterStartUp` → `@EventListener`), carrying
   the meta-annotation's defaults (method, path, cron) (R9).
5. Mappings declared on interfaces and abstract classes, projected onto each implementation (R6).
6. `@Schedules` containers, numeric constants and `timeUnit` enums in `@Scheduled` (R3, R8).
7. Generated sources: if `target/generated-sources` or `build/generated` exist, scan them with
   `origin=GENERATED`; if they do not and a class implements an interface that cannot be found in the
   source tree while the pom declares `openapi-generator`/`swagger-codegen`, emit an `UNKNOWN` record
   per such class with the interface name and a manifest warning ("build required for N controllers").
8. Programmatic consumers and routes: `RouterFunction` beans (count the route calls), `@Bean` returning
   `Consumer<`/`Function<`/`Supplier<` (Spring Cloud Stream), `implements Job`/`QuartzJobBean`, Spring
   Batch `Job` beans, and library consumer types (`KafkaConsumer`, `MessageListenerContainer`,
   `SqsAsyncClient.receiveMessage`, own abstractions found by name pattern) as `INFERRED` consumer
   records, because a Kafka-based system like thingsboard otherwise reports zero consumers.

**Discovery status rules.**
- `STATIC`: literal, or constant resolved through an unambiguous scoped lookup; placeholders `${...}`
  and SpEL `#{...}` stay `STATIC` with the text verbatim plus `placeholder: true`.
- `INFERRED`: prefix taken from an interface/superclass; record projected onto an implementation;
  value from a meta-annotation default; programmatic consumer detected by pattern; Kotlin regex result;
  constant resolved through a wildcard import.
- `UNKNOWN`: expression not evaluable (method call, non-constant field, missing type): keep the
  expression text in angle brackets in `path`, e.g. `/api/<Unknown.PATH>`; parse failure of the file;
  generated interface not present.

**Output format.** Keep JSONL as the stored atlas (diffable, one record per entry point, 385 B/record),
with these fields: `id` (stable hash of `cls#method#anno#path`), `kind`, `subkind` (`GET`, `kafka`,
`cron`, `EventListener`, ...), `target` (path / topic / cron / event type), `module`, `cls`, `method`,
`file`, `line`, `anno`, `level` (`method|class`), `status`, `origin` (`SOURCE|GENERATED|KOTLIN`),
`inherited_from`, `via_meta`, `placeholder`, `params` (HTTP `params`/`consumes` filters, Kafka `groupId`,
`@Scheduled` `initialDelay`). Do not store the package-qualified class name and the file path both
in full; store `cls` as the FQN and derive the file from a separate `types.jsonl` (FQN → file, module)
to cut ~35% of bytes. Generate, at scan time, the per-module and per-class summaries measured above
(~3k tokens for thingsboard) and provide a query script (`atlas-query --kind http --module application
--path-prefix /api/device`) that prints the compact TSV projection; skills read summaries and query
results, never the full JSONL.

## 5. Risks

- **Parser lag vs. target Java version.** Any third-party parser will eventually reject new syntax
  (seen: Java 22 `_`). Mitigation: javac Tree API; regex fallback per failed file; parse-failure
  count in the manifest.
- **Generated code.** OpenAPI-generated controller interfaces hide 97% of endpoints in
  petclinic-rest from any source-only scanner. Mitigation: scan generated dirs when present, warn when
  absent; optionally parse `openapi.yml` operations as `INFERRED` endpoints.
- **Programmatic messaging.** Large systems (thingsboard) consume queues without listener
  annotations. The messaging table cannot rely on annotations; it needs library-call patterns and
  a "known abstractions" list per target, which is where `INFERRED` and the coverage ledger matter most.
- **Ambiguous resolution.** Bare-name constant lookup produced 168 silently plausible wrong paths in
  Nacos; the scanner must prefer `UNKNOWN` over a guess. Simple-name fallback must never be `STATIC`.
- **Output size.** 1k endpoints are ~100k tokens as JSONL and ~47k as TSV; skills must query, not load.
- **Kotlin and other JVM languages.** Unhandled by Java parsers; regex fallback with `INFERRED`.
- **Windows specifics.** Long paths (`core.longpaths`), decimal-comma locales in Java formatting,
  corporate Maven mirrors blocking dependency download. A zero-dependency scanner avoids the last one.
- **Incremental correctness.** File-list rescans without the global index give wrong constants
  (fine on thingsboard, wrong on nacos). Either persist the index or rescan fully (3-5 s per 5k files).

## 6. Files

- Report: `scratchpad/spike-empirical.md` (this file)
- Prototype A: `scratchpad/prototypes/regex_scan.py`
- Prototype B: `scratchpad/prototypes/ast-scan/` (`pom.xml`, `src/main/java/spike/AstScan.java`, built with `mvn -s ../mvn-settings.xml package`; run `java -cp "target/classes;target/deps/*" spike.AstScan <repo> <out.jsonl> [files.txt]`)
- Prototype C: `scratchpad/prototypes/javac-parse/JavacParse.java`
- Fixture: `scratchpad/prototypes/fixture/`
- Diff and size tools: `scratchpad/prototypes/diff_results.py`, `scratchpad/prototypes/sizes.py`
- Raw outputs: `scratchpad/out/<repo>.{regex,ast}.jsonl`, `scratchpad/out/thingsboard.ast.{full.jsonl,compact.tsv,minimal.tsv,by-module.tsv,by-package.tsv,by-class.tsv}`, parse-failure logs `scratchpad/out/*.ast.err`
