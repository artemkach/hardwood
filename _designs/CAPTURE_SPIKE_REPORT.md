# Plan-Capture Spike: JFR vs Custom Observer

**Status:** spike complete — feasibility answers, not shipping interfaces.
**Branch:** `capture`. **Build:** `./mvnw clean verify` green (575 tests, all
Error Prone / license / spotless gates pass).
**Scope source:** [perf-sandbox-2.md](../../hardwood-research/docs/research/perf-sandbox-2.md)
§2 spike contract (a), §4.2 capture-mechanism go/no-go, §B.5 build order.

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

### Mechanism-neutral core — `dev.hardwood.internal.capture`

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

### JFR mechanism — `dev.hardwood.jfr.capture`

Six `@Enabled(false) @StackTrace(false)` event classes (`PlanNodeEvent`,
`PlanRequirementEvent`, `PlanEdgeEvent`, `PlanSealedEvent`, `RequestEvent`,
`ExecutionSealedEvent`); `JfrCaptureSink` with a **cached**
`EventType.getEventType(...).isEnabled()` guard read once at construction;
`JfrRecordingReader` that parses a dumped `.jfr` into a `RecordSet` and flags
`jdk.DataLoss`.

### Observer mechanism — `dev.hardwood.internal.capture.ObserverCaptureSink`

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

## 5. What is explicitly NOT in this spike

Per §B.5 ("feasibility answers, not interfaces") and §12's exclusions:

- **No scorer** — deliberately out of the capture spike (it is a separate,
  pure, hand-testable deliverable). `StaticFetchPlan` is shaped to feed it.
- **No canonical JSON serialization on disk** — `StaticFetchPlan` is
  reconstructed in-memory; persistence across isolated contender JVMs is
  implementation-scope for the agreed design, not a feasibility question.
- **No CLI / entry-point skeletons** — the doc forbids them pre-negotiation.
- **No multi-row-group / prefetch-chain / retry capture** — v0 is
  single-plan, statically-known first reads; the async-planning-publisher
  tracking for the general case is named future work.
- **No lane-2 transport, calibration, or flagship table** — separate spike
  deliverables (c) and beyond.
- **`StaticFetchPlan` / capture types are all `internal`** — no supported
  public API added, honoring the minimal-surface rule.

---

## 6. Test inventory

| Test | Proves |
|---|---|
| `PlanExtractorLossTest` (13) | Loss/mutation/duplicate/unplanned detection; order-independent hash; schema/manifest checks — mechanism-independent. |
| `ObserverCaptureEndToEndTest` (3) | Real-planner reconstruction (fused + indexed), concurrent-reader isolation. |
| `JfrCaptureRoundTripTest` (2) | JFR dump→parse→extract reconstructs exactly; disabled family emits nothing. |
| `SplitVersusFusedConformanceTest` (2) | Lazy-sequential identity holds in both plan shapes via the gap override. |
| `DisabledPathOverheadTest` (1) | Coarse disabled-path guard; prints indicative overhead. |
| `CaptureOverheadBenchmark` (JMH) | The authoritative four-configuration equivalence gate (run on a fixed host). |

All pass in `./mvnw clean verify`.
