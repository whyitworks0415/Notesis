# Notesis Lab performance experiment

Based on the standard 0.90.20 release, this branch produces `com.notesis.lab`, installed beside the original app. It is
not a replacement package and must not be merged into the standard app as-is.
Export a copy of a note or backup from Notesis and import it into Lab for a fair
comparison. Keep display rate, brightness, pen, paper, and Light mode the same.
The Glass settings contain an explicit Efficient glass quality tradeoff; compare
both its enabled and disabled states.

The private source branch is `codex/performance-lab-0.90.20`. A separate repository
could not be created with the connected GitHub integration. APKs use pre-release
tags `lab-v<version>-<code>-preview` in the existing release repositories, with
`make_latest: false`. The trailing word makes the original application's numeric
release parser reject the tag. Lab's feed also rejects original release tags.
Automatic checks are off by default in Lab; manual Lab updates are available
without the original developer-code gate.

## Implementation

- Kotlin UI and AndroidX Ink stay in place. A small C++ BVH adds a second query
  backend to the existing page grid for 512–65,536 strokes. Smaller or unsupported
  cases use the original grid. Trees larger than 2,048 entries are built by the
  loader rather than a UI query. Appended strokes form a bounded incremental tail.
- Records, nodes and output are GC-owned direct buffers. JNI keeps no pointer
  after a call and copies no coordinate arrays per query. A bit set restores the
  document's drawing order, including after removals.
- Repeated viewport queries reuse their result until the page or geometry changes.
  Whiteboard drawing and erasing reuse bounds instead of querying native stroke
  geometry for every stroke on every frame.
- Abandoned geometry loads stop between strokes. Native geometry calls are not
  forcefully interrupted.
- Efficient glass uses one iOS surface pass and caps surface blur at 12dp. It is
  enabled by default and can be disabled to recover the original expression.
  Refresh-rate controls and note file formats are unchanged.

## Verification

Use JDK 17, Android platform 36, NDK 28.2.13676358 and CMake 3.22.1 for a release.
The host checks need Linux and a C++17 compiler. Robolectric debug fixtures use
JDK 21. Run from the repository root:

```sh
bash tools/performance-lab/check-native.sh
./gradlew testDebugUnitTest -Pnotesis.hostNativeDir="$PWD/build/host-native"
./gradlew assembleRelease
```

The native script tests the exact shipped kernel under address/undefined-behavior
sanitizers, checks 4,540 randomized queries against brute force, checks inclusive
edges and invalid capacities, then builds a host JNI library. The JVM tests call
that library, verify direct-buffer results and invalid-buffer rejection, and
check cancellation, update-channel separation and existing UI behavior.

`build/host-native/benchmark.txt` compares the C++ BVH with a C++ linear scan over
the same cached rectangles. Both use synthetic data on the build host. It is an
algorithm comparison, **not a Kotlin-vs-C++ speedup or Android frame/battery
measurement**. Initial tree-building time is reported separately. The scenario
is deliberately spatially sparse; gains vary with document and viewport coverage.

The Lab version of `apk.yml` is manual-only and gates signing/publication on the
native checks, full release unit suite, and selected debug UI/JNI checks. It uses
the existing deployment signing workflow and verifies the isolated application
ID before signing. The public pre-release includes its host benchmark output.
Actual device installation, stylus/rendering fidelity, 60/120Hz frame times,
AGSL appearance, sustained heat and battery use still require tablet comparison.
