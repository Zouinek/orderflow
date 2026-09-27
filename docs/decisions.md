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

## D1: PostgreSQL over MySQL (2026-09-27)

**Context:** the project has only one service, the  database has no data worth keeping, 
and there are no migrations yet. Every new service and  every migration makes switching the database more expensive, so this was the right moment to decide. 

**Decision:** Use PostgreSQL for the order service, in docker-compose and in Kubernetes (as a StatefulSet with a persistent volume). 

**Rejected:**  MySQL. It worked, but:
- when a schema change fails halfway, Postgres can roll it back completely, MySQL can't. That matters once I add Flyway migrations.
- most backend job offers  I see ask for Postgres.

**Measured afterwards:** idle Postgres uses ~42Mi vs ~510Mi for idle MySQL, both with almost no data (not a benchmark), so the pod needs a much smaller memory limit (256Mi vs 1024Mi).

**Consequences:**  It cost me: a new driver  in the pom, a new  JDBC URL, different env var names, (`POSTGRES_*`), and a new docker-compose service. It makes easier later: adding Flyway migrations.
