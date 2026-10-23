---
type: llm
---

PASS if the reply describes the billing webhook flow with all of these:
1. the controller answers the provider with HTTP 200;
2. an event receipt is written to the database inside a transaction;
3. a message is sent to the invoice SQS queue;
4. work is handed to an in-memory executor;
5. the 200 can be returned while the executor work is still pending, so the acknowledgement does not wait for all work to be durable.

FAIL if any of the five is missing, or if the reply states something that contradicts them, such as the executor work completing before the response.
