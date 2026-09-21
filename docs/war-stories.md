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

## #4 — The green rollout that took everything down (2026-09-21)
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

## #3 — The Secret that wasn't (2026-09-20)
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

## #2 — The cluster that fixed itself (2026-09-20)
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

## #1 — The first crash loop: "Connection refused" (2026-08-25)
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
