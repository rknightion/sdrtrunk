# SDRTrunk

SDR (Software Defined Radio) trunking decoder and scanner application written in Java with a JavaFX GUI.

## Commands

```bash
# Run the application
./gradlew run

# Run tests
./gradlew test

# Build release for current OS
./gradlew runtimeZipCurrent   # output: build/image/sdr-trunk.zip

# Build releases for Linux + macOS
./gradlew runtimeZipOthers

# Build Windows release
./gradlew runtimeZipWindows

# Compile only
./gradlew compileJava
```

## Architecture

```
src/main/java/io/github/dsheirer/
├── gui/               JavaFX UI — SDRTrunk.java is the main entry point
│   └── playlist/      Playlist editor panels
│       └── discovery/ Band-scan dialog (ScanDialog.java)
├── module/
│   ├── discovery/     Signal discovery — spectral survey + classification pipeline
│   ├── decode/        Protocol decoders (P25, DMR, NBFM, etc.)
│   └── demodulate/    Demodulators
├── source/
│   └── tuner/         SDR hardware adapters (RTL-SDR, Airspy, HackRF, SDRplay, etc.)
├── channel/           Channel management
├── controller/        Playlist + channel processing
├── dsp/               DSP primitives (FFT, windowing, filters)
└── preference/        Persisted user preferences
```

## Key Files

- `gui/SDRTrunk.java` — application entry point, JavaFX app class
- `module/discovery/BandScanController.java` — orchestrates survey → probe → classify pipeline
- `module/discovery/SpectralSurvey.java` — FFT-based energy peak finder (wideband tap)
- `module/discovery/TunerControl.java` — seam interface for testable tuner access
- `module/discovery/SpectralSurveyApi.java` — seam interface for testable survey

## JDK Requirement

Requires **Bellsoft Liberica JDK 25** (or another JDK 25 that bundles JavaFX). Vanilla OpenJDK won't work — JavaFX modules are required. The build uses `--enable-preview` and `--add-modules=jdk.incubator.vector` for the Project Panama Vector API.

## Discovery Module (active development)

The `module/discovery` package implements signal-discovery (band scan):

1. **SpectralSurvey** — taps the tuner's raw wideband I/Q stream (bypasses the polyphase channelizer), runs FFT, masks the DC spike, finds energy peaks via power-weighted centroid.
2. **BandScanController** — drives the pipeline: survey → mark known channels → probe unknowns → update DiscoveryModel.
3. **TunerControl / TunerControlImpl** — narrow interface wrapping TunerController; fake implementations used in tests.

Two survey paths:
- **In-band** (non-disruptive): span ≤ tuner sample rate → no retuning.
- **Stepped** (disruptive): span > sample rate → retunes across span, restores original center in `finally`.

## Testing

```bash
./gradlew test
# Tests are JUnit 5 (Jupiter); discovery tests are under:
# src/test/java/io/github/dsheirer/module/discovery/
```

Tests for the discovery module use fake `TunerControl` and `SpectralSurveyApi` implementations — no real hardware needed.

## Gotchas

- **JavaFX thread rule**: all UI updates must go through `FxThreads.runOnFxThread(...)` or `Platform.runLater(...)`. Violations cause `IllegalStateException` at runtime, not compile time.
- **Preview + incubator flags**: the JVM args in `build.gradle` (`--enable-preview`, `--add-modules=jdk.incubator.vector`) must be passed to both the `run` task and tests — already wired in `build.gradle`, but IDEs may need them added manually.
- **No channelizer for wide scans**: `TunerManager.getSource()` allocates a DDC channel; this fails for spans wider than the narrowest polyphase filter. SpectralSurvey deliberately uses `addWidebandSampleListener` instead.
- **Epoch counter pattern**: `BandScanController` uses an `AtomicInteger` epoch to prevent stale async scan callbacks from clobbering a newer scan. Check epoch before writing to the model in any async callback.
