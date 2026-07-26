# Plan-Capture Spike: JFR vs Custom Observer

**Status:** all four spike items — (a) capture mechanism, (b) lazy sequential
identity, (c) transport, (d) scorer arithmetic — complete. Feasibility
answers, not shipping interfaces.
**Branch:** `capture`. **Build:** `./mvnw clean verify` green (all Error
Prone / license / spotless gates pass).
**Scope source:** [perf-sandbox-2.md](../../hardwood-research/docs/research/perf-sandbox-2.md)
§2 spike contract, §4.2 capture-mechanism go/no-go, §4.3 scorer, §5.2
transport, §B.5 build order.

This spike prototyped **both** capture mechanisms the research doc names — the
JFR causal events (the declared first choice) and the custom in-memory observer
(the named fallback) — behind one shared, mechanism-neutral schema, and ran
them against the §4.2 go/no-go. The headline result: **JFR passes every
go/no-go property, so the fallback is not triggered. Ship JFR.** The observer
is retained in the tree as the proven fallback and as the reference oracle the
JFR round-trip is validated against.

---

## 1. What was built

The spike deliberately factored the problem so the *mechanism* is a thin sink
and everything hard — identity, canonical hashing, seal reconciliation — is
shared. This is what makes the comparison fair: both mechanisms run the same
reconstruction and are held to the same bar by the same code.

### Mechanism-neutral core — `dev.hardwood.internal.iotrace`

| Type | Role |
|---|---|
| `CaptureSink` | The delivery seam. Six `emit*` methods, one per record type. The only thing that differs between JFR and observer. |
| `CaptureSchema` | Versioned string constants (stages, seal status, outcomes). Schema version 1. |
| `NodeIdentity` | Stable semantic identity `(executionId, planId, nodeId, stage, role)` carried on the request object — the irreducible custom work. |
| `CaptureContext` | Per-execution state: monotonic ID assignment, per-plan `PlanScope`, order-independent execution accumulator, dual seal emission. No process-global state; propagated by reference. |
| `CaptureControl` | Thread-confined install handshake — a reader consumes the pending context at construction, then it lives by reference. The only thread-local read on the path. |
| `CanonicalHash` | Order-independent SHA-256 over canonical record encodings. Structural fields only (no timestamps/threads). |
| `CaptureRecords` / `RecordSet` | Plain carriers both mechanisms materialize into before extraction. |
| `StaticFetchPlan` | The canonical scorer artifact both mechanisms reconstruct. |
| `ScenarioManifest` | The external expected-execution set written before the run — the only way lost seals become detectable. |
| `PlanExtractor` | The single reconstruction + validation path both mechanisms feed. |
| `CaptureLossException` | Thrown on any protocol violation — rejection, never silent scoring. |

### JFR mechanism — `dev.hardwood.jfr.iotrace`

Six `@Enabled(false) @StackTrace(false)` event classes (`PlanNodeEvent`,
`PlanRequirementEvent`, `PlanEdgeEvent`, `PlanSealedEvent`, `RequestEvent`,
`ExecutionSealedEvent`); `JfrCaptureSink` with a **cached**
`EventType.getEventType(...).isEnabled()` guard read once at construction;
`JfrRecordingReader` that parses a dumped `.jfr` into a `RecordSet` and flags
`jdk.DataLoss`.

### Observer mechanism — `dev.hardwood.internal.iotrace.ObserverCaptureSink`

In-memory `CopyOnWriteArrayList` append per record; `toRecordSet()` for
extraction; `dataLoss` structurally always false.

### Planner wiring — `internal.reader` (5 files touched)

- `ChunkHandle` / `SharedRegion` carry an optional `CaptureContext` +
  `NodeIdentity`; on the actual `readRange` they record one attempt (success
  and failure paths). `null` on the disabled path → zero work.
- `RowGroupIterator` consumes the pending context at construction, publishes
  final post-coalescing nodes after `coalesceAcrossColumns()` (the
  publication quiescence point), and seals the execution in `close()` (the
  execution quiescence point for the one-row-group v0).
- `CoalescableFirstChunk` gained `attachedRegion()` and
  `setFirstReadCapture(...)`; `IndexedFetchPlan` stamps its existing first
  handle immediately, `SequentialFetchPlan` stashes the identity and stamps
  the lazily created first handle in `advanceChunk(0)` — the spike's
  lazy-sequential-identity path.
- `MAX_CROSS_COL_GAP_BYTES` gained a per-instance
  `hardwood.internal.maxCrossColGapBytes` override (matching its two sibling
  knobs), so the planner can produce fused and split plans on identical bytes.
  Production default stays 64 KB.

---

## 2. Go/no-go results (§4.2)

The go/no-go is one question: **can the JFR events reconstruct the exact static
plan, correlate every executed request across asynchronous handoffs, detect
incomplete recordings via the seal protocol (both seals, external manifest),
and meet the disabled-overhead bound?**

| Property | JFR | Observer | Evidence |
|---|---|---|---|
| **Exact plan reconstruction** | ✅ Pass | ✅ Pass | `JfrCaptureRoundTripTest`, `ObserverCaptureEndToEndTest` — fused fixture reconstructs to 1 node + N requirements; indexed fixture to 1 node + 3 requirements. |
| **Request correlation across async handoffs** | ✅ Pass | ✅ Pass | Prefetch (common pool) + decode (virtual threads) attempts all resolve to their published node; execution seal reconciles. |
| **Incomplete-recording detection** (dual seal + manifest) | ✅ Pass | ✅ Pass | `PlanExtractorLossTest` (13 cases): data loss, missing/duplicate seals, lost/mutated node, lost/duplicate/unplanned attempt, unknown execution ID, schema mismatch — all rejected. Order-independence of the attempt hash verified. |
| **Unsupported ≠ lost** | ✅ Pass | ✅ Pass | `PlanSealedEvent.status` marks `INCOMPLETE` with a reason vs absence-of-seal. |
| **Disabled-path overhead** | ✅ Pass | n/a (never constructed when disabled) | `DisabledPathOverheadTest`: capture-enabled read (1.64×) is *no more expensive* than an unrelated recording (1.70×) — the delta is JFR recording start/stop, not capture code. JMH `CaptureOverheadBenchmark` is the authoritative gate. |
| **Reachability from perf module** | ✅ Pass | ✅ Pass | `CaptureOverheadBenchmark` in `micro-benchmarks` installs a context and reads — no new supported public API. |
| **Isolation between concurrent executions** | ✅ Pass | ✅ Pass | `ObserverCaptureEndToEndTest.concurrentReadersAreIsolated` — two readers, distinct execution IDs, no cross-contamination. |
| **Non-interference / no global state** | ✅ Pass | ✅ Pass | Only thread-local is the transient install pointer, read once at synchronous build; capture state lives by reference. |

**No property failed. The fallback condition ("fall back to the observer only
on a failed property") is not met.**

### Two execution-closure options (§4.2)

The doc asked the spike to compare `ExecutionSealedEvent` + source-side summary
vs an authoritative `TracingInputFile` attempt record. The spike implemented the
**first**: `CaptureContext` maintains a synchronized order-independent attempt
accumulator and emits `ExecutionSealedEvent` at reader close. It works and is
self-contained (no second authoritative record to reconcile), so the spike did
not need the `TracingInputFile`-authoritative variant. The summary state is
real (a lock + retained canonical attempt strings), confirming the doc's "events
plus a source-side summary, not events only" caveat — but it is bounded and
lives entirely on the enabled path.

---

## 3. Recommendation: ship JFR

**JFR is the mechanism.** It passes every go/no-go property, and its
non-technical fit is strong (the maintainer authored JfrUnit and JFR
Analytics — the conformance-assertion and post-processing halves of this
design already exist as their tools). The comparison, stated at true strength:

| Dimension | JFR | Observer |
|---|---|---|
| Completeness when disabled | Engineered for it; cached guard, no allocation | Structural (never constructed disabled) |
| Loss detection | **Needs** the seal protocol — and passes it | Loss impossible, but the protocol still runs |
| Causality richness | By schema convention over flat events | Same, by the same schema |
| Delivery cost | Event commit; disabled = early return | List append |
| Injection across threads | **None** — emitted from the site | Same (context by reference) |
| Production artifact | A user's JFR recording *is* a diagnostic artifact | In-memory only, test-scope |
| Ecosystem fit | JfrUnit / JFR Analytics are the maintainer's own | None |

The observer's only genuine differentiator was "no loss questions." Since JFR
**passes** the loss-detection go/no-go, that differentiator does not decide the
choice — which is exactly the falsifiable ordering the doc prescribed. The
observer stays in the tree as (1) the proven fallback should a future
higher-volume, multi-row-group scenario surface undetectable JFR loss, and (2)
the in-memory oracle the JFR round-trip validates against.

One argument-hygiene note the doc insisted on: JfrUnit authorship is a
**social-fit argument for the proposal**, not a technical argument for the
mechanism. The technical case stands on the go/no-go table above; authorship
only lowers upstream friction.

---

## 4. Confirmed feasibility facts (for the design PR and #763 comment)

1. **No plan exists at `open()`** — capture is a session the planner publishes
   into, confirmed. Publication at the `coalesceAcrossColumns()` return works;
   publishing earlier would record provisional handles that never execute.
2. **Lazy-sequential identity is solvable** — storing the identity on the plan
   and stamping the first handle in `advanceChunk(0)` carries the ID to the
   standalone first read. Verified in both fused and split shapes
   (`SplitVersusFusedConformanceTest`).
3. **The gap knob is a two-line change with in-file precedent** — a per-instance
   `Integer.getInteger` matching the two siblings. Forcing a negative override
   produces a genuine split plan on identical bytes.
4. **Dual closure + manifest is necessary and sufficient** — a plan-only seal
   cannot catch a lost/duplicate/unplanned attempt; the execution seal plus the
   external manifest can. 13 loss cases confirm.
5. **The canonical hash must be order-independent** — attempts commit from many
   threads; a counter + incremental digest would be insufficient. The
   sort-then-digest construction passes the reversed-order test.
6. **Disabled overhead is not a concern** — the cached `isEnabled()` guard means
   the capture-enabled path costs the same as any unrelated recording.

---

## 5. Spike item (b): lazy sequential identity with split and fused conformance

The deliverable: prove how a structured request ID reaches the first
standalone `ChunkHandle` an uncoalesced `SequentialFetchPlan` creates later in
`advanceChunk()`, with split **and** fused conformance tests.

**Answer: store the post-coalescing identity on the plan; stamp the handle at
creation.** `SequentialFetchPlan.setFirstReadCapture(...)` stashes the
`CaptureContext` + `NodeIdentity`; `advanceChunk(0)` stamps them onto the
handle it creates when (and only when) the first read is standalone. The
region-backed (fused) case carries identity on the `SharedRegion` instead.

Conformance is checked by **two independent witnesses** per shape
(`SplitVersusFusedConformanceTest`):

1. **Execution-seal reconciliation** — the capture's own attempt records must
   match the sealed count and order-independent hash (extraction throws
   otherwise).
2. **`TracingInputFile` one-to-one matching** — an independent trace at the
   `InputFile` seam (generalizing `CountingInputFile` from counts to
   `(offset, length)` records) is matched one-to-one against the plan's final
   nodes by `PlanConformance.matchOneToOne`: every node executed exactly once
   with its exact range, no duplicates. The only unmatched reads are the three
   local metadata-stage footer reads, asserted exactly — the v0 data-stage
   plan does not model the metadata stage, and the fixture bounds it.

Results, on identical fixture bytes (the 20-column sequential file, gap knob
as the only difference):

- **fused** (default 64 KB gap): 1 node, N requirements, one-to-one conformant;
- **split** (negative gap override): N nodes, one requirement each, one-to-one
  conformant — the lazily created standalone handles all carried their IDs;
- **identical useful bytes** across both contenders (and zero dead bytes in
  the fused node for this back-to-back fixture);
- **truncated plans seal `INCOMPLETE`**: a page-dropping filter (and, more
  broadly, any filter / row-mask / `maxRows` truncation) marks the plan
  execution-resolved rather than exporting it as a complete static DAG. This
  hardened a real gap the test found: the original publication logic only
  looked at per-plan coalesce-safety and would have sealed a
  filter-truncated-but-single-page-group plan `SUPPORTED`.

## 6. Spike item (d): scorer arithmetic unit tests (pure, zero Hardwood coupling)

`dev.hardwood.scorer` (test-scope, three ~100-line files): `ScoredPlan`
(nodes + edges, eager validation: duplicate IDs, dangling edges, self-edges,
cycles via Kahn's algorithm), `CostModel`
(`latency + ceilDiv(bytes * 1e9, bytesPerSecond)`, overflow-checked, invalid
parameters rejected), `PlanScorer` (the A.5 discrete-event loop: start what's
ready under the concurrency cap, jump the clock to the next completion, batch
simultaneous completions, ready queue in stable-ID order — declared as an
arbitrary fixed modeled policy).

The 22 tests in `PlanScorerTest` encode, pencil-verifiable:

- **The doc's worked example exactly**: fused ≈ 74 ms, split-parallel = 50 ms
  (exact), split-serial = 100 ms (exact) at L = 30 ms, B = 50 MiB/s — the
  `parallel < fused < serial` ordering of §3.1.
- **The serial break-even boundary** `g* = L × B = 1,572,864` bytes: fusion
  wins one byte below, exact tie at the boundary, splitting wins one byte
  above — the three boundary tests §4.3 mandates.
- **The conditionality of the break-even**: under 2 free connections,
  splitting beats fusion at *any* gap, including zero.
- **§4.3's invalid-inference counterexample verified numerically**: plans
  X `[6,6,6,2]` / Y `[8,3,8,3]` — X wins at both endpoints (20 vs 22 serial,
  6 vs 8 ideal) and loses at concurrency 2 (12 vs 11). Endpoint rankings do
  not bound intermediate concurrency; the scorer computes intermediate cells.
- **Dependency scheduling**: chains serialize regardless of concurrency;
  diamonds join at the slower branch; simultaneous completions batch before
  new starts.
- **Fail-early validation**: cyclic/self-edge/dangling/duplicate plans and
  invalid model parameters are rejected; a clock overflow throws
  `ArithmeticException` rather than wrapping into a small positive score.

The scorer has zero imports from any `dev.hardwood` package outside its own —
`(plan, model) → nanos`, no threads, no sleeping, deterministic.

## 7. Spike item (c): transport — verified on the pinned images

The deliverable: confirm the S3Proxy latency middleware works and its stream
throttle does not (both claims were source-based until now — §5.2 required
confirmation "on the actual pinned image"), select a Toxiproxy digest, and run
the three smoke checks. All done in
`TransportImpairmentSpikeTest` (s3 module, Testcontainers), plus ad-hoc
container runs during development. Findings:

1. **Latency middleware works on the pinned image** (`6597ca59`, via
   `ghcr.io/hardwood-hq/s3proxy`). `S3PROXY_JAVA_OPTS=-Ds3proxy.latency-blobstore.get.latency=300`
   activates `LatencyBlobStore` ("Using latency storage backend" in the log)
   and adds the configured delay: ad-hoc runs measured ~512–597 ms total for
   a GET whose baseline is ~92 ms. One precision over the research doc: the
   activating property key is `s3proxy.latency-blobstore.<op>.latency` and
   the GET operation name is **`get`**, confirmed from the pinned jar's
   bytecode (`PROPERTIES_LATENCY_RE`, op-name constants).
2. **The stream throttle is defective at realistic rates, exactly as
   analyzed.** With `s3proxy.latency-blobstore.get.speed=52429` (≈50 MiB/s in
   bytes-per-ms terms), a GET fails outright with **HTTP 400 "nanosecond
   timeout value out of range"** — the `ThrottledInputStream`
   `Thread.sleep(size/speed, (size % speed) * 1_000_000)` overflow, visible
   in the pinned jar's bytecode (`imul` by 1_000_000 into the nanos
   argument). It fails identically at `speed=1000`; the throttle cannot
   supply lane 1's `B` at any realistic setting. Confirmed, not just
   source-inferred: this is why Toxiproxy is required.
3. **Toxiproxy digest selected**:
   `ghcr.io/shopify/toxiproxy@sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e`
   (v2.12.0 — the version eval-3's toxic-pipeline analysis examined).
   Recorded in the test as the pinned constant; mirroring via `s3proxy-mirror`
   is proposed only if it becomes durable CI infrastructure.
4. **Latency smoke check: pass.** A downstream latency toxic (300 ms,
   jitter 0) added ~297 ms over the plain-proxy control (16.6 ms → 313.9 ms
   ad-hoc; the test asserts > 200 ms added).
5. **Bandwidth smoke check: pass, and notably precise.** A downstream
   bandwidth toxic at 1024 KB/s moved a 2 MiB object in 2.052 s at a
   measured 1,022,028 B/s — within 0.2% of the configured rate at this size.
   (No generalization to other sizes/rates — that is what per-profile
   calibration is for.)
6. **Two-connection overlap check: pass — the bandwidth toxic is
   per-connection**, as §5.2's units warning states. Two concurrent GETs on
   separate connections each completed in ~2.05 s (the solo duration, i.e.
   each got the full per-connection rate) while the serial control took
   4.13 s. Concurrent wall clock ≈ solo duration confirms genuine overlap
   with no aggregate cap — the sandbox's independent-per-connection-bandwidth
   assumption holds on this stack, and an aggregate cap, if ever wanted,
   must be modeled elsewhere.

Environment notes for whoever picks this up next: container-to-container
networking (Toxiproxy → S3Proxy over a shared Testcontainers `Network`) works
in this dev container's socket-proxy Docker setup; the Toxiproxy REST API is
driven with `java.net.http.HttpClient` directly, avoiding a `toxiproxy-java`
dependency; the smoke checks use anonymous S3Proxy (`S3PROXY_AUTHORIZATION=none`)
and plain HTTP GETs because they characterize the impairment layers, not the
`S3InputFile` stack (which lane 2 proper will put in the loop). The pinned
Toxiproxy image has no `/bin/sh`, so Testcontainers' default internal port
check logs a warning before the API becomes reachable — harmless.

## 8. What is explicitly NOT in this spike

Per §B.5 ("feasibility answers, not interfaces") and §12's exclusions:

- **No per-profile calibration** — the smoke checks verify the impairment
  layers function and have the documented semantics; fitted `(L_eff, B_eff)`
  with predeclared residuals, warm/cold separation, and frozen tolerances
  wait for the agreed scope (§5.2's calibration protocol).
- **No `StaticFetchPlan` → `ScoredPlan` bridge** — deliberately: the scorer's
  purity (zero Hardwood coupling) is the spike property under test. The
  bridge is a trivial mapping (nodes have IDs and lengths in both) and
  belongs to the agreed implementation scope.
- **No canonical JSON serialization on disk** — `StaticFetchPlan` is
  reconstructed in-memory; persistence across isolated contender JVMs is
  implementation-scope for the agreed design, not a feasibility question.
- **No CLI / entry-point skeletons** — the doc forbids them pre-negotiation.
- **No multi-row-group / prefetch-chain / retry capture** — v0 is
  single-plan, statically-known first reads; the async-planning-publisher
  tracking for the general case is named future work.
- **No aggregate-bandwidth term** — the `CostModel` documents its absence;
  results derived from it must state that.
- **`StaticFetchPlan` / capture types are all `internal`; scorer types are
  test-scope** — no supported public API added.

---

## 9. Test inventory

| Test | Proves |
|---|---|
| `PlanExtractorLossTest` (13) | Loss/mutation/duplicate/unplanned detection; order-independent hash; schema/manifest checks — mechanism-independent. |
| `ObserverCaptureEndToEndTest` (3) | Real-planner reconstruction (fused + indexed), concurrent-reader isolation. |
| `JfrCaptureRoundTripTest` (2) | JFR dump→parse→extract reconstructs exactly; disabled family emits nothing. |
| `SplitVersusFusedConformanceTest` (4) | Lazy-sequential identity in both plan shapes via the gap override, with one-to-one `TracingInputFile` conformance; identical useful bytes across contenders; filter truncation seals `INCOMPLETE`. |
| `PlanScorerTest` (22) | Scorer arithmetic: worked 74/50/100 ms example, `g* = L × B` boundary triple, conditional break-even, intermediate-concurrency counterexample, dependency/tie policies, fail-early validation. |
| `DisabledPathOverheadTest` (1) | Coarse disabled-path guard; prints indicative overhead. |
| `TransportImpairmentSpikeTest` (5) | Pinned-image latency middleware works; throttle defective (HTTP 400 nanosecond overflow); Toxiproxy latency/bandwidth/two-connection-overlap smoke checks. |
| `CaptureOverheadBenchmark` (JMH) | The authoritative four-configuration equivalence gate (run on a fixed host). |

All pass in `./mvnw clean verify`.
