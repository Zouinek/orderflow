# 🧭 Decisions

Why OrderFlow is built the way it is: one entry per decision, including what I rejected and why.
Newest on top.

---

## Template

```markdown
## D<N>: <what I chose> over <what I rejected> (YYYY-MM-DD)

**Context:** the situation that forced a decision.

**Decision:** what I chose, in one sentence.

**Rejected:** the alternative(s), and why not.

**Consequences:** what it costs and what it makes easier later.
```

---

## D6: UNKNOWN payment status with reconciliation over treating a timeout as failed (2026-10-07)

**Context:** the Payment service calls an external payment provider. When that call times out, I don't know what happened: maybe the provider never got the request, maybe it charged the customer and only the answer got lost.

**Decision:** a timeout sets the payment to `UNKNOWN`. A reconciliation job inside the Payment service retries the call with the same idempotency key until it gets a real answer. If there is still no answer after a deadline, the order is cancelled and the payment refunded. The final result is published through the outbox like any other event.

**Rejected:**
- Treating a timeout as failed: if the provider did charge the customer, I cancel the order and keep their money.
- Treating a timeout as success: if the provider did not charge, I ship an order nobody paid for.

**Consequences:** it costs a third payment state, a background job and a deadline to choose. The same idempotency key is what makes the retry safe: the provider sees a duplicate, not a second charge. Some orders take longer to confirm, but the money is never wrong (integrity over timeliness, DDIA ch. 12).

## D5: Debezium (CDC) over polling the outbox table (2026-10-07)

**Context:** with the outbox (D3), events sit in a table in each service's database. Something has to move them from there to Kafka.

**Decision:** Debezium reads the database log (Postgres WAL) of all three databases and publishes the new outbox rows to Kafka.

**Rejected:** a polling publisher inside each service (`SELECT ... WHERE published = false` every few seconds). It is simpler, but it adds load on the database, adds delay equal to the poll interval, and every service needs the same publishing code.

**Consequences:** it costs one more component to run and learn (Debezium on Kafka Connect, logical replication on Postgres). It makes the services simpler: they only write rows, they don't talk to Kafka to publish. Debezium does not know who consumes the events; consumers pull from Kafka themselves.

## D4: choreography over orchestration (2026-10-07)

**Context:** an order goes through several services: reserve stock, take payment, confirm, notify. Something has to decide which step comes next.

**Decision:** choreography. Each service listens to the event of the previous step and publishes its own:
- Inventory listens to `order-created`, publishes `stock-reserved` or `stock-rejected`
- Payment listens to `stock-reserved`, publishes `payment-completed` or `payment-failed`
- Order listens to `stock-rejected` / `payment-completed` / `payment-failed`, publishes `order-confirmed` / `order-cancelled`
- Notification listens to `order-confirmed` / `order-cancelled`

On `payment-failed`, Inventory releases the stock and Order sets the order to `CANCELLED`.
On `stock-rejected`, Order sets the order to `CANCELLED` and Payment is never called, so there is nothing to refund.

**Rejected:** orchestration, where one central service tells each service what to do. The flow would be easier to read in one place, but that service has to know every other service and becomes a central point every change goes through.

**Consequences:** no service knows the whole flow, so it is harder to see where an order is stuck. I need the sequence diagram ([architecture](architecture.md)) and good logging to follow an order. Adding a new step means one new listener instead of changing a coordinator. Open question: Inventory could listen to `order-cancelled` instead of `payment-failed`.

## D3: transactional outbox over a dual write (2026-10-07)

**Context:** after D2, a service has to save its data and tell the other services about it. Saving to the database and sending to Kafka are two different systems, so there is no single transaction across both.

**Decision:** every service uses the outbox: in the same database transaction, it writes its data and an event row into an `outbox` table. Debezium (D5) publishes that row to Kafka.

**Rejected:** dual write (save to DB, then send to Kafka). If the app crashes between the two, the order exists but no event is sent, or the event is sent but the order was rolled back. Nothing retries it, and the system stays wrong silently.

**Consequences:** it costs an extra table per service and one more moving part. Delivery becomes at-least-once, so an event can arrive twice: every consumer must be idempotent (handling the same event twice has the same effect as once). In return, an event is published if and only if the data was saved.

## D2: database per service over a shared database (2026-10-07)

**Context:** the split into Order, Payment, Inventory and Notification services was already planned, but not in detail. While drawing the target architecture in detail (C4 level 2), I discovered it is better to split the database too. If services share tables, a schema change in one service can break another, so they can't change, deploy or scale independently.

**Decision:** each service owns its own database (Orders DB, Payments DB, Inventory DB). No service reads another service's tables; they only talk through events.

**Rejected:** a shared database. It is easier to start with (joins, one transaction for everything), but every service would depend on every other service's schema. Honest note: locally I run one Postgres with three databases, so the hardware is still a single point of failure. The split is about ownership of the data, not about the machine.

**Consequences:** I lose two things a shared database gives for free:
- no SQL joins across services (orders + stock in one query)
- no single transaction across services (reserve stock AND take payment, all or nothing)

That loss is why I need the outbox (D3) and a saga with compensating steps (D4) instead. It makes it easier later to change one service's schema without touching the others.

## D1: PostgreSQL over MySQL (2026-09-27)

**Context:** the project has only one service, the  database has no data worth keeping, 
and there are no migrations yet. Every new service and  every migration makes switching the database more expensive, so this was the right moment to decide. 

**Decision:** Use PostgreSQL for the order service, in docker-compose and in Kubernetes (as a StatefulSet with a persistent volume). 

**Rejected:**  MySQL. It worked, but:
- when a schema change fails halfway, Postgres can roll it back completely, MySQL can't. That matters once I add Flyway migrations.
- most backend job offers  I see ask for Postgres.

**Measured afterwards:** idle Postgres uses ~42Mi vs ~510Mi for idle MySQL, both with almost no data (not a benchmark), so the pod needs a much smaller memory limit (256Mi vs 1024Mi).

**Consequences:**  It cost me: a new driver  in the pom, a new  JDBC URL, different env var names, (`POSTGRES_*`), and a new docker-compose service. It makes easier later: adding Flyway migrations.
