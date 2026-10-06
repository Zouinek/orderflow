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
