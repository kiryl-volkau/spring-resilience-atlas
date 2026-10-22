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
| D5 | Webhook not idempotent | `billing/service/StripeWebhookService.java:46-50`; unused `billing/persistence/EventReceiptRepository.java:9` (`findByProviderEventId`); no unique constraint at `billing/persistence/EventReceipt.java:20` | Stripe redelivers on timeout or non-2xx: duplicate receipts, duplicate SQS messages, duplicate reconciliation. |
| D6 | SQS consumer not idempotent | `billing/messaging/InvoiceEventListener.java:25`, `billing/service/InvoiceService.java:31`, `billing/persistence/Invoice.java:45` (`UUID.randomUUID()`) | SQS is at-least-once; every redelivery creates a new invoice and a new charge. |
| D7 | Remote charge inside a DB transaction | `billing/service/InvoiceService.java:29` (`@Transactional`), `:34` (`createCharge`) | The DB connection is held for the duration of the provider call. If the commit fails after the charge succeeded, money moved with no invoice row; the `AFTER_ROLLBACK` listener at `billing/events/InvoiceNotificationListener.java:27` only logs. |
| D8 | Charge request without idempotency key | `billing/client/PaymentProviderClient.java:30` | A retried or redelivered message double-charges the payer; the provider cannot deduplicate. |
| D9 | HTTP client without timeouts | `billing/config/BillingInfrastructureConfig.java:20` (`new RestTemplate()`), used at `billing/client/PaymentProviderClient.java:30` | Default connect/read timeouts are infinite. A hung provider pins SQS listener threads; the visibility timeout expires and the message is redelivered while the first attempt is still in flight (amplifies D6/D8). |
| D10 | Poison message redelivered forever | `billing/messaging/InvoiceEventListener.java:26` (`readTree` throws), no DLQ/redrive or error handler in code | A malformed payload is retried until retention expires, consuming listener capacity. |

## Near-misses (not defects)

- `resident/messaging/ResidentSyncPoller.java:64`: the message is deleted only after it was
  processed successfully; failures are left for redelivery.
- `billing/jobs/LateFeeAccrualJob.java:13`: `@DisallowConcurrentExecution` prevents overlapping
  runs on one scheduler.
- `billing/events/InvoiceNotificationListener.java:21`: the invoice e-mail is sent
  `AFTER_COMMIT`, not inside the transaction.

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

