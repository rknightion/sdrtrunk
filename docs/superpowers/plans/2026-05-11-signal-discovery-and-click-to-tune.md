# Signal Discovery & Click-to-Tune — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add (1) click-a-signal-on-the-spectrum → auto-detect protocol → start decoding, and (2) auto-discovery scanning of a frequency range, both built on a shared `SignalClassifier` engine.

**Architecture:** New toolkit-agnostic package `io.github.dsheirer.module.discovery` holds the engine (`SignalClassifier` — runs the existing decoders transiently against a captured signal and watches the channel state machine for a lock), the spectral survey (energy-peak finder over FFT bins), and the band-scan orchestrator + discovery model. A small Swing controller in `spectrum/` adds the click/drag interaction; a JavaFX `DiscoveryEditor` tab in `gui/playlist/` shows scan results. Everything reuses existing `ProcessingChain` / `DecoderFactory` / `ChannelProcessingManager` / `TunerManager` machinery — no new DSP.

**Tech Stack:** Java 25, Swing (spectral display), JavaFX (playlist editor), JUnit 5 + (where useful) Mockito-style hand fakes, Gradle. SLF4J logging. No new runtime dependencies. GPL copyright header (see `copyright.template`) on every new file.

**Spec:** `docs/superpowers/specs/2026-05-11-signal-discovery-and-click-to-tune-design.md` — read it first.

**Plan shape:** Phase 1 (engine) is fully detailed because it's executed first and is mostly self-contained. Phases 2–5 are concrete task lists (exact files + responsibility + test approach); each phase gets a short "expand to bite-sized steps against the actual code" pass immediately before it is executed, because those tasks depend on existing-code APIs (`ProcessingChain` wiring, `OverlayPanel`/`SpectralDisplayPanel` internals, the JavaFX `PlaylistEditor` tab structure, `UserPreferences` registration) that must be read first. Build stays green and new tests pass at the end of every task; a code-review pass closes every phase.

---

## File structure (new + modified)

**New — `src/main/java/io/github/dsheirer/module/discovery/`** (engine, no UI toolkit deps):
- `SignalKind.java` — enum {CONTROL, CONVENTIONAL, TRAFFIC, UNKNOWN}.
- `LockState.java` — enum {LOCKED, PARTIAL, NONE, ERROR}.
- `ClassificationOutcome.java` — enum {IDENTIFIED, UNIDENTIFIED, NO_SIGNAL, ERROR, CANCELLED}.
- `Candidate.java` — record(DecoderType, LockState, double lockQuality, String note).
- `ClassificationRequest.java` — record(long centerFrequencyHz, int approximateBandwidthHz, EnumSet<DecoderType> candidateDecoders, Duration overallDeadline, boolean keepWinningChainRunning, String label) + a static `builder()`/factory with sane defaults.
- `ClassificationResult.java` — record(long centerFrequencyHz, ClassificationOutcome outcome, List<Candidate> candidates, DecoderType bestDecoder, DecodeConfiguration bestDecodeConfig, SignalKind kind, String summary, Map<String,String> metadata, double signalPowerDb, ProcessingChain liveChain) + static factories `identified(...)`, `unidentified(...)`, `noSignal(...)`, `error(reason)`, `cancelled()`.
- `CandidateOrdering.java` — pure function: `List<DecoderType> order(EnumSet<DecoderType> candidates, int approxBandwidthHz)`.
- `LockWatcher.java` — implements `IDecoderStateEventListener`; consumes `DecoderStateEvent`s, exposes `LockState getLockState()`, `double getLockQuality()`, `SignalKind getKind()`, `Map<String,String> getMetadata()`, `String getSummary()`.
- `ProbeChainFactory.java` — `ProcessingChain buildProbeChain(DecoderType, AliasModel, ChannelMapModel, UserPreferences)` — a decoder-only `ProcessingChain` (state + decoder modules; no audio output, no event logger, no recorder, no aux decoders) plus the `LockWatcher` wired to it.
- `ComplexSampleFanout.java` — owns one real `Source`, registers as its listener, re-broadcasts `ComplexSamples` (and forwards sample-rate/frequency `SourceEvent`s) to N subscriber probe chains via thin `ComplexSource` adapters; `addSubscriber()/removeSubscriber()/start()/stop()`.
- `ClassificationSession.java` — `AutoCloseable`; holds the acquired `Source`, the `ComplexSampleFanout`, and all live probe `ProcessingChain`s; `close()` stops+disposes everything.
- `SignalClassifier.java` — the engine. `CompletableFuture<ClassificationResult> classify(ClassificationRequest)`; constructed with `TunerManager`, `AliasModel`, `ChannelMapModel`, `UserPreferences`, `DiscoveryPreference`, and an `ExecutorService`.
- `EnergyPeak.java` — record(long centerFrequencyHz, int occupiedBandwidthHz, double powerDb, double snrDb).
- `SpectralSurvey.java` — `CompletableFuture<List<EnergyPeak>> survey(long minHz, long maxHz, Duration dwell, double thresholdDb, ProgressListener)`; in-band mode in Phase 3, stepped-sweep mode in Phase 5. Also exposes a pure static `findPeaks(float[] magnitudesDb, long binWidthHz, long baseFrequencyHz, double thresholdDb)` for unit testing.
- `DiscoveryState.java` — enum {ENERGY_DETECTED, PROBING, IDENTIFIED, UNIDENTIFIED, KNOWN, ERROR}.
- `Discovery.java` — observable model object (JavaFX `*Property` fields for the table) for one discovered signal (fields per spec §8).
- `DiscoveryModel.java` — owns an `ObservableList<Discovery>` + a `Listener<DiscoveryEvent>` broadcaster (for the Swing overlay); add/update/remove/clear; `findOverlapping(freqHz, widthHz)`.
- `DiscoveryEvent.java` — record describing an add/update/remove for the Swing side.
- `ScanRequest.java` / `ScanState.java` — record + enum per spec §8.
- `BandScanController.java` — drives a scan: resolve span → `SpectralSurvey` → per-peak `SignalClassifier` → updates `DiscoveryModel`; `startScan(ScanRequest)`, `stop()`, `shutdown()`; the operator actions (`addAsChannel(Discovery)`, `addAll(int minPips)`, `ignore(Discovery)`, `setWatched(Discovery,boolean)`, `reprobe(Discovery)`).
- `IgnoreRange.java` — record(long minHz, long maxHz, String note, Instant addedAt) + `contains(freqHz)` / overlap helpers (used by the ignore list).

**New — `src/main/java/io/github/dsheirer/preference/discovery/`:**
- `DiscoveryPreference.java` — `Preference` subclass; survey dwell/threshold, max concurrent probes/classifications, tuner headroom, excluded decoders, default scan decoders, click default bandwidth, keep-listening duration, overlay display mode, optional per-decoder probe windows, and the persisted ignore list (`List<IgnoreRange>`). Registered in `UserPreferences`.

**New — `src/main/java/io/github/dsheirer/spectrum/`** (Swing, click-to-tune):
- `ClickToTuneController.java` — takes a `ClassificationRequest` from the spectral display, calls `SignalClassifier`, on `IDENTIFIED` builds a `Channel` + `channelModel.addChannel` + starts it via `ChannelProcessingManager`; on miss shows the popup (keep-listening / pick-decoder / cancel); manages the "pending" overlay state.
- `PendingClassificationOverlay.java` — paints the in-progress selection/probe box + animated label on a layer of the `JLayeredPane` (or via `OverlayPanel`).
- `DecoderOverrideChip.java` — small Swing popup/control over a click-to-tune channel's overlay: current decoder + dropdown of other primary decoders + "Re-detect…"; on change, stop→swap `DecodeConfiguration`→restart the channel.
- `DiscoveryOverlay.java` — paints faint dashed rectangles for `Discovery` rows not yet added; subscribes to `DiscoveryModel`'s `DiscoveryEvent` listener; respects the overlay-display preference; click → posts `ShowDiscoveryRequest`.

**New — `src/main/java/io/github/dsheirer/gui/playlist/discovery/`** (JavaFX):
- `DiscoveryEditor.java` — `BorderPane`; toolbar (`Scan…`, `Stop`, progress, `Add all ≥ ▾`, `Clear finished`, `Settings`, `Manage ignored…`) + `TableView<Discovery>`; wired to `BandScanController`/`DiscoveryModel`.
- `DiscoveryTableColumns.java` (or inline cell factories) — column + cell factories (live-state icon, confidence pips, action button cell).
- `ScanDialog.java` — JavaFX dialog: frequency range (+ presets "current view"/"current tuner span"/manual), the red disruption banner shown only for stepped sweeps, decoder checkbox list, advanced (dwell/threshold/max signals), continuous toggle+interval.
- `ManageIgnoreListDialog.java` — list/remove `IgnoreRange`s.

**New — events on `MyEventBus`** (`eventbus/` or wherever `ViewChannelRequest` lives):
- `ClassifyAndTuneRequest.java` — posted by the spectral display, consumed by `ClickToTuneController` (decouples if needed).
- `ShowDiscoveryRequest.java` — focuses the Discovery tab (optionally on a given `Discovery`).
- `ScanSpanRequest.java` — "scan this view" from the spectral display → opens `ScanDialog` pre-filled.

**Modified:**
- `spectrum/SpectralDisplayPanel.java` — construct/hold a `ClickToTuneController`, `DiscoveryOverlay`, `PendingClassificationOverlay`; add Shift+drag selection + double-click handling and the new context-menu items to `MouseEventProcessor`; expose an accessor for the tuner's wideband sample stream / a way for `SpectralSurvey` to tap it; add the `DiscoveryDisplay` toggle to the "Display ▸ Channel" submenu. *(Be surgical — existing pan/zoom/right-click behaviour must be unchanged.)*
- `spectrum/OverlayPanel.java` — if reused for the pending/selection box, add a minimal "transient overlay rectangle" API; otherwise untouched.
- `gui/playlist/PlaylistEditor.java` — add the `DiscoveryEditor` as a new tab; pass it the `BandScanController`/`DiscoveryModel`.
- `preference/UserPreferences.java` — register `DiscoveryPreference` (+ a getter), mirroring the existing pattern.
- `gui/preference/` — add a `DiscoveryPreferenceEditor` pane, mirroring an existing preference editor (e.g. the duplicate/decoder one).
- Wherever the top-level managers are assembled (search for where `ChannelProcessingManager` / `PlaylistManager` / `TunerManager` are constructed and handed around — likely `SDRTrunk.java` / a `gui` bootstrap class): construct `SignalClassifier`, `SpectralSurvey`, `DiscoveryModel`, `BandScanController` (with a shared `ExecutorService`), and pass them to the spectral display + playlist editor; shut them down on exit.
- `controller/channel/Channel.java` — *only if needed* a transient (non-serialized, `@JsonIgnore`) `boolean` flag marking "created by click-to-tune" so `DecoderOverrideChip` knows when to show; alternative is a side-set in the controller (preferred — zero `Channel` change). Decide at Phase 2.

---

## Phase 1 — Engine core

### Task 1.1: Read the existing APIs the engine depends on

**Files (read only):** `module/ProcessingChain.java`, `module/Module.java` + the `I*Provider`/`I*Listener` module interfaces, `module/decode/DecoderFactory.java` (esp. `getModules(...)` / any `getPrimaryModules`), `module/decode/DecoderType.java`, `module/decode/config/DecodeConfiguration.java` + a couple of subclasses (`DecodeConfigNBFM`, `DecodeConfigP25Phase1`), `source/tuner/manager/TunerManager.java` (`getSource(...)`), `source/tuner/channel/TunerChannelSource.java`, `source/tuner/channel/ChannelSpecification.java`, `source/config/SourceConfigTuner.java`, `channel/state/DecoderStateEvent.java`, `channel/state/State.java`, `channel/state/IDecoderStateEventListener.java`, `channel/state/AbstractChannelState.java`/`SingleChannelState.java`, `sample/complex/ComplexSamples.java` + `source/ComplexSource.java`/`Source.java`, `dsp/squelch/PowerMonitor.java`, `controller/channel/Channel.java` + `ChannelType.java`, `alias/AliasModel.java`, `controller/channel/map/ChannelMapModel.java`, `preference/UserPreferences.java` + an existing `Preference` subclass (e.g. `preference/duplicate/DuplicateCallDetectionPreference.java`).

- [ ] **Step 1:** Read the above; write a short `docs/superpowers/plans/notes-phase1-apis.md` capturing the exact signatures/usage you'll rely on (constructor params, how `ChannelProcessingManager` builds+wires a chain, how modules are added, how `getModules` is parameterised, how a `Source` is consumed, how `PowerMonitor` reports level, how a `Preference` persists). This is the only "task" without a test — it de-risks every task below.
- [ ] **Step 2:** Commit the notes file. `git add docs/superpowers/plans/notes-phase1-apis.md && git commit -m "Notes: existing APIs for the discovery engine"`

### Task 1.2: Enums + simple records

**Files:** Create `module/discovery/SignalKind.java`, `LockState.java`, `ClassificationOutcome.java`, `DiscoveryState.java`, `Candidate.java`, `EnergyPeak.java`, `IgnoreRange.java`. Test: `src/test/java/io/github/dsheirer/module/discovery/IgnoreRangeTest.java`.

- [ ] **Step 1: Failing test** — `IgnoreRangeTest`: `contains()` true/false at edges; `overlaps(minHz,maxHz)` true/false; constructor rejects `min > max`.
- [ ] **Step 2:** Run `./gradlew test --tests '*IgnoreRangeTest'` → FAIL (class not found).
- [ ] **Step 3:** Implement the enums (no behaviour) and `Candidate`/`EnergyPeak` (records, validate non-null/positive in compact constructor), then `IgnoreRange` (record + `contains`/`overlaps` + validation). Copyright headers.
- [ ] **Step 4:** Run the test → PASS. Then `./gradlew compileJava` → PASS.
- [ ] **Step 5:** Commit. `git add src/main/java/io/github/dsheirer/module/discovery src/test/java/io/github/dsheirer/module/discovery && git commit -m "discovery: add core enums + Candidate/EnergyPeak/IgnoreRange value types"`

### Task 1.3: `CandidateOrdering`

**Files:** Create `module/discovery/CandidateOrdering.java`. Test: `module/discovery/CandidateOrderingTest.java`.

- [ ] **Step 1: Failing test** — given `EnumSet.copyOf(DecoderType.PRIMARY_DECODERS)`: with `approxBw = 6_250` → `P25_PHASE2` is first; with `12_500` → `P25_PHASE1` precedes `AM`; with `25_000` → `NBFM` precedes `P25_PHASE2`; with `0` (unknown) → `NBFM` first, `AM` early-ish, but all 9 present in every case; passing a subset → output is exactly that subset, reordered.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement: a small table of `(bandwidth band) → priority list`, then `order()` = stable-sort the input set by priority index. Pure, static, no deps beyond `DecoderType`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit. `git commit -am "discovery: add bandwidth→decoder candidate ordering"`

### Task 1.4: `LockWatcher`

**Files:** Create `module/discovery/LockWatcher.java`. Test: `module/discovery/LockWatcherTest.java`.

- [ ] **Step 1: Failing test** — construct a `LockWatcher`; feed it synthetic `DecoderStateEvent`s via its `IDecoderStateEventListener` (use the real `DecoderStateEvent`/`State` types from `channel/state`): (a) only IDLE events → `getLockState()==NONE`; (b) one ACTIVE then back to IDLE quickly → `PARTIAL`; (c) several consecutive CONTROL/CALL events past the debounce → `LOCKED` and `getKind()==CONTROL` for CONTROL; (d) events carrying identifiers (NAC etc. — use whatever identifier-carrying event shape exists; if state events don't carry identifiers, this watcher also implements the message/identifier listener interface — confirm in Task 1.1 and adjust) populate `getMetadata()`; (e) an ERROR/decoder-exception path → `ERROR`. Assert `getLockQuality()` is higher for fast/clean locks.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the small state machine: track last N states + timestamps; LOCKED when a non-IDLE state is sustained ≥ debounce / ≥ K consecutive events; PARTIAL on a transient non-IDLE or a sync-detect-only signal; map CONTROL→`SignalKind.CONTROL`, CALL/ACTIVE on a conventional decoder→`CONVENTIONAL`/`TRAFFIC`; collect identifiers into `metadata`; `lockQuality` from time-to-lock + state stability.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit. `git commit -am "discovery: add LockWatcher (decoder-state → lock state/quality/kind/metadata)"`

### Task 1.5: `ProbeChainFactory`

**Files:** Create `module/discovery/ProbeChainFactory.java`. Test: `module/discovery/ProbeChainFactoryTest.java`.

- [ ] **Step 1: Failing test** — `buildProbeChain(DecoderType.NBFM, aliasModel, channelMapModel, userPreferences)` returns a non-null `ProcessingChain` that (a) contains a decoder module for NBFM, (b) contains a channel-state module, (c) contains *no* audio-output / event-logger / recorder modules, (d) has the supplied `LockWatcher` registered as a decoder-state listener. Repeat for `DMR` and `P25_PHASE1`. (Use real `AliasModel`/`ChannelMapModel`/`UserPreferences` instances — they're cheap to construct; if not, hand-fake the minimum.) Verify it does **not** call `start()` (no `Source` yet).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement: build a throwaway `Channel` with the chosen `DecoderType`'s default `DecodeConfiguration` and a null/`SourceConfigTuner` source config, `new ProcessingChain(channel, aliasModel)`, get modules via `DecoderFactory.getModules(channelMapModel, channel, aliasModel, userPreferences, null /*no traffic mgr*/, null /*no descriptor*/)`, filter out audio-output / logger / recorder module types (keep decoder + state), `addModule` each, register the `LockWatcher`. Return chain + watcher (a small `ProbeChain` record holding both).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit. `git commit -am "discovery: add ProbeChainFactory (decoder-only transient ProcessingChain + LockWatcher)"`

### Task 1.6: `ComplexSampleFanout`

**Files:** Create `module/discovery/ComplexSampleFanout.java`. Test: `module/discovery/ComplexSampleFanoutTest.java`.

- [ ] **Step 1: Failing test** — with a fake `Source` you can push `ComplexSamples` into: subscribe two fake `ComplexSource`-adapter consumers; push 3 buffers → both receive all 3; forward a sample-rate `SourceEvent` → both see it; `removeSubscriber(a)` → only `b` gets subsequent buffers; `stop()` → underlying source `stop()` called, no further delivery.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement: registers as the real source's listener; keeps a copy-on-write subscriber list; on each `ComplexSamples` (or whatever buffer type — confirm in 1.1) forwards to all subscribers; forwards relevant `SourceEvent`s; `start()`/`stop()` proxy to the real source. Provide a `newSubscriberSource()` returning a thin `ComplexSource` adapter suitable for `ProcessingChain.setSource(...)`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit. `git commit -am "discovery: add ComplexSampleFanout (one tuner channel → many probe chains)"`

### Task 1.7: `ClassificationRequest` / `ClassificationResult` / `ClassificationSession`

**Files:** Create `module/discovery/ClassificationRequest.java`, `ClassificationResult.java`, `ClassificationSession.java`. Test: `module/discovery/ClassificationResultTest.java`, `ClassificationSessionTest.java`.

- [ ] **Step 1: Failing tests** — `ClassificationResult` static factories produce the right `outcome` and null/non-null fields (e.g. `identified(...)` has non-null `bestDecoder`/`bestDecodeConfig`; `error("x")` has `outcome==ERROR`, `summary` mentions "x"). `ClassificationRequest` defaults: `candidateDecoders` defaults to all primaries, `overallDeadline` ~12 s, `keepWinningChainRunning` false. `ClassificationSession`: register a couple of fake closeables (chains/source) → `close()` closes all, idempotent, swallows-and-logs individual close failures.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the records (compact-constructor validation + builder/factory defaults) and `ClassificationSession` (`AutoCloseable`; holds `Source` + `ComplexSampleFanout` + `List<ProcessingChain>`; `close()` stops+disposes each in turn, catching).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit. `git commit -am "discovery: add ClassificationRequest/Result + ClassificationSession (resource scope)"`

### Task 1.8: `DiscoveryPreference` + register in `UserPreferences`

**Files:** Create `preference/discovery/DiscoveryPreference.java`. Modify `preference/UserPreferences.java`. Test: `preference/discovery/DiscoveryPreferenceTest.java`.

- [ ] **Step 1: Failing test** — defaults are as in spec §9 (dwell 3 s, threshold 6 dB, max probes 2, max classifications 1, headroom 0, no excluded decoders, all primaries in default scan set, click bw 12500, keep-listening 30 s, overlay = IDENTIFIED_ONLY, empty ignore list); setters persist (write, re-read via a fresh `DiscoveryPreference` over the same store, values survive); ignore-list add/remove round-trips. (Mirror the existing `*PreferenceTest` style; use the same backing-store mechanism the other preferences use.)
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement `DiscoveryPreference extends Preference` following the existing pattern (Java `Preferences` node or whatever the codebase uses); serialise the ignore list (JSON string via the existing Jackson mapper, or individual keys). Register it in `UserPreferences` with a getter, mirroring siblings.
- [ ] **Step 4:** Run → PASS; `./gradlew compileJava` → PASS.
- [ ] **Step 5:** Commit. `git commit -am "discovery: add DiscoveryPreference (+register in UserPreferences)"`

### Task 1.9: `SignalClassifier` — happy path against fakes

**Files:** Create `module/discovery/SignalClassifier.java`. Introduce narrow seams so it's testable: a `SourceProvider` functional interface (`Source acquire(SourceConfiguration, ChannelSpecification, String)`) defaulting to `tunerManager::getSource`, and a `ProbeChainFactory` injected. Test: `module/discovery/SignalClassifierTest.java`.

- [ ] **Step 1: Failing test** — inject a fake `SourceProvider` returning a fake `Source` that, when started, emits a canned buffer stream; inject a fake `ProbeChainFactory` whose chains' `LockWatcher`s are scripted: e.g. the "P25_PHASE1" probe reports `LOCKED`/CONTROL after 200 ms, others report `NONE`. Assert: `classify(request).get()` → `outcome==IDENTIFIED`, `bestDecoder==P25_PHASE1`, `bestDecodeConfig` non-null, `kind==CONTROL`, `candidates` lists all tried with their states; the fake source + all chains were closed (the session closed). A second scenario: all probes `NONE`, but the energy gate saw power → `UNIDENTIFIED`. Third: energy gate saw nothing → `NO_SIGNAL` and no probe chains were ever built. Fourth: `SourceProvider` returns `null` → `outcome==ERROR`, reason mentions capacity.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the probe loop per spec §5.3: acquire source (→ ERROR on null); energy gate via `PowerMonitor` for ~300 ms (→ NO_SIGNAL if never above floor); order candidates via `CandidateOrdering`; iterate with a `Semaphore`-bounded pool (`discovery.max.concurrent.probes`); per candidate build a `ProbeChain`, subscribe it to the `ComplexSampleFanout`, start it, await its `LockWatcher` up to the per-protocol window (`DiscoveryPreference.probeWindow(decoder)`), record a `Candidate`, tear it down; short-circuit on a fast clean `LOCKED`; pick winner (priority then quality); build the `ClassificationResult` (`bestDecodeConfig = DecoderFactory.getDecodeConfiguration(best)`); always close the `ClassificationSession` in `finally`. Honour `overallDeadline` and cancellation (interrupt → CANCELLED). `keepWinningChainRunning` is accepted but v1 always returns `liveChain == null`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit. `git commit -am "discovery: add SignalClassifier (transient-probe protocol detection engine)"`

### Task 1.10: `SignalClassifier` — cancellation, timeout, cleanup edge cases

**Files:** extend `SignalClassifierTest.java`.

- [ ] **Step 1: Failing tests** — (a) cancel the returned future mid-probe → result `CANCELLED`, every chain + source closed, no leaked threads (use a small fixed executor and assert it's quiescent). (b) a probe chain whose `LockWatcher` never settles → that candidate times out at its window, loop continues, overall completes. (c) `overallDeadline` shorter than the sum of windows → completes at the deadline with whatever was found, everything closed. (d) a `ProbeChainFactory` that throws for one decoder → that `Candidate` is `LockState.ERROR`, others still run. (e) two decoders both `LOCKED` → the higher-priority-by-bandwidth one wins.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Harden the loop: try/finally per candidate; catch per-candidate exceptions → `LockState.ERROR`; cooperative cancellation checks between candidates and inside the await; ensure `finally` close runs on every path.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit. `git commit -am "discovery: harden SignalClassifier cancellation/timeout/cleanup"`

### Task 1.11: Wire engine objects into app bootstrap (no UI yet)

**Files:** Modify the top-level assembly class (identified in 1.1 — where `ChannelProcessingManager`/`TunerManager`/`PlaylistManager` are constructed). Add: a shared `ExecutorService` for discovery, a `SignalClassifier`, a `SpectralSurvey` *placeholder* (skeleton class compiling but `survey()` throwing `UnsupportedOperationException("Phase 3")` — or just defer creating it until Phase 3; prefer deferring), a `DiscoveryModel` (Phase 3 — defer), a `BandScanController` (Phase 3 — defer). For Phase 1, only `SignalClassifier` + the executor are wired, and nothing consumes them yet — so this task may be a no-op beyond constructing `SignalClassifier` and storing it. Add a shutdown hook to stop the executor on exit.

- [ ] **Step 1:** Construct `SignalClassifier` in the assembly class; store it; shut down its executor on app exit. No test (pure wiring) — verify `./gradlew build` PASS and the app still launches (`./gradlew run` smoke, or just compile if `run` is impractical here).
- [ ] **Step 2:** Commit. `git commit -am "discovery: construct SignalClassifier in app bootstrap"`

### Task 1.12: Phase 1 review

- [ ] Run `./gradlew build` → PASS; `./gradlew test --tests 'io.github.dsheirer.module.discovery.*' --tests '*DiscoveryPreferenceTest'` → all PASS.
- [ ] Use `superpowers:requesting-code-review` (or dispatch a review subagent) on the Phase 1 diff: correctness of the probe loop, resource-cleanup completeness, thread-safety, adherence to existing patterns, header/style. Address findings.
- [ ] Commit any review fixes. `git commit -am "discovery: address Phase 1 review feedback"`

---

## Phase 2 — Click-to-tune (Swing)

> Expand to bite-sized steps after reading `spectrum/SpectralDisplayPanel.java`, `spectrum/OverlayPanel.java`, `spectrum/SpectrumPanel.java`, `spectrum/channel/...` overlays, how `SpectralDisplayPanel` is constructed and what it already has refs to (`ChannelModel`, `ChannelProcessingManager`, `PlaylistManager`, `MyEventBus`), and `controller/channel/ChannelProcessingManager.startChannelRequest` + `ChannelStartProcessingRequest`.

- [ ] **Task 2.1** — `ClassifyAndTuneRequest`, `ScanSpanRequest`, `ShowDiscoveryRequest` event classes. *(Trivial; test = none or a tiny construction test.)*
- [ ] **Task 2.2** — `ClickToTuneController`: given a `ClassificationRequest`, call `SignalClassifier.classify`, and on `IDENTIFIED` build a `Channel` (name `"Discovered <MHz>[ (control)]"`, `SourceConfigTuner(freq)`, `result.bestDecodeConfig`, default alias list), `channelModel.addChannel`, then `channelProcessingManager.startChannelRequest(new ChannelStartProcessingRequest(channel))`; on `ChannelException` remove the channel and report; on miss expose a small result object the UI turns into the popup. *Test:* fake `SignalClassifier` + a recording-stub `ChannelModel`/`ChannelProcessingManager` (or thin interfaces) → assert the channel built/added/started has the right config; miss path returns the right "no signal"/"keep listening"/"error" outcome.
- [ ] **Task 2.3** — `PendingClassificationOverlay`: a `JComponent`/layer drawing the selection box + animated "identifying…" label; `setPending(freqHz, widthHz)`, `clear()`, cancellable via click/Esc (fires a callback). *Test:* light/none (pure painting) — at most a logic test for the cancel callback.
- [ ] **Task 2.4** — `SpectralDisplayPanel`/`MouseEventProcessor`: add Shift+left-drag → draw selection (using `getFrequencyFromAxis` at both edges, live label) → on release fire a `ClassificationRequest` through `ClickToTuneController`; double-click → classify at `DiscoveryPreference.clickDefaultBandwidthHz`; add context-menu items "Decode here (auto-detect)", "Decode here as ▸ <each primary decoder>" (the latter skips the classifier — straight to channel creation), "Scan this view…" (posts `ScanSpanRequest`), "Show discoveries" (posts `ShowDiscoveryRequest`). **Do not change** plain-drag pan, wheel zoom, or the existing right-click items. *Test:* extract the hit-test / freq-from-pixels / request-construction into testable methods; UI plumbing verified manually.
- [ ] **Task 2.5** — `DecoderOverrideChip`: small Swing control anchored to a click-to-tune channel's overlay; shows the decoder short name + a popup menu of the other primaries + "Re-detect…"; on pick → `channelProcessingManager.stop(channel)` (or the existing disable path), swap `channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(picked))`, restart; "Re-detect…" re-runs the classifier on the channel's frequency. Track "is click-to-tune channel" via a side-`Set<Channel>` in `ClickToTuneController` (no `Channel` change). *Test:* the swap-and-restart logic against stubs.
- [ ] **Task 2.6** — Wire `ClickToTuneController` (and pass `SignalClassifier`) into `SpectralDisplayPanel` construction at the bootstrap site. Smoke: app launches, Shift+drag a box on a live/recorded tuner → channel appears & decodes (manual).
- [ ] **Task 2.7** — Phase 2 review (code-review subagent) + fixes. `./gradlew build` PASS.

---

## Phase 3 — Spectral survey + band-scan orchestrator

> Expand after reading `spectrum/ComplexDftProcessor.java`, `spectrum/DFTResultsConverter.java`, `dsp/window/WindowType.java`, how the tuner's full-rate sample stream is obtained (the `SpectralDisplayPanel` already taps it — find that path), and `controller/channel/ChannelModel.getChannels()` for the "already-configured channel overlaps this peak" check.

- [ ] **Task 3.1** — `SpectralSurvey.findPeaks(float[] magsDb, long binWidthHz, long baseFreqHz, double thresholdDb)` pure static peak finder + noise-floor estimate (median/low-percentile) + contiguous-run detection + close-peak merge + guard widening → `List<EnergyPeak>`. *Test (TDD):* synthetic arrays — flat noise → no peaks; one fat bump → one peak with the right center/width/snr; two close bumps → merged or separate per min-separation; edge bumps handled.
- [ ] **Task 3.2** — `SpectralSurvey` in-band mode: spin up a dedicated short-lived `ComplexDftProcessor` on a tap of the tuner's wideband output (or attach a `DFTResultsConverter` listener), average magnitude bins over `dwell`, call `findPeaks`, map bin indices → absolute Hz using the tuner's center freq + sample rate, report progress, cancellable. *Test:* feed it a fake DFT source emitting canned magnitude frames → expect the peaks; cancellation mid-dwell stops cleanly.
- [ ] **Task 3.3** — `Discovery` (observable) + `DiscoveryEvent` + `DiscoveryModel` (ObservableList + `Listener<DiscoveryEvent>` broadcaster; add/update/remove/clear; `findOverlapping`). *Test:* model mutations fire the right events; `findOverlapping` math.
- [ ] **Task 3.4** — `ScanRequest`/`ScanState` + `BandScanController`: resolve span → in-band vs stepped decision (stepped just flagged for now; Phase 5 implements the sweep — if stepped, return an error "wide-range sweep not yet available" until Phase 5, OR implement a degenerate single-step) → `SpectralSurvey` → seed `Discovery` rows (`ENERGY_DETECTED`; drop ignore-list matches; mark playlist-overlapping ones `KNOWN`) → sequentially `SignalClassifier.classify` each remaining peak (`keepWinningChainRunning=false`) → update rows (`PROBING`→`IDENTIFIED`/`UNIDENTIFIED`/`ERROR`, confidence pips from lock state/quality) → `DONE` (or continuous loop). `stop()`, `shutdown()`. Operator actions: `addAsChannel(Discovery)` (build `Channel` like 2.2, add+start, keep `createdChannel`), `addAll(minPips)`, `ignore(Discovery)` (→ `DiscoveryPreference` ignore list + remove row), `setWatched`, `reprobe`. *Test (TDD, the meat of Phase 3):* inject fake `SpectralSurvey` + fake `SignalClassifier` → assert the full state machine, ignore/known filtering, sequential probing, every operator action, continuous re-survey, cancellation/teardown.
- [ ] **Task 3.5** — Construct `SpectralSurvey`, `DiscoveryModel`, `BandScanController` in the bootstrap site; wire to nothing-visible yet (or a temporary debug log). `./gradlew build` PASS.
- [ ] **Task 3.6** — Phase 3 review + fixes.

---

## Phase 4 — Discovery UI (JavaFX) + spectral overlay

> Expand after reading `gui/playlist/PlaylistEditor.java` (how tabs are added/wired), an existing tab editor (e.g. `gui/playlist/channel/ChannelEditor.java`) for table/cell-factory and dialog patterns, and how the spectral display gets its overlays painted.

- [ ] **Task 4.1** — `DiscoveryEditor` (`BorderPane`): toolbar + `TableView<Discovery>` bound to `DiscoveryModel.getDiscoveries()`; columns per spec §9 with cell factories (live-state icon, confidence pips, MHz formatting, action-button cell with ＋/👁/✕/↻ calling `BandScanController`). Selection ↔ spectral overlay highlight (post an event). *Test:* light.
- [ ] **Task 4.2** — `ScanDialog`: range fields + presets ("current view"/"current tuner span"/manual), decoder checkbox list (defaults from `DiscoveryPreference`), advanced collapsible (dwell/threshold/maxSignals), continuous toggle+interval, the **red disruption banner** shown only when the chosen span exceeds the tuner's instantaneous bandwidth — with an ETA — `[Scan]`/`[Cancel]` → builds a `ScanRequest` and calls `BandScanController.startScan`. *Test:* the "is this stepped?" / ETA logic; banner visibility.
- [ ] **Task 4.3** — `ManageIgnoreListDialog`: table of `IgnoreRange` from `DiscoveryPreference`, remove button. *Test:* light.
- [ ] **Task 4.4** — Add the `DiscoveryEditor` as a "Discovery" tab in `PlaylistEditor`; handle `ShowDiscoveryRequest` (select the tab, optionally select a row) and `ScanSpanRequest` (open `ScanDialog` pre-filled). Pass `BandScanController`/`DiscoveryModel`/`DiscoveryPreference` through.
- [ ] **Task 4.5** — `DiscoveryOverlay` on the spectral display: subscribe to `DiscoveryModel`'s `DiscoveryEvent` listener; paint faint dashed rects for non-added discoveries colored by state; tooltip with metadata; click → `ShowDiscoveryRequest`; respect `DiscoveryPreference.overlayDisplay`; add the `DiscoveryDisplay` {ALL, IDENTIFIED_ONLY, NONE} toggle to the existing "Display ▸ Channel" submenu. *Test:* light.
- [ ] **Task 4.6** — `DiscoveryPreferenceEditor` pane in `gui/preference/` mirroring an existing one; register it. *Test:* light.
- [ ] **Task 4.7** — Phase 4 review + fixes. Manual smoke: open the tab, run a scan against a recorded tuner, see rows fill, add one, see it become a channel + decode, see overlays.

---

## Phase 5 — Stepped sweep + polish

> Expand after Phase 3/4 are in.

- [ ] **Task 5.1** — `SpectralSurvey` stepped-sweep mode: commandeer the tuner (or a designated discovery tuner if >1 — `DiscoveryPreference`), step across the span at ~80% sample-rate strides, in-band-survey each step, accumulate+merge peaks, **restore the tuner's prior center frequency** at the end (and on cancel/error); report progress + ETA. *Test (TDD):* fake tuner controller → assert stepping, accumulation, restore-on-finish, restore-on-cancel, restore-on-error.
- [ ] **Task 5.2** — `BandScanController`: enable the stepped path (it was stubbed in 3.4); ensure the `ScanDialog` warning is honoured (the dialog already computes "stepped?"). *Test:* orchestration with the fake tuner.
- [ ] **Task 5.3** — Click-to-tune "keep listening 30 s" + `PARTIAL` "start it anyway as X?" offers in the miss popup (`ClickToTuneController` + the popup component). *Test:* the re-run-with-longer-deadline + partial-offer logic.
- [ ] **Task 5.4** — Docs: a short section in the spec/PR description with the manual test plan; update any in-repo user docs if the project keeps them in-tree (it mostly uses a wiki — likely just the PR description).
- [ ] **Task 5.5** — Final full review (`superpowers:requesting-code-review` over the whole branch diff) + `superpowers:verification-before-completion` (run `./gradlew build`, `./gradlew test`, app launch smoke; record outputs) + fixes.
- [ ] **Task 5.6** — `superpowers:finishing-a-development-branch`: decide merge/PR/cleanup with the user.

---

## Self-review notes

- **Spec coverage:** Engine §5 → Phase 1; click-to-tune §6 → Phase 2; survey §7 → Phase 3 (in-band) + Phase 5 (stepped); scan/orchestration §8 → Phase 3; Discovery UI §9 → Phase 4; preferences §9 → Tasks 1.8 + 4.6; cross-cutting §10 → woven through (resource cleanup = `ClassificationSession`/`finally` in 1.7/1.9/1.10; disruption consent = `ScanDialog` 4.2 + sweep 5.1; tuner contention = 1.9 ERROR path + 3.4 backoff; playlist hygiene = discoveries-are-candidates in 3.4 + click-adds-immediately in 2.2); types §11 → file structure above; testing §12 → TDD on `CandidateOrdering`/`LockWatcher`/`findPeaks`/`SignalClassifier`-with-fakes/`BandScanController`-with-fakes/`DiscoveryPreference`; phases §13 → these 5 phases.
- **Placeholders:** Phases 2–5 are intentionally task-level (not step-level) with an explicit "expand against the code" gate per phase — this is a deliberate decision (downstream tasks depend on existing-code APIs not yet read), not an accidental TODO. Phase 1 is fully step-level.
- **Type consistency:** `ProbeChain` (record of `ProcessingChain` + `LockWatcher`) introduced in 1.5, used in 1.9/1.10. `ClassificationSession` in 1.7, used in 1.9/1.10. `DiscoveryModel.findOverlapping` in 3.3, used in 3.4. `result.bestDecodeConfig` produced in 1.9, consumed in 2.2 and 3.4. `DiscoveryPreference.probeWindow(decoder)` in 1.8, used in 1.9.
