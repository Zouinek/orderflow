# OrderFlow

**An event-driven order & payment platform built to be broken on purpose.**

> **The invariant this project defends:**
> Under any single failure (pod kill, Kafka broker kill, network fault, deploy in progress),
> **no accepted order is ever lost, and no payment is ever executed twice.**

Most demo projects show a system working. This one is built to show a system *failing*, then fixed
until it stops failing. Every failure is reproduced on command and written up as a war story.

---

## Status

🚧 **Phase 0: foundations.** In progress, built in public.

| | |
|---|---|
| ✅ Done | Order service (REST + Postgres), containerized, running on Kubernetes (kind) with externalized config, health probes (liveness, readiness, startup), resource limits, graceful rolling updates (preStop + grace period), Postgres with persistent storage (StatefulSet + PVC), break-things lab (4 of 5 scenarios) |
| ✅ Done | Target architecture designed: C4 containers, saga sequence with failure paths, [decisions](docs/decisions.md) D1 to D6 |
| 🔜 Next | Kafka: concepts, then Kafka in Docker, then the first producer and consumer |
| 📋 Planned | Payment + inventory + notification services, transactional outbox + CDC (Debezium), saga with compensation, idempotent consumers, DLQs, observability, chaos testing |

Roadmap: Phase 0 foundations → Phase 1 build it *wrong* (dual-write, double charges) →
Phase 2 correctness (outbox, idempotency, saga) → Phase 3 production-readiness (tracing, SLOs, chaos) → Phase 4 ship.

---

## Target architecture

Full view (containers + saga sequence with all failure paths): [docs/architecture.md](docs/architecture.md).

![OrderFlow container diagram](docs/img/orderflow-app-diagram.png)

[Interactive version in IcePanel](https://s.icepanel.io/qjQk3YauZVn2JL/LMt1)

Order fulfilment is a **choreographed saga**: `order-created → stock-reserved → payment-completed → order-confirmed`.
If payment fails, Inventory releases the stock and the order is cancelled. If there is no stock, the order is cancelled
and Payment is never called.

*Today, only the order service and its database exist. Everything else is the target.*

---

## Stack

Java 25 · Spring Boot 4 · Postgres · Docker · Kubernetes (kind) · Maven multi-module. Kafka, Debezium, Prometheus/Grafana and OpenTelemetry arrive in later phases.

## Repository layout

```
orderflow/
├── pom.xml                 # parent / aggregator
├── services/
│   └── order/              # order service (REST API + order state machine)
├── k8s/                    # Kubernetes manifests
│   ├── postgres/
│   │   ├── pg-statefulset.yaml 
│   │   └── pg-service.yaml
│   ├── configmap.yaml
│   ├── secret.yaml.example # copy to secret.yaml and fill in
│   ├── deployment.yaml
│   └── service.yaml   
├── Dockerfile
└── docker-compose.yml      # local run: app + Postgres
```

---

## Run it

### Locally (Docker Compose)

```bash
docker compose up --build
curl http://localhost:8080/actuator/health
```

### On Kubernetes (kind)

```bash
# 1. cluster + image
kind create cluster --name orderflow
docker build -t orderflow-api:local .
kind load docker-image orderflow-api:local --name orderflow

# 2. config (the real secret is gitignored)
cp k8s/secret.yaml.example k8s/secret.yaml   # then fill in the values
kubectl apply -R -f k8s/

# 3. reach it
kubectl port-forward svc/orderflow-api 8080:80
curl http://localhost:8080/actuator/health    # {"status":"UP"}
```

### Build from source

```bash
./mvnw clean package                      # all modules
./mvnw spring-boot:run -pl services/order # run the order service
```

---

## Why this project exists

Production incidents rarely come from one component failing. They come from the interactions
between components under partial failure. This project is a deliberate exercise in causing those
interactions, diagnosing them, and fixing them properly: dual writes, at-least-once duplicates,
poison messages, consumer rebalances, broker loss, pod death mid-transaction, OOMKills,
misconfigured probes.

Each one gets caused on purpose, fixed, and written up.

## Learning in public

- [War stories](docs/war-stories.md): what broke, why, and how I fixed it.
- [Decisions](docs/decisions.md): what I chose, what I rejected, and why.
- [Architecture](docs/architecture.md): the target design as C4 containers and the saga sequence.
 