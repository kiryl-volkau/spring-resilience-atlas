# Seeded reliability defects

Deliberate defects in the billing flow, for audit-skill evals. Paths are relative to
`fixtures/spring-golden/src/main/java/com/acme/care/`.

Flow: `BillingWebhookController.receiveStripeEvent` → `StripeWebhookService.handle`
(`@Transactional`) → `EventReceiptRepository.save` + `SqsTemplate.send` + `executor.submit`
→ HTTP 200 → `InvoiceEventListener.onInvoiceEvent` (`@SqsListener`) →
`InvoiceService.createInvoiceFromProviderEvent` (`@Transactional`) →
`PaymentProviderClient.createCharge` (RestTemplate).

## Defects

| # | Defect | Evidence | Failure scenario |
|---|---|---|---|
| D1 | Non-durable hand-off acknowledged with HTTP 200 | `billing/service/StripeWebhookService.java:56` (`executor.submit`), `billing/web/BillingWebhookController.java:36` (`ResponseEntity.ok()`) | Customer reconciliation runs on an in-memory pool after the provider has been told the webhook succeeded; a crash, deploy or `OOMKilled` loses the queued task and the provider never redelivers. The returned `Future` is dropped, so task exceptions are silent. |
| D2 | Unbounded in-memory executor without graceful drain | `billing/config/BillingInfrastructureConfig.java:15` (`Executors.newFixedThreadPool(4)`) | Unbounded `LinkedBlockingQueue` grows under webhook bursts; on shutdown Spring only calls `shutdown()`, without awaiting termination, so queued work is dropped. |
| D3 | Dual write: DB insert and SQS send in one local transaction | `billing/service/StripeWebhookService.java:45` (`@Transactional`), `:50` (`save`), `:53` (`sqsTemplate.send`) | The SQS message is sent before commit. If the commit fails, the invoice event is already published for a receipt that does not exist; the provider then retries and a second message is sent. No outbox. |
| D4 | Async task started inside the transaction | `billing/service/StripeWebhookService.java:56` | The submitted task can run before the receipt commits, or after it rolled back. |
| D5 | Webhook not idempotent | `billing/service/StripeWebhookService.java:46-50`; unused `billing/persistence/EventReceiptRepository.java:9` (`findByProviderEventId`); no unique constraint at `billing/persistence/EventReceipt.java:20` or in `src/main/resources/db/migration/V1__billing.sql`, which indexes `provider_event_id` without `UNIQUE` | Stripe redelivers on timeout or non-2xx: duplicate receipts, duplicate SQS messages, duplicate reconciliation. |
| D6 | SQS consumer not idempotent | `billing/messaging/InvoiceEventListener.java:25`, `billing/service/InvoiceService.java:31`, `billing/persistence/Invoice.java:45` (`UUID.randomUUID()`) | SQS is at-least-once; every redelivery creates a new invoice and a new charge. |
| D7 | Remote charge inside a DB transaction | `billing/service/InvoiceService.java:29` (`@Transactional`), `:34` (`createCharge`) | The DB connection is held for the duration of the provider call. If the commit fails after the charge succeeded, money moved with no invoice row; the `AFTER_ROLLBACK` listener at `billing/events/InvoiceNotificationListener.java:27` only logs. |
| D8 | Charge request without idempotency key | `billing/client/PaymentProviderClient.java:30` | A retried or redelivered message double-charges the payer; the provider cannot deduplicate. |
| D9 | HTTP client without timeouts | `billing/config/BillingInfrastructureConfig.java:20` (`new RestTemplate()`), used at `billing/client/PaymentProviderClient.java:30` | Default connect/read timeouts are infinite. A hung provider pins SQS listener threads; the visibility timeout expires and the message is redelivered while the first attempt is still in flight (amplifies D6/D8). |
| D10 | Poison message redelivered forever | `billing/messaging/InvoiceEventListener.java:26` (`readTree` throws), no DLQ/redrive or error handler in code | A malformed payload is retried until retention expires, consuming listener capacity. |

D2 and D9 are what `executor-unbounded-queue` and `http-client-without-timeout` find: their
place is the bean method that builds the executor or client, and it lists every operation that
calls through it.

## Ledger defects

`billing/ledger/` posts invoices to an external ledger through the Feign client
`billing/client/LedgerClient.java`. Every class holds one deliberate defect for a rule.

| # | Rule | Evidence | Failure scenario |
|---|---|---|---|
| L1 | `after-commit-listener-writes-db` | `billing/ledger/LedgerPostingListener.java:18` | The listener runs after `InvoiceService` committed, with that transaction still bound; the `save` joins it and is never committed. |
| L2 | `after-commit-listener-without-transaction` | `billing/ledger/LedgerCloseJob.java:25` publishes, `:28` listens | The job publishes outside any transaction; the `@TransactionalEventListener` has no `fallbackExecution`, so the day is never closed at the ledger. |
| L3 | `read-only-tx-writes` | `billing/ledger/LedgerStatementService.java:11`, `:24` | The class is `readOnly`; the viewed-at change is not flushed. |
| L4 | `lock-held-across-remote-call` | `billing/ledger/LedgerSyncService.java:31-32` | The posting row stays locked while the ledger answers. |
| L5 | `consumer-swallows-failure` | `billing/ledger/LedgerSyncConsumer.java:25` | The listener logs the failure and returns; Kafka commits the offset and the posting is never synced. |
| L6 | `remote-failure-swallowed` | `billing/ledger/LedgerSyncService.java:45` | A rejected adjustment is only logged; nothing retries it. |
| L7 | `nested-requires-new-in-tx` | `billing/ledger/LedgerAuditService.java:18`, called from `LedgerSyncService.adjust` | Each adjustment holds two connections at once. |
| L8 | `request-answers-ok-on-failure` | `billing/ledger/LedgerController.java:48`, `:51` | The bank's webhook gets 200 when the transfer was not recorded, so the bank never redelivers. |
| L9 | `flush-on-shutdown` | `billing/ledger/LedgerBuffer.java:24-26` | Buffered postings are written only in `@PreDestroy`; a kill loses them. |
| L10 | `startup-remote-call` | `billing/ledger/LedgerWarmup.java:19` | Startup fails while the ledger is down. |
| L11 | `quartz-concurrent-execution` | `billing/ledger/LedgerReconciliationJob.java:10` | Without `@DisallowConcurrentExecution` two runs reconcile the same postings. |
| L12 | `send-result-ignored` | `shared/patterns/PatternsController.java:49`, `shared/patterns/PatternAuditService.java:26` | The Kafka send's future is dropped; a failed send is only logged. |

## Module defects

These come from the module's config and migrations, not from a call path. Each forms one place
at the config key or the migration file.

| # | Rule | Evidence | Failure scenario |
|---|---|---|---|
| M1 | `graceful-shutdown-off` | `src/main/resources/application.yml` has no `server.shutdown`; the pom pins Spring Boot 3.3.5 | Deploys cut requests in flight. |
| M2 | `kafka-auto-commit` | `src/main/resources/application.yml:20` | Offsets are committed on a timer, before processing ends. |
| M3 | `ddl-auto-in-production` | `src/main/resources/application.yml:10` (`update`) | Hibernate alters the schema at startup. |
| M4 | `feign-default-timeouts` (LOW) | no `spring.cloud.openfeign.client.config` timeouts | Ledger calls may wait 60 s. |
| M5 | `destructive-migration` | `src/main/resources/db/migration/V2__ledger.sql:10` renames `billing_invoice.status` | Replicas on the old code fail while the deploy rolls. |

## Near-misses (not defects)

- `src/main/resources/application.yml:8`: `spring.jpa.open-in-view: false`, so
  `open-in-view-enabled` must not fire.
- `src/main/resources/application-local.yml` sets graceful shutdown and `create-drop`; a local
  profile says nothing about production, so neither changes M1 or M3.
- `billing/events/InvoiceNotificationListener.java:21`: an after-commit listener that only
  e-mails writes nothing, so L1's rule must not fire on it.
- `billing/web/PaypalWebhookController.java`: the catch takes only `InvalidSignatureException`
  and answers 400, so `request-answers-ok-on-failure` must not fire.

- `resident/messaging/ResidentSyncPoller.java:64`: the message is deleted only after it was
  processed successfully; failures are left for redelivery.
- `billing/jobs/LateFeeAccrualJob.java:13`: `@DisallowConcurrentExecution` prevents overlapping
  runs on one scheduler.
- `billing/events/InvoiceNotificationListener.java:21`: the invoice e-mail is sent
  `AFTER_COMMIT`, not inside the transaction.

## A hint the quick check must clear

`POST /api/billing/webhooks/paypal` → `billing/service/PaypalWebhookService.java` (`handle`).
The scanner reports `webhook-not-idempotent`, because its lookup patterns do not know the name
`lookupByReference`. The code is idempotent: `handle` skips a delivery whose PayPal event id was
already stored, and `billing/persistence/PaypalReceipt.java` and the migration put a unique constraint on that id.
The quick check must answer REFUTED (`lookup before write`, `unique event id`), not CONFIRMED.
`audit/verdicts-expected.tsv` lists the verdict expected for every place of the fixture that a
seed decides.

## One remote call under two transactions

`PaymentProviderClient.createCharge` is called inside two different transactions: the one
`InvoiceService.createInvoiceFromProviderEvent` opens for the invoice consumer (D3), and the one
`billing/service/ChargeRetryService.java` (`retry`) opens for
`POST /api/billing/admin/invoices/{invoiceId}/charge [action=retry]`. Each is its own
`remote-in-tx` place, keyed by the method that opens the transaction, so a verdict on one never
speaks for the other's operations. `[action=cancel]` on the same path and verb only deletes a
row: it has no hint, and its `no-hint` place is what `--level NONE` sweeps.

## A shared field that is read back

`legacy/portlet/ResidentAccountPortlet.java`: the singleton portlet stores each user's search
in the field `lastSearch` (`saveAccount`) and renders it back (`showAccount`), so one user sees
another's search and every replica shows a different one. The quick check must answer
CONFIRMED for `shared-field-write`; a field that is written and never read would be harmless.

## Analyzer regression cases

`shared/patterns/` and `legacy/portlet/` isolate cases the effect analysis once got wrong. They are
checked by `signals.tsv` and `risk.tsv`, not by audits.

| Endpoint | What it pins down |
|---|---|
| `POST /api/patterns/after-commit` | A send in `afterCommit` runs after the transaction: a plain dual write (MEDIUM), not a send before commit |
| `POST /api/patterns/tx-template` | Work inside `TransactionTemplate.executeWithoutResult` is inside the transaction (HIGH dual write) |
| `POST /api/patterns/overload` | Same-arity overloads are told apart by argument type; the `@Async` overload is reached through `this`, so it is a proxy bypass, not a hand-off |
| `GET /api/patterns/parallel` | `supplyAsync(...).join()` is waited for: no hand-off |
| `POST /api/patterns/fire` | A Guava `Joiner.join` next to a fire-and-forget `execute` does not hide the hand-off |
| `POST /api/patterns/wrapped` | A future kept in a local and returned as `Mono.fromFuture(future)` is awaited by the caller: an asynchronous response, not a hand-off after the ack |
| `GET /api/patterns/stream` | Work that writes into a returned `SseEmitter` keeps the client connected: an asynchronous response |
| `POST /api/patterns/pumped` | A thread returned by a private helper and joined through a `join(thread)` helper is waited for: no hand-off |
| `PORTLET ACTION VIEW:save` | A portlet action handler writes a field of the singleton controller |

