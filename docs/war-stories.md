# ⚔️ War Stories

Things that broke, or that I broke on purpose, while building OrderFlow, and what each one taught me.
Newest on top. Every `feat`/`fix` commit adds or updates an entry here (enforced by `.githooks/commit-msg`).

---

## Template

```markdown
## #N — <short, memorable title> (YYYY-MM-DD)

**Commit:** `<type(scope): message>`

**Symptom:** what I saw (exact error / kubectl output), before I understood anything.

**Root cause:** why it actually happened.

**Fix:** what I changed, and how I verified it.

**Also learned:** side lessons, gotchas, commands worth remembering.

**Concept:** the general idea behind it (DDIA / distributed systems), in one or two sentences.
```

---

## #15 — Two cancels, both 200 (2026-11-25)

**Commits:**
- `feat(order): track order status, starting as CREATED`
- `feat(order): let clients cancel orders, rejecting repeat cancels with 409`
- `feat(order): reject concurrent updates to the same order with 409`
- `fix(order): only allow cancelling orders that are still CREATED` (after the PR review)
- `refactor(order): build the cancel response from the saved order` (after the PR review)
- `fix(order): make repeat cancels return 200 instead of 409` (after the PR review)
- `fix(order): remove DELETE /orders/{id}, orders are cancelled, never deleted` (after the PR review)



**Symptom:** an order had no lifecycle, so a client could not cancel it. The first version of `cancel()` also had a
hidden problem. Two requests that read the same order at the same time would both pass the check and both save, and the
last save would silently overwrite the first. Today that means two cancels, both `200`. Once Payment confirms orders
(Phase 2), it gets worse: a customer cancels, then the payment flow saves its old copy as `CONFIRMED`. The order is
shipped after being cancelled.

**Root cause:** the rule in `Order.cancel()` checks the status **in memory**. Each request loads its own copy of the row,
and neither copy knows that the other one exists. Without a version, the `UPDATE` does not check whether the row
changed since it was read. It just writes.

**Fix:**
1. `V3` migration + `OrderStatus` enum (`CREATED`, `CONFIRMED`, `CANCELLED`), stored as `STRING`, with a `CHECK` constraint.
2. `POST /orders/{id}/cancel`: the rule lives in `Order.cancel()` (no setter for `status`). A repeat cancel returns `409`
   with a clear message, and an unknown id returns `404`.
3. `V4` migration + `@Version` on `Order`. Hibernate now writes `UPDATE ... WHERE id = ? AND version = ?`. If someone
   else saved first, the version no longer matches, 0 rows are updated, and the save throws
   `ObjectOptimisticLockingFailureException`, which the handler turns into `409`.

Verified with curl (cancel → `200`, again → `409`, unknown → `404`), a unit test for the rule (`OrderTest`), and an IT
that loads two copies of the same order, saves the first, and expects the second save to fail. I watched that IT fail
without `@Version` ("nothing was thrown") before turning it green.

**Also learned:**
- `git branch -m feat/order-status` **renames the current branch**, and I was on `main`. `git checkout -b` creates a new one.
- `EnumType.ORDINAL` stores 0/1/2. Reordering the enum silently changes the meaning of every row, so use `STRING`.
- A DB `DEFAULT` is not enough: Hibernate inserts an explicit `NULL` unless the Java field is initialized.
- `ALREADY_REPORTED` is `208`, a **success** code. "You can't do that now" is `409 CONFLICT`.
- Never edit an applied migration (checksum mismatch, see #12). A change goes in the next version.
- A failing unit test stops the build before Failsafe runs the ITs, so "ITs passed" can mean "ITs never ran".
- In `assertThrows`, I first put the wrong exception. The test failed with the *real* exception, which showed that `@Version` worked.

**Concept:** optimistic concurrency control. Don't lock the row while reading. Instead, check at write time that nobody
changed it since you read it (compare-and-set on a version number). A check that only lives in memory can't see other
requests. The write itself has to carry the condition (DDIA ch. 7, preventing lost updates).

## #14 — Client mistakes returned 500 (2026-11-23)

**Commits:**
- `fix(order): return 400 with field errors instead of 500 for invalid orders`
- `fix(order): return field errors for non-numeric values and oversized input` (after the PR review)
- `fix(order): never return 500 from the error handler for an empty JSON path` (second review)
- `fix(order): reject decimal quantities instead of silently truncating them` (third review)
- `fix(order): reject zero-price orders and say when quantity must be a whole number` (fourth review)

**Symptom:** I sent `POST /orders` with bad input on purpose:

| Request | Response |
|---|---|
| no `quantity` | `500 Internal Server Error` |
| `"quantity": -5` | `500 Internal Server Error` |
| no `customerName` | `500 Internal Server Error` |
| `"price": "abc"` | `400 Bad Request`, but the body did not say which field was wrong |

All four are client mistakes, so they should be `4xx`. A `500` tells the client "the server is broken", and a
developer using my API had no way to know what to fix.

**Root cause:** Nothing checked the request before it reached the code, and nothing handled the exceptions that
came after. Each bad request crashed in a different place:
- **No quantity:** Jackson set `quantity` to `null`. The mapper passed it to `setQuantity(int)`, and Java cannot
  unbox `null` into an `int`, so it threw a `NullPointerException`.
- **Negative quantity:** the Java code accepted `-5`. Only Postgres stopped it, with the `CHECK` constraint.
- **No customerName:** same thing. Postgres stopped it with `NOT NULL`.
- **price "abc":** Jackson could not parse it. Spring turns that into a `400` automatically, but with a generic body.

Spring Boot answers any unhandled exception with `500`, even when the real cause is bad input.

**Fix:** validate the request at the door, and return a clear error body:
- Added `spring-boot-starter-validation` (no `<version>`; the Spring Boot parent manages it).
- Put constraints on `CreateOrderRequest`: `@NotBlank` on the strings, `@NotNull @Positive` on `quantity`, and
  `@NotNull @DecimalMin("0")` on `price`.
- Added `@Valid` on the `@RequestBody` in `OrderController`, so invalid input is rejected before any of my code runs.
- Enabled `spring.mvc.problemdetails.enabled=true`, so errors use the standard RFC 9457 format
  (`application/problem+json`).
- `ApiExceptionHandler` (`@RestControllerAdvice`) adds an `errors` map (field → message) to that ProblemDetail.

I verified it with curl and two integration tests: one missing field, and one with all fields invalid (4 errors).
I removed `@Valid` once to watch a test fail: it crashed with the same `NullPointerException`, which was the right
reason. Then I put it back, and the tests passed.

The PR review (#10) found two gaps that I fixed in a second commit:
- **`"price": "abc"` still had a generic body.** Validation never runs here, because Jackson fails before the record
  exists. I overrode `handleHttpMessageNotReadable`: if the cause is Jackson's `InvalidFormatException`,
  `getPath().get(0).getPropertyName()` gives the field, and the response says `"price": "must be a valid number"`.
  Broken JSON (no field to name) still returns a clean `400`.
- **Input bigger than the DB columns still gave a `500`.** Added `@Size(max = 255)` on the strings (`VARCHAR(255)`)
  and `@Digits(integer = 10, fraction = 2)` on `price`.

The second review found two more things:
- `getPath().get(0)` could throw on an empty path, and an exception *inside* the error handler is a `500` again.
  Added `!ife.getPath().isEmpty()` to the `if`.
- I had also added `@Max(1000)` on `quantity`. That is a business rule, not a DB limit, and I had no real reason
  for 1000 (a bulk order could be bigger), so I removed it.
- A fourth test sends a 256-character `customerName` and expects `$.errors.customerName`.

The third review guessed (without testing) that `"quantity": 1.9` might be silently truncated. I checked with curl:
`201 Created` with `"quantity": 1`. The client asked for 1.9 and got 1, with no error. `@Positive` cannot catch it,
because Jackson already changed the value before validation runs. Fix: `spring.jackson.deserialization.accept-float-as-int=false`.
Now it returns `400` with `errors.quantity`, through the same `InvalidFormatException` path, so the handler needed
no change. A fifth test covers it.

The fourth review found two last points:
- For `"quantity": 1.9` the message `"must be a valid number"` was wrong, because 1.9 *is* a number. The handler now
  picks the message from `ife.getTargetType()`: `"must be a whole number"` for `Integer`, `"must be a valid number"`
  otherwise.
- `@DecimalMin("0")` is inclusive, so a price of `0` created a free order. I decided free orders are not allowed and
  changed it to `@Positive`. The DB still allows `price >= 0`, which is fine: the API may be stricter than the
  database. A sixth test checks the boundary (`"price": 0` → `400`).

A third test sends `"price": "abc"`. With the new handler commented out it failed with
`No value at JSON path "$.errors.price"`: the status was already `400`, only the field information was missing.

Now every bad request above returns `400` with, for example, `"errors": {"quantity": "must not be null"}`.

**Also learned:**
- `@NotBlank` already includes `@NotNull` and `@NotEmpty`. Putting all three gives three duplicate messages.
- Number constraints like `@Positive` ignore `null`, so they need `@NotNull` too.
- `Collectors.toMap` throws on a duplicate key unless you give it a merge function, like `(first, second) -> first`.
- JUnit **Failure** = an assertion was wrong. **Error** = the test crashed with an exception.
- MockMvc has no Tomcat. An unhandled exception goes straight into the test instead of becoming a `500`.
- Don't check exact validation messages in tests. They depend on the locale, so use `.exists()`.
- "Port 8080 already in use" was my Docker `app` container. Fix: `docker compose stop app`.
- In PowerShell, `\"` inside curl JSON breaks the body and gives a misleading `400`. Pipe the JSON into
  `curl.exe --data-binary "@-"` instead.
- Spring Boot 4 uses **Jackson 3**: the package is `tools.jackson...`, not `com.fasterxml.jackson...`.
- `@Digits` only counts digits, it does not check the sign. When I replaced `@DecimalMin("0")` with it, `-5` would
  have passed again. Annotations stack: each one checks one rule.
- An exact message in a test is fine when **I** wrote the message (`"must be a valid number"`). Only Bean
  Validation's built-in messages depend on the locale.
- `isBadRequest()` alone can pass for the wrong reason. My long-name test first sent broken JSON (`%s` without
  quotes), got a `400` from the JSON parser, and only `$.errors.customerName` showed that validation never ran.
- A silent wrong value is worse than an error. Nobody notices `1.9` becoming `1`.
- A review comment is a hypothesis, not a fact. The reviewer was not sure, so I tested it myself with one curl.
- An error message must be true for every input that reaches it. Check it against real examples (`1.9`, `"abc"`),
  not only the one you had in mind.
- Limits like `VARCHAR(255)` come from the database. Limits like "no free orders" are business rules, and need a
  conscious decision plus a boundary test.

**Concept:** Validate at the door, and keep the database as the last line of defense. The API should reject bad
input early, with a clear `4xx` that says what to fix. Database constraints still protect the data if something
gets past the API, but they should never be the thing that answers the client.

## #13 — POST /orders could overwrite any order (2026-11-17)

**Commit:** `fix(order): prevent POST /orders from overwriting existing orders`

**Symptom:** I sent `POST /orders` with an extra `"id"` field in the JSON body:
- `"id": 999` (no such order) → `500 Internal Server Error` instead of a new order.
- `"id": 1` (an existing order) → `201 Created`, but no new order was created. Order 1 was **overwritten** with
  my data. Anyone could change someone else's order, for example set its price to `0`. Its `createdAt` was also
  replaced with `now()`, so the order lost its real creation date.

**Root cause:** The controller used the JPA entity `Order` as the request body (`@RequestBody Order`), so the
client controlled *every* field of the entity, not only the ones it should. Two behaviours combined:
1. `Order` has no `setId()`, but Jackson still writes the JSON value into the private `id` field, because the field
   has a getter. So the client's `id` reached the entity.
2. `repository.save(order)` looks at the id. If it is not `null`, Spring Data thinks the order already exists and
   runs a *merge* (`UPDATE`) instead of an `INSERT`. With id 999 Hibernate found no row to update and threw an
   exception (the 500). With id 1 it found the row and updated it.

This is called **mass assignment**: the API accepts more fields than it should, and the database model is exposed
as the API contract.

**Fix:** I separated the API contract from the database model with DTOs (Java records):
- `CreateOrderRequest`: only the fields the client is allowed to decide (`customerName`, `item`, `quantity`,
  `price`). No `id`, no `createdAt`.
- `OrderResponse`: what the client sees, including the computed `total`.
- `OrderMapper` (a stateless `@Component`) converts request → entity and entity → response. The controller never
  receives or returns `Order` anymore.

Spring Boot ignores unknown JSON fields, so an `"id"` in the body is now simply dropped. Verified with curl
(`POST` with `"id": 1` created a new order 2, order 1 unchanged) and an integration test
`postWithClientIdDoesNotOverWriteExistingOrder`. I watched it fail against the old code
(`Expected: not <2> but: was <2>`) before making it pass.

**Also learned:**
- A test must fail **for the right reason**. My first two "watch it fail" attempts failed for other reasons
  (a `400` from a broken GET, then "Ambiguous mapping" from two `@PostMapping` methods on the same URL).
- A Hamcrest `jsonPath(...)` matcher checks nothing unless it is inside `.andExpect(...)`.
- Text blocks need `%d` + `.formatted(value)` to insert a value. Writing `"id": id` just sends the text `id`.
- The running app must be restarted to pick up code changes. I first tested old code and was confused.
- An identity sequence gives the *next* value, not `max + 1`. Gaps in ids are normal.

**Concept:** The API is a contract with the outside world; the entity is an internal storage detail. Keeping them
separate means a client can only send what you explicitly allow, and the database schema can change without
breaking clients.

---

## #12: Green tests, broken upgrade (2026-11-17)

**Commit:** `fix(order): migrate existing databases to Flyway with idempotent V2`

**Symptom:** PR #7 passed every check (tests, CodeQL, Trivy). The Claude review still flagged it: *"Upgrade path
breaks every existing database."* My kind cluster's Postgres has a 50-day-old PersistentVolume holding a table that
Hibernate created, with no `flyway_schema_history`. Deploying the new image would have crash-looped the pods,
the same failure as #11, but this time on data I couldn't throw away with `down -v`.

**Root cause:** My tests only proved the *fresh install* path. Testcontainers always starts from an empty database,
so V1 always runs cleanly. The *upgrade* path (an existing database created before Flyway) was never tested by anything.

**Fix:**
1. Looked before fixing. I counted the rows that would break the new rules:
   `SELECT count(*) FILTER (WHERE price IS NULL) ... FROM orders;` → 2 rows, 0 violations, so no data cleanup was needed.
2. Turned on `SPRING_FLYWAY_BASELINE_ON_MIGRATE: "true"` in `k8s/configmap.yaml` only, never in
   `application.properties`. Baseline marks V1 as done on the cluster without running it.
3. Wrote V2, which adds the constraints V1 promised. It has to work on both kinds of database:
   - On kind: baselined, with no constraints.
   - On fresh databases: V1 already added them, so a plain `ADD CONSTRAINT` fails with `already exists`.

   So V2 is idempotent: `SET NOT NULL` and `SET DEFAULT` are safe to repeat, and each CHECK is
   `DROP CONSTRAINT IF EXISTS` followed by `ADD CONSTRAINT`.
4. Removed the baseline flag after the rollout. It only matters when there is no history table, and leaving it
   on would silently baseline the next wrong database.

Verified with `flyway_schema_history` and `\d orders` on both databases:

| | Local (compose) | Cluster (kind) |
|---|---|---|
| V1 | `SQL` | `BASELINE` |
| V2 | `SQL` | `SQL` |
| Schema | same NOT NULLs, default and CHECKs | same, and the existing 2 rows kept |

**Also learned:**
- Never edit a migration that has already run. Flyway stores its checksum, and every database that ran it refuses
  to start. Every change goes in a new `V<n>` file.
- In `ALTER TABLE`, each comma-separated action does one thing, and a CHECK belongs to the table, not to a column.
- `kind load docker-image` is needed because kind can't see local images. `rollout restart` is needed because the
  `:local` tag didn't change.
- Keep one-time steps as separate commits (flag on, then flag off), so the history shows the procedure.

**Concept:** A schema change has two paths, fresh install and upgrade, and tests usually cover only the first.
A migration must take *every* existing state to the same final state; that is what idempotent means.

---

## #11: The migration that never ran (2026-11-16)

**Commit:** `feat(order): manage schema with Flyway migrations`

**Symptom:** Running `./mvnw spring-boot:run -pl services/order` first failed with an unexpected error:
`password authentication failed for user "orderflow"`. After fixing that, I got the error I expected:
`Failed to initialize dependency 'flywayInitializer'`. The last `Caused by:` said: found non-empty schema
"public" but no schema history table. Use baseline() or set baselineOnMigrate to true.

**Root cause:** Two separate problems.
1. Two programs were listening on port 5432: the Postgres in Docker and a Postgres installed directly on
   Windows. `localhost:5432` reached the Windows one, which has no `orderflow` user.
2. Hibernate (`ddl-auto=update`) had already created the `orders` table, but Flyway had no history.
   Running V1 would fail with `relation "orders" already exists`, so Flyway refused. When I forced
   `baseline-on-migrate=true`, Flyway marked V1 as applied **without running it**: the history said
   `BASELINE`, `\d orders` showed no NOT NULL, no CHECK and no default, and the app **still started green**,
   because `validate` only checks types.

**Fix:** Stopped the Windows Postgres service and set it to Manual startup (admin PowerShell). Reset the dev
database with `docker compose down -v` so the old table didn't survive, then let Flyway migrate from scratch.
Verified with `flyway_schema_history` showing `type = SQL`, and `\d orders` listing NOT NULLs, `DEFAULT now()`
and both `Check constraints`.

**Also learned:**
- "Auth failed" can mean *wrong server*, not wrong password. `netstat -ano` + `Get-Process -Id` showed who
  owned the port. Stopping a Windows service needs an admin PowerShell.
- Read the **last** `Caused by:` in a Spring stack trace; the top line is only the wrapper.
- `ddl-auto=validate` checks tables, columns and types, not constraints, nullability or identity.
- `down -v` is only OK for dev. In production I would baseline knowingly, then write a V2 that adds the
  missing constraints. Database rules apply to all data, not only new data: old data has to be cleaned
  before a new rule can be added.

**Concept:** The schema history table is a *record* of which migrations ran, and Flyway trusts it without
checking the real schema. Baseline writes that record without doing the work, so the record and reality can
disagree silently. Refusing to start is the safe default when the state is unknown.

---
## #10: 12 CVEs I never asked for (2026-10-31)

**Commit:** `fix(deps): bump Spring Boot to 4.1.2 to patch critical CVEs`

**Symptom:** I added a Trivy image scan to CI and the first run went red. The log only said `Process completed with exit code 1`, no list of what was wrong.
The findings were in the Security tab: 6 CRITICAL and 6 HIGH, all inside `app.jar/BOOT-INF/lib/`:
`tomcat-embed-core` 11.0.22 (4 critical), `spring-webmvc` 7.0.8 (2 critical), `jackson-core`/`jackson-databind` 3.1.4 (5 high), `postgresql` 42.7.11 (1 high).
The base image (Ubuntu 26.04) was clean. Every problem was in my JAR.

**Root cause:** Tomcat, Spring MVC and Jackson aren't in my `pom.xml` at all. They are transitive dependencies: I declare `spring-boot-starter-webmvc` and `spring-boot-starter-data-jpa`, and those pull them in.
The Postgres driver I do declare, but without a `<version>`. So none of these versions are picked by me, they're all picked by the `spring-boot-starter-parent` (the BOM). So I was shipping whatever 4.1.0 decided, and 4.1.0 had become old.
CI had been green for weeks the whole time. Green only meant "it compiles and the tests pass", not "it's safe to run".

**Fix:** One line: parent `4.1.0` -> `4.1.2`. I first went to 4.1.1, but 4.1.2 was already out, and comparing the two `spring-boot-dependencies` poms showed 4.1.2 moves every vulnerable library further (Tomcat 11.0.27, Spring 7.0.10, Jackson 3.1.7, Postgres driver 42.7.14).
No `<version>` overrides on single libraries: overriding Tomcat by hand would fix today's CVE, but then the parent stops managing it and I'd have to remember it forever.

**Verified:** Locally `./mvnw verify` compiled and the unit tests passed, but `OrderApiIT` failed with `Could not find a valid Docker environment` because Docker Desktop wasn't running (Testcontainers needs it). The real check is the PR: Image scan must go green with 0 CRITICAL/HIGH.

**Also learned:**
- Trivy with `format: sarif` ignores `severity: CRITICAL,HIGH` for the report and uploads everything (MEDIUMs too). It needs `limit-severities-for-sarif: true` so the Security tab matches what fails the build.
- `ignore-unfixed: true`: only fail on CVEs that have a fixed version. A CVE with no fix is something to watch, not something I can act on today.
- The Claude review job failed with `401 Unauthorized - Claude Code is not installed on this repository`. The OAuth token secret isn't enough, the Claude GitHub App also has to be installed on the repo.
- Dependabot would have opened this exact bump as a PR. The scanner finds the problem, Dependabot keeps it from coming back.

**Concept:** Most of the code I ship isn't mine. My `pom.xml` lists a few starters, but the JAR contains dozens of libraries I never chose, and each one can go bad after I stop looking. Security isn't a state the build is in, it decays over time, so the scan has to keep running on unchanged code.

---

## #9: Green build, zero tests (2026-10-11)

**Commit:** `test(order): add Testcontainers integration test for create and fetch`

**Symptom:** I ran `./mvnw test` and got `BUILD SUCCESS`, but there was no `Tests run:` line in the log: my new `OrderApiIT` test never ran.
A CI pipeline built on that command would have stayed green forever while testing nothing.

**Root cause:** 1. Surefire (phase `test`) runs only `*Test`, `*Tests`, `Test*`, `*TestCase`.
`*IT` classes belong to Failsafe (phase `integration-test` / `verify`), which isn't on by default. 2. Surefire doesn't fail when it finds 0 tests.
So "nothing ran" looks exactly like "everything passed".

**Fix:** I've also added `maven-failsafe-plugin` with both goals:
`integration-test` runs the tests, `verify` fails the build if one failed (without it, a red test still gives `BUILD SUCCESS`).
No `<version>`, because the Spring Boot parent manages it.

**Verified:** `./mvnw -pl services/order verify` now shows `failsafe:3.5.6:integration-test` with `Tests run: 1, Failures: 0` for `OrderApiIT`, then `failsafe:3.5.6:verify`.
Then I broke the test on purpose: changed `.value("Tila")` to `.value("Bob")` and it went red (expected `Bob`, actual `Tila`). Changed it back, green again. A test I've never seen fail could be testing nothing.

**Also learned:**
- The test runs against a real `postgres:18` (same image as the StatefulSet) via Testcontainers, not H2. H2 is a different engine pretending to be Postgres: different SQL, different DDL from `ddl-auto`, no `SKIP LOCKED` the way Postgres does it, no WAL for Debezium. Green on H2 can still mean broken on Postgres.
- `@ServiceConnection` replaces my datasource config: the log showed `jdbc:postgresql://localhost:49588/test`, a random port and a database called `test`, not my `DB_HOST`/`DB_NAME`. Ryuk is the extra container that cleans up even if the test crashes.
- `standaloneSetup(new OrderController())` is a unit test (no Spring, no DB). `@SpringBootTest` + `@AutoConfigureMockMvc` runs the whole app against the container. I mixed both on my first try.
- Don't hardcode the id from a previous run: the database picks it. Read it from the live response (`andReturn()` -> `getContentAsString()` -> `JsonPath.read(body, "$.id")`), and keep create + fetch in one test, because JUnit doesn't guarantee test order.
- Testcontainers 2.x renamed things: artifacts are `testcontainers-postgresql` / `testcontainers-junit-jupiter`, the class is in `org.testcontainers.postgresql`. Most tutorials still show the old names, start.spring.io doesn't.

**Concept:** A green signal only means something if it could have been red. "0 tests ran" and "all tests passed" print the same `BUILD SUCCESS`, so every check needs proof that it ran and that it can fail.

---

## #8: One NO to spare (2026-10-06)

**Commit:** `fix(k8s): add startupProbe so liveness cannot kill a booting pod`

**Symptom:** Nothing broke. This is a bug that hadn't happened yet.
During the step 13 break-things lab I watched new pods start: they went from 0/1 to 1/1 after 28-31s. My liveness probe starts checking at 15s, so the app's boot time was getting close to the moment liveness would kill it.
This had already been flagged as a thin margin in #7, and with the app growing it would only get worse, so I fixed it before it turned into a real outage.

**Root cause:** The app now boots in ~28-31s, but liveness started at 15s and restarts after 3 NOs in a row, every 10s: 15 + 10 + 10 = 35s.
During boot it got 2 NOs (at 15s and 25s) and the YES at 35s. The pod survived with one NO to spare.
Liveness can't tell "still booting" from "frozen", both look like no answer. If boot gets ~5s slower (more code, slower machine), every new pod gets killed while booting, boots again, gets killed again: a restart loop.

**Fix:** Added a `startupProbe` on `/actuator/health/liveness`: period 3s x failureThreshold 20 = ~61s boot budget (about 2x the boot time). Liveness and readiness are paused until it says YES once, then it retires. Liveness `initialDelaySeconds` went from 15 to 0, because it no longer has to guess the boot time.
My first try was period 3 x threshold 10 (copied from readiness) = 31s, which is zero margin. A failed startupProbe restarts the container just like liveness, so it needs a real margin.

**Verified:** Before the fix, `kubectl get events --field-selector reason=Unhealthy` showed a `Liveness probe failed ... connection refused` on a new pod during boot (no restart, but one NO closer). After the rollout, the new pods show `Startup probe failed ... connection refused` x8 (harmless, 8 of 20 allowed), zero `Liveness probe failed`, 0 restarts. `kubectl describe pod` lists all three probes.

**Also learned (rest of the lab):**
- Delete an api pod: the ReplicaSet creates a new one with a new name at the same second, but it is only Ready after ~32s. With 2 replicas: 0 errors. With 1 replica it would be a ~32s outage. Self-healing is not zero downtime, redundancy is.
- Delete `postgres-0`: same name, same disk (PVC), new IP (`.7` -> `.16`). Result: 1x 500, then 18s with no answer at all, then it healed without restarting the api (Hikari threw away the dead connections). The api stayed 1/1 Ready the whole time. A failure is often a hang, not an error, and a hang is what makes users click "Pay" twice.
- My first persistence check returned `[]` and proved nothing, because the table was already empty. Seed data first: an experiment that can't fail proves nothing.
- `kubectl scale` to 4 starts both new pods at once (scaling is not a rollout). Scaling down kills the newest pods first. The next `kubectl apply` resets replicas to what the file says: the file is the truth.
- Broken readiness path (`/actuator/health/nonsens`): the rollout got stuck with 1 new pod at 0/1 and the 2 old pods kept serving. No user noticed. Compare with #4, where broken liveness took everything down. The probe event said `statuscode: 404` = the app is fine, the URL is wrong.
- Fix order at 3am: `kubectl rollout undo` to stop the bleeding, then fix the file and `apply` so git and the cluster agree. `kubectl diff -f` with no output = in sync (on Windows it needs Git's `diff.exe` on PATH).

**Concept:** A health check that restarts things must be able to tell "slow" from "dead". If it can't, the cure becomes the disease: the restart loop is caused by the check itself. Give boot its own budget (startupProbe) and keep the runtime check strict.

---

## #7: A bug with 0 failures (2026-10-04)

**Commit:** `feat(k8s): add preStop sleep and termination grace period for graceful rollouts`

**Symptom:** I expected some requests to fail during a rolling update, because the pod gets SIGTERM while the Service still sends it requests.
I ran a curl loop from a pod inside the cluster against `http://orderflow-api/orders` while running `kubectl rollout restart`.
Result: 18,400 requests, 0 failures. The one `000` I saw was before the rollout started, not during it.
So the bug I was trying to fix never showed up.

**Root cause:** Since 3.4, Spring Boot uses `server.shutdown=graceful` by default: on SIGTERM it stops accepting new requests and waits for in-flight requests to finish before exiting.
The default rolling update settings (`maxSurge=1`, `maxUnavailable=0` with 2 replicas) mean that the new pod is created and Ready before the old pod is killed.
On my single-node kind cluster, the pod was removed from the Service before the app stopped accepting requests, so no new request ever reached a closing pod.
But on a real cluster with many nodes, every node has to update its routing (kube-proxy), and during that delay a new request can still arrive at the old pod.
Graceful shutdown doesn't help here, because the app already stopped accepting new requests, so that request gets refused.

**Fix:** Added `lifecycle.preStop.sleep.seconds: 10` to the container. The pod is removed from the Service right away, but the app only gets SIGTERM after 10 seconds, so it keeps serving while every node updates its routing.
Raised `terminationGracePeriodSeconds` to 45, because the grace countdown starts before preStop: it has to cover the 10s sleep plus the app's own shutdown.

**Verified:** The first `rollout restart` after applying showed no 10s wait, because the pods being terminated were created from the old template, which had no preStop.
The second restart showed exactly 10 seconds between `Terminating` and `Error` (read from AGE), and the curl loop still had 0 failures.

**Also learned:** The `Error` status on terminated api pods is exit code 143 = 128 + 15 (SIGTERM). The JVM always exits like this on SIGTERM, so it's cosmetic, not a bug.
If the grace period is shorter than preStop (e.g. 5 < 10), the app never even gets SIGTERM: the pod is SIGKILLed in the middle of the sleep.
The default `maxSurge`/`maxUnavailable` are 25%, rounded up and down: 1/0 with 2 replicas, but 1/1 with 4, so the defaults change as you scale.
`kubectl port-forward` pins one pod and dies when that pod is terminated, so load tests during a rollout have to run from a pod inside the cluster.
Boot now takes ~25-30s, but liveness starts at 15s, and it failed once at 20s. The margin is thin, so a startupProbe is worth revisiting.

**Concept:** Removing a pod from the Service and stopping the pod happen in parallel, not in order, so the system is only eventually consistent about where traffic goes. A 0 in a small test doesn't prove the race is gone, only that the window was too small to hit.

---

## #6: The Silent failure (2026-09-27)

**Commit:** `refactor: migrate from MySQL to PostgreSQL with persistent storage`

**Symptom:** I changed mysql.yaml and ran kubectl apply. The mysql pod was killed and recreated. 
Everything looked healthy: the api pods stayed 1/1 Ready with no restarts, and /actuator/health/readiness said UP. 
BUT when I tried to see what's inside the orders table I got an error: GET /orders returned HTTP 500: 
`Table 'orderflow.orders' doesn't exist`.

**Root cause:**  mysql had no volume, so the data lived inside the pod. When apply replaced the pod, the new one started with a fresh empty database.
Hibernate's  `ddl-auto=update` only creates tables when the api starts, so the running api never noticed the table was gone. And the health checks didn't catch it, because they only check if the process is up, not if the table the app needs exists.

**Fix:**  Quick fix: `kubectl  rollout restart deploy/orderflow-api`, so  the api starts again 
and ddl-auto recreates the table. 
Real fix: switched to Postgres as a Statefulset with a persistent volume (volumeClaimTemplates), so the data lives outside the pod.

**Verified:** created an order, ran `kubectl delete pod postgres-0`, and the order was still there when the pod came back up without restarting the api.

**Also learned:**  mysql had no readiness probe, so it showed 1/1 after ~3 seconds, before it was really ready.
With a Deployment, a rolling update can briefly run two DB pods on the same disk, which corrupts the data. A StatefulSet stops the old pod before starting the new one, and reattaches the same disk instead of copying it.

**Concept:** 1. A health check tells you the process is up, not that everything it depends on works. 2. pods are 
disposable, so data must live outside the pod, on  a persistent volume. 

---
## #5: The crash that nobody noticed (2026-09-26)

**Commit:** `feat(k8s): add resource requests and limits`

**Symptom:** Break-it lab: I set the memory limit to 40Mi and applied it. The new pod was OOMKilled over and over,
but the two old pods stayed `1/1 Running` the whole time:

```text
$ kubectl get pods -w
NAME                             READY   STATUS             RESTARTS      AGE
orderflow-api-57bc6bb646-mfzzg   1/1     Running            0             8m22s
orderflow-api-57bc6bb646-p8nhv   1/1     Running            0             7m15s
orderflow-api-5d9cc7cf9b-9lvmf   0/1     Running            1 (3s ago)    5s
orderflow-api-5d9cc7cf9b-9lvmf   0/1     OOMKilled          1 (4s ago)    6s
orderflow-api-5d9cc7cf9b-9lvmf   0/1     CrashLoopBackOff   1 (5s ago)    10s
orderflow-api-5d9cc7cf9b-9lvmf   0/1     Running            2 (13s ago)   18s
orderflow-api-5d9cc7cf9b-9lvmf   0/1     OOMKilled          2 (16s ago)   21s
orderflow-api-5d9cc7cf9b-9lvmf   0/1     CrashLoopBackOff   2 (10s ago)   30s
```

The old pods survived because of the readiness probe, which protected them from being killed:
the rolling update created one new pod, and an old pod can only go once a new pod is Ready (+ `minReadySeconds`).
The new pod was never Ready, so the rollout just got stuck.

**Root cause:** A 40Mi limit, while the JVM needs about 210Mi just idle, so the kernel killed it after ~4s, before any probe ran.

**Fix:** My first limit was 256Mi. I measured the memory usage of the JVM with
`kubectl exec deploy/orderflow-api -- cat /sys/fs/cgroup/memory.current` and it was about 222Mi (~87% of the limit, while idle),
so I raised it one more time to 512Mi with requests = limits. Now it's ~210Mi of 512Mi (~41%), 0 restarts.
Healing it went back to the old ReplicaSet (`57bc6bb646`), same template = same hash.

**Also learned:**
- The difference between `requests` (the scheduler uses it to pick a node) and `limits` (the kernel enforces it), and
  what happens when CPU and memory hit the limit: CPU gets throttled (slow), memory gets OOMKilled (exit 137).
- The Java detail: once the JVM has grown its heap, it rarely gives that memory back. Anything above the request is
  "borrowed" memory, and a JVM holds onto it. That's why a common practice for Java is memory: requests = limits.
- This doesn't mean the QoS class is Guaranteed. For it to be Guaranteed, requests and limits must be equal for memory **and** CPU:

  ```text
  $ kubectl get pods -o custom-columns=NAME:.metadata.name,QOS:.status.qosClass
  NAME                             QOS
  mysql-7b954db468-krbm2           BestEffort
  orderflow-api-57bc6bb646-mfzzg   Burstable
  orderflow-api-57bc6bb646-p8nhv   Burstable
  ```
- MySQL has no `resources:` at all, so it's BestEffort, the first pod evicted when the node runs low on memory.
- `kubectl top` doesn't work on kind (no metrics-server); `cat /sys/fs/cgroup/memory.current` / `memory.max` does.
- `kubectl exec deploy/...` hit an **old** pod during the rollout and showed the old 256Mi limit. Check which pod you're on.

**Concept:** A pod that crashes before it becomes Ready is caught by the rollout, so the old pods keep serving.
The dangerous failures are the ones that happen after Ready (like #4).

## #4: The green rollout that took everything down (2026-09-21)

**Commit:** `feat(k8s): add liveness/readiness probes and minReadySeconds`

**Symptom:** Break-it lab: I pointed the liveness probe at a bogus path (`/actuator/health/nonsense`) and applied it.
The rollout **completed successfully**, both old pods were deleted, and a minute later every `orderflow-api` pod
was restarting in a loop and ended up in `CrashLoopBackOff`.

**Root cause:** Readiness and liveness run independently, each on its own timer. The new pods passed readiness
at ~11s, so the Deployment counted them as available and immediately killed the healthy old pods. Liveness only
started judging at 15s and killed the container after 3 failures, at ~35–40s
(`initialDelaySeconds + periodSeconds × (failureThreshold − 1)` + shutdown time). By then the rollout was done
and there were no healthy pods left to fall back to.

**Fix:** Reverted the path, and added `minReadySeconds: 35`: a new pod must stay Ready for 35s
(past the ~40s liveness kill) before the rollout trusts it and removes an old pod.
Verified: the next rollout started the second new pod 46s after the first (11s ready + 35s probation), and both
pods held 0 restarts.

**Also learned:**
- **Liveness** = "am I stuck?" → failure **restarts** the container (expensive). **Readiness** = "can I take
  traffic?" → failure only **removes the pod from the Service** (cheap, reversible). So liveness must be the
  *more patient* of the two. My first attempt (`period 1 × threshold 1`) would have killed pods on a single GC pause.
- Never point liveness at an external dependency (DB). A DB outage would restart every pod at once: a restart storm.
  Restarting doesn't fix a DB you can't reach, just like rebooting your laptop doesn't fix the Wi-Fi.
- Probes only *ask*. They never fix anything; Kubernetes only reacts to the answer.
- `kubectl describe pod` shows the defaults I didn't set (`timeout=1s`, `#success=1`).
- CrashLoopBackOff waits longer after each crash (10s → 20s → 40s … 5 min).
- Rolling back to an identical pod template reuses the old ReplicaSet (same hash), which is also how `kubectl rollout undo` works.
- A JVM stopped with SIGTERM exits with code 143, which `kubectl get pods` shows as `Error`. It's harmless here; graceful shutdown comes later.
- Readiness `periodSeconds` bounds time-to-Ready: a check that fails at 1s waits a full period for the next one. Tuned readiness from 10s × 3 to 3s × 10 (same 30s "leave traffic" window), and time-to-Ready dropped from 11s to 8–10s. Because `minReadySeconds` counts from Ready, I had to recheck it and raised it to 39 (Ready ~10s + 39 = 49s > liveness kill ~40s).

**Concept:** a health check that's wrong is worse than none, because it turns a healthy system into an outage.
"Ready once" is not the same as "healthy": a deploy gate has to observe a pod long enough to catch failures that show up later.

## #3: The Secret that wasn't (2026-09-20)

**Commit:** `feat: order service with Docker and Kubernetes manifests`

**Symptom:** After splitting the config into `configmap.yaml` + `secret.yaml`, the app kept working, but
`kubectl get secret orderflow-secret -o jsonpath='{.data}'` showed **five** keys: `DB_USER`, `DB_PASSWORD`,
`DB_USERNAME`, `username`, `password`.

**Root cause:** Two problems stacked. (1) `kubectl apply` can't prune keys removed from a `stringData` manifest:
the last-applied annotation records `stringData`, while the object stores base64 `data`, so deleted keys never
show up in the diff and just accumulate. (2) It was invisible because `application.properties` uses defaults:
`${DB_USER:orderflow}` silently falls back to the same value, so a broken Secret still "worked".

**Fix:** `kubectl delete secret orderflow-secret`, re-applied, and corrected the keys (key = env var name, value = secret).
Verified with `kubectl exec deploy/orderflow-api -- printenv | Select-String "DB_"`, which shows exactly five vars.

**Also learned:** env vars from ConfigMaps/Secrets are injected **at container start**, never live-updated.
If you change one, nothing happens until the pods restart.

**Concept:** silent failure via fallback defaults. A system can look correct because the wrong path happens to
produce the same answer. Correctness you haven't *proven* is correctness you don't have.

## #2: The cluster that fixed itself (2026-09-20)

**Commit:** (no code change)

**Symptom:** After a 3-week break and a Docker restart, the `orderflow-api` pods showed `Error` with RESTARTS
climbing, while `mysql` was `Running`. AGE stayed at 25 days.

**Root cause:** Kubernetes has no `depends_on`. MySQL is ephemeral (no volume), so it spent ~60s re-initializing
and refusing connections. Spring Boot fails fast on an unreachable datasource, so the app exited and the kubelet
restarted the container, with backoff.

**Fix:** None needed. Once MySQL was up, the next restart succeeded and both pods went `1/1 Running`.
The proper hardening is a readiness probe (see #4).

**Also learned:** RESTARTS climbing while AGE stays the same means the **kubelet** restarted the *container in place*.
A new *pod* (AGE reset) only comes from the ReplicaSet, when a pod is deleted or its node dies.
A kind cluster is just a Docker container: `docker start orderflow-control-plane` brings it back.

**Concept:** convergence by retry. There are no ordering guarantees between components; the system retries until
actual state matches desired state.

## #1: The first crash loop: "Connection refused" (2026-08-25)

**Commit:** `feat: order service with Docker and Kubernetes manifests`

**Symptom:** First `kubectl apply` of the Deployment: pods went straight to `CrashLoopBackOff`.

**Root cause:** Read the stack trace bottom-up (`docker run --rm orderflow-api:local`): MySQL `Connection refused`.
The app defaulted `DB_HOST` to `localhost`, but inside a pod `localhost` is the pod itself, and there was no DB in the cluster.

**Fix:** Ran MySQL in the cluster behind a Service named `mysql`, and injected `DB_HOST=mysql` etc. through a
ConfigMap + Secret with `envFrom`. No code change was needed, because the app is env-var driven.
Verified with `kubectl port-forward` + `curl.exe /actuator/health`, which returned `UP`.

**Also learned:** YAML maps vs lists (`-` only for list items like `containers`, `envFrom`), quote numbers that
must be strings (`"3306"`), `stringData` is case-sensitive, and on PowerShell `curl` is an alias, so use `curl.exe`.

**Concept:** service discovery. Pods find each other by Service name via cluster DNS, never by `localhost` or IP.
