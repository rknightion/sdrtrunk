# Phase 1 API Notes — Signal Discovery Engine

Captured while reading the actual source files before implementing Phase 1.

---

## ProcessingChain (`module/ProcessingChain.java`)

**Constructor:** `new ProcessingChain(Channel channel, AliasModel aliasModel)`
- Auto-adds `SingleChannelState` (if `timeslotCount==1`) or `MultiChannelState`, plus `DecodeEventHistory` and `MessageHistory`.

**Key methods:**
- `addModule(Module m)` — wires the module by all its provider/listener interfaces automatically.
- `addModules(List<Module>)` — calls addModule for each.
- `setSource(Source)` — must be called before `start()`; throws if already processing.
- `start()` — broadcasts sample-rate and frequency SourceEvents; registers the source listener; starts all modules.
- `stop()` — removes+stops the source; stops+resets all modules.
- `dispose()` — calls `stop()`, then removes+disposes all modules and broadcasters.
- `removeEventLoggingModules()` — removes `EventLogger` instances.
- `removeRecordingModules()` — removes `ComplexSamplesWaveRecorder`, `MBECallSequenceRecorder`, `BinaryRecorder`.
- `removeTrafficChannelManager()` — removes `TrafficChannelManager` instances.
- `addDecoderStateEventListener(Listener<DecoderStateEvent>)` — registers an external listener on the decoder-state broadcaster.
- `getDecoderStateEventListener()` — returns the broadcaster (a `Listener<DecoderStateEvent>`).
- `broadcast(SourceEvent)` — sends a SourceEvent to all modules.

**Module auto-wiring in `addModule`:**
- `IComplexSamplesListener` → subscribed to `mBasebandComplexSamplesBroadcaster`
- `IDecoderStateEventListener` → subscribed to `mDecoderStateEventBroadcaster`
- `IDecoderStateEventProvider` → its output set to `mDecoderStateEventBroadcaster`
- `ISourceEventListener` → subscribed to `mSourceEventBroadcaster`
- ... etc. for audio, message, channel event interfaces.

**For probe chains:** Build a Channel with the target decode config → `new ProcessingChain(channel, aliasModel)` → `addModules(DecoderFactory.getPrimaryModules(...))` (not `getModules` — that also adds aux decoders) → call `removeEventLoggingModules()`, `removeRecordingModules()`, `removeTrafficChannelManager()`, also strip audio modules manually (AudioModule, DMRAudioModule, P25P1AudioModule, P25P2AudioModule which extend AbstractAudioModule/AudioModule).

---

## DecoderFactory (`module/decode/DecoderFactory.java`)

**Key methods:**
- `getModules(ChannelMapModel, Channel, AliasModel, UserPreferences, TrafficChannelManager, IChannelDescriptor)` — returns primary + aux decoders.
- `getPrimaryModules(ChannelMapModel, Channel, AliasModel, UserPreferences, TrafficChannelManager, IChannelDescriptor)` — returns just the primary decoder modules (no aux). TrafficChannelManager and IChannelDescriptor may be null.
- `getDecodeConfiguration(DecoderType)` — creates a default `DecodeConfiguration` for any primary decoder type.
- `getDefaultDecodeConfiguration()` — returns NBFM config.

**Note:** `getPrimaryModules` adds `AliasActionManager` as the first entry. It also adds `TrafficChannelManager` for DMR/P25/MPT1327 STANDARD channels, plus `AudioModule`/`DMRAudioModule`/`P25P1AudioModule`/`P25P2AudioModule` for all types. We need to strip audio modules after calling this.

**Audio module types to strip (for probe chains):**
- `AbstractAudioModule` (base class for all audio modules) — filter by `instanceof AbstractAudioModule`.
- `io.github.dsheirer.audio.AudioModule extends AbstractAudioModule` (NBFM, AM, LTR, Passport, MPT1327).
- `P25P1AudioModule`, `P25P2AudioModule`, `DMRAudioModule` all extend `AbstractAudioModule`.

---

## DecoderType (`module/decode/DecoderType.java`)

`PRIMARY_DECODERS = EnumSet.of(AM, DMR, LTR, LTR_NET, MPT1327, NBFM, P25_PHASE1, P25_PHASE2, PASSPORT)`

**Note:** `PRIMARY_DECODERS` is not `final` — it's mutable. EnumSet.copyOf should be used when iterating.

---

## DecodeConfiguration (`module/decode/config/DecodeConfiguration.java`)

Abstract. Key methods:
- `getDecoderType()` — abstract
- `getChannelSpecification()` — abstract, returns `ChannelSpecification`
- `getTimeslotCount()` — default 1; overridden by DMR (returns 2) and P25 Phase 2 (returns 2)

---

## ChannelSpecification (`source/tuner/channel/ChannelSpecification.java`)

`new ChannelSpecification(double minimumSampleRate, int bandwidth, double passFrequency, double stopFrequency)`

NBFM 12.5 kHz: `new ChannelSpecification(25000.0, 12500, 6000.0, 7000.0)`

---

## TunerManager (`source/tuner/manager/TunerManager.java`)

```java
public Source getSource(SourceConfiguration config, ChannelSpecification channelSpecification, String threadName)
    throws SourceException
```
- Returns `null` if no tuner can provide the source (not an exception — just null).
- Throws `SourceException` for actual errors.
- For TUNER type: extracts frequency from `SourceConfigTuner`, builds a `TunerChannel`, finds a tuner.

---

## SourceConfigTuner (`source/config/SourceConfigTuner.java`)

- Default constructor: `new SourceConfigTuner()` then `setFrequency(long)`.
- Or: `new SourceConfigTuner(TunerChannel)`.
- `getFrequency()`, `setFrequency(long)`, `getTunerChannel(int bandwidth)`.

---

## Source / ComplexSource

- `Source extends Module implements ISourceEventListener, ISourceEventProvider, IHeartbeatProvider`
- `getSampleType()` — returns `SampleType.COMPLEX` or `SampleType.REAL`
- `getSampleRate()`, `getFrequency()`
- `ComplexSource extends Source implements Provider<ComplexSamples>` — `setListener(Listener<ComplexSamples>)` (inherited from `Provider`)
- `Source.stop()` — called by `ProcessingChain.stop()` to release the channelizer slot.

**Fan-out approach:** The real `TunerChannelSource` is a `ComplexSource`. To fan out samples to multiple probe chains, we own it directly (not via a `ProcessingChain`), set ourselves as its listener, and re-broadcast `ComplexSamples` to each probe chain's `mBasebandComplexSamplesBroadcaster` via a thin wrapper. We also need to forward `SourceEvent`s (sample rate, frequency) to each chain's source-event broadcaster.

**Alternative simpler approach for fan-out:** Give each probe chain a `FanoutComplexSource` (a custom `ComplexSource` that registers to our `Broadcaster<ComplexSamples>`). Call `chain.setSource(fanoutSource)` for each probe chain — then `chain.start()` will broadcast sample rate/frequency events correctly. The `FanoutComplexSource` wraps the real source's sample rate and frequency.

---

## DecoderStateEvent (`channel/state/DecoderStateEvent.java`)

```java
new DecoderStateEvent(Object source, Event event, State state)
new DecoderStateEvent(Object source, Event event, State state, int timeslot)
new DecoderStateEvent(Object source, Event event, State state, int timeslot, long frequency)
DecoderStateEvent.stateNotification(State state, int timeslot) // static factory
```

**Event enum:** `CONTINUATION, DECODE, END, START, NOTIFICATION_CHANNEL_STATE, NOTIFICATION_CHANNEL_ACTIVE_STATE, NOTIFICATION_CHANNEL_INACTIVE_STATE, NOTIFICATION_SOURCE_FREQUENCY, REQUEST_*`

**No identifiers in DecoderStateEvent.** Identifiers (NAC, color code) come via a separate `IdentifierUpdateNotification` on the `IdentifierUpdateProvider/Listener` path. The `LockWatcher` should also implement `IdentifierUpdateListener` to harvest metadata — `getIdentifierUpdateListener()` returns a `Listener<IdentifierUpdateNotification>`.

---

## State (`channel/state/State.java`)

Enum: `ACTIVE, CALL, CONTROL, DATA, ENCRYPTED, FADE, IDLE, RESET, TEARDOWN`

- `SINGLE_CHANNEL_ACTIVE_STATES = EnumSet.of(ACTIVE, CALL, CONTROL, DATA, ENCRYPTED)`
- Decoder transitions out of IDLE → ACTIVE/CALL/CONTROL/DATA when decoding.
- `CONTROL` state = trunking control channel. Maps to `SignalKind.CONTROL`.
- `CALL`/`ACTIVE` = conventional or traffic. Maps to `CONVENTIONAL` or `TRAFFIC`.

---

## IDecoderStateEventListener (`channel/state/IDecoderStateEventListener.java`)

```java
public interface IDecoderStateEventListener {
    Listener<DecoderStateEvent> getDecoderStateListener();
}
```

Used by `ProcessingChain.addModule()` auto-wiring (subscribes the returned listener to the chain's decoder-state broadcaster). Also usable via `addDecoderStateEventListener(Listener<DecoderStateEvent>)` on the chain directly.

---

## IdentifierUpdateListener (`identifier/IdentifierUpdateListener.java`)

```java
public interface IdentifierUpdateListener {
    Listener<IdentifierUpdateNotification> getIdentifierUpdateListener();
}
```

Auto-wired by `ProcessingChain.addModule()` via `mIdentifierUpdateNotificationBroadcaster`. `LockWatcher` can implement this to harvest NAC, color code, etc. from `IdentifierUpdateNotification`.

---

## PowerMonitor (`dsp/squelch/PowerMonitor.java`)

- `process(float[] i, float[] q)` — call with ComplexSamples data; broadcasts `SourceEvent.channelPowerLevel(null, db)` roughly twice per second.
- `setSampleRate(int)` — adjusts broadcast frequency.
- `setSourceEventListener(Listener<SourceEvent>)` — registers a listener for power level events.
- **Not a Module** — must be wired manually. Listen to the real source's `ComplexSamples` output for the energy gate. Call `process(samples.i(), samples.q())` on each buffer.

---

## UserPreferences / Preference pattern

**Preference subclass pattern (from `CallManagementPreference`):**
```java
class MyPreference extends Preference {
    private static final String KEY = "some.key";
    private Preferences mPreferences = Preferences.userNodeForPackage(MyPreference.class);
    private Boolean mCachedValue;

    public MyPreference(Listener<PreferenceType> updateListener) { super(updateListener); }

    @Override public PreferenceType getPreferenceType() { return PreferenceType.MY_TYPE; }

    public boolean getValue() {
        if(mCachedValue == null) mCachedValue = mPreferences.getBoolean(KEY, DEFAULT);
        return mCachedValue;
    }
    public void setValue(boolean v) {
        mPreferences.putBoolean(KEY, v);
        mCachedValue = v;
        notifyPreferenceUpdated();
    }
}
```

**UserPreferences registration:**
1. Add a field: `private DiscoveryPreference mDiscoveryPreference;`
2. In `loadPreferenceTypes()`: `mDiscoveryPreference = new DiscoveryPreference(this::receive);`
3. Add getter: `public DiscoveryPreference getDiscoveryPreference() { return mDiscoveryPreference; }`
4. Add `DISCOVERY` to `PreferenceType` enum.

---

## Channel (`controller/channel/Channel.java`)

- Default constructor: `new Channel(String name)` — use `channel.setName(name)` or pass via constructor.
- Setters: `setDecodeConfiguration(DecodeConfiguration)`, `setSourceConfiguration(SourceConfiguration)`, `setAliasListName(String)`.
- Default decode config is NBFM; default source config is some default from factory.
- `getChannelType()` — defaults to `ChannelType.STANDARD`.
- `isStandardChannel()`, `isTrafficChannel()`.

---

## SDRTrunk.java — Bootstrap wiring

`mTunerManager` and `mPlaylistManager` are created around line 192-199:
```java
mTunerManager = new TunerManager(mUserPreferences);
mTunerManager.start();
// ...
mPlaylistManager = new PlaylistManager(mUserPreferences, mTunerManager, aliasModel, eventLogManager, mIconModel);
```

`mPlaylistManager.getChannelProcessingManager()` gives access to the channel processing manager.

Best place to construct `SignalClassifier`: after `mPlaylistManager` is created (it has the `AliasModel` and `ChannelMapModel` accessible via `mPlaylistManager.getAliasModel()` and `mPlaylistManager.getChannelMapModel()`).

**App shutdown:** `SDRTrunk` has a `WindowAdapter` that calls `shutdown()` — add executor shutdown there. Or add a JVM shutdown hook.

---

## Key deviations / decisions for implementation

1. **`DecoderFactory.getPrimaryModules` vs `getModules`**: Use `getPrimaryModules` (no aux decoders); still need to strip audio modules after calling it.
2. **Audio module stripping**: Filter by `instanceof AbstractAudioModule`.
3. **Fan-out**: Use a `ComplexSampleFanout` that owns the real `Source` and provides `FanoutSubscriberSource` (a `ComplexSource` wrapper) to each probe chain.
4. **`LockWatcher` also implements `IdentifierUpdateListener`** to harvest metadata (NAC, color code).
5. **Energy gate**: `PowerMonitor.process(i, q)` called manually on each `ComplexSamples` buffer during the gate phase.
6. **`SourceException`**: `TunerManager.getSource` can throw `SourceException` (not just return null). Catch this and treat as `ERROR` outcome.
