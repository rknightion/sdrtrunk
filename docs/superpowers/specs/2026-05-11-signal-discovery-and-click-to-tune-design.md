# Signal Discovery & Click‑to‑Tune — Design

**Date:** 2026‑05‑11
**Status:** Approved (Approach ①, "spectrum‑native, reuse the decoders")
**Repo:** `dsheirer/sdrtrunk` — built to upstream‑contribution quality (Java 25, JUnit 5, copyright headers, existing patterns).

---

## 1. Summary

Today the operator must hand‑add control and voice channels to the playlist before anything decodes. This feature adds two capabilities built on a single shared engine:

1. **Click‑to‑tune** — on the spectral display, drag a box around a signal (or right‑click it) and the app captures samples there, figures out which protocol it is by actually decoding, and immediately starts a real channel — with a one‑click control to override the auto‑detected decoder.
2. **Auto‑discovery** — point the app at a frequency range; it surveys for energy, probes each detected signal with the decoders, and streams the results into a Discovery panel where the operator turns whichever ones they want into channels (or "add all above confidence X").

Both run on the **`SignalClassifier`**: *given a frequency and rough bandwidth → capture samples → run candidate decoders → report which lock(s) and with what confidence.* No new DSP/demod code — every existing sdrtrunk decoder is reused verbatim; "confidence" comes from real decode lock, since sdrtrunk has no generic modulation classifier.

## 2. Goals / Non‑Goals

**Goals**
- Click a visible signal on the spectrum → it tunes there and starts decoding with the right protocol, automatically.
- One‑click override of the auto‑detected decoder on the resulting channel.
- Scan an operator‑chosen frequency range → produce a list of `(frequency, bandwidth, detected protocol, confidence, brief metadata)` for active signals.
- Turn discoveries into real playlist channels individually or in bulk.
- Locking a P25/DMR control channel "just works" — existing traffic‑channel following takes over once the control channel is added.
- Non‑disruptive by default: discovery within the current tuner's instantaneous bandwidth never retunes the SDR and never interrupts existing decoding. Wider scans that *do* retune are explicit and confirmed.
- Reliable resource cleanup; never starve the operator's manual decoding of tuner capacity.

**Non‑Goals (this design)**
- Protocols sdrtrunk doesn't already decode (NXDN, dPMR, TETRA, …).
- ML / statistical modulation classification (we decode to confirm; future work could add a cheap pre‑classifier).
- Multi‑SDR coordination / scanning across multiple tuners simultaneously.
- Auto‑naming discovered systems from RadioReference (a natural follow‑on, but out of scope here).
- Decoding audio *during* probing for the operator to listen to — probing is short and headless.

## 3. Relevant existing architecture (orientation)

- **`Channel`** (`controller/channel/Channel.java`) aggregates a `SourceConfiguration` (e.g. `SourceConfigTuner` with a frequency), a `DecodeConfiguration` (protocol + settings; subclasses per `DecoderType`), alias list, event‑log, record config. `ChannelType` ∈ {`STANDARD` (persisted), `TRAFFIC` (transient, decoder‑spawned)}.
- **`ChannelModel`** holds the observable channel list; `addChannel()` broadcasts a `ChannelEvent` and triggers playlist save. **`ChannelProcessingManager`** turns a `ChannelStartProcessingRequest` into a running `ProcessingChain`: acquires a `Source` from `TunerManager.getSource(sourceConfig, channelSpecification, threadName)`, builds modules via `DecoderFactory.getModules(...)`, wires metadata/audio, `setSource()`, `start()`.
- **`ProcessingChain`** (`module/ProcessingChain.java`): `new ProcessingChain(channel, aliasModel)` → `addModule(...)` decoder/aux modules → `setSource(source)` → `start()` → `stop()` → `dispose()`. Broadcasts `DecoderStateEvent`s.
- **Lock signal**: `channel/state/State.java` — a decoder seeing valid sync/messages transitions the channel state machine out of `IDLE` → `ACTIVE`/`CALL`/`CONTROL`/`DATA`. Observable via `DecoderStateEvent` on the chain's decoder‑state broadcaster (register an `IDecoderStateEventListener`). Sync detectors (`P25P1SyncDetector`, `DMRSoftSyncDetector`, …) optionally expose sync‑correlation quality via `ISyncDetectListener`.
- **`DecoderType`** (`module/decode/DecoderType.java`): `PRIMARY_DECODERS` = {`AM, DMR, LTR, LTR_NET, MPT1327, NBFM, P25_PHASE1, P25_PHASE2, PASSPORT`}. `DecoderFactory.getDecodeConfiguration(type)` builds a default `DecodeConfiguration`; each carries a `ChannelSpecification(minSampleRate, bandwidth, passFreq, stopFreq)`.
- **`TunerManager.getSource(...)`** allocates a narrow `TunerChannelSource` from a wideband tuner (polyphase channelizer / heterodyne). Returns `null` if no tuner can source it (busy or out of range). Concurrent channel count is hardware‑limited; no explicit cap.
- **Spectral display** (`spectrum/`): `SpectralDisplayPanel` (a `JLayeredPane`) hosts `SpectrumPanel`/`WaterfallPanel` + `OverlayPanel` (renders configured channels as overlays; has `getFrequencyFromAxis(xPixel)↔getAxisFromFrequency(hz)`, `getChannelsAtFrequency(hz)`) + `FrequencyOverlayPanel`. `MouseEventProcessor` already does zoom (wheel) / pan (drag) / right‑click context menu (currently: view‑existing‑channel, colors, FFT width, frame rate, window type, smoothing, zoom, show‑tuner). `ComplexDftProcessor` computes the FFT and emits magnitude bins to `DFTResultsConverter` listeners (currently only the renderer listens).
- **Playlist editor** (`gui/playlist/`): JavaFX. `PlaylistEditor` contains tabbed editors (Channels, Aliases, …). `ChannelEditor` is a `SplitPane` (channel `TableView` + protocol‑specific `ChannelConfigurationEditor`).
- **Persistence**: `PlaylistManager` / `PlaylistV2` — Jackson XML; channels persisted in playlist. Models can be mutated programmatically; changes auto‑save (debounced).

## 4. Architecture overview

New package `io.github.dsheirer.module.discovery` (engine + orchestration, toolkit‑agnostic) plus UI in `io.github.dsheirer.gui.discovery` (JavaFX panel) and a small interaction controller in `io.github.dsheirer.spectrum` (Swing, click‑to‑tune).

```
spectrum window (Swing)                              playlist window (JavaFX)
 ├─ SpectralDisplayPanel.MouseEventProcessor          └─ PlaylistEditor
 │    ├─ left‑drag selection box ─┐                        └─ DiscoveryEditor  ◀── DiscoveryModel
 │    └─ right‑click "Decode here / Scan view…" ─┤              (new "Discovery" tab)
 │                                               ▼
 │                                   ClickToTuneController ─────────┐
 │   discovery overlays (faint, dashed) ◀── DiscoveryOverlay         │
 └─────────────────────────────────────────────────────────────────│──────────────┐
                                                                    ▼              ▼
  module.discovery:                                          ┌── BandScanController ──┐
   ┌─────────────────────────────────────────┐  uses  ──────▶│  drives SpectralSurvey │ (FFT tap → EnergyPeak[])
   │              SignalClassifier             │◀─────────────│  then per peak →       │
   │  in: ClassificationRequest(freq, bw, …)   │              │  SignalClassifier      │──▶ DiscoveryModel rows
   │  ─ allocate 1 TunerChannelSource @ freq   │              └────────────────────────┘
   │  ─ candidates ordered by bandwidth/hints  │
   │  ─ per candidate (small pool):            │   ClassificationResult ─▶ ClickToTuneController:
   │      transient ProcessingChain(decoder)   │       new Channel(SourceConfigTuner+decodeConfig)
   │      + LockWatcher on DecoderStateEvent    │       ─▶ ChannelModel.addChannel ─▶ ChannelProcessingManager.start
   │      run ≤ per‑protocol window → locked?  │       (P25/DMR control channel ⇒ existing traffic following)
   │  out: ClassificationResult                │
   └─────────────────────────────────────────┘   ProbeChainFactory builds the transient chains
                                                  (decoder modules only — no audio/log/record)
```

Key principle: the **engine knows nothing about Swing or JavaFX**; it exposes plain Java APIs and `Listener<…>`/event callbacks. Both UIs are thin adapters over it.

## 5. Component: `SignalClassifier` (the engine)

### 5.1 Inputs / outputs

```java
record ClassificationRequest(
    long centerFrequencyHz,
    int approximateBandwidthHz,          // operator's box width, or a survey peak width; may be 0/unknown
    EnumSet<DecoderType> candidateDecoders,  // default = DecoderType.PRIMARY_DECODERS, less excluded ones
    Duration overallDeadline,            // hard cap for the whole request (default ~12 s)
    boolean keepWinningChainRunning,     // true for click‑to‑tune (hand the live chain to the caller), false for scans
    String label)                        // for thread names / logging

record ClassificationResult(
    long centerFrequencyHz,
    Outcome outcome,                     // IDENTIFIED | UNIDENTIFIED | NO_SIGNAL | ERROR | CANCELLED
    List<Candidate> candidates,          // every decoder tried, with its lock metric — best first
    DecoderType bestDecoder,             // null unless IDENTIFIED
    DecodeConfiguration bestDecodeConfig,// a ready‑to‑use config (default for the type), null unless IDENTIFIED
    SignalKind kind,                     // CONTROL | CONVENTIONAL | TRAFFIC | UNKNOWN — from decoded messages
    String summary,                      // e.g. "P25 Phase 1 · control · NAC 0x293"
    Map<String,String> metadata,         // NAC, color code, system, site, talkgroup glimpse, etc.
    double signalPowerDb,                // measured by PowerMonitor during probing
    ProcessingChain liveChain)           // non‑null only when keepWinningChainRunning && IDENTIFIED

record Candidate(DecoderType decoderType, LockState lockState, double lockQuality /*0..1*/, String note)
enum LockState { LOCKED, PARTIAL /* sync seen, no sustained state */, NONE, ERROR }
```

### 5.2 Candidate ordering (no decode required)

`approximateBandwidthHz` (when known) is mapped to a *priority order* over `candidateDecoders`, so the likely decoder is tried first and a confident lock can short‑circuit the rest:

- ≤ ~8 kHz → P25_PHASE2, then P25_PHASE1, DMR, NBFM, … (P25 P2 is ~6.25 kHz; P25 P1/DMR ~12.5 kHz still tried).
- ~8–16 kHz → P25_PHASE1, DMR, NBFM, then P25_PHASE2, LTR, LTR_NET, MPT1327, PASSPORT, then AM.
- ~16–30 kHz → NBFM (wide), AM, then the 12.5 kHz set.
- ≥ ~30 kHz / unknown → NBFM, AM, then everything else.

This is *ordering only* — every decoder in `candidateDecoders` is still eligible; ordering just front‑loads the probable ones. (A future cheap FM‑vs‑PSK‑vs‑FSK pre‑filter could prune the list; not built now.)

`AM` and the niche LTR/MPT1327/Passport decoders are included by default but ordered last; an exclusion preference (§10) lets operators drop chronic false‑positives.

### 5.3 Probe procedure

Per `ClassificationRequest`:

1. **Acquire source.** Build a `SourceConfigTuner(centerFrequencyHz)`; pick a `ChannelSpecification` wide enough for the widest candidate (so one source serves all candidates) — use the max of the candidates' specs, clamped to a sane ceiling. `source = tunerManager.getSource(cfg, spec, "discovery:"+label)`. If it returns `null`, that means *no tuner capacity or the frequency is out of range* — return `outcome = ERROR` with that reason (distinct from `NO_SIGNAL`, which means "tuned fine, but dead air"), and let callers/scan back off and retry.
2. **Quick energy gate.** Attach a `PowerMonitor` to the source for ~200–400 ms. If power never exceeds an adaptive floor, return `NO_SIGNAL` immediately (don't waste decoder time on dead air). The measured power goes into the result regardless.
3. **Probe candidates** in priority order, with a small concurrency pool (default 2 — `discovery.max.concurrent.probes` preference, see §9). For each candidate:
   - Build a **probe chain** via `ProbeChainFactory.build(decoderType, source, sharedContext)` — a `ProcessingChain` with **only** the decoder/state modules from `DecoderFactory.getModules(...)` (no audio output, no event logger, no recorder; aux decoders off). All probe chains share the *same* source via a fan‑out tap (sdrtrunk sources are single‑consumer, so the classifier owns a `Broadcaster<ComplexSamples>` reading the real source once and feeding each running probe chain — see §5.4).
   - Register a `LockWatcher` (an `IDecoderStateEventListener`) on the chain's decoder‑state broadcaster. It records: first transition out of `IDLE` and to what state; whether the state is *sustained* past a debounce (e.g. ≥ 2 consecutive state events / ≥ 250 ms in a non‑IDLE state) ⇒ `LOCKED`; a one‑shot sync‑detect without sustained state ⇒ `PARTIAL`. It also captures the first useful identifiers it sees (NAC, color code, system/site, talkgroup) and whether the messages indicate a control channel (`CONTROL` state, or trunking control opcodes) ⇒ `SignalKind.CONTROL`.
   - Run until: `LOCKED` (record, and if `LOCKED` with a clean fast lock, you may cancel remaining lower‑priority candidates), or the per‑protocol window elapses (`decoder.getProbeWindow()` — e.g. ~1.5 s for FM, ~3 s for P25/DMR control‑rate, longer for slow trunking like LTR/MPT1327 since their signalling is sparse), or the request's `overallDeadline` hits, or cancelled.
   - Tear down the probe chain (`stop()` + `dispose()`) before/while moving on.
4. **Pick winner.** Among `LOCKED` candidates, prefer: (a) the highest‑priority‑by‑bandwidth that locked, then (b) highest `lockQuality`. `IDENTIFIED` with `bestDecoder`, `bestDecodeConfig = DecoderFactory.getDecodeConfiguration(bestDecoder)`, `kind`, `summary`, `metadata`. If only `PARTIAL`s → still `UNIDENTIFIED` but report the partials (the UI can offer "try anyway as X"). If energy was present but nothing locked/partialed → `UNIDENTIFIED`. If no energy → `NO_SIGNAL`.
5. **Keep or release.** If `keepWinningChainRunning && IDENTIFIED`: *don't* tear down the winner — but a probe chain has no audio/metadata wiring, so the cleanest path is: tear the probe chain down too, return the result with `liveChain == null`, and let the **caller** (`ClickToTuneController`) create a proper `Channel` + start it through `ChannelProcessingManager` (which wires audio/metadata/recording correctly). Rationale: a half‑second of "decoder restarting" is invisible and avoids a fragile "promote a bare chain" path. (We keep the `keepWinningChainRunning`/`liveChain` shape in the API in case a later optimisation wants it, but v1 always returns `null` and re‑creates via the manager.)
6. **Always** release the source and dispose every chain in a `finally`.

### 5.4 Source fan‑out

sdrtrunk `Source`s are single‑consumer. The classifier owns the one real `TunerChannelSource`, registers itself as its listener, and re‑broadcasts buffers to a small `Broadcaster<ComplexSamples>` that each *running* probe chain subscribes to (probe chains' `setSource()` gets a thin `ComplexSource` adapter over that broadcaster). Sample‑rate/frequency `SourceEvent`s are forwarded too. This keeps tuner allocation to **one channel per classification request** regardless of how many candidates run.

### 5.5 Concurrency / cancellation

- Each `SignalClassifier.classify(request)` returns a `CompletableFuture<ClassificationResult>` and runs on a dedicated single‑thread‑per‑request executor drawn from a bounded pool (`discovery.max.concurrent.classifications`, default 1 for click‑to‑tune contexts, configurable up for scans only if tuner capacity allows — but realistically scans run classifications **sequentially**, see §8).
- A `Cancellable` handle on the returned future tears down cleanly at any point.
- Hard global rule: the classifier never holds more than `discovery.max.concurrent.probes` probe chains, and a classification will not start (or will queue) if acquiring its source would leave fewer than `discovery.tuner.headroom.channels` (default 0, configurable) free for the operator — but since `getSource` returning `null` already means "no capacity", this is really just graceful handling of that null + a small optional reserve.

## 6. Component: Click‑to‑tune (Swing, in `spectrum/`)

### 6.1 Interaction

Add to `SpectralDisplayPanel.MouseEventProcessor` (and a small new `ClickToTuneController`):

- **Left‑button drag** on the `OverlayPanel`/`SpectrumPanel`: draws a translucent selection rectangle; a floating label shows the implied center frequency and width (`overlayPanel.getFrequencyFromAxis(x)` at both edges). On release with a non‑trivial width → start a classification with `centerFrequencyHz = midpoint`, `approximateBandwidthHz = |right−left|`.
  - Existing left‑drag = pan. Resolve the conflict: pan stays the default *empty‑space* drag; the selection box is engaged with a modifier (e.g. **Shift+drag**) — OR — switch the default behaviour for drags that *start over a rendered signal peak* to "select". Decision: **Shift+drag = select box**; plain drag = pan (least surprising for existing users). Also: **double‑click** a spot = classify with a default width (the current decode‑config default bandwidth, ~12.5 kHz).
- **Right‑click** menu (additions to the existing context menu): "**Decode here (auto‑detect)**" (uses ~12.5 kHz default width at the cursor) and a "**Decode here as ▸** AM / DMR / LTR / LTR‑Net / MPT1327 / NBFM / P25 P1 / P25 P2 / Passport" submenu (skip the classifier — go straight to channel creation with that decoder). Plus "**Scan this view…**" (opens the scan dialog pre‑filled with the visible span) and "**Show discoveries**" (focuses the Discovery tab).

### 6.2 Pending → result

- While classifying: draw a "pending" overlay box at the target span on a new lightweight layer of the `JLayeredPane` (or reuse `OverlayPanel`) with a small animated label ("identifying… P25‑1? DMR? P25‑2? NBFM?"). It's cancellable (click it / Esc).
- On `IDENTIFIED`: `ClickToTuneController` builds `Channel`:
  - `name` = e.g. `"Discovered " + frequencyMHz + (kind==CONTROL? " (control)":"")` (operator can rename later).
  - `setSourceConfiguration(new SourceConfigTuner(centerFrequencyHz))` — *unless* the result `kind == CONTROL` for a protocol that benefits from multi‑frequency control (P25/DMR can rotate among control channels): v1 keeps it single‑frequency; the operator/trunking layer can extend it.
  - `setDecodeConfiguration(result.bestDecodeConfig)`.
  - `setAliasListName(...)` — the spectral display knows the tuner's current playlist context; default to the playlist's default alias list (or none).
  - `channelModel.addChannel(channel)` (persists), then post a `ChannelStartProcessingRequest(channel)` (or call `channelProcessingManager.startChannelRequest(...)`). On `ChannelException`, surface a small error and remove the channel.
  - The new channel renders as a normal overlay; on it (or in a small popover anchored to it) sits the `● P25‑1 ▾` chip.
- On `UNIDENTIFIED`/`NO_SIGNAL`/`ERROR`: small non‑modal popup near the cursor — "No recognised signal at 154.4450 MHz" / "No tuner capacity" — with actions: **Keep listening 30 s** (re‑run with a longer deadline; useful for intermittent conventional channels), **Pick a decoder…** (the manual submenu), **Cancel**. `PARTIAL`s, if any, are offered: "Looked like NBFM (weak) — start it anyway?".

### 6.3 Decoder override chip

A small Swing control attached to a channel's overlay, shown initially only for channels created via click‑to‑tune (tracked by a transient flag on `Channel` or a side‑set in the controller; *not* persisted — see §14): shows the current `DecoderType` short name + a dropdown of the other primary decoders + "Re‑detect…". Choosing another: stop the channel, replace its `DecodeConfiguration` with the default for the chosen type (preserving frequency/name/alias list), restart it. "Re‑detect…" re‑runs the classifier. This is just a thin wrapper over existing channel start/stop + `ChannelModel` update.

## 7. Component: `SpectralSurvey`

Finds energy peaks within a span, producing `List<EnergyPeak>`:

```java
record EnergyPeak(long centerFrequencyHz, int occupiedBandwidthHz, double powerDb, double snrDb)
```

Two modes, picked by `BandScanController`:

- **In‑band (non‑disruptive)** — the requested span ⊆ the tuner's current instantaneous bandwidth: attach a `DFTResultsConverter` listener to the running `ComplexDftProcessor` of the relevant `SpectralDisplayPanel` (a small new accessor exposes it, or the survey gets a dedicated short‑lived `ComplexDftProcessor` on a tap of the tuner's full output buffer — preferred, so survey FFT params are independent of display settings). Integrate (average) magnitude bins over a dwell (default ~3 s, `discovery.survey.dwell`), estimate the noise floor (e.g. median or a low percentile of bins), then find contiguous runs of bins exceeding `noiseFloor + threshold` (default ~6 dB, `discovery.survey.threshold.db`); each run → an `EnergyPeak` (center = run centroid, width = run span widened by a guard, power = peak bin, snr = peak − floor). Merge peaks closer than a min‑separation.
- **Stepped sweep (disruptive)** — the span exceeds the tuner's instantaneous bandwidth: the operator confirmed a retune. The survey commandeers the tuner (or a dedicated discovery tuner if the operator has more than one — preference): for each step (step = ~80% of the tuner's max sample rate to overlap edges), retune, do a short in‑band survey, accumulate peaks, advance. On finish, restore the tuner to its prior center frequency. This *interrupts decoding on that tuner* for the duration — the dialog says so plainly, with an ETA.

The survey is cancellable and reports progress (fraction of span covered).

## 8. Component: `BandScanController` (auto‑discovery orchestration)

Drives a scan and owns the `DiscoveryModel`:

```java
record ScanRequest(
    long minFrequencyHz, long maxFrequencyHz,   // or a "current view"/"current tuner span" sentinel
    EnumSet<DecoderType> candidateDecoders,
    Duration surveyDwell, double thresholdDb,
    int maxSignalsToProbe,                       // safety cap, default e.g. 200
    boolean continuous, Duration continuousInterval)
```

Flow:

1. Resolve the effective span; decide in‑band vs stepped (and, if stepped, confirm with the operator via the dialog). Mark `ScanState = SURVEYING`.
2. Run `SpectralSurvey` → `EnergyPeak[]`. Each peak immediately becomes a `Discovery` row in state `ENERGY_DETECTED` (so the operator sees the map filling in before any probing). Drop peaks on the persisted **ignore list** (§10), and peaks that overlap an *already‑configured channel* (those become rows in state `KNOWN` — shown greyed, not re‑probed, but visible so the band map is complete).
3. `ScanState = PROBING`. **Sequentially** (one classification at a time — respects tuner capacity and avoids thrashing the channelizer; `discovery.max.concurrent.classifications` can raise this, default 1) feed each non‑ignored, non‑known peak to `SignalClassifier.classify(...)` with `keepWinningChainRunning = false`. As each returns, update its `Discovery`: `PROBING → IDENTIFIED(decoderType, confidence, kind, summary, metadata) | UNIDENTIFIED | ERROR`. Confidence = a small bucketed score derived from `lockState`/`lockQuality`/how fast it locked (e.g. 1–4 "●" pips).
4. `ScanState = DONE` (or `→ IDLE_CONTINUOUS` if `continuous`: after `continuousInterval`, re‑survey; new peaks get appended/refreshed; `WATCH`‑flagged `UNIDENTIFIED` rows get re‑probed even if energy is momentarily absent).
5. Cancellable at any point (`ScanState = CANCELLED`); in‑flight classification is torn down; tuner restored if it was a stepped sweep.

`Discovery` model object:

```java
class Discovery {                       // observable (JavaFX properties for the table)
  long centerFrequencyHz; int bandwidthHz;
  DiscoveryState state;                 // ENERGY_DETECTED | PROBING | IDENTIFIED | UNIDENTIFIED | KNOWN | ERROR
  DecoderType detectedDecoder;          // null unless IDENTIFIED
  SignalKind kind;                      // CONTROL | CONVENTIONAL | TRAFFIC | UNKNOWN
  int confidence;                       // 0..4
  double powerDb, snrDb;
  Map<String,String> metadata;          // NAC, CC, system/site, …
  Instant firstSeen, lastSeen;
  Channel createdChannel;               // set once the operator adds it (null otherwise)
  boolean watched;                      // re‑probe periodically
}
```

Operator actions (from the panel; see §9): **Add as channel** (build a `Channel` like §6.2 from `detectedDecoder`/frequency/bandwidth, `channelModel.addChannel`, start it; row keeps `createdChannel` and shows live state thereafter), **Add all ≥ N pips**, **Ignore** (adds the frequency±width to the persisted ignore list and removes the row; reversible via a "manage ignore list" dialog), **Watch** (toggle re‑probe), **Re‑probe now**, **Edit/View** (open the channel editor if added).

When the operator adds a control‑channel discovery, nothing special is needed — `ChannelProcessingManager` + the protocol's decoder state do traffic‑channel following exactly as for a hand‑added control channel today. The Discovery row's `metadata` already flagged it `kind = CONTROL` so the row label reads "P25 P1 · control".

## 9. Component: Discovery UI

- **New `DiscoveryEditor` tab** in `gui/playlist/PlaylistEditor` (alongside Channels / Aliases / Channel Maps / Streaming): a JavaFX `BorderPane`.
  - **Top toolbar**: `[ Scan… ]` (opens `ScanDialog`), `[ Stop ]`, a `ProgressBar` + state label, `[ Add all ≥ ● pips ▾ ]`, `[ Clear finished ]`, `[ ⚙ Settings ]` (opens scan/engine preferences), `[ Manage ignored… ]`.
  - **Center**: `TableView<Discovery>` — columns: live ● (icon by `createdChannel`'s processing state, or "⚡ energy"/"⏳ probing"/"✓"/"?"/"known"), Frequency (MHz, sortable), Bandwidth, Detected (e.g. "P25 P1 · control"), Confidence (●●●○), Power/SNR, First/Last seen, Notes (compact metadata), and a button cell with `＋`/`👁`/`✕`/`↻`. Right‑click row → the same actions. Selecting a row highlights the corresponding overlay on the spectral display (and vice‑versa).
  - **`ScanDialog`** (JavaFX): frequency range (min/max with unit; presets: "current spectral view", "current tuner span", manual); a clear banner that turns red — "⚠ This range is wider than your SDR can see at once. Scanning it will retune the SDR and **interrupt decoding** for ~N min." — only when stepped; decoder set (checkbox list of primary decoders, defaults from preferences); survey dwell & threshold (advanced, collapsible); `maxSignalsToProbe`; continuous toggle + interval. `[ Scan ]` / `[ Cancel ]`.
- **Spectral display integration** (Swing): a `DiscoveryOverlay` layer renders each `Discovery` not yet added as a faint dashed rectangle at its frequency/width, colored by state (energy = grey, probing = pulsing, identified = the decoder's color at low alpha, unidentified = amber dashed). Hover → tooltip with the metadata. Click → focuses/scrolls the Discovery tab to that row. Added discoveries just become normal channel overlays. A `DiscoveryDisplay` ∈ {ALL, IDENTIFIED_ONLY, NONE} toggle in the existing "Display ▸ Channel" submenu controls visibility (default: IDENTIFIED_ONLY to avoid clutter).
- **Threading**: all engine work is off‑thread; UI updates marshalled onto the JavaFX thread (`Platform.runLater`) / EDT (`SwingUtilities.invokeLater`) respectively. The `DiscoveryModel` exposes JavaFX‑observable collections for the table and a plain `Listener<DiscoveryEvent>` for the Swing overlay.
- **Preferences** (new `DiscoveryPreference` under `preference/`): `survey.dwell` (3 s), `survey.threshold.db` (6 dB), `max.concurrent.probes` (2), `max.concurrent.classifications` (1), `tuner.headroom.channels` (0), `excluded.decoders` (empty), `default.scan.decoders` (all primary), `click.default.bandwidth.hz` (12500), `keep.listening.duration` (30 s), `discovery.overlay.display` (IDENTIFIED_ONLY), `probe.window.<decoder>` overrides (optional). The persisted **ignore list** lives in `DiscoveryPreference` too (a list of `{minHz,maxHz,note,addedAt}`), or — if it should travel with the playlist — a small new element in `PlaylistV2`. **Decision: store the ignore list in `DiscoveryPreference`** (it's operator/environment‑specific, like a squelch setting, not part of a shareable playlist).

## 10. Cross‑cutting concerns

- **Thread safety / EDT discipline**: engine never runs on EDT/JavaFX threads; all chain start/stop happens on engine threads; UI mutation marshalled back. `ProcessingChain` start/stop is already designed for off‑thread use by `ChannelProcessingManager`.
- **Resource cleanup**: every transient `ProcessingChain` → `stop()`+`dispose()`; every `TunerChannelSource` → `stop()` (one‑shot, auto‑releases the channelizer slot) — all in `finally`. A `ClassificationSession` `AutoCloseable` owns these so a single try‑with‑resources covers a request. On app shutdown / playlist switch, `BandScanController.shutdown()` cancels everything.
- **Tuner contention**: `getSource()==null` ⇒ surface "no tuner capacity / out of range" (don't silently fail); the scan pauses and retries with backoff (operator can stop it). Manual decoding always wins — the engine never pre‑empts an operator channel.
- **Disruption consent**: the only operation that retunes the SDR is a stepped sweep, and only after the operator confirms the explicit warning in `ScanDialog`. In‑band scans and all click‑to‑tune classifications use the polyphase channelizer's spare capacity and never move the SDR.
- **Playlist hygiene**: discoveries never auto‑enter the playlist; only `Add` does, producing a normal `STANDARD` channel. Click‑to‑tune does add+start immediately (that *is* the requested behaviour) — but it's one explicit operator gesture, and the channel is a normal editable/removable entry.
- **Error surfaces**: classification of one peak failing never aborts a scan (row → `ERROR`, continue). Decoder exceptions are logged and treated as `LockState.ERROR` for that candidate. A stepped‑sweep retune failure aborts the sweep, restores the tuner, reports it.
- **Performance**: probing is the cost. The energy gate (§5.3 step 2) skips dead frequencies cheaply; bandwidth‑ordering short‑circuits on a fast confident lock; scans probe sequentially. A 2 MHz in‑band scan with ~10 active signals ≈ tens of seconds. Stepped sweeps are minutes and clearly flagged.
- **i18n / style / headers**: follow existing conventions; GPL copyright header on every new file (see `copyright.template`); SLF4J logging; no new runtime dependencies.

## 11. New types — summary

`module/discovery/`: `SignalClassifier`, `ClassificationRequest`, `ClassificationResult`, `Candidate`, `LockState`, `Outcome`, `SignalKind`, `LockWatcher`, `ProbeChainFactory`, `ClassificationSession (AutoCloseable)`, `CandidateOrdering`, `SpectralSurvey`, `EnergyPeak`, `BandScanController`, `ScanRequest`, `ScanState`, `Discovery`, `DiscoveryState`, `DiscoveryModel`, `DiscoveryEvent`.
`spectrum/`: `ClickToTuneController`, `SelectionOverlay` (or additions to `OverlayPanel`), `DecoderOverrideChip`, `DiscoveryOverlay`, `PendingClassificationOverlay`; additions to `SpectralDisplayPanel.MouseEventProcessor` and its context menu; a small accessor for the tuner's wideband sample tap / `ComplexDftProcessor`.
`gui/discovery/` (or `gui/playlist/discovery/`): `DiscoveryEditor`, `DiscoveryTableView`/cell factories, `ScanDialog`, `ManageIgnoreListDialog`.
`preference/discovery/`: `DiscoveryPreference` (+ register in `UserPreferences`); reuse the existing preferences UI pattern for an editor pane.
Events: a `ClassifyAndTuneRequest` / `ShowDiscoveryRequest` on `MyEventBus` for loose coupling between the spectral window and the playlist window (mirroring the existing `ViewChannelRequest`).
Wiring: `DiscoveryModel`, `BandScanController`, `SignalClassifier` constructed in the same place the other top‑level managers are (near `PlaylistManager`/`ChannelProcessingManager`/`TunerManager` assembly), and handed to both UIs.

## 12. Testing strategy

The repo has minimal unit tests and **no in‑repo baseband recordings**, so:

- **Unit‑testable without RF** (the bulk of new logic): `CandidateOrdering` (bandwidth → decoder priority), `LockWatcher` state machine (feed it synthetic `DecoderStateEvent` sequences → assert `LOCKED`/`PARTIAL`/`NONE`, metadata extraction, control‑channel detection), `SpectralSurvey` peak finder (feed synthetic magnitude‑bin arrays with known peaks/noise floor → assert the `EnergyPeak`s, merging, thresholding), `BandScanController` orchestration (inject a fake `SignalClassifier` and fake `SpectralSurvey` → assert state transitions, ignore‑list filtering, known‑channel filtering, the `add`/`add‑all`/`watch`/`ignore` actions, continuous re‑survey), `Discovery` confidence bucketing, `DiscoveryPreference` persistence round‑trip, ignore‑list overlap math. Mock `TunerManager`/`ProcessingChain` interactions behind narrow interfaces so `SignalClassifier`'s probe loop (ordering, concurrency cap, timeouts, cancellation, cleanup‑in‑finally) is unit‑tested against fakes.
- **Integration test** (optional, gated): a `RecordingTuner` can play a `.wav` baseband file. If a small P25 / DMR / NBFM / noise sample is added to test resources (or pointed at via a system property), an integration test asserts the classifier returns the expected `DecoderType` / `NO_SIGNAL`. Kept out of the default `test` run if it needs large fixtures.
- **Manual test plan** (documented in the spec/PR): on a real SDR over a known P25 system — click‑detect the control channel, confirm traffic following; scan a 2 MHz span, confirm the control + active conventional channels appear; override a decoder via the chip; stepped sweep across a wide range, confirm the warning and tuner restore.
- **No regressions**: existing spectral‑display interactions (pan/zoom/right‑click) unchanged; new gestures are additive (Shift+drag, double‑click, new menu items).

## 13. Implementation phases

Each phase ends with `./gradlew build` green, the new unit tests passing, and a code‑review pass.

1. **Engine core** — `module/discovery` types; `SignalClassifier` + `ProbeChainFactory` + `LockWatcher` + `ClassificationSession` + `CandidateOrdering`; `DiscoveryPreference`; wiring into the manager assembly. Tests: ordering, lock watcher, classifier probe loop against fakes. *(No UI yet — verified by tests + a tiny throwaway main if needed.)*
2. **Click‑to‑tune** — `ClickToTuneController`; `SpectralDisplayPanel`/`MouseEventProcessor` additions (Shift+drag select, double‑click, "Decode here (auto‑detect)" + "Decode here as ▸" + "Scan this view…" + "Show discoveries" menu items); pending overlay; channel creation + start; `DecoderOverrideChip`. Tests: controller logic with a fake classifier; channel‑build correctness.
3. **Survey + scan** — `SpectralSurvey` (in‑band first), `BandScanController`, `Discovery`/`DiscoveryModel`, ignore‑list + known‑channel filtering, continuous mode. Tests: peak finder, orchestration, actions.
4. **Discovery UI** — `DiscoveryEditor` tab, table + cells, `ScanDialog`, `ManageIgnoreListDialog`; `DiscoveryOverlay` on the spectral display + the `DiscoveryDisplay` toggle; cross‑window events. (Light tests — UI.)
5. **Stepped sweep + polish** — wide‑range retune sweep in `SpectralSurvey`/`BandScanController` with the disruption warning + tuner restore; preferences editor pane; "keep listening 30 s" + `PARTIAL` offers; docs/manual‑test notes; final review.

## 14. Open decisions / assumptions (proceeding unless told otherwise)

- **Shift+drag = selection box** on the spectrum (plain drag stays pan); double‑click = classify at default width. *(If you'd rather plain‑drag‑over‑a‑signal select, say so.)*
- **Ignore list lives in `DiscoveryPreference`**, not the playlist.
- **Override chip shown only on click‑to‑tune‑created channels** initially (not every tuner channel).
- **Default candidate set = all `PRIMARY_DECODERS`**, AM + LTR/MPT1327/Passport ordered last; an exclusion preference exists for chronic false positives.
- **Click‑to‑tune adds + starts the channel immediately** (per the request); discoveries never auto‑add.
- **Stepped sweep is the only thing that retunes the SDR**, always behind an explicit confirmation.
- New code targets **upstream‑contribution quality**; no new runtime dependencies.
- These are the kind of choices I'll make as I go; I'll only stop for ones that genuinely fork the design.

---

## 15. Manual Test Plan (Phase 5 — Stepped Sweep & Polish)

These tests are designed for execution on a real SDR connected to an active RF environment.
They complement the automated unit-test suite, which covers all logic paths with fakes.
A P25 Phase 1 trunked system or a mix of NBFM/DMR conventional channels is ideal.

### 15.1  Click-to-tune — happy path

| Step | Expected |
|------|----------|
| 1. Open the spectral display while a known P25 control channel is visible as a peak. | — |
| 2. Shift+drag a selection box centred on the peak (or double-click it). | Pending overlay appears at that span. |
| 3. Wait ≤ 12 s for the classifier to lock. | Pending overlay clears; a channel strip appears at that frequency with decoder type "P25 Phase 1". |
| 4. Observe that trunked traffic channels begin to follow. | Traffic channels are added dynamically as calls are dispatched. |
| 5. Right-click the channel strip and choose "Decode here as… NBFM". | The channel's decoder switches to NBFM without creating a second channel. |
| 6. Choose "Re-detect" from the same menu. | Classifier runs again; if P25 wins, the channel switches back to P25 Phase 1. |

### 15.2  Click-to-tune — miss popup with "Keep listening"

| Step | Expected |
|------|----------|
| 1. Click a frequency where a very-low-duty-cycle or intermittent signal appears. | Classifier times out (12 s default) → miss popup appears. |
| 2. Note the popup title shows the outcome (e.g. "unidentified" or "no signal"). If the classifier saw any PARTIAL sync, the popup also shows "Start as &lt;decoder&gt;" button(s). | Popup text matches the outcome; PARTIAL buttons present only when applicable. |
| 3. Press "Keep listening". | A new classification starts; pending overlay reappears. The probing window is now `DiscoveryPreference.keepListeningSeconds` seconds (default 30 s), NOT 30 Hz bandwidth. |
| 4. If the signal becomes active, the classifier eventually locks. | Channel is created. |

### 15.3  Click-to-tune — PARTIAL offer in miss popup

| Step | Expected |
|------|----------|
| 1. Find a signal where e.g. P25 Phase 1 achieves brief sync but cannot sustain it (common on edge-of-range sites). | — |
| 2. Click-to-tune. When miss popup appears, observe "Start as P25 Phase 1" button. | Button text matches the decoder that reached PARTIAL. |
| 3. Press "Start as P25 Phase 1". | A P25 Phase 1 channel starts immediately at that frequency. |

### 15.4  In-band band scan (non-disruptive)

| Step | Expected |
|------|----------|
| 1. Open the Discovery tab (playlist window → "Discovery"). | Empty table. |
| 2. Press "Scan". In the ScanDialog, set min/max frequency within the current tuner's instantaneous bandwidth (no stepped sweep warning). | No "disruption" warning banner is shown. |
| 3. Press "Start". | Progress bar advances; table populates with detected signals. |
| 4. Confirm the current tuner's center frequency is unchanged throughout. | Center frequency in the spectral display remains stable. |
| 5. Press "Add" on an NBFM discovery. | A channel is added to the playlist and starts decoding immediately. |

### 15.5  Wide stepped sweep (disruptive)

| Step | Expected |
|------|----------|
| 1. In the ScanDialog, set min/max to a range wider than the tuner's instantaneous bandwidth (e.g. 140–170 MHz on an RTL-SDR). | A yellow warning banner appears: "Wide scan will retune the SDR…". |
| 2. Acknowledge the warning and press "Start". | The spectral display shows the tuner stepping in ~80% bandwidth strides across the span. Existing channels on other frequencies lose signal (expected — the tuner has been moved). |
| 3. After the sweep completes, confirm the tuner returns to its original center frequency. | Spectral display returns to the pre-scan view. Previously active channels resume. |
| 4. Cancel partway through a stepped sweep. | Tuner is immediately restored to the original center frequency. No channels remain permanently disrupted. |

### 15.6  Exclusion list and decoder filtering

| Step | Expected |
|------|----------|
| 1. Open Discovery Preferences, add "AM" to the excluded decoder list. | — |
| 2. Run a band scan over a range with known AM broadcasts. | AM signals may appear in the table with outcome UNIDENTIFIED (energy detected, no lock accepted). They do NOT appear as IDENTIFIED/AM because AM is excluded. |
| 3. Remove AM from the exclusion list and re-scan. | AM signals now identify correctly. |

### 15.7  Continuous re-scan

| Step | Expected |
|------|----------|
| 1. Start a band scan with "Continuous" enabled and a 2-minute interval. | After the first cycle completes, the table shows results and state transitions to "idle (continuous)". |
| 2. Wait for the interval to elapse. | A second survey begins automatically. Existing rows are refreshed (not duplicated). |
| 3. Mark one UNIDENTIFIED row as "watched". | On the next rescan, that row is re-probed even if the survey returns no energy at its frequency. |
| 4. Press "Stop". | Scanning halts; state returns to IDLE. |

---

## 16. Known Limitations and Future Work

### 16.1  Stepped sweep — implemented and wired

The stepped sweep is implemented in `SpectralSurvey.surveyWide()` and wired as an automatic
fallback in `BandScanController.runSurvey()`: when the in-band survey fails because the span
exceeds the tuner's instantaneous bandwidth, the controller retries automatically with
`surveyWide()` provided a `TunerControl` is available.

**How it works:** `TunerControlImpl` is constructed in `SDRTrunk` with a
`Supplier<TunerController>` bound to `SpectralDisplayPanel::getTunerController`.  This means
the stepped sweep always commands whichever tuner the spectral display is currently showing,
and `isAvailable()` returns `false` when no tuner is displayed (disabling the fallback).

**Known disruption:** when `surveyWide()` steps the tuner's center frequency, any
`TunerChannelSource` instances already allocated against that tuner will receive mistuned
samples for the duration of each step.  This is documented in the `ScanDialog` warning banner
("interrupts decoding on that tuner for the duration") and is expected behaviour.  The tuner
center frequency is restored unconditionally in a `finally` block (including on cancel/error).
Only the spectral display's currently-shown tuner is commandeered; other connected tuners are
unaffected.

**DC-spike avoidance (known limitation):** the stepped sweep allocates a channelizer channel
at each step's center frequency via `SurveySourceProvider.acquire()`.  On SDRs with a DC-spike
avoidance zone (`MiddleUnusableBandwidth` — e.g. RTL-SDR, RSP), the small region around each
step center is not surveyed because that frequency range maps to the channelizer's unusable
band.  A future redesign could tap the tuner's raw wideband buffer directly instead of going
through the channelizer, covering that region at the cost of higher complexity.

**Future work:** coordinate with `ChannelProcessingManager` to gracefully pause active
channels before stepping and resume them after restoration.  This would require a new
"suspend" lifecycle hook on `ProcessingChain`.

### 16.2  No pre-classifier for modulation type

Every candidate decoder is probed by actually running it against captured samples.  For a
span with many signals this is CPU-intensive and slow.  The candidate set is bounded by
`ScanRequest.maxSignalsToProbe` (default 200) and the decoder exclusion list.

**Future work:** add a cheap modulation pre-classifier (e.g. instantaneous frequency
variance for FM vs AM; symbol-rate estimation for digital modes) to prune the candidate
set before full probing.

### 16.3  No multi-tuner coordination

The band-scan controller uses a single `SpectralSurveyApi` instance.  It cannot split a
wide scan across multiple tuners or merge results from simultaneous in-band surveys on
different hardware.

**Future work:** implement a `MultiTunerSpectralSurvey` that parallelises the stepped
sweep across available tuners, significantly reducing scan time for wide spans.

### 16.4  ScanDialog owner window not set

`ScanDialog` is created without a parent `Stage` (`initOwner` not called) because threading
the `Stage` reference through `PlaylistEditor` → `DiscoveryEditor` → `ScanDialog` would
require invasive changes across the JavaFX presenter layer.  The dialog is therefore not
modal relative to the playlist window.

**Future work:** pass the owner `Stage` during construction by exposing it via a narrow
interface on `DiscoveryEditor`.

### 16.5  Click-to-tune miss popup is a blocking JOptionPane

The current miss popup uses `javax.swing.JOptionPane.showOptionDialog()`, which blocks the
EDT while open.  For the initial implementation this is acceptable because the popup is
short-lived and the operator is actively waiting.

**Future work:** replace with a non-blocking Swing or JavaFX notification panel that the
operator can dismiss at will without blocking other UI interactions.

### 16.6  Classifier probing is not aware of existing channels

The `BandScanController` skips peaks that overlap channels already in the channel model
(`isKnownChannel`), but click-to-tune classifies blindly.  If the operator clicks a
frequency already being decoded, a duplicate channel may be created.

**Future work:** in `ClickToTuneController.classifyAndTune()`, check `ChannelModel` for
an existing channel at the target frequency and offer to show/focus it instead of
creating a new one.
