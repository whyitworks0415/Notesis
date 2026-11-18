#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUTPUT="$ROOT/build/host-native"
mkdir -p "$OUTPUT"
SOURCE="$ROOT/app/src/main/cpp"
CXX="${CXX:-g++}"
COMMON=(-ffp-contract=off -std=c++17 -Wall -Wextra -Werror -I"$SOURCE")
"$CXX" "${COMMON[@]}" -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer \
  "$SOURCE/spatial_index.cpp" "$ROOT/tools/performance-lab/spatial_test.cpp" -o "$OUTPUT/spatial-sanitized"
"$OUTPUT/spatial-sanitized" > "$OUTPUT/sanitized.txt"
"$CXX" "${COMMON[@]}" -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer \
  "$SOURCE/erase_geometry.cpp" "$ROOT/tools/performance-lab/erase_test.cpp" -o "$OUTPUT/erase-sanitized"
"$OUTPUT/erase-sanitized" >> "$OUTPUT/sanitized.txt"
"$CXX" "${COMMON[@]}" -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer \
  "$SOURCE/erase_geometry.cpp" "$SOURCE/continuous_erase.cpp" "$ROOT/tools/performance-lab/continuous_erase_test.cpp" -o "$OUTPUT/continuous-sanitized"
"$OUTPUT/continuous-sanitized" >> "$OUTPUT/sanitized.txt"
"$CXX" "${COMMON[@]}" -O3 "$SOURCE/erase_geometry.cpp" "$SOURCE/continuous_erase.cpp" \
  "$ROOT/tools/performance-lab/continuous_erase_test.cpp" -o "$OUTPUT/erase-comparison"
"$OUTPUT/erase-comparison" benchmark | tee "$OUTPUT/erase-comparison.txt"
"$CXX" "${COMMON[@]}" -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer \
  "$SOURCE/ink_batch.cpp" "$ROOT/tools/performance-lab/ink_batch_test.cpp" -o "$OUTPUT/ink-batch-sanitized"
"$OUTPUT/ink-batch-sanitized" >> "$OUTPUT/sanitized.txt"
"$CXX" "${COMMON[@]}" -O3 "$SOURCE/ink_batch.cpp" "$ROOT/tools/performance-lab/ink_batch_test.cpp" -o "$OUTPUT/ink-batch-bench"
"$OUTPUT/ink-batch-bench" benchmark | tee "$OUTPUT/ink-batch-benchmark.txt"
"$CXX" "${COMMON[@]}" -O3 "$SOURCE/spatial_index.cpp" "$ROOT/tools/performance-lab/spatial_test.cpp" -o "$OUTPUT/spatial-bench"
"$OUTPUT/spatial-bench" | tee "$OUTPUT/benchmark.txt"
JNI_INCLUDE="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}/include"
if [[ ! -f "$JNI_INCLUDE/jni.h" ]]; then
  JNI_INCLUDE="$OUTPUT/jni"
  mkdir -p "$JNI_INCLUDE"
  cp "${ANDROID_HOME:?}/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/jni.h" "$JNI_INCLUDE/jni.h"
fi
"$CXX" "${COMMON[@]}" -O3 -fPIC -shared -I"$JNI_INCLUDE" -I"$JNI_INCLUDE/linux" \
  "$SOURCE/spatial_index.cpp" "$SOURCE/erase_geometry.cpp" "$SOURCE/continuous_erase.cpp" "$SOURCE/spatial_jni.cpp" -o "$OUTPUT/libnotesis_spatial.so"
