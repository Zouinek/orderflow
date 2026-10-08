# 🏗️ Architecture

The **target** architecture of OrderFlow. Today only the order service and its database exist.
Why it looks like this: see [decisions](decisions.md) D2 to D6.

---

## Containers (C4 level 2)

The full picture, drawn in IcePanel ([open the interactive version](https://s.icepanel.io/qjQk3YauZVn2JL/LMt1): zoom, click objects, step through the flow):

![OrderFlow container diagram](img/orderflow-app-diagram.png)

Each service owns its database (D2) and writes an outbox row in the same transaction as its data (D3).
Debezium reads the database log (WAL) and publishes the outbox rows to Kafka (D5). No service writes to Kafka directly.
Consumers pull from Kafka; Debezium does not know who reads the events.

---

## Order saga (sequence)

In what order. One diagram, three paths: happy, payment failed, no stock.
Debezium is left out to keep it readable: every arrow to Kafka means "outbox row, then Debezium publishes it".

- `alt` / `else`: only one branch happens (if / else)
- `par` / `and`: both branches happen, in any order

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Order as Order svc
    participant Kafka
    participant Inventory as Inventory svc
    participant Payment as Payment svc
    participant Provider as Payment Provider
    participant Notification as Notification svc

    Note over Order,Notification: Every event is written to the service's outbox in the same DB transaction, then Debezium publishes it to Kafka

    Client->>Order: POST /orders
    Order->>Order: save order CREATED + outbox order-created
    Order-->>Client: 201 Created (status CREATED)
    Order->>Kafka: order-created
    Kafka->>Inventory: order-created

    alt stock available
        Inventory->>Inventory: reserve stock + outbox stock-reserved
        Inventory->>Kafka: stock-reserved
        Kafka->>Payment: stock-reserved
        Payment->>Provider: charge card (idempotency key)
        Note over Payment,Provider: timeout = UNKNOWN, reconciliation job retries with the same key (D6)

        alt payment ok
            Provider-->>Payment: success
            Payment->>Payment: save COMPLETED + outbox payment-completed
            Payment->>Kafka: payment-completed
            Kafka->>Order: payment-completed
            Order->>Order: set CONFIRMED + outbox order-confirmed
            Order->>Kafka: order-confirmed
            Kafka->>Notification: order-confirmed
        else payment declined
            Provider-->>Payment: declined
            Payment->>Payment: save FAILED + outbox payment-failed
            Payment->>Kafka: payment-failed
            par compensation
                Kafka->>Inventory: payment-failed
                Inventory->>Inventory: release stock
            and
                Kafka->>Order: payment-failed
                Order->>Order: set CANCELLED + outbox order-cancelled
            end
            Order->>Kafka: order-cancelled
            Kafka->>Notification: order-cancelled
        end

    else no stock
        Inventory->>Inventory: outbox stock-rejected
        Inventory->>Kafka: stock-rejected
        Kafka->>Order: stock-rejected
        Order->>Order: set CANCELLED + outbox order-cancelled
        Order->>Kafka: order-cancelled
        Kafka->>Notification: order-cancelled
        Note over Payment: never called, no charge, no refund
    end
```

The client gets `201` right away with status `CREATED`; the final status (`CONFIRMED` or `CANCELLED`) arrives later
through `GET /orders/{id}`. That gap is eventual consistency.
