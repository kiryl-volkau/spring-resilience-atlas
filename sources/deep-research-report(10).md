# Claude Code Reliability-Audit Skill System for a Very Large Spring Application

## Executive conclusion

For the application you described—a very large Spring Boot codebase containing multiple business areas such as CRM, Billing, Resident, Daily Care, many controllers, queues, scheduled jobs, integrations, transactions, and probably years of accumulated architectural decisions—I would **not** build one “high availability audit” skill, and I would also **not** build twenty independent skills that each start by reading the whole repository.

The architecture I recommend is:

> **Build a persistent machine-readable atlas of the application once, update it incrementally, and let small specialized Claude Code skills query that atlas and open only the code needed to prove or disprove a particular reliability hypothesis.**

That recommendation is closely aligned with Anthropic's current context-engineering guidance. Anthropic recommends “just-in-time” retrieval for large information spaces: keep lightweight identifiers such as file paths and stored queries, then retrieve only the relevant material at runtime rather than loading the entire corpus into context. Claude Code itself follows this hybrid approach with persistent instructions plus filesystem search and targeted retrieval. citeturn23view4 Anthropic's work on long-running agents reaches a similar conclusion from another angle: attempting to do too much in a single context creates incomplete work and poor handoff; persistent artifacts plus incremental sessions perform better. citeturn22view3

So the system should conceptually look like this:

```text
                     HUGE SPRING APPLICATION
                              │
                              ▼
                ┌─────────────────────────┐
                │   deterministic scan    │
                │ grep / AST / build /    │
                │ OpenRewrite / metadata  │
                └────────────┬────────────┘
                             │
                             ▼
                 .reliability/atlas/
                 ├── modules
                 ├── domains
                 ├── entrypoints
                 ├── side-effects
                 ├── db-writes
                 ├── transactions
                 ├── queues
                 ├── schedulers
                 ├── external-clients
                 ├── async-boundaries
                 ├── observability
                 ├── deployment
                 └── migrations
                             │
          ┌──────────────────┼─────────────────┐
          ▼                  ▼                 ▼
     Billing slice      Resident slice      CRM slice
          │                  │                 │
          ▼                  ▼                 ▼
      specialized reliability audit skills
          │
          ▼
      critical-flow evidence packets
          │
          ▼
      verifier / deduplicator
          │
          ▼
      engineering backlog
```

The **atlas is the optimization layer**. The skills are the reasoning layer.

I would target roughly **twenty-five to thirty narrow skills eventually**, but only about **twelve to fourteen are necessary for the first useful version**.

The core philosophical unit should not be a Java class, controller, or package. It should be a **business flow**:

```text
external trigger
    ↓
entry point
    ↓
validation/authentication
    ↓
transaction
    ↓
state mutation
    ↓
durable/async boundary
    ↓
downstream processing
    ↓
external effects
    ↓
observable outcome
    ↓
recovery mechanism
```

For example:

```text
POST /billing/webhook
        ↓
BillingWebhookController
        ↓
StripeWebhookService
        ↓
save EventReceipt
        ↓
SQS billing-events
        ↓
BillingEventListener
        ↓
InvoiceService
        ↓
DB transaction
        ↓
PaymentProviderClient
        ↓
invoice status update
```

A good skill does not say:

> “This method uses SQS. Looks reliable.”

It should answer:

> The provider can receive HTTP 200 only after the event has crossed a durable boundary.  
> SQS is at-least-once, therefore duplicate consumption is possible.  
> The consumer uses `providerEventId` as a durable idempotency key.  
> The DB mutation is committed before message acknowledgement.  
> A poison event reaches a DLQ after the configured attempts.  
> There is an alert on DLQ depth and message age.  
> A runbook exists to redrive or reconcile the event.  
> Therefore this failure mode is protected.

For standard SQS queues, AWS explicitly documents at-least-once delivery and the possibility of duplicate delivery; visibility timeout controls temporary invisibility during processing, and DLQs/redrive are explicit recovery mechanisms. citeturn25search6turn25search0turn25search4turn25search7 That means a skill that merely finds “we use SQS” is nowhere near sufficient.

One clarification from your prompt: I could not find a relevant engineering source named **“Synchron Tropic.”** In the Claude Code context I assume you meant **Anthropic**, so the design below is based heavily on Anthropic's first-party Claude Code guidance.

## What the research changes about the design

The most important result of the research is that **the expensive work and the intelligent work should be separated**.

Claude should not spend thousands of tokens repeatedly rediscovering things that a deterministic script can discover in seconds.

For example, finding all of these is primarily a machine problem:

```text
@Controller
@RestController
@RequestMapping
@SqsListener
@KafkaListener
@Scheduled
@Async
@Transactional
@TransactionalEventListener
ApplicationEventPublisher
CompletableFuture
ExecutorService
RestTemplate
WebClient
FeignClient
JdbcTemplate
JpaRepository
Flyway
Liquibase
Retry
CircuitBreaker
Bulkhead
```

Understanding whether one particular combination creates a production failure is the reasoning problem.

Anthropic specifically recommends filtering and transforming large datasets before they enter model context. Its MCP context-efficiency work demonstrates the general design principle of loading only needed tools/data and preprocessing large results rather than dumping them all into context. citeturn22view4 Claude Code skills themselves support the same pattern: a skill body is loaded only when used, and skills can have supporting files rather than forcing all instructions into persistent context. citeturn22view0

### The atlas should be a real artifact

I would create something like:

```text
.reliability/
  atlas/
    manifest.json
    domains.jsonl
    modules.jsonl
    entrypoints.jsonl
    beans.jsonl
    transactions.jsonl
    persistence.jsonl
    message-producers.jsonl
    message-consumers.jsonl
    scheduled-jobs.jsonl
    async-execution.jsonl
    external-clients.jsonl
    application-events.jsonl
    side-effects.jsonl
    observability.jsonl
    infrastructure.jsonl
    migrations.jsonl
    critical-flow-candidates.jsonl

  flows/
    billing/
    resident/
    crm/
    daily-care/

  findings/
  coverage/
```

SQLite would arguably be even better once this gets large:

```text
.reliability/atlas.db
```

Then Claude can ask questions such as:

```sql
SELECT *
FROM entrypoints
WHERE domain = 'billing'
  AND kind = 'webhook';
```

or:

```sql
SELECT *
FROM flows
WHERE writes_database = true
  AND sends_message = true;
```

or:

```sql
SELECT *
FROM async_boundaries
WHERE durable = false;
```

The precise storage format is your choice. The architectural point is that **Claude consumes references and evidence, not an entire monolith**.

Every atlas build should be associated with at least:

```text
repository commit SHA
build module
source file
symbol
relevant annotation
domain candidate
discovery mechanism
```

Then incremental scans can invalidate only affected slices.

### Domain discovery must happen before reliability discovery

Your CRM/Billing/Resident/Daily Care example is extremely important.

A giant monolith often has at least three overlapping structures:

```text
technical structure
controller → service → repository

business structure
billing → resident → CRM → daily care

runtime structure
HTTP → transaction → event → queue → scheduler → external API
```

The audit needs all three.

Spring Modulith is worth investigating here even if you do **not** plan to convert the application into a “modulith.” It can derive a model of logical Spring application modules, verify module cycles and illegal internal access, and its `Documenter` can produce C4/UML relationships and a module canvas listing relevant beans, aggregate roots, published/listened events, and configuration properties. citeturn20search8turn20search0turn20search4

It will not magically understand that `ResidentBalanceAdjustmentService` belongs to a financially critical workflow, so your own domain inference is still necessary. But it is potentially excellent deterministic input for the atlas.

The domain cartographer should use signals such as:

```text
package names
controller route prefixes
class names
database tables
JPA entities
repositories
configuration namespaces
queues/topics
events
Flyway/Liquibase migrations
external API clients
cross-package dependencies
test packages
git ownership/history
```

It should produce **confidence**, not pretend it knows the truth:

```json
{
  "domain": "Billing",
  "confidence": 0.93,
  "packages": [
    "com.org.billing",
    "com.org.payments"
  ],
  "crossDomainDependencies": [
    "Resident",
    "CRM"
  ],
  "uncertainClasses": [
    "ResidentFinancialProfileService"
  ]
}
```

### A critical-flow tracer should be the center of the system

This is probably the single most important skill.

It receives something narrow:

```text
/billing/webhook
```

or:

```text
ResidentService.dischargeResident()
```

or:

```text
queue = invoice-processing
```

Then it follows reachable code and records **boundaries**, rather than reading everything.

The algorithm I would instruct Claude to follow is:

```text
Start at entry point
      │
      ▼
follow direct business calls
      │
      ├── @Transactional? ───── record
      │
      ├── repository write? ─── record
      │
      ├── publish event? ────── record
      │
      ├── SQS/Kafka send? ───── record
      │
      ├── async fork? ───────── record
      │
      ├── scheduler? ────────── record
      │
      ├── external HTTP? ────── record
      │
      └── filesystem/cache? ─── record
      │
      ▼
stop at understood system boundary
```

Only if a boundary is ambiguous should it expand another level.

This is exactly the type of progressive disclosure Anthropic recommends for large-context work: maintain lightweight references, progressively discover relevant information, and avoid loading irrelevant data. citeturn23view4

### Findings should be evidence packets, not opinions

Every reliability finding should have the same contract:

```json
{
  "ruleId": "DUR-001",
  "domain": "Billing",
  "flow": "Provider webhook -> billing event",
  "severity": "CRITICAL",
  "confidence": "HIGH",

  "failureScenario": "Process terminates after returning 200 but before work is durably recorded.",

  "entrypoint": {
    "symbol": "BillingWebhookController.handle",
    "file": "..."
  },

  "evidence": [
    {
      "symbol": "BillingWebhookController.handle",
      "reason": "HTTP 200 returned"
    },
    {
      "symbol": "BillingService.processAsync",
      "reason": "critical work delegated to in-memory executor"
    }
  ],

  "existingMitigations": [],

  "blastRadius": {
    "businessDomains": ["Billing"],
    "effects": ["payment event may be lost"]
  },

  "recommendation": "Durably persist or enqueue accepted work before acknowledgement.",

  "verification": [
    "Kill process immediately after HTTP acknowledgement.",
    "Accepted event must eventually be processed.",
    "Duplicate delivery must not duplicate the business effect."
  ],

  "dedupeKey": "billing-webhook-nondurable-handoff"
}
```

Two fields should always remain separate:

**Severity** asks: “How bad is this if true?”

**Confidence** asks: “How sure are we that this is really a bug?”

That makes an AI-generated backlog vastly more trustworthy.

## Recommended skill catalog

I would divide the skills into four families: **mapping**, **reliability**, **observability/operations**, and **verification/synthesis**.

Not all of these should run on every audit.

### Foundation and codebase-mapping skills

These skills reduce the cost of every later skill.

| Skill | What it should do | Typical scope |
|---|---|---|
| `repo-atlas-bootstrap` | Build/update the deterministic repository inventory | Whole repo, incremental after first run |
| `domain-cartographer` | Infer CRM/Billing/Resident/Daily Care-style functional boundaries | Atlas first, source only for ambiguity |
| `entrypoint-catalog` | Inventory HTTP endpoints, webhooks, queue consumers, scheduled jobs, batch jobs, event listeners | Whole repo deterministically |
| `side-effect-catalog` | Inventory DB writes, queue sends, HTTP calls, email/payment calls, cache/filesystem state | Whole repo deterministically |
| `dependency-config-atlas` | Discover databases, queues, external clients, executors, pools, resilience libraries, config properties | Whole repo deterministically |
| `architecture-boundary-audit` | Find module cycles, unexpected cross-domain dependencies and shared hotspots | Module graph |
| `critical-flow-discovery` | Rank candidate business flows worth deep auditing | Atlas + business heuristics |
| `critical-flow-tracer` | Produce a detailed execution/effect graph for one business flow | One entrypoint or flow |

**`repo-atlas-bootstrap`**

This should be mostly scripts, not free-form LLM exploration.

It should answer:

```text
What modules exist?
Which Spring Boot version?
Which Java version?
What controllers?
What listeners?
What scheduled tasks?
What repositories?
What external clients?
What queues?
What migrations?
What Kubernetes/Terraform/Helm manifests?
Which observability libraries?
Which resilience libraries?
```

Spring Boot Actuator can also provide useful runtime corroboration when a representative environment is available. Spring Boot exposes production-management endpoints, including endpoint mappings and scheduled tasks. citeturn18search2turn18search22turn18search18

The skill should clearly distinguish:

```text
STATICALLY DISCOVERED
RUNTIME CONFIRMED
INFERRED
UNKNOWN
```

That distinction will prevent a lot of bullshit findings.

**`domain-cartographer`**

The purpose is not merely drawing packages.

It should answer:

```text
What are the actual business subsystems?
Where are their boundaries?
Who owns which DB state?
Which domains communicate synchronously?
Which communicate with events?
Which "shared" classes create hidden coupling?
```

Spring Modulith can provide structural input here, particularly application-module dependencies and documentation artifacts. citeturn20search0turn20search4

**`critical-flow-discovery`**

You probably cannot deep-trace ten thousand endpoints.

This skill ranks them.

A possible score:

```text
business criticality
+ money movement
+ resident/customer state mutation
+ number of DB writes
+ async boundaries
+ external calls
+ queue boundaries
+ scheduled execution
+ cross-domain fan-out
+ transaction count
+ historical churn
+ production error rate
```

Something like:

```text
Billing provider webhook               98
Resident discharge                     94
Medication/daily-care update           91
Invoice generation scheduled job       88
CRM contact read endpoint              31
Static lookup endpoint                  8
```

The ranking is a heuristic, so the output should expose *why* each flow received its score rather than presenting the score as objective truth.

**`critical-flow-tracer`**

This is the deep reasoning worker.

Its output should be a compact sequence model:

```text
[External webhook]
      │
      │ POST
      ▼
WebhookController
      │
      │ validate signature
      ▼
WebhookService
      │
      ├── DB: webhook_receipt INSERT
      │       transaction T1
      │
      ├── SQS send
      │
      ▼
return HTTP 200

SQS
 │
 ▼
InvoiceListener
 │
 ├── DB transaction T2
 ├── external provider API
 └── invoice update
```

Everything else can consume that artifact.

### Reliability and high-availability skills

These are the skills that find the failure modes you originally described.

| Skill | Core question |
|---|---|
| `durable-handoff-audit` | Can acknowledged work disappear if the process dies? |
| `transaction-dual-write-audit` | Can one side of DB + messaging/external state succeed while the other fails? |
| `messaging-reliability-audit` | Are consumer acknowledgement, visibility, retry, DLQ and ordering correct? |
| `idempotency-retry-audit` | Is every possible retry safe? |
| `async-execution-audit` | Is critical work stranded in JVM memory? |
| `scheduler-cluster-safety-audit` | Are scheduled tasks safe with multiple replicas and restarts? |
| `external-dependency-resilience-audit` | Can one slow dependency exhaust the application? |
| `replica-statefulness-audit` | Does correctness depend on one JVM/pod? |
| `database-consistency-audit` | Are transaction/concurrency assumptions actually valid? |
| `schema-event-compatibility-audit` | Can old and new versions safely coexist during rollout? |
| `shutdown-deployment-audit` | Can pods terminate/redeploy without dropping work or traffic? |
| `recovery-reconciliation-audit` | Can stranded/unknown work be detected and repaired? |

**`durable-handoff-audit`**

This directly covers your webhook example.

It searches for moments where the system changes from:

```text
"I have received this operation"
```

to:

```text
"I promise not to lose this operation."
```

Red flags include:

```java
return ResponseEntity.ok();
```

before a durable write, or:

```java
executor.submit(...);
return OK;
```

or:

```java
@Async
public void process(...)
```

after the requester has already been acknowledged.

It should audit:

```text
HTTP acknowledgement
webhook acknowledgement
queue acknowledgement
batch checkpoint
scheduler progress marker
event publication
```

The rule should be:

> **Acknowledged critical work must have crossed a durable recovery boundary.**

That boundary could be SQS, Kafka, a committed DB record, an outbox, or another durable mechanism. It does not have to be SQS.

**`transaction-dual-write-audit`**

This looks for flows like:

```java
@Transactional
public void createInvoice(...) {
    repository.save(invoice);
    sqs.sendMessage(...);
}
```

or:

```java
repository.save(entity);
externalCrm.update(entity);
```

The problem is not “SQS is bad.” The problem is that a local database transaction cannot magically make a second external system atomic.

AWS explicitly defines the transactional outbox pattern as a solution to the dual-write problem where one logical operation both writes a database and publishes an event/message; failure of one side can otherwise produce inconsistent state. citeturn24search1

The skill should detect existing mitigations before filing a finding:

```text
transactional outbox
CDC
durable event publication registry
reconciliation process
idempotent downstream consumer
business compensating workflow
```

It should not blindly recommend an outbox for everything.

**`messaging-reliability-audit`**

This should understand each queue/consumer as a mini protocol.

For SQS:

```text
producer
   ↓
queue retention
   ↓
receive
   ↓
visibility window
   ↓
processing
   ↓
ack/delete
   ↓
retry
   ↓
DLQ
   ↓
redrive / reconciliation
```

Standard SQS queues provide at-least-once delivery, so duplicate delivery is part of the contract. citeturn25search3turn25search6 The visibility timeout only hides a received message temporarily while processing is underway. citeturn25search0 A DLQ is specifically intended to receive messages that repeatedly fail processing, and AWS supports redrive from a DLQ back to a processing destination. citeturn25search4turn25search7

Checks should include:

```text
ack/delete semantics
exceptions swallowed by listener
visibility timeout versus processing duration
visibility extension
consumer concurrency
batch partial failure
poison message behavior
max receive count
DLQ configured
DLQ retention
DLQ alarm
redrive procedure
message schema compatibility
duplicate handling
ordering assumptions
```

One subtle operational rule: SQS retention is finite—default four days and configurable up to fourteen days—so “the queue guarantees we never lose work” is not an unlimited guarantee. citeturn25search2turn25search8

**`idempotency-retry-audit`**

This is one of the highest-value skills.

It should search not just for retry annotations but for **unknown-result states**:

```text
send request
      ↓
remote system performs operation
      ↓
network breaks before response
      ↓
caller sees timeout
      ↓
DID IT HAPPEN?
```

AWS explicitly notes that after a timeout, the result of a distributed request can be **unknown**—the operation may or may not have occurred. citeturn24search21 This is why retry and idempotency cannot be audited independently. AWS also recommends timeouts plus controlled retries/backoff/jitter and separately discusses idempotent API design as a prerequisite for safe automated retry behavior. citeturn24search0turn24search6

The skill should recognize idempotency patterns such as:

```text
provider event ID unique constraint
idempotency key
processed_message table
natural business key
conditional insert/update
optimistic version check
deduplication store
reconciliation-before-retry
```

and distinguish them from:

```text
"we think SQS won't duplicate it"
```

**`async-execution-audit`**

Search for:

```text
@Async
CompletableFuture
ExecutorService
TaskExecutor
ForkJoinPool
new Thread
application events with async multicaster
```

Then ask:

```text
Is this best effort?
Or is this required business work?
```

A metrics flush can disappear.

A “charge customer” action probably cannot.

That distinction prevents useless findings.

**`scheduler-cluster-safety-audit`**

This should inventory all scheduled operations and ask:

```text
What happens with 2 pods?
What happens with 20 pods?
What happens when the pod dies halfway?
What happens if the job doesn't run for an hour?
What happens if it runs twice?
```

It should inspect:

```text
@Scheduled
Quartz
ShedLock or equivalent
DB advisory locks
leader election
work claiming
fencing/version tokens
checkpoint state
```

A lock alone is not proof of correctness. Distributed leader/lease designs have failure scenarios involving network delay, pauses and lease expiry, so side effects still need careful coordination. citeturn24search18

**`external-dependency-resilience-audit`**

For each client:

```text
CRM
payment provider
email
document service
identity provider
third-party API
internal service
```

inspect:

```text
connect timeout
read/response timeout
overall deadline
retry policy
retryable exceptions/statuses
exponential backoff
jitter
max attempts
circuit breaker
bulkhead/concurrency limit
connection pool
fallback
rate limit
```

A particularly important rule is:

> Retry is not resilience if retry multiplies the overload.

AWS's Builders' Library explicitly discusses retries/backoff/jitter and also warns that retries consume additional concurrency when a dependency is already failing; dependency isolation and retry suppression are used to prevent cascading exhaustion. citeturn24search0turn24search12

**`replica-statefulness-audit`**

This finds assumptions such as:

```java
static Map<String, Job> jobs
```

```text
local filesystem
local in-memory cache as source of truth
sticky-session dependency
JVM-local lock protecting shared data
local sequence generation
single-pod-only initialization
```

The invariant is:

> Killing an arbitrary application replica must not destroy unique business state or invalidate system-wide coordination.

**`database-consistency-audit`**

This skill should look for:

```text
lost-update risk
read-modify-write sequences
missing uniqueness constraints
optimistic locking
pessimistic locking
transaction isolation assumptions
very long transactions
remote HTTP inside DB transactions
cross-domain DB writes
retry around transactions
```

It should reason about business invariants rather than merely reporting annotations.

Example:

```text
Two workers process the same resident adjustment.
Both read balance=100.
Both write balance=80.
Expected balance=60.
Actual balance=80.
```

That is far more useful than “entity lacks `@Version`.”

**`schema-event-compatibility-audit`**

For zero-downtime deployment, schema compatibility needs explicit analysis.

It should examine:

```text
Flyway/Liquibase changes
column removal
column rename
constraint tightening
enum changes
default changes
data backfills
message/event payload changes
consumer schema changes
```

And model this interval:

```text
OLD APP ─────┐
             ├── same DB/schema/message ecosystem
NEW APP ─────┘
```

If old and new code cannot operate simultaneously, rolling deployment alone cannot save you.

The preferred pattern for incompatible DB evolution should usually be some form of:

```text
expand
   ↓
deploy compatible code
   ↓
migrate/backfill
   ↓
switch behavior
   ↓
contract
```

**`shutdown-deployment-audit`**

This should combine application and Kubernetes evidence:

```text
Spring shutdown config
HTTP server drain
SQS consumer shutdown
executor shutdown
terminationGracePeriodSeconds
preStop if present
readiness
liveness
startup probes
replica count
RollingUpdate
maxUnavailable
maxSurge
PodDisruptionBudget
topology distribution
```

Kubernetes readiness failure removes a Pod from matching Service traffic, while liveness/startup failures can lead to container restart. citeturn24search8 Kubernetes Deployments support rolling replacement of pods, but the actual safety depends on your workload configuration and available capacity rather than the mere word “RollingUpdate.” citeturn24search14turn24search5 PodDisruptionBudgets can constrain some voluntary disruptions, although Kubernetes explicitly notes that not every disruption is covered by a PDB. citeturn24search2turn24search20

The acceptance test should be much stronger than config inspection:

```text
continuous traffic
+
rolling release
+
queue activity
+
in-flight work
=
no unacceptable loss or duplicate business effect
```

**`recovery-reconciliation-audit`**

This skill asks a question scanners usually ignore:

> When every automatic mechanism has already failed, how does the system recover?

For each critical flow:

```text
Can failed work be listed?
Can it be replayed?
Can we determine if an external side effect happened?
Can operators reconcile source-of-truth state?
Can DLQ events be redriven?
Can redrive itself duplicate effects?
Is there an audit trail?
```

For financial or other irreversible side effects, the difference between:

```text
retry()
```

and:

```text
query provider state → compare → reconcile → retry if safe
```

is enormous.

### Observability and operational skills

These are necessary because a system that technically retries but nobody can tell that it has been stuck for three days is not operationally reliable.

| Skill | Core question |
|---|---|
| `observability-coverage-map` | Can we observe the health of every critical business flow? |
| `trace-continuity-audit` | Can one operation be followed across HTTP, async and messaging boundaries? |
| `sli-alert-audit` | Are important failure modes measurable and actionable? |
| `logging-diagnostics-audit` | Could an engineer reconstruct what happened from logs? |
| `production-evidence-correlator` | Do actual production traces/errors validate the static risk? |
| `runbook-operability-audit` | When an alert fires, is there a defined recovery action? |

**`observability-coverage-map`**

This should not ask:

> “Do we use Micrometer?”

It should produce a matrix:

| Critical flow | Logs | Metrics | Traces | Business outcome | Alert | Recovery |
|---|---:|---:|---:|---:|---:|---:|
| Payment webhook | ✓ | ✓ | partial | ✓ | ✓ | ✓ |
| Resident discharge | ✓ | ✗ | partial | ✗ | ✗ | ✗ |
| Daily-care import | ✓ | ✓ | ✗ | ✓ | ✗ | partial |
| Invoice scheduler | partial | ✓ | ✗ | ✗ | ✓ | ✗ |

Spring Boot's production-ready features expose health and metrics capabilities, while its current observability stack has integration for metrics/tracing and OpenTelemetry. citeturn18search2turn18search10turn18search14 OpenTelemetry's Spring Boot starter currently supports Spring Boot 3.1+, which is directly relevant if your “3.1” means Spring Boot 3.1. citeturn20search1

A key distinction:

```text
technical telemetry
CPU = 40%
HTTP 500 = 0.1%

vs.

business telemetry
webhooks accepted = 1000
webhooks completed = 997
oldest unfinished webhook = 42 min
payments unknown = 2
DLQ messages = 1
```

For the audit system, the second category is often more valuable.

**`trace-continuity-audit`**

For a critical flow, test whether the same operation can be correlated through:

```text
incoming HTTP
    ↓
controller
    ↓
service
    ↓
DB
    ↓
queue producer
    ↓
queue consumer
    ↓
outbound HTTP
```

OpenTelemetry's Spring Web MVC instrumentation can create server spans and propagate incoming trace context, but that does not prove that every custom async/event/messaging boundary in your application preserves the context you need. citeturn20search5

Therefore this skill should search for:

```text
trace context
correlation ID
event ID
business entity ID
tenant/org ID
provider event ID
message attributes
```

and explicitly mark gaps.

**`sli-alert-audit`**

For each critical flow, I would require consideration of:

```text
availability
latency
business success ratio
retry rate
queue age
queue depth
DLQ depth
consumer failure rate
external dependency errors
saturation
reconciliation backlog
```

For SQS specifically, AWS documents CloudWatch alarms on visible DLQ messages, so “we have a DLQ” should not count as complete coverage without some detection mechanism. citeturn25search18

**`logging-diagnostics-audit`**

Look for:

```text
swallowed exceptions
log.error("something failed")
missing business identifiers
logs with no queue/event ID
logs where retry attempt is invisible
PII/secrets
same error logged ten times at different layers
lack of failure outcome
```

A good standard is:

> An engineer given a resident/order/invoice/event identifier should be able to reconstruct the important execution path without reading the source code first.

**`production-evidence-correlator`**

This is where existing tools can save you a lot of development.

If your organization uses Sentry, Sentry has an official MCP integration for Claude Code that exposes production debugging/error information to the coding agent. citeturn20search2

If you use Datadog, Datadog similarly provides an MCP server that can expose observability data to Claude Code and other MCP clients. citeturn20search3

This skill should take a static finding:

```text
"CustomerProfileClient has no obvious timeout"
```

and investigate production evidence:

```text
Do requests actually hang here?
What is p99 latency?
Is it in recent incident traces?
How many thread-pool exhaustion events correlate with this dependency?
```

That changes prioritization dramatically.

For privacy and context efficiency, I would attach production MCP servers only to this dedicated subagent. Claude Code supports defining MCP servers at the subagent level so their tools can remain out of the main conversation and disconnect when that agent finishes. citeturn23view3

**`runbook-operability-audit`**

For every high-severity mode:

```text
Detection
→ diagnosis
→ immediate mitigation
→ recovery
→ reconciliation
→ validation
```

should exist somewhere.

Example:

```text
Alert:
BillingEvent DLQ > 0

Runbook:
1. inspect providerEventId and failure cause
2. determine whether charge happened
3. fix permanent payload/config issue
4. verify idempotency guard
5. redrive
6. confirm invoice state
```

Without that, a DLQ can simply become a durable graveyard.

### Verification and synthesis skills

These should run after candidate findings exist.

| Skill | Job |
|---|---|
| `failure-injection-test-designer` | Turn a finding into kill/retry/network/failover tests |
| `finding-verifier-deduper` | Re-check evidence and eliminate false positives |
| `backlog-task-generator` | Convert validated finding into an engineering ticket |
| `coverage-ledger` | Record what was and was not actually analyzed |
| `spring-boot-upgrade-readiness` | Independently assess the 3.1 → 3.5 migration |

**`failure-injection-test-designer`**

Given:

```text
Webhook acknowledged before asynchronous completion
```

generate:

```text
1. send event
2. observe HTTP 200
3. kill JVM at target point
4. restart
5. assert eventual business result
6. resend same event
7. assert result remains single
```

Other cases:

```text
Kill consumer after DB commit, before queue acknowledgement.
Kill consumer before DB commit.
Make dependency timeout after remote side effect.
Pause PostgreSQL.
Return 503 for twenty seconds.
Expire queue visibility while worker is running.
Deploy while long-running handlers execute.
Run scheduler on four replicas.
Send poison message.
Redrive the same DLQ event twice.
```

Testcontainers is a natural fit for integration-level dependency tests; its JUnit 5 integration supports lifecycle-managed containers. citeturn19search24 For network-fault testing, Testcontainers' ecosystem includes Toxiproxy support, making controlled latency/disconnect scenarios practical without depending on production chaos experiments. citeturn19search7

**`finding-verifier-deduper`**

This one is essential.

Finding generation should be high-recall.

Verification should be high-precision.

The verifier gets only:

```text
finding
evidence references
flow artifact
relevant 3–10 files
configuration slice
```

Then tries to disprove the finding.

For example:

```text
Candidate:
"webhook isn't idempotent"

Verifier discovers:
DB unique constraint(provider_event_id)

Result:
REJECTED
existing mitigation provides durable deduplication
```

Or:

```text
Candidate A:
12 Billing controllers perform unsafe in-memory async work.

Candidate B:
8 Resident controllers do the same.

Root cause:
Shared AsyncDomainEventDispatcher.

Result:
one architectural task + affected-flow inventory
```

Anthropic's own Code Review product uses a similar conceptual progression: parallel candidate discovery, verification, deduplication/ranking, then reporting. citeturn23view5 That architecture is particularly appropriate for an AI reliability scanner because false positives will otherwise destroy developer trust.

**`backlog-task-generator`**

Only validated findings should reach this skill.

A ticket should contain:

```text
Title
Domain
Affected critical flow
Severity
Confidence

Failure scenario

Timeline:
T0 ...
T1 ...
T2 process dies
T3 incorrect state

Evidence:
file/symbol references

Existing mitigations

Why they are insufficient

Remediation options

Preferred option

Acceptance criteria

Failure-injection verification

Observability acceptance criteria
```

This is what turns your system from “AI architecture reviewer” into an actual engineering instrument.

**`coverage-ledger`**

This might be one of the least glamorous and most important skills.

You need to know the difference between:

> “Claude found no problems.”

and:

> “Claude analyzed 36 of 420 critical-flow candidates.”

Example:

```json
{
  "commit": "a91d...",
  "domain": "Billing",

  "entrypoints": 417,
  "classified": 417,

  "criticalFlowCandidates": 34,
  "deepAudited": 29,
  "remaining": 5,

  "profilesExecuted": [
    "durability",
    "messaging",
    "idempotency",
    "external-dependencies",
    "observability"
  ],

  "notExecuted": [
    "failure-injection"
  ]
}
```

Never generate a fake:

```text
Reliability score: 94%
```

unless the denominator is well-defined.

**`spring-boot-upgrade-readiness`**

Keep your 3.1 → 3.5 upgrade separate from the reliability audit.

OpenRewrite currently publishes a `UpgradeSpringBoot_3_5` recipe intended to migrate Spring Boot applications toward the 3.5 line, including build/API changes. citeturn19search0

This skill can combine:

```text
OpenRewrite migration report
dependency compatibility
deprecated APIs
Actuator changes
observability changes
test failures
custom Spring integrations
```

but should not contaminate reliability findings with ordinary upgrade noise.

## Claude Code packaging and execution model

I would package the complete thing as an internal **Claude Code plugin**, not paste giant instructions into `CLAUDE.md`.

Claude Code's own docs distinguish persistent `CLAUDE.md` context from skills, subagents, MCP, hooks, code intelligence and plugins. Skills load reusable workflows/knowledge when needed, while subagents run isolated loops and return summaries. citeturn22view2 Skill bodies specifically load only when a skill is used, which is exactly what you want for a large reliability rule library. citeturn22view0

A conceptual package:

```text
reliability-audit/
│
├── .claude-plugin/
│   └── plugin.json
│
├── agents/
│   ├── atlas-builder.md
│   ├── domain-cartographer.md
│   ├── flow-tracer.md
│   ├── reliability-reviewer.md
│   ├── observability-reviewer.md
│   └── finding-verifier.md
│
├── skills/
│   ├── repo-atlas-bootstrap/
│   │   ├── SKILL.md
│   │   ├── references/
│   │   └── scripts/
│   │
│   ├── critical-flow-tracer/
│   │   ├── SKILL.md
│   │   └── references/
│   │
│   ├── messaging-reliability-audit/
│   ├── idempotency-retry-audit/
│   ├── transaction-dual-write-audit/
│   ├── observability-coverage-map/
│   └── ...
│
├── scripts/
│   ├── build-atlas
│   ├── query-atlas
│   ├── changed-slices
│   ├── rank-flows
│   └── validate-finding
│
├── rules/
│   ├── durability/
│   ├── messaging/
│   ├── transactions/
│   ├── deployment/
│   ├── observability/
│   └── recovery/
│
└── schemas/
    ├── atlas.schema.json
    ├── flow.schema.json
    └── finding.schema.json
```

Claude Code supports skills with supporting files, which is much better than putting a huge rulebook into every skill's prompt. citeturn22view0

### Keep `CLAUDE.md` tiny

I would put only universally useful facts there:

```text
# Reliability audit

Atlas location:
.reliability/atlas.db

Query:
./reliability/scripts/query-atlas

Never claim full coverage without reading coverage ledger.

All findings require:
- concrete failure scenario
- code/config evidence
- existing mitigation analysis
- severity
- confidence
- verification procedure

Do not modify production code during audit unless explicitly requested.
```

Everything procedural should remain in skills.

This follows Claude Code's extension model: persistent context belongs in `CLAUDE.md`; reusable workflows belong in skills. citeturn22view2turn22view0

### Use forked context for deep audits

A deep reliability analysis is a very good candidate for:

```yaml
context: fork
```

Claude Code supports running skills in a forked subagent context, including an `Explore`-style research workflow. citeturn23view2turn22view1

So:

```text
main coordinator
      │
      ├── flow A → isolated audit
      │
      ├── flow B → isolated audit
      │
      └── flow C → isolated audit
      │
      ▼
small structured summaries
```

is preferable to:

```text
one context
+ 300 source files
+ 50 flows
+ thousands of grep lines
+ production logs
+ Kubernetes YAML
+ rulebook
```

### Parallelize independent flows, not tightly coupled code traversal

Anthropic's research on multi-agent systems is useful here: parallel subagents are effective when work decomposes cleanly, but tasks with substantial shared context and dependency between agents are less naturally parallel. Anthropic explicitly notes that coding tasks often have fewer truly parallelizable pieces than research. citeturn18search9

Therefore good parallel units are:

```text
Billing webhook flow
Resident discharge flow
Daily Care import flow
Invoice scheduler
CRM synchronization flow
```

Bad parallel decomposition:

```text
agent 1 reads controller
agent 2 reads service
agent 3 reads repository
agent 4 tries to combine all three later
```

The second design throws away exactly the execution context you need for reliability reasoning.

### Every skill needs an explicit budget

For optimization, put a search budget in the skill definition:

```text
Start with atlas only.

Initial source budget:
- max 8 files
- max 2 implementation levels beyond known boundary
- max 1 config slice

Expand only when:
- a mitigation cannot be confirmed
- a side effect target is unknown
- dynamic dispatch prevents resolution
- transactional behavior is ambiguous

Never recursively read an entire package.
```

This turns “be efficient” into enforceable behavior.

### Scripts should answer broad questions; Claude should answer semantic questions

Bad:

```text
Claude:
"Let me grep 90,000 files for @Transactional..."
```

Better:

```text
script:
query transactions for Billing

result:
47 transaction boundaries
9 external calls inside transactions
6 DB+message flows

Claude:
analyze those 15 suspicious cases
```

Anthropic's guidance on context-efficient tools explicitly recommends filtering/pagination/range selection for large results, and its context-engineering material recommends targeted queries instead of pulling whole datasets into context. citeturn18search5turn23view4

### Make skills parameterized

Examples:

```text
/reliability:trace-flow billing-webhook

/reliability:audit-messaging --domain Billing

/reliability:audit-durability --flow resident-discharge

/reliability:audit-observability --domain DailyCare

/reliability:verify-finding DUR-017

/reliability:generate-task FINDING-128
```

Claude Code skills can accept arguments and substitute them into skill content, so this is a native model rather than a hack. citeturn23view2

### Use a two-pass model

For expensive audits:

```text
PASS A — Discovery
cheap/broad/high recall

PASS B — Verification
deep/narrow/high precision
```

Example:

```text
Discovery:
find all DB-write + message-send flows
       ↓
132 candidates

cheap static filtering
       ↓
27 suspicious

Claude flow analysis
       ↓
9 candidate findings

independent verifier
       ↓
4 confirmed findings

dedupe
       ↓
2 backlog tasks
```

That is the sort of precision funnel you want.

### Keep tools scoped

Your production-observability agent should have:

```text
Read
Grep
atlas query
Datadog/Sentry/Grafana MCP
```

Your static verifier does **not** need those production tools.

Claude Code explicitly supports scoping MCP servers to a subagent, including keeping their tool definitions entirely out of the parent context. citeturn23view3

That is useful for both token consumption and security.

### Use an internal marketplace for organizational distribution

Claude Code currently supports organization-controlled plugin marketplaces. A marketplace can be hosted privately, list multiple plugins, and be controlled centrally for a team or organization. citeturn22view5

So I would eventually distribute:

```text
org-engineering-marketplace/
   reliability-audit
   spring-upgrades
   architecture-review
   security-review
```

instead of copying `.claude/skills` manually between developers.

## Existing tools and plugins worth reusing

You should **not** implement every discovery mechanism yourself.

The ideal solution is a Claude layer sitting on top of existing static/runtime tools.

### OpenRewrite — probably one of the best fits for the deterministic atlas

OpenRewrite is particularly attractive for a very large Java/Spring repository because it already has semantic Java/Spring understanding and supports recipe-driven analysis/transformation. It also has official recipes for Spring Boot migrations, including a Spring Boot 3.5 migration recipe. citeturn19search0

I would consider writing **read-only/scanning OpenRewrite recipes** for things such as:

```text
controllers
transaction boundaries
repository calls
SQS producers
listeners
scheduled methods
async methods
external clients
```

Then Claude consumes the generated tables rather than reparsing Java by eye every time.

OpenRewrite should not replace the reasoning skills; it should replace repeated mechanical discovery.

### Spring Modulith — very promising for domain architecture

For the business-domain problem you described, Spring Modulith is worth a prototype.

It can build a model of application modules, verify dependency constraints and cycles, document relationships, and expose module-relevant components/events. citeturn20search8turn20search0turn20search4

Possible flow:

```text
Spring Modulith
      ↓
candidate application modules
      ↓
your domain cartographer
      ↓
CRM / Billing / Resident / Daily Care
      ↓
human override
      ↓
persistent domain atlas
```

I would not require changing production architecture merely to use this information. Try it as an analysis tool first.

### ArchUnit — turn discoveries into permanent architecture guarantees

ArchUnit can express architecture tests over Java dependencies, including layered rules and cycle checks. citeturn19search1turn19search5

That makes it ideal for this lifecycle:

```text
Claude discovers:
Billing must not access DailyCare internals directly

team accepts architecture rule

convert rule into ArchUnit test

future regression fails deterministically
```

That is much better than asking the AI to rediscover the same architectural rule on every release.

### CodeQL — useful for complex cross-file queries

CodeQL turns a codebase into a queryable database and supports Java/Kotlin analysis. citeturn19search6turn19search15 Query packs let teams package reusable query collections. citeturn19search11

This could become valuable for advanced questions such as:

```text
Find execution paths from controllers
to side-effecting client calls
where no timeout wrapper is encountered.

Find execution paths from SQS listeners
to DB writes
that catch Exception before the listener exits.

Find controller paths that hit async execution
before a durable state transition.
```

I would consider CodeQL a **phase-two accelerator**, not an MVP dependency. Custom framework modeling and dataflow work can become a project of its own.

### Claude Code's code intelligence / Java LSP — useful, but be careful in a giant repository

Claude Code supports code intelligence through language servers for symbol-level navigation. citeturn22view2 For focused source traversal, that can make `critical-flow-tracer` much more precise than plain grep.

However, I would **not make Java LSP the mandatory foundation for many parallel audit agents** in your environment without benchmarking it.

There is a first-party Anthropic GitHub issue reporting that JDTLS instances spawned per subagent caused more than 50 GB of memory consumption on a large enterprise Gradle workspace; that specific issue was ultimately closed as “not planned.” citeturn21search0 That is a report from one environment, not proof that yours will behave identically, but it is directly relevant to your “huge org application” use case.

My hierarchy would therefore be:

```text
deterministic atlas/query
        ↓
grep / file references
        ↓
OpenRewrite / static model
        ↓
optional single/few-agent LSP navigation
```

rather than:

```text
30 parallel JDTLS-heavy agents
```

### Claude Code Review — useful as a regression layer, not the baseline audit

Anthropic's Code Review product uses multiple reviewers, verification, deduplication and severity ranking around pull-request changes. citeturn23view5 That makes it conceptually useful for **preventing newly introduced reliability regressions** once your rule vocabulary exists.

But it is fundamentally PR/change-focused, not a replacement for a one-time deep historical audit of a giant application. Anthropic also explicitly warns that a clean Code Review does not prove the code is bug-free. citeturn23view5

There is also a currently open Anthropic GitHub issue, filed October 7, 2026, reporting that recent Claude Code versions sometimes skipped the expected multi-agent review procedure in a particular GitHub Action configuration and still emitted a no-issues result. citeturn21search2 That is an issue report rather than a confirmed universal defect, but it reinforces an important architectural principle for your system:

> **Record actual coverage and execution evidence; never trust “no findings” without knowing what actually ran.**

That is exactly why I recommend `coverage-ledger`.

Anthropic's newer Ultrareview is also a deep multi-agent PR/branch review system with independent verification, but it is currently described as a research preview and remains focused on changes rather than full application reliability architecture. citeturn21search13

### Sentry / Datadog MCP — excellent for closing the static-to-production gap

Sentry's official MCP server supports Claude Code and gives an agent access to production debugging/error information. citeturn20search2 Datadog offers an MCP server for observability insights as well. citeturn20search3

Therefore:

```text
static analysis finding
       +
flow graph
       +
production telemetry
       =
much better severity/prioritization
```

Example:

```text
Static:
External CRM client retry policy looks dangerous.

Production:
17,000 retries during last CRM outage,
request p99 jumped to 28 s,
servlet pool saturation followed.

Finding:
CRITICAL, HIGH confidence.
```

versus:

```text
Static:
Legacy client appears to have no timeout.

Production:
No runtime path has invoked it in 11 months.

Finding:
Low priority / candidate dead code.
```

That is a much stronger audit system.

### OpenTelemetry — useful evidence for trace coverage

OpenTelemetry currently supports Spring Boot 3.1+ through its Spring Boot starter, and its Spring Web MVC instrumentation can create server spans and propagate HTTP trace context. citeturn20search1turn20search5

Rather than writing an “install OpenTelemetry” skill, I would build:

```text
trace-continuity-audit
```

that first discovers what you already have and then reports only missing flow coverage.

### Testcontainers — convert findings into executable reliability tests

Testcontainers is useful for making the audit self-validating.

A reliability finding becomes much more valuable when the tool can propose:

```text
Postgres container
SQS emulator/local equivalent where appropriate
mock external dependency
network fault proxy
Spring Boot app
```

then reproduce the failure.

Testcontainers provides JUnit 5 integration and networking support that can underpin these tests. citeturn19search24turn19search7

The key is that `failure-injection-test-designer` should **design or generate local/integration tests**, not casually introduce destructive chaos into production.

### A practical tool stack

I would start roughly here:

| Problem | Preferred mechanism |
|---|---|
| Fast file discovery | `rg`, `find`, build-tool metadata |
| Spring/Java structural scan | OpenRewrite/custom scanner |
| Logical modules | Spring Modulith |
| Architecture invariants | ArchUnit |
| Complex dataflow | CodeQL, optional later |
| Symbol navigation | Java LSP, carefully benchmarked |
| HTTP/mapping runtime truth | Spring Actuator |
| Production incidents/traces | Sentry/Datadog/Grafana-style MCP |
| Trace instrumentation | existing Micrometer/OpenTelemetry stack |
| Failure tests | JUnit/Testcontainers/Toxiproxy-style network faults |
| Claude orchestration | Skills + forked subagents |
| Distribution | Internal Claude Code plugin marketplace |

The important thing is that **Claude is the investigator**, not the parser, database engine, grep implementation, or telemetry backend.

## Rollout, coverage, and success criteria

I would not implement the entire thirty-skill catalogue first.

The highest-leverage rollout is much smaller.

### Initial foundation

Build these first:

| Priority | Skill |
|---|---|
| Foundation | `repo-atlas-bootstrap` |
| Foundation | `domain-cartographer` |
| Foundation | `entrypoint-catalog` |
| Foundation | `side-effect-catalog` |
| Foundation | `critical-flow-discovery` |
| Foundation | `critical-flow-tracer` |

At the end of this phase, you should be able to ask:

```text
Show me all Billing entrypoints.

Show me all paths that touch payments.

Show me all queue consumers in Resident.

Show me scheduled jobs that mutate DB state.

Show me flows that both write DB and publish messages.

Show me external clients reachable from Daily Care.
```

without Claude rereading the whole application.

That alone creates enormous leverage.

### First reliability pack

Then implement:

```text
durable-handoff-audit
transaction-dual-write-audit
messaging-reliability-audit
idempotency-retry-audit
async-execution-audit
external-dependency-resilience-audit
shutdown-deployment-audit
```

These give you very high coverage of the “can we restart/release/fail without losing or corrupting critical work?” question.

They are grounded in well-established distributed-system failure modes: SQS standard queues are at-least-once; dual writes require explicit consistency mechanisms; timeouts can produce unknown outcomes; retries require backoff/control and idempotency; and rolling deployment safety requires correct readiness and disruption behavior rather than simply multiple pods. citeturn25search6turn24search1turn24search21turn24search0turn24search8

### Observability pack

Then add:

```text
observability-coverage-map
trace-continuity-audit
sli-alert-audit
logging-diagnostics-audit
production-evidence-correlator
recovery-reconciliation-audit
```

At this point every important flow should ideally be answerable through a matrix such as:

```text
                       Billing webhook

Accepted durably?             YES
Duplicate-safe?               YES
DB/message atomicity?         OUTBOX
Retry bounded?                YES
DLQ?                          YES
DLQ alert?                    YES
Trace across consumer?        NO        ← finding
Business completion metric?   YES
Replay mechanism?             YES
Reconciliation?               PARTIAL   ← finding
Rolling deploy safe?          YES
Failure test exists?          NO        ← finding
```

That is the artifact I think you actually want.

### Verification pack

Finally:

```text
failure-injection-test-designer
finding-verifier-deduper
backlog-task-generator
coverage-ledger
```

Then the full workflow becomes:

```text
                    REPOSITORY
                        │
                        ▼
                 BUILD / UPDATE ATLAS
                        │
                        ▼
                  DISCOVER DOMAINS
                        │
                        ▼
             ENUMERATE ENTRY POINTS
                        │
                        ▼
              RANK CRITICAL FLOWS
                        │
                        ▼
           TRACE ONE FLOW AT A TIME
                        │
                        ▼
      ┌─────────────────┼─────────────────┐
      ▼                 ▼                 ▼
 durability         messaging        observability
      │                 │                 │
      └─────────────────┼─────────────────┘
                        ▼
                candidate findings
                        │
                        ▼
              independent verifier
                        │
              false ────┴──── true
                                  │
                                  ▼
                              dedupe
                                  │
                                  ▼
                      failure-test design
                                  │
                                  ▼
                          backlog task
                                  │
                                  ▼
                        coverage ledger
```

### The system-wide invariants

Under all those skills, I would define a small fixed set of invariants.

These are more important than the individual rules.

**Accepted means durable**

```text
Once the outside world has been told that critical work was accepted,
a process restart cannot make that work unknowable or unrecoverable.
```

**Interruption is expected**

```text
Any process can terminate between any two instructions.
Correctness must not rely on "this tiny window probably won't happen."
```

**Retries are safe**

```text
Every retryable operation has known duplicate semantics.
Unknown outcomes are explicitly handled.
```

**Replicas are disposable**

```text
Removing one application instance cannot remove unique critical state.
```

**Multi-replica execution is safe**

```text
Anything that may execute on two replicas simultaneously
has explicit concurrency semantics.
```

**Old and new versions can overlap**

```text
Deployment compatibility includes DB schema,
event schema and shared-state semantics.
```

**Dependency failure is contained**

```text
One slow or failing external system cannot consume all request,
thread, connection or retry capacity.
```

**Failures are detectable**

```text
Critical flows expose enough signals to distinguish
accepted, pending, completed, failed and irrecoverable work.
```

**Failures are recoverable**

```text
There is a known operation for replay, redrive,
compensation or reconciliation.
```

**Coverage is explicit**

```text
"No finding" is never presented as "safe"
unless the relevant flow and audit profile actually ran.
```

### Definition of done for one critical flow

I would consider a flow fully reliability-audited only when the system can answer all of these:

| Question | Required |
|---|---:|
| Entry point identified? | Yes |
| Business domain known? | Yes |
| All important side effects identified? | Yes |
| Transaction boundaries known? | Yes |
| Async boundaries known? | Yes |
| Durable acceptance point known? | Yes |
| Retry semantics known? | Yes |
| Idempotency mechanism known or explicitly unnecessary? | Yes |
| Multi-replica behavior understood? | Yes |
| Deployment interruption behavior understood? | Yes |
| External dependency failure behavior understood? | Yes |
| Logs sufficient for diagnosis? | Yes |
| Metrics/business outcome visible? | Yes |
| Trace/correlation strategy known? | Yes |
| Alert or explicit monitoring decision? | Yes |
| Recovery/reconciliation path known? | Yes |
| At least one relevant failure scenario verified? | Yes |
| Audit evidence tied to commit SHA? | Yes |

That is a real denominator for “coverage.”

### The MVP I would actually build

Despite the long catalogue above, my concrete recommendation is to begin with **fourteen** skills:

```text
repo-atlas-bootstrap
domain-cartographer
entrypoint-catalog
side-effect-catalog
critical-flow-discovery
critical-flow-tracer

durable-handoff-audit
transaction-dual-write-audit
messaging-reliability-audit
idempotency-retry-audit
external-dependency-resilience-audit
shutdown-deployment-audit

observability-coverage-map
finding-verifier-deduper
```

Everything else can be layered on once these prove useful.

The most important thing to resist is making the first version:

```text
"Claude, inspect our codebase for high availability problems."
```

That will spend context rediscovering architecture, produce different coverage from run to run, and make “no issues found” almost meaningless.

The stronger model is:

```text
Build deterministic knowledge once.
      ↓
Persist it.
      ↓
Select a business domain.
      ↓
Rank critical flows.
      ↓
Trace one flow.
      ↓
Ask one reliability question.
      ↓
Collect evidence.
      ↓
Try to disprove the finding.
      ↓
Generate a task.
      ↓
Record coverage.
```

That design is consistent with Anthropic's guidance on just-in-time context, isolated subagents, skill-based progressive disclosure, persistent long-running-agent artifacts, and context-efficient preprocessing. citeturn23view4turn22view1turn22view0turn22view3turn22view4

For a codebase of the size you described, that distinction is probably the difference between a cool Claude demo and an audit system your engineering organization can actually trust.